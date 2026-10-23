import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Kotlin/Native exports every public declaration to Swift unless an entry-point allowlist says
 * otherwise (`-Xbinary=objcExportEntryPointsPath`). This writes that allowlist.
 *
 * Kept: the iOS bridge (iosMain / iosFull / iosAppStore) and any other public type Swift names,
 * plus subclasses of those types so overridden properties still dispatch.
 * Dropped: everything else, including the performance package, so image compression and the other
 * perf helpers never become Swift entry points. Kotlin code reachable from the kept entry points
 * stays in the binary; only the unused Swift/ObjC thunks are eligible for removal.
 */
object SwiftExportEntryPoints {
    const val PERFORMANCE_PACKAGE = "com.nuvio.app.core.perf"

    /**
     * Swift calls preferredTabName and the other route properties on the runtime instance.
     * Kotlin/Native 2.4.10 does not expand entry points to overrides, so every route type has to
     * be listed or the base getter is what Swift sees.
     */
    val navigationRouteEntryPoints = listOf(
        "AppRoute",
        "SettingsDestinationRoute",
        "TabsRoute",
        "DetailRoute",
        "PersonDetailRoute",
        "EntityBrowseRoute",
        "SettingsPageRoute",
        "HomescreenSettingsRoute",
        "MetaScreenSettingsRoute",
        "ContinueWatchingSettingsRoute",
        "PartyRoute",
        "DownloadsRoute",
        "DownloadShowRoute",
        "DownloadsSettingsRoute",
        "DownloadsStorageRoute",
        "AddonsSettingsRoute",
        "PluginsSettingsRoute",
        "AccountSettingsRoute",
        "SupportersContributorsSettingsRoute",
        "LicensesAttributionsSettingsRoute",
        "WhatsNewSettingsRoute",
        "ProfileEditRoute",
        "CollectionsRoute",
        "CollectionEditorRoute",
        "CollectionEditorPageRoute",
        "FolderDetailRoute",
        "StreamRoute",
        "CatalogRoute",
        "PlayerRoute",
    ).map { "callable com.nuvio.app.navigation.$it.*" }

    val requiredEntryPoints = listOf(
        "function com.nuvio.app.MainViewController",
        "function com.nuvio.app.ScreenViewController",
        "function com.nuvio.app.AppGateViewController",
        "function com.nuvio.app.disposeRoute",
        "function com.nuvio.app.core.deeplink.handleAppUrl",
        "function com.nuvio.app.core.i18n.localizedPlayerPictureInPicture",
        "function com.nuvio.app.core.ui.publishProfileTabIconFrame",
        "function com.nuvio.app.core.ui.nativeTabVisibilityChanged",
        "function com.nuvio.app.core.ui.observeNativePopToRoot",
        "function com.nuvio.app.core.ui.observeNativeTabBarVisible",
        "function com.nuvio.app.core.ui.nativeTabSelect",
        "function com.nuvio.app.features.downloads.handleDownloadsBackgroundEvents",
        "function com.nuvio.app.features.downloads.prepareDownloadsForAppBackground",
        "function com.nuvio.app.features.downloads.pauseDownloadsForAppBackground",
        "callable com.nuvio.app.features.player.NuvioPlayerBridge.*",
        "callable com.nuvio.app.features.player.NuvioPlayerBridgeCreator.*",
        "callable com.nuvio.app.features.player.NuvioPlayerBridgeFactory.*",
        "callable com.nuvio.app.features.player.seekpreview.NuvioSeekPreviewGenerator.*",
        "callable com.nuvio.app.features.player.seekpreview.NuvioSeekPreviewGeneratorCreator.*",
        "callable com.nuvio.app.features.player.seekpreview.NuvioSeekPreviewRegistry.*",
        "callable com.nuvio.app.features.analysis.NuvioMediaAnalyzerBridge.*",
        "callable com.nuvio.app.features.analysis.NuvioMediaAnalyzerRegistry.*",
        "callable com.nuvio.app.features.downloads.NuvioFileReleaseBridge.*",
        "callable com.nuvio.app.features.downloads.NuvioFileReleaseRegistry.*",
        "callable com.nuvio.app.core.logging.InAppLogBridge.*",
        "callable com.nuvio.app.features.player.AudioEnergySample.*",
    ) + navigationRouteEntryPoints

    fun generate(kotlinRoots: List<File>, swiftRoots: List<File>): String {
        val swiftNames = swiftIdentifiers(swiftRoots)
        val parsed = kotlinRoots.flatMap { root ->
            if (!root.exists()) {
                emptyList()
            } else {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { parseFile(it) }.toList()
            }
        }
        val included = select(parsed, swiftNames)
        val lines = linkedSetOf<String>()
        included.filterIsInstance<TypeDecl>().forEach { lines += "callable ${it.fqName}.*" }
        included.filterIsInstance<CallableDecl>().forEach { decl ->
            val kind = if (decl.isProperty) "property" else "function"
            lines += "$kind ${decl.fqName}"
        }
        val sorted = lines.sorted()
        val missing = requiredEntryPoints.filter { it !in lines }
        require(missing.isEmpty()) {
            "Swift export allowlist is missing the bridge Swift calls: ${missing.joinToString()}"
        }
        require(sorted.none { PERFORMANCE_PACKAGE in it || "compactArtworkUrl" in it || "TmdbImageSizing" in it }) {
            "Performance code leaked into the Swift export allowlist"
        }
        require(sorted.none { it.startsWith("callable com.nuvio.app.features.home.HomeRepository") }) {
            "Swift export allowlist kept commonMain types Swift never calls"
        }
        val typeCount = parsed.filterIsInstance<TypeDecl>().size
        val exportedTypes = included.filterIsInstance<TypeDecl>().size
        require(exportedTypes < typeCount) {
            "Swift export allowlist kept every public type ($exportedTypes); the trim did not apply"
        }
        return buildString {
            appendLine("// Generated by generateSwiftExportEntryPoints. Kotlin/Native entry points exposed to Swift.")
            appendLine("// Public types: $exportedTypes of $typeCount. Performance package is excluded.")
            sorted.forEach { appendLine(it) }
        }
    }

    internal fun select(parsed: List<Decl>, swiftNames: Set<String>): List<Decl> {
        val types = parsed.filterIsInstance<TypeDecl>()
        val included = mutableSetOf<String>()
        fun keep(type: TypeDecl) {
            if (type.exported && !type.excluded) included += type.fqName
        }
        types.filter { it.bridgeFile }.forEach(::keep)
        types.filter { it.simpleName in swiftNames }.forEach(::keep)
        var grew = true
        while (grew) {
            grew = false
            val includedSimple = types.filter { it.fqName in included }.map { it.simpleName }.toSet()
            for (type in types) {
                if (type.fqName in included || type.excluded || !type.exported) continue
                val parentIncluded = type.supertypes.any { it in includedSimple }
                val enclosingIncluded = type.enclosingFq?.let { it in included } == true
                if (parentIncluded || enclosingIncluded) {
                    included += type.fqName
                    grew = true
                }
            }
        }
        val callables = parsed.filterIsInstance<CallableDecl>().filter { decl ->
            decl.exported && !decl.excluded && (decl.bridgeFile || decl.simpleName in swiftNames)
        }
        return types.filter { it.fqName in included } + callables
    }
}

internal sealed interface Decl {
    val fqName: String
    val simpleName: String
    val bridgeFile: Boolean
    val excluded: Boolean
}

internal data class TypeDecl(
    override val fqName: String,
    override val simpleName: String,
    override val bridgeFile: Boolean,
    override val excluded: Boolean,
    val exported: Boolean,
    val enclosingFq: String?,
    val supertypes: List<String>,
) : Decl

internal data class CallableDecl(
    override val fqName: String,
    override val simpleName: String,
    override val bridgeFile: Boolean,
    override val excluded: Boolean,
    val exported: Boolean,
    val isProperty: Boolean,
) : Decl

internal fun swiftIdentifiers(roots: List<File>): Set<String> {
    val names = HashSet<String>()
    val pattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    roots.forEach { root ->
        if (!root.exists()) return@forEach
        root.walkTopDown().filter { it.isFile && it.extension == "swift" }.forEach { file ->
            pattern.findAll(maskNonCode(file.readText())).forEach { names += it.value }
        }
    }
    return names
}

internal fun parseFile(file: File): List<Decl> = parseSource(file.readText(), file.path)

internal fun parseSource(source: String, path: String): List<Decl> {
    val text = maskNonCode(source)
    val bridgeFile = "/iosMain/" in path || "/iosFull/" in path || "/iosAppStore/" in path
    var packageName = ""
    var i = 0
    var depth = 0
    var paren = 0
    var ready = true
    val classes = ArrayDeque<OpenClass>()
    val decls = mutableListOf<Decl>()

    fun bodyDepth() = classes.lastOrNull()?.bodyDepth ?: 0
    fun atTypeScope() = paren == 0 && depth == bodyDepth()
    fun excluded(fq: String) = fq == SwiftExportEntryPoints.PERFORMANCE_PACKAGE ||
        fq.startsWith(SwiftExportEntryPoints.PERFORMANCE_PACKAGE + ".") ||
        "/core/perf/" in path

    while (i < text.length) {
        val c = text[i]
        if (c == '\n') {
            if (paren == 0 && depth == bodyDepth()) ready = true
            i++
            continue
        }
        if (c.isWhitespace()) {
            i++
            continue
        }
        if (atTypeScope() && ready && c == '@') {
            i = skipAnnotation(text, i)
            continue
        }
        if (atTypeScope() && ready && c.isJavaIdentifierStart()) {
            val word = readIdentifier(text, i)
            val next = word.second
            when (word.first) {
                "package" -> {
                    val name = readQualified(text, skipSpace(text, next))
                    packageName = name.first
                    i = name.second
                    ready = false
                    continue
                }
                "import" -> {
                    i = skipToNewline(text, next)
                    continue
                }
                in MODIFIERS -> {
                    i = next
                    continue
                }
                "class", "interface", "object", "enum" -> {
                    if (word.first == "enum" && peekIdentifier(text, next) != "class") {
                        ready = false
                        i = next
                        continue
                    }
                    val parsed = parseType(
                        text,
                        if (word.first == "enum") skipSpace(text, next) else i,
                        packageName,
                        classes,
                        bridgeFile,
                        ::excluded,
                    )
                    decls += parsed.first
                    i = parsed.second.index
                    if (parsed.second.opened) {
                        depth++
                        classes.addLast(OpenClass(depth, parsed.first.exported, parsed.first.fqName))
                    }
                    ready = parsed.second.opened
                    continue
                }
                "fun", "val", "var" -> {
                    if (word.first == "fun" && peekIdentifier(text, next) == "interface") {
                        val parsed = parseType(text, skipSpace(text, next), packageName, classes, bridgeFile, ::excluded)
                        decls += parsed.first
                        i = parsed.second.index
                        if (parsed.second.opened) {
                            depth++
                            classes.addLast(OpenClass(depth, parsed.first.exported, parsed.first.fqName))
                        }
                        ready = parsed.second.opened
                        continue
                    }
                    if (classes.isEmpty() && headerVisibility(text, i) == "public") {
                        val named = readCallableName(text, next)
                        if (named != null) {
                            val fq = if (packageName.isEmpty()) named.first else "$packageName.${named.first}"
                            decls += CallableDecl(
                                fqName = fq,
                                simpleName = named.first,
                                bridgeFile = bridgeFile,
                                excluded = excluded(fq),
                                exported = true,
                                isProperty = word.first != "fun",
                            )
                        }
                    }
                    ready = false
                    i = next
                    continue
                }
            }
        }
        when (c) {
            '{' -> depth++
            '}' -> {
                depth--
                if (classes.lastOrNull()?.bodyDepth == depth + 1) classes.removeLast()
                if (paren == 0 && depth == bodyDepth()) ready = true
            }
            '(' -> paren++
            ')' -> paren--
            '=' -> if (paren == 0 && depth == bodyDepth()) ready = false
        }
        i++
    }
    return decls
}

private data class OpenClass(val bodyDepth: Int, val exported: Boolean, val fqName: String)

private data class ParseCursor(val index: Int, val depth: Int, val paren: Int, val opened: Boolean)

private fun parseType(
    text: String,
    keywordAt: Int,
    packageName: String,
    classes: ArrayDeque<OpenClass>,
    bridgeFile: Boolean,
    excluded: (String) -> Boolean,
): Pair<TypeDecl, ParseCursor> {
    var i = skipSpace(text, keywordAt)
    val keyword = readIdentifier(text, i)
    i = skipSpace(text, keyword.second)
    var name = peekIdentifier(text, i)
    if (keyword.first == "object" && (name == null || name in MODIFIERS || name == "class")) {
        name = "Companion"
    } else if (name != null && name !in CONTROL_WORDS) {
        i = readIdentifier(text, skipSpace(text, i)).second
    } else {
        name = "Companion"
    }
    var depth = 0
    var paren = 0
    var angle = 0
    val header = StringBuilder()
    var opened = false
    while (i < text.length) {
        val c = text[i]
        if ((c == '{' || c == '}') && paren == 0 && angle == 0) {
            if (c == '{') {
                opened = true
                i++
            }
            break
        }
        if (c == '\n' && paren == 0 && angle == 0) {
            // The next declaration often starts after a blank line and an annotation
            // (`: SettingsDestinationRoute` then `@Serializable`). Stop before that,
            // or the supertype token keeps the annotation and the subtype is dropped.
            var lookAt = i + 1
            while (lookAt < text.length && text[lookAt].isWhitespace()) lookAt++
            if (lookAt >= text.length || text[lookAt] == '@' || peekIdentifier(text, lookAt) in NEXT_DECLARATION) {
                break
            }
        }
        when (c) {
            '(' -> paren++
            ')' -> paren--
            '<' -> angle++
            '>' -> if (angle > 0) angle--
        }
        header.append(c)
        i++
    }
    val enclosing = classes.lastOrNull()?.fqName
    val owner = enclosing ?: packageName
    val fq = if (owner.isEmpty()) name!! else "$owner.$name"
    val outerExported = classes.lastOrNull()?.exported ?: true
    val visibility = headerVisibility(text, keywordAt)
    val decl = TypeDecl(
        fqName = fq,
        simpleName = name!!,
        bridgeFile = bridgeFile,
        excluded = excluded(fq),
        exported = outerExported && visibility == "public",
        enclosingFq = enclosing,
        supertypes = supertypeNames(header.toString()),
    )
    return decl to ParseCursor(i, depth, 0, opened)
}

private fun headerVisibility(text: String, keywordAt: Int): String {
    var i = keywordAt
    var visibility = "public"
    while (i > 0) {
        i--
        val c = text[i]
        if (c == '\n' || c == '{' || c == '}' || c == ';') break
        if (c.isJavaIdentifierStart()) {
            var start = i
            while (start > 0 && text[start - 1].isJavaIdentifierPart()) start--
            when (text.substring(start, i + 1)) {
                "internal", "private", "protected" -> visibility = text.substring(start, i + 1)
                "public" -> visibility = "public"
            }
            i = start
        }
    }
    return visibility
}

private fun supertypeColon(header: String): Int {
    var paren = 0
    var angle = 0
    header.forEachIndexed { index, c ->
        when (c) {
            '(' -> paren++
            ')' -> paren--
            '<' -> angle++
            '>' -> if (angle > 0) angle--
            ':' -> if (paren == 0 && angle == 0) return index
        }
    }
    return -1
}

private fun supertypeNames(header: String): List<String> {
    val colon = supertypeColon(header)
    if (colon < 0) return emptyList()
    var rest = header.substring(colon + 1)
    val where = Regex("""\bwhere\b""").find(rest)
    if (where != null) rest = rest.substring(0, where.range.first)
    val parts = mutableListOf<String>()
    var current = StringBuilder()
    var paren = 0
    var angle = 0
    for (c in rest) {
        when {
            c == ',' && paren == 0 && angle == 0 -> {
                parts += current.toString()
                current = StringBuilder()
            }
            c == '(' -> {
                paren++
                current.append(c)
            }
            c == ')' -> {
                paren--
                current.append(c)
            }
            c == '<' -> {
                angle++
                current.append(c)
            }
            c == '>' -> {
                if (angle > 0) angle--
                current.append(c)
            }
            else -> current.append(c)
        }
    }
    parts += current.toString()
    return parts.mapNotNull { part ->
        val line = part.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val bare = line
            .substringBefore(" by ")
            .substringBefore('(')
            .substringBefore('<')
            .substringBefore('@')
            .trim()
            .substringAfterLast('.')
            .trim()
            .takeWhile { it.isJavaIdentifierPart() }
        bare.takeIf { it.isNotEmpty() && it[0].isJavaIdentifierStart() && it !in CONTROL_WORDS }
    }
}

/** Name of a top-level fun/val/var, skipping a receiver (`fun Color.brightened` -> brightened). */
private fun readCallableName(text: String, at: Int): Pair<String, Int>? {
    var i = at
    while (i < text.length && text[i].isWhitespace()) i++
    if (i < text.length && text[i] == '<') {
        i = skipAngles(text, i)
        while (i < text.length && text[i].isWhitespace()) i++
    }
    var name: String? = null
    var end = i
    while (i < text.length && text[i].isJavaIdentifierStart()) {
        val ident = readIdentifier(text, i)
        if (ident.first in CONTROL_WORDS) return null
        name = ident.first
        end = ident.second
        i = ident.second
        while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i++
        if (i < text.length && text[i] == '<') {
            i = skipAngles(text, i)
            while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i++
        }
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i++
            continue
        }
        break
    }
    val resolved = name ?: return null
    return resolved to end
}

private fun skipAngles(text: String, at: Int): Int {
    if (at >= text.length || text[at] != '<') return at
    var angle = 0
    var i = at
    while (i < text.length) {
        when (text[i]) {
            '<' -> angle++
            '>' -> {
                angle--
                i++
                if (angle == 0) return i
                continue
            }
        }
        i++
    }
    return i
}

internal fun maskNonCode(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        if (isRawStringOpen(source, i)) {
            // The opener is three quotes. A longer run keeps the extra quotes as content
            // (`""""pattern""""`), and the closer is the last three quotes of the next run.
            i = maskRawString(out, source, i)
            continue
        }
        if (source.startsWith("//", i)) {
            val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
            appendMasked(out, source, i, end)
            i = end
            continue
        }
        if (source.startsWith("/*", i)) {
            val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
            appendMasked(out, source, i, end)
            i = end
            continue
        }
        if (source[i] == '"') {
            out.append(' ')
            i++
            while (i < source.length) {
                if (source[i] == '\\') {
                    out.append(' ')
                    if (i + 1 < source.length) {
                        out.append(if (source[i + 1] == '\n') '\n' else ' ')
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (source[i] == '"') {
                    out.append(' ')
                    i++
                    break
                }
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
            continue
        }
        if (source[i] == '\'') {
            out.append(' ')
            i++
            if (i < source.length && source[i] == '\\') {
                out.append(' ')
                i++
            }
            if (i < source.length) {
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
            if (i < source.length && source[i] == '\'') {
                out.append(' ')
                i++
            }
            continue
        }
        out.append(source[i])
        i++
    }
    return out.toString()
}

private fun isRawStringOpen(source: String, at: Int): Boolean =
    at + 2 < source.length && source[at] == '"' && source[at + 1] == '"' && source[at + 2] == '"'

private fun maskRawString(out: StringBuilder, source: String, openAt: Int): Int {
    var i = openAt + 3
    while (i < source.length) {
        if (source[i] == '"') {
            var end = i
            while (end < source.length && source[end] == '"') end++
            if (end - i >= 3) {
                appendMasked(out, source, openAt, end)
                return end
            }
            i = end
            continue
        }
        i++
    }
    appendMasked(out, source, openAt, source.length)
    return source.length
}

private fun appendMasked(out: StringBuilder, source: String, start: Int, end: Int) {
    for (j in start until end) out.append(if (source[j] == '\n') '\n' else ' ')
}

private fun skipAnnotation(text: String, at: Int): Int {
    var i = at + 1
    if (i < text.length && text[i].isJavaIdentifierStart()) i = readIdentifier(text, i).second
    while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i++
    if (i < text.length && text[i] == '.') {
        return skipAnnotation(text, i)
    }
    if (i < text.length && text[i] == '(') {
        var paren = 1
        i++
        while (i < text.length && paren > 0) {
            when (text[i]) {
                '(' -> paren++
                ')' -> paren--
            }
            i++
        }
    }
    return i
}

private fun readIdentifier(text: String, at: Int): Pair<String, Int> {
    var i = at
    while (i < text.length && text[i].isJavaIdentifierPart()) i++
    return text.substring(at, i) to i
}

private fun readQualified(text: String, at: Int): Pair<String, Int> {
    var i = skipSpace(text, at)
    val start = i
    while (i < text.length && (text[i].isJavaIdentifierPart() || text[i] == '.')) i++
    return text.substring(start, i).trim('.') to i
}

private fun peekIdentifier(text: String, at: Int): String? {
    val i = skipSpace(text, at)
    if (i >= text.length || !text[i].isJavaIdentifierStart()) return null
    return readIdentifier(text, i).first
}

private fun skipSpace(text: String, at: Int): Int {
    var i = at
    while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i++
    return i
}

private fun skipToNewline(text: String, at: Int): Int {
    val next = text.indexOf('\n', at)
    return if (next < 0) text.length else next
}

private val MODIFIERS = setOf(
    "public", "private", "internal", "protected", "abstract", "open", "override", "expect", "actual",
    "external", "data", "sealed", "annotation", "value", "inner", "companion", "operator", "infix",
    "inline", "tailrec", "suspend", "const", "lateinit", "final", "crossinline", "noinline",
    "vararg", "reified", "inner",
)

private val CONTROL_WORDS = MODIFIERS + setOf("class", "interface", "object", "fun", "val", "var", "constructor", "where")

private val NEXT_DECLARATION = setOf(
    "class", "fun", "val", "var", "interface", "object", "typealias", "import",
    "public", "private", "internal", "protected", "override", "expect", "actual", "data", "sealed", "enum",
)

abstract class GenerateSwiftExportEntryPointsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinRoots: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val swiftRoots: ConfigurableFileCollection

    @get:OutputFile
    abstract val entryPointsFile: RegularFileProperty

    @TaskAction
    fun writeAllowlist() {
        val text = SwiftExportEntryPoints.generate(kotlinRoots.files.toList(), swiftRoots.files.toList())
        val output = entryPointsFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(text)
        logger.lifecycle("Swift export allowlist: ${text.lineSequence().count { it.startsWith("callable") || it.startsWith("function") || it.startsWith("property") }} entry points -> ${output.path}")
    }
}

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
 * Kept: the iOS bridge types (iosMain / iosFull / iosAppStore), any other public type or top-level
 * callable Swift names, subclasses of those types, and public types in the signature of a member
 * Swift names (so a returned data class still has its properties). Interfaces are listed member
 * by member, with explicit accessor lines for their properties. A `callable
 * Interface.*` wildcard also matches the fake `equals` override, and Kotlin/Native then emits
 * that as a required `equals(other:)` which Swift implementors do not have.
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
    val navigationRouteSimpleNames = listOf(
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
    )

    /** The members `AppRoute` declares; Swift reads each of them through the `AppRoute` protocol. */
    val appRouteMemberNames = listOf(
        "preferredTabName",
        "navigationIdentity",
        "hidesNavigationBar",
        "title",
        "subtitle",
    )

    /**
     * Route interfaces that extend `AppRoute`. Kotlin/Native only treats a member as an override of
     * the `AppRoute` one when the intermediate interface's copy of it is exposed too. A copy left
     * out of the allowlist makes every class below that interface declare its own base `title`,
     * `subtitle`, `hidesNavigationBar` and `navigationIdentity` instead of implementing the
     * `AppRoute` protocol's, so every member has to be listed on each of them.
     */
    val appRouteSubInterfaceSimpleNames = listOf("SettingsDestinationRoute")

    val navigationRouteEntryPoints =
        appRouteMemberNames.flatMap { interfaceProperty("com.nuvio.app.navigation.AppRoute", it, mutable = false) } +
            appRouteSubInterfaceSimpleNames.flatMap { owner ->
                appRouteMemberNames.flatMap { interfaceProperty("com.nuvio.app.navigation.$owner", it, mutable = false) }
            } +
            navigationRouteSimpleNames
                .filter { it != "AppRoute" && it !in appRouteSubInterfaceSimpleNames }
                .map { "callable com.nuvio.app.navigation.$it.*" }

    /** Any.equals / hashCode / toString must not be listed on a protocol Swift implements. */
    private val anyMethodNames = setOf("equals", "hashCode", "toString")

    /**
     * Kotlin/Native matches a property's accessors as functions named `<get-x>` / `<set-x>`, so
     * `property I.x` alone leaves the getter unexposed. A class exported with `callable C.*` then
     * has a getter that looks like a base method while its property overrides an exposed one, and
     * the framework link fails with `bridgePropertyType` "Assertion failed".
     */
    fun interfaceProperty(ownerFq: String, name: String, mutable: Boolean): List<String> =
        listOfNotNull(
            "property $ownerFq.$name",
            "function $ownerFq.<get-$name>",
            "function $ownerFq.<set-$name>".takeIf { mutable },
        )

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
        "function com.nuvio.app.features.player.NuvioPlayerBridge.loadFile",
        "function com.nuvio.app.features.player.NuvioPlayerBridgeCreator.createBridge",
        "callable com.nuvio.app.features.player.NuvioPlayerBridgeFactory.*",
        "function com.nuvio.app.features.player.seekpreview.NuvioSeekPreviewGenerator.frameJpeg",
        "function com.nuvio.app.features.player.seekpreview.NuvioSeekPreviewGeneratorCreator.createGenerator",
        "callable com.nuvio.app.features.player.seekpreview.NuvioSeekPreviewRegistry.*",
        "function com.nuvio.app.features.analysis.NuvioMediaAnalyzerBridge.analyze",
        "callable com.nuvio.app.features.analysis.NuvioMediaAnalyzerRegistry.*",
        "function com.nuvio.app.features.downloads.NuvioFileReleaseBridge.releaseFile",
        "callable com.nuvio.app.features.downloads.NuvioFileReleaseRegistry.*",
        "callable com.nuvio.app.core.logging.InAppLogBridge.*",
        "callable com.nuvio.app.features.player.AudioEnergySample.*",
        "callable com.nuvio.app.features.player.PcmCapturePlan.*",
        "callable com.nuvio.app.features.profiles.PinVerifyResult.*",
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
        val sorted = entryPointLines(included)
        val lines = sorted.toSet()
        val interfaceNames = included.filterIsInstance<TypeDecl>().filter { it.isInterface }.map { it.fqName }.toSet()
        require(sorted.none { line ->
            line.startsWith("callable ") && line.removePrefix("callable ").removeSuffix(".*") in interfaceNames
        }) { "Interface wildcard would export Any.equals as a required Swift protocol method" }
        require(sorted.none { line ->
            val owner = line.substringAfter(' ').substringBeforeLast('.')
            owner in interfaceNames && line.substringAfterLast('.') in anyMethodNames
        }) { "Any.equals/hashCode/toString leaked onto an exported protocol" }
        val withoutGetter = sorted.filter { line ->
            line.startsWith("property ") && line.substringAfter(' ').substringBeforeLast('.') in interfaceNames &&
                "function ${line.substringAfter(' ').substringBeforeLast('.')}.<get-${line.substringAfterLast('.')}>" !in lines
        }
        require(withoutGetter.isEmpty()) {
            "Interface properties without their getter entry point break the framework link: ${withoutGetter.joinToString()}"
        }
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

    internal fun entryPointLines(included: List<Decl>): List<String> {
        val lines = linkedSetOf<String>()
        val interfaces = included.filterIsInstance<TypeDecl>().filter { it.isInterface }
        fun inheritedMembers(type: TypeDecl, seen: MutableSet<String> = mutableSetOf()): List<MemberDecl> =
            type.supertypes.mapNotNull { name -> interfaces.firstOrNull { it.simpleName == name } }
                .filter { seen.add(it.fqName) }
                .flatMap { parent -> parent.members + inheritedMembers(parent, seen) }
        included.filterIsInstance<TypeDecl>().forEach { type ->
            if (type.isInterface) {
                // An interface that re-inherits a member is only seen by Kotlin/Native as overriding
                // the parent's when its own copy is exposed as well (see appRouteSubInterfaceSimpleNames).
                (type.members + inheritedMembers(type)).filter { it.exported && it.simpleName !in anyMethodNames }.forEach { member ->
                    if (member.isProperty) {
                        lines += interfaceProperty(type.fqName, member.simpleName, member.isMutable)
                    } else {
                        lines += "function ${type.fqName}.${member.simpleName}"
                    }
                }
            } else {
                lines += "callable ${type.fqName}.*"
            }
        }
        included.filterIsInstance<CallableDecl>().forEach { decl ->
            val kind = if (decl.isProperty) "property" else "function"
            lines += "$kind ${decl.fqName}"
        }
        return lines.sorted()
    }

    internal fun select(parsed: List<Decl>, swiftNames: Set<String>): List<Decl> {
        val types = parsed.filterIsInstance<TypeDecl>()
        val included = mutableSetOf<String>()
        fun keep(type: TypeDecl) {
            if (type.exported && !type.excluded) included += type.fqName
        }
        types.filter { it.bridgeFile }.forEach(::keep)
        // Swift spells a nested type through its outer type, which pulls it in below.
        types.filter { it.enclosingFq == null && it.simpleName in swiftNames }.forEach(::keep)
        fun callableKept(decl: CallableDecl) =
            decl.exported && !decl.excluded && decl.simpleName in swiftNames
        val keptCallables = parsed.filterIsInstance<CallableDecl>().filter(::callableKept)
        var grew = true
        while (grew) {
            grew = false
            val includedTypes = types.filter { it.fqName in included }
            val includedSimple = includedTypes.map { it.simpleName }.toSet()
            // Swift can only reach a returned or callback type through a member it names.
            val referenced = HashSet<String>()
            includedTypes.forEach { type ->
                type.members.filter { it.exported && it.simpleName in swiftNames }.forEach { referenced += it.referencedTypes }
            }
            keptCallables.forEach { referenced += it.referencedTypes }
            for (type in types) {
                if (type.fqName in included || type.excluded || !type.exported) continue
                val parentIncluded = type.supertypes.any { it in includedSimple }
                val enclosingIncluded = type.enclosingFq?.let { it in included } == true
                val usedInSignature = type.simpleName in referenced
                if (parentIncluded || enclosingIncluded || usedInSignature) {
                    included += type.fqName
                    grew = true
                }
            }
        }
        return types.filter { it.fqName in included } + keptCallables
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
    val isInterface: Boolean = false,
    val members: MutableList<MemberDecl> = mutableListOf(),
) : Decl

internal data class MemberDecl(
    val simpleName: String,
    val isProperty: Boolean,
    val exported: Boolean,
    val referencedTypes: List<String>,
    val isMutable: Boolean = false,
)

internal data class CallableDecl(
    override val fqName: String,
    override val simpleName: String,
    override val bridgeFile: Boolean,
    override val excluded: Boolean,
    val exported: Boolean,
    val isProperty: Boolean,
    val referencedTypes: List<String> = emptyList(),
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
                        classes.addLast(openClass(depth, parsed.first))
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
                            classes.addLast(openClass(depth, parsed.first))
                        }
                        ready = parsed.second.opened
                        continue
                    }
                    val named = readCallableName(text, next)
                    if (named != null && headerVisibility(text, i) == "public") {
                        val refs = signatureTypeNames(text, named.second)
                        val owner = classes.lastOrNull()
                        if (owner == null) {
                            val fq = if (packageName.isEmpty()) named.first else "$packageName.${named.first}"
                            decls += CallableDecl(
                                fqName = fq,
                                simpleName = named.first,
                                bridgeFile = bridgeFile,
                                excluded = excluded(fq),
                                exported = true,
                                isProperty = word.first != "fun",
                                referencedTypes = refs,
                            )
                        } else {
                            owner.members += MemberDecl(
                                simpleName = named.first,
                                isProperty = word.first != "fun",
                                exported = true,
                                referencedTypes = refs,
                                isMutable = word.first == "var",
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

private data class OpenClass(
    val bodyDepth: Int,
    val exported: Boolean,
    val fqName: String,
    val members: MutableList<MemberDecl>,
)

private fun openClass(depth: Int, decl: TypeDecl) =
    OpenClass(depth, decl.exported, decl.fqName, decl.members)

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
        isInterface = keyword.first == "interface",
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
        // Modifiers are soft keywords, so `val value` and `fun open()` are ordinary names here.
        if (ident.first in HARD_KEYWORDS) return null
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

private val TYPE_NAME = Regex("""\b[A-Z][A-Za-z0-9_]*""")

private fun typeNamesIn(text: String): List<String> =
    TYPE_NAME.findAll(text).map { it.value }.distinct().toList()

/** Type names in a callable signature, stopping before the body. */
private fun signatureTypeNames(text: String, at: Int): List<String> = typeNamesIn(signatureSlice(text, at))

private fun signatureSlice(text: String, at: Int): String {
    var paren = 0
    var angle = 0
    val buf = StringBuilder()
    var i = at
    while (i < text.length) {
        val c = text[i]
        if (paren == 0 && angle == 0 && (c == '{' || c == '=' || c == '}')) break
        if (c == '\n' && paren == 0 && angle == 0) {
            var lookAt = i + 1
            while (lookAt < text.length && text[lookAt].isWhitespace()) lookAt++
            if (
                lookAt >= text.length ||
                text[lookAt] == '@' ||
                text[lookAt] == '}' ||
                peekIdentifier(text, lookAt) in NEXT_DECLARATION
            ) {
                break
            }
        }
        when (c) {
            '(' -> paren++
            ')' -> if (paren > 0) paren--
            '<' -> angle++
            '>' -> if (angle > 0) angle--
        }
        buf.append(c)
        i++
    }
    return buf.toString()
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

private val HARD_KEYWORDS = setOf("class", "interface", "object", "fun", "val", "var")

private val CONTROL_WORDS = MODIFIERS + HARD_KEYWORDS + setOf("constructor", "where")

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

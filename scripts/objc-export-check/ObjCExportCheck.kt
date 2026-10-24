@file:OptIn(
    org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi::class,
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.K1Deprecation::class,
)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

import org.jetbrains.kotlin.backend.konan.descriptors.contributedMethods
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCEntryPoints
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportHeaderGenerator
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportMapper
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportNamer
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportNamerImpl
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportProblemCollector
import org.jetbrains.kotlin.backend.konan.objcexport.getBaseMethods
import org.jetbrains.kotlin.backend.konan.objcexport.isBaseMethod
import org.jetbrains.kotlin.backend.konan.objcexport.readObjCEntryPoints
import org.jetbrains.kotlin.backend.konan.objcexport.shouldBeExposed
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoots
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.nativeBinaryOptions.UnitSuspendFunctionObjCExport
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs the Kotlin/Native ObjC export over composeApp on a non-Mac host: header translation plus
 * the selector/bridge pass that `linkReleaseFrameworkIosArm64` runs (where an inconsistent
 * allowlist fails with `bridgePropertyType` "Assertion failed"). Library types are unresolved
 * here, so this checks composeApp declarations only.
 *
 * args: <entry-points.txt> <header out dir> <iosApp dir> <classpath> <source roots...>
 */
fun main(args: Array<String>) {
    val entryPointsFile = args[0]
    val outDir = File(args[1]).apply { mkdirs() }
    val swiftRoot = File(args[2])
    val classpath = args[3].split(':').filter { it.isNotBlank() }.map(::File)
    val roots = args.drop(4)

    val disposable = Disposer.newDisposable()
    val conf = CompilerConfiguration()
    conf.put(CommonConfigurationKeys.MODULE_NAME, "composeApp")
    conf.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    conf.put(JVMConfigurationKeys.JDK_HOME, File(System.getProperty("java.home")))
    conf.languageVersionSettings = LanguageVersionSettingsImpl(
        LanguageVersion.KOTLIN_1_9,
        ApiVersion.KOTLIN_1_9,
        emptyMap(),
        mapOf(
            LanguageFeature.MultiPlatformProjects to LanguageFeature.State.ENABLED,
            LanguageFeature.ContextReceivers to LanguageFeature.State.ENABLED,
        ),
    )
    conf.addJvmClasspathRoots(classpath)
    conf.addKotlinSourceRoots(roots)
    val env = KotlinCoreEnvironment.createForProduction(disposable, conf, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    val module = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
        env.project,
        env.getSourceFiles(),
        NoScopeRecordCliBindingTrace(env.project),
        conf,
        env::createPackagePartProvider,
    ).moduleDescriptor

    val fromFile = org.jetbrains.kotlin.konan.file.File(entryPointsFile).readObjCEntryPoints()
    val allowlist = object : ObjCEntryPoints {
        override fun shouldBeExposed(descriptor: CallableMemberDescriptor): Boolean =
            try {
                fromFile.shouldBeExposed(descriptor)
            } catch (_: IllegalStateException) {
                // Unresolved library declarations have no fully-qualified name on this host.
                false
            }
    }
    val everything = object : ObjCEntryPoints {
        override fun shouldBeExposed(descriptor: CallableMemberDescriptor) = descriptor.module == module
    }

    val problems = mutableListOf<String>()
    val filteredBases = mutableMapOf<String, MutableSet<BaseMethod>>()
    val unfilteredBases = mutableMapOf<String, MutableSet<BaseMethod>>()
    val header = export(module, allowlist, File(outDir, "ComposeApp.h"), problems, filteredBases)
    val unfiltered = export(module, everything, File(outDir, "ComposeApp-unfiltered.h"), mutableListOf(), unfilteredBases)
    val swiftText = swiftRoot.walkTopDown().filter { it.isFile && it.extension == "swift" }.joinToString("\n") { it.readText() }
    val swiftIdentifiers = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(swiftText).map { it.value }.toSet()

    val implemented = Regex("""class\s+\w+\s*:\s*NSObject\s*,([^{]+)\{""").findAll(swiftText)
        .flatMap { match -> match.groupValues[1].split(',').map { it.trim() } }
        .toSet()
    for (protocol in implemented) {
        val required = header[protocol]?.required ?: continue
        val anyMethods = required.filter { it.substringBefore('(') in setOf("equals", "hashCode", "toString") }
        if (anyMethods.isNotEmpty()) problems += "Swift implements $protocol but the header requires $anyMethods"
        val missing = unfiltered.getValue(protocol).required - required -
            setOf("equals(other:)", "hashCode()", "toString()")
        if (missing.isNotEmpty()) problems += "Swift implements $protocol but the header drops $missing"
    }
    for ((name, type) in unfiltered) {
        if (name.substringBefore('.') !in swiftIdentifiers || name.endsWith("Kt") || type.protocol) continue
        val missing = type.members - (header[name]?.members ?: emptySet())
        if (missing.isNotEmpty()) problems += "Swift names $name but the header drops $missing"
    }

    // A class member that implements an exported interface's member in the full export must not turn
    // into a base method of the class in the trimmed one: Swift reads it through the protocol, and
    // the class would then carry a second, differently bridged copy of it.
    for ((className, bases) in filteredBases) {
        val full = unfilteredBases[className] ?: continue
        val fromInterface = full.filter { it.ownerIsInterface && it.accessorName in header[it.owner]?.members.orEmpty() }
            .map { it.name }.toSet()
        val redeclared = bases.filter { !it.ownerIsInterface && it.name in fromInterface && it.name !in anyMemberNames }
        if (redeclared.isNotEmpty()) {
            problems += "$className declares ${redeclared.map { it.name }.sorted()} itself in the trimmed export " +
                "but implements them from an interface in the full one; list them on the intermediate interface"
        }
    }

    Disposer.dispose(disposable)
    problems.forEach { println("FAIL $it") }
    println("ObjC export check: ${header.size} types, ${problems.size} problems")
    if (problems.isNotEmpty()) exitProcess(2)
}

private val anyMemberNames = setOf("equals", "hashCode", "toString")

private data class BaseMethod(val name: String, val owner: String, val ownerIsInterface: Boolean) {
    val accessorName: String get() = name.removePrefix("<get-").removeSuffix(">")
}

private class HeaderType(val protocol: Boolean) {
    val members = mutableSetOf<String>()
    val required = mutableSetOf<String>()
}

private fun export(
    module: ModuleDescriptor,
    entryPoints: ObjCEntryPoints,
    headerFile: File,
    problems: MutableList<String>,
    classBases: MutableMap<String, MutableSet<BaseMethod>>,
): Map<String, HeaderType> {
    val mapper = ObjCExportMapper(
        local = false,
        unitSuspendFunctionExport = UnitSuspendFunctionObjCExport.DEFAULT,
        entryPoints = entryPoints,
    )
    val collector = ObjCExportProblemCollector.SILENT
    val namer = ObjCExportNamerImpl(
        object : ObjCExportNamer.Configuration {
            override val topLevelNamePrefix = "ComposeApp"
            override fun getAdditionalPrefix(module: ModuleDescriptor): String? =
                if (module.name.asString() == "<composeApp>") null else "Ext"
            override val objcGenerics = true
        },
        module.builtIns,
        mapper,
        collector,
        local = false,
    )
    val generator = ObjCExportHeaderGenerator.createInstance(
        moduleDescriptors = listOf(module),
        mapper = mapper,
        namer = namer,
        problemCollector = collector,
        objcGenerics = true,
        objcExportBlockExplicitParameterNames = false,
        shouldExportKDoc = false,
        additionalImports = emptyList(),
    )
    generator.translateModule()
    val exported = generator.buildInterface()
    headerFile.writeText(exported.headerLines.joinToString("\n"))

    // Same walk as ObjCExportCodeSpec.createCodeSpec in the framework link.
    fun bridge(owner: String, method: FunctionDescriptor) {
        try {
            check(mapper.isBaseMethod(method)) { "not a base method" }
            namer.getSelector(method)
            mapper.bridgeMethod(method)
        } catch (t: Throwable) {
            val property = (method as? PropertyAccessorDescriptor)?.correspondingProperty
            val overrides = property?.overriddenDescriptors?.joinToString { "${it.containingDeclaration.name}.${it.name}" }
            problems += "$owner.${method.name}: ${t.javaClass.simpleName} ${t.message.orEmpty()} (overrides $overrides)"
        }
    }
    fun bridgeAll(owner: String, declarations: List<CallableMemberDescriptor>) {
        declarations.forEach { decl ->
            when (decl) {
                is PropertyDescriptor -> {
                    decl.getter?.let { bridge(owner, it) }
                    decl.setter?.takeIf { mapper.shouldBeExposed(it) }?.let { bridge(owner, it) }
                }
                is FunctionDescriptor -> bridge(owner, decl)
                else -> Unit
            }
        }
    }
    exported.topLevel.values.forEach { bridgeAll("<top-level>", it) }
    exported.categoryMembers.forEach { (cls, members) -> bridgeAll(cls.name.asString(), members) }
    exported.generatedClasses.forEach { descriptor ->
        val owner = descriptor.name.asString()
        val bases = descriptor.contributedMethods.filter { mapper.shouldBeExposed(it) }
            .flatMap { mapper.getBaseMethods(it) }.distinct()
        bases.forEach { bridge(owner, it) }
        if (descriptor.kind != ClassKind.INTERFACE) {
            classBases.getOrPut(owner) { mutableSetOf() } += bases.map { base ->
                val baseOwner = base.containingDeclaration as? org.jetbrains.kotlin.descriptors.ClassDescriptor
                BaseMethod(base.name.asString(), baseOwner?.name?.asString().orEmpty(), baseOwner?.kind == ClassKind.INTERFACE)
            }
        }
        if (descriptor.kind != ClassKind.INTERFACE) {
            descriptor.constructors.filter { mapper.shouldBeExposed(it) }.forEach { bridge(owner, it) }
        }
    }
    return parseHeader(exported.headerLines)
}

private fun parseHeader(lines: List<String>): Map<String, HeaderType> {
    val swiftName = Regex("""swift_name\("([^"]+)"\)""")
    val types = mutableMapOf<String, HeaderType>()
    var pending: String? = null
    var current: HeaderType? = null
    var required = true
    for (raw in lines) {
        val line = raw.trim()
        if (current == null) {
            if (line.startsWith("__attribute__((swift_name(")) pending = swiftName.find(line)?.groupValues?.get(1)
            val opens = (line.startsWith("@interface") || line.startsWith("@protocol")) && !line.endsWith(";")
            if (opens && pending != null && !line.substringBefore(':').contains('(')) {
                current = types.getOrPut(pending) { HeaderType(protocol = line.startsWith("@protocol")) }
                required = true
            }
            if (opens) pending = null
            continue
        }
        when {
            line == "@end" -> current = null
            line == "@required" -> required = true
            line == "@optional" -> required = false
            line.startsWith("-") || line.startsWith("+") || line.startsWith("@property") -> {
                val name = swiftName.find(line)?.groupValues?.get(1) ?: continue
                current.members += name
                if (current.protocol && required) current.required += name
            }
        }
    }
    return types
}

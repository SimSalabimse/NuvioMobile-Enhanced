import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftExportEntryPointsTest {
    @Test
    fun keepsTheBridgeAndDropsPerformanceAndUnreferencedTypes() {
        val common = """
            package demo
            internal class Hidden { class AlsoHidden }
            class Parent
            class Child : Parent()
            class Unused
            fun usedBySwift() {}
            fun unused() {}
            internal fun compactArtworkUrl() {}
        """.trimIndent()
        val ios = """
            package demo.ios
            fun bridgeOnly() {}
            class Bridge
        """.trimIndent()
        val perf = """
            package com.nuvio.app.core.perf
            class TmdbImageSizing
            fun compactArtworkUrl(url: String) {}
        """.trimIndent()
        val multiline = """
            package demo
            data class PlayerRoute(
                val id: String,
            ) : Parent
        """.trimIndent()
        val parsed = parseSource(common, "src/commonMain/kotlin/Demo.kt") +
            parseSource(ios, "src/iosMain/kotlin/Bridge.kt") +
            parseSource(perf, "src/commonMain/kotlin/com/nuvio/app/core/perf/Image.kt") +
            parseSource(multiline, "src/commonMain/kotlin/Player.kt")
        val selected = SwiftExportEntryPoints.select(parsed, setOf("Parent", "usedBySwift", "Child"))
        val names = selected.map { it.simpleName }.toSet()
        assertTrue(names.contains("Parent"))
        assertTrue(names.contains("Child"))
        assertTrue("PlayerRoute" in names)
        assertTrue("usedBySwift" in names)
        assertFalse("bridgeOnly" in names)
        assertTrue("Bridge" in names)
        assertFalse("Unused" in names)
        assertFalse("unused" in names)
        assertFalse("Hidden" in names)
        assertFalse("AlsoHidden" in names)
        assertFalse("TmdbImageSizing" in names)
        assertFalse("compactArtworkUrl" in names)
    }

    @Test
    fun parsesRouteSubtypes() {
        val parsed = parseFile(java.io.File("../composeApp/src/commonMain/kotlin/com/nuvio/app/navigation/Routes.kt"))
        val home = parsed.filterIsInstance<TypeDecl>().single { it.simpleName == "HomescreenSettingsRoute" }
        check(home.supertypes == listOf("SettingsDestinationRoute")) { home.toString() }
        check(home.exported) { home.toString() }
        val tabs = parsed.filterIsInstance<TypeDecl>().single { it.simpleName == "TabsRoute" }
        check(tabs.supertypes == listOf("AppRoute")) { tabs.toString() }
        val selected = SwiftExportEntryPoints.select(parsed, setOf("AppRoute")).map { it.simpleName }.toSet()
        val expected = SwiftExportEntryPoints.navigationRouteSimpleNames
        check(selected == expected.toSet()) { "selected=$selected expected=$expected" }
    }

    @Test
    fun namesTheCallableRatherThanItsReceiver() {
        val source = """
            package demo
            fun Color.brightenedDominant(fallback: Color): Color = fallback
            fun String.isValidAvatarUrl(): Boolean = true
            val List<String>.firstItem: String get() = this[0]
            private fun String.hidden(): String = this
            class Parent
            @Serializable
            data object TabsRoute : Parent

            @Serializable
            data class HomescreenSettingsRoute(override val title: String = "") : Parent
        """.trimIndent()
        val parsed = parseSource(source, "src/commonMain/kotlin/Demo.kt")
        val names = parsed.map { it.simpleName }.toSet()
        assertTrue("brightenedDominant" in names)
        assertTrue("isValidAvatarUrl" in names)
        assertTrue("firstItem" in names)
        assertFalse("Color" in names)
        assertFalse("String" in names)
        assertFalse("hidden" in names)
        val selected = SwiftExportEntryPoints.select(parsed, setOf("Parent")).map { it.simpleName }.toSet()
        assertTrue("TabsRoute" in selected)
        assertTrue("HomescreenSettingsRoute" in selected)
        assertFalse("brightenedDominant" in selected)
    }

    @Test
    fun realSourcesDoNotParseReceiverTypesAsCallables() {
        val root = java.io.File("../composeApp/src")
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            parseFile(file).filterIsInstance<CallableDecl>().filter { it.simpleName == "Color" || it.simpleName == "String" || it.simpleName == "first" }.forEach {
                bad += "${file.path} ${it.fqName}"
            }
        }
        check(bad.isEmpty()) { bad.joinToString("\n") }
    }

    @Test
    fun interfaceMembersOmitEqualsAndSwiftNamedSignaturesKeepTheirTypes() {
        val source = """
            package demo
            interface Bridge {
                fun loadFile(url: String)
                override fun equals(other: Any?): Boolean
            }
            class Engine {
                fun plan(): Sample
                fun diagnostics(): Report
            }
            data class Sample(val energy: Double)
            data class Report(val text: String)
            class Unused
        """.trimIndent()
        val parsed = parseSource(source, "src/commonMain/kotlin/Demo.kt")
        val selected = SwiftExportEntryPoints.select(parsed, setOf("Bridge", "Engine", "plan", "loadFile"))
        val names = selected.map { it.simpleName }.toSet()
        assertTrue("Sample" in names)
        assertFalse("Report" in names)
        assertFalse("Unused" in names)
        val lines = SwiftExportEntryPoints.entryPointLines(selected)
        assertTrue("function demo.Bridge.loadFile" in lines)
        assertFalse(lines.any { it.endsWith(".equals") || it.startsWith("callable demo.Bridge") })
        assertTrue("callable demo.Sample.*" in lines)
        assertFalse("callable demo.Report.*" in lines)
    }

    @Test
    fun interfacePropertiesListTheirAccessors() {
        val source = """
            package demo
            interface Route {
                val title: String
                var subtitle: String?
                fun open()
            }
            class HomeRoute : Route {
                override val title: String = ""
                override var subtitle: String? = null
                override fun open() {}
            }
        """.trimIndent()
        val parsed = parseSource(source, "src/commonMain/kotlin/Demo.kt")
        val lines = SwiftExportEntryPoints.entryPointLines(SwiftExportEntryPoints.select(parsed, setOf("Route")))
        assertTrue("property demo.Route.title" in lines)
        assertTrue("function demo.Route.<get-title>" in lines)
        assertFalse("function demo.Route.<set-title>" in lines)
        assertTrue("property demo.Route.subtitle" in lines)
        assertTrue("function demo.Route.<get-subtitle>" in lines)
        assertTrue("function demo.Route.<set-subtitle>" in lines)
        assertTrue("function demo.Route.open" in lines)
        assertTrue("callable demo.HomeRoute.*" in lines)
    }

    @Test
    fun subInterfacesExposeTheMembersTheyInherit() {
        val source = """
            package demo
            interface Route {
                val title: String?
                    get() = null
                val hidesBar: Boolean
                    get() = false
                fun open()
            }
            interface SettingsRoute : Route {
                val tab: String
                    get() = "Settings"
            }
            class AccountRoute : SettingsRoute
        """.trimIndent()
        val parsed = parseSource(source, "src/commonMain/kotlin/Demo.kt")
        val lines = SwiftExportEntryPoints.entryPointLines(SwiftExportEntryPoints.select(parsed, setOf("Route")))
        listOf("title", "hidesBar").forEach { name ->
            assertTrue("property demo.SettingsRoute.$name" in lines)
            assertTrue("function demo.SettingsRoute.<get-$name>" in lines)
        }
        assertTrue("function demo.SettingsRoute.open" in lines)
        assertTrue("function demo.SettingsRoute.<get-tab>" in lines)
        assertFalse("function demo.Route.<get-tab>" in lines)
    }

    @Test
    fun everyAppRouteInterfaceExposesEveryAppRouteMember() {
        val text = SwiftExportEntryPoints.generate(
            listOf(
                java.io.File("../composeApp/src/commonMain/kotlin"),
                java.io.File("../composeApp/src/iosMain/kotlin"),
                java.io.File("../composeApp/src/iosFull/kotlin"),
                java.io.File("../composeApp/src/iosAppStore/kotlin"),
            ),
            listOf(java.io.File("../iosApp")),
        )
        val lines = text.lines().toSet()
        val routes = parseFile(java.io.File("../composeApp/src/commonMain/kotlin/com/nuvio/app/navigation/Routes.kt"))
            .filterIsInstance<TypeDecl>()
        val subInterfaces = routes.filter { it.isInterface && "AppRoute" in it.supertypes }
        assertTrue(subInterfaces.isNotEmpty())
        assertTrue(
            "update SwiftExportEntryPoints.appRouteSubInterfaceSimpleNames",
            subInterfaces.map { it.simpleName }.toSet() == SwiftExportEntryPoints.appRouteSubInterfaceSimpleNames.toSet(),
        )
        val missing = subInterfaces.flatMap { type ->
            SwiftExportEntryPoints.appRouteMemberNames.flatMap { member ->
                listOf(
                    "property ${type.fqName}.$member",
                    "function ${type.fqName}.<get-$member>",
                )
            }
        }.filter { it !in lines }
        assertTrue("sub-interface members missing from the allowlist: $missing", missing.isEmpty())
    }

    @Test
    fun realAllowlistExportsPlansWithoutProtocolEquals() {
        val text = SwiftExportEntryPoints.generate(
            listOf(
                java.io.File("../composeApp/src/commonMain/kotlin"),
                java.io.File("../composeApp/src/iosMain/kotlin"),
                java.io.File("../composeApp/src/iosFull/kotlin"),
                java.io.File("../composeApp/src/iosAppStore/kotlin"),
            ),
            listOf(java.io.File("../iosApp")),
        )
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("//") }
        assertTrue("callable com.nuvio.app.features.player.PcmCapturePlan.*" in lines)
        assertTrue("callable com.nuvio.app.features.profiles.PinVerifyResult.*" in lines)
        assertTrue("function com.nuvio.app.features.player.NuvioPlayerBridge.loadFile" in lines)
        assertTrue("function com.nuvio.app.features.player.seekpreview.NuvioSeekPreviewGenerator.frameJpeg" in lines)
        assertTrue("function com.nuvio.app.features.downloads.NuvioFileReleaseBridge.releaseFile" in lines)
        assertTrue("function com.nuvio.app.features.analysis.NuvioMediaAnalyzerBridge.analyze" in lines)
        assertTrue("property com.nuvio.app.navigation.AppRoute.preferredTabName" in lines)
        assertTrue("function com.nuvio.app.navigation.AppRoute.<get-preferredTabName>" in lines)
        assertTrue("function com.nuvio.app.navigation.SettingsDestinationRoute.<get-preferredTabName>" in lines)
        assertTrue("callable com.nuvio.app.navigation.HomescreenSettingsRoute.*" in lines)
        assertFalse(lines.any { it == "callable com.nuvio.app.features.player.NuvioPlayerBridge.*" })
        assertFalse(lines.any { it.endsWith(".equals") || it.endsWith(".hashCode") || it.endsWith(".toString") })
        assertFalse(lines.any { "com.nuvio.app.core.perf" in it || "compactArtworkUrl" in it })
        assertFalse(lines.any { ".Android" in it || "PlayerEngineController" in it })
        val propertyLines = lines.filter {
            it.startsWith("property ") && it.substringBeforeLast('.').substringAfterLast('.').first().isUpperCase()
        }
        assertTrue(propertyLines.isNotEmpty())
        val withoutGetter = propertyLines.filter { line ->
            val fq = line.removePrefix("property ")
            "function ${fq.substringBeforeLast('.')}.<get-${fq.substringAfterLast('.')}>" !in lines
        }
        check(withoutGetter.isEmpty()) { "property lines without getters: $withoutGetter" }
    }
}

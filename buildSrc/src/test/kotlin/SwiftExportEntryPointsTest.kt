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
        assertTrue("bridgeOnly" in names)
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
    fun interfaceMembersOmitEqualsAndSignatureTypesAreKept() {
        val source = """
            package demo
            interface Bridge {
                fun loadFile(url: String)
                fun stop(): Sample
            }
            class Engine {
                fun plan(): Sample
            }
            data class Sample(val energy: Double)
            class Unused
        """.trimIndent()
        val parsed = parseSource(source, "src/commonMain/kotlin/Demo.kt")
        val selected = SwiftExportEntryPoints.select(parsed, setOf("Bridge", "Engine"))
        val names = selected.map { it.simpleName }.toSet()
        assertTrue("Sample" in names)
        assertFalse("Unused" in names)
        val bridge = parsed.filterIsInstance<TypeDecl>().single { it.simpleName == "Bridge" }
        assertTrue(bridge.isInterface)
        assertTrue(bridge.members.map { it.simpleName }.containsAll(listOf("loadFile", "stop")))
        val lines = linkedSetOf<String>()
        selected.filterIsInstance<TypeDecl>().forEach { type ->
            if (type.isInterface) {
                type.members.forEach { lines += "function ${type.fqName}.${it.simpleName}" }
            } else {
                lines += "callable ${type.fqName}.*"
            }
        }
        assertTrue("function demo.Bridge.loadFile" in lines)
        assertFalse(lines.any { it.endsWith(".equals") || it.startsWith("callable demo.Bridge") })
        assertTrue("callable demo.Sample.*" in lines)
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
        assertFalse(lines.any { it == "callable com.nuvio.app.features.player.NuvioPlayerBridge.*" })
        assertFalse(lines.any { it.endsWith(".equals") || it.endsWith(".hashCode") || it.endsWith(".toString") })
        assertFalse(lines.any { "com.nuvio.app.core.perf" in it || "compactArtworkUrl" in it })
    }
}

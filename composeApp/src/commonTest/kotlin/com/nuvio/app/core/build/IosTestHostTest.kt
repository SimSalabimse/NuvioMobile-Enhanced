package com.nuvio.app.core.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SubtitleTests loads the app the simulator actually built.
 * The product name can contain a space. The bundle and the executable use that same name.
 */
/**
 * Xcode's default Swift module name: letters, digits, and underscores stay,
 * and every other character in the product name becomes an underscore.
 * A space in the product name therefore changes the module. The app target
 * pins `PRODUCT_MODULE_NAME` so the tests do not follow that default.
 */
internal fun defaultSwiftModuleName(productName: String): String {
    val name = productName.trim()
    require(name.isNotEmpty())
    val mapped = buildString {
        name.forEach { character ->
            append(if (character.isLetterOrDigit() || character == '_') character else '_')
        }
    }
    return if (mapped.first().isDigit()) "_$mapped" else mapped
}

internal fun iosSubtitleTestHost(builtProductsDir: String, productName: String): String {
    val directory = builtProductsDir.trim().trimEnd('/')
    val name = productName.trim()
    require(directory.isNotEmpty())
    require(name.isNotEmpty())
    require(!name.contains('"') && !name.contains('/') && !name.contains('\\'))
    return "$directory/$name.app/$name"
}

class IosTestHostTest {
    @Test
    fun hostUsesTheProductNameForTheBundleAndTheExecutable() {
        assertEquals(
            "/tmp/Products/Debug-iphonesimulator/Nuvio Enhanced.app/Nuvio Enhanced",
            iosSubtitleTestHost("/tmp/Products/Debug-iphonesimulator", "Nuvio Enhanced"),
        )
        assertEquals(
            "/tmp/Products/Debug-iphonesimulator/Nuvio.app/Nuvio",
            iosSubtitleTestHost("/tmp/Products/Debug-iphonesimulator/", "Nuvio"),
        )
    }

    @Test
    fun projectPointsTheTestHostAtTheConfiguredProductName() {
        val project = locate("iosApp/iosApp.xcodeproj/project.pbxproj")
        val config = locate("iosApp/Configuration/Config.xcconfig")
        val projectText = project.readText()
        val configText = config.readText()
        assertTrue(configText.contains("NUVIO_APP_PRODUCT_NAME=Nuvio Enhanced"))
        assertTrue(configText.contains("PRODUCT_NAME=$(NUVIO_APP_PRODUCT_NAME)"))
        val host = """TEST_HOST = "$(BUILT_PRODUCTS_DIR)/$(NUVIO_APP_PRODUCT_NAME).app/$(NUVIO_APP_PRODUCT_NAME)";"""
        assertEquals(2, Regex(Regex.escape(host)).findAll(projectText).count())
        assertTrue(!projectText.contains("Nuvio.app/Nuvio"))
    }

    @Test
    fun aSpaceInTheProductNameChangesTheDefaultModule() {
        assertEquals("Nuvio", defaultSwiftModuleName("Nuvio"))
        assertEquals("Nuvio_Enhanced", defaultSwiftModuleName("Nuvio Enhanced"))
    }

    @Test
    fun subtitleTestsImportThePinnedAppModuleAndCompileBothSources() {
        val project = locate("iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        assertEquals(2, Regex("PRODUCT_MODULE_NAME = Nuvio;").findAll(project).count())
        assertEquals(2, Regex("PRODUCT_MODULE_NAME = SubtitleTests;").findAll(project).count())
        assertTrue(project.contains("PRODUCT_BUNDLE_IDENTIFIER = com.nuvio.app;"))
        assertTrue(project.contains("PRODUCT_NAME=Nuvio Enhanced") || locate("iosApp/Configuration/Config.xcconfig").readText().contains("NUVIO_APP_PRODUCT_NAME=Nuvio Enhanced"))
        assertTrue(project.contains("MpvSubtitleSyncTests.swift in Sources"))
        assertTrue(project.contains("SubtitlePlaybackTests.swift in Sources"))
        val tests = locate("iosApp/iosAppTests")
        val sources = tests.listFiles { file -> file.extension == "swift" }.orEmpty().sortedBy { it.name }
        assertEquals(listOf("MpvSubtitleSyncTests.swift", "SubtitlePlaybackTests.swift"), sources.map { it.name })
        sources.forEach { file ->
            val text = file.readText()
            assertTrue(text.contains("@testable import Nuvio"), file.name)
            assertTrue(!text.contains("import Nuvio_Enhanced"), file.name)
            assertTrue(!text.contains("import iosApp"), file.name)
        }
        val playback = sources.first { it.name == "SubtitlePlaybackTests.swift" }.readText()
        listOf(
            "submitMode: false",
            "privateSession: false",
            "useNativeTabBar: false",
            "useTabletFloatingTabBar: false",
            "outlineBlur: 0",
        ).forEach { argument ->
            assertTrue(playback.contains(argument), argument)
        }
        val sync = sources.first { it.name == "MpvSubtitleSyncTests.swift" }.readText()
        listOf(
            "testLateCueUsesSpeedBelowOneAndDelayIsSeconds",
            "testAnUntrustedSpeedResetsToOne",
            "testAFitCapturedBeforeATrackChangeDoesNotApply",
            "testUserDelayStaysOnceAfterTheFitIsCleared",
        ).forEach { name ->
            assertTrue(sync.contains("func $name"), name)
        }
        val scheme = locate("iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/SubtitleTests.xcscheme").readText()
        assertTrue(scheme.contains("BlueprintName = \"SubtitleTests\""))
        assertTrue(scheme.contains("skipped = \"NO\""))
        assertTrue(!scheme.contains("skipped = \"YES\""))
    }

    private fun locate(relative: String): File {
        val start = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." })
        val candidates = generateSequence(start) { it.parentFile }.take(6).map { File(it, relative) }
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: error("Missing $relative from ${start.absolutePath}")
    }
}

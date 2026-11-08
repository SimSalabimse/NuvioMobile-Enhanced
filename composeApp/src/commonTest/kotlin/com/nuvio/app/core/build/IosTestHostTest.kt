package com.nuvio.app.core.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SubtitleTests loads the app the simulator actually built.
 * The product name can contain a space. The bundle and the executable use that same name.
 */
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

    private fun locate(relative: String): File {
        val start = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." })
        val candidates = generateSequence(start) { it.parentFile }.take(6).map { File(it, relative) }
        return candidates.firstOrNull { it.isFile }
            ?: error("Missing $relative from ${start.absolutePath}")
    }
}

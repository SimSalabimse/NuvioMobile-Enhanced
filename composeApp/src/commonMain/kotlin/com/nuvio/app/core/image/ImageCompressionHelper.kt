package com.nuvio.app.core.image

import com.nuvio.app.features.settings.PerformanceSettingsRepository

/**
 * Image compression utilities for performance optimization.
 * Item 9: Image compression + trim Swift exports.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance.
 */
object ImageCompressionHelper {
    
    /**
     * Check if image compression optimization is enabled.
     */
    fun isCompressionEnabled(): Boolean {
        PerformanceSettingsRepository.ensureLoaded()
        return PerformanceSettingsRepository.imageCompressionEnabled.value
    }
    
    /**
     * Get recommended JPEG quality based on optimization setting.
     */
    fun getJpegQuality(): Int {
        return if (isCompressionEnabled()) {
            85 // Balanced quality/size
        } else {
            95 // High quality
        }
    }
    
    /**
     * Get recommended PNG compression level.
     */
    fun getPngCompressionLevel(): Int {
        return if (isCompressionEnabled()) {
            6 // Balanced compression
        } else {
            3 // Light compression
        }
    }
}

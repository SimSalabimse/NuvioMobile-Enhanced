import Foundation
import AVFoundation
import ComposeApp

/**
 * Test harness for AVAssetAudioEnergyCapture verification.
 *
 * This validates that AVAsset dual-decode audio capture produces:
 * 1. Non-zero sample count
 * 2. Varying energy values (not flat zeros)
 * 3. Mean energy above silence floor
 *
 * **How to run:**
 * Call `AVAssetAudioCaptureTestHarness.runAllTests()` from anywhere in the app
 * (e.g., add a debug menu item or button).
 *
 * **Requirements:**
 * - No MPV player needed
 * - No UI interaction required
 * - Runs fully automated
 */
final class AVAssetAudioCaptureTestHarness {
    
    // MARK: - Test Configuration
    
    private static let testDurationSeconds = 2.0
    private static let captureStartPositionMs: Int64 = 0
    private static let minExpectedSamples = 15  // ~1.5 seconds at 100ms windows
    private static let minEnergyThreshold = 0.001  // Above silence floor
    private static let minVarianceThreshold = 0.00001  // Not flat
    
    // MARK: - Public API
    
    /**
     * Run all tests and return a summary report.
     */
    static func runAllTests() -> TestReport {
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "========================================"
        )
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "AVAsset Audio Capture Test Harness"
        )
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "========================================\n"
        )
        
        var report = TestReport()
        
        // Test 1: Generate test media file
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Test 1: Generating test audio file..."
        )
        
        guard let testFileURL = generateTestAudioFile() else {
            report.addFailure("Failed to generate test audio file")
            return finalizeReport(report)
        }
        
        report.addSuccess("Generated test audio file: \(testFileURL.lastPathComponent)")
        
        // Test 2: Run AVAsset capture
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "\nTest 2: Running AVAsset audio capture..."
        )
        
        let samples = captureAudioFromFile(url: testFileURL)
        
        if samples.isEmpty {
            report.addFailure("AVAsset capture returned 0 samples")
            cleanupTestFile(url: testFileURL)
            return finalizeReport(report)
        }
        
        report.addSuccess("Captured \(samples.count) energy samples")
        
        // Test 3: Validate sample count
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "\nTest 3: Validating sample count..."
        )
        
        if samples.count >= minExpectedSamples {
            report.addSuccess("Sample count (\(samples.count)) >= minimum (\(minExpectedSamples))")
        } else {
            report.addFailure("Sample count (\(samples.count)) < minimum (\(minExpectedSamples))")
        }
        
        // Test 4: Validate energy levels
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "\nTest 4: Validating energy levels..."
        )
        
        let energies = samples.map { $0.energy }
        let meanEnergy = energies.reduce(0.0, +) / Double(energies.count)
        let maxEnergy = energies.max() ?? 0.0
        let minEnergy = energies.min() ?? 0.0
        
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Mean energy: \(String(format: "%.6f", meanEnergy))"
        )
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Max energy: \(String(format: "%.6f", maxEnergy))"
        )
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Min energy: \(String(format: "%.6f", minEnergy))"
        )
        
        if meanEnergy >= minEnergyThreshold {
            report.addSuccess("Mean energy (\(String(format: "%.6f", meanEnergy))) >= threshold (\(String(format: "%.6f", minEnergyThreshold)))")
        } else {
            report.addFailure("Mean energy (\(String(format: "%.6f", meanEnergy))) < threshold (\(String(format: "%.6f", minEnergyThreshold)))")
        }
        
        // Test 5: Validate variance (not flat)
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "\nTest 5: Validating energy variance..."
        )
        
        let variance = calculateVariance(values: energies, mean: meanEnergy)
        
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Variance: \(String(format: "%.8f", variance))"
        )
        
        if variance >= minVarianceThreshold {
            report.addSuccess("Variance (\(String(format: "%.8f", variance))) >= threshold (\(String(format: "%.8f", minVarianceThreshold)))")
        } else {
            report.addFailure("Variance (\(String(format: "%.8f", variance))) < threshold (\(String(format: "%.8f", minVarianceThreshold))) - samples are too flat")
        }
        
        // Test 6: Validate timestamps are sequential
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "\nTest 6: Validating timestamp sequence..."
        )
        
        var timestampsValid = true
        for i in 1..<samples.count {
            if samples[i].timestampMs <= samples[i-1].timestampMs {
                timestampsValid = false
                break
            }
        }
        
        if timestampsValid {
            report.addSuccess("All timestamps are sequential and increasing")
        } else {
            report.addFailure("Timestamps are not properly sequential")
        }
        
        // Cleanup
        cleanupTestFile(url: testFileURL)
        
        return finalizeReport(report)
    }
    
    // MARK: - Test Audio Generation
    
    /**
     * Generate a test audio file with varying tone (440Hz sine wave with amplitude variation).
     * This ensures we get non-zero, varying energy samples.
     */
    private static func generateTestAudioFile() -> URL? {
        let sampleRate: Double = 44100.0
        let duration = testDurationSeconds
        let frequency: Double = 440.0  // A4 note
        let channels: AVAudioChannelCount = 1
        
        let sampleCount = Int(sampleRate * duration)
        
        // Create audio format
        guard let audioFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: channels,
            interleaved: false
        ) else {
            InAppLogBridge.shared.error(
                tag: "AVAssetCaptureTest",
                message: "Failed to create audio format"
            )
            return nil
        }
        
        // Create audio buffer
        guard let audioBuffer = AVAudioPCMBuffer(
            pcmFormat: audioFormat,
            frameCapacity: AVAudioFrameCount(sampleCount)
        ) else {
            InAppLogBridge.shared.error(
                tag: "AVAssetCaptureTest",
                message: "Failed to create audio buffer"
            )
            return nil
        }
        
        audioBuffer.frameLength = audioBuffer.frameCapacity
        
        // Generate sine wave with amplitude modulation
        if let channelData = audioBuffer.floatChannelData {
            for i in 0..<sampleCount {
                let time = Double(i) / sampleRate
                let angle = 2.0 * .pi * frequency * time
                
                // Amplitude modulation: varies between 0.3 and 0.7 at 2Hz
                let amplitudeModulation = 0.5 + 0.2 * sin(2.0 * .pi * 2.0 * time)
                
                channelData[0][i] = Float(sin(angle) * amplitudeModulation)
            }
        }
        
        // Create temporary file URL
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = "test_audio_\(UUID().uuidString).caf"
        let fileURL = tempDir.appendingPathComponent(fileName)
        
        // Write to file using AVAudioFile (simpler than AVAssetWriter)
        do {
            // Remove file if it exists
            try? FileManager.default.removeItem(at: fileURL)
            
            let audioFile = try AVAudioFile(
                forWriting: fileURL,
                settings: audioFormat.settings,
                commonFormat: .pcmFormatFloat32,
                interleaved: false
            )
            
            try audioFile.write(from: audioBuffer)
            
            InAppLogBridge.shared.info(
                tag: "AVAssetCaptureTest",
                message: "Successfully wrote test audio to: \(fileURL.path)"
            )
            
            return fileURL
        } catch {
            InAppLogBridge.shared.error(
                tag: "AVAssetCaptureTest",
                message: "Error creating audio file: \(error.localizedDescription)"
            )
            return nil
        }
    }
    
    // MARK: - Audio Capture
    
    /**
     * Capture audio from the test file using AVAssetAudioEnergyCapture.
     */
    private static func captureAudioFromFile(url: URL) -> [ComposeApp.AudioEnergySample] {
        // Create a minimal capture instance without playerViewController
        let capture = AVAssetAudioEnergyCapture(playerViewController: nil)
        
        // Start capture
        capture.startCapture(
            startTimeMs: captureStartPositionMs,
            mediaURL: url,
            headers: [:]
        )
        
        // Wait for capture to complete
        let captureWaitTime = testDurationSeconds + 2.0  // Add 2s buffer
        Thread.sleep(forTimeInterval: captureWaitTime)
        
        // Stop and get samples
        let samples = capture.stopCapture()
        
        return samples
    }
    
    // MARK: - Utilities
    
    private static func calculateVariance(values: [Double], mean: Double) -> Double {
        guard !values.isEmpty else { return 0.0 }
        
        let squaredDifferences = values.map { pow($0 - mean, 2) }
        return squaredDifferences.reduce(0.0, +) / Double(values.count)
    }
    
    private static func cleanupTestFile(url: URL) {
        try? FileManager.default.removeItem(at: url)
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Cleaned up test file"
        )
    }
    
    private static func finalizeReport(_ report: TestReport) -> TestReport {
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "\n========================================"
        )
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Test Results: \(report.passed ? "✅ PASSED" : "❌ FAILED")"
        )
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "Successes: \(report.successCount) | Failures: \(report.failureCount)"
        )
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "========================================\n"
        )
        
        if !report.passed {
            InAppLogBridge.shared.info(
                tag: "AVAssetCaptureTest",
                message: "Failures:"
            )
            report.failures.forEach { failure in
                InAppLogBridge.shared.error(
                    tag: "AVAssetCaptureTest",
                    message: "  - \(failure)"
                )
            }
        }
        
        return report
    }
}

// MARK: - Test Report Model

struct TestReport {
    private(set) var successes: [String] = []
    private(set) var failures: [String] = []
    
    var passed: Bool {
        return failures.isEmpty && !successes.isEmpty
    }
    
    var successCount: Int { successes.count }
    var failureCount: Int { failures.count }
    
    mutating func addSuccess(_ message: String) {
        successes.append(message)
        InAppLogBridge.shared.info(
            tag: "AVAssetCaptureTest",
            message: "  ✅ \(message)"
        )
    }
    
    mutating func addFailure(_ message: String) {
        failures.append(message)
        InAppLogBridge.shared.error(
            tag: "AVAssetCaptureTest",
            message: "  ❌ \(message)"
        )
    }
    
    func summary() -> String {
        let status = passed ? "PASSED" : "FAILED"
        return "[\(status)] \(successCount) passed, \(failureCount) failed"
    }
}

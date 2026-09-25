import Foundation
import AVFoundation
import ReplayKit
import ComposeApp

/**
 * Captures audio energy from MPV player for subtitle Auto Sync.
 * 
 * This processor uses ReplayKit's RPScreenRecorder to capture app audio output,
 * computes RMS energy over time windows, and provides timestamped energy samples
 * for correlation with subtitle cues.
 * 
 * Supports both Float32 (most common on iOS) and Int16 PCM audio formats.
 * 
 * ## iOS Platform Constraint (ReplayKit-Only)
 * 
 * **Why ReplayKit is Required:**
 * iOS does not provide any API to tap/intercept audio output from another library.
 * Unlike Android (where ExoPlayer's AudioProcessor can be inserted into the pipeline),
 * iOS sandboxing prevents direct audio capture. ReplayKit is Apple's ONLY official API
 * for capturing app audio without microphone entitlements.
 * 
 * **Why Android Works But iOS Has Restrictions:**
 * - Android: ExoPlayer is app code → AudioProcessor taps pipeline directly
 * - iOS: libmpv is compiled library → audiounit output is internal & opaque
 * 
 * **Alternatives Investigated (None Viable):**
 * - AVAudioEngine taps: Only for input (microphone), not output
 * - AudioUnit callbacks: Can't tap MPV's internal AudioUnit
 * - AVAudioSession hooks: Configuration only, no data access
 * - MPV audio filters: No real-time export mechanism available
 * - Custom MPV fork: Would require weeks of work to add audio hooks
 * 
 * **Result:** ReplayKit is the only practical option on iOS.
 * 
 * ## Requirements and Limitations:
 * 
 * 1. **ReplayKit Availability**: Requires iOS ReplayKit to be available. May fail on
 *    simulators or devices with ReplayKit disabled.
 * 
 * 2. **Sideload Restrictions**: ReplayKit may be restricted or unavailable in sideloaded
 *    apps (e.g., via SideStore, AltStore) depending on iOS version and provisioning.
 *    iOS may deny audio capture for apps without proper entitlements.
 * 
 * 3. **MPV Audio Output**: MPV must route audio through AVFoundation-compatible outputs
 *    (like 'audiounit'). Other audio outputs may not be visible to ReplayKit.
 * 
 * 4. **User Indicators**: A brief screen recording indicator may appear when capture starts.
 * 
 * 5. **No Microphone Entitlement Needed**: Uses ReplayKit app audio capture, which does
 *    not require microphone permissions.
 * 
 * ## Retry Logic:
 * 
 * - Waits 8 seconds for first audio buffer before considering retry
 * - Attempts one retry if no buffers received (in case ReplayKit had slow startup)
 * - Total of 2 attempts before declaring failure
 * - Helps handle timing races where ReplayKit takes time to initialize
 * 
 * ## Diagnostics:
 * 
 * - Logs detailed information at each step (availability, format, buffer reception)
 * - Tracks whether any audio buffers were received from ReplayKit
 * - Provides specific failure reasons (unavailable, no buffers, timeout, etc.)
 * - Reports retry attempts in final summary
 * 
 * ## Future: Alternative Capture Approach
 * 
 * Android uses a direct audio pipeline tap (AudioProcessor) that's 100% reliable.
 * iOS could potentially use MPV audio filters (`af=`) to export PCM data directly,
 * bypassing ReplayKit entirely. See MPV_AUDIO_TAP_INVESTIGATION.md for details.
 */
final class MPVAudioCaptureProcessor: NSObject {
    
    private var screenRecorder: RPScreenRecorder?
    private var isCapturing = false
    private var samples: [AudioEnergySample] = []
    private let samplesLock = NSLock()
    private var captureStartPositionMs: Int64 = 0
    private var receivedAnyBuffer = false
    private var captureStartTime: TimeInterval = 0
    private var captureFailureReason: String?
    private var retryCount = 0
    private var startupCheckWorkItem: DispatchWorkItem?
    
    // Configuration
    private let sampleWindowSize = 4800  // ~100ms at 48kHz
    private let firstBufferTimeoutSeconds: TimeInterval = 8.0  // Increased from 5s to allow slow ReplayKit startup
    private let maxRetryAttempts = 1  // Retry once if no buffers received
    private var energyAccumulator: Double = 0.0
    private var samplesInWindow = 0
    private var processedSampleCount: Int64 = 0
    
    weak var playerViewController: MPVPlayerViewController?
    
    init(playerViewController: MPVPlayerViewController) {
        self.playerViewController = playerViewController
        super.init()
    }
    
    /**
     * Start capturing audio energy at the specified playback position.
     */
    func startCapture(startTimeMs: Int64) {
        InAppLogBridge.shared.info(tag: "MPV/iOS/AudioCapture", message: "Starting audio capture at \(startTimeMs)ms")
        
        // Cancel any pending startup check
        startupCheckWorkItem?.cancel()
        startupCheckWorkItem = nil
        
        samplesLock.lock()
        samples.removeAll()
        energyAccumulator = 0.0
        samplesInWindow = 0
        processedSampleCount = 0
        captureStartPositionMs = startTimeMs
        receivedAnyBuffer = false
        captureFailureReason = nil
        retryCount = 0
        samplesLock.unlock()
        
        captureStartTime = CACurrentMediaTime()
        
        // Check ReplayKit availability before starting
        let recorder = RPScreenRecorder.shared()
        if !recorder.isAvailable {
            let errorMsg = "ReplayKit not available on this device. This may occur on sideloaded apps or simulators. Screen recording capability is required for Auto Sync audio capture."
            InAppLogBridge.shared.error(tag: "MPV/iOS/AudioCapture", message: errorMsg)
            samplesLock.lock()
            captureFailureReason = "ReplayKit unavailable"
            samplesLock.unlock()
            return
        }
        
        // Verify MPV is using compatible audio output
        if let mpvAo = playerViewController?.getCurrentAudioOutput(), !mpvAo.contains("audiounit") {
            InAppLogBridge.shared.warn(
                tag: "MPV/iOS/AudioCapture",
                message: "MPV audio output '\(mpvAo)' may not be compatible with ReplayKit. 'audiounit' is recommended."
            )
        }
        
        isCapturing = true
        
        // Start capturing audio via ReplayKit
        setupAudioCapture()
        
        // Schedule startup check - if no buffers after timeout, consider retry
        scheduleStartupCheck()
    }
    
    /**
     * Stop capturing and return all collected energy samples.
     */
    func stopCapture() -> [ComposeApp.AudioEnergySample] {
        InAppLogBridge.shared.info(tag: "MPV/iOS/AudioCapture", message: "Stopping audio capture")
        
        // Cancel any pending startup check
        startupCheckWorkItem?.cancel()
        startupCheckWorkItem = nil
        
        isCapturing = false
        teardownAudioCapture()
        
        samplesLock.lock()
        let capturedSamples = samples.map { sample in
            ComposeApp.AudioEnergySample(timestampMs: sample.timestampMs, energy: sample.energy)
        }
        let gotAnyBuffer = receivedAnyBuffer
        let failure = captureFailureReason
        let retries = retryCount
        samplesLock.unlock()
        
        let duration = CACurrentMediaTime() - captureStartTime
        let retriesInfo = retries > 0 ? " (\(retries) retries attempted)" : ""
        
        if capturedSamples.isEmpty {
            if let failure = failure {
                InAppLogBridge.shared.error(
                    tag: "MPV/iOS/AudioCapture",
                    message: "Capture failed: \(failure). Duration: \(String(format: "%.1f", duration))s\(retriesInfo)"
                )
            } else if !gotAnyBuffer {
                InAppLogBridge.shared.error(
                    tag: "MPV/iOS/AudioCapture",
                    message: "ReplayKit never delivered audio buffers after \(String(format: "%.1f", duration))s\(retriesInfo). Possible causes: (1) Sideload restrictions on ReplayKit, (2) MPV audio routing incompatibility, (3) iOS denying background audio capture, (4) No audio playback active."
                )
            } else {
                InAppLogBridge.shared.warn(
                    tag: "MPV/iOS/AudioCapture",
                    message: "Received buffers but captured 0 energy samples after \(String(format: "%.1f", duration))s\(retriesInfo). Audio may be silent or below threshold."
                )
            }
        } else {
            InAppLogBridge.shared.info(
                tag: "MPV/iOS/AudioCapture",
                message: "Successfully captured \(capturedSamples.count) energy samples over \(String(format: "%.1f", duration))s\(retriesInfo)"
            )
        }
        
        return capturedSamples
    }
    
    /**
     * Get the duration of audio that has been captured so far.
     */
    func getCaptureDuration() -> Int64 {
        guard isCapturing else { return 0 }
        
        samplesLock.lock()
        let count = samples.count
        let gotBuffer = receivedAnyBuffer
        samplesLock.unlock()
        
        // Log a warning if we haven't received any buffers after a timeout
        let elapsed = CACurrentMediaTime() - captureStartTime
        if !gotBuffer && elapsed > firstBufferTimeoutSeconds {
            InAppLogBridge.shared.warn(
                tag: "MPV/iOS/AudioCapture",
                message: "No audio buffers received from ReplayKit after \(String(format: "%.1f", elapsed))s. This likely indicates a sideload/ReplayKit restriction or audio routing issue."
            )
        }
        
        // Each sample represents ~100ms
        return Int64(count * 100)
    }
    
    /**
     * Check if any audio buffers have been received from ReplayKit.
     */
    func hasReceivedAudioBuffers() -> Bool {
        samplesLock.lock()
        defer { samplesLock.unlock() }
        return receivedAnyBuffer
    }
    
    // MARK: - Retry Logic
    
    private func scheduleStartupCheck() {
        startupCheckWorkItem?.cancel()
        
        let workItem = DispatchWorkItem { [weak self] in
            self?.checkAndRetryIfNeeded()
        }
        startupCheckWorkItem = workItem
        
        DispatchQueue.main.asyncAfter(deadline: .now() + firstBufferTimeoutSeconds, execute: workItem)
        
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture",
            message: "Scheduled startup check in \(String(format: "%.1f", firstBufferTimeoutSeconds))s"
        )
    }
    
    private func checkAndRetryIfNeeded() {
        guard isCapturing else { return }
        
        samplesLock.lock()
        let gotBuffer = receivedAnyBuffer
        let retries = retryCount
        samplesLock.unlock()
        
        if gotBuffer {
            InAppLogBridge.shared.info(
                tag: "MPV/iOS/AudioCapture",
                message: "Startup check: buffers received, ReplayKit working normally"
            )
            return
        }
        
        // No buffers received yet
        let elapsed = CACurrentMediaTime() - captureStartTime
        
        if retries < maxRetryAttempts {
            InAppLogBridge.shared.warn(
                tag: "MPV/iOS/AudioCapture",
                message: "No buffers after \(String(format: "%.1f", elapsed))s, retrying ReplayKit (attempt \(retries + 2) of \(maxRetryAttempts + 1))"
            )
            
            samplesLock.lock()
            retryCount += 1
            let currentStartTime = captureStartPositionMs
            samplesLock.unlock()
            
            // Stop and restart ReplayKit
            teardownAudioCapture()
            
            // Brief delay before retry
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                guard let self = self, self.isCapturing else { return }
                self.setupAudioCapture()
                self.scheduleStartupCheck()
            }
        } else {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture",
                message: "No buffers after \(String(format: "%.1f", elapsed))s and \(maxRetryAttempts) retries. ReplayKit likely blocked on this device/build."
            )
            
            samplesLock.lock()
            if captureFailureReason == nil {
                captureFailureReason = "ReplayKit never delivered buffers after \(maxRetryAttempts) attempts"
            }
            samplesLock.unlock()
        }
    }
    
    // MARK: - Audio Capture Implementation
    
    private func setupAudioCapture() {
        // Clean up any existing recorder
        teardownAudioCapture()
        
        let recorder = RPScreenRecorder.shared()
        screenRecorder = recorder
        
        // Double-check recording availability
        guard recorder.isAvailable else {
            InAppLogBridge.shared.error(tag: "MPV/iOS/AudioCapture", message: "Screen recorder not available in setupAudioCapture")
            samplesLock.lock()
            captureFailureReason = "ReplayKit unavailable in setup"
            samplesLock.unlock()
            isCapturing = false
            return
        }
        
        // Start capturing app audio only (not microphone)
        recorder.isMicrophoneEnabled = false
        
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture",
            message: "Calling RPScreenRecorder.startCapture with isMicrophoneEnabled=false"
        )
        
        recorder.startCapture(handler: { [weak self] sampleBuffer, sampleType, error in
            guard let self = self else { return }
            
            if let error = error {
                InAppLogBridge.shared.error(
                    tag: "MPV/iOS/AudioCapture",
                    message: "Capture handler error: \(error.localizedDescription)"
                )
                self.samplesLock.lock()
                if self.captureFailureReason == nil {
                    self.captureFailureReason = "ReplayKit error: \(error.localizedDescription)"
                }
                self.samplesLock.unlock()
                return
            }
            
            // Process only audio samples
            if sampleType == .audioApp {
                self.samplesLock.lock()
                let isFirstBuffer = !self.receivedAnyBuffer
                if isFirstBuffer {
                    self.receivedAnyBuffer = true
                }
                self.samplesLock.unlock()
                
                if isFirstBuffer {
                    let elapsed = CACurrentMediaTime() - self.captureStartTime
                    InAppLogBridge.shared.info(
                        tag: "MPV/iOS/AudioCapture",
                        message: "Received first audio buffer from ReplayKit after \(String(format: "%.2f", elapsed))s"
                    )
                }
                self.processSampleBuffer(sampleBuffer)
            }
        }) { [weak self] error in
            guard let self = self else { return }
            
            if let error = error {
                let errorMsg = "Failed to start ReplayKit capture: \(error.localizedDescription)"
                InAppLogBridge.shared.error(tag: "MPV/iOS/AudioCapture", message: errorMsg)
                self.samplesLock.lock()
                self.captureFailureReason = errorMsg
                self.samplesLock.unlock()
                self.isCapturing = false
            } else {
                InAppLogBridge.shared.info(
                    tag: "MPV/iOS/AudioCapture",
                    message: "ReplayKit startCapture completed successfully. Waiting for audio buffers..."
                )
            }
        }
    }
    
    private func teardownAudioCapture() {
        if let recorder = screenRecorder {
            recorder.stopCapture { error in
                if let error = error {
                    InAppLogBridge.shared.warn(
                        tag: "MPV/iOS/AudioCapture",
                        message: "Stop capture error: \(error.localizedDescription)"
                    )
                }
            }
        }
        screenRecorder = nil
    }
    
    private func processSampleBuffer(_ sampleBuffer: CMSampleBuffer) {
        guard isCapturing else { return }
        
        // Get format description
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return }
        guard let streamDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription) else { return }
        
        let sampleRate = streamDescription.pointee.mSampleRate
        let channelCount = Int(streamDescription.pointee.mChannelsPerFrame)
        let formatFlags = streamDescription.pointee.mFormatFlags
        let isFloat = (formatFlags & kAudioFormatFlagIsFloat) != 0
        let bitsPerChannel = Int(streamDescription.pointee.mBitsPerChannel)
        
        // Log format on first buffer
        if processedSampleCount == 0 {
            InAppLogBridge.shared.info(
                tag: "MPV/iOS/AudioCapture",
                message: "Audio format: \(isFloat ? "Float32" : "Int16"), \(channelCount)ch, \(Int(sampleRate))Hz, \(bitsPerChannel)bit"
            )
        }
        
        guard channelCount > 0, sampleRate > 0 else { return }
        
        // Get audio buffer list
        var audioBufferList = AudioBufferList()
        var blockBuffer: CMBlockBuffer?
        
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: &audioBufferList,
            bufferListSize: MemoryLayout<AudioBufferList>.size,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer
        )
        
        guard status == noErr else { 
            InAppLogBridge.shared.warn(
                tag: "MPV/iOS/AudioCapture",
                message: "Failed to get audio buffer list: \(status)"
            )
            return 
        }
        
        defer {
            // ARC automatically manages the blockBuffer lifetime
            // No manual CFRelease needed in modern Swift with ARC
            _ = blockBuffer
        }
        
        // Process each buffer
        let bufferCount = Int(audioBufferList.mNumberBuffers)
        let buffersPointer = UnsafeMutableAudioBufferListPointer(&audioBufferList)
        
        for buffer in buffersPointer {
            guard let data = buffer.mData else { continue }
            
            if isFloat {
                // Process Float32 audio (most common on iOS)
                let frameCount = Int(buffer.mDataByteSize) / MemoryLayout<Float32>.size / channelCount
                guard frameCount > 0 else { continue }
                
                let samples = data.assumingMemoryBound(to: Float32.self)
                
                for i in 0..<frameCount {
                    var sample: Float32 = 0
                    
                    // Mix down to mono if stereo
                    if channelCount == 1 {
                        sample = samples[i]
                    } else {
                        var mixedSample: Float32 = 0
                        for ch in 0..<channelCount {
                            mixedSample += samples[i * channelCount + ch]
                        }
                        sample = mixedSample / Float32(channelCount)
                    }
                    
                    // Compute energy (sample is already normalized -1.0 to 1.0)
                    let normalized = Double(sample)
                    energyAccumulator += normalized * normalized
                    samplesInWindow += 1
                    processedSampleCount += 1
                    
                    // When we have enough samples for a window, compute RMS and store
                    if samplesInWindow >= sampleWindowSize {
                        recordEnergySample(sampleRate: sampleRate)
                    }
                }
            } else {
                // Process Int16 audio (less common but still supported)
                let frameCount = Int(buffer.mDataByteSize) / MemoryLayout<Int16>.size / channelCount
                guard frameCount > 0 else { continue }
                
                let samples = data.assumingMemoryBound(to: Int16.self)
                
                for i in 0..<frameCount {
                    var sample: Int16 = 0
                    
                    // Mix down to mono if stereo
                    if channelCount == 1 {
                        sample = samples[i]
                    } else {
                        var mixedSample: Int32 = 0
                        for ch in 0..<channelCount {
                            mixedSample += Int32(samples[i * channelCount + ch])
                        }
                        sample = Int16(mixedSample / Int32(channelCount))
                    }
                    
                    // Compute energy
                    let normalized = Double(sample) / Double(Int16.max)
                    energyAccumulator += normalized * normalized
                    samplesInWindow += 1
                    processedSampleCount += 1
                    
                    // When we have enough samples for a window, compute RMS and store
                    if samplesInWindow >= sampleWindowSize {
                        recordEnergySample(sampleRate: sampleRate)
                    }
                }
            }
        }
    }
    
    private func recordEnergySample(sampleRate: Double) {
        let rmsEnergy = sqrt(energyAccumulator / Double(samplesInWindow))
        let timestampMs = captureStartPositionMs + Int64((Double(processedSampleCount) * 1000.0) / sampleRate)
        
        samplesLock.lock()
        samples.append(AudioEnergySample(timestampMs: timestampMs, energy: rmsEnergy))
        let sampleCount = samples.count
        samplesLock.unlock()
        
        // Log a diagnostic warning if we're consistently getting very low energy
        // (might indicate audio is muted, paused, or not routing through ReplayKit)
        if sampleCount == 50 { // After ~5 seconds of capture
            samplesLock.lock()
            let avgEnergy = samples.prefix(50).map { $0.energy }.reduce(0.0, +) / 50.0
            samplesLock.unlock()
            
            if avgEnergy < 0.001 {
                InAppLogBridge.shared.warn(
                    tag: "MPV/iOS/AudioCapture",
                    message: "Audio energy is very low (avg: \(String(format: "%.6f", avgEnergy))). Audio may be silent, muted, or MPV audio routing may not be compatible with ReplayKit."
                )
            } else {
                InAppLogBridge.shared.info(
                    tag: "MPV/iOS/AudioCapture",
                    message: "Audio energy looks good (avg: \(String(format: "%.6f", avgEnergy))) after 5s"
                )
            }
        }
        
        // Reset for next window
        energyAccumulator = 0.0
        samplesInWindow = 0
    }
}

// Local model for internal use
private struct AudioEnergySample {
    let timestampMs: Int64
    let energy: Double
}

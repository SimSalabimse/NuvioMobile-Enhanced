import Foundation
import AVFoundation
import ReplayKit
import ComposeApp

/**
 * Captures audio energy from MPV player for subtitle Auto Sync.
 * 
 * This processor uses ReplayKit's audio capture to get system audio output,
 * computes RMS energy over time windows, and provides timestamped energy samples
 * for correlation with subtitle cues.
 * 
 * Supports both Float32 (most common on iOS) and Int16 PCM audio formats.
 * 
 * Note: This implementation works with sideload builds. ReplayKit allows capturing
 * app audio without microphone entitlements. The user may see a brief screen recording
 * indicator when audio capture starts.
 */
final class MPVAudioCaptureProcessor: NSObject {
    
    private var screenRecorder: RPScreenRecorder?
    private var isCapturing = false
    private var samples: [AudioEnergySample] = []
    private let samplesLock = NSLock()
    private var captureStartPositionMs: Int64 = 0
    
    // Configuration
    private let sampleWindowSize = 4800  // ~100ms at 48kHz
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
        
        samplesLock.lock()
        samples.removeAll()
        energyAccumulator = 0.0
        samplesInWindow = 0
        processedSampleCount = 0
        captureStartPositionMs = startTimeMs
        samplesLock.unlock()
        
        isCapturing = true
        
        // Check ReplayKit availability first
        let recorder = RPScreenRecorder.shared()
        if !recorder.isAvailable {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture",
                message: "ReplayKit not available. Device may not support screen recording or feature is disabled."
            )
        }
        
        // Start capturing audio via ReplayKit
        setupAudioCapture()
    }
    
    /**
     * Stop capturing and return all collected energy samples.
     */
    func stopCapture() -> [ComposeApp.AudioEnergySample] {
        InAppLogBridge.shared.info(tag: "MPV/iOS/AudioCapture", message: "Stopping audio capture")
        
        isCapturing = false
        teardownAudioCapture()
        
        samplesLock.lock()
        let capturedSamples = samples.map { sample in
            ComposeApp.AudioEnergySample(timestampMs: sample.timestampMs, energy: sample.energy)
        }
        samplesLock.unlock()
        
        InAppLogBridge.shared.info(tag: "MPV/iOS/AudioCapture", message: "Captured \(capturedSamples.count) energy samples")
        return capturedSamples
    }
    
    /**
     * Get the duration of audio that has been captured so far.
     */
    func getCaptureDuration() -> Int64 {
        guard isCapturing else { return 0 }
        
        samplesLock.lock()
        let count = samples.count
        samplesLock.unlock()
        
        // Each sample represents ~100ms
        return Int64(count * 100)
    }
    
    // MARK: - Audio Capture Implementation
    
    private func setupAudioCapture() {
        // Clean up any existing recorder
        teardownAudioCapture()
        
        let recorder = RPScreenRecorder.shared()
        screenRecorder = recorder
        
        // Check if recording is available
        guard recorder.isAvailable else {
            InAppLogBridge.shared.error(tag: "MPV/iOS/AudioCapture", message: "Screen recorder not available")
            return
        }
        
        // Start capturing app audio only (not microphone)
        recorder.isMicrophoneEnabled = false
        
        var receivedFirstBuffer = false
        
        recorder.startCapture(handler: { [weak self] sampleBuffer, sampleType, error in
            guard let self = self else { return }
            
            if let error = error {
                InAppLogBridge.shared.error(
                    tag: "MPV/iOS/AudioCapture",
                    message: "Capture error: \(error.localizedDescription)"
                )
                return
            }
            
            // Process only audio samples
            if sampleType == .audioApp {
                if !receivedFirstBuffer {
                    receivedFirstBuffer = true
                    InAppLogBridge.shared.info(
                        tag: "MPV/iOS/AudioCapture",
                        message: "Received first audio buffer from ReplayKit"
                    )
                }
                self.processSampleBuffer(sampleBuffer)
            }
        }) { error in
            if let error = error {
                InAppLogBridge.shared.error(
                    tag: "MPV/iOS/AudioCapture",
                    message: "Failed to start capture: \(error.localizedDescription)"
                )
            } else {
                InAppLogBridge.shared.info(tag: "MPV/iOS/AudioCapture", message: "Audio capture started successfully")
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
        samplesLock.unlock()
        
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

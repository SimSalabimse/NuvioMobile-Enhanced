import Foundation
import AVFoundation
import ComposeApp

/**
 * Audio energy capture using AVAssetReader for subtitle Auto Sync.
 * 
 * This implementation decodes the audio track independently (parallel to MPV playback)
 * and computes RMS energy samples. Unlike ReplayKit, this works on sideloaded apps
 * because it only requires standard AVFoundation decoding APIs.
 * 
 * ## How It Works:
 * 
 * 1. When capture starts, creates an AVAssetReader for the same media URL MPV is playing
 * 2. Seeks the reader to the current playback position
 * 3. Decodes PCM audio samples in real-time on a background thread
 * 4. Computes RMS energy over 100ms windows
 * 5. Timestamps samples relative to MPV's playback position
 * 
 * ## Supported Media Types:
 * 
 * - Local files (file://)
 * - HTTP/HTTPS streams (AVAsset can load remote media)
 * - Most container formats supported by AVFoundation (MP4, M4V, MOV, etc.)
 * 
 * ## Limitations:
 * 
 * - May not work for some streaming protocols (HLS/DASH with DRM, unusual codecs)
 * - Requires the media URL to be accessible (not expired signed URLs during long captures)
 * - Adds modest CPU load for parallel audio decode (typically 1-5% on modern devices)
 * 
 * ## Advantages Over ReplayKit:
 * 
 * - Works on sideloaded apps (SideStore, AltStore, etc.)
 * - No entitlements required
 * - No screen recording indicator
 * - More reliable (no iOS version or provisioning dependencies)
 */
final class AVAssetAudioEnergyCapture: NSObject {
    
    private var isCapturing = false
    private var samples: [AudioEnergySample] = []
    private let samplesLock = NSLock()
    private var captureStartPositionMs: Int64 = 0
    private var captureStartTime: TimeInterval = 0
    private var captureThread: Thread?
    private var shouldStopCapture = false
    
    // Media source
    private var mediaURL: URL?
    private var requestHeaders: [String: String] = [:]
    
    // Energy computation
    private let sampleWindowSize = 4800  // ~100ms at 48kHz
    private var energyAccumulator: Double = 0.0
    private var samplesInWindow = 0
    private var processedSampleCount: Int64 = 0
    
    weak var playerViewController: MPVPlayerViewController?
    
    init(playerViewController: MPVPlayerViewController?) {
        self.playerViewController = playerViewController
        super.init()
    }
    
    /**
     * Start capturing audio energy at the specified playback position.
     */
    func startCapture(startTimeMs: Int64, mediaURL: URL?, headers: [String: String]) {
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/AVAsset",
            message: "Starting AVAsset-based audio capture at \(startTimeMs)ms for URL: \(mediaURL?.absoluteString ?? "nil")"
        )
        
        guard let url = mediaURL else {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Cannot start capture: media URL is nil"
            )
            return
        }
        
        samplesLock.lock()
        samples.removeAll()
        energyAccumulator = 0.0
        samplesInWindow = 0
        processedSampleCount = 0
        captureStartPositionMs = startTimeMs
        samplesLock.unlock()
        
        self.mediaURL = url
        self.requestHeaders = headers
        captureStartTime = CACurrentMediaTime()
        isCapturing = true
        shouldStopCapture = false
        
        // Start capture on background thread
        captureThread = Thread { [weak self] in
            self?.runCaptureLoop()
        }
        captureThread?.name = "AVAssetAudioCapture"
        captureThread?.start()
    }
    
    /**
     * Stop capturing and return all collected energy samples.
     */
    func stopCapture() -> [ComposeApp.AudioEnergySample] {
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/AVAsset",
            message: "Stopping AVAsset audio capture"
        )
        
        isCapturing = false
        shouldStopCapture = true
        
        // Wait for capture thread to finish (with timeout)
        let deadline = Date().addingTimeInterval(2.0)
        while captureThread != nil && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.05)
        }
        
        samplesLock.lock()
        let capturedSamples = samples.map { sample in
            ComposeApp.AudioEnergySample(timestampMs: sample.timestampMs, energy: sample.energy)
        }
        samplesLock.unlock()
        
        let duration = CACurrentMediaTime() - captureStartTime
        
        if capturedSamples.isEmpty {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "AVAsset capture produced 0 samples after \(String(format: "%.1f", duration))s. Media may have no audio track or format may be unsupported."
            )
        } else {
            InAppLogBridge.shared.info(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Successfully captured \(capturedSamples.count) energy samples over \(String(format: "%.1f", duration))s using AVAsset decode"
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
        samplesLock.unlock()
        
        // Each sample represents ~100ms
        return Int64(count * 100)
    }
    
    // MARK: - Capture Loop
    
    private func runCaptureLoop() {
        guard let url = mediaURL else {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Capture loop: media URL is nil"
            )
            captureThread = nil
            return
        }
        
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/AVAsset",
            message: "Setting up AVAsset for \(url.scheme ?? "no-scheme")://... (path: \(url.path.suffix(50)))"
        )
        
        // Create asset with headers if needed
        let asset = createAsset(url: url, headers: requestHeaders)
        
        // Check if asset is playable first
        if !asset.isPlayable {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "AVAsset reports media is not playable (might be unsupported format or DRM)"
            )
            captureThread = nil
            return
        }
        
        // Load tracks with timeout (important for remote URLs)
        var audioTrack: AVAssetTrack?
        let semaphore = DispatchSemaphore(value: 0)
        var loadError: Error?
        
        asset.loadTracks(withMediaType: .audio) { tracks, error in
            audioTrack = tracks?.first
            loadError = error
            semaphore.signal()
        }
        
        // Wait up to 10 seconds for track loading (important for remote media)
        let timeout = semaphore.wait(timeout: .now() + 10.0)
        
        if timeout == .timedOut {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Timed out loading audio tracks after 10s (slow network or unsupported URL)"
            )
            captureThread = nil
            return
        }
        
        if let error = loadError {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Failed to load audio tracks: \(error.localizedDescription)"
            )
            captureThread = nil
            return
        }
        
        guard let track = audioTrack else {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "No audio track found in media (video-only or unsupported format)"
            )
            captureThread = nil
            return
        }
        
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/AVAsset",
            message: "Found audio track (format: \(track.mediaType.rawValue)), creating reader"
        )
        
        // Create asset reader
        guard let reader = try? AVAssetReader(asset: asset) else {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Failed to create AVAssetReader (unsupported container or codec)"
            )
            captureThread = nil
            return
        }
        
        // Configure output for PCM audio
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]
        
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: outputSettings)
        reader.add(output)
        
        // Seek to start position
        let startSeconds = CMTime(value: Int64(captureStartPositionMs), timescale: 1000)
        reader.timeRange = CMTimeRange(start: startSeconds, duration: .positiveInfinity)
        
        guard reader.startReading() else {
            InAppLogBridge.shared.error(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Failed to start AVAssetReader: \(reader.error?.localizedDescription ?? "unknown error")"
            )
            captureThread = nil
            return
        }
        
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/AVAsset",
            message: "AVAssetReader started successfully, beginning sample processing"
        )
        
        // Read and process audio samples
        var sampleRate: Double = 48000.0  // Will be updated from actual format
        var channelCount: Int = 2
        var loggedFormat = false
        
        while !shouldStopCapture {
            guard let sampleBuffer = output.copyNextSampleBuffer() else {
                // End of file or error
                let status = reader.status
                if status == .failed {
                    InAppLogBridge.shared.warn(
                        tag: "MPV/iOS/AudioCapture/AVAsset",
                        message: "Reader failed: \(reader.error?.localizedDescription ?? "unknown")"
                    )
                }
                break
            }
            
            // Get format on first buffer
            if !loggedFormat, let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
               let streamDesc = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc) {
                sampleRate = streamDesc.pointee.mSampleRate
                channelCount = Int(streamDesc.pointee.mChannelsPerFrame)
                InAppLogBridge.shared.info(
                    tag: "MPV/iOS/AudioCapture/AVAsset",
                    message: "Audio format: \(channelCount)ch, \(Int(sampleRate))Hz"
                )
                loggedFormat = true
            }
            
            // Process the audio buffer
            processSampleBuffer(sampleBuffer, sampleRate: sampleRate, channelCount: channelCount)
            
            CMSampleBufferInvalidate(sampleBuffer)
        }
        
        reader.cancelReading()
        
        InAppLogBridge.shared.info(
            tag: "MPV/iOS/AudioCapture/AVAsset",
            message: "Capture loop finished"
        )
        
        captureThread = nil
    }
    
    private func createAsset(url: URL, headers: [String: String]) -> AVAsset {
        // For remote URLs with headers, use AVURLAsset with options
        if !headers.isEmpty && (url.scheme == "http" || url.scheme == "https") {
            let options: [String: Any] = [
                "AVURLAssetHTTPHeaderFieldsKey": headers
            ]
            return AVURLAsset(url: url, options: options)
        }
        
        return AVAsset(url: url)
    }
    
    private func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, sampleRate: Double, channelCount: Int) {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        
        var length: Int = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        
        let status = CMBlockBufferGetDataPointer(
            blockBuffer,
            atOffset: 0,
            lengthAtOffsetOut: nil,
            totalLengthOut: &length,
            dataPointerOut: &dataPointer
        )
        
        guard status == kCMBlockBufferNoErr, let data = dataPointer else { return }
        
        // Process as Int16 PCM (we requested 16-bit in outputSettings)
        let samples = data.withMemoryRebound(to: Int16.self, capacity: length / 2) { $0 }
        let frameCount = length / (2 * channelCount)  // 2 bytes per sample
        
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
    
    private func recordEnergySample(sampleRate: Double) {
        let rmsEnergy = sqrt(energyAccumulator / Double(samplesInWindow))
        let timestampMs = captureStartPositionMs + Int64((Double(processedSampleCount) * 1000.0) / sampleRate)
        
        samplesLock.lock()
        samples.append(AudioEnergySample(timestampMs: timestampMs, energy: rmsEnergy))
        let sampleCount = samples.count
        samplesLock.unlock()
        
        // Log progress periodically
        if sampleCount % 50 == 0 {  // Every ~5 seconds
            InAppLogBridge.shared.info(
                tag: "MPV/iOS/AudioCapture/AVAsset",
                message: "Captured \(sampleCount) samples (~\(sampleCount / 10)s), latest energy: \(String(format: "%.6f", rmsEnergy))"
            )
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

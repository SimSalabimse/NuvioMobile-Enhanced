import Foundation

struct AudioEnergySamplePoint {
    let timestampMs: Int64
    let energy: Double
}

enum AudioEnergyWave {
    static func energySamples(from url: URL, startTimeMs: Int64, windowFrames: Int = 4800) -> [AudioEnergySamplePoint] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return energySamples(data: data, startTimeMs: startTimeMs, windowFrames: windowFrames)
    }

    static func energySamples(data: Data, startTimeMs: Int64, windowFrames: Int = 4800) -> [AudioEnergySamplePoint] {
        guard let format = readFormat(prefix: data), format.blockAlign > 0, format.sampleRate > 0 else {
            return []
        }
        let payload = data.subdata(in: format.dataOffset..<data.count)
        let usableBytes = payload.count - (payload.count % format.blockAlign)
        guard usableBytes > 0 else { return [] }
        let frames = usableBytes / format.blockAlign
        let channels = format.channelCount
        var energyAccumulator = 0.0
        var samplesInWindow = 0
        var processedFrames = 0
        var results: [AudioEnergySamplePoint] = []

        payload.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            for frame in 0..<frames {
                let frameOffset = frame * format.blockAlign
                var mixed = 0.0
                if format.isFloat {
                    for channel in 0..<channels {
                        let sample: Float32 = readFloat32(base, frameOffset + channel * 4)
                        mixed += Double(sample)
                    }
                } else if format.bitsPerSample == 16 {
                    for channel in 0..<channels {
                        let sample: Int16 = readInt16(base, frameOffset + channel * 2)
                        mixed += Double(sample) / Double(Int16.max)
                    }
                } else {
                    return
                }
                let normalized = mixed / Double(channels)
                energyAccumulator += normalized * normalized
                samplesInWindow += 1
                processedFrames += 1
                if samplesInWindow >= windowFrames {
                    let rms = sqrt(energyAccumulator / Double(samplesInWindow))
                    let timestampMs = startTimeMs + Int64((Double(processedFrames) * 1000.0) / format.sampleRate)
                    results.append(AudioEnergySamplePoint(timestampMs: timestampMs, energy: rms))
                    energyAccumulator = 0
                    samplesInWindow = 0
                }
            }
        }
        return results
    }

    static func capturedDurationMs(at url: URL) -> Int64 {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return 0 }
        defer { try? handle.close() }
        let prefix = (try? handle.read(upToCount: 512)) ?? Data()
        let size = (try? handle.seekToEnd()) ?? 0
        guard let format = readFormat(prefix: prefix), format.blockAlign > 0, format.sampleRate > 0 else {
            return 0
        }
        let payload = size > UInt64(format.dataOffset) ? size - UInt64(format.dataOffset) : 0
        let frames = payload / UInt64(format.blockAlign)
        return Int64((frames * 1000) / UInt64(format.sampleRate.rounded()))
    }

    private struct WaveFormat {
        var sampleRate: Double
        var channelCount: Int
        var bitsPerSample: Int
        var isFloat: Bool
        var blockAlign: Int
        var dataOffset: Int
    }

    private static func readFormat(prefix: Data) -> WaveFormat? {
        guard prefix.count >= 12 else { return nil }
        guard ascii(prefix, 0, 4) == "RIFF", ascii(prefix, 8, 4) == "WAVE" else { return nil }
        var offset = 12
        var format: WaveFormat?
        while offset + 8 <= prefix.count {
            let chunkId = ascii(prefix, offset, 4)
            let chunkSize = Int(readU32(prefix, offset + 4))
            let chunkStart = offset + 8
            if chunkId == "fmt ", chunkSize >= 16, chunkStart + 16 <= prefix.count {
                let audioFormat = readU16(prefix, chunkStart)
                let channels = Int(readU16(prefix, chunkStart + 2))
                let sampleRate = Double(readU32(prefix, chunkStart + 4))
                let bits = Int(readU16(prefix, chunkStart + 14))
                let isFloat = audioFormat == 3
                let blockAlign = Int(readU16(prefix, chunkStart + 12))
                let resolvedAlign = blockAlign > 0 ? blockAlign : channels * (bits / 8)
                if channels > 0, sampleRate > 0, resolvedAlign > 0, (isFloat && bits == 32) || (!isFloat && bits == 16) {
                    format = WaveFormat(
                        sampleRate: sampleRate,
                        channelCount: channels,
                        bitsPerSample: bits,
                        isFloat: isFloat,
                        blockAlign: resolvedAlign,
                        dataOffset: 0
                    )
                }
            } else if chunkId == "data" {
                if var parsed = format {
                    parsed.dataOffset = chunkStart
                    return parsed
                }
                return nil
            }
            let padded = chunkSize + (chunkSize % 2)
            offset = chunkStart + padded
        }
        return nil
    }

    private static func ascii(_ data: Data, _ offset: Int, _ length: Int) -> String {
        guard offset + length <= data.count else { return "" }
        return String(data: data.subdata(in: offset..<(offset + length)), encoding: .ascii) ?? ""
    }

    private static func readU16(_ data: Data, _ offset: Int) -> UInt16 {
        UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
    }

    private static func readU32(_ data: Data, _ offset: Int) -> UInt32 {
        UInt32(data[offset])
            | (UInt32(data[offset + 1]) << 8)
            | (UInt32(data[offset + 2]) << 16)
            | (UInt32(data[offset + 3]) << 24)
    }

    private static func readInt16(_ base: UnsafeRawPointer, _ offset: Int) -> Int16 {
        let low = UInt16(base.load(fromByteOffset: offset, as: UInt8.self))
        let high = UInt16(base.load(fromByteOffset: offset + 1, as: UInt8.self))
        return Int16(bitPattern: low | (high << 8))
    }

    private static func readFloat32(_ base: UnsafeRawPointer, _ offset: Int) -> Float32 {
        let b0 = UInt32(base.load(fromByteOffset: offset, as: UInt8.self))
        let b1 = UInt32(base.load(fromByteOffset: offset + 1, as: UInt8.self))
        let b2 = UInt32(base.load(fromByteOffset: offset + 2, as: UInt8.self))
        let b3 = UInt32(base.load(fromByteOffset: offset + 3, as: UInt8.self))
        return Float32(bitPattern: b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
    }
}

import CryptoKit
import Foundation

// AES-256-GCM for analyzed-details sync. CryptoKit's combined box is
// nonce || ciphertext || tag. The sync format keeps the nonce outside the
// blob, so this returns ciphertext || tag only.
//
// Mac vector parity: from iosApp/iosApp/Sync, `swift test`.
// That runs AnalyzedSyncAesGcmTests against docs/sync/test-vectors/v1/crypto.json.
// This Linux agent cannot run it. The JVM check of the same ciphertext is
// SyncVectorTest.cryptoVectorMatchesPublishedBytes, which uses JCA.

enum SyncAesGcm {
    static func seal(key: Data, nonce: Data, aad: Data, plaintext: Data) throws -> Data {
        let sealed = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: try AES.GCM.Nonce(data: nonce),
            authenticating: aad
        )
        guard sealed.tag.count == 16 else {
            throw SyncAesGcmError.tag
        }
        return sealed.ciphertext + sealed.tag
    }

    static func open(key: Data, nonce: Data, aad: Data, ciphertextAndTag: Data) throws -> Data {
        guard ciphertextAndTag.count >= 16 else {
            throw SyncAesGcmError.tag
        }
        let cipherCount = ciphertextAndTag.count - 16
        let box = try AES.GCM.SealedBox(
            nonce: try AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.prefix(cipherCount),
            tag: ciphertextAndTag.suffix(16)
        )
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }
}

private enum SyncAesGcmError: Error {
    case tag
}

private func copyBytes(_ pointer: UnsafePointer<UInt8>?, count: CInt) -> Data? {
    if count < 0 {
        return nil
    }
    if count == 0 {
        return Data()
    }
    guard let pointer else {
        return nil
    }
    return Data(bytes: pointer, count: Int(count))
}

private func writeBytes(
    _ bytes: Data,
    to out: UnsafeMutablePointer<UInt8>?,
    capacity: CInt,
    length: UnsafeMutablePointer<CInt>?
) -> CInt {
    guard let length, bytes.count <= Int(capacity) else {
        return 1
    }
    if bytes.isEmpty {
        length.pointee = 0
        return 0
    }
    guard let out else {
        return 1
    }
    var wrote = false
    bytes.withUnsafeBytes { raw in
        guard let base = raw.bindMemory(to: UInt8.self).baseAddress else {
            return
        }
        out.update(from: base, count: bytes.count)
        wrote = true
    }
    guard wrote else {
        return 1
    }
    length.pointee = CInt(bytes.count)
    return 0
}

@_cdecl("NuvioSyncAesGcmSeal")
public func NuvioSyncAesGcmSeal(
    _ key: UnsafePointer<UInt8>?,
    _ keyLen: CInt,
    _ nonce: UnsafePointer<UInt8>?,
    _ nonceLen: CInt,
    _ aad: UnsafePointer<UInt8>?,
    _ aadLen: CInt,
    _ plaintext: UnsafePointer<UInt8>?,
    _ plaintextLen: CInt,
    _ out: UnsafeMutablePointer<UInt8>?,
    _ outCap: CInt,
    _ outLen: UnsafeMutablePointer<CInt>?
) -> CInt {
    guard
        let keyBytes = copyBytes(key, count: keyLen),
        let nonceBytes = copyBytes(nonce, count: nonceLen),
        let aadBytes = copyBytes(aad, count: aadLen),
        let plainBytes = copyBytes(plaintext, count: plaintextLen)
    else {
        return 1
    }
    guard let sealed = try? SyncAesGcm.seal(
        key: keyBytes,
        nonce: nonceBytes,
        aad: aadBytes,
        plaintext: plainBytes
    ) else {
        return 1
    }
    return writeBytes(sealed, to: out, capacity: outCap, length: outLen)
}

@_cdecl("NuvioSyncAesGcmOpen")
public func NuvioSyncAesGcmOpen(
    _ key: UnsafePointer<UInt8>?,
    _ keyLen: CInt,
    _ nonce: UnsafePointer<UInt8>?,
    _ nonceLen: CInt,
    _ aad: UnsafePointer<UInt8>?,
    _ aadLen: CInt,
    _ ciphertextAndTag: UnsafePointer<UInt8>?,
    _ ciphertextAndTagLen: CInt,
    _ out: UnsafeMutablePointer<UInt8>?,
    _ outCap: CInt,
    _ outLen: UnsafeMutablePointer<CInt>?
) -> CInt {
    guard
        let keyBytes = copyBytes(key, count: keyLen),
        let nonceBytes = copyBytes(nonce, count: nonceLen),
        let aadBytes = copyBytes(aad, count: aadLen),
        let cipherBytes = copyBytes(ciphertextAndTag, count: ciphertextAndTagLen)
    else {
        return 1
    }
    guard let plain = try? SyncAesGcm.open(
        key: keyBytes,
        nonce: nonceBytes,
        aad: aadBytes,
        ciphertextAndTag: cipherBytes
    ) else {
        return 1
    }
    return writeBytes(plain, to: out, capacity: outCap, length: outLen)
}

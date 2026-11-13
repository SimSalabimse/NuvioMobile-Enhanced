import XCTest
@testable import AnalyzedSyncAesGcm

/// Proves CryptoKit AES-GCM matches `docs/sync/test-vectors/v1/crypto.json`.
/// On a Mac: `cd iosApp/iosApp/Sync && swift test`.
final class AnalyzedSyncAesGcmTests: XCTestCase {
    func testSealMatchesCryptoJsonVector() throws {
        let sealed = try SyncAesGcm.seal(key: key, nonce: nonce, aad: aad, plaintext: plaintext)
        XCTAssertEqual(sealed, ciphertext)
        let opened = try SyncAesGcm.open(key: key, nonce: nonce, aad: aad, ciphertextAndTag: sealed)
        XCTAssertEqual(opened, plaintext)
    }

    func testOpenRejectsATamperedTag() {
        var tampered = ciphertext
        tampered[tampered.count - 1] ^= 0x01
        XCTAssertThrowsError(try SyncAesGcm.open(key: key, nonce: nonce, aad: aad, ciphertextAndTag: tampered))
    }

    func testCAbiRoundTripMatchesTheVector() {
        let sealed = sealThroughC(key: key, nonce: nonce, aad: aad, plaintext: plaintext)
        XCTAssertEqual(sealed, ciphertext)
        let opened = openThroughC(key: key, nonce: nonce, aad: aad, ciphertextAndTag: ciphertext)
        XCTAssertEqual(opened, plaintext)
        var tampered = ciphertext
        tampered[tampered.count - 1] ^= 0x01
        XCTAssertNil(openThroughC(key: key, nonce: nonce, aad: aad, ciphertextAndTag: tampered))
    }
}

private let key = Data(hex: "96f5841b69a4ac4597b25cae547889deb9432ea5a2844ce79f6535fbd052bc97")
private let nonce = Data(base64Encoded: "EBESExQVFhcYGRob")!
private let aad = Data("nuvio-sync/1\n11111111-1111-4111-8111-111111111111\n22222222-2222-4222-8222-222222222222\ndevice-a\nmark\n".utf8)
private let plaintext = Data("{\"deleted\":false,\"durationMs\":3600000,\"endMs\":92000,\"episode\":8,\"hlc\":{\"counter\":0,\"deviceId\":\"device-a\",\"wallMs\":1700000000000},\"id\":\"mark/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa01\",\"kind\":\"mark\",\"presence\":\"range\",\"season\":1,\"segmentIndex\":0,\"segmentType\":\"intro\",\"showId\":\"tmdb:100\",\"startMs\":42000}".utf8)
private let ciphertext = Data(base64Encoded: "3TwIhuMST1vbFojC00RUuBfx7plRjCL/51m/7nI/8w2Qe1kDnLL6FWsQXxa1xR8jlm8NuwuBQPSmjLTlsN6lL8EGXqZ22cjh59uEkyXZvCOvLoEx8Via1pwxWpqmnu5SSqsCRcoaH3JUo65eD0fJz0kGu+jGB/4V2zMmvWO8PhnSFDAtTNdiCxardG3lobuUyx9pDfnR7etrkCKIqnx2cazXPchSxb9F7wex4xvTqR7+rxYPx2zcn+59PSH+kuVx3DexH6ax89cfs7NG7dNPiqJavPRvYZci/+8CctWX1HKUJSbIi3t6IRsj2z3R5wnYt7JwgIlVgOU7CtO5P17j4XT+N5VdzZTi01fL2RRTbrEsJaRnnobbERRECc0FforMYPuBjQin/4jBD2jlT/VKlqPGjZszoPFQxw==")!

private func sealThroughC(key: Data, nonce: Data, aad: Data, plaintext: Data) -> Data? {
    let capacity = plaintext.count + 16
    let out = UnsafeMutablePointer<UInt8>.allocate(capacity: max(capacity, 1))
    defer { out.deallocate() }
    var written: CInt = -1
    let status = key.withUnsafeBytes { keyRaw in
        nonce.withUnsafeBytes { nonceRaw in
            aad.withUnsafeBytes { aadRaw in
                plaintext.withUnsafeBytes { plainRaw in
                    NuvioSyncAesGcmSeal(
                        keyRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(key.count),
                        nonceRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(nonce.count),
                        aadRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(aad.count),
                        plainRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(plaintext.count),
                        out,
                        CInt(capacity),
                        &written
                    )
                }
            }
        }
    }
    guard status == 0, written == CInt(capacity) else {
        return nil
    }
    return Data(bytes: out, count: capacity)
}

private func openThroughC(key: Data, nonce: Data, aad: Data, ciphertextAndTag: Data) -> Data? {
    let capacity = ciphertextAndTag.count - 16
    guard capacity >= 0 else {
        return nil
    }
    let out = UnsafeMutablePointer<UInt8>.allocate(capacity: max(capacity, 1))
    defer { out.deallocate() }
    var written: CInt = -1
    let status = key.withUnsafeBytes { keyRaw in
        nonce.withUnsafeBytes { nonceRaw in
            aad.withUnsafeBytes { aadRaw in
                ciphertextAndTag.withUnsafeBytes { cipherRaw in
                    NuvioSyncAesGcmOpen(
                        keyRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(key.count),
                        nonceRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(nonce.count),
                        aadRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(aad.count),
                        cipherRaw.bindMemory(to: UInt8.self).baseAddress,
                        CInt(ciphertextAndTag.count),
                        capacity == 0 ? nil : out,
                        CInt(capacity),
                        &written
                    )
                }
            }
        }
    }
    guard status == 0, written == CInt(capacity) else {
        return nil
    }
    return Data(bytes: out, count: capacity)
}

private extension Data {
    init(hex: String) {
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        self.init(bytes)
    }
}

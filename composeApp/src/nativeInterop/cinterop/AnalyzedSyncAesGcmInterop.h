#pragma once

#include <stdint.h>

// Implemented by @_cdecl in iosApp/iosApp/Sync/AnalyzedSyncAesGcm.swift.
// AES-256-GCM through CryptoKit. Seal writes ciphertext || 16-byte tag.
// Open checks the tag and writes plaintext. Returns 0 on success.
// C `int` is Swift CInt. Keep this in sync with that file.

int NuvioSyncAesGcmSeal(
    const uint8_t *key,
    int keyLen,
    const uint8_t *nonce,
    int nonceLen,
    const uint8_t *aad,
    int aadLen,
    const uint8_t *plaintext,
    int plaintextLen,
    uint8_t *out,
    int outCap,
    int *outLen
);

int NuvioSyncAesGcmOpen(
    const uint8_t *key,
    int keyLen,
    const uint8_t *nonce,
    int nonceLen,
    const uint8_t *aad,
    int aadLen,
    const uint8_t *ciphertextAndTag,
    int ciphertextAndTagLen,
    uint8_t *out,
    int outCap,
    int *outLen
);

// Copyright 2024 The BoringSSL Authors
// Minimal sha2.h for BoringSSL curve25519/spake25519 (pinned commit 3fed4f207543470b3d639db6ed40737e30308b51).
#ifndef OPENSSL_HEADER_SHA2_H
#define OPENSSL_HEADER_SHA2_H

#include <openssl/base.h>

#if defined(__cplusplus)
extern "C" {
#endif

#define SHA512_CBLOCK 128
#define SHA512_DIGEST_LENGTH 64

typedef struct sha512_state_st {
  uint64_t h[8];
  uint64_t Nl, Nh;
  uint8_t p[SHA512_CBLOCK];
  unsigned num, md_len;
} SHA512_CTX;

OPENSSL_EXPORT int SHA512_Init(SHA512_CTX *sha);
OPENSSL_EXPORT int SHA512_Update(SHA512_CTX *sha, const void *data, size_t len);
OPENSSL_EXPORT int SHA512_Final(uint8_t out[SHA512_DIGEST_LENGTH], SHA512_CTX *sha);
OPENSSL_EXPORT uint8_t *SHA512(const uint8_t *data, size_t len, uint8_t out[SHA512_DIGEST_LENGTH]);

#if defined(__cplusplus)
}
#endif

#endif  // OPENSSL_HEADER_SHA2_H

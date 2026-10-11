// Copyright 2014 The BoringSSL Authors
// Minimal mem.h for BoringSSL curve25519/spake25519 (pinned commit 3fed4f207543470b3d639db6ed40737e30308b51).
#ifndef OPENSSL_HEADER_MEM_H
#define OPENSSL_HEADER_MEM_H

#include <openssl/base.h>
#include <string.h>

#if defined(__cplusplus)
extern "C" {
#endif

OPENSSL_EXPORT void *OPENSSL_malloc(size_t size);
OPENSSL_EXPORT void OPENSSL_free(void *ptr);
OPENSSL_EXPORT void OPENSSL_cleanse(void *ptr, size_t len);
OPENSSL_EXPORT int CRYPTO_memcmp(const void *a, const void *b, size_t len);

static inline int OPENSSL_memcmp(const void *a, const void *b, size_t len) {
  return memcmp(a, b, len);
}

#if defined(__cplusplus)
}
#endif

#endif  // OPENSSL_HEADER_MEM_H

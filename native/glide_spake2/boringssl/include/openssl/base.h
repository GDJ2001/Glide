// Copyright 2014 The BoringSSL Authors
// Minimal base.h for BoringSSL curve25519/spake25519 (pinned commit 3fed4f207543470b3d639db6ed40737e30308b51).
#ifndef OPENSSL_HEADER_BASE_H
#define OPENSSL_HEADER_BASE_H

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#ifndef OPENSSL_NO_ASM
#define OPENSSL_NO_ASM
#endif

#if defined(__x86_64) || defined(_M_AMD64) || defined(_M_X64) || defined(__aarch64__) || defined(_M_ARM64) || defined(__LP64__)
#define OPENSSL_64_BIT
#if defined(__GNUC__) || defined(__clang__)
#define BORINGSSL_HAS_UINT128
typedef __int128_t int128_t;
typedef __uint128_t uint128_t;
#endif
#else
#define OPENSSL_32_BIT
#endif

#define OPENSSL_EXPORT
#define OPENSSL_INLINE static inline

#if defined(__cplusplus)
#define BSSL_NAMESPACE_BEGIN namespace bssl {
#define BSSL_NAMESPACE_END }
#define BORINGSSL_MAKE_DELETER(type, deleter)
#else
#define BSSL_NAMESPACE_BEGIN
#define BSSL_NAMESPACE_END
#endif

typedef struct spake2_ctx_st SPAKE2_CTX;
typedef struct cpace_ctx_st CPACE_CTX;

#endif  // OPENSSL_HEADER_BASE_H

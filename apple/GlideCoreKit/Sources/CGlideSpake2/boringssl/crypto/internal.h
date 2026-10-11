// Copyright 2014 The BoringSSL Authors
// Minimal crypto/internal.h and mem_internal.h and fipsmodule/bn/internal.h for BoringSSL SPAKE2.
#ifndef OPENSSL_HEADER_CRYPTO_INTERNAL_H
#define OPENSSL_HEADER_CRYPTO_INTERNAL_H

#include <openssl/base.h>
#include <openssl/mem.h>
#include <assert.h>
#include <string.h>

#if defined(OPENSSL_64_BIT)
typedef uint64_t crypto_word_t;
#else
typedef uint32_t crypto_word_t;
#endif

#define CONSTTIME_DECLASSIFY(x, y) ((void)0)
#define declassify_assert(expr) assert(expr)

static inline void *OPENSSL_memcpy(void *dst, const void *src, size_t n) {
  if (n == 0) {
    return dst;
  }
  return memcpy(dst, src, n);
}

static inline void *OPENSSL_memmove(void *dst, const void *src, size_t n) {
  if (n == 0) {
    return dst;
  }
  return memmove(dst, src, n);
}

static inline void *OPENSSL_memset(void *dst, int c, size_t n) {
  if (n == 0) {
    return dst;
  }
  return memset(dst, c, n);
}

static inline uint64_t CRYPTO_load_u64_le(const void *in) {
  uint64_t v;
  memcpy(&v, in, sizeof(v));
  return v;
}

static inline crypto_word_t value_barrier_w(crypto_word_t a) {
  return a;
}

static inline crypto_word_t constant_time_msb_w(crypto_word_t a) {
  return 0u - (a >> (sizeof(a) * 8 - 1));
}

static inline crypto_word_t constant_time_is_zero_w(crypto_word_t a) {
  return constant_time_msb_w(~a & (a - 1));
}

static inline crypto_word_t constant_time_eq_w(crypto_word_t a, crypto_word_t b) {
  return constant_time_is_zero_w(a ^ b);
}

static inline int constant_time_declassify_int(int v) {
  return v;
}

static inline void constant_time_conditional_memxor(void *out, const void *in, size_t n, crypto_word_t mask) {
  uint8_t *o = (uint8_t *)out;
  const uint8_t *i = (const uint8_t *)in;
  uint8_t m = (uint8_t)mask;
  for (size_t idx = 0; idx < n; idx++) {
    o[idx] ^= i[idx] & m;
  }
}

static inline int CRYPTO_is_NEON_capable(void) { return 0; }
static inline int CRYPTO_is_ADX_capable(void) { return 0; }
static inline int CRYPTO_is_BMI1_capable(void) { return 0; }
static inline int CRYPTO_is_BMI2_capable(void) { return 0; }

#endif  // OPENSSL_HEADER_CRYPTO_INTERNAL_H

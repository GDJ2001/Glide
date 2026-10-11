// Copyright 2014 The BoringSSL Authors
#ifndef OPENSSL_HEADER_BN_INTERNAL_H
#define OPENSSL_HEADER_BN_INTERNAL_H

#include "../../internal.h"

#if defined(OPENSSL_64_BIT)
typedef uint64_t BN_ULONG;
#define TOBN(hi, lo) (((uint64_t)(hi) << 32) | (lo))
#else
typedef uint32_t BN_ULONG;
#define TOBN(hi, lo) (lo), (hi)
#endif

static inline void bn_select_words(BN_ULONG *r, crypto_word_t mask, const BN_ULONG *a, const BN_ULONG *b, size_t num) {
  for (size_t i = 0; i < num; i++) {
    r[i] = (a[i] & mask) | (b[i] & ~mask);
  }
}

static inline BN_ULONG bn_add_words(BN_ULONG *r, const BN_ULONG *a, const BN_ULONG *b, size_t num) {
  BN_ULONG carry = 0;
  for (size_t i = 0; i < num; i++) {
    BN_ULONG ai = a[i];
    BN_ULONG bi = b[i];
    BN_ULONG sum1 = ai + carry;
    BN_ULONG c1 = (sum1 < ai) ? 1 : 0;
    BN_ULONG sum2 = sum1 + bi;
    BN_ULONG c2 = (sum2 < sum1) ? 1 : 0;
    r[i] = sum2;
    carry = c1 | c2;
  }
  return carry;
}

#endif  // OPENSSL_HEADER_BN_INTERNAL_H

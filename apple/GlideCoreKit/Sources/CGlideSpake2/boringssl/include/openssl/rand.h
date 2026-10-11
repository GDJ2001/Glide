// Copyright 2014 The BoringSSL Authors
// Minimal rand.h and digest.h and bytestring.h for BoringSSL SPAKE2.
#ifndef OPENSSL_HEADER_RAND_H
#define OPENSSL_HEADER_RAND_H

#include <openssl/base.h>

#if defined(__cplusplus)
extern "C" {
#endif

OPENSSL_EXPORT int RAND_bytes(uint8_t *buf, size_t len);

#if defined(__cplusplus)
}
#endif

#endif  // OPENSSL_HEADER_RAND_H

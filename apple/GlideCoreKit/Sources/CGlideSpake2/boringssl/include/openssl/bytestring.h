// Copyright 2014 The BoringSSL Authors
#ifndef OPENSSL_HEADER_BYTESTRING_H
#define OPENSSL_HEADER_BYTESTRING_H

#include <openssl/base.h>
#include <openssl/mem.h>

#if defined(__cplusplus)
extern "C" {
#endif

typedef struct cbs_st {
  const uint8_t *data;
  size_t len;
} CBS;

static inline void CBS_init(CBS *cbs, const uint8_t *data, size_t len) {
  cbs->data = data;
  cbs->len = len;
}

static inline int CBS_stow(const CBS *cbs, uint8_t **out_ptr, size_t *out_len) {
  OPENSSL_free(*out_ptr);
  *out_ptr = NULL;
  *out_len = 0;
  if (cbs->len == 0) {
    return 1;
  }
  *out_ptr = (uint8_t *)OPENSSL_malloc(cbs->len);
  if (*out_ptr == NULL) {
    return 0;
  }
  memcpy(*out_ptr, cbs->data, cbs->len);
  *out_len = cbs->len;
  return 1;
}

#if defined(__cplusplus)
}
#endif

#endif  // OPENSSL_HEADER_BYTESTRING_H

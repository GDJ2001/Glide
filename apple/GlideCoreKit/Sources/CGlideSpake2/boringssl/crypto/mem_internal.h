// Copyright 2017 The BoringSSL Authors
#ifndef OPENSSL_HEADER_CRYPTO_MEM_INTERNAL_H
#define OPENSSL_HEADER_CRYPTO_MEM_INTERNAL_H

#include <openssl/base.h>
#include <openssl/mem.h>
#include <new>
#include <utility>

#define DECLARE_OPAQUE_STRUCT(c_name, cxx_name) \
  BSSL_NAMESPACE_BEGIN                          \
  class cxx_name;                               \
  BSSL_NAMESPACE_END                            \
  struct c_name {};

BSSL_NAMESPACE_BEGIN

template <typename T>
class Span {
 public:
  Span() : data_(nullptr), size_(0) {}
  Span(T *data, size_t size) : data_(data), size_(size) {}
  T *data() const { return data_; }
  size_t size() const { return size_; }
 private:
  T *data_;
  size_t size_;
};

template <typename T>
class Array {
 public:
  Array() : data_(nullptr), size_(0) {}
  ~Array() { OPENSSL_free(data_); }
  T *data() const { return data_; }
  size_t size() const { return size_; }
 private:
  T *data_;
  size_t size_;
};

template <typename T, typename... Args>
T *New(Args &&...args) {
  void *buf = OPENSSL_malloc(sizeof(T));
  if (buf == nullptr) {
    return nullptr;
  }
  return new (buf) T(std::forward<Args>(args)...);
}

template <typename T>
void Delete(T *ptr) {
  if (ptr == nullptr) {
    return;
  }
  ptr->~T();
  OPENSSL_free(ptr);
}

BSSL_NAMESPACE_END

#endif  // OPENSSL_HEADER_CRYPTO_MEM_INTERNAL_H

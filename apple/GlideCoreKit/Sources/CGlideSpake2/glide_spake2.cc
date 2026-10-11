// Copyright 2026 Glide Project
// Private C ABI wrapper over BoringSSL SPAKE2 (pinned commit 3fed4f207543470b3d639db6ed40737e30308b51).

#include "glide_spake2.h"

#include <openssl/spake25519.h>
#include <openssl/curve25519.h>
#include <openssl/mem.h>
#include <openssl/rand.h>
#include <openssl/sha2.h>
#include "../boringssl/crypto/curve25519/internal.h"

#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <bcrypt.h>
#pragma comment(lib, "bcrypt.lib")
#else
#include <fcntl.h>
#include <unistd.h>
#endif

extern "C" {

void *OPENSSL_malloc(size_t size) {
  if (size == 0) {
    size = 1;
  }
  return malloc(size);
}

void OPENSSL_free(void *ptr) {
  free(ptr);
}

void OPENSSL_cleanse(void *ptr, size_t len) {
  if (ptr == nullptr || len == 0) {
    return;
  }
  volatile uint8_t *p = reinterpret_cast<volatile uint8_t *>(ptr);
  for (size_t i = 0; i < len; i++) {
    p[i] = 0;
  }
}

int CRYPTO_memcmp(const void *in_a, const void *in_b, size_t len) {
  const uint8_t *a = reinterpret_cast<const uint8_t *>(in_a);
  const uint8_t *b = reinterpret_cast<const uint8_t *>(in_b);
  uint8_t x = 0;
  for (size_t i = 0; i < len; i++) {
    x |= a[i] ^ b[i];
  }
  return x;
}

static thread_local bool g_has_test_entropy = false;
static thread_local uint8_t g_test_entropy[64] = {0};

int RAND_bytes(uint8_t *buf, size_t len) {
  if (buf == nullptr) {
    return 0;
  }
  if (len == 0) {
    return 1;
  }
  if (g_has_test_entropy && len == 64) {
    memcpy(buf, g_test_entropy, 64);
    OPENSSL_cleanse(g_test_entropy, sizeof(g_test_entropy));
    g_has_test_entropy = false;
    return 1;
  }
#if defined(_WIN32)
  NTSTATUS status = BCryptGenRandom(
      NULL,
      reinterpret_cast<PUCHAR>(buf),
      static_cast<ULONG>(len),
      BCRYPT_USE_SYSTEM_PREFERRED_RNG);
  if (status != 0) {
    abort();
  }
  return 1;
#else
  int fd = open("/dev/urandom", O_RDONLY);
  if (fd < 0) {
    abort();
  }
  size_t total = 0;
  while (total < len) {
    ssize_t r = read(fd, buf + total, len - total);
    if (r <= 0) {
      close(fd);
      abort();
    }
    total += static_cast<size_t>(r);
  }
  close(fd);
  return 1;
#endif
}

// FIPS 180-4 SHA-512 implementation for BoringSSL SHA512_Init / Update / Final.
static const uint64_t kSha512K[80] = {
    UINT64_C(0x428a2f98d728ae22), UINT64_C(0x7137449123ef65cd),
    UINT64_C(0xb5c0fbcfec4d3b2f), UINT64_C(0xe9b5dba58189dbbc),
    UINT64_C(0x3956c25bf348b538), UINT64_C(0x59f111f1b605d019),
    UINT64_C(0x923f82a4af194f9b), UINT64_C(0xab1c5ed5da6d8118),
    UINT64_C(0xd807aa98a3030242), UINT64_C(0x12835b0145706fbe),
    UINT64_C(0x243185be4ee4b28c), UINT64_C(0x550c7dc3d5ffb4e2),
    UINT64_C(0x72be5d74f27b896f), UINT64_C(0x80deb1fe3b1696b1),
    UINT64_C(0x9bdc06a725c71235), UINT64_C(0xc19bf174cf692694),
    UINT64_C(0xe49b69c19ef14ad2), UINT64_C(0xefbe4786384f25e3),
    UINT64_C(0x0fc19dc68b8cd5b5), UINT64_C(0x240ca1cc77ac9c65),
    UINT64_C(0x2de92c6f592b0275), UINT64_C(0x4a7484aa6ea6e483),
    UINT64_C(0x5cb0a9dcbd41fbd4), UINT64_C(0x76f988da831153b5),
    UINT64_C(0x983e5152ee66dfab), UINT64_C(0xa831c66d2db43210),
    UINT64_C(0xb00327c898fb213f), UINT64_C(0xbf597fc7beef0ee4),
    UINT64_C(0xc6e00bf33da88fc2), UINT64_C(0xd5a79147930aa725),
    UINT64_C(0x06ca6351e003826f), UINT64_C(0x142929670a0e6e70),
    UINT64_C(0x27b70a8546d22ffc), UINT64_C(0x2e1b21385c26c926),
    UINT64_C(0x4d2c6dfc5ac42aed), UINT64_C(0x53380d139d95b3df),
    UINT64_C(0x650a73548baf63de), UINT64_C(0x766a0abb3c77b2a8),
    UINT64_C(0x81c2c92e47edaee6), UINT64_C(0x92722c851482353b),
    UINT64_C(0xa2bfe8a14cf10364), UINT64_C(0xa81a664bbc423001),
    UINT64_C(0xc24b8b70d0f89791), UINT64_C(0xc76c51a30654be30),
    UINT64_C(0xd192e819d6ef5218), UINT64_C(0xd69906245565a910),
    UINT64_C(0xf40e35855771202a), UINT64_C(0x106aa07032bbd1b8),
    UINT64_C(0x19a4c116b8d2d0c8), UINT64_C(0x1e376c085141ab53),
    UINT64_C(0x2748774cdf8eeb99), UINT64_C(0x34b0bcb5e19b48a8),
    UINT64_C(0x391c0cb3c5c95a63), UINT64_C(0x4ed8aa4ae3418acb),
    UINT64_C(0x5b9cca4f7763e373), UINT64_C(0x682e6ff3d6b2b8a3),
    UINT64_C(0x748f82ee5defb2fc), UINT64_C(0x78a5636f43172f60),
    UINT64_C(0x84c87814a1f0ab72), UINT64_C(0x8cc702081a6439ec),
    UINT64_C(0x90befffa23631e28), UINT64_C(0xa4506cebde82bde9),
    UINT64_C(0xbef9a3f7b2c67915), UINT64_C(0xc67178f2e372532b),
    UINT64_C(0xca273eceea26619c), UINT64_C(0xd186b8c721c0c207),
    UINT64_C(0xeada7dd6cde0eb1e), UINT64_C(0xf57d4f7fee6ed178),
    UINT64_C(0x06f067aa72176fba), UINT64_C(0x0a637dc5a2c898a6),
    UINT64_C(0x113f9804bef90dae), UINT64_C(0x1b710b35131c471b),
    UINT64_C(0x28db77f523047d84), UINT64_C(0x32caab7b40c72493),
    UINT64_C(0x3c9ebe0a15c9bebc), UINT64_C(0x431d67c49c100d4c),
    UINT64_C(0x4cc5d4becb3e42b6), UINT64_C(0x597f299cfc657e2a),
    UINT64_C(0x5fcb6fab3ad6faec), UINT64_C(0x6c44198c4a475817),
};

static inline uint64_t rotr64(uint64_t x, unsigned n) {
  return (x >> n) | (x << (64 - n));
}

static inline uint64_t load_u64_be(const uint8_t *p) {
  return (static_cast<uint64_t>(p[0]) << 56) |
         (static_cast<uint64_t>(p[1]) << 48) |
         (static_cast<uint64_t>(p[2]) << 40) |
         (static_cast<uint64_t>(p[3]) << 32) |
         (static_cast<uint64_t>(p[4]) << 24) |
         (static_cast<uint64_t>(p[5]) << 16) |
         (static_cast<uint64_t>(p[6]) << 8) |
         static_cast<uint64_t>(p[7]);
}

static inline void store_u64_be(uint8_t *p, uint64_t v) {
  p[0] = static_cast<uint8_t>(v >> 56);
  p[1] = static_cast<uint8_t>(v >> 48);
  p[2] = static_cast<uint8_t>(v >> 40);
  p[3] = static_cast<uint8_t>(v >> 32);
  p[4] = static_cast<uint8_t>(v >> 24);
  p[5] = static_cast<uint8_t>(v >> 16);
  p[6] = static_cast<uint8_t>(v >> 8);
  p[7] = static_cast<uint8_t>(v);
}

static void sha512_transform(uint64_t state[8], const uint8_t block[SHA512_CBLOCK]) {
  uint64_t W[80];
  for (int i = 0; i < 16; i++) {
    W[i] = load_u64_be(block + i * 8);
  }
  for (int i = 16; i < 80; i++) {
    uint64_t s0 = rotr64(W[i - 15], 1) ^ rotr64(W[i - 15], 8) ^ (W[i - 15] >> 7);
    uint64_t s1 = rotr64(W[i - 2], 19) ^ rotr64(W[i - 2], 61) ^ (W[i - 2] >> 6);
    W[i] = W[i - 16] + s0 + W[i - 7] + s1;
  }

  uint64_t a = state[0];
  uint64_t b = state[1];
  uint64_t c = state[2];
  uint64_t d = state[3];
  uint64_t e = state[4];
  uint64_t f = state[5];
  uint64_t g = state[6];
  uint64_t h = state[7];

  for (int i = 0; i < 80; i++) {
    uint64_t S1 = rotr64(e, 14) ^ rotr64(e, 18) ^ rotr64(e, 41);
    uint64_t ch = (e & f) ^ ((~e) & g);
    uint64_t temp1 = h + S1 + ch + kSha512K[i] + W[i];
    uint64_t S0 = rotr64(a, 28) ^ rotr64(a, 34) ^ rotr64(a, 39);
    uint64_t maj = (a & b) ^ (a & c) ^ (b & c);
    uint64_t temp2 = S0 + maj;

    h = g;
    g = f;
    f = e;
    e = d + temp1;
    d = c;
    c = b;
    b = a;
    a = temp1 + temp2;
  }

  state[0] += a;
  state[1] += b;
  state[2] += c;
  state[3] += d;
  state[4] += e;
  state[5] += f;
  state[6] += g;
  state[7] += h;
  OPENSSL_cleanse(W, sizeof(W));
}

int SHA512_Init(SHA512_CTX *sha) {
  memset(sha, 0, sizeof(*sha));
  sha->h[0] = UINT64_C(0x6a09e667f3bcc908);
  sha->h[1] = UINT64_C(0xbb67ae8584caa73b);
  sha->h[2] = UINT64_C(0x3c6ef372fe94f82b);
  sha->h[3] = UINT64_C(0xa54ff53a5f1d36f1);
  sha->h[4] = UINT64_C(0x510e527fade682d1);
  sha->h[5] = UINT64_C(0x9b05688c2b3e6c1f);
  sha->h[6] = UINT64_C(0x1f83d9abfb41bd6b);
  sha->h[7] = UINT64_C(0x5be0cd19137e2179);
  sha->md_len = SHA512_DIGEST_LENGTH;
  return 1;
}

int SHA512_Update(SHA512_CTX *sha, const void *data, size_t len) {
  if (len == 0) {
    return 1;
  }
  const uint8_t *in = reinterpret_cast<const uint8_t *>(data);
  uint64_t bit_len_lo = static_cast<uint64_t>(len) << 3;
  uint64_t bit_len_hi = static_cast<uint64_t>(len) >> 61;

  uint64_t old_nl = sha->Nl;
  sha->Nl += bit_len_lo;
  if (sha->Nl < old_nl) {
    sha->Nh++;
  }
  sha->Nh += bit_len_hi;

  if (sha->num > 0) {
    size_t todo = SHA512_CBLOCK - sha->num;
    if (len < todo) {
      memcpy(sha->p + sha->num, in, len);
      sha->num += static_cast<unsigned>(len);
      return 1;
    }
    memcpy(sha->p + sha->num, in, todo);
    sha512_transform(sha->h, sha->p);
    in += todo;
    len -= todo;
    sha->num = 0;
  }

  while (len >= SHA512_CBLOCK) {
    sha512_transform(sha->h, in);
    in += SHA512_CBLOCK;
    len -= SHA512_CBLOCK;
  }

  if (len > 0) {
    memcpy(sha->p, in, len);
    sha->num = static_cast<unsigned>(len);
  }
  return 1;
}

int SHA512_Final(uint8_t out[SHA512_DIGEST_LENGTH], SHA512_CTX *sha) {
  sha->p[sha->num++] = 0x80;
  if (sha->num > SHA512_CBLOCK - 16) {
    memset(sha->p + sha->num, 0, SHA512_CBLOCK - sha->num);
    sha512_transform(sha->h, sha->p);
    sha->num = 0;
  }
  memset(sha->p + sha->num, 0, SHA512_CBLOCK - 16 - sha->num);
  store_u64_be(sha->p + SHA512_CBLOCK - 16, sha->Nh);
  store_u64_be(sha->p + SHA512_CBLOCK - 8, sha->Nl);
  sha512_transform(sha->h, sha->p);

  for (int i = 0; i < 8; i++) {
    store_u64_be(out + i * 8, sha->h[i]);
  }
  OPENSSL_cleanse(sha, sizeof(*sha));
  return 1;
}

uint8_t *SHA512(const uint8_t *data, size_t len, uint8_t out[SHA512_DIGEST_LENGTH]) {
  SHA512_CTX ctx;
  SHA512_Init(&ctx);
  SHA512_Update(&ctx, data, len);
  SHA512_Final(out, &ctx);
  return out;
}

}  // extern "C"

struct glide_spake2_ctx_st {
  SPAKE2_CTX *inner;
};

extern "C" {

GLIDE_SPAKE2_API const char *glide_spake2_boringssl_commit(void) {
  return "3fed4f207543470b3d639db6ed40737e30308b51";
}

GLIDE_SPAKE2_API glide_spake2_ctx *glide_spake2_ctx_new(
    int role,
    const uint8_t *my_name,
    size_t my_name_len,
    const uint8_t *their_name,
    size_t their_name_len) {
  if (role != GLIDE_SPAKE2_ROLE_ALICE && role != GLIDE_SPAKE2_ROLE_BOB) {
    return nullptr;
  }
  spake2_role_t bssl_role =
      (role == GLIDE_SPAKE2_ROLE_ALICE) ? spake2_role_alice : spake2_role_bob;

  SPAKE2_CTX *inner = SPAKE2_CTX_new(
      bssl_role,
      my_name,
      my_name_len,
      their_name,
      their_name_len);
  if (inner == nullptr) {
    return nullptr;
  }

  glide_spake2_ctx *wrapper =
      reinterpret_cast<glide_spake2_ctx *>(OPENSSL_malloc(sizeof(glide_spake2_ctx)));
  if (wrapper == nullptr) {
    SPAKE2_CTX_free(inner);
    return nullptr;
  }
  wrapper->inner = inner;
  return wrapper;
}

GLIDE_SPAKE2_API void glide_spake2_ctx_free(glide_spake2_ctx *ctx) {
  if (ctx == nullptr) {
    return;
  }
  if (ctx->inner != nullptr) {
    OPENSSL_cleanse(ctx->inner->private_key, sizeof(ctx->inner->private_key));
    OPENSSL_cleanse(ctx->inner->password_scalar, sizeof(ctx->inner->password_scalar));
    OPENSSL_cleanse(ctx->inner->password_hash, sizeof(ctx->inner->password_hash));
    SPAKE2_CTX_free(ctx->inner);
    ctx->inner = nullptr;
  }
  OPENSSL_free(ctx);
}

GLIDE_SPAKE2_API int glide_spake2_generate_msg(
    glide_spake2_ctx *ctx,
    uint8_t *out_msg,
    size_t *out_msg_len,
    size_t max_out_msg_len,
    const uint8_t *password,
    size_t password_len) {
  if (ctx == nullptr || ctx->inner == nullptr || out_msg == nullptr || out_msg_len == nullptr) {
    return 0;
  }
  return SPAKE2_generate_msg(
      ctx->inner,
      out_msg,
      out_msg_len,
      max_out_msg_len,
      password,
      password_len);
}

GLIDE_SPAKE2_API int glide_spake2_generate_msg_deterministic_for_test(
    glide_spake2_ctx *ctx,
    uint8_t *out_msg,
    size_t *out_msg_len,
    size_t max_out_msg_len,
    const uint8_t *password,
    size_t password_len,
    const uint8_t entropy64[64]) {
  if (ctx == nullptr || ctx->inner == nullptr || out_msg == nullptr || out_msg_len == nullptr || entropy64 == nullptr) {
    return 0;
  }
  memcpy(g_test_entropy, entropy64, 64);
  g_has_test_entropy = true;
  int rc = SPAKE2_generate_msg(
      ctx->inner,
      out_msg,
      out_msg_len,
      max_out_msg_len,
      password,
      password_len);
  OPENSSL_cleanse(g_test_entropy, sizeof(g_test_entropy));
  g_has_test_entropy = false;
  return rc;
}

GLIDE_SPAKE2_API int glide_spake2_process_msg(
    glide_spake2_ctx *ctx,
    uint8_t *out_key,
    size_t *out_key_len,
    size_t max_out_key_len,
    const uint8_t *their_msg,
    size_t their_msg_len) {
  if (ctx == nullptr || ctx->inner == nullptr || out_key == nullptr || out_key_len == nullptr || their_msg == nullptr) {
    return 0;
  }
  return SPAKE2_process_msg(
      ctx->inner,
      out_key,
      out_key_len,
      max_out_key_len,
      their_msg,
      their_msg_len);
}

}  // extern "C"

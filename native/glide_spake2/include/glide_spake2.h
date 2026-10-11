// Copyright 2026 Glide Project
// Private C ABI wrapper over BoringSSL SPAKE2 (pinned commit 3fed4f207543470b3d639db6ed40737e30308b51).
#ifndef GLIDE_SPAKE2_H
#define GLIDE_SPAKE2_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(GLIDE_SPAKE2_BUILD_SHARED)
#define GLIDE_SPAKE2_API __declspec(dllexport)
#else
#define GLIDE_SPAKE2_API
#endif
#elif defined(__GNUC__) || defined(__clang__)
#define GLIDE_SPAKE2_API __attribute__((visibility("default")))
#else
#define GLIDE_SPAKE2_API
#endif

#if defined(__cplusplus)
extern "C" {
#endif

#define GLIDE_SPAKE2_ROLE_ALICE 0
#define GLIDE_SPAKE2_ROLE_BOB 1

#define GLIDE_SPAKE2_MSG_SIZE 32
#define GLIDE_SPAKE2_KEY_SIZE 64

typedef struct glide_spake2_ctx_st glide_spake2_ctx;

GLIDE_SPAKE2_API const char *glide_spake2_boringssl_commit(void);

GLIDE_SPAKE2_API glide_spake2_ctx *glide_spake2_ctx_new(
    int role,
    const uint8_t *my_name,
    size_t my_name_len,
    const uint8_t *their_name,
    size_t their_name_len);

GLIDE_SPAKE2_API void glide_spake2_ctx_free(glide_spake2_ctx *ctx);

GLIDE_SPAKE2_API int glide_spake2_generate_msg(
    glide_spake2_ctx *ctx,
    uint8_t *out_msg,
    size_t *out_msg_len,
    size_t max_out_msg_len,
    const uint8_t *password,
    size_t password_len);

GLIDE_SPAKE2_API int glide_spake2_generate_msg_deterministic_for_test(
    glide_spake2_ctx *ctx,
    uint8_t *out_msg,
    size_t *out_msg_len,
    size_t max_out_msg_len,
    const uint8_t *password,
    size_t password_len,
    const uint8_t entropy64[64]);

GLIDE_SPAKE2_API int glide_spake2_process_msg(
    glide_spake2_ctx *ctx,
    uint8_t *out_key,
    size_t *out_key_len,
    size_t max_out_key_len,
    const uint8_t *their_msg,
    size_t their_msg_len);

#if defined(__cplusplus)
}
#endif

#endif  // GLIDE_SPAKE2_H

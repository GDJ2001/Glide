cmake_minimum_required(VERSION 3.16)
project(glide_spake2 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(glide_spake2 SHARED
    src/glide_spake2.cc
    boringssl/crypto/curve25519/spake25519.cc
    boringssl/crypto/curve25519/curve25519.cc
)

target_compile_definitions(glide_spake2 PRIVATE
    GLIDE_SPAKE2_BUILD_SHARED
    OPENSSL_NO_ASM
)

target_include_directories(glide_spake2
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/boringssl/include
        ${CMAKE_CURRENT_SOURCE_DIR}/boringssl/crypto
)

if(WIN32)
    target_link_libraries(glide_spake2 PRIVATE bcrypt)
endif()

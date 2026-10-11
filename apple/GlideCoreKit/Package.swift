// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "GlideCoreKit",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
        .watchOS(.v9),
        .tvOS(.v16)
    ],
    products: [
        .library(
            name: "GlideCoreKit",
            targets: ["GlideCoreKit"]),
    ],
    dependencies: [
        // Pure Swift + pinned BoringSSL SPAKE2 C target (3fed4f207543470b3d639db6ed40737e30308b51)
    ],
    targets: [
        .target(
            name: "CGlideSpake2",
            path: "Sources/CGlideSpake2",
            sources: [
                "glide_spake2.cc",
                "boringssl/crypto/curve25519/spake25519.cc",
                "boringssl/crypto/curve25519/curve25519.cc"
            ],
            publicHeadersPath: "include",
            cxxSettings: [
                .headerSearchPath("."),
                .headerSearchPath("boringssl/include")
            ]
        ),
        .target(
            name: "GlideCoreKit",
            dependencies: ["CGlideSpake2"]),
        .testTarget(
            name: "GlideCoreKitTests",
            dependencies: ["GlideCoreKit"]),
    ],
    cxxLanguageStandard: .cxx17
)

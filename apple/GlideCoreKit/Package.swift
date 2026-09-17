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
        // Pure Swift, zero third-party dependencies
    ],
    targets: [
        .target(
            name: "GlideCoreKit",
            dependencies: []),
        .testTarget(
            name: "GlideCoreKitTests",
            dependencies: ["GlideCoreKit"]),
    ]
)

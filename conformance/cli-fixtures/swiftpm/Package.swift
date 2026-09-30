// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "affected-swiftpm-fixture",
    targets: [
        .target(name: "Alpha"),
        .target(name: "Beta", dependencies: ["Alpha"]),
        .target(name: "Gamma"),
        .target(name: "Delta"),
        .testTarget(name: "AlphaTests", dependencies: ["Alpha"]),
        .testTarget(name: "BetaTests", dependencies: ["Beta"]),
        .testTarget(name: "GammaTests", dependencies: ["Gamma"]),
        .testTarget(name: "DeltaTests", dependencies: ["Delta"]),
    ]
)

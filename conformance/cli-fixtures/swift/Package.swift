// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "probe",
    targets: [
        .target(name: "probe"),
        .testTarget(name: "probeTests", dependencies: ["probe"]),
    ]
)

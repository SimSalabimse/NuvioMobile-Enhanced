// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AnalyzedSyncAesGcm",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    products: [
        .library(name: "AnalyzedSyncAesGcm", targets: ["AnalyzedSyncAesGcm"]),
    ],
    targets: [
        .target(name: "AnalyzedSyncAesGcm"),
        .testTarget(
            name: "AnalyzedSyncAesGcmTests",
            dependencies: ["AnalyzedSyncAesGcm"]
        ),
    ]
)

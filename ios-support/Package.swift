// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ShareInboxExtension",
    platforms: [.iOS(.v15), .macOS(.v13)],
    products: [.library(name: "ShareInboxExtension", targets: ["ShareInboxExtension"])],
    targets: [
        .target(name: "ShareInboxExtension"),
        .testTarget(name: "ShareInboxExtensionTests", dependencies: ["ShareInboxExtension"])
    ]
)

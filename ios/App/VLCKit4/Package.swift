// swift-tools-version: 5.9
import PackageDescription

// VLCKit 4 (libVLC 4) from VideoLAN's own build, the one their CocoaPods spec "VLCKit 4.0.0a25" ships.
// It brings Picture in Picture (VLCPictureInPictureDrawable); VLCKit 3 has none.
// The archive is large (~900 MB, every Apple platform); SwiftPM downloads it once and caches it.
let package = Package(
    name: "VLCKit4",
    platforms: [.iOS(.v15)],
    products: [.library(name: "VLCKit4", targets: ["VLCKit"])],
    targets: [
        .binaryTarget(
            name: "VLCKit",
            url: "https://download.videolan.org/cocoapods/unstable/VLCKit-4.0-20260929-1631.zip",
            checksum: "f37c8dbdd4427d1a3f5d75dc4b8bd1ae863cef4426f9a405a2fd1c5c9012f1fb"
        )
    ]
)

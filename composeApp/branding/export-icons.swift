#!/usr/bin/env swift
// Deterministic size/layout exports from the selected generated logo; no image-generation dependency.
import AppKit
import ImageIO
import UniformTypeIdentifiers

let root = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : FileManager.default.currentDirectoryPath)
let brand = root.appendingPathComponent("composeApp/branding")
let res = root.appendingPathComponent("composeApp/src/androidMain/res")
let url = brand.appendingPathComponent("passportemu-logo-source.png")
guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fatalError("Missing logo source") }
// Alpha bounds remove only transparent margins. Keep all opaque/semitransparent logo pixels.
let width = image.width, height = image.height
var pixels = [UInt8](repeating: 0, count: width * height * 4)
var minX = width, minY = height, maxX = 0, maxY = 0
pixels.withUnsafeMutableBytes { buffer in
    guard let context = CGContext(data: buffer.baseAddress, width: width, height: height,
        bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { fatalError("No pixel context") }
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    for y in 0..<height { for x in 0..<width {
        if buffer[y * width * 4 + x * 4 + 3] > 0 {
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
    }}
}
guard minX <= maxX, minY <= maxY,
      let cropped = image.cropping(to: CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)) else {
    fatalError("Logo has no visible pixels")
}
let background = CGColor(red: 211/255, green: 228/255, blue: 1, alpha: 1)
func export(_ destination: URL, size: Int, occupancy: CGFloat, opaque: Bool, round: Bool = false) {
    guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { fatalError("No context") }
    let bounds = CGRect(x: 0, y: 0, width: size, height: size)
    if round { context.addEllipse(in: bounds); context.clip() }
    if opaque { context.setFillColor(background); context.fill(bounds) }
    let scale = CGFloat(size) * occupancy / CGFloat(max(cropped.width, cropped.height))
    let w = CGFloat(cropped.width) * scale, h = CGFloat(cropped.height) * scale
    context.interpolationQuality = .high
    context.draw(cropped, in: CGRect(x: (CGFloat(size)-w)/2, y: (CGFloat(size)-h)/2, width: w, height: h))
    guard let rendered = context.makeImage(),
          let output = CGImageDestinationCreateWithURL(destination as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        fatalError("Cannot save \(destination)")
    }
    CGImageDestinationAddImage(output, rendered, nil)
    guard CGImageDestinationFinalize(output) else { fatalError("Cannot finalize") }
}
// Adaptive icon visible art occupies a 60dp square inside the 108dp canvas, safely inside launcher masks.
export(res.appendingPathComponent("drawable-nodpi/ic_launcher_art.png"), size: 432, occupancy: 60/108, opaque: false)
for (density, size) in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)] {
    export(res.appendingPathComponent("mipmap-\(density)/ic_launcher.png"), size: size, occupancy: 0.76, opaque: true)
    export(res.appendingPathComponent("mipmap-\(density)/ic_launcher_round.png"), size: size, occupancy: 0.72, opaque: true, round: true)
}
// A transparent in-app mascot and a shareable icon preview, from the same mark.
export(root.appendingPathComponent("composeApp/src/commonMain/composeResources/drawable/passportemu_mascot.png"),
    size: 768, occupancy: 0.90, opaque: false)
export(brand.appendingPathComponent("passportemu-icon-preview.png"), size: 512, occupancy: 0.72, opaque: true, round: true)
print("Exported adaptive, legacy, round and in-app PassportEmu assets")

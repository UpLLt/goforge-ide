import AppKit

// Original vector-like GF mark; no upstream logo or artwork is copied.
let output = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let pixels = size * scale
        let image = NSImage(size: NSSize(width: pixels, height: pixels))
        image.lockFocus()
        let factor = CGFloat(pixels) / 128
        let transform = AffineTransform(scale: factor)
        (transform as NSAffineTransform).concat()
        NSColor(calibratedRed: 16/255, green: 43/255, blue: 63/255, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: 4, y: 4, width: 120, height: 120), xRadius: 28, yRadius: 28).fill()
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        ("GF" as NSString).draw(in: NSRect(x: 8, y: 29, width: 112, height: 75), withAttributes: [
            .font: NSFont.monospacedSystemFont(ofSize: 58, weight: .bold),
            .foregroundColor: NSColor(calibratedRed: 87/255, green: 214/255, blue: 206/255, alpha: 1),
            .paragraphStyle: paragraph
        ])
        image.unlockFocus()
        let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
        let data = bitmap.representation(using: .png, properties: [:])!
        let suffix = scale == 1 ? "" : "@2x"
        try data.write(to: output.appendingPathComponent("icon_\(size)x\(size)\(suffix).png"))
    }
}

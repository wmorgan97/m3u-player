import SwiftUI
import WidgetKit
import UIKit

// Look: the app's own — near-black, one blue accent, no red. Quiet type, generous spacing.
enum Style {
    static let bg = Color(red: 0.039, green: 0.047, blue: 0.067)          // #0a0c11
    static let card = Color(red: 0.086, green: 0.098, blue: 0.13)
    static let accent = Color(red: 0.357, green: 0.576, blue: 1.0)        // #5b93ff
    static let secondary = Color.white.opacity(0.62)
    static let tertiary = Color.white.opacity(0.4)
}

extension View {
    /// Declared as the widget's background, so iOS can drop it in StandBy / tinted Home Screens.
    func widgetBackground<B: View>(_ b: B) -> some View { containerBackground(for: .widget) { b } }
}

/// Images for widgets must be in hand before the timeline is returned (no AsyncImage). Downloaded once, kept in the
/// extension's own caches folder (keyed by URL), shrunk to what a widget draws.
enum ImageCache {
    static let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("widget-img", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    static func file(_ key: String) -> URL { dir.appendingPathComponent(M3UShared.hash(key)) }

    static func cached(_ key: String) -> UIImage? {
        if let d = try? Data(contentsOf: file(key)), let i = UIImage(data: d) { return i }
        return M3UShared.image(key).flatMap(UIImage.init(data:))   // a crest the app saved for the Live Activity
    }

    /// `key` names the image (its original URL); `url` is where to fetch it (often the server's /img thumbnail).
    static func load(_ key: String, from url: URL?, maxSide: CGFloat) async -> UIImage? {
        if let i = cached(key) { return i }
        guard let url = url, let (data, resp) = try? await URLSession.shared.data(from: url),
              (resp as? HTTPURLResponse)?.statusCode ?? 200 < 400, let img = UIImage(data: data) else { return nil }
        let s = min(1, maxSide / max(img.size.width, img.size.height))
        let size = CGSize(width: (img.size.width * s).rounded(), height: (img.size.height * s).rounded())
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: fmt).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        if let out = small.pngData() { try? out.write(to: file(key)) }
        prune()
        return small
    }
    /// Keep the folder small: drop images not used for two weeks.
    static func prune() {
        let old = Date().addingTimeInterval(-14 * 86400)
        for f in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            if let d = try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate, d < old { try? FileManager.default.removeItem(at: f) }
        }
    }
}

/// A team crest, or its initials in a soft circle when there's no picture.
struct Crest: View {
    var image: UIImage?
    var name: String
    var size: CGFloat
    var body: some View {
        if let i = image {
            if i.isDark {
                // A dark crest (Spurs' navy) would vanish on the black background: sit it on a soft light disc.
                Circle().fill(Color.white.opacity(0.88)).frame(width: size, height: size)
                    .overlay(Image(uiImage: i).resizable().scaledToFit().padding(size * 0.14))
            } else {
                Image(uiImage: i).resizable().scaledToFit().frame(width: size, height: size)
            }
        } else {
            Circle().fill(Color.white.opacity(0.12)).frame(width: size, height: size)
                .overlay(Text(initials).font(.system(size: size * 0.36, weight: .semibold, design: .rounded)).foregroundColor(.white.opacity(0.85)))
        }
    }
    var initials: String {
        let w = name.split(separator: " ").filter { !["FC", "AFC", "CF"].contains($0.uppercased()) }
        return String((w.count > 1 ? String(w[0].prefix(1)) + String(w[1].prefix(1)) : String(w.first?.prefix(2) ?? ""))).uppercased()
    }
}

enum Fmt {
    static func time(_ d: Date) -> String { let f = DateFormatter(); f.dateFormat = "HH:mm"; return f.string(from: d) }
    /// "20:00", "Tomorrow 15:00", "Sat 17:30", "12 Nov".
    static func when(_ d: Date, now: Date = Date()) -> String {
        let cal = Calendar.current
        if cal.isDate(d, inSameDayAs: now) { return time(d) }
        if cal.isDateInTomorrow(d) { return "Tomorrow \(time(d))" }
        let f = DateFormatter()
        f.dateFormat = d.timeIntervalSince(now) < 6 * 86400 ? "EEE HH:mm" : "d MMM"
        return f.string(from: d)
    }
    /// "1 h 12 min left", "38 min left".
    static func left(_ secs: Double) -> String {
        let m = Int((secs / 60).rounded())
        if m >= 60 { return m % 60 == 0 ? "\(m / 60) h left" : "\(m / 60) h \(m % 60) min left" }
        return "\(max(1, m)) min left"
    }
}

/// A thin progress line, the app's own.
struct ProgressLine: View {
    var fraction: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.22))
                Capsule().fill(Style.accent).frame(width: max(3, g.size.width * fraction))
            }
        }.frame(height: 3)
    }
}

extension UIImage {
    /// Mostly dark where it isn't transparent (average of the opaque pixels, on a 12x12 sample).
    var isDark: Bool {
        guard let cg = cgImage else { return false }
        let n = 12, bytes = n * n * 4
        var px = [UInt8](repeating: 0, count: bytes)
        guard let ctx = CGContext(data: &px, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: n, height: n))
        var lum = 0.0, weight = 0.0
        for i in stride(from: 0, to: bytes, by: 4) {
            let a = Double(px[i + 3]) / 255
            guard a > 0.3 else { continue }
            lum += (0.2126 * Double(px[i]) + 0.7152 * Double(px[i + 1]) + 0.0722 * Double(px[i + 2])) / 255   // premultiplied
            weight += a
        }
        return weight > 0 && lum / weight < 0.22
    }
}

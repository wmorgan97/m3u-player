import SwiftUI
import WidgetKit

// "Continue watching": the films and episodes you're part-way through, newest first, with their artwork and the time
// left. Tapping one opens the app and resumes it where you left off (m3uplayer://resume?k=…).
// Fresh from your server (the synced progress, so it follows what you watched on any device) every 30 minutes, and
// straight away whenever the app saves new progress.

struct CWEntry: TimelineEntry {
    struct Item { var item: CWItem; var image: UIImage? }
    var date: Date
    var items: [Item]
    var connected: Bool
}

struct CWProvider: TimelineProvider {
    func placeholder(in context: Context) -> CWEntry { .sample }
    func getSnapshot(in context: Context, completion: @escaping (CWEntry) -> Void) {
        if context.isPreview { completion(.sample); return }
        Task { completion(await load(count: context.family == .systemSmall ? 1 : 2)) }
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<CWEntry>) -> Void) {
        Task {
            let e = await load(count: context.family == .systemSmall ? 1 : 2)
            completion(Timeline(entries: [e], policy: .after(Date().addingTimeInterval(30 * 60))))
        }
    }

    func load(count: Int) async -> CWEntry {
        guard let cfg = M3UShared.config else { return CWEntry(date: Date(), items: [], connected: false) }
        let snap = M3UShared.snapshot
        var list = snap?.cw ?? []
        // The server's copy wins (another device may have moved on); the app's copy supplies titles and backdrops.
        if let (_, progress) = try? await M3UAPI.sync(cfg) {
            let known = Dictionary((snap?.cw ?? []).map { ($0.k, $0) }, uniquingKeysWith: { a, _ in a })
            list = progress.compactMap { url, e -> CWItem? in
                guard let it = e.item, it.kind == "movie" || it.kind == "episode", e.done != true, e.hidden != true,
                      let t = e.t, let d = e.d, t > 30, d > 0 else { return nil }
                let k = M3UShared.hash(url)
                if var mine = known[k] { mine.t = t; mine.d = d; mine.at = e.at ?? mine.at; return mine }
                return CWItem(k: k, url: url, name: it.name ?? "", sub: nil, art: it.logo, poster: true, t: t, d: d, at: e.at ?? 0)
            }.sorted { $0.at > $1.at }
        }
        var out: [CWEntry.Item] = []
        for it in list.prefix(count) {
            var img: UIImage?
            if let art = it.art, !art.isEmpty {
                img = await ImageCache.load(art, from: M3UAPI.thumb(cfg, art, width: 500) ?? URL(string: art), maxSide: 520)
            }
            out.append(.init(item: it, image: img))
        }
        return CWEntry(date: Date(), items: out, connected: true)
    }
}

extension CWEntry {
    static let sample = CWEntry(date: Date(), items: [
        .init(item: CWItem(k: "a", name: "The Night Manager", sub: "S2 E3", art: nil, t: 1500, d: 3600, at: 0), image: nil),
        .init(item: CWItem(k: "b", name: "Dune: Part Two", sub: nil, art: nil, t: 4000, d: 9960, at: 0), image: nil),
    ], connected: true)
}

/// Artwork, a soft fade at the bottom for the words, or a quiet gradient when there's none.
private struct Artwork: View {
    var image: UIImage?
    var body: some View {
        ZStack {
            LinearGradient(colors: [Style.card, Style.bg], startPoint: .top, endPoint: .bottom)
            // Fill the frame without widening it (a scaledToFill image otherwise pushes the layout past the edges).
            if let i = image { Color.clear.overlay(Image(uiImage: i).resizable().scaledToFill()).clipped() }
            else { Image(systemName: "film").font(.system(size: 22, weight: .light)).foregroundColor(Style.tertiary) }
        }
    }
}

struct CWView: View {
    var entry: CWEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        Group {
            if !entry.connected { message("Open M3U Player+ and connect your server", icon: "link") }
            else if entry.items.isEmpty { message("Nothing to continue", icon: "play.rectangle") }
            else if family == .systemSmall { small(entry.items[0]) }
            else { medium }
        }
        .widgetBackground(Style.bg)
    }

    func message(_ text: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Continue watching").font(.caption.weight(.semibold)).foregroundColor(Style.secondary)
            Spacer()
            Image(systemName: icon).font(.title3).foregroundColor(Style.tertiary)
            Text(text).font(.footnote).foregroundColor(Style.secondary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(16)
        .widgetURL(DeepLink.home)
    }

    func small(_ it: CWEntry.Item) -> some View {
        ZStack(alignment: .bottomLeading) {
            Artwork(image: it.image)
            LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 3) {
                Text(it.item.name).font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(2)
                Text([it.item.sub, Fmt.left(it.item.left)].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption2).foregroundColor(Style.secondary).lineLimit(1)
                ProgressLine(fraction: it.item.fraction).padding(.top, 3)
            }
            .padding(14)
        }
        .widgetURL(DeepLink.resume(it.item))
    }

    var medium: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Continue watching").font(.caption.weight(.semibold)).foregroundColor(Style.secondary)
            HStack(alignment: .top, spacing: 12) {
                ForEach(Array(entry.items.prefix(2).enumerated()), id: \.offset) { _, it in
                    Link(destination: DeepLink.resume(it.item)) {
                        VStack(alignment: .leading, spacing: 4) {
                            Artwork(image: it.image)
                                .frame(maxWidth: .infinity).frame(height: 70)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .overlay(alignment: .bottom) { ProgressLine(fraction: it.item.fraction).padding(.horizontal, 8).padding(.bottom, 6) }
                            Text(it.item.name).font(.footnote.weight(.semibold)).foregroundColor(.white).lineLimit(1)
                            Text([it.item.sub, Fmt.left(it.item.left)].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption2).foregroundColor(Style.secondary).lineLimit(1)
                        }
                    }
                }
                if entry.items.count == 1 { Color.clear.frame(maxWidth: .infinity) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(16)
    }
}

struct ContinueWatchingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ContinueWatching", provider: CWProvider()) { CWView(entry: $0) }
            .configurationDisplayName("Continue watching")
            .description("Pick up a film or episode where you left off.")
            .supportedFamilies([.systemSmall, .systemMedium])
            .contentMarginsDisabled()   // the small one's picture runs to the edges
    }
}

import SwiftUI
import WidgetKit

// "My teams": the next game (or the one on now) for the teams you follow in the app, with crests, kick-off, channel,
// and the score while it's on. Fixtures come straight from your server's /fixtures; which games are your teams' comes
// from the app (its matcher knows the aliases) plus a simple name match here for games the app hasn't seen yet.
//
// Honest about freshness: a widget is redrawn when iOS allows (about every 30 minutes; every 10 while a game is on,
// within iOS's daily budget), so a live score always says the time it was fetched.

struct TeamsEntry: TimelineEntry {
    struct Game { var f: Fixture; var home: UIImage?; var away: UIImage?; var channel: String?; var mineIsHome: Bool }
    var date: Date
    var games: [Game]
    var fetched: Date?          // when these scores came from the server (nil: never)
    var connected: Bool
    var hasFollows: Bool
}

struct TeamsProvider: TimelineProvider {
    func placeholder(in context: Context) -> TeamsEntry { .sample }
    func getSnapshot(in context: Context, completion: @escaping (TeamsEntry) -> Void) {
        if context.isPreview { completion(.sample); return }
        Task { completion(await load(count: count(context.family))) }
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<TeamsEntry>) -> Void) {
        Task {
            let e = await load(count: count(context.family))
            let now = Date()
            var next = now.addingTimeInterval(30 * 60)
            if e.games.contains(where: { $0.f.isLive }) { next = now.addingTimeInterval(10 * 60) }
            else if let k = e.games.first(where: { $0.f.status == "scheduled" })?.f.startDate, k > now, k < next { next = k.addingTimeInterval(60) }
            completion(Timeline(entries: [e], policy: .after(next)))
        }
    }
    func count(_ f: WidgetFamily) -> Int { f == .systemMedium ? 3 : 1 }

    static let cacheFile = ImageCache.dir.appendingPathComponent("games.json")
    private struct Cached: Codable { var at: Date; var games: [Fixture] }

    func load(count: Int) async -> TeamsEntry {
        guard let cfg = M3UShared.config else { return TeamsEntry(date: Date(), games: [], fetched: nil, connected: false, hasFollows: false) }
        let snap = M3UShared.snapshot
        var follows = snap?.follows ?? []
        if snap == nil, let (f, _) = try? await M3UAPI.sync(cfg) { follows = f }
        let ids = Set(snap?.followed ?? [])
        var games: [Fixture], fetched: Date?
        if let list = try? await M3UAPI.fixtures(cfg, days: 7) {
            games = Follow.games(list, follows: follows, ids: ids)
            fetched = Date()
            if let d = try? JSONEncoder().encode(Cached(at: fetched!, games: games)) { try? d.write(to: Self.cacheFile) }
        } else if let d = try? Data(contentsOf: Self.cacheFile), let c = try? JSONDecoder().decode(Cached.self, from: d) {
            // The server didn't answer: last known list, minus games over by now. A game that was on stays marked with
            // the time its score is from.
            let ms = Date().timeIntervalSince1970 * 1000
            games = c.games.filter { $0.end > ms || $0.isLive }; fetched = c.at
        } else { games = []; fetched = nil }
        // Results only while nothing is coming: a just-finished game is shown below upcoming ones.
        let shown = Array(games.prefix(count))
        var out: [TeamsEntry.Game] = []
        for f in shown {
            async let h = crest(f.home?.crest), a = crest(f.away?.crest)
            // Which side is yours (for "v Spurs"): the away side only when a follow names it and not the home side.
            let mineAway = follows.contains { Follow.isTeam($0, f.away) } && !follows.contains { Follow.isTeam($0, f.home) }
            out.append(.init(f: f, home: await h, away: await a, channel: snap?.channels[f.id], mineIsHome: !mineAway))
        }
        return TeamsEntry(date: Date(), games: out, fetched: fetched, connected: true, hasFollows: !follows.isEmpty || !ids.isEmpty)
    }
    private func crest(_ src: String?) async -> UIImage? {
        guard let s = src, !s.isEmpty else { return nil }
        return await ImageCache.load(s, from: URL(string: s), maxSide: 96)
    }
}

extension TeamsEntry {
    static let sample: TeamsEntry = {
        let now = Date().timeIntervalSince1970 * 1000
        let live = Fixture(id: "s1", sport: "football", comp: "Premier League", start: now - 3_600_000, end: now + 3_600_000, status: "live", minute: "63'",
                           home: FxTeam(name: "Arsenal", score: "2"), away: FxTeam(name: "Tottenham Hotspur", short: "Tottenham", score: "1"))
        let next = Fixture(id: "s2", sport: "football", comp: "Champions League", start: now + 3 * 86_400_000, end: now + 3 * 86_400_000 + 7_200_000, status: "scheduled",
                           home: FxTeam(name: "Inter Milan", short: "Inter"), away: FxTeam(name: "Arsenal"))
        return TeamsEntry(date: Date(), games: [.init(f: live, channel: "Sky Sports Main Event", mineIsHome: true), .init(f: next, channel: "TNT Sports 1", mineIsHome: false)],
                          fetched: Date(), connected: true, hasFollows: true)
    }()
}

extension TeamsEntry.Game {
    init(f: Fixture, channel: String?, mineIsHome: Bool) { self.init(f: f, home: nil, away: nil, channel: channel, mineIsHome: mineIsHome) }
    var mine: FxTeam? { mineIsHome ? f.home : f.away }
    var opponent: FxTeam? { mineIsHome ? f.away : f.home }
    var score: String { "\(f.home?.score ?? "0")–\(f.away?.score ?? "0")" }
    var statusLine: String {
        switch f.status {
        case "live": return f.minute?.isEmpty == false ? f.minute! : "Live"
        case "final": return "Full time"
        default: return Fmt.when(f.startDate)
        }
    }
}

// MARK: - Home Screen

struct TeamsView: View {
    var entry: TeamsEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        Group {
            if !entry.connected { note("Open M3U Player+ and connect your server") }
            else if !entry.hasFollows { note("Follow a team in Sports to see its next game here") }
            else if entry.games.isEmpty { note(entry.fetched == nil ? "Couldn’t reach your server" : "No games in the next week") }
            else if family == .systemMedium { medium }
            else { small(entry.games[0]) }
        }
        .padding(16)
        .widgetBackground(Style.bg)
    }

    func note(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("My teams").font(.caption.weight(.semibold)).foregroundColor(Style.secondary)
            Spacer()
            Image(systemName: "sportscourt").font(.title3).foregroundColor(Style.tertiary)
            Text(text).font(.footnote).foregroundColor(Style.secondary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetURL(DeepLink.sports)
    }

    /// "Updated 20:41" under a score that is on now: a widget can't promise it's this minute's.
    @ViewBuilder func freshness(_ g: TeamsEntry.Game) -> some View {
        if g.f.isLive, let at = entry.fetched {
            Text("Updated \(Fmt.time(at))").font(.caption2).foregroundColor(Style.tertiary)
        }
    }

    func small(_ g: TeamsEntry.Game) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(g.f.comp ?? "").font(.caption2.weight(.semibold)).foregroundColor(Style.secondary).lineLimit(1)
            Spacer(minLength: 6)
            HStack(alignment: .center) {
                Crest(image: g.home, name: g.f.home?.name ?? "", size: 38)
                Spacer(minLength: 4)
                if g.f.isLive || g.f.status == "final" {
                    Text(g.score).font(.system(size: 22, weight: .semibold, design: .rounded)).monospacedDigit().foregroundColor(.white).minimumScaleFactor(0.7)
                } else {
                    Text("v").font(.footnote).foregroundColor(Style.tertiary)
                }
                Spacer(minLength: 4)
                Crest(image: g.away, name: g.f.away?.name ?? "", size: 38)
            }
            Spacer(minLength: 6)
            Text(g.f.isLive || g.f.status == "final" ? (g.opponent.map { "v \($0.label)" } ?? g.f.name) : g.f.name)
                .font(.footnote.weight(.semibold)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.85)
            HStack(spacing: 4) {
                if g.f.isLive { Circle().fill(Style.accent).frame(width: 5, height: 5) }
                Text(g.statusLine).foregroundColor(g.f.isLive ? Style.accent : Style.secondary)
                if !g.f.isLive, let ch = g.channel { Text("· \(ch)").foregroundColor(Style.tertiary) }
            }.font(.caption2.weight(.medium)).lineLimit(1)
            freshness(g)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetURL(DeepLink.fixture(g.f.id))
    }

    var medium: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("My teams").font(.caption.weight(.semibold)).foregroundColor(Style.secondary)
                Spacer()
                if entry.games.contains(where: { $0.f.isLive }), let at = entry.fetched {
                    Text("Updated \(Fmt.time(at))").font(.caption2).foregroundColor(Style.tertiary)
                }
            }
            ForEach(Array(entry.games.prefix(3).enumerated()), id: \.offset) { i, g in
                if i > 0 { Rectangle().fill(Color.white.opacity(0.08)).frame(height: 0.5) }
                Link(destination: DeepLink.fixture(g.f.id)) { row(g) }
            }
            Spacer(minLength: 0)
        }
    }

    func row(_ g: TeamsEntry.Game) -> some View {
        HStack(spacing: 10) {
            HStack(spacing: -6) {
                Crest(image: g.home, name: g.f.home?.name ?? "", size: 24)
                Crest(image: g.away, name: g.f.away?.name ?? "", size: 24)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(g.f.name).font(.footnote.weight(.semibold)).foregroundColor(.white).lineLimit(1)
                Text([g.f.comp, g.channel].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption2).foregroundColor(Style.secondary).lineLimit(1)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 1) {
                if g.f.isLive || g.f.status == "final" {
                    Text(g.score).font(.system(.subheadline, design: .rounded).weight(.semibold)).monospacedDigit().foregroundColor(.white)
                }
                Text(g.statusLine).font(.caption2.weight(.medium)).foregroundColor(g.f.isLive ? Style.accent : Style.secondary).lineLimit(1)
            }
        }
    }
}

struct MyTeamsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "MyTeams", provider: TeamsProvider()) { TeamsView(entry: $0) }
            .configurationDisplayName("My teams")
            .description("The next game for the teams you follow, and the score while it’s on.")
            .supportedFamilies([.systemSmall, .systemMedium])
            .contentMarginsDisabled()
    }
}

// MARK: - Lock Screen

struct NextGameView: View {
    var entry: TeamsEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        Group {
            if let g = entry.games.first {
                switch family {
                case .accessoryCircular: circular(g)
                case .accessoryInline: inline(g)
                default: rectangular(g)
                }
            } else {
                Image(systemName: "sportscourt").widgetAccentable()
            }
        }
        .widgetURL(entry.games.first.map { DeepLink.fixture($0.f.id) } ?? DeepLink.sports)
        .containerBackground(for: .widget) { AccessoryWidgetBackground() }
    }

    func code(_ t: FxTeam?) -> String {
        let w = (t?.short ?? t?.name ?? "").uppercased().split(separator: " ")
        return String(w.first?.prefix(3) ?? "")
    }

    func circular(_ g: TeamsEntry.Game) -> some View {
        VStack(spacing: 0) {
            Text(g.f.isLive ? (g.f.minute ?? "Live") : "v \(code(g.opponent))").font(.system(size: 10, weight: .semibold)).widgetAccentable()
            Text(g.f.isLive || g.f.status == "final" ? g.score : Fmt.time(g.f.startDate))
                .font(.system(size: 15, weight: .semibold, design: .rounded)).monospacedDigit().minimumScaleFactor(0.6)
            if !g.f.isLive && g.f.status == "scheduled" && !Calendar.current.isDateInToday(g.f.startDate) {
                Text(dayShort(g.f.startDate)).font(.system(size: 9)).foregroundStyle(.secondary)
            }
        }
    }

    func rectangular(_ g: TeamsEntry.Game) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(g.f.name).font(.headline).widgetAccentable().lineLimit(1).minimumScaleFactor(0.8)
            if g.f.isLive || g.f.status == "final" {
                Text("\(g.score) · \(g.statusLine)").font(.body.weight(.semibold)).monospacedDigit()
                if g.f.isLive, let at = entry.fetched { Text("Updated \(Fmt.time(at))").font(.caption2).foregroundStyle(.secondary) }
            } else {
                Text(Fmt.when(g.f.startDate)).font(.body)
                Text(g.channel ?? g.f.comp ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    func inline(_ g: TeamsEntry.Game) -> some View {
        Text(g.f.isLive ? "\(g.f.name) \(g.score)" : "\(g.f.name) · \(Fmt.when(g.f.startDate))")
    }

    func dayShort(_ d: Date) -> String {
        if Calendar.current.isDateInTomorrow(d) { return "TMRW" }
        let f = DateFormatter(); f.dateFormat = "EEE"; return f.string(from: d).uppercased()
    }
}

struct NextGameWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NextGame", provider: TeamsProvider()) { NextGameView(entry: $0) }
            .configurationDisplayName("Next game")
            .description("Your team’s next kick-off, or the score while it’s on.")
            .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

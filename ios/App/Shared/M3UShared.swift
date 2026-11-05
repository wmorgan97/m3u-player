import Foundation
import Security

// Shared by the app and its widget extension (compiled into both targets).
//
// How the two talk without an App Group (a free Apple developer team can't use App Groups): through a shared keychain
// access group, "<team>.com.wmorgan97.m3uplayer.shared" (free profiles allow <team>.*). The app writes there
//   config     the server address + key, so the widgets can ask the server themselves
//   snapshot   what the app knows that the server doesn't: nicer titles and backdrops for Continue watching, which
//              fixtures your followed teams play (its own matcher knows "Spurs" = Tottenham…) and the channel for each
//   img.<hash> small crests for the Live Activity (it can't load images from the network)
// Everything is small; keychain items are fine for a few KB each.

enum M3UShared {
    static let service = "com.wmorgan97.m3uplayer.shared"
    /// "$(AppIdentifierPrefix)com.wmorgan97.m3uplayer.shared", put in each target's Info.plist at build time.
    static var accessGroup: String? {
        guard let g = Bundle.main.object(forInfoDictionaryKey: "M3UKeychainGroup") as? String, !g.isEmpty, !g.hasPrefix("$(") else { return nil }
        return g
    }

    private static func query(_ account: String, group: String?) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        if let g = group { q[kSecAttrAccessGroup as String] = g }
        return q
    }

    static func write(_ account: String, _ data: Data?) {
        let q = query(account, group: accessGroup)
        guard let data = data else { SecItemDelete(q as CFDictionary); return }
        let attrs: [String: Any] = [kSecValueData as String: data,
                                    // readable while the phone is locked (lock-screen widgets, Live Activity)
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock]
        var status = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(q.merging(attrs) { $1 } as CFDictionary, nil) }
        if status != errSecSuccess { NSLog("M3UShared: keychain write %@ failed: %d", account, status) }
    }

    static func read(_ account: String) -> Data? {
        var q = query(account, group: accessGroup)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    static func load<T: Decodable>(_ type: T.Type, _ account: String) -> T? {
        read(account).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }
    static func save<T: Encodable>(_ value: T?, _ account: String) {
        write(account, value.flatMap { try? JSONEncoder().encode($0) })
    }

    static var config: M3UConfig? { load(M3UConfig.self, "config") }
    static var snapshot: M3USnapshot? { load(M3USnapshot.self, "snapshot") }

    // Small images (crests) for the Live Activity, which can't fetch them itself.
    static func image(_ url: String) -> Data? { url.isEmpty ? nil : read("img.\(hash(url))") }
    static func putImage(_ url: String, _ data: Data) { write("img.\(hash(url))", data) }

    /// FNV-1a over the UTF-8 bytes, as hex: the same function as fnv() in js/app.js (deep links name items by it).
    static func hash(_ s: String) -> String {
        var h: UInt32 = 0x811c9dc5
        for b in s.utf8 { h ^= UInt32(b); h = h &* 0x01000193 }
        return String(h, radix: 16)
    }
}

// MARK: - what the app hands over

struct M3UConfig: Codable {
    var base: String        // "https://host:8443/" (the server's root; API paths are appended)
    var key: String
}

struct M3USnapshot: Codable {
    var at: Double = 0                     // ms
    var follows: [String] = []             // followed teams / names, as typed
    var followed: [String] = []            // fixture ids the app's matcher says are followed teams' games
    var channels: [String: String] = [:]   // fixture id -> channel name (best copy on your channels)
    var cw: [CWItem] = []                  // Continue watching as the app shows it
    var liveActivities: Bool = true
    var hideScores: Bool? = nil            // Settings → Hide scores: "Live" / "FT" instead of the score, no live-score activities
}

struct CWItem: Codable, Hashable {
    var k: String            // M3UShared.hash(progress url)
    var url: String = ""     // the progress key (matched against the server's copy; never put in a deep link)
    var name: String
    var sub: String?
    var art: String?         // backdrop (16:9) or poster, full URL
    var poster: Bool?        // art is a poster, not a backdrop
    var t: Double
    var d: Double
    var at: Double
    var left: Double { max(0, d - t) }
    var fraction: Double { d > 0 ? min(1, max(0, t / d)) : 0 }
}

// MARK: - fixtures (shape: js/fixtures.js)

struct FxTeam: Codable, Hashable {
    var name: String
    var short: String?
    var crest: String?
    var score: String?
    var nick: String?
    var label: String { (name.count <= 14 || (short ?? "").isEmpty) ? name : short! }
}

struct Fixture: Codable, Identifiable, Hashable {
    var id: String
    var sport: String?
    var comp: String?
    var start: Double
    var end: Double
    var status: String        // scheduled | live | final | postponed
    var minute: String?
    var home: FxTeam?
    var away: FxTeam?
    var title: String?
    var session: String?
    var women: Bool?

    var startDate: Date { Date(timeIntervalSince1970: start / 1000) }
    var isLive: Bool { status == "live" }
    var name: String {
        if let h = home, let a = away { return "\(h.label) v \(a.label)" }
        return [title, session].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

private struct FixturesReply: Decodable { var fixtures: [Fixture]? }

struct ProgressEntry: Decodable {
    struct Item: Decodable { var kind: String?; var name: String?; var url: String?; var logo: String? }
    var t: Double?; var d: Double?; var at: Double?; var done: Bool?; var hidden: Bool?; var item: Item?
}
private struct SyncReply: Decodable {
    struct Follow: Decodable { var q: String?; var deleted: Bool? }
    var progress: [String: ProgressEntry]?
    var follows: [String: Follow]?
}

// MARK: - the server

enum M3UAPI {
    static func url(_ cfg: M3UConfig, _ path: String, _ extra: [URLQueryItem] = []) -> URL? {
        var c = URLComponents(string: cfg.base.hasSuffix("/") ? cfg.base + path : cfg.base + "/" + path)
        c?.queryItems = (cfg.key.isEmpty ? [] : [URLQueryItem(name: "key", value: cfg.key)]) + extra
        return c?.url
    }
    static func get(_ url: URL?, timeout: TimeInterval = 12) async throws -> Data {
        guard let url = url else { throw URLError(.badURL) }
        var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        r.setValue("M3UWidgets", forHTTPHeaderField: "X-Client")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        return data
    }
    static func fixtures(_ cfg: M3UConfig, days: Int = 7) async throws -> [Fixture] {
        let d = try await get(url(cfg, "fixtures", [URLQueryItem(name: "days", value: String(days))]))
        return try JSONDecoder().decode(FixturesReply.self, from: d).fixtures ?? []
    }
    /// The synced state: (follows, progress).
    static func sync(_ cfg: M3UConfig) async throws -> ([String], [String: ProgressEntry]) {
        let d = try await get(url(cfg, "sync"))
        let s = try JSONDecoder().decode(SyncReply.self, from: d)
        let follows = (s.follows ?? [:]).values.filter { $0.deleted != true }.compactMap { $0.q }
        return (follows, s.progress ?? [:])
    }
    /// A server-shrunk JPEG (for photos: posters, backdrops).
    static func thumb(_ cfg: M3UConfig, _ src: String, width: Int) -> URL? {
        guard src.lowercased().hasPrefix("http") else { return nil }
        return url(cfg, "img", [URLQueryItem(name: "w", value: String(width)), URLQueryItem(name: "u", value: src)])
    }
}

// MARK: - followed teams (a lighter version of followMatches in js/fixtures.js; the app's own list of ids comes first)

enum Follow {
    static func fold(_ s: String) -> String {
        let plain = s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
        let mapped = plain.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
        return String(mapped).split(separator: " ").joined(separator: " ")
    }
    static let women = try! NSRegularExpression(pattern: "\\b(women|womens|ladies|wfc|w)\\b")
    static func matches(_ q: String, _ f: Fixture) -> Bool {
        var k = fold(q)
        let isWomen = women.firstMatch(in: k, range: NSRange(k.startIndex..., in: k)) != nil
        if isWomen { k = fold(women.stringByReplacingMatches(in: k, range: NSRange(k.startIndex..., in: k), withTemplate: "")) }
        guard !k.isEmpty, isWomen == (f.women ?? false), let h = f.home, let a = f.away else { return false }
        return team(k, h) || team(k, a)
    }
    /// Is `q` (as followed) this team?
    static func isTeam(_ q: String, _ t: FxTeam?) -> Bool { t.map { team(fold(q), $0) } ?? false }
    private static func team(_ k: String, _ t: FxTeam) -> Bool {
        let vs = [t.name, t.short, t.nick].compactMap { $0 }.map(fold)
        return vs.contains(k) || (k.count >= 4 && " \(fold(t.name)) ".contains(" \(k) "))
    }
    /// Followed teams' games still to come (or on), live first, then soonest.
    static func games(_ list: [Fixture], follows: [String], ids: Set<String>, now: Date = Date()) -> [Fixture] {
        let ms = now.timeIntervalSince1970 * 1000
        return list.filter { f in
            f.status != "postponed" && (f.isLive || (f.status != "final" && f.end > ms) || (f.status == "final" && f.end > ms - 2 * 3600_000))
                && (ids.contains(f.id) || follows.contains { matches($0, f) })
        }.sorted { a, b in
            let ra = a.isLive ? 0 : a.status == "final" ? 2 : 1, rb = b.isLive ? 0 : b.status == "final" ? 2 : 1
            return ra != rb ? ra < rb : a.start < b.start
        }
    }
}

// MARK: - deep links (m3uplayer://…), opened by the widgets and handled by the app

enum DeepLink {
    static let scheme = "m3uplayer"
    static func resume(_ item: CWItem) -> URL { URL(string: "\(scheme)://resume?k=\(item.k)")! }
    static func fixture(_ id: String) -> URL {
        var c = URLComponents(); c.scheme = scheme; c.host = "sports"; c.queryItems = [URLQueryItem(name: "f", value: id)]
        return c.url!
    }
    static let sports = URL(string: "\(scheme)://sports")!
    static let home = URL(string: "\(scheme)://home")!
}

// MARK: - Live Activity

#if canImport(ActivityKit)
import ActivityKit

/// One followed team's match on the Lock Screen / Dynamic Island. Started and updated only by the app while it is
/// running (no push server on a free account), so every state says when it was fetched.
@available(iOS 16.1, *)
struct MatchAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var homeScore: String
        var awayScore: String
        var minute: String       // "63'", "HT", "FT", or "" before kick-off
        var status: String       // live | final | postponed | scheduled
        var updated: Date        // when the app last fetched it
    }
    var fixtureId: String
    var comp: String
    var sport: String
    var home: String
    var away: String
    var homeShort: String
    var awayShort: String
    var homeCrest: String
    var awayCrest: String
    var channel: String
}
#endif

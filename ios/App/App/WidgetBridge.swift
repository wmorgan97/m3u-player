import UIKit
import Capacitor
import WebKit
import WidgetKit
#if canImport(ActivityKit)
import ActivityKit
#endif

/// The web app hands the widgets what they need (see js/app.js, "Widgets"), and takes the links they open.
@objc(WidgetsPlugin)
public class WidgetsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "WidgetsPlugin"
    public let jsName = "Widgets"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "configure", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "snapshot", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "takeLink", returnType: CAPPluginReturnPromise),
    ]

    /// { base, key }: the server, for the widgets to ask themselves. Empty key/base: disconnected.
    @objc func configure(_ call: CAPPluginCall) {
        let base = call.getString("base") ?? "", key = call.getString("key") ?? ""
        let old = M3UShared.config
        if base.isEmpty { M3UShared.save(nil as M3UConfig?, "config") }
        else if old?.base != base || old?.key != key { M3UShared.save(M3UConfig(base: base, key: key), "config") }
        if old?.base != base || old?.key != key { WidgetCenter.shared.reloadAllTimelines() }
        LiveScores.shared.kick()
        call.resolve()
    }

    /// { json }: an M3USnapshot (follows, followed fixture ids, channels, Continue watching, liveActivities).
    @objc func snapshot(_ call: CAPPluginCall) {
        guard let json = call.getString("json")?.data(using: .utf8), var snap = try? JSONDecoder().decode(M3USnapshot.self, from: json) else {
            call.reject("Bad snapshot"); return
        }
        let old = M3UShared.snapshot
        snap.at = Date().timeIntervalSince1970 * 1000
        M3UShared.save(snap, "snapshot")
        // Only redraw the widgets when what they show changed (iOS budgets widget reloads).
        if old?.cw != snap.cw { WidgetCenter.shared.reloadTimelines(ofKind: "ContinueWatching") }
        if old?.followed != snap.followed || old?.follows != snap.follows || old?.channels != snap.channels {
            WidgetCenter.shared.reloadTimelines(ofKind: "MyTeams"); WidgetCenter.shared.reloadTimelines(ofKind: "NextGame")
        }
        if old?.liveActivities != snap.liveActivities || old?.hideScores != snap.hideScores || old?.followed != snap.followed { LiveScores.shared.kick() }
        call.resolve()
    }

    /// The widget link the app was opened from (once): { kind: resume|sports|home, k?, f? }.
    @objc func takeLink(_ call: CAPPluginCall) {
        let u = DeepLinks.pending; DeepLinks.pending = nil
        guard let url = u else { call.resolve([:]); return }
        var out: [String: Any] = ["kind": url.host ?? ""]
        for q in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] { out[q.name] = q.value ?? "" }
        call.resolve(out)
    }
}

/// m3uplayer://resume?k=…, m3uplayer://sports?f=…: kept until the page takes it (a cold launch is still loading).
enum DeepLinks {
    static var pending: URL?
    static func open(_ url: URL) -> Bool {
        guard url.scheme == DeepLink.scheme else { return false }
        pending = url
        func poke(_ tries: Int) {
            guard pending != nil, tries > 0 else { return }
            AppDelegate.webView?.evaluateJavaScript("typeof window.__widgetLink === 'function' && (window.__widgetLink(), true)", completionHandler: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { poke(tries - 1) }
        }
        DispatchQueue.main.async { poke(25) }
        return true
    }
}

// MARK: - Live scores on the Lock Screen / Dynamic Island

/// Without a push server (free account: no APNs), a Live Activity can only change while this app runs: in the
/// foreground, or in the background while it plays (audio keeps it alive) or gets one of iOS's background refreshes.
/// So: fetch /fixtures every ~90 s while a followed team is on, start an activity for each such game (iOS only lets
/// an app start one while it's in the foreground), update it, end it at full time. Every state carries the time it
/// was fetched and a stale date 5 minutes on; once that passes, the activity says when it was last updated instead of
/// looking live.
final class LiveScores {
    static let shared = LiveScores()
    private var timer: Timer?
    private var busy = false
    private var lastFetch = Date.distantPast
    private var nextGameStart: Date?

    func start() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.tick() }
        timer?.tolerance = 10
        kick()
    }
    /// Something changed (settings, follows, the app came to the front): look now.
    func kick() { DispatchQueue.main.async { self.lastFetch = .distantPast; self.tick() } }

    private func tick() {
        guard #available(iOS 16.2, *) else { return }
        let active = !Activity<MatchAttributes>.activities.isEmpty
        let soon = nextGameStart.map { $0.timeIntervalSinceNow < 120 } ?? false
        // A game on (or about to be): every 90 s. Otherwise every 10 minutes, to notice the next one starting.
        let every: TimeInterval = active || soon ? 90 : 600
        guard !busy, Date().timeIntervalSince(lastFetch) >= every else { return }
        refresh()
    }

    func refresh(done: (() -> Void)? = nil) {
        guard #available(iOS 16.2, *) else { done?(); return }
        let snap = M3UShared.snapshot
        guard let cfg = M3UShared.config, let snap = snap, snap.liveActivities, snap.hideScores != true, !(snap.follows.isEmpty && snap.followed.isEmpty) else {
            endAll(); done?(); return
        }
        busy = true; lastFetch = Date()
        Task {
            defer { DispatchQueue.main.async { self.busy = false; done?() } }
            guard let list = try? await M3UAPI.fixtures(cfg, days: 2) else { NSLog("M3U LiveScores: fixtures failed"); return }
            let games = Follow.games(list, follows: snap.follows, ids: Set(snap.followed))
            NSLog("M3U LiveScores: %d followed games, %d live, %d activities", games.count, games.filter(\.isLive).count, Activity<MatchAttributes>.activities.count)
            nextGameStart = games.filter { $0.status == "scheduled" }.map(\.startDate).min()
            await reconcile(games.filter { $0.isLive || $0.status == "final" }, channels: snap.channels)
        }
    }

    @available(iOS 16.2, *)
    private func state(_ f: Fixture) -> MatchAttributes.ContentState {
        let minute = f.status == "final" ? "FT" : (f.minute ?? "")
        return .init(homeScore: f.home?.score ?? "0", awayScore: f.away?.score ?? "0", minute: minute, status: f.status, updated: Date())
    }

    @available(iOS 16.2, *)
    private func reconcile(_ games: [Fixture], channels: [String: String]) async {
        let byId = Dictionary(games.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for a in Activity<MatchAttributes>.activities {
            guard let f = byId[a.attributes.fixtureId] else {
                // Gone from the feed (or no longer followed): don't leave a frozen score up.
                await a.end(nil, dismissalPolicy: .immediate); continue
            }
            let s = state(f)
            if f.status == "final" {
                await a.end(ActivityContent(state: s, staleDate: nil), dismissalPolicy: .after(Date().addingTimeInterval(20 * 60)))
            } else if s != a.content.state || (a.content.staleDate.map { $0.timeIntervalSinceNow < 240 } ?? true) {
                await a.update(ActivityContent(state: s, staleDate: Date().addingTimeInterval(5 * 60)))
            }
        }
        // New live games: only possible while the app is in front (iOS rule), and only if Live Activities are allowed.
        let running = Set(Activity<MatchAttributes>.activities.map(\.attributes.fixtureId))
        let canStart = await MainActor.run { UIApplication.shared.applicationState == .active }
        guard canStart, ActivityAuthorizationInfo().areActivitiesEnabled else {
            if games.contains(where: { $0.isLive && !running.contains($0.id) }) { NSLog("M3U LiveScores: not starting (in front: %d, allowed: %d)", canStart ? 1 : 0, ActivityAuthorizationInfo().areActivitiesEnabled ? 1 : 0) }
            return
        }
        for f in games where f.isLive && !running.contains(f.id) && f.home != nil && f.away != nil {
            guard Activity<MatchAttributes>.activities.count < 2 else { break }
            let h = f.home!, aw = f.away!
            await cacheCrest(h.crest); await cacheCrest(aw.crest)
            let attrs = MatchAttributes(fixtureId: f.id, comp: f.comp ?? "", sport: f.sport ?? "", home: h.name, away: aw.name,
                                        homeShort: abbreviation(h), awayShort: abbreviation(aw), homeCrest: h.crest ?? "", awayCrest: aw.crest ?? "",
                                        channel: channels[f.id] ?? "")
            do { _ = try Activity.request(attributes: attrs, content: ActivityContent(state: state(f), staleDate: Date().addingTimeInterval(5 * 60)), pushType: nil) }
            catch { NSLog("M3U LiveScores: couldn't start the Live Activity: %@", "\(error)") }
        }
    }

    @available(iOS 16.2, *)
    func endAll() {
        Task { for a in Activity<MatchAttributes>.activities { await a.end(nil, dismissalPolicy: .immediate) } }
    }

    /// "Arsenal" -> "ARS"; "Manchester United" -> "MUN"-ish: the short name's first three letters.
    private func abbreviation(_ t: FxTeam) -> String {
        let words = (t.short ?? t.name).uppercased().split(separator: " ").filter { !["FC", "AFC", "CF"].contains($0) }
        if words.count >= 2, words[0].count <= 4 { return String((words[0].prefix(1) + words[1].prefix(2))) }
        return String(words.first?.prefix(3) ?? "")
    }

    /// A crest, shrunk to 72 px PNG, into the shared keychain for the Live Activity to draw.
    private func cacheCrest(_ src: String?) async {
        guard let src = src, !src.isEmpty, M3UShared.image(src) == nil, let url = URL(string: src),
              let (data, _) = try? await URLSession.shared.data(from: url), let img = UIImage(data: data) else { return }
        let side: CGFloat = 72, scale = min(side / img.size.width, side / img.size.height)
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
        let png = UIGraphicsImageRenderer(size: size, format: fmt).pngData { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        M3UShared.putImage(src, png)
    }
}

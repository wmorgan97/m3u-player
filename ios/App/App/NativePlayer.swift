import UIKit
import AVKit
import Capacitor
import UserNotifications
import WebKit
import VLCKitSPM
import MediaPlayer

/// Hosts the web app and registers the app's own native plugin.
class MainViewController: CAPBridgeViewController {
    private var testReminderDone = false
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(NativePlayerPlugin())
    }

    /// Test hook: launched with `-testStream <url>` (simulator / Xcode only), open the player straight on that
    /// stream so its controls can be checked without a playlist or a provider connection.
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        let args = ProcessInfo.processInfo.arguments
        #if DEBUG
        // Test hook: `-testReminder <stream url>` schedules a real reminder 5 s from now for a pretend channel on that stream.
        // `-testNewEpisode`: a sample new-episode notification, exactly as the favourites check would post it.
        if args.contains("-testNewEpisode"), !testReminderDone {
            testReminderDone = true
            let content = UNMutableNotificationContent()
            content.title = "New episode: The Test Show"; content.body = "S2 E5 · The Return"; content.sound = .default
            content.userInfo = ["series": "{\"name\":\"The Test Show\",\"kind\":\"series\"}"]
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "ep-test", content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: 8, repeats: false)))
            }
            return
        }
        // `-testReminderTap <stream url>`: as if the app had just been opened by tapping a reminder for that channel.
        if let r = args.firstIndex(of: "-testReminderTap"), r + 1 < args.count, !testReminderDone {
            testReminderDone = true
            let ch = (try? JSONSerialization.data(withJSONObject: ["name": "Test channel", "url": args[r + 1], "group": "Test", "kind": "live"])).flatMap { String(data: $0, encoding: .utf8) } ?? ""
            ReminderTaps.tapped(ch, webView: nil)
            return
        }
        if let r = args.firstIndex(of: "-testReminder"), r + 1 < args.count, !testReminderDone {
            testReminderDone = true
            let ch = (try? JSONSerialization.data(withJSONObject: ["name": "Test channel", "url": args[r + 1], "group": "Test", "kind": "live"])).flatMap { String(data: $0, encoding: .utf8) } ?? ""
            NativePlayerPlugin.schedule(id: "test", at: Date().timeIntervalSince1970 * 1000 + 185_000, title: "Test programme", channelName: "Test channel", channel: ch) { r in NSLog("M3UTEST reminder scheduled %@", "\(r)") }
            return
        }
        // `-testEpisodes <url> [-testStart secs]`: a pretend 3-episode season (with stills) on that stream, stepping
        // through it like the web app does, to check the next-episode card without a provider.
        if let e = args.firstIndex(of: "-testEpisodes"), e + 1 < args.count, let url = URL(string: args[e + 1]), presentedViewController == nil,
           !children.contains(where: { $0 is VLCPlayerController }) {
            let start = args.firstIndex(of: "-testStart").flatMap { $0 + 1 < args.count ? Double(args[$0 + 1]) : nil } ?? 0
            let names = ["Pilot", "The Crossing", "Homecoming"]
            func episode(_ n: Int) -> VLCPlayerController.Item {
                var it = VLCPlayerController.Item(url: url, title: "The Test Show", subtitle: "S1 E\(n + 1) · \(names[n])", info: "", live: false, start: start,
                                                  headers: ["X-Test-Mute": "1"], hasPrev: n > 0, hasNext: n < names.count - 1)
                it.list = names.enumerated().map { ("\($0.offset + 1). \($0.element)", "Season 1") }
                it.listArt = names.indices.map { "https://picsum.photos/seed/m3u-ep\($0 + 1)/640/360" }
                it.listCard = names.enumerated().map { "S1 E\($0.offset + 1) · \($0.element)" }
                it.listTitle = "Episodes"; it.current = n; it.show = "The Test Show"
                return it
            }
            var index = 0
            weak var player: VLCPlayerController?
            let controller = VLCPlayerController(item: episode(0)) { event in
                NSLog("M3UTEST player event %@", "\(event)")
                let to = (event["type"] as? String) == "step" ? index + ((event["dir"] as? Int) ?? 0) : (event["type"] as? String) == "jump" ? ((event["index"] as? Int) ?? -1) : -1
                guard names.indices.contains(to) else { return }
                index = to
                DispatchQueue.main.async { player?.load(episode(to)) }
            }
            player = controller
            controller.show(in: self)
            return
        }
        #endif
        // `-testChannels <base url>`: a pretend group of live channels (with logos and a guide) on the local test streams
        // (scratchpad streams/serve.py: /live/<n>.ts and /hls/<n>/index.m3u8), to try zapping, timeshift and the mini guide.
        if let e = args.firstIndex(of: "-testChannels"), e + 1 < args.count, presentedViewController == nil,
           !children.contains(where: { $0 is VLCPlayerController }) {
            let base = args[e + 1]
            let names = ["BBC One", "BBC Two", "ITV1", "Channel 4", "Sky News", "Sky Sports Main Event", "Eurosport 1", "Discovery", "Comedy Central", "Film4", "Dave", "E4"]
            let shows = ["The One Show", "Gardeners' World", "Coronation Street", "Grand Designs", "Sky News Tonight", "Premier League: Arsenal v Chelsea",
                         "Tour de France Highlights", "Gold Rush", "Friends", "Paddington 2", "Taskmaster", "The Big Bang Theory"]
            let now = Date().timeIntervalSince1970 * 1000
            let catchup = args.contains("-testCatchup")
            func channel(_ n: Int) -> VLCPlayerController.Item {
                let hls = args.contains("-testHLS")
                let url = URL(string: hls ? "\(base)/hls/\(n % 3)/index.m3u8" : "\(base)/live/\(n % 3).ts")!
                var it = VLCPlayerController.Item(url: url, title: names[n], subtitle: "\(shows[n]) · ends 21:00", info: "A pretend programme description for the test channel.",
                                                  live: true, start: 0, headers: ["X-Test-Mute": "1"], hasPrev: n > 0, hasNext: n < names.count - 1)
                it.list = names.indices.map { (names[$0], shows[$0]) }
                it.listTitle = "Channels"; it.current = n; it.num = 101 + n; it.group = "UK Entertainment"
                it.listNum = names.indices.map { 101 + $0 }
                it.listLogo = names.indices.map { "https://picsum.photos/seed/m3u-logo\($0)/96/96" }
                it.listNowStart = names.indices.map { now - Double(($0 * 7 + 10) % 50 + 5) * 60_000 }
                it.listNowEnd = names.indices.map { now + Double(($0 * 11 + 15) % 55 + 5) * 60_000 }
                it.nowTitle = shows[n]; it.nowStart = it.listNowStart[n]; it.nowEnd = it.listNowEnd[n]
                it.nextTitle = "The News at Ten"; it.nextStart = it.nowEnd
                it.art = it.listLogo[n]
                it.catchupDays = catchup ? 7 : 0
                return it
            }
            var index = 0
            weak var player: VLCPlayerController?
            let controller = VLCPlayerController(item: channel(0)) { event in
                NSLog("M3UTEST player event %@", "\(event)")
                let to = (event["type"] as? String) == "step" ? index + ((event["dir"] as? Int) ?? 0) : (event["type"] as? String) == "jump" ? ((event["index"] as? Int) ?? -1) : -1
                guard names.indices.contains(to) else { return }
                index = to
                DispatchQueue.main.async { player?.load(channel(to)) }
            }
            // Catch-up for the test: the "archive" is the same local stream (it can't really go back in time).
            controller.askJS = { js, done in
                if js.contains("__playerCatchup") { done(catchup ? "\(base)/live/\(index % 3).ts" : "") } else { done(nil) }
            }
            player = controller
            controller.show(in: self)
            return
        }
        guard let i = args.firstIndex(of: "-testStream"), i + 1 < args.count, let url = URL(string: args[i + 1]), presentedViewController == nil else { return }
        let live = args.contains("-testLive")
        var item = VLCPlayerController.Item(url: url, title: "Test stream", subtitle: "Player check", info: "", live: live, start: 0, headers: ["X-Test-Mute": "1"], hasPrev: true, hasNext: true)
        item.list = (1...12).map { ("Episode \($0)", "S1 E\($0)") }
        item.listTitle = "Episodes"
        item.current = 2
        let controller = VLCPlayerController(item: item) { event in NSLog("M3UTEST player event %@", "\(event)") }
        guard !children.contains(where: { $0 is VLCPlayerController }) else { return }
        controller.show(in: self)
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    // The player sits on top of the app as a child (so it can shrink into a mini player with the app usable behind it):
    // while it is full screen, it decides the status bar, home indicator and rotation.
    private var fullPlayer: VLCPlayerController? { children.compactMap { $0 as? VLCPlayerController }.first { !$0.isMini } }
    override var childForStatusBarHidden: UIViewController? { fullPlayer }
    override var childForHomeIndicatorAutoHidden: UIViewController? { fullPlayer }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { fullPlayer?.supportedInterfaceOrientations ?? .allButUpsideDown }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        children.compactMap { $0 as? VLCPlayerController }.forEach { $0.layoutInParent() }
    }
}

/// Native features the web app calls through `Capacitor.nativePromise('NativePlayer', …)`:
/// - vlc: the app's own full-screen player on VLC's engine. Plays everything an IPTV provider
///   serves (.ts, .mkv, HEVC, 10-bit, DTS/AC3, multiple audio tracks, subtitles) straight from the
///   provider, with channel up/down, seeking, and progress reported back to the web app.
/// - close: closes that player.
/// - play: the full-screen system player (AVPlayer), kept for HLS streams that want AirPlay / PiP.
/// - share: the iOS share sheet for exported playlists and backups.
/// A reminder notification tapped: its channel, until the web app picks it up (it may still be loading).
enum ReminderTaps {
    static var pending: String?
    static var pendingSeries: String?
    static func tapped(_ channel: String, webView: WKWebView?) { tapped(channel: channel, series: nil, webView: webView) }
    static func tapped(channel: String?, series: String?, webView: WKWebView?) {
        if let c = channel { pending = c }
        if let s = series { pendingSeries = s }
        // Tell the page; on a cold launch it may still be loading, so keep asking until it has taken the channel.
        func poke(_ tries: Int) {
            guard pending != nil || pendingSeries != nil, tries > 0 else { return }
            let web = webView ?? (UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.windows.first?.rootViewController as? CAPBridgeViewController }.first)?.webView
            web?.evaluateJavaScript("typeof window.__reminderTap === 'function' && (window.__reminderTap(), true)", completionHandler: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { poke(tries - 1) }
        }
        DispatchQueue.main.async { poke(20) }
    }
}

@objc(NativePlayerPlugin)
public class NativePlayerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativePlayerPlugin"
    public let jsName = "NativePlayer"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "play", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "vlc", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "close", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "share", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remind", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unremind", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "takeReminderTap", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "notify", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "notifications", returnType: CAPPluginReturnPromise),
    ]

    // MARK: reminders
    // "Remind me" in the guide becomes a scheduled iOS notification: delivered by iOS 3 minutes before the programme even
    // if the app is closed or the phone is locked. Tapping it opens the app on that channel (see AppDelegate).

    /// { id, at (ms), title, channelName, channel (JSON string, handed back on tap) }
    /// Notification permission: { status: granted | denied | ask }. With ask: true and not decided yet, shows iOS's prompt.
    @objc func notifications(_ call: CAPPluginCall) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { s in
            if s.authorizationStatus == .notDetermined && call.getBool("ask") == true {
                center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in call.resolve(["status": granted ? "granted" : "denied"]) }
                return
            }
            let ok = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional || s.authorizationStatus == .ephemeral
            call.resolve(["status": ok ? "granted" : s.authorizationStatus == .notDetermined ? "ask" : "denied"])
        }
    }

    @objc func remind(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let at = call.getDouble("at") else { call.reject("Missing reminder"); return }
        NativePlayerPlugin.schedule(id: id, at: at, title: call.getString("title") ?? "", channelName: call.getString("channelName") ?? "",
                                    channel: call.getString("channel") ?? "") { result in
            switch result { case .success(let ok): call.resolve(["scheduled": ok]); case .failure(let e): call.reject(e.localizedDescription) }
        }
    }

    static func schedule(id: String, at: Double, title: String, channelName: String, channel: String, done: @escaping (Result<Bool, Error>) -> Void) {
        let fire = Date(timeIntervalSince1970: at / 1000 - 180)
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { done(.success(false)); return }
            let content = UNMutableNotificationContent()
            content.title = title.isEmpty ? "Starting soon" : title
            let f = DateFormatter(); f.dateFormat = "HH:mm"
            content.body = "Starts at \(f.string(from: Date(timeIntervalSince1970: at / 1000))) on \(channelName)"
            content.sound = .default
            content.userInfo = ["channel": channel]
            let wait = fire.timeIntervalSinceNow
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, wait), repeats: false)
            center.add(UNNotificationRequest(identifier: "remind-\(id)", content: content, trigger: trigger)) { error in
                if let error = error { done(.failure(error)) } else { done(.success(true)) }
            }
        }
    }

    /// A notification now (new episodes): { id, title, body, series (JSON, opened on tap) }.
    @objc func notify(_ call: CAPPluginCall) {
        let content = UNMutableNotificationContent()
        content.title = call.getString("title") ?? ""
        content.body = call.getString("body") ?? ""
        content.sound = .default
        content.userInfo = ["series": call.getString("series") ?? ""]
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { call.resolve(["shown": false]); return }
            center.add(UNNotificationRequest(identifier: call.getString("id") ?? UUID().uuidString, content: content, trigger: nil)) { _ in call.resolve(["shown": true]) }
        }
    }

    @objc func unremind(_ call: CAPPluginCall) {
        guard let id = call.getString("id") else { call.reject("Missing id"); return }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["remind-\(id)"])
        call.resolve()
    }

    /// The channel of a reminder notification the app was opened from (once), for the web app to start playing.
    @objc func takeReminderTap(_ call: CAPPluginCall) {
        let ch = ReminderTaps.pending; ReminderTaps.pending = nil
        let se = ReminderTaps.pendingSeries; ReminderTaps.pendingSeries = nil
        var out: [String: Any] = [:]
        if let ch = ch { out["channel"] = ch }
        if let se = se { out["series"] = se }
        call.resolve(out)
    }

    @objc func play(_ call: CAPPluginCall) {
        guard let string = call.getString("url"), let url = URL(string: string) else {
            call.reject("Missing or invalid url")
            return
        }
        var headers: [String: String] = [:]
        for (key, value) in call.getObject("headers") ?? [:] {
            if let value = value as? String { headers[key] = value }
        }
        DispatchQueue.main.async {
            guard let presenter = self.bridge?.viewController else {
                call.reject("The app isn't ready")
                return
            }
            let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
            let player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
            let controller = AVPlayerViewController()
            controller.player = player
            controller.allowsPictureInPicturePlayback = true
            controller.canStartPictureInPictureAutomaticallyFromInline = true
            presenter.present(controller, animated: true) { player.play() }
            call.resolve()
        }
    }

    private weak var vlcController: VLCPlayerController?

    @objc func vlc(_ call: CAPPluginCall) {
        guard let string = call.getString("url"), let url = URL(string: string) else {
            call.reject("Missing or invalid url")
            return
        }
        var headers: [String: String] = [:]
        for (key, value) in call.getObject("headers") ?? [:] {
            if let value = value as? String { headers[key] = value }
        }
        var item = VLCPlayerController.Item(
            url: url, title: call.getString("title") ?? "", subtitle: call.getString("subtitle") ?? "",
            info: call.getString("info") ?? "",
            live: call.getBool("live") ?? true, start: call.getDouble("start") ?? 0, headers: headers,
            hasPrev: call.getBool("hasPrev") ?? false, hasNext: call.getBool("hasNext") ?? false)
        item.list = (call.getArray("list") ?? []).compactMap { $0 as? [String: Any] }.map { (($0["t"] as? String) ?? "", ($0["s"] as? String) ?? "") }
        item.listStart = call.getInt("listStart") ?? 0
        item.current = call.getInt("current") ?? 0
        item.listTitle = call.getString("listTitle") ?? "Episodes"
        item.art = call.getString("art") ?? ""
        let rows = (call.getArray("list") ?? []).compactMap { $0 as? [String: Any] }
        item.listArt = rows.map { ($0["a"] as? String) ?? "" }
        item.listCard = rows.map { ($0["c"] as? String) ?? "" }
        item.show = call.getString("show") ?? ""
        item.num = call.getInt("num") ?? 0
        item.nowTitle = call.getString("nowT") ?? ""
        item.nowStart = call.getDouble("nowA") ?? 0
        item.nowEnd = call.getDouble("nowB") ?? 0
        item.nowDesc = call.getString("info") ?? ""
        item.nextTitle = call.getString("nextT") ?? ""
        item.nextStart = call.getDouble("nextA") ?? 0
        item.catchupDays = call.getInt("catchupDays") ?? 0
        item.group = call.getString("group") ?? ""
        item.listLogo = rows.map { ($0["l"] as? String) ?? "" }
        item.listNum = rows.map { ($0["n"] as? NSNumber)?.intValue ?? 0 }
        item.listNowStart = rows.map { ($0["pa"] as? NSNumber)?.doubleValue ?? 0 }
        item.listNowEnd = rows.map { ($0["pb"] as? NSNumber)?.doubleValue ?? 0 }
        DispatchQueue.main.async {
            // Already open (channel up/down, next episode): swap the stream in place.
            if let open = self.vlcController, open.parent != nil {
                // The player's own next-episode countdown ran out while it was the mini player: keep it small.
                let stayMini = open.isMini && open.selfStepPending
                open.load(item)
                if !stayMini { open.expand() }   // picked something while it was the mini player: back to full screen
                call.resolve()
                return
            }
            guard let presenter = self.bridge?.viewController else {
                call.reject("The app isn't ready")
                return
            }
            let controller = VLCPlayerController(item: item) { [weak self] event in self?.emit(event) }
            // The player asks the web app for fresh guide rows and catch-up links (it has the guide and the logins).
            controller.askJS = { [weak self] js, done in
                guard let web = self?.bridge?.webView else { return done(nil) }
                web.evaluateJavaScript(js) { r, _ in done(r) }
            }
            self.vlcController = controller
            controller.show(in: presenter)
            call.resolve()
        }
    }

    override public func load() {
        LiveBuffer.purge()                   // recordings left behind by a killed app
        LocalStreamServer.shared.start()     // ready before the first channel
    }

    @objc func close(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.vlcController?.finish()
            call.resolve()
        }
    }

    /// Player events go to `window.__nativePlayer(event)` in the web app.
    private func emit(_ event: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: event),
              let json = String(data: data, encoding: .utf8) else { return }
        DispatchQueue.main.async {
            self.bridge?.webView?.evaluateJavaScript("window.__nativePlayer && window.__nativePlayer(\(json))", completionHandler: nil)
        }
    }

    @objc func share(_ call: CAPPluginCall) {
        guard let text = call.getString("text") else {
            call.reject("Nothing to share")
            return
        }
        let name = (call.getString("name") ?? "export.txt").replacingOccurrences(of: "/", with: "_")
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try text.write(to: file, atomically: true, encoding: .utf8)
        } catch {
            call.reject("Couldn't create the file")
            return
        }
        DispatchQueue.main.async {
            guard let presenter = self.bridge?.viewController else {
                call.reject("The app isn't ready")
                return
            }
            let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            // iPad shows the share sheet as a popover, which needs an anchor.
            sheet.popoverPresentationController?.sourceView = presenter.view
            sheet.popoverPresentationController?.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.maxY - 40, width: 1, height: 1)
            presenter.present(sheet, animated: true)
            call.resolve()
        }
    }
}

/// The player's root view. While it is the mini player only the visible card takes touches; the rest of the (scaled,
/// cropped, invisible) player lets them through to the app behind.
final class PlayerRootView: UIView {
    var touchArea: CGRect?
    var onTouch: (() -> Void)?   // any touch on the player: someone is watching (resets the autoplay run)
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if event?.type == .touches { onTouch?() }
        return super.hitTest(point, with: event)
    }
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        if let a = touchArea { return a.contains(point) }
        return super.point(inside: point, with: event)
    }
}

/// Full-screen player on VLC's engine. Tap to show the controls; swipe up/down on a live channel for
/// the next/previous one. Events: progress {t, d} every 5s, closed {t, d}, ended, error {message}, step {dir}.
final class VLCPlayerController: UIViewController, VLCMediaPlayerDelegate {
    struct Item {
        let url: URL
        let title: String
        let subtitle: String
        let info: String
        let live: Bool
        let start: Double
        let headers: [String: String]
        let hasPrev: Bool
        let hasNext: Bool
        /// The episodes (or channels) around this one, for the list button: titles, optional second lines,
        /// the index of the first one in the app's own list, and which one is playing.
        var list: [(title: String, detail: String)] = []
        var listStart = 0
        var current = 0
        var listTitle = "Episodes"
        var art = ""   // poster / logo for the lock screen
        var listArt: [String] = []    // per list row: the episode's still (next-episode card)
        var listCard: [String] = []   // per list row: "S1 E2 · Title" for the next-episode card
        var show = ""                 // the series' name ("Still watching …?")
        // Live channels: number, what's on now / next (times in ms), days of catch-up, the group; per list row the
        // channel's logo, number and the times of what's on now (the mini guide).
        var num = 0
        var nowTitle = "", nowStart: Double = 0, nowEnd: Double = 0, nowDesc = ""
        var nextTitle = "", nextStart: Double = 0
        var catchupDays = 0
        var group = ""
        var listLogo: [String] = []
        var listNum: [Int] = []
        var listNowStart: [Double] = []
        var listNowEnd: [Double] = []
    }

    private let player = VLCMediaPlayer()
    private let videoView = UIView()
    private let overlay = UIView()
    private let gradient = CAGradientLayer()
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let timeLabel = UILabel()      // total length, right of the bar
    private let elapsedLabel = UILabel()   // time played, left of the bar
    private let qualityBadge = UILabel()   // SD / HD / 4K, from the picture VLC is actually showing
    private let statusLabel = UILabel()
    private let liveBadge = UILabel()
    private let slider = UISlider()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let closeButton = UIButton(type: .system)
    private let rotateButton = UIButton(type: .system)
    private let audioButton = UIButton(type: .system)
    private let subsButton = UIButton(type: .system)
    private let infoButton = UIButton(type: .system)
    private let playButton = UIButton(type: .system)
    private let backButton = UIButton(type: .system)
    private let forwardButton = UIButton(type: .system)
    private let prevButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private let listButton = UIButton(type: .system)
    private let aspectButton = UIButton(type: .system)
    private let lockButton = UIButton(type: .system)
    private let unlockButton = UIButton(type: .system)   // the only control while the screen is locked

    private var item: Item
    private let onEvent: ([String: Any]) -> Void
    private var tickTimer: Timer?
    private var hideTimer: Timer?
    private var ticks = 0
    private var lastTime: Int32 = -1
    private var scrubbing = false
    private var finished = false
    private var locked = false
    // Picture shape, like VLC's: tap to cycle. Kept for the next channel / episode.
    private static let aspectModes = ["Best fit", "Fill screen", "Stretch", "16:9", "4:3", "2.35:1"]
    private var aspectMode = 0
    private let middle = UIStackView()   // centre transport row
    private let spacer = UIView()   // takes the spare width when the slider is hidden (live), so "LIVE" stays small
    // Big centre read-out for swipes (brightness, volume, seek): visible even while the controls are hidden.
    private let hud = UILabel()
    private var hudTimer: Timer?
    // Audio / subtitle sync, VLC-style: nudge in 50 ms steps.
    private let syncPanel = UIStackView()
    private let syncLabel = UILabel()
    private var syncKind = 0   // 0 audio, 1 subtitles
    // "Next episode in 10" at the end of an episode.
    // Next episode: a card in the end credits (last 30 s), counting down 10 s inside its Play next button.
    private let nextCard = NextEpisodeCard()
    private enum CardMode { case none, next, still }
    private var cardMode = CardMode.none
    private var countdown: Timer?
    private var countdownTotal: Double = 10
    private var countdownLeft: Double = 0
    private var creditsDismissed = false   // "Watch credits": no card again until you seek out of the credits
    private var ended = false
    private var autoChain = 0              // episodes started by the countdown in a row, with no touch in between
    private var stillAfter = 3             // "Still watching?" instead of the 4th
    private var cardConstraints: (portrait: [NSLayoutConstraint], landscape: [NSLayoutConstraint]) = ([], [])
    var selfStepPending = false            // the countdown asked for the next episode (don't pop the mini player open)
    // Swipes on films and episodes: left half brightness, right half volume, sideways to seek.
    private enum Drag { case none, scrub, brightness, volume, minimize, guide }
    private var drag = Drag.none
    private var dragFrom: Double = 0
    private var scrubTarget: Double = 0
    private let volumeView = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 10, height: 10))
    private var volumeSlider: UISlider? { volumeView.subviews.compactMap { $0 as? UISlider }.first }
    private var startBrightness: CGFloat = 1
    // Volume read-out. The hidden volume control above (needed for the volume swipe) makes iOS hide its own volume
    // indicator, so the side buttons changed the volume with nothing on screen. This draws one instead, where iOS puts
    // its own: a slim frosted bar on the left edge by the volume buttons, filling from the bottom with a speaker under it,
    // shown on every change (buttons or swipe), then fading.
    private let volumePill = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterialDark))
    private let volumeIcon = UIImageView()
    private let volumeTrack = UIView()
    private let volumeFill = UIView()
    private var volumeFillHeight: NSLayoutConstraint?
    private var volumeObs: NSKeyValueObservation?
    private var volumeHide: Timer?
    // Lock screen / Control Center / headphone buttons.
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private var artwork: MPMediaItemArtwork?
    private var deinterlaceSet = false
    // Bytes read, for the stream info panel's bitrate.
    private var samples: [(t: TimeInterval, bytes: Int64)] = []

    // MARK: live state
    // Channel changes: a tuning card (logo, number, name, what's on) from the moment you swipe until the picture comes,
    // over the last frame. Rapid swipes are gathered up (only the channel you stop on is opened: one connection).
    private let tuningCard = TuningCard()
    private var freeze: UIView?                 // the last frame, held under the tuning card / while seeking
    private var zapTarget: Int?                 // the channel (index in the app's list) a swipe is heading for
    private var zapTimer: Timer?
    private var lastZap: TimeInterval = 0
    // Pause / rewind on live TV. Buffered: the channel goes through LiveBuffer (recorded to Caches, played from there).
    // Catch-up: the provider's archive (Start over, or rewinding past what's been recorded). Direct: plain live.
    private enum Feed { case direct, buffered, catchup }
    private var feed = Feed.direct
    private var buffer: LiveBuffer?
    private var shiftTarget: Double?            // where the current session was asked to start (nil = live)
    private var sessionContent: Double?         // where it really started (wall-clock time of that moment of TV)
    private var firstPictureAt: TimeInterval = 0
    private var pausedAt: TimeInterval?
    private var pausedTotal: TimeInterval = 0
    private var liveLag: Double = 0             // how far behind the provider "live" is (keyframe + buffering, HLS window)
    private var catchupStart: Double = 0        // the moment of TV at the catch-up recording's 0:00
    private var userPaused = false
    private var lastTimeChange: TimeInterval = 0
    private var stalls = 0
    private let liveButton = UIButton(type: .custom)        // "● LIVE": red dot at live; tap to go back when behind
    private let startOverButton = UIButton(type: .custom)   // "Start over": the current programme from its start
    private var guide: GuidePanel?
    /// Runs a line of the web app's JavaScript and hands back its value (fresh guide rows, catch-up links).
    var askJS: ((String, @escaping (Any?) -> Void) -> Void)?
    private var uptime: TimeInterval { ProcessInfo.processInfo.systemUptime }
    private var wall: Double { Date().timeIntervalSince1970 }
    /// Live buffering (ms) for channels straight from the provider. Starts low (a quick picture) and grows on its own
    /// if this phone's connection stalls; kept between launches.
    private static var liveCaching: Int {
        get { let v = UserDefaults.standard.integer(forKey: "m3u.liveCaching"); return v > 0 ? v : 800 }
        set { UserDefaults.standard.set(min(2500, max(600, newValue)), forKey: "m3u.liveCaching") }
    }
    /// What VLC itself would say: some providers only let VLC-looking players in.
    private static let vlcAgent: String = {
        let v = VLCLibrary.shared().version.split(separator: " ").first.map(String.init) ?? "3.0.21"
        return "VLC/\(v) LibVLC/\(v)"
    }()

    init(item: Item, onEvent: @escaping ([String: Any]) -> Void) {
        self.item = item
        self.onEvent = onEvent
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }
    override func loadView() {
        let root = PlayerRootView()
        root.onTouch = { [weak self] in self?.autoChain = 0 }
        view = root
    }
    private var root: PlayerRootView? { view as? PlayerRootView }

    override var prefersStatusBarHidden: Bool { !isMini }
    override var prefersHomeIndicatorAutoHidden: Bool { !isMini }
    // Rotate button: forces landscape even with the phone's rotation lock on (like VLC), and back to portrait.
    private var forcedLandscape = false
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { forcedLandscape ? .landscape : .allButUpsideDown }
    @objc private func rotateTapped() { setForcedLandscape(!forcedLandscape); showOverlay() }
    /// Forced landscape on or off (the rotate button; minimising goes back upright and expanding restores it).
    private func setForcedLandscape(_ on: Bool) {
        forcedLandscape = on
        if #available(iOS 16.0, *) {
            let mask: UIInterfaceOrientationMask = forcedLandscape ? .landscapeRight : .portrait
            (parent ?? self).setNeedsUpdateOfSupportedInterfaceOrientations()
            view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
            if !forcedLandscape {   // back to following the phone (portrait while rotation lock is on)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in (self?.parent ?? self)?.setNeedsUpdateOfSupportedInterfaceOrientations() }
            }
        } else {
            UIDevice.current.setValue((forcedLandscape ? UIInterfaceOrientation.landscapeRight : .portrait).rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
        rotateButton.tintColor = forcedLandscape ? UIColor(red: 0.36, green: 0.58, blue: 1, alpha: 1) : .white
    }
    private var landscapeBeforeMini = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        videoView.frame = view.bounds
        videoView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        videoView.backgroundColor = .black
        view.addSubview(videoView)
        player.drawable = videoView
        player.delegate = self
        tuningCard.frame = view.bounds
        tuningCard.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(tuningCard)
        buildOverlay()
        LocalStreamServer.shared.start()
        if item.live { overlay.alpha = 0; overlay.isUserInteractionEnabled = false }   // a channel opens on its tuning card, not the controls

        // Touch: a single tap shows/hides the controls, a double tap on the left/right third skips 10s.
        // Taps that land on a button or the slider go to that control only (see gestureRecognizer(_:shouldReceive:)).
        videoView.isUserInteractionEnabled = false   // VLC's drawing view must never take the touches
        // Taps and swipes are read straight from the touches (touchesBegan/Ended below): gesture recognizers
        // never fired over VLC's video view, which is what made the player feel dead to the touch.
        startBrightness = UIScreen.main.brightness
        view.addSubview(volumeView)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        setUpVolumeIndicator()
        setUpMiniClose()
        setUpRemoteControls()
        tickTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments   // test hook: `-testStillAfter 1` asks "Still watching?" sooner
        if let i = args.firstIndex(of: "-testStillAfter"), i + 1 < args.count, let n = Int(args[i + 1]) { stillAfter = n }
        #endif
        load(item)
    }

    // MARK: touch
    private var touchStart: (point: CGPoint, time: TimeInterval)?
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if isMini { touchStart = touches.first.map { ($0.location(in: parent?.view), ProcessInfo.processInfo.systemUptime) }; return }
        if locked { showUnlock(); return super.touchesBegan(touches, with: event) }   // locked: a touch only offers the unlock button
        if let t = touches.first, !isOnControl(t) { touchStart = (t.location(in: view), ProcessInfo.processInfo.systemUptime) }
        super.touchesBegan(touches, with: event)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesMoved(touches, with: event)
        if isMini { return miniMoved(touches) }
        guard !locked, let t = touches.first, let start = touchStart else { return }
        let p = t.location(in: view), dx = p.x - start.point.x, dy = p.y - start.point.y
        let h = view.bounds.height, w = view.bounds.width
        let portrait = h > w
        if drag == .none {
            guard max(abs(dx), abs(dy)) > 14 else { return }
            // Up/down by where the swipe starts: portrait, anywhere = minimise (on a downward swipe). Landscape, the screen
            // is in thirds: left = brightness, middle = minimise (downward), right = volume. Live or film alike.
            let third = start.point.x < w / 3 ? 0 : start.point.x > w * 2 / 3 ? 2 : 1
            let vertical = abs(dy) > abs(dx)
            // The system's own edge swipes (Notification Centre from the top, Home from the very bottom).
            if vertical && (start.point.y < 50 || start.point.y > h - (dy < 0 ? 24 : 50)) { touchStart = nil; return }
            if vertical && dy > 0 && (portrait || third == 1) && parent != nil { drag = .minimize; hideOverlay() }
            // Up in the middle (sideways) or anywhere (upright): the mini guide. Down is the mini player: the pair mirror.
            else if vertical && dy < 0 && (portrait || third == 1) && item.live && !item.list.isEmpty { drag = .guide }
            else if vertical && !portrait && third == 0 { drag = .brightness; dragFrom = Double(UIScreen.main.brightness) }
            else if vertical && !portrait && third == 2 { drag = .volume; dragFrom = Double(AVAudioSession.sharedInstance().outputVolume) }
            else if vertical { touchStart = nil; return }
            else if item.live { return }   // live: sideways swipes change channel (on release, see touchesEnded)
            else { drag = .scrub; dragFrom = position().0; scrubTarget = dragFrom }
        }
        switch drag {
        case .scrub:
            let d = position().1
            scrubTarget = min(max(0, dragFrom + Double(dx / w) * 120), d > 1 ? d - 1 : .greatestFiniteMagnitude)   // full width = 2 minutes
            let delta = scrubTarget - dragFrom
            showHud("\(clock(scrubTarget))   \(delta < 0 ? "−" : "+")\(clock(abs(delta)))", sticky: true)
        case .brightness:
            let v = min(1, max(0, dragFrom - Double(dy / (h * 0.6))))
            UIScreen.main.brightness = CGFloat(v)
            showHud("☀︎  \(Int(v * 100))%", sticky: true)
        case .volume:
            let v = min(1, max(0, dragFrom - Double(dy / (h * 0.6))))
            volumeSlider?.value = Float(v)
            showVolume(Float(v))   // (the same pill as the side buttons)
        case .minimize:
            // Follows the finger: shrinks toward the corner as it is pulled down.
            let f = min(1, max(0, dy) / (h * 0.6))
            let s = 1 - 0.5 * f
            view.transform = CGAffineTransform(translationX: w * 0.25 * f, y: max(0, dy) * 0.85).scaledBy(x: s, y: s)
            setCrop(min(1, f * 3), scale: s)   // the crop reaches the picture early in the pull
        case .guide: break
        case .none: break
        }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        defer { touchStart = nil; super.touchesEnded(touches, with: event) }
        if isMini { return miniEnded(touches) }
        if drag == .minimize {
            drag = .none
            let dy = (touches.first.map { $0.location(in: view).y } ?? 0) - (touchStart?.point.y ?? 0)
            if dy > view.bounds.height * 0.18 { minimize() } else { springBack() }
            return
        }
        if drag == .guide {
            drag = .none
            let dy = (touches.first.map { $0.location(in: view).y } ?? 0) - (touchStart?.point.y ?? 0)
            if dy < -40 { openGuide() }
            return
        }
        if drag != .none {
            if drag == .scrub { player.time = VLCTime(int: Int32(scrubTarget * 1000)) }
            let wasVolume = drag == .volume
            drag = .none
            if !wasVolume { showHud(hud.text ?? "", sticky: false) }   // (volume has its own pill, which fades by itself)
            return
        }
        guard let t = touches.first, let start = touchStart else { return }
        let p = t.location(in: view), dx = p.x - start.point.x, dy = p.y - start.point.y
        if item.live && abs(dx) > 60 && abs(dx) > abs(dy) * 1.5 { return zap(next: dx < 0) }   // live: swipe left = next channel, right = previous
        // A quick flick up (no move events on the way): the guide, as for a slower swipe.
        let middle = view.bounds.height > view.bounds.width || (start.point.x > view.bounds.width / 3 && start.point.x < view.bounds.width * 2 / 3)
        if item.live && dy < -60 && abs(dy) > abs(dx) * 1.5 && middle && start.point.y < view.bounds.height - 24 { return openGuide() }
        if abs(dx) < 12 && abs(dy) < 12 && ProcessInfo.processInfo.systemUptime - start.time < 0.6 { tapped(at: p) }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if drag == .minimize { drag = .none; springBack() }
        if drag == .guide { drag = .none }
        if drag != .none { let wasVolume = drag == .volume; drag = .none; if !wasVolume { showHud(hud.text ?? "", sticky: false) } }
        touchStart = nil
        super.touchesCancelled(touches, with: event)
    }
    private func setUpVolumeIndicator() {
        volumePill.layer.cornerRadius = 19
        volumePill.clipsToBounds = true
        volumePill.alpha = 0
        volumePill.isUserInteractionEnabled = false
        volumePill.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(volumePill)
        volumeIcon.tintColor = .white
        volumeIcon.contentMode = .scaleAspectFit
        volumeIcon.translatesAutoresizingMaskIntoConstraints = false
        volumeTrack.backgroundColor = UIColor(white: 1, alpha: 0.25)
        volumeTrack.layer.cornerRadius = 3
        volumeTrack.translatesAutoresizingMaskIntoConstraints = false
        volumeFill.backgroundColor = .white
        volumeFill.layer.cornerRadius = 3
        volumeFill.translatesAutoresizingMaskIntoConstraints = false
        volumePill.contentView.addSubview(volumeIcon)
        volumePill.contentView.addSubview(volumeTrack)
        volumeTrack.addSubview(volumeFill)
        let fill = volumeFill.heightAnchor.constraint(equalToConstant: 0)
        volumeFillHeight = fill
        NSLayoutConstraint.activate([
            volumePill.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 10),
            volumePill.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 96),   // below the title row, above the middle controls
            volumePill.widthAnchor.constraint(equalToConstant: 38),
            volumePill.heightAnchor.constraint(equalToConstant: 170),
            volumeTrack.topAnchor.constraint(equalTo: volumePill.contentView.topAnchor, constant: 16),
            volumeTrack.centerXAnchor.constraint(equalTo: volumePill.contentView.centerXAnchor),
            volumeTrack.widthAnchor.constraint(equalToConstant: 6),
            volumeTrack.bottomAnchor.constraint(equalTo: volumeIcon.topAnchor, constant: -10),
            volumeIcon.bottomAnchor.constraint(equalTo: volumePill.contentView.bottomAnchor, constant: -12),
            volumeIcon.centerXAnchor.constraint(equalTo: volumePill.contentView.centerXAnchor),
            volumeIcon.widthAnchor.constraint(equalToConstant: 20),
            volumeIcon.heightAnchor.constraint(equalToConstant: 16),
            volumeFill.leadingAnchor.constraint(equalTo: volumeTrack.leadingAnchor),
            volumeFill.trailingAnchor.constraint(equalTo: volumeTrack.trailingAnchor),
            volumeFill.bottomAnchor.constraint(equalTo: volumeTrack.bottomAnchor),
            fill,
        ])
        // The side buttons: the system volume changes; show it.
        volumeObs = AVAudioSession.sharedInstance().observe(\.outputVolume, options: [.new]) { [weak self] _, change in
            guard let v = change.newValue else { return }
            DispatchQueue.main.async { self?.showVolume(v) }
        }
    }

    private func showVolume(_ v: Float) {
        let level = min(1, max(0, v))
        let name = level == 0 ? "speaker.slash.fill" : level < 0.34 ? "speaker.wave.1.fill" : level < 0.67 ? "speaker.wave.2.fill" : "speaker.wave.3.fill"
        volumeIcon.image = UIImage(systemName: name)
        view.layoutIfNeeded()
        volumeFillHeight?.constant = volumeTrack.bounds.height * CGFloat(level)
        UIView.animate(withDuration: 0.12, delay: 0, options: [.beginFromCurrentState, .curveEaseOut]) {
            self.volumePill.alpha = 1
            self.view.layoutIfNeeded()
        }
        view.bringSubviewToFront(volumePill)
        volumeHide?.invalidate()
        volumeHide = Timer.scheduledTimer(withTimeInterval: 1.4, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.35) { self?.volumePill.alpha = 0 }
        }
    }

    private func showHud(_ text: String, sticky: Bool) {
        hudTimer?.invalidate()
        hud.text = text
        hud.alpha = 1
        if !sticky { hudTimer = Timer.scheduledTimer(withTimeInterval: 0.8, repeats: false) { [weak self] _ in UIView.animate(withDuration: 0.25) { self?.hud.alpha = 0 } } }
    }
    private func isOnControl(_ t: UITouch) -> Bool {
        guard overlay.alpha > 0.5 else { return false }
        var v: UIView? = t.view
        while let current = v, current !== view { if current is UIControl { return true }; v = current.superview }
        return false
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        gradient.frame = overlay.bounds
        let wide = view.bounds.width > view.bounds.height
        NSLayoutConstraint.deactivate(wide ? cardConstraints.portrait : cardConstraints.landscape)
        NSLayoutConstraint.activate(wide ? cardConstraints.landscape : cardConstraints.portrait)
        updateCardVisibility()
        if aspectMode == 1 || aspectMode == 2 { applyAspect() }   // fill / stretch follow the screen's shape when it turns
        if guide != nil && !isMini { placeVideoForGuide(animated: false) }
        middle.spacing = wide ? 30 : 14   // upright the five discs must fit the width with room either side
    }

    // MARK: stream

    func load(_ next: Item) {
        item = next
        lastTime = -1
        titleLabel.text = next.title
        subtitleLabel.text = next.subtitle
        statusLabel.text = ""
        liveBadge.isHidden = !next.live
        slider.isHidden = next.live
        spacer.isHidden = !next.live
        backButton.isHidden = next.live
        forwardButton.isHidden = next.live
        timeLabel.isHidden = next.live
        elapsedLabel.isHidden = next.live
        qualityBadge.isHidden = true
        listButton.isHidden = next.list.count < 2
        listButton.accessibilityLabel = next.live ? "Guide" : next.listTitle
        // Live: the back / forward pair step 10 s through the recording (shown once there is one); films keep 30 s.
        backButton.setImage(Self.symbol(next.live ? "gobackward.10" : "gobackward.30", Self.skipGlyph), for: .normal)
        forwardButton.setImage(Self.symbol(next.live ? "goforward.10" : "goforward.30", Self.skipGlyph), for: .normal)
        liveButton.isHidden = true
        startOverButton.isHidden = true
        infoButton.isHidden = false   // stream info is always there
        hideCard(animated: false)
        ended = false
        creditsDismissed = false
        selfStepPending = false
        syncPanel.isHidden = true
        deinterlaceSet = false
        samples = []
        let rc = MPRemoteCommandCenter.shared()
        rc.skipForwardCommand.isEnabled = !next.live
        rc.skipBackwardCommand.isEnabled = !next.live
        rc.changePlaybackPositionCommand.isEnabled = !next.live
        rc.nextTrackCommand.isEnabled = next.hasNext
        rc.previousTrackCommand.isEnabled = next.hasPrev
        loadArtwork(next.art)
        prevButton.isEnabled = next.hasPrev
        nextButton.isEnabled = next.hasNext
        // Unavailable: the disc stays (the row keeps its shape), only the glyph fades.
        prevButton.tintColor = next.hasPrev ? .white : Self.offTint
        nextButton.tintColor = next.hasNext ? .white : Self.offTint

        // A quiet stretch of live watching: the buffering can come down a little again.
        if feed != .catchup && firstPicture && stalls == 0 && uptime - firstPictureAt > 600 && liveWasOn { Self.liveCaching -= 100 }
        liveWasOn = next.live
        if zapTimer?.isValid != true { zapTarget = nil }   // (a swipe still on its way keeps its target and card)
        buffer?.stop(); buffer = nil   // first: the provider allows one connection
        feed = .direct; shiftTarget = nil; sessionContent = nil; liveLag = 0; stalls = 0; triedDirect = false; expectBuffer = false
        if next.live {
            // Controls up (you pressed next): they show the new name; otherwise the tuning card does.
            if !isMini && overlay.alpha < 0.5 && zapTarget == nil { showTuning(name: next.title, number: next.num, logo: next.art, now: next.nowTitle) }
            startLive()
            if !isMini && overlay.alpha > 0.5 { showOverlay() }
        } else {
            tuningCard.hide(animated: false); dropFreeze()
            openMedia(next.url, caching: 3000, start: next.start)
            if !isMini { showOverlay() }   // the next episode in the mini player: no controls on the card
        }
        updateLiveBar()
        updateNowPlaying()
    }
    private var liveWasOn = false
    private var triedDirect = false
    private static var timeshift: Bool {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-testNoTimeshift") { return false }
        #endif
        return UserDefaults.standard.object(forKey: "m3u.timeshift") as? Bool ?? true
    }
    /// Live: VLC can keep saying "playing" while a live input is held paused, so the player's own state decides.
    private var isPlayingNow: Bool { item.live ? !userPaused : player.isPlaying }
    private func setPlayIcon() {
        let playing = isPlayingNow
        playButton.setImage(Self.symbol(playing ? "pause.fill" : "play.fill", Self.playGlyph), for: .normal)
        playButton.imageEdgeInsets = UIEdgeInsets(top: 0, left: playing ? 0 : 4, bottom: 0, right: 0)   // the triangle, optically centred
    }
    private static let offTint = UIColor(white: 1, alpha: 0.3)
    private static let playGlyph: CGFloat = 26
    private static let skipGlyph: CGFloat = 21
    private static func symbol(_ name: String, _ size: CGFloat) -> UIImage? {
        UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .semibold))
    }

    /// Opens a stream in VLC (a fresh start: the session clock, pause and first-picture state reset).
    private func openMedia(_ url: URL, caching: Int, start: Double = 0) {
        let media = VLCMedia(url: url)
        var c = caching
        #if DEBUG
        let a = ProcessInfo.processInfo.arguments
        if let i = a.firstIndex(of: "-testCaching"), i + 1 < a.count, let v = Int(a[i + 1]) { c = v }
        #endif
        media.addOption(":network-caching=\(c)")
        // (Also on the local recording's address: if it hands VLC on to the provider, they go with it.)
        if let agent = item.headers["User-Agent"] { media.addOption(":http-user-agent=\(agent)") }
        if let referrer = item.headers["Referer"] { media.addOption(":http-referrer=\(referrer)") }
        if start > 1 { media.addOption(":start-time=\(Int(start))") }
        if item.headers["X-Test-Mute"] != nil { media.addOption(":no-audio") }   // test hook only: silent
        loadStartedAt = uptime; firstPicture = false; pausedAt = nil; pausedTotal = 0; userPaused = false; lastTimeChange = uptime
        busy(true)
        player.media = media
        player.play()
        #if DEBUG
        NSLog("M3UTTFP open caching=%d %@", c, url.absoluteString)
        #endif
    }

    /// A live channel: through the timeshift buffer where it can be (pause, rewind), otherwise straight to VLC.
    private func startLive() {
        let scheme = item.url.scheme?.lowercased() ?? ""
        guard Self.timeshift, scheme == "http" || scheme == "https" else { return openDirectLive() }
        expectBuffer = true
        let it = item
        LocalStreamServer.shared.start { [weak self] port in
            guard let self = self, !self.finished, self.item.url == it.url, self.buffer == nil else { return }
            guard port != nil else { return self.openDirectLive() }
            var h = it.headers
            if h["User-Agent"] == nil { h["User-Agent"] = Self.vlcAgent }
            let b = LiveBuffer(url: it.url, headers: h)
            b.onFail = { [weak self, weak b] why in
                guard let self = self, let b = b, self.buffer === b, !self.firstPicture else { return }
                NSLog("M3UTS playing direct instead: %@", why)
                b.stop(); self.buffer = nil
                self.openDirectLive()
            }
            self.buffer = b
            b.start()
            self.startBuffered(target: nil)
        }
    }
    private var expectBuffer = false
    private func openDirectLive() {
        expectBuffer = false
        triedDirect = true
        feed = .direct
        openMedia(item.url, caching: Self.liveCaching)
    }
    /// A session of the recording: live (nil) or from a moment in it.
    private func startBuffered(target: Double?) {
        guard let b = buffer, let url = b.sessionURL(target: target) else { return openDirectLive() }
        feed = .buffered; shiftTarget = target; sessionContent = nil
        // From the phone's own storage there's no network to ride out: very little buffering, so seeks start quickly.
        openMedia(url, caching: target == nil ? Self.liveCaching : 400)
    }
    private func busy(_ on: Bool) {
        if on && !tuningCard.showing { spinner.startAnimating() } else if !on { spinner.stopAnimating() }
    }

    func finish() {
        guard !finished else { return }
        finished = true
        let (t, d) = position()
        onEvent(["type": "closed", "t": ended && d > 0 ? d : t, "d": d])
        tickTimer?.invalidate()
        hideTimer?.invalidate()
        countdown?.invalidate()
        zapTimer?.invalidate()
        buffer?.stop(); buffer = nil
        tearDownRemoteControls()
        if UIScreen.main.brightness != startBrightness { UIScreen.main.brightness = startBrightness }   // the phone's own brightness back
        player.stop()
        leaveHost()
    }

    private func position() -> (Double, Double) {
        let t = Double(player.time.intValue) / 1000
        let d = Double(player.media?.length.intValue ?? 0) / 1000
        return (max(0, t), max(0, d))
    }

    private func tick() {
        let (t, d) = position()
        let now = player.time.intValue
        if now != lastTime && player.isPlaying { spinner.stopAnimating() }
        lastTime = now
        // Radio / audio-only channels never show a picture: playing is enough.
        if !firstPicture && player.isPlaying && now > 1500 { markFirstPicture() }
        // A live stall (the picture froze while it should be playing): more buffering next time.
        if item.live && firstPicture && feed != .catchup && shiftTarget == nil && !userPaused && player.state != .paused && uptime - lastTimeChange > 1.5 {
            if !stalling { stalling = true; stalls += 1; Self.liveCaching += 300; NSLog("M3UTTFP stall: live caching now %d", Self.liveCaching) }
        }
        // The provider dropped and the recording has picked up again, but VLC is stuck on the gap (a restarted feed's
        // clock): start the session again from the recording, at the same distance behind.
        if item.live, feed == .buffered, firstPicture, !userPaused, uptime - lastTimeChange > 4, uptime - recoveredAt > 10,
           let b = buffer, !b.window.direct, wall - b.window.edge < 2, let pos = livePosition() {
            recoveredAt = uptime
            NSLog("M3UTS picture stuck: restarting the session")
            holdFrame()
            startBuffered(target: shiftTarget == nil && isAtLive ? nil : pos)
        }
        updateLiveBar()
        if item.live && item.nowEnd > 0 && wall * 1000 > item.nowEnd { refreshNow() }
        if !item.live && !scrubbing && d > 0 { slider.value = Float(t / d) }
        if !item.live && !scrubbing { elapsedLabel.text = clock(t); timeLabel.text = clock(d) }
        updateQuality()
        setPlayIcon()
        ticks += 1
        // (Once it has ended VLC's clock reads ~0: report the full length, or the episode would lose its "watched".)
        if !item.live && d > 0 && ticks % 5 == 0 { onEvent(["type": "progress", "t": ended ? d : t, "d": d]) }
        checkCredits(t, d)
        if ticks % 5 == 0 { updateNowPlaying() }
        if let m = player.media {
            samples.append((ProcessInfo.processInfo.systemUptime, Int64(m.statistics.readBytes)))
            if samples.count > 6 { samples.removeFirst() }
        }
    }

    private func updateQuality() {
        let size = player.videoSize
        guard size.width > 0, size.height > 0 else { return }
        let w = max(size.width, size.height), h = min(size.width, size.height)
        let label = w >= 3200 || h >= 1800 ? "4K" : w >= 1200 || h >= 700 ? "HD" : "SD"
        if qualityBadge.text != " \(label) " || qualityBadge.isHidden { qualityBadge.text = " \(label) "; qualityBadge.isHidden = false }
    }

    private func clock(_ seconds: Double) -> String {
        let s = Int(seconds)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    private var loadStartedAt: TimeInterval = 0
    private var firstPicture = false
    private var stalling = false
    private var recoveredAt: TimeInterval = 0
    func mediaPlayerTimeChanged(_ aNotification: Notification) {
        lastTimeChange = uptime
        stalling = false
        guard !firstPicture, player.videoSize.width > 0 else { return }
        markFirstPicture()
    }
    /// The new picture is up: the tuning card and held frame go, and the live clock starts.
    private func markFirstPicture() {
        firstPicture = true
        firstPictureAt = uptime
        if feed == .buffered, let b = buffer {
            sessionContent = b.sessionStart() ?? shiftTarget ?? wall
            if shiftTarget == nil { liveLag = max(0, wall - (sessionContent ?? wall)) }
        }
        tuningCard.hide()
        dropFreeze()
        busy(false)
        NSLog("M3UTTFP %.0f ms live=%d feed=%@ lag=%.1f url=%@", (uptime - loadStartedAt) * 1000, item.live ? 1 : 0, "\(feed)", liveLag, item.url.absoluteString)
        updateLiveBar()
    }

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        switch player.state {
        case .opening, .buffering:
            if !player.isPlaying { busy(true) }
        case .playing:
            ended = false   // played again after the end
            applyAspect()
            if isMini { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.layoutInParent() } }   // next episode on the mini card: fit its picture
            // Interlaced broadcasts (most live sport, many UK channels) get VLC's smooth 50fps deinterlacer; progressive
            // pictures are left alone (auto only acts on interlaced frames).
            if !deinterlaceSet { deinterlaceSet = true; player.setDeinterlace(.auto, withFilter: item.live ? "yadif2x" : "yadif") }
            spinner.stopAnimating()
            statusLabel.text = ""
            updateNowPlaying()
        case .paused:
            updateNowPlaying()
        case .error:
            // The local recording couldn't be played: once, straight from the provider instead.
            if item.live && feed == .buffered && !firstPicture && !triedDirect {
                buffer?.stop(); buffer = nil
                return openDirectLive()
            }
            tuningCard.hide(); dropFreeze()
            spinner.stopAnimating()
            statusLabel.text = "This stream couldn't be played"
            showOverlay(keep: true)
            onEvent(["type": "error", "message": "This stream couldn't be played"])
        case .ended:
            if item.live {   // the archive ran up to now (or a feed dropped): back to live
                if feed == .catchup || feed == .buffered { goLive() }
                return
            }
            let (t, d) = position()
            onEvent(["type": "progress", "t": d > 0 ? d : t, "d": d])
            onEvent(["type": "ended"])
            ended = true
            if hasNextEpisode {
                // Ended with no card up (a short video, or "Watch credits"): a short card, then the next one.
                if cardMode == .none { presentCard(seconds: creditsDismissed ? 5 : 10) }
            } else { showOverlay(keep: true) }
        default:
            break
        }
    }

    // MARK: controls

    private func buildOverlay() {
        overlay.frame = view.bounds
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // The controls' own backing, so they read over any picture: the whole frame dims a little, and soft gradients
        // darken the top (titles, buttons) and bottom (bar, tools) further.
        overlay.backgroundColor = UIColor(white: 0, alpha: 0.25)
        gradient.colors = [UIColor.black.withAlphaComponent(0.55).cgColor, UIColor.clear.cgColor, UIColor.clear.cgColor, UIColor.black.withAlphaComponent(0.65).cgColor]
        gradient.locations = [0, 0.28, 0.62, 1]
        overlay.layer.addSublayer(gradient)
        view.addSubview(overlay)

        spinner.color = .white
        spinner.hidesWhenStopped = true
        spinner.isUserInteractionEnabled = false   // it sits over the play button: never block taps
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)

        func style(_ b: UIButton, _ symbol: String, _ size: CGFloat = 22, box: CGFloat? = nil) {
            b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .semibold)), for: .normal)
            b.tintColor = .white
            b.translatesAutoresizingMaskIntoConstraints = false
            b.widthAnchor.constraint(equalToConstant: box ?? size + 24).isActive = true
            b.heightAnchor.constraint(equalToConstant: box ?? size + 24).isActive = true
        }
        // Centre transport: each on a translucent dark disc (they sit over the picture), play / pause the largest.
        func disc(_ b: UIButton, _ symbol: String, _ glyph: CGFloat, _ d: CGFloat) {
            style(b, symbol, glyph, box: d)
            b.backgroundColor = UIColor(white: 0, alpha: 0.38)
            b.layer.cornerRadius = d / 2
            b.layer.borderWidth = 1 / UIScreen.main.scale
            b.layer.borderColor = UIColor(white: 1, alpha: 0.14).cgColor
        }
        style(closeButton, "xmark")
        style(rotateButton, UIImage(systemName: "rectangle.landscape.rotate") != nil ? "rectangle.landscape.rotate" : "rotate.right")
        rotateButton.accessibilityLabel = "Rotate"
        rotateButton.addTarget(self, action: #selector(rotateTapped), for: .touchUpInside)
        style(audioButton, "speaker.wave.2")
        style(subsButton, "captions.bubble")
        style(infoButton, "info.circle")
        disc(playButton, "pause.fill", Self.playGlyph, 64)
        disc(backButton, "gobackward.30", Self.skipGlyph, 48)
        disc(forwardButton, "goforward.30", Self.skipGlyph, 48)
        disc(prevButton, "backward.end.fill", 17, 44)
        disc(nextButton, "forward.end.fill", 17, 44)
        // Tools row: compact 36 pt targets so it sits close over the bar (one group with it).
        style(listButton, "list.bullet", 20, box: 36)
        style(aspectButton, "aspectratio", 20, box: 36)
        aspectButton.setImage(Self.aspectIcon(), for: .normal)
        aspectButton.accessibilityLabel = "Picture size"
        aspectButton.addTarget(self, action: #selector(aspectTapped), for: .touchUpInside)
        style(lockButton, "lock", 20, box: 36)
        lockButton.accessibilityLabel = "Lock screen"
        style(unlockButton, "lock.fill", 22)
        unlockButton.accessibilityLabel = "Unlock"
        unlockButton.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        unlockButton.layer.cornerRadius = 23
        unlockButton.alpha = 0
        listButton.addTarget(self, action: #selector(showList), for: .touchUpInside)
        lockButton.addTarget(self, action: #selector(lockTapped), for: .touchUpInside)
        unlockButton.addTarget(self, action: #selector(unlockTapped), for: .touchUpInside)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        audioButton.addTarget(self, action: #selector(pickAudio), for: .touchUpInside)
        subsButton.addTarget(self, action: #selector(pickSubtitles), for: .touchUpInside)
        infoButton.addTarget(self, action: #selector(showInfo), for: .touchUpInside)
        playButton.addTarget(self, action: #selector(playPause), for: .touchUpInside)
        backButton.addTarget(self, action: #selector(jumpBack), for: .touchUpInside)
        forwardButton.addTarget(self, action: #selector(jumpForward), for: .touchUpInside)
        prevButton.addTarget(self, action: #selector(prevTapped), for: .touchUpInside)
        nextButton.addTarget(self, action: #selector(nextTapped), for: .touchUpInside)

        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.textColor = .white
        subtitleLabel.font = .systemFont(ofSize: 13)
        subtitleLabel.textColor = UIColor.white.withAlphaComponent(0.75)
        statusLabel.font = .systemFont(ofSize: 14, weight: .medium)
        statusLabel.textColor = .white
        statusLabel.textAlignment = .center
        for l in [timeLabel, elapsedLabel] {
            l.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
            l.textColor = .white
            l.setContentHuggingPriority(.required, for: .horizontal)
            l.setContentCompressionResistancePriority(.required, for: .horizontal)
        }
        timeLabel.textColor = UIColor.white.withAlphaComponent(0.75)
        // Quiet outlined tag, like the HD mark on a TV guide.
        qualityBadge.font = .systemFont(ofSize: 10, weight: .bold)
        qualityBadge.textColor = .white
        qualityBadge.layer.borderColor = UIColor.white.withAlphaComponent(0.8).cgColor
        qualityBadge.layer.borderWidth = 1
        qualityBadge.layer.cornerRadius = 3
        qualityBadge.isHidden = true
        qualityBadge.setContentHuggingPriority(.required, for: .horizontal)
        qualityBadge.setContentCompressionResistancePriority(.required, for: .horizontal)
        // Small and quiet: a red dot and "LIVE", no coloured bar.
        let live = NSMutableAttributedString(string: "● ", attributes: [.foregroundColor: UIColor(red: 1, green: 0.27, blue: 0.3, alpha: 1), .font: UIFont.systemFont(ofSize: 10, weight: .bold)])
        live.append(NSAttributedString(string: "LIVE", attributes: [.foregroundColor: UIColor.white, .font: UIFont.systemFont(ofSize: 12, weight: .semibold), .kern: 0.8]))
        liveBadge.attributedText = live
        slider.minimumTrackTintColor = UIColor(red: 0.36, green: 0.58, blue: 1, alpha: 1)
        slider.maximumTrackTintColor = UIColor.white.withAlphaComponent(0.3)
        slider.addTarget(self, action: #selector(scrubStart), for: .touchDown)
        slider.addTarget(self, action: #selector(scrubMoved), for: .valueChanged)
        slider.addTarget(self, action: #selector(scrubEnd), for: [.touchUpInside, .touchUpOutside, .touchCancel])

        let titleRow = UIStackView(arrangedSubviews: [qualityBadge, titleLabel])
        titleRow.spacing = 6
        titleRow.alignment = .center
        let titles = UIStackView(arrangedSubviews: [titleRow, subtitleLabel])
        titles.axis = .vertical
        titles.spacing = 2
        let gap = UIView()   // the title gets the room first; the gap only takes what's left
        gap.setContentHuggingPriority(.init(1), for: .horizontal)
        gap.setContentCompressionResistancePriority(.init(1), for: .horizontal)
        let top = UIStackView(arrangedSubviews: [titles, gap, infoButton, audioButton, subsButton, rotateButton, closeButton])
        top.alignment = .center
        top.spacing = 4
        middle.addArrangedSubview(prevButton); middle.addArrangedSubview(backButton); middle.addArrangedSubview(playButton)
        middle.addArrangedSubview(forwardButton); middle.addArrangedSubview(nextButton)
        middle.alignment = .center
        middle.spacing = 26
        spacer.setContentHuggingPriority(.init(1), for: .horizontal)
        // Live with a recording: [Start over] [−4:12] ———●——— [● LIVE]. The live edge is the bar's right end, so "LIVE"
        // sits there; the time behind sits left, where the bar's own time does for films.
        styleCapsule(startOverButton, symbol: "arrow.counterclockwise", title: "Start over")
        startOverButton.accessibilityLabel = "Start this programme over"
        startOverButton.addTarget(self, action: #selector(startOverTapped), for: .touchUpInside)
        liveButton.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
        liveButton.layer.cornerRadius = 14
        liveButton.layer.cornerCurve = .continuous
        liveButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
        liveButton.addTarget(self, action: #selector(liveTapped), for: .touchUpInside)
        for b in [startOverButton, liveButton] {
            b.setContentHuggingPriority(.required, for: .horizontal)
            b.setContentCompressionResistancePriority(.required, for: .horizontal)
            b.isHidden = true
        }
        styleLiveButton(atLive: true)
        // A slim bar with a small thumb that grows under your finger.
        slider.setThumbImage(Self.thumb(14), for: .normal)
        slider.setThumbImage(Self.thumb(22), for: .highlighted)
        let bottom = UIStackView(arrangedSubviews: [liveBadge, startOverButton, elapsedLabel, slider, timeLabel, liveButton, spacer])
        bottom.alignment = .center
        bottom.spacing = 12
        // The slider takes all the spare width (it collapsed to its thumb before); labels keep their size.
        slider.setContentHuggingPriority(.defaultLow - 1, for: .horizontal)
        liveBadge.setContentHuggingPriority(.required, for: .horizontal)
        slider.heightAnchor.constraint(equalToConstant: 34).isActive = true
        bottom.heightAnchor.constraint(greaterThanOrEqualToConstant: 34).isActive = true   // same height with or without the bar
        for v in [top, middle, bottom, statusLabel] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            overlay.addSubview(v)
        }
        // Tools row just above the bar, like VLC-style players: picture size left, lock centre, list right.
        for b in [aspectButton, lockButton, listButton] { overlay.addSubview(b) }
        NSLayoutConstraint.activate([
            // (glyph edges on the bar's 16 pt margins)
            aspectButton.leadingAnchor.constraint(equalTo: overlay.safeAreaLayoutGuide.leadingAnchor, constant: 8),
            lockButton.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            listButton.trailingAnchor.constraint(equalTo: overlay.safeAreaLayoutGuide.trailingAnchor, constant: -8),
        ] + [aspectButton, lockButton, listButton].map { $0.bottomAnchor.constraint(equalTo: bottom.topAnchor, constant: 4) })
        unlockButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(unlockButton)

        hud.font = .monospacedDigitSystemFont(ofSize: 22, weight: .semibold)
        hud.textColor = .white
        hud.textAlignment = .center
        hud.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        hud.layer.cornerRadius = 12
        hud.clipsToBounds = true
        hud.alpha = 0
        hud.isUserInteractionEnabled = false
        hud.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hud)

        func pill(_ title: String?, _ symbol: String?, _ action: Selector) -> UIButton {
            let b = UIButton(type: .system)
            if let title = title { b.setTitle(title, for: .normal) }
            if let symbol = symbol { b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold)), for: .normal) }
            b.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
            b.tintColor = .white
            b.addTarget(self, action: action, for: .touchUpInside)
            b.widthAnchor.constraint(greaterThanOrEqualToConstant: 40).isActive = true
            b.heightAnchor.constraint(equalToConstant: 40).isActive = true
            return b
        }
        syncLabel.font = .monospacedDigitSystemFont(ofSize: 15, weight: .medium)
        syncLabel.textColor = .white
        syncLabel.textAlignment = .center
        syncLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 150).isActive = true
        for v in [pill(nil, "minus", #selector(syncMinus)), syncLabel, pill(nil, "plus", #selector(syncPlus)), pill("Reset", nil, #selector(syncReset)), pill(nil, "xmark", #selector(syncDone))] { syncPanel.addArrangedSubview(v) }
        syncPanel.spacing = 6
        syncPanel.alignment = .center
        syncPanel.backgroundColor = UIColor.black.withAlphaComponent(0.7)
        syncPanel.layer.cornerRadius = 12
        syncPanel.isLayoutMarginsRelativeArrangement = true
        syncPanel.layoutMargins = UIEdgeInsets(top: 2, left: 10, bottom: 2, right: 10)
        syncPanel.isHidden = true
        syncPanel.translatesAutoresizingMaskIntoConstraints = false
        overlay.addSubview(syncPanel)

        nextCard.translatesAutoresizingMaskIntoConstraints = false
        nextCard.alpha = 0
        nextCard.isHidden = true
        nextCard.onPrimary = { [weak self] in self?.cardPrimary() }
        nextCard.onSecondary = { [weak self] in self?.cardSecondary() }
        view.addSubview(nextCard)
        // Bottom right when sideways; along the bottom, clear of the controls bar, when upright.
        let sg = view.safeAreaLayoutGuide
        cardConstraints.landscape = [
            nextCard.trailingAnchor.constraint(equalTo: sg.trailingAnchor, constant: -16),
            nextCard.bottomAnchor.constraint(equalTo: sg.bottomAnchor, constant: -16),
            nextCard.widthAnchor.constraint(equalToConstant: 360),
        ]
        cardConstraints.portrait = [
            nextCard.leadingAnchor.constraint(equalTo: sg.leadingAnchor, constant: 12),
            nextCard.trailingAnchor.constraint(equalTo: sg.trailingAnchor, constant: -12),
            nextCard.bottomAnchor.constraint(equalTo: sg.bottomAnchor, constant: -104),
        ]
        NSLayoutConstraint.activate([
            hud.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hud.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -90),
            hud.heightAnchor.constraint(equalToConstant: 48),
            hud.widthAnchor.constraint(greaterThanOrEqualToConstant: 150),
            syncPanel.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            syncPanel.bottomAnchor.constraint(equalTo: lockButton.topAnchor, constant: -10),
        ])
        let g = overlay.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            top.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            top.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -8),
            top.topAnchor.constraint(equalTo: g.topAnchor, constant: 8),
            middle.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            middle.centerYAnchor.constraint(equalTo: overlay.centerYAnchor),
            bottom.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            bottom.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),
            bottom.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -8),

            statusLabel.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            statusLabel.topAnchor.constraint(equalTo: middle.bottomAnchor, constant: 18),
            unlockButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            unlockButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    private func showOverlay(keep: Bool = false) {
        if locked { return }
        hideTimer?.invalidate()
        if tuningCard.showing { tuningCard.hide(animated: true); if !firstPicture { spinner.startAnimating() } }   // the controls show the name now
        overlay.isUserInteractionEnabled = true
        UIView.animate(withDuration: 0.2) { self.overlay.alpha = 1 }
        updateCardVisibility()
        if !keep {
            hideTimer = Timer.scheduledTimer(withTimeInterval: item.headers["X-Test-Mute"] != nil ? 30 : 4, repeats: false) { [weak self] _ in
                guard let self = self, self.isPlayingNow, !self.scrubbing else { return }
                self.hideOverlay()
            }
        }
    }

    private func hideOverlay() {
        hideTimer?.invalidate()
        overlay.isUserInteractionEnabled = false   // hidden controls can't be pressed by accident
        UIView.animate(withDuration: 0.25) { self.overlay.alpha = 0 }
        updateCardVisibility()
    }

    private var lastTap: (time: TimeInterval, x: CGFloat, controls: Bool) = (0, 0, false)
    private func tapped(at point: CGPoint) {
        #if DEBUG
        NSLog("M3UDBG tap alpha=%.2f", overlay.alpha)
        #endif
        let now = ProcessInfo.processInfo.systemUptime, x = point.x, w = view.bounds.width
        let side = x < w / 3 ? -1 : x > w * 2 / 3 ? 1 : 0
        let quickSecond = now - lastTap.time < 0.3 && abs(x - lastTap.x) < 80
        // Live: only while the controls were up at the first tap (the screen's thirds are for swipes otherwise).
        let liveSkip = item.live && lastTap.controls && liveWindow() != nil
        lastTap = (now, x, overlay.alpha > 0.5)
        if quickSecond && side != 0 && (!item.live || liveSkip) { return skip(side) }
        if overlay.alpha > 0.5 { hideOverlay() } else { showOverlay() }
    }

    private func skip(_ dir: Int) {
        if item.live {
            guard let pos = livePosition() else { return }
            if dir > 0 && isAtLive { return showOverlay() }
            seekLive(to: pos + Double(dir) * 10)
            statusLabel.text = dir < 0 ? "« 10s" : "10s »"
            showOverlay()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in if self?.statusLabel.text?.contains("10s") == true { self?.statusLabel.text = "" } }
            return
        }
        if dir < 0 { player.jumpBackward(10); statusLabel.text = "« 10s" } else { player.jumpForward(10); statusLabel.text = "10s »" }
        showOverlay()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in if self?.statusLabel.text?.contains("10s") == true { self?.statusLabel.text = "" } }
    }


    private func zap(next: Bool) {
        guard item.live else { return }
        let to = (zapTarget ?? item.current) + (next ? 1 : -1)
        if item.list.indices.contains(to - item.listStart) { return zapTo(index: to) }
        // (No list to look ahead in: let the app step.)
        if zapTarget == nil, next ? item.hasNext : item.hasPrev { onEvent(["type": "step", "dir": next ? 1 : -1]) }
    }

    /// Channel change, as it should feel: that channel's card at once (from the list the player already has), the old
    /// one silenced, and the app asked for the new stream. Quick swipes in a row only open the one you stop on.
    private func zapTo(index to: Int) {
        let row = to - item.listStart
        guard item.list.indices.contains(row) else { return }
        let r = item.list[row]
        let logo = item.listLogo.indices.contains(row) ? item.listLogo[row] : ""
        let num = item.listNum.indices.contains(row) ? item.listNum[row] : 0
        if overlay.alpha > 0.5 && guide == nil {
            titleLabel.text = r.title; subtitleLabel.text = r.detail
            holdFrame(); busy(true); showOverlay()
        } else {
            showTuning(name: r.title, number: num, logo: logo, now: r.detail)
        }
        player.pause()
        let quick = uptime - lastZap < 0.6
        lastZap = uptime
        zapTarget = to
        zapTimer?.invalidate()
        let fire = { [weak self] in
            self?.zapTimer = nil
            self?.onEvent(["type": "jump", "index": to])
        }
        if quick { zapTimer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: false) { _ in fire() } } else { fire() }
    }

    private func showTuning(name: String, number: Int, logo: String, now: String) {
        holdFrame()
        spinner.stopAnimating()
        tuningCard.show(name: name, number: number, logo: logo, now: now)
    }
    /// Keeps the current picture on screen (dimmed by whatever is over it) until the next one arrives.
    private func holdFrame() {
        guard freeze == nil, !isMini, firstPicture || player.isPlaying else { return }
        guard let snap = videoView.snapshotView(afterScreenUpdates: false) else { return }
        snap.frame = videoView.frame
        snap.transform = videoView.transform
        snap.isUserInteractionEnabled = false
        view.insertSubview(snap, aboveSubview: videoView)
        freeze = snap
    }
    private func dropFreeze() {
        guard let f = freeze else { return }
        freeze = nil
        UIView.animate(withDuration: 0.2, animations: { f.alpha = 0 }) { _ in f.removeFromSuperview() }
    }

    // MARK: live timeline
    /// The stretch of this channel you can move about in (wall-clock seconds): from the oldest recorded moment (or the
    /// programme's start, when the provider has catch-up) to live.
    private func liveWindow() -> (start: Double, end: Double)? {
        guard item.live else { return nil }
        let now = wall
        var start: Double?
        if feed == .buffered, let b = buffer { let w = b.window; if !w.direct && w.start > 0 { start = w.start } }
        // A channel still starting that will be recorded: the bar is there from the first moment (no jump when it fills).
        if start == nil && expectBuffer && (buffer.map { !$0.window.direct } ?? true) { start = now - liveLag }
        if feed == .catchup { start = catchupStart }
        if catchupOK, item.nowStart > 0 {
            let ps = item.nowStart / 1000
            if ps > now - Double(item.catchupDays) * 86400 { start = min(start ?? ps, ps) }
        }
        guard let st = start else { return nil }
        return (st, max(st, now - liveLag))
    }
    private var catchupOK: Bool { item.catchupDays > 0 && askJS != nil }
    /// The moment of TV on screen now (wall-clock seconds).
    private func livePosition() -> Double? {
        switch feed {
        case .direct: return wall - liveLag
        case .catchup: return catchupStart + Double(max(0, player.time.intValue)) / 1000
        case .buffered:
            guard let start = sessionContent ?? shiftTarget else { return wall - liveLag }
            guard firstPicture else { return start }
            let played = (uptime - firstPictureAt) - pausedTotal - (pausedAt.map { uptime - $0 } ?? 0)
            return start + max(0, played)
        }
    }
    private var isAtLive: Bool {
        guard feed != .catchup, let w = liveWindow(), let p = livePosition() else { return feed != .catchup }
        return w.end - p < 8
    }

    private func setShown(_ v: UIView, _ on: Bool) { if v.isHidden == on { v.isHidden = !on } }
    /// Once a second (and after every change): the bar, the time behind, LIVE, Start over.
    private func updateLiveBar() {
        guard item.live else { return }
        let w = liveWindow(), pos = livePosition()
        let shiftable = w != nil && pos != nil
        setShown(liveBadge, !shiftable)
        setShown(spacer, !shiftable)
        setShown(slider, shiftable)
        setShown(liveButton, shiftable)
        setShown(backButton, shiftable)
        setShown(forwardButton, shiftable)
        guard let win = w, let p0 = pos else { setShown(elapsedLabel, false); setShown(startOverButton, false); return }
        let span = max(1, win.end - win.start)
        let p = min(max(p0, win.start), win.end)
        let behind = win.end - p
        let atLive = feed != .catchup && behind < 8
        #if DEBUG
        if ticks % 5 == 0 { NSLog("M3ULIVE feed=%@ win=%.1f..%.1f pos=%.1f behind=%.1f lag=%.1f paused=%.1f vlc=%d", "\(feed)", win.start - wall, win.end - wall, p0 - wall, behind, liveLag, pausedTotal, player.time.intValue) }
        #endif
        if !scrubbing {
            slider.value = Float((p - win.start) / span)
            elapsedLabel.text = "−" + clock(behind)
            setShown(elapsedLabel, !atLive)
        }
        styleLiveButton(atLive: atLive)
        forwardButton.isEnabled = !atLive
        forwardButton.tintColor = atLive ? Self.offTint : .white
        let ps = item.nowStart / 1000
        let canStartOver = item.nowStart > 0 && item.nowEnd / 1000 > wall && ps >= win.start - 5 && p > ps + 30
        setShown(startOverButton, canStartOver)
    }

    private var liveStyled: Bool?
    private func styleLiveButton(atLive: Bool) {
        guard liveStyled != atLive else { return }
        liveStyled = atLive
        // At live: a quiet filled capsule (red dot). Behind: the same capsule outlined, grey dot: a button back to live.
        let dot = atLive ? UIColor(red: 1, green: 0.27, blue: 0.3, alpha: 1) : UIColor(white: 1, alpha: 0.45)
        let t = NSMutableAttributedString(string: "● ", attributes: [.foregroundColor: dot, .font: UIFont.systemFont(ofSize: 10, weight: .bold)])
        t.append(NSAttributedString(string: "LIVE", attributes: [.foregroundColor: UIColor.white, .font: UIFont.systemFont(ofSize: 12, weight: .semibold), .kern: 0.8]))
        liveButton.setAttributedTitle(t, for: .normal)
        liveButton.layer.borderWidth = 1
        liveButton.layer.borderColor = UIColor(white: 1, alpha: atLive ? 0 : 0.45).cgColor
        liveButton.backgroundColor = UIColor(white: 1, alpha: atLive ? 0.14 : 0.08)
        liveButton.isUserInteractionEnabled = !atLive
        liveButton.accessibilityLabel = atLive ? "Live" : "Go to live"
    }
    private func styleCapsule(_ b: UIButton, symbol: String, title: String) {
        b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .bold)), for: .normal)
        b.setTitle(" " + title, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        b.tintColor = .white
        b.setTitleColor(.white, for: .normal)
        b.setTitleColor(UIColor(white: 1, alpha: 0.6), for: .highlighted)
        b.backgroundColor = UIColor(white: 1, alpha: 0.14)
        b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 12)
        b.layer.cornerRadius = 14
        b.layer.cornerCurve = .continuous
        b.heightAnchor.constraint(equalToConstant: 28).isActive = true
    }
    private static func thumb(_ d: CGFloat) -> UIImage {
        let pad: CGFloat = 4
        return UIGraphicsImageRenderer(size: CGSize(width: d + pad * 2, height: d + pad * 2)).image { ctx in
            ctx.cgContext.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor(white: 0, alpha: 0.4).cgColor)
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(x: pad, y: pad, width: d, height: d)).fill()
        }
    }

    @objc private func liveTapped() { goLive(); showOverlay() }
    @objc private func startOverTapped() { seekLive(to: item.nowStart / 1000); showOverlay() }

    /// Back to live: a new live session of the recording, or (from catch-up) the channel itself again.
    private func goLive() {
        guard item.live else { return }
        holdFrame()
        if feed == .buffered, buffer != nil { startBuffered(target: nil) }
        else { buffer?.stop(); buffer = nil; feed = .direct; liveLag = 0; startLive() }
        updateLiveBar()
    }

    /// Moves to a moment of this channel: within the recording, else from the provider's archive.
    private func seekLive(to t: Double) {
        guard let w = liveWindow() else { return }
        if t >= w.end - 5 { if !isAtLive || feed == .catchup { goLive() }; return }
        if feed == .buffered, let b = buffer, t >= b.window.start - 1 {
            holdFrame()
            startBuffered(target: max(t, b.window.start))
        } else if feed == .catchup, t >= catchupStart, player.isSeekable, (player.media?.length.intValue ?? 0) > 0 {
            player.time = VLCTime(int: Int32((t - catchupStart) * 1000))
        } else if catchupOK, t > wall - Double(item.catchupDays) * 86400 {
            startCatchup(at: t)
        } else if feed == .buffered, let b = buffer {
            holdFrame()
            startBuffered(target: b.window.start)
        }
        updateLiveBar()
    }

    /// The provider's archive from `t` (to the end of the programme it falls in). The live recording stops: one connection.
    private func startCatchup(at t: Double) {
        let from = (t / 60).rounded(.down) * 60
        let inNow = item.nowStart > 0 && from >= item.nowStart / 1000 - 60 && item.nowEnd > 0
        let to = inNow ? item.nowEnd / 1000 : from + 3 * 3600
        holdFrame(); busy(true)
        askJS?("window.__playerCatchup && window.__playerCatchup(\(Int64(from * 1000)), \(Int64(to * 1000)))") { [weak self] r in
            guard let self = self, !self.finished else { return }
            guard let s = r as? String, !s.isEmpty, let url = URL(string: s) else {
                self.busy(false); self.dropFreeze()
                self.statusLabel.text = "Catch-up isn't available for this"
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.statusLabel.text = "" }
                return
            }
            self.buffer?.stop(); self.buffer = nil
            self.feed = .catchup
            self.catchupStart = from
            self.openMedia(url, caching: 1500)
            self.updateLiveBar()
        }
    }

    /// Pause / play. A paused recording keeps recording; play carries on from where it stopped, behind live.
    private func setPaused(_ p: Bool) {
        if p {
            player.pause()
            userPaused = true
            if feed == .buffered, pausedAt == nil { buffer?.pauseSession(); pausedAt = uptime }
        } else {
            if feed == .buffered, let a = pausedAt { buffer?.resumeSession(); pausedTotal += uptime - a; pausedAt = nil }
            userPaused = false
            player.play()
        }
        updateLiveBar()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.updateNowPlaying() }
    }

    // MARK: mini guide
    private func guideData() -> GuideData {
        let rows = item.list.indices.map { i in
            GuideRow(name: item.list[i].title, number: item.listNum.indices.contains(i) ? item.listNum[i] : 0,
                     logo: item.listLogo.indices.contains(i) ? item.listLogo[i] : "", now: item.list[i].detail,
                     start: item.listNowStart.indices.contains(i) ? item.listNowStart[i] : 0,
                     end: item.listNowEnd.indices.contains(i) ? item.listNowEnd[i] : 0)
        }
        return GuideData(rows: rows, current: item.current - item.listStart, group: item.group, nowTitle: item.nowTitle, nowStart: item.nowStart,
                         nowEnd: item.nowEnd, nowDesc: item.nowDesc, nextTitle: item.nextTitle, nextStart: item.nextStart)
    }
    /// Fresh rows from the app (what's on changes while you watch): { list, listStart, current, group, nowT, nowA, … }.
    private func applyGuideJSON(_ r: Any?) -> Bool {
        guard let s = r as? String, let d = s.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return false }
        let rows = (o["list"] as? [[String: Any]]) ?? []
        if !rows.isEmpty, let cur = o["current"] as? Int, cur == item.current {
            item.list = rows.map { (($0["t"] as? String) ?? "", ($0["s"] as? String) ?? "") }
            item.listStart = (o["listStart"] as? Int) ?? 0
            item.listLogo = rows.map { ($0["l"] as? String) ?? "" }
            item.listNum = rows.map { ($0["n"] as? NSNumber)?.intValue ?? 0 }
            item.listNowStart = rows.map { ($0["pa"] as? NSNumber)?.doubleValue ?? 0 }
            item.listNowEnd = rows.map { ($0["pb"] as? NSNumber)?.doubleValue ?? 0 }
        }
        if let t = o["nowT"] as? String {
            item.nowTitle = t
            item.nowStart = (o["nowA"] as? NSNumber)?.doubleValue ?? 0
            item.nowEnd = (o["nowB"] as? NSNumber)?.doubleValue ?? 0
            item.nextTitle = (o["nextT"] as? String) ?? ""
            item.nextStart = (o["nextA"] as? NSNumber)?.doubleValue ?? 0
            if !t.isEmpty { subtitleLabel.text = "\(t) · ends \(PlayerStyle.clock(item.nowEnd))" }
        }
        return true
    }
    private var nowAskedAt: TimeInterval = 0
    /// The programme ended while you watched: what's on now (for the bar, Start over and the guide).
    private func refreshNow() {
        guard askJS != nil, uptime - nowAskedAt > 60 else { return }
        nowAskedAt = uptime
        askJS?("window.__playerGuide && window.__playerGuide()") { [weak self] r in _ = self?.applyGuideJSON(r); self?.updateLiveBar() }
    }

    private func openGuide() {
        guard item.live, guide == nil, !isMini, !locked, !item.list.isEmpty else { return }
        let g = GuidePanel(data: guideData())
        g.frame = view.bounds
        g.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        g.onClose = { [weak self] in self?.closeGuide() }
        g.onPick = { [weak self] row in
            guard let self = self else { return }
            let to = self.item.listStart + row
            self.closeGuide()
            self.zapTo(index: to)
        }
        hideOverlay()
        view.addSubview(g)
        guide = g
        placeVideoForGuide(animated: true)
        g.present()
        askJS?("window.__playerGuide && window.__playerGuide()") { [weak self] r in
            guard let self = self, let g = self.guide, self.applyGuideJSON(r) else { return }
            g.update(self.guideData())
        }
    }
    private func closeGuide() {
        guard let g = guide else { return }
        guide = nil
        g.dismiss { g.removeFromSuperview() }
        UIView.animate(withDuration: 0.36, delay: 0, usingSpringWithDamping: 0.95, initialSpringVelocity: 0) {
            self.videoView.transform = .identity
            self.freeze?.transform = .identity
        }
    }
    /// Upright, the picture moves to the top so the guide sheet sits under it (nothing covered); sideways it stays put.
    private func placeVideoForGuide(animated: Bool) {
        guard let g = guide else { return }
        var t = CGAffineTransform.identity
        if view.bounds.height > view.bounds.width {
            let band = pictureBand(view.bounds.size)
            let dy = view.safeAreaInsets.top + 6 - band.minY
            t = CGAffineTransform(translationX: 0, y: dy)
            g.videoBottom = band.maxY + dy + 10
        }
        let apply = { self.videoView.transform = t; self.freeze?.transform = t; g.setNeedsLayout(); g.layoutIfNeeded() }
        if animated { UIView.animate(withDuration: 0.42, delay: 0, usingSpringWithDamping: 0.92, initialSpringVelocity: 0.2, options: [], animations: apply) } else { apply() }
    }

    // MARK: mini player
    // Swipe down (portrait) shrinks the player into a card in the bottom-right corner, still playing, with the app
    // usable behind it. Tap the card (or swipe it up) for full screen again; swipe it down or tap × to stop.
    private(set) var isMini = false
    private let miniClose = UIButton(type: .system)

    func show(in host: UIViewController) {
        host.addChild(self)
        view.frame = host.view.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.alpha = 0
        view.transform = CGAffineTransform(translationX: 0, y: 40)
        host.view.addSubview(view)
        didMove(toParent: host)
        UIView.animate(withDuration: 0.28, delay: 0, options: .curveEaseOut) { self.view.alpha = 1; self.view.transform = .identity }
        hostAppearanceChanged()
    }

    private func leaveHost() {
        guard let host = parent else { dismiss(animated: true); return }
        UIView.animate(withDuration: 0.22, delay: 0, options: .curveEaseIn, animations: {
            self.view.alpha = 0
            if self.isMini { self.view.transform = self.view.transform.concatenating(CGAffineTransform(translationX: 0, y: 60)) }
        }) { _ in
            self.willMove(toParent: nil)
            self.view.removeFromSuperview()
            self.removeFromParent()
            host.setNeedsStatusBarAppearanceUpdate()
            host.setNeedsUpdateOfHomeIndicatorAutoHidden()
            if #available(iOS 16.0, *) { host.setNeedsUpdateOfSupportedInterfaceOrientations() }
        }
    }

    private func hostAppearanceChanged() {
        guard let host = parent else { return }
        host.setNeedsStatusBarAppearanceUpdate()
        host.setNeedsUpdateOfHomeIndicatorAutoHidden()
        if #available(iOS 16.0, *) { host.setNeedsUpdateOfSupportedInterfaceOrientations() }
    }

    // While shrinking/growing, the full-size player is cropped to the picture's 16:9 band (the black above and below a
    // portrait player would otherwise make a tall card that snaps to 16:9 at the end).
    private let cropMask = UIView()
    private func pictureBand(_ size: CGSize) -> CGRect {
        // The picture's real shape (2.35:1 films, 16:9 TV…), as VLC draws it fitted to the width; 16:9 until known.
        let v = player.videoSize
        let aspect = v.width > 0 && v.height > 0 ? v.width / v.height : 16 / 9
        let h = min(size.height, size.width / aspect)
        return CGRect(x: 0, y: (size.height - h) / 2, width: size.width, height: h)
    }
    /// 0 = whole player, 1 = just the picture band (with the card's rounded corners at scale k).
    private func setCrop(_ f: CGFloat, cornerOnScreen: CGFloat = 12, scale k: CGFloat = 1) {
        let full = CGRect(origin: .zero, size: view.bounds.size), band = pictureBand(view.bounds.size)
        cropMask.backgroundColor = .black
        cropMask.frame = CGRect(x: 0, y: full.minY + (band.minY - full.minY) * f, width: full.width, height: full.height + (band.height - full.height) * f)
        cropMask.layer.cornerRadius = cornerOnScreen / max(k, 0.01) * f
        if view.mask !== cropMask { view.mask = cropMask }
    }
    private func clearCrop() { view.mask = nil; view.layer.cornerRadius = 0 }

    private func miniFrame(in hostView: UIView) -> CGRect {
        let b = hostView.bounds, safe = hostView.safeAreaInsets
        let band = pictureBand(view.bounds.size)
        let w = (min(b.width, b.height) * 0.52).rounded(), h = (w * band.height / max(band.width, 1)).rounded()
        return CGRect(x: b.width - w - 12 - safe.right, y: b.height - safe.bottom - h - 76, width: w, height: h)   // (above the app's tab bar)
    }

    // The mini player is the full-size player drawn scaled down onto the card and cropped to its picture: the video is
    // never resized (resizing VLC's view made it go black for a moment), only transformed.
    private func miniTransform(in host: UIView) -> (CGAffineTransform, CGFloat) {
        let full = view.bounds.size, band = pictureBand(full), target = miniFrame(in: host)
        let k = target.width / band.width
        // Scale about the view's centre, then move the picture band's centre onto the card's centre.
        let bandOffset = (band.midY - full.height / 2) * k
        let t = CGAffineTransform(translationX: target.midX - full.width / 2, y: target.midY - full.height / 2 - bandOffset).scaledBy(x: k, y: k)
        return (t, k)
    }

    private func placeMiniClose(scale k: CGFloat) {
        let band = pictureBand(view.bounds.size), size = 26 / k, inset = 6 / k
        NSLayoutConstraint.deactivate(miniClose.constraints + view.constraints.filter { $0.firstItem === miniClose || $0.secondItem === miniClose })
        miniClose.translatesAutoresizingMaskIntoConstraints = true
        miniClose.frame = CGRect(x: band.maxX - size - inset, y: band.minY + inset, width: size, height: size)
        miniClose.layer.cornerRadius = size / 2
        miniClose.setImage(UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 12 / k, weight: .bold)), for: .normal)
    }

    /// Keeps the mini card in its corner when the screen turns or the app's layout changes.
    func layoutInParent() {
        guard let host = parent, isMini, drag == .none, touchStart == nil else { return }
        // A scaled view must not be resized while scaled: unscale, take the new screen size, then shrink onto the card again.
        if view.bounds.size != host.view.bounds.size {
            view.transform = .identity
            view.frame = host.view.bounds
            view.layoutIfNeeded()
        }
        let (t, k) = miniTransform(in: host.view)
        view.transform = t
        setCrop(1, scale: k)
        placeMiniClose(scale: k)
        root?.touchArea = pictureBand(view.bounds.size)
    }

    func minimize() {
        guard let host = parent, !isMini, !locked else { springBack(); return }
        closeGuide()
        view.autoresizingMask = []   // sized by layoutInParent while it is the card (never by autoresizing while scaled)
        isMini = true
        hideOverlay(); hud.alpha = 0; volumePill.alpha = 0
        updateCardVisibility()
        // Watching sideways: the app comes back upright with the card in its corner (the card follows the turn).
        landscapeBeforeMini = forcedLandscape
        if forcedLandscape { setForcedLandscape(false) }
        let (t, k) = miniTransform(in: host.view)
        placeMiniClose(scale: k)
        miniClose.alpha = 0; miniClose.isHidden = false
        view.bringSubviewToFront(miniClose)
        root?.touchArea = pictureBand(view.bounds.size)
        UIView.animate(withDuration: 0.45, delay: 0, usingSpringWithDamping: 0.9, initialSpringVelocity: 0.2, options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.view.transform = t
            self.setCrop(1, scale: k)
        } completion: { _ in
            guard self.isMini else { return }
            UIView.animate(withDuration: 0.15) { self.miniClose.alpha = 1 }
        }
        hostAppearanceChanged()
    }

    func expand() {
        guard parent != nil, isMini else { return }
        isMini = false
        root?.touchArea = nil
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        if landscapeBeforeMini { landscapeBeforeMini = false; setForcedLandscape(true) }   // back to how you were watching
        UIView.animate(withDuration: 0.1) { self.miniClose.alpha = 0 } completion: { _ in if !self.isMini { self.miniClose.isHidden = true } }
        UIView.animate(withDuration: 0.45, delay: 0, usingSpringWithDamping: 0.9, initialSpringVelocity: 0.2, options: [.beginFromCurrentState]) {
            self.view.transform = .identity
            self.setCrop(0)
        } completion: { _ in
            if !self.isMini { self.clearCrop() }
        }
        hostAppearanceChanged()
        showOverlay()
        updateCardVisibility()
    }

    private func springBack() {
        UIView.animate(withDuration: 0.35, delay: 0, usingSpringWithDamping: 0.85, initialSpringVelocity: 0.3, options: [.beginFromCurrentState]) {
            self.view.transform = .identity
            self.setCrop(0)
        } completion: { _ in if !self.isMini { self.clearCrop() } }
    }

    private func miniMoved(_ touches: Set<UITouch>) {
        guard let t = touches.first, let start = touchStart, let host = parent else { return }
        let dy = t.location(in: host.view).y - start.point.y
        let base = miniTransform(in: host.view).0
        view.transform = base.concatenating(CGAffineTransform(translationX: 0, y: dy > 0 ? dy : dy * 0.4))   // follows the finger down; resists going up
        view.alpha = dy > 0 ? max(0.35, 1 - dy / 220) : 1
    }

    private func miniEnded(_ touches: Set<UITouch>) {
        defer { touchStart = nil }
        guard let t = touches.first, let start = touchStart, let host = parent else { return }
        let dy = t.location(in: host.view).y - start.point.y, dx = t.location(in: host.view).x - start.point.x
        if dy > 50 { finish(); return }                                  // swiped down: stop
        let base = miniTransform(in: host.view).0
        UIView.animate(withDuration: 0.25) { self.view.transform = base; self.view.alpha = 1 }
        if dy < -40 || (abs(dx) < 12 && abs(dy) < 12) { expand() }       // swiped up or tapped: full screen
    }

    @objc private func miniCloseTapped() { finish() }

    private func setUpMiniClose() {
        miniClose.setImage(UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 12, weight: .bold)), for: .normal)
        miniClose.tintColor = .white
        miniClose.backgroundColor = UIColor(white: 0, alpha: 0.55)
        miniClose.layer.cornerRadius = 13
        miniClose.isHidden = true
        miniClose.translatesAutoresizingMaskIntoConstraints = false
        miniClose.addTarget(self, action: #selector(miniCloseTapped), for: .touchUpInside)
        view.addSubview(miniClose)
        NSLayoutConstraint.activate([
            miniClose.topAnchor.constraint(equalTo: view.topAnchor, constant: 6),
            miniClose.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            miniClose.widthAnchor.constraint(equalToConstant: 26),
            miniClose.heightAnchor.constraint(equalToConstant: 26),
        ])
    }

    @objc private func closeTapped() { finish() }

    /// The current programme's (or film's) description, from the guide / catalogue.
    @objc private func showInfo() {
        let text = [item.info, streamInfo()].filter { !$0.isEmpty }.joined(separator: "\n\n")
        let sheet = UIAlertController(title: item.subtitle.isEmpty || item.info.isEmpty ? item.title : item.subtitle, message: text, preferredStyle: .alert)
        sheet.addAction(UIAlertAction(title: "Close", style: .cancel))
        present(sheet, animated: true)
        showOverlay(keep: true)
    }
    /// What's actually playing, like VLC's media info: codec, size, frame rate, bit depth, audio format, bitrate.
    private func streamInfo() -> String {
        let tracks = (player.media?.tracksInformation as? [[String: Any]]) ?? []
        func codec(_ t: [String: Any], _ type: String) -> String {
            guard let n = t[VLCMediaTracksInformationCodec] as? NSNumber else { return "?" }
            let long = VLCMedia.codecName(forFourCC: n.uint32Value, trackType: type), u = long.uppercased()
            // VLC's names are long ("H264 - MPEG-4 AVC (part 10)"): the everyday name instead.
            let names: [(String, String)] = [("HEVC", "HEVC (H.265)"), ("H265", "HEVC (H.265)"), ("H264", "H.264"), ("AV1", "AV1"), ("VP9", "VP9"),
                ("MPEG-2", "MPEG-2"), ("MPEG-1/2 VIDEO", "MPEG-2"), ("E-AC-3", "Dolby Digital Plus"), ("EAC3", "Dolby Digital Plus"), ("A52", "Dolby Digital"),
                ("AC-3", "Dolby Digital"), ("TRUEHD", "Dolby TrueHD"), ("DTS", "DTS"), ("AAC", "AAC"), ("OPUS", "Opus"), ("FLAC", "FLAC"),
                ("MPEG AUDIO", "MP2 / MP3"), ("MP3", "MP3")]
            return names.first { u.contains($0.0) }?.1 ?? long
        }
        func num(_ t: [String: Any], _ k: String) -> Double { (t[k] as? NSNumber)?.doubleValue ?? 0 }
        var lines: [String] = []
        if let v = tracks.first(where: { ($0[VLCMediaTracksInformationType] as? String) == VLCMediaTracksInformationTypeVideo }) {
            let name = codec(v, VLCMediaTracksInformationTypeVideo)
            var bits = [name]
            let w = Int(num(v, VLCMediaTracksInformationVideoWidth)), h = Int(num(v, VLCMediaTracksInformationVideoHeight))
            let size = player.videoSize
            if w > 0 && h > 0 { bits.append("\(w)×\(h)") } else if size.width > 0 { bits.append("\(Int(size.width))×\(Int(size.height))") }
            let fr = num(v, VLCMediaTracksInformationFrameRate), den = num(v, VLCMediaTracksInformationFrameRateDenominator)
            if fr > 0 && den > 0 { bits.append(String(format: "%g fps", (fr / den * 100).rounded() / 100)) }
            if num(v, VLCMediaTracksInformationCodecProfile) == 2 && (name.contains("HEVC") || name.contains("265")) { bits.append("10-bit") }
            lines.append("Video: " + bits.joined(separator: " · "))
        }
        let audio = tracks.filter { ($0[VLCMediaTracksInformationType] as? String) == VLCMediaTracksInformationTypeAudio }
        if let a = audio.first(where: { Int32(num($0, VLCMediaTracksInformationId)) == player.currentAudioTrackIndex }) ?? audio.first {
            var bits = [codec(a, VLCMediaTracksInformationTypeAudio)]
            let ch = Int(num(a, VLCMediaTracksInformationAudioChannelsNumber))
            if ch > 0 { bits.append(ch == 1 ? "Mono" : ch == 2 ? "Stereo" : ch == 6 ? "5.1" : ch == 8 ? "7.1" : "\(ch) ch") }
            let rate = num(a, VLCMediaTracksInformationAudioRate)
            if rate > 0 { bits.append(String(format: "%g kHz", rate / 1000)) }
            if let lang = a[VLCMediaTracksInformationLanguage] as? String, !lang.isEmpty { bits.append(lang.uppercased()) }
            lines.append("Audio: " + bits.joined(separator: " · "))
        }
        if let first = samples.first, let last = samples.last, last.t - first.t >= 2, last.bytes > first.bytes {
            let mbit = Double(last.bytes - first.bytes) * 8 / (last.t - first.t) / 1_000_000
            lines.append(String(format: "Bitrate: %.1f Mbit/s", mbit))
        }
        if let stats = player.media?.statistics, stats.lostPictures > 0 { lines.append("Dropped frames: \(stats.lostPictures)") }
        let ad = player.currentAudioPlaybackDelay / 1000
        if ad != 0 { lines.append("Audio sync: \(ad > 0 ? "+" : "")\(ad) ms") }
        return lines.isEmpty ? "Stream details appear once it's playing." : lines.joined(separator: "\n")
    }

    // MARK: sync
    private func showSync(_ kind: Int) {
        syncKind = kind
        updateSyncLabel()
        syncPanel.isHidden = false
        showOverlay(keep: true)
    }
    private var syncMs: Int { (syncKind == 0 ? player.currentAudioPlaybackDelay : player.currentVideoSubTitleDelay) / 1000 }
    private func setSync(_ ms: Int) {
        if syncKind == 0 { player.currentAudioPlaybackDelay = ms * 1000 } else { player.currentVideoSubTitleDelay = ms * 1000 }
        updateSyncLabel()
    }
    private func updateSyncLabel() {
        let ms = syncMs
        syncLabel.text = "\(syncKind == 0 ? "Audio" : "Subtitles") \(ms > 0 ? "+" : "")\(ms) ms" + (ms == 0 ? "" : ms > 0 ? " later" : " earlier")
    }
    @objc private func syncMinus() { setSync(syncMs - 50) }
    @objc private func syncPlus() { setSync(syncMs + 50) }
    @objc private func syncReset() { setSync(0) }
    @objc private func syncDone() { syncPanel.isHidden = true; showOverlay() }

    // MARK: next episode
    private var hasNextEpisode: Bool { !item.live && item.hasNext && item.listTitle == "Episodes" }
    private var nextRow: Int { item.current - item.listStart + 1 }

    /// Every second: the card shows once the credits start (last 30 s; short videos only at the end) and goes
    /// again when you seek back out of them.
    private func checkCredits(_ t: Double, _ d: Double) {
        guard hasNextEpisode, !ended, !scrubbing, d > 0, t > 0 else { return }
        let window: Double = d >= 180 ? 30 : 0
        let inCredits = window > 0 && d - t <= window
        if inCredits {
            if cardMode == .none && !creditsDismissed { presentCard(seconds: 10) }
        } else {
            creditsDismissed = false
            if cardMode != .none { hideCard(animated: true) }
        }
    }

    private func presentCard(seconds: Double) {
        #if DEBUG
        NSLog("M3UDBG card present %.0f mini=%d chain=%d", seconds, isMini ? 1 : 0, autoChain)
        #endif
        let i = nextRow
        let card = item.listCard.indices.contains(i) && !item.listCard[i].isEmpty ? item.listCard[i]
            : item.list.indices.contains(i) ? item.list[i].title : "Next episode"
        let art = item.listArt.indices.contains(i) ? item.listArt[i] : ""
        if autoChain >= stillAfter {
            // Three in a row with nobody touching the phone: ask before playing more.
            cardMode = .still
            let show = item.show.isEmpty ? "" : " " + item.show
            nextCard.configure(kicker: "Up next: \(card)", title: "Still watching\(show)?", art: art, primary: "Continue", secondary: "Stop", countdown: false)
        } else {
            cardMode = .next
            nextCard.configure(kicker: "Next episode", title: card, art: art, primary: "Play next", secondary: ended ? "Close" : "Watch credits", countdown: true)
            countdownTotal = seconds
            countdownLeft = seconds
            nextCard.progress = 0
            countdown?.invalidate()
            let step = 0.05
            countdown = Timer.scheduledTimer(withTimeInterval: step, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                // Runs while the episode plays (or has ended) and the card can be seen or the player is the mini card;
                // a pause, or the controls covering the card, hold it.
                guard self.ended || self.player.isPlaying, self.isMini || self.locked || self.nextCard.alpha > 0.5 else { return }
                self.countdownLeft -= step
                self.nextCard.progress = CGFloat(1 - max(0, self.countdownLeft) / self.countdownTotal)
                if self.countdownLeft <= 0 { self.advance(auto: true) }
            }
        }
        nextCard.isHidden = false
        nextCard.transform = CGAffineTransform(translationX: 0, y: 18)
        // Sideways the controls would cover it: tuck them away (a tap brings them back, and the card steps aside).
        if view.bounds.width > view.bounds.height && overlay.alpha > 0.5 && !scrubbing && !isMini { hideOverlay() }
        updateCardVisibility()
    }

    /// The card is hidden while the player is the mini card, and sideways while the controls are up (they'd overlap).
    private func updateCardVisibility() {
        guard cardMode != .none else { return }
        let wide = view.bounds.width > view.bounds.height
        let show = !isMini && !locked && !(wide && overlay.alpha > 0.5 && overlay.isUserInteractionEnabled)
        if show { view.bringSubviewToFront(nextCard) }
        UIView.animate(withDuration: 0.35, delay: 0, usingSpringWithDamping: 0.9, initialSpringVelocity: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.nextCard.alpha = show ? 1 : 0
            self.nextCard.transform = show ? .identity : CGAffineTransform(translationX: 0, y: 18)
        }
    }

    private func hideCard(animated: Bool) {
        countdown?.invalidate()
        countdown = nil
        cardMode = .none
        let done = { self.nextCard.isHidden = self.cardMode == .none }
        if !animated { nextCard.alpha = 0; done(); return }
        UIView.animate(withDuration: 0.25, animations: {
            self.nextCard.alpha = 0
            self.nextCard.transform = CGAffineTransform(translationX: 0, y: 18)
        }) { _ in done() }
    }

    private func cardPrimary() {
        autoChain = 0
        advance(auto: false)
    }
    private func cardSecondary() {
        if cardMode == .still { hideCard(animated: true); finish(); return }   // Stop
        // Watch credits: carry on watching; if it then ends, a short card takes you on.
        hideCard(animated: true)
        creditsDismissed = true
        if ended { showOverlay(keep: true) }
    }
    /// On to the next episode, the one finishing counted as watched.
    private func advance(auto: Bool) {
        hideCard(animated: true)
        let (t, d) = position()
        if d > 0 { onEvent(["type": "progress", "t": max(t, d), "d": d]) }
        autoChain = auto ? autoChain + 1 : 0
        selfStepPending = true
        onEvent(["type": "step", "dir": 1])
    }

    // MARK: lock screen
    private func setUpRemoteControls() {
        let c = MPRemoteCommandCenter.shared()
        func on(_ cmd: MPRemoteCommand, _ f: @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus) {
            remoteTargets.append((cmd, cmd.addTarget { [weak self] e in self?.autoChain = 0; return f(e) }))   // a press = someone's there
        }
        on(c.playCommand) { [weak self] _ in self?.setPaused(false); return .success }
        on(c.pauseCommand) { [weak self] _ in self?.setPaused(true); return .success }
        on(c.togglePlayPauseCommand) { [weak self] _ in self?.playPause(); return .success }   // (uses isPlayingNow)
        on(c.nextTrackCommand) { [weak self] _ in self?.onEvent(["type": "step", "dir": 1]); return .success }
        on(c.previousTrackCommand) { [weak self] _ in self?.onEvent(["type": "step", "dir": -1]); return .success }
        c.skipForwardCommand.preferredIntervals = [30]
        c.skipBackwardCommand.preferredIntervals = [30]
        on(c.skipForwardCommand) { [weak self] _ in self?.player.jumpForward(30); return .success }
        on(c.skipBackwardCommand) { [weak self] _ in self?.player.jumpBackward(30); return .success }
        on(c.changePlaybackPositionCommand) { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.player.time = VLCTime(int: Int32(e.positionTime * 1000))
            return .success
        }
    }
    private func tearDownRemoteControls() {
        for (cmd, target) in remoteTargets { cmd.removeTarget(target) }
        remoteTargets = []
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
    private func updateNowPlaying() {
        guard !finished else { return }
        let (t, d) = position()
        var info: [String: Any] = [MPMediaItemPropertyTitle: item.title, MPMediaItemPropertyArtist: item.subtitle,
                                   MPNowPlayingInfoPropertyPlaybackRate: isPlayingNow ? 1.0 : 0.0, MPNowPlayingInfoPropertyIsLiveStream: item.live]
        if !item.live && d > 0 { info[MPMediaItemPropertyPlaybackDuration] = d; info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = t }
        if let a = artwork { info[MPMediaItemPropertyArtwork] = a }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
    private func loadArtwork(_ url: String) {
        artwork = nil
        guard !url.isEmpty, let u = URL(string: url) else { return }
        URLSession.shared.dataTask(with: u) { [weak self] data, _, _ in
            guard let data = data, let img = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self = self, self.item.art == url else { return }
                self.artwork = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
                self.updateNowPlaying()
            }
        }.resume()
    }

    /// The picture-size icon: a filled screen with a small panel cut into its lower-right corner (VLC-style).
    private static func aspectIcon() -> UIImage {
        let size = CGSize(width: 24, height: 20)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let c = ctx.cgContext
            UIColor.white.setFill()
            UIBezierPath(roundedRect: CGRect(x: 1, y: 2, width: 22, height: 16), cornerRadius: 2.5).fill()
            c.setBlendMode(.clear)   // cut the panel's outline out of the filled screen
            let panel = UIBezierPath(roundedRect: CGRect(x: 10.5, y: 8.5, width: 11, height: 8), cornerRadius: 1.2)
            panel.lineWidth = 1.6
            panel.stroke()
            UIBezierPath(rect: CGRect(x: 1, y: 6.4, width: 9.5, height: 1.5)).fill()   // the bar along the top-left, as in the original
        }.withRenderingMode(.alwaysTemplate)
    }

    @objc private func aspectTapped() {
        aspectMode = (aspectMode + 1) % Self.aspectModes.count
        applyAspect()
        statusLabel.text = Self.aspectModes[aspectMode]
        let shown = statusLabel.text
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in if self?.statusLabel.text == shown { self?.statusLabel.text = "" } }
        showOverlay()
    }
    /// Best fit: VLC's own. Fill screen: crop to the screen's shape (no bars, edges cut). Stretch: the picture is
    /// told it has the screen's shape (no bars, nothing cut, faces get wider). The rest force a film shape.
    private func applyAspect() {
        let b = view.bounds
        let screen = "\(Int(b.width.rounded())):\(Int(b.height.rounded()))"
        let (ratio, crop): (String?, String?) = {
            switch aspectMode {
            case 1: return (nil, screen)
            case 2: return (screen, nil)
            case 3: return ("16:9", nil)
            case 4: return ("4:3", nil)
            case 5: return ("235:100", nil)
            default: return (nil, nil)
            }
        }()
        setVLCString(ratio) { self.player.videoAspectRatio = $0 }
        setVLCString(crop) { self.player.videoCropGeometry = $0 }
    }
    /// VLC copies these strings when they're set, so a temporary C copy is freed straight after.
    private func setVLCString(_ value: String?, _ assign: (UnsafeMutablePointer<CChar>?) -> Void) {
        guard let value = value, let c = strdup(value) else { return assign(nil) }
        assign(c)
        free(c)
    }

    // Screen lock: hides every control and ignores taps and swipes (pocket, bed, kids); a touch shows one
    // unlock button for a few seconds.
    private var unlockTimer: Timer?
    @objc private func lockTapped() {
        locked = true
        hideOverlay()
        showUnlock()
    }
    private func showUnlock() {
        unlockTimer?.invalidate()
        UIView.animate(withDuration: 0.2) { self.unlockButton.alpha = 1 }
        unlockTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.25) { self?.unlockButton.alpha = 0 }
        }
    }
    @objc private func unlockTapped() {
        locked = false
        unlockTimer?.invalidate()
        unlockButton.alpha = 0
        showOverlay()
    }

    /// Episodes (or channels) list: pick one to play it here.
    @objc private func showList() {
        if item.live && !item.list.isEmpty { return openGuide() }
        let picker = EpisodeListController(title: item.listTitle, rows: item.list, current: item.current - item.listStart) { [weak self] row in
            guard let self = self else { return }
            self.onEvent(["type": "jump", "index": self.item.listStart + row])
        }
        let nav = UINavigationController(rootViewController: picker)
        nav.overrideUserInterfaceStyle = .dark
        if #available(iOS 15.0, *), let sheet = nav.sheetPresentationController { sheet.detents = [.medium(), .large()]; sheet.prefersGrabberVisible = true }
        present(nav, animated: true)
        showOverlay(keep: true)
    }
    @objc private func prevTapped() { if item.live { zap(next: false) } else { onEvent(["type": "step", "dir": -1]) }; showOverlay() }
    @objc private func nextTapped() { if item.live { zap(next: true) } else { onEvent(["type": "step", "dir": 1]) }; showOverlay() }

    @objc private func playPause() {
        #if DEBUG
        NSLog("M3UDBG playPause playing=%d", player.isPlaying ? 1 : 0)
        #endif
        if isPlayingNow { setPaused(true); showOverlay(keep: true) } else { setPaused(false); showOverlay() }
        setPlayIcon()
    }

    @objc private func jumpBack() { if item.live { return skip(-1) }; player.jumpBackward(30); showOverlay() }
    @objc private func jumpForward() { if item.live { return skip(1) }; player.jumpForward(30); showOverlay() }

    @objc private func scrubStart() { scrubbing = true; showOverlay(keep: true) }
    /// Live: the bar's position as a moment of TV.
    private func liveScrubTarget() -> Double? {
        guard let w = liveWindow() else { return nil }
        return w.start + Double(slider.value) * (w.end - w.start)
    }
    @objc private func scrubEnd() {
        if item.live {
            scrubbing = false
            if let t = liveScrubTarget() { seekLive(to: t) }
            showOverlay()
            return
        }
        // Seek by time (exact), not by VLC's 0-1 position, which jumps back to the start on some streamed files.
        let d = Double(player.media?.length.intValue ?? 0)
        if d > 0 { player.time = VLCTime(int: Int32(Double(slider.value) * d)) } else { player.position = slider.value }
        scrubbing = false
        showOverlay()
    }
    @objc private func scrubMoved() {
        if item.live {
            if let t = liveScrubTarget(), let w = liveWindow() {
                let behind = w.end - t
                elapsedLabel.text = "−" + clock(max(0, behind))
                setShown(elapsedLabel, true)
            }
            return
        }
        let d = Double(player.media?.length.intValue ?? 0) / 1000
        if d > 0 { elapsedLabel.text = clock(Double(slider.value) * d) }
    }

    @objc private func pickAudio() { pickTrack(title: "Audio", names: player.audioTrackNames, ids: player.audioTrackIndexes, current: player.currentAudioTrackIndex, source: audioButton, extra: ("Audio sync…", { [weak self] in self?.showSync(0) })) { [weak self] id in self?.player.currentAudioTrackIndex = id } }

    @objc private func pickSubtitles() { pickTrack(title: "Subtitles", names: player.videoSubTitlesNames, ids: player.videoSubTitlesIndexes, current: player.currentVideoSubTitleIndex, source: subsButton, extra: ("Subtitle sync…", { [weak self] in self?.showSync(1) })) { [weak self] id in self?.player.currentVideoSubTitleIndex = id } }

    private func pickTrack(title: String, names: [Any]?, ids: [Any]?, current: Int32, source: UIView, extra: (String, () -> Void)? = nil, choose: @escaping (Int32) -> Void) {
        let labels = (names ?? []).map { "\($0)" }
        let numbers = (ids ?? []).compactMap { ($0 as? NSNumber)?.int32Value }
        let sheet = UIAlertController(title: title, message: labels.isEmpty ? "None in this stream" : nil, preferredStyle: .actionSheet)
        for (i, label) in labels.enumerated() where i < numbers.count {
            let id = numbers[i]
            sheet.addAction(UIAlertAction(title: (id == current ? "✓ " : "") + label, style: .default) { _ in choose(id) })
        }
        if let (label, action) = extra { sheet.addAction(UIAlertAction(title: label, style: .default) { _ in action() }) }
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        sheet.popoverPresentationController?.sourceView = source
        sheet.popoverPresentationController?.sourceRect = source.bounds
        present(sheet, animated: true)
        showOverlay(keep: true)
    }
}

/// The list behind the player's list button: one row per episode / channel, the playing one ticked and in view.
final class EpisodeListController: UITableViewController {
    private let rows: [(title: String, detail: String)]
    private let current: Int
    private let onPick: (Int) -> Void

    init(title: String, rows: [(title: String, detail: String)], current: Int, onPick: @escaping (Int) -> Void) {
        self.rows = rows
        self.current = current
        self.onPick = onPick
        super.init(style: .plain)
        self.title = title
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .close, target: self, action: #selector(close))
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if rows.indices.contains(current) { tableView.scrollToRow(at: IndexPath(row: current, section: 0), at: .middle, animated: false) }
    }
    @objc private func close() { dismiss(animated: true) }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "row") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "row")
        let row = rows[indexPath.row]
        cell.textLabel?.text = row.title
        cell.textLabel?.font = .systemFont(ofSize: 16, weight: indexPath.row == current ? .semibold : .regular)
        cell.detailTextLabel?.text = row.detail
        cell.detailTextLabel?.textColor = .secondaryLabel
        cell.accessoryType = indexPath.row == current ? .checkmark : .none
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let row = indexPath.row
        dismiss(animated: true) { [onPick] in onPick(row) }
    }
}

/// The next-episode card: the next episode's still, a small label, its name, and two clear buttons. The primary one
/// fills up as the countdown runs. It keeps every touch to itself (no swipes or taps go through to the player).
final class NextEpisodeCard: UIView {
    var onPrimary: (() -> Void)?
    var onSecondary: (() -> Void)?
    var progress: CGFloat = 0 { didSet { primary.progress = progress } }
    private let thumb = UIImageView()
    private let kicker = UILabel()
    private let title = UILabel()
    private let primary = FillButton()
    private let secondary = UIButton(type: .custom)
    private var artURL = ""
    private static var images: [String: UIImage] = [:]
    static let accent = UIColor(red: 0.357, green: 0.576, blue: 1, alpha: 1)   // the app's blue (#5b93ff)

    init() {
        super.init(frame: .zero)
        // Dark, blurred backing so the card and its buttons read over bright and dark frames alike.
        layer.cornerRadius = 18
        layer.cornerCurve = .continuous
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.45
        layer.shadowRadius = 18
        layer.shadowOffset = CGSize(width: 0, height: 8)
        let back = UIVisualEffectView(effect: UIBlurEffect(style: .systemThickMaterialDark))
        back.backgroundColor = UIColor(white: 0.06, alpha: 0.55)
        back.layer.cornerRadius = 18
        back.layer.cornerCurve = .continuous
        back.layer.borderWidth = 1
        back.layer.borderColor = UIColor.white.withAlphaComponent(0.12).cgColor
        back.clipsToBounds = true
        back.translatesAutoresizingMaskIntoConstraints = false
        addSubview(back)

        thumb.contentMode = .scaleAspectFill
        thumb.clipsToBounds = true
        thumb.layer.cornerRadius = 10
        thumb.layer.cornerCurve = .continuous
        thumb.backgroundColor = UIColor(white: 1, alpha: 0.08)
        thumb.tintColor = UIColor(white: 1, alpha: 0.35)
        kicker.font = .systemFont(ofSize: 12, weight: .semibold)
        kicker.textColor = UIColor(white: 1, alpha: 0.7)
        title.font = .systemFont(ofSize: 17, weight: .semibold)
        title.textColor = .white
        title.numberOfLines = 2

        primary.backgroundColor = Self.accent
        primary.addTarget(self, action: #selector(primaryTapped), for: .touchUpInside)
        secondary.backgroundColor = UIColor(white: 1, alpha: 0.16)
        secondary.layer.borderWidth = 1
        secondary.layer.borderColor = UIColor(white: 1, alpha: 0.45).cgColor
        secondary.addTarget(self, action: #selector(secondaryTapped), for: .touchUpInside)
        for b in [primary, secondary] {
            b.layer.cornerRadius = 12
            b.layer.cornerCurve = .continuous
            b.clipsToBounds = true
            b.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
            b.titleLabel?.adjustsFontSizeToFitWidth = true
            b.titleLabel?.minimumScaleFactor = 0.8
            b.setTitleColor(.white, for: .normal)
            b.tintColor = .white
            b.heightAnchor.constraint(equalToConstant: 46).isActive = true
            b.addTarget(self, action: #selector(pressDown(_:)), for: [.touchDown, .touchDragEnter])
            b.addTarget(self, action: #selector(pressUp(_:)), for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
        }

        let text = UIStackView(arrangedSubviews: [kicker, title])
        text.axis = .vertical
        text.spacing = 3
        let top = UIStackView(arrangedSubviews: [thumb, text])
        top.spacing = 12
        top.alignment = .center
        let buttons = UIStackView(arrangedSubviews: [primary, secondary])
        buttons.spacing = 10
        buttons.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [top, buttons])
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            back.topAnchor.constraint(equalTo: topAnchor), back.bottomAnchor.constraint(equalTo: bottomAnchor),
            back.leadingAnchor.constraint(equalTo: leadingAnchor), back.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 14),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -14),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            thumb.widthAnchor.constraint(equalToConstant: 128),
            thumb.heightAnchor.constraint(equalTo: thumb.widthAnchor, multiplier: 9.0 / 16.0),
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func configure(kicker k: String, title t: String, art: String, primary p: String, secondary s: String, countdown: Bool) {
        kicker.text = k
        title.text = t
        primary.setTitle(" " + p, for: .normal)
        primary.setImage(UIImage(systemName: "play.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 14, weight: .bold)), for: .normal)
        primary.progress = 0
        primary.showsFill = countdown
        primary.accessibilityLabel = p
        secondary.setTitle(s, for: .normal)
        loadArt(art)
    }

    private func loadArt(_ url: String) {
        artURL = url
        if let img = Self.images[url] { thumb.image = img; thumb.contentMode = .scaleAspectFill; return }
        thumb.image = UIImage(systemName: "play.rectangle", withConfiguration: UIImage.SymbolConfiguration(pointSize: 22, weight: .regular))
        thumb.contentMode = .center
        guard !url.isEmpty, let u = URL(string: url) else { return }
        URLSession.shared.dataTask(with: u) { [weak self] data, _, _ in
            guard let data = data, let img = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                Self.images[url] = img
                guard let self = self, self.artURL == url else { return }
                UIView.transition(with: self.thumb, duration: 0.2, options: .transitionCrossDissolve) {
                    self.thumb.image = img
                    self.thumb.contentMode = .scaleAspectFill
                }
            }
        }.resume()
    }

    @objc private func primaryTapped() { onPrimary?() }
    @objc private func secondaryTapped() { onSecondary?() }
    @objc private func pressDown(_ b: UIButton) { UIView.animate(withDuration: 0.1) { b.transform = CGAffineTransform(scaleX: 0.96, y: 0.96); b.alpha = 0.85 } }
    @objc private func pressUp(_ b: UIButton) { UIView.animate(withDuration: 0.15) { b.transform = .identity; b.alpha = 1 } }

    // Touches on the card stay on the card: no swipe-to-minimise, seek or brightness from here.
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {}
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {}
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {}
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {}
}

/// A button with a lighter band sweeping across it: the countdown, without any numbers.
final class FillButton: UIButton {
    private let fill = UIView()
    var showsFill = true { didSet { fill.isHidden = !showsFill } }
    var progress: CGFloat = 0 { didSet { setNeedsLayout() } }
    override init(frame: CGRect) {
        super.init(frame: frame)
        fill.backgroundColor = UIColor(white: 1, alpha: 0.24)
        fill.isUserInteractionEnabled = false
        addSubview(fill)
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func layoutSubviews() {
        super.layoutSubviews()
        fill.frame = CGRect(x: 0, y: 0, width: bounds.width * min(1, max(0, progress)), height: bounds.height)
        sendSubviewToBack(fill)
    }
}

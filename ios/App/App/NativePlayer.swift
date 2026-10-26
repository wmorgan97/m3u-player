import UIKit
import AVKit
import Capacitor
import VLCKitSPM
import MediaPlayer

/// Hosts the web app and registers the app's own native plugin.
class MainViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(NativePlayerPlugin())
    }

    /// Test hook: launched with `-testStream <url>` (simulator / Xcode only), open the player straight on that
    /// stream so its controls can be checked without a playlist or a provider connection.
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-testStream"), i + 1 < args.count, let url = URL(string: args[i + 1]), presentedViewController == nil else { return }
        let live = args.contains("-testLive")
        var item = VLCPlayerController.Item(url: url, title: "Test stream", subtitle: "Player check", info: "", live: live, start: 0, headers: ["X-Test-Mute": "1"], hasPrev: true, hasNext: true)
        item.list = (1...12).map { ("Episode \($0)", "S1 E\($0)") }
        item.listTitle = "Episodes"
        item.current = 2
        let controller = VLCPlayerController(item: item) { event in print("[player] \(event)") }
        controller.modalPresentationStyle = .fullScreen
        present(controller, animated: false)
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
}

/// Native features the web app calls through `Capacitor.nativePromise('NativePlayer', …)`:
/// - vlc: the app's own full-screen player on VLC's engine. Plays everything an IPTV provider
///   serves (.ts, .mkv, HEVC, 10-bit, DTS/AC3, multiple audio tracks, subtitles) straight from the
///   provider, with channel up/down, seeking, and progress reported back to the web app.
/// - close: closes that player.
/// - play: the full-screen system player (AVPlayer), kept for HLS streams that want AirPlay / PiP.
/// - share: the iOS share sheet for exported playlists and backups.
@objc(NativePlayerPlugin)
public class NativePlayerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativePlayerPlugin"
    public let jsName = "NativePlayer"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "play", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "vlc", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "close", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "share", returnType: CAPPluginReturnPromise),
    ]

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
        DispatchQueue.main.async {
            // Already open (channel up/down, next episode): swap the stream in place.
            if let open = self.vlcController, open.presentingViewController != nil {
                open.load(item)
                call.resolve()
                return
            }
            guard let presenter = self.bridge?.viewController else {
                call.reject("The app isn't ready")
                return
            }
            let controller = VLCPlayerController(item: item) { [weak self] event in self?.emit(event) }
            controller.modalPresentationStyle = .fullScreen
            self.vlcController = controller
            presenter.present(controller, animated: true)
            call.resolve()
        }
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
    private let spacer = UIView()   // takes the spare width when the slider is hidden (live), so "LIVE" stays small
    // Big centre read-out for swipes (brightness, volume, seek): visible even while the controls are hidden.
    private let hud = UILabel()
    private var hudTimer: Timer?
    // Audio / subtitle sync, VLC-style: nudge in 50 ms steps.
    private let syncPanel = UIStackView()
    private let syncLabel = UILabel()
    private var syncKind = 0   // 0 audio, 1 subtitles
    // "Next episode in 10" at the end of an episode.
    private let nextPanel = UIView()
    private let nextName = UILabel()
    private let nextCount = UILabel()
    private var countdown: Timer?
    private var countdownLeft = 0
    // Swipes on films and episodes: left half brightness, right half volume, sideways to seek.
    private enum Drag { case none, scrub, brightness, volume }
    private var drag = Drag.none
    private var dragFrom: Double = 0
    private var scrubTarget: Double = 0
    private let volumeView = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 10, height: 10))
    private var volumeSlider: UISlider? { volumeView.subviews.compactMap { $0 as? UISlider }.first }
    private var startBrightness: CGFloat = 1
    // Lock screen / Control Center / headphone buttons.
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private var artwork: MPMediaItemArtwork?
    private var deinterlaceSet = false
    // Bytes read, for the stream info panel's bitrate.
    private var samples: [(t: TimeInterval, bytes: Int64)] = []

    init(item: Item, onEvent: @escaping ([String: Any]) -> Void) {
        self.item = item
        self.onEvent = onEvent
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    // Rotate button: forces landscape even with the phone's rotation lock on (like VLC), and back to portrait.
    private var forcedLandscape = false
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { forcedLandscape ? .landscape : .allButUpsideDown }
    @objc private func rotateTapped() {
        forcedLandscape.toggle()
        if #available(iOS 16.0, *) {
            let mask: UIInterfaceOrientationMask = forcedLandscape ? .landscapeRight : .portrait
            setNeedsUpdateOfSupportedInterfaceOrientations()
            view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
            if !forcedLandscape {   // back to following the phone (portrait while rotation lock is on)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.setNeedsUpdateOfSupportedInterfaceOrientations() }
            }
        } else {
            UIDevice.current.setValue((forcedLandscape ? UIInterfaceOrientation.landscapeRight : .portrait).rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
        rotateButton.tintColor = forcedLandscape ? UIColor(red: 0.36, green: 0.58, blue: 1, alpha: 1) : .white
        showOverlay()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        videoView.frame = view.bounds
        videoView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        videoView.backgroundColor = .black
        view.addSubview(videoView)
        player.drawable = videoView
        player.delegate = self
        buildOverlay()

        // Touch: a single tap shows/hides the controls, a double tap on the left/right third skips 10s.
        // Taps that land on a button or the slider go to that control only (see gestureRecognizer(_:shouldReceive:)).
        videoView.isUserInteractionEnabled = false   // VLC's drawing view must never take the touches
        // Taps and swipes are read straight from the touches (touchesBegan/Ended below): gesture recognizers
        // never fired over VLC's video view, which is what made the player feel dead to the touch.
        startBrightness = UIScreen.main.brightness
        view.addSubview(volumeView)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        setUpRemoteControls()
        tickTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        load(item)
    }

    // MARK: touch
    private var touchStart: (point: CGPoint, time: TimeInterval)?
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if locked { showUnlock(); return super.touchesBegan(touches, with: event) }   // locked: a touch only offers the unlock button
        if let t = touches.first, !isOnControl(t) { touchStart = (t.location(in: view), ProcessInfo.processInfo.systemUptime) }
        super.touchesBegan(touches, with: event)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        super.touchesMoved(touches, with: event)
        guard !locked, !item.live, let t = touches.first, let start = touchStart else { return }
        let p = t.location(in: view), dx = p.x - start.point.x, dy = p.y - start.point.y
        let h = view.bounds.height, w = view.bounds.width
        if drag == .none {
            guard max(abs(dx), abs(dy)) > 14 else { return }
            if abs(dx) > abs(dy) { drag = .scrub; dragFrom = position().0; scrubTarget = dragFrom }
            else if start.point.y < 50 || start.point.y > h - 50 { touchStart = nil; return }   // the system's own edge swipes
            else if start.point.x < w / 2 { drag = .brightness; dragFrom = Double(UIScreen.main.brightness) }
            else { drag = .volume; dragFrom = Double(AVAudioSession.sharedInstance().outputVolume) }
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
            showHud("Volume  \(Int(v * 100))%", sticky: true)
        case .none: break
        }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        defer { touchStart = nil; super.touchesEnded(touches, with: event) }
        if drag != .none {
            if drag == .scrub { player.time = VLCTime(int: Int32(scrubTarget * 1000)) }
            drag = .none
            showHud(hud.text ?? "", sticky: false)
            return
        }
        guard let t = touches.first, let start = touchStart else { return }
        let p = t.location(in: view), dx = p.x - start.point.x, dy = p.y - start.point.y
        if abs(dy) > 60 && abs(dy) > abs(dx) * 1.5 { return swipe(up: dy < 0) }          // vertical swipe: channel up/down
        if abs(dx) < 12 && abs(dy) < 12 && ProcessInfo.processInfo.systemUptime - start.time < 0.6 { tapped(at: p) }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if drag != .none { drag = .none; showHud(hud.text ?? "", sticky: false) }
        touchStart = nil
        super.touchesCancelled(touches, with: event)
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
        if aspectMode == 1 || aspectMode == 2 { applyAspect() }   // fill / stretch follow the screen's shape when it turns
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
        listButton.accessibilityLabel = next.listTitle
        infoButton.isHidden = false   // stream info is always there
        countdown?.invalidate()
        nextPanel.isHidden = true
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
        prevButton.alpha = next.hasPrev ? 1 : 0.35
        nextButton.alpha = next.hasNext ? 1 : 0.35

        let media = VLCMedia(url: next.url)
        media.addOption(next.live ? ":network-caching=1500" : ":network-caching=3000")
        if let agent = next.headers["User-Agent"] { media.addOption(":http-user-agent=\(agent)") }
        if let referrer = next.headers["Referer"] { media.addOption(":http-referrer=\(referrer)") }
        if !next.live && next.start > 1 { media.addOption(":start-time=\(Int(next.start))") }
        if next.headers["X-Test-Mute"] != nil { media.addOption(":no-audio") }   // test hook only: silent
        spinner.startAnimating()
        player.media = media
        player.play()
        showOverlay()
        updateNowPlaying()
    }

    func finish() {
        guard !finished else { return }
        finished = true
        let (t, d) = position()
        onEvent(["type": "closed", "t": t, "d": d])
        tickTimer?.invalidate()
        hideTimer?.invalidate()
        countdown?.invalidate()
        tearDownRemoteControls()
        if UIScreen.main.brightness != startBrightness { UIScreen.main.brightness = startBrightness }   // the phone's own brightness back
        player.stop()
        dismiss(animated: true)
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
        if !item.live && !scrubbing && d > 0 { slider.value = Float(t / d) }
        if !item.live && !scrubbing { elapsedLabel.text = clock(t); timeLabel.text = clock(d) }
        updateQuality()
        playButton.setImage(UIImage(systemName: player.isPlaying ? "pause.fill" : "play.fill"), for: .normal)
        ticks += 1
        if !item.live && d > 0 && ticks % 5 == 0 { onEvent(["type": "progress", "t": t, "d": d]) }
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

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        switch player.state {
        case .opening, .buffering:
            if !player.isPlaying { spinner.startAnimating() }
        case .playing:
            applyAspect()
            // Interlaced broadcasts (most live sport, many UK channels) get VLC's smooth 50fps deinterlacer; progressive
            // pictures are left alone (auto only acts on interlaced frames).
            if !deinterlaceSet { deinterlaceSet = true; player.setDeinterlace(.auto, withFilter: item.live ? "yadif2x" : "yadif") }
            spinner.stopAnimating()
            statusLabel.text = ""
            updateNowPlaying()
        case .paused:
            updateNowPlaying()
        case .error:
            spinner.stopAnimating()
            statusLabel.text = "This stream couldn't be played"
            showOverlay(keep: true)
            onEvent(["type": "error", "message": "This stream couldn't be played"])
        case .ended:
            let (t, d) = position()
            onEvent(["type": "progress", "t": d > 0 ? d : t, "d": d])
            onEvent(["type": "ended"])
            if !item.live && item.hasNext && item.listTitle == "Episodes" { startCountdown() } else { showOverlay(keep: true) }
        default:
            break
        }
    }

    // MARK: controls

    private func buildOverlay() {
        overlay.frame = view.bounds
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        gradient.colors = [UIColor.black.withAlphaComponent(0.7).cgColor, UIColor.clear.cgColor, UIColor.clear.cgColor, UIColor.black.withAlphaComponent(0.75).cgColor]
        gradient.locations = [0, 0.3, 0.6, 1]
        overlay.layer.addSublayer(gradient)
        view.addSubview(overlay)

        spinner.color = .white
        spinner.hidesWhenStopped = true
        spinner.isUserInteractionEnabled = false   // it sits over the play button: never block taps
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)

        func style(_ b: UIButton, _ symbol: String, _ size: CGFloat = 22) {
            b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .semibold)), for: .normal)
            b.tintColor = .white
            b.translatesAutoresizingMaskIntoConstraints = false
            b.widthAnchor.constraint(equalToConstant: size + 24).isActive = true
            b.heightAnchor.constraint(equalToConstant: size + 24).isActive = true
        }
        style(closeButton, "xmark")
        style(rotateButton, UIImage(systemName: "rectangle.landscape.rotate") != nil ? "rectangle.landscape.rotate" : "rotate.right")
        rotateButton.accessibilityLabel = "Rotate"
        rotateButton.addTarget(self, action: #selector(rotateTapped), for: .touchUpInside)
        style(audioButton, "speaker.wave.2")
        style(subsButton, "captions.bubble")
        style(infoButton, "info.circle")
        style(playButton, "pause.fill", 34)
        style(backButton, "gobackward.30", 26)
        style(forwardButton, "goforward.30", 26)
        style(prevButton, "backward.end.fill", 24)
        style(nextButton, "forward.end.fill", 24)
        style(listButton, "list.bullet")
        style(aspectButton, "aspectratio", 20)
        aspectButton.setImage(Self.aspectIcon(), for: .normal)
        aspectButton.accessibilityLabel = "Picture size"
        aspectButton.addTarget(self, action: #selector(aspectTapped), for: .touchUpInside)
        style(lockButton, "lock", 20)
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
        let middle = UIStackView(arrangedSubviews: [prevButton, backButton, playButton, forwardButton, nextButton])
        middle.alignment = .center
        middle.spacing = 22
        spacer.setContentHuggingPriority(.init(1), for: .horizontal)
        let bottom = UIStackView(arrangedSubviews: [liveBadge, elapsedLabel, slider, timeLabel, spacer])
        bottom.alignment = .center
        bottom.spacing = 12
        // The slider takes all the spare width (it collapsed to its thumb before); labels keep their size.
        slider.setContentHuggingPriority(.defaultLow - 1, for: .horizontal)
        liveBadge.setContentHuggingPriority(.required, for: .horizontal)
        slider.heightAnchor.constraint(equalToConstant: 34).isActive = true
        for v in [top, middle, bottom, statusLabel] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            overlay.addSubview(v)
        }
        // Tools row just above the bar, like VLC-style players: picture size left, lock centre, list right.
        for b in [aspectButton, lockButton, listButton] { overlay.addSubview(b) }
        NSLayoutConstraint.activate([
            aspectButton.leadingAnchor.constraint(equalTo: overlay.safeAreaLayoutGuide.leadingAnchor, constant: 6),
            lockButton.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            listButton.trailingAnchor.constraint(equalTo: overlay.safeAreaLayoutGuide.trailingAnchor, constant: -6),
        ] + [aspectButton, lockButton, listButton].map { $0.bottomAnchor.constraint(equalTo: bottom.topAnchor, constant: 2) })
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

        nextPanel.backgroundColor = UIColor(white: 0.1, alpha: 0.92)
        nextPanel.layer.cornerRadius = 14
        nextPanel.isHidden = true
        nextPanel.translatesAutoresizingMaskIntoConstraints = false
        nextCount.font = .systemFont(ofSize: 13, weight: .medium)
        nextCount.textColor = UIColor.white.withAlphaComponent(0.7)
        nextName.font = .systemFont(ofSize: 16, weight: .semibold)
        nextName.textColor = .white
        let playNow = UIButton(type: .system)
        playNow.setTitle("Play now", for: .normal)
        playNow.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        playNow.setTitleColor(.black, for: .normal)
        playNow.backgroundColor = .white
        playNow.layer.cornerRadius = 8
        playNow.heightAnchor.constraint(equalToConstant: 38).isActive = true
        playNow.addTarget(self, action: #selector(playNextNow), for: .touchUpInside)
        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel", for: .normal)
        cancel.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
        cancel.tintColor = .white
        cancel.heightAnchor.constraint(equalToConstant: 38).isActive = true
        cancel.addTarget(self, action: #selector(cancelNext), for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [playNow, cancel])
        buttons.distribution = .fillEqually
        buttons.spacing = 8
        let nextStack = UIStackView(arrangedSubviews: [nextCount, nextName, buttons])
        nextStack.axis = .vertical
        nextStack.spacing = 6
        nextStack.setCustomSpacing(12, after: nextName)
        nextStack.translatesAutoresizingMaskIntoConstraints = false
        nextPanel.addSubview(nextStack)
        view.addSubview(nextPanel)
        NSLayoutConstraint.activate([
            hud.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hud.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -90),
            hud.heightAnchor.constraint(equalToConstant: 48),
            hud.widthAnchor.constraint(greaterThanOrEqualToConstant: 150),
            syncPanel.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            syncPanel.bottomAnchor.constraint(equalTo: lockButton.topAnchor, constant: -10),
            nextPanel.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            nextPanel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -110),
            nextPanel.widthAnchor.constraint(equalToConstant: 260),
            nextStack.topAnchor.constraint(equalTo: nextPanel.topAnchor, constant: 14),
            nextStack.bottomAnchor.constraint(equalTo: nextPanel.bottomAnchor, constant: -14),
            nextStack.leadingAnchor.constraint(equalTo: nextPanel.leadingAnchor, constant: 14),
            nextStack.trailingAnchor.constraint(equalTo: nextPanel.trailingAnchor, constant: -14),
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
            bottom.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -12),

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
        overlay.isUserInteractionEnabled = true
        UIView.animate(withDuration: 0.2) { self.overlay.alpha = 1 }
        if !keep {
            hideTimer = Timer.scheduledTimer(withTimeInterval: item.headers["X-Test-Mute"] != nil ? 30 : 4, repeats: false) { [weak self] _ in
                guard let self = self, self.player.isPlaying, !self.scrubbing else { return }
                self.hideOverlay()
            }
        }
    }

    private func hideOverlay() {
        hideTimer?.invalidate()
        overlay.isUserInteractionEnabled = false   // hidden controls can't be pressed by accident
        UIView.animate(withDuration: 0.25) { self.overlay.alpha = 0 }
    }

    private var lastTap: (time: TimeInterval, x: CGFloat) = (0, 0)
    private func tapped(at point: CGPoint) {
        #if DEBUG
        NSLog("M3UDBG tap alpha=%.2f", overlay.alpha)
        #endif
        let now = ProcessInfo.processInfo.systemUptime, x = point.x, w = view.bounds.width
        let side = x < w / 3 ? -1 : x > w * 2 / 3 ? 1 : 0
        let quickSecond = now - lastTap.time < 0.3 && abs(x - lastTap.x) < 80
        lastTap = (now, x)
        if quickSecond && side != 0 && !item.live { return skip(side) }
        if overlay.alpha > 0.5 { hideOverlay() } else { showOverlay() }
    }

    private func skip(_ dir: Int) {
        if dir < 0 { player.jumpBackward(10); statusLabel.text = "« 10s" } else { player.jumpForward(10); statusLabel.text = "10s »" }
        showOverlay()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in if self?.statusLabel.text?.contains("10s") == true { self?.statusLabel.text = "" } }
    }


    private func swipe(up: Bool) {
        guard item.live else { return }
        if up, item.hasNext { onEvent(["type": "step", "dir": 1]) }
        if !up, item.hasPrev { onEvent(["type": "step", "dir": -1]) }
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
    private func startCountdown() {
        let i = item.current - item.listStart + 1
        nextName.text = item.list.indices.contains(i) ? item.list[i].title : "Next episode"
        countdownLeft = 10
        nextCount.text = "Next episode in 10"
        nextPanel.isHidden = false
        countdown?.invalidate()
        countdown = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.countdownLeft -= 1
            self.nextCount.text = "Next episode in \(self.countdownLeft)"
            if self.countdownLeft <= 0 { self.playNextNow() }
        }
    }
    @objc private func playNextNow() { countdown?.invalidate(); nextPanel.isHidden = true; onEvent(["type": "step", "dir": 1]) }
    @objc private func cancelNext() { countdown?.invalidate(); nextPanel.isHidden = true; showOverlay(keep: true) }

    // MARK: lock screen
    private func setUpRemoteControls() {
        let c = MPRemoteCommandCenter.shared()
        func on(_ cmd: MPRemoteCommand, _ f: @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus) { remoteTargets.append((cmd, cmd.addTarget(handler: f))) }
        on(c.playCommand) { [weak self] _ in self?.player.play(); return .success }
        on(c.pauseCommand) { [weak self] _ in self?.player.pause(); return .success }
        on(c.togglePlayPauseCommand) { [weak self] _ in self?.playPause(); return .success }
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
                                   MPNowPlayingInfoPropertyPlaybackRate: player.isPlaying ? 1.0 : 0.0, MPNowPlayingInfoPropertyIsLiveStream: item.live]
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
    @objc private func prevTapped() { onEvent(["type": "step", "dir": -1]); showOverlay() }
    @objc private func nextTapped() { onEvent(["type": "step", "dir": 1]); showOverlay() }

    @objc private func playPause() {
        #if DEBUG
        NSLog("M3UDBG playPause playing=%d", player.isPlaying ? 1 : 0)
        #endif
        if player.isPlaying { player.pause(); showOverlay(keep: true) } else { player.play(); showOverlay() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.updateNowPlaying() }
    }

    @objc private func jumpBack() { player.jumpBackward(30); showOverlay() }
    @objc private func jumpForward() { player.jumpForward(30); showOverlay() }

    @objc private func scrubStart() { scrubbing = true; showOverlay(keep: true) }
    @objc private func scrubEnd() {
        // Seek by time (exact), not by VLC's 0-1 position, which jumps back to the start on some streamed files.
        let d = Double(player.media?.length.intValue ?? 0)
        if d > 0 { player.time = VLCTime(int: Int32(Double(slider.value) * d)) } else { player.position = slider.value }
        scrubbing = false
        showOverlay()
    }
    @objc private func scrubMoved() {
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

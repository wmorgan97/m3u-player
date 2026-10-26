import UIKit
import AVKit
import Capacitor
import VLCKitSPM

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
        let item = VLCPlayerController.Item(url: url, title: "Test stream", subtitle: "Player check", info: "", live: live, start: 0, headers: ["X-Test-Mute": "1"], hasPrev: true, hasNext: true)
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
        let item = VLCPlayerController.Item(
            url: url, title: call.getString("title") ?? "", subtitle: call.getString("subtitle") ?? "",
            info: call.getString("info") ?? "",
            live: call.getBool("live") ?? true, start: call.getDouble("start") ?? 0, headers: headers,
            hasPrev: call.getBool("hasPrev") ?? false, hasNext: call.getBool("hasNext") ?? false)
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
    }

    private let player = VLCMediaPlayer()
    private let videoView = UIView()
    private let overlay = UIView()
    private let gradient = CAGradientLayer()
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let timeLabel = UILabel()
    private let statusLabel = UILabel()
    private let liveBadge = UILabel()
    private let slider = UISlider()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let closeButton = UIButton(type: .system)
    private let audioButton = UIButton(type: .system)
    private let subsButton = UIButton(type: .system)
    private let infoButton = UIButton(type: .system)
    private let playButton = UIButton(type: .system)
    private let backButton = UIButton(type: .system)
    private let forwardButton = UIButton(type: .system)
    private let prevButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)

    private var item: Item
    private let onEvent: ([String: Any]) -> Void
    private var tickTimer: Timer?
    private var hideTimer: Timer?
    private var ticks = 0
    private var lastTime: Int32 = -1
    private var scrubbing = false
    private var finished = false

    init(item: Item, onEvent: @escaping ([String: Any]) -> Void) {
        self.item = item
        self.onEvent = onEvent
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .allButUpsideDown }

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
        tickTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        load(item)
    }

    // MARK: touch
    private var touchStart: (point: CGPoint, time: TimeInterval)?
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let t = touches.first, !isOnControl(t) { touchStart = (t.location(in: view), ProcessInfo.processInfo.systemUptime) }
        super.touchesBegan(touches, with: event)
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        defer { touchStart = nil; super.touchesEnded(touches, with: event) }
        guard let t = touches.first, let start = touchStart else { return }
        let p = t.location(in: view), dx = p.x - start.point.x, dy = p.y - start.point.y
        if abs(dy) > 60 && abs(dy) > abs(dx) * 1.5 { return swipe(up: dy < 0) }          // vertical swipe: channel up/down
        if abs(dx) < 12 && abs(dy) < 12 && ProcessInfo.processInfo.systemUptime - start.time < 0.6 { tapped(at: p) }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { touchStart = nil; super.touchesCancelled(touches, with: event) }
    private func isOnControl(_ t: UITouch) -> Bool {
        guard overlay.alpha > 0.5 else { return false }
        var v: UIView? = t.view
        while let current = v, current !== view { if current is UIControl { return true }; v = current.superview }
        return false
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        gradient.frame = overlay.bounds
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
        backButton.isHidden = next.live
        forwardButton.isHidden = next.live
        timeLabel.isHidden = next.live
        infoButton.isHidden = next.info.isEmpty
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
    }

    func finish() {
        guard !finished else { return }
        finished = true
        let (t, d) = position()
        onEvent(["type": "closed", "t": t, "d": d])
        tickTimer?.invalidate()
        hideTimer?.invalidate()
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
        if !item.live { timeLabel.text = "\(clock(t)) / \(clock(d))" }
        playButton.setImage(UIImage(systemName: player.isPlaying ? "pause.fill" : "play.fill"), for: .normal)
        ticks += 1
        if !item.live && d > 0 && ticks % 5 == 0 { onEvent(["type": "progress", "t": t, "d": d]) }
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
            spinner.stopAnimating()
            statusLabel.text = ""
        case .error:
            spinner.stopAnimating()
            statusLabel.text = "This stream couldn't be played"
            showOverlay(keep: true)
            onEvent(["type": "error", "message": "This stream couldn't be played"])
        case .ended:
            let (t, d) = position()
            onEvent(["type": "progress", "t": d > 0 ? d : t, "d": d])
            onEvent(["type": "ended"])
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
        style(audioButton, "speaker.wave.2")
        style(subsButton, "captions.bubble")
        style(infoButton, "info.circle")
        style(playButton, "pause.fill", 34)
        style(backButton, "gobackward.30", 26)
        style(forwardButton, "goforward.30", 26)
        style(prevButton, "backward.end.fill", 24)
        style(nextButton, "forward.end.fill", 24)
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
        timeLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        timeLabel.textColor = .white
        liveBadge.text = "  LIVE  "
        liveBadge.font = .systemFont(ofSize: 11, weight: .bold)
        liveBadge.textColor = .white
        liveBadge.backgroundColor = UIColor(red: 0.9, green: 0.2, blue: 0.25, alpha: 1)
        liveBadge.layer.cornerRadius = 10
        liveBadge.clipsToBounds = true
        slider.minimumTrackTintColor = UIColor(red: 0.36, green: 0.58, blue: 1, alpha: 1)
        slider.maximumTrackTintColor = UIColor.white.withAlphaComponent(0.3)
        slider.addTarget(self, action: #selector(scrubStart), for: .touchDown)
        slider.addTarget(self, action: #selector(scrubMoved), for: .valueChanged)
        slider.addTarget(self, action: #selector(scrubEnd), for: [.touchUpInside, .touchUpOutside, .touchCancel])

        let titles = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel])
        titles.axis = .vertical
        titles.spacing = 2
        let top = UIStackView(arrangedSubviews: [titles, UIView(), infoButton, audioButton, subsButton, closeButton])
        top.alignment = .center
        top.spacing = 4
        let middle = UIStackView(arrangedSubviews: [prevButton, backButton, playButton, forwardButton, nextButton])
        middle.alignment = .center
        middle.spacing = 22
        let bottom = UIStackView(arrangedSubviews: [liveBadge, slider, timeLabel])
        bottom.alignment = .center
        bottom.spacing = 12
        // The slider takes all the spare width (it collapsed to its thumb before); labels keep their size.
        slider.setContentHuggingPriority(.defaultLow - 1, for: .horizontal)
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)
        timeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        liveBadge.setContentHuggingPriority(.required, for: .horizontal)
        slider.heightAnchor.constraint(equalToConstant: 34).isActive = true
        for v in [top, middle, bottom, statusLabel] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            overlay.addSubview(v)
        }
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
            liveBadge.heightAnchor.constraint(equalToConstant: 20),
            statusLabel.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            statusLabel.topAnchor.constraint(equalTo: middle.bottomAnchor, constant: 18),
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    private func showOverlay(keep: Bool = false) {
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
        let sheet = UIAlertController(title: item.subtitle.isEmpty ? item.title : item.subtitle, message: item.info, preferredStyle: .alert)
        sheet.addAction(UIAlertAction(title: "Close", style: .cancel))
        present(sheet, animated: true)
        showOverlay(keep: true)
    }
    @objc private func prevTapped() { onEvent(["type": "step", "dir": -1]); showOverlay() }
    @objc private func nextTapped() { onEvent(["type": "step", "dir": 1]); showOverlay() }

    @objc private func playPause() {
        #if DEBUG
        NSLog("M3UDBG playPause playing=%d", player.isPlaying ? 1 : 0)
        #endif
        if player.isPlaying { player.pause(); showOverlay(keep: true) } else { player.play(); showOverlay() }
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
        if d > 0 { timeLabel.text = "\(clock(Double(slider.value) * d)) / \(clock(d))" }
    }

    @objc private func pickAudio() { pickTrack(title: "Audio", names: player.audioTrackNames, ids: player.audioTrackIndexes, current: player.currentAudioTrackIndex, source: audioButton) { [weak self] id in self?.player.currentAudioTrackIndex = id } }

    @objc private func pickSubtitles() { pickTrack(title: "Subtitles", names: player.videoSubTitlesNames, ids: player.videoSubTitlesIndexes, current: player.currentVideoSubTitleIndex, source: subsButton) { [weak self] id in self?.player.currentVideoSubTitleIndex = id } }

    private func pickTrack(title: String, names: [Any]?, ids: [Any]?, current: Int32, source: UIView, choose: @escaping (Int32) -> Void) {
        let labels = (names ?? []).map { "\($0)" }
        let numbers = (ids ?? []).compactMap { ($0 as? NSNumber)?.int32Value }
        let sheet = UIAlertController(title: title, message: labels.isEmpty ? "None in this stream" : nil, preferredStyle: .actionSheet)
        for (i, label) in labels.enumerated() where i < numbers.count {
            let id = numbers[i]
            sheet.addAction(UIAlertAction(title: (id == current ? "✓ " : "") + label, style: .default) { _ in choose(id) })
        }
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        sheet.popoverPresentationController?.sourceView = source
        sheet.popoverPresentationController?.sourceRect = source.bounds
        present(sheet, animated: true)
        showOverlay(keep: true)
    }
}

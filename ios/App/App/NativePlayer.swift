import UIKit
import AVKit
import Capacitor

/// Hosts the web app and registers the app's own native plugin.
class MainViewController: CAPBridgeViewController {
    override func capacitorDidLoad() {
        bridge?.registerPluginInstance(NativePlayerPlugin())
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
}

/// Native features the web app calls through `Capacitor.nativePromise('NativePlayer', …)`:
/// - play: the full-screen system player, which handles http:// streams, custom
///   request headers (user agent / referrer), Picture in Picture and AirPlay.
/// - share: the iOS share sheet for exported playlists and backups.
@objc(NativePlayerPlugin)
public class NativePlayerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativePlayerPlugin"
    public let jsName = "NativePlayer"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "play", returnType: CAPPluginReturnPromise),
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

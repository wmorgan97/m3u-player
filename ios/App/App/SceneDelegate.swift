import UIKit
import Capacitor

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        window = UIWindow(windowScene: windowScene)
        window?.rootViewController = MainViewController()
        window?.makeKeyAndVisible()
        // Offline downloads (Downloads.swift): its own small plugin beside the player's.
        (window?.rootViewController as? CAPBridgeViewController)?.bridge?.registerPluginInstance(DownloadsPlugin())

        SceneDelegateProxy.shared.scene(scene, willConnectTo: session, options: connectionOptions)
        // Opened from a widget or the Live Activity while closed.
        for c in connectionOptions.urlContexts { _ = DeepLinks.open(c.url) }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        let mine = URLContexts.filter { DeepLinks.open($0.url) }
        SceneDelegateProxy.shared.scene(scene, openURLContexts: URLContexts.subtracting(mine))
    }

    // Scene-based app: UIApplicationDelegate's didBecomeActive isn't called, so live scores start (and look now) here.
    func sceneDidBecomeActive(_ scene: UIScene) {
        LiveScores.shared.start()
        LiveScores.shared.kick()
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        SceneDelegateProxy.shared.scene(scene, continue: userActivity)
    }
}

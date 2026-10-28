import UIKit
import AVFoundation
import Capacitor
import UserNotifications
import BackgroundTasks
import WebKit

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Play sound even with the silent switch on, and keep audio going in the background / PiP.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        UNUserNotificationCenter.current().delegate = self   // reminder notifications: shown in the app too, and tappable
        // New episodes of favourite series: iOS lends the app a little background time now and then (when and how often is
        // up to iOS, usually a few times a day), and the app checks the favourites then.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: AppDelegate.refreshTask, using: nil) { task in
            AppDelegate.handleRefresh(task as! BGAppRefreshTask)
        }
        return true
    }

    // A reminder arriving while the app is open: show it as a banner anyway.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    // Tapped: open on that channel.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let ch = (info["channel"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let se = (info["series"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if ch != nil || se != nil { ReminderTaps.tapped(channel: ch, series: se, webView: AppDelegate.webView) }
        completionHandler()
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Sent when the application is about to move from active to inactive state. This can occur for certain types of temporary interruptions (such as an incoming phone call or SMS message) or when the user quits the application and it begins the transition to the background state.
        // Use this method to pause ongoing tasks, disable timers, and invalidate graphics rendering callbacks. Games should use this method to pause the game.
    }

    static let refreshTask = "com.wmorgan97.m3uplayer.newepisodes"
    static var webView: WKWebView? {
        (UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.windows.first?.rootViewController as? CAPBridgeViewController }.first)?.webView
    }
    static func scheduleRefresh() {
        let r = BGAppRefreshTaskRequest(identifier: refreshTask)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 3 * 3600)
        try? BGTaskScheduler.shared.submit(r)
    }
    static func handleRefresh(_ task: BGAppRefreshTask) {
        scheduleRefresh()   // the next one
        var done = false
        let finish: (Bool) -> Void = { ok in DispatchQueue.main.async { if !done { done = true; task.setTaskCompleted(success: ok) } } }
        task.expirationHandler = { finish(false) }
        DispatchQueue.main.async {
            guard let web = webView else { finish(false); return }
            // The page checks the favourites (window.__bgCheck returns a promise); iOS allows ~30 s.
            web.callAsyncJavaScript("return await (window.__bgCheck ? window.__bgCheck() : 0)", arguments: [:], in: nil, in: .page) { _ in finish(true) }
        }
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        AppDelegate.scheduleRefresh()
        // Use this method to release shared resources, save user data, invalidate timers, and store enough application state information to restore your application to its current state in case it is terminated later.
        // If your application supports background execution, this method is called instead of applicationWillTerminate: when the user quits.
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // Called as part of the transition from the background to the active state; here you can undo many of the changes made on entering the background.
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Restart any tasks that were paused (or not yet started) while the application was inactive. If the application was previously in the background, optionally refresh the user interface.
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Called when the application is about to terminate. Save data if appropriate. See also applicationDidEnterBackground:.
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default Configuration",
                                          sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

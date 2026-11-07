import Foundation
import Capacitor
import WebKit
import UIKit

/// Films and episodes kept on the phone for watching offline. Xtream film / episode links are plain files, so they are
/// fetched as they are (no conversion) by a background URLSession into Application Support/Downloads (not backed up),
/// and play in the app's VLC player from there. One at a time: the provider allows one connection, so the rest wait
/// in a queue. Events go to `window.__downloads(event)` in the web app; the web app keeps the titles' details.
final class DownloadManager: NSObject, URLSessionDownloadDelegate {
    static let shared = DownloadManager()

    struct Entry: Codable {
        var id: String
        var url: String
        var ext: String
        var headers: [String: String]
        var meta: String            // the web app's own details (title, poster, kind…), handed back as is
        var state: String           // queued | downloading | paused | done | failed
        var bytes: Int64 = 0
        var total: Int64 = 0
        var error: String?
        var at: Double
        var fname: String? = nil    // the file's name in Files (the title); older downloads are "<id>.<ext>"
    }

    /// Called with each change ({ id, state, bytes, total, path?, error? }), on the main thread.
    var onEvent: (([String: Any]) -> Void)?
    /// iOS's completion for background events (AppDelegate hands it over); called once the session has caught up.
    var backgroundCompletion: (() -> Void)?

    private let queue = DispatchQueue(label: "m3u.downloads")
    private var entries: [String: Entry] = [:]
    private var tasks: [String: URLSessionDownloadTask] = [:]
    private var pausing = Set<String>()
    private var lastEmit: [String: TimeInterval] = [:]
    private var lastSave: TimeInterval = 0
    static let sessionId = "com.wmorgan97.m3uplayer.downloads"

    static let folder: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var u = base
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
        return base
    }()
    /// The films themselves live in Documents/Downloads, so they show in the Files app (On My iPhone > this app) and can be
    /// shared or copied out, and still play with no connection. Bookkeeping (manifest, resume data) stays private above.
    static let media: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: docs, withIntermediateDirectories: true)
        var u = docs
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
        return docs
    }()
    private var manifest: URL { Self.folder.appendingPathComponent("downloads.json") }
    private func file(_ e: Entry) -> URL { Self.media.appendingPathComponent(e.fname ?? "\(e.id).\(e.ext)") }
    /// "Oppenheimer (2023)" -> "Oppenheimer (2023).mp4" (no path characters; a clash gets the id added).
    private func friendlyName(id: String, ext: String, meta: String) -> String? {
        guard let d = meta.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let t = (o["title"] ?? o["name"]) as? String else { return nil }
        var base = t.components(separatedBy: CharacterSet(charactersIn: "/\\:*?\"<>|\n\r\t")).joined(separator: " ").trimmingCharacters(in: .whitespaces)
        if let y = o["year"], !"\(y)".isEmpty, "\(y)" != "<null>", !base.contains("\(y)") { base += " (\(y))" }
        if let sx = o["season"], let ex = o["episode"], !"\(sx)".isEmpty { base += " S\(sx)E\(ex)" }
        guard !base.isEmpty else { return nil }
        base = String(base.prefix(80))
        var name = "\(base).\(ext)"
        if entries.values.contains(where: { $0.id != id && $0.fname == name }) || FileManager.default.fileExists(atPath: Self.media.appendingPathComponent(name).path) { name = "\(base) [\(id.prefix(8))].\(ext)" }
        return name
    }
    private func resumeFile(_ id: String) -> URL { Self.folder.appendingPathComponent("\(id).resume") }

    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: Self.sessionId)
        c.isDiscretionary = false
        c.sessionSendsLaunchEvents = true
        c.httpMaximumConnectionsPerHost = 1
        c.timeoutIntervalForRequest = 60
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    private override init() {
        super.init()
        queue.sync {
            if let d = try? Data(contentsOf: manifest), let list = try? JSONDecoder().decode([Entry].self, from: d) {
                for e in list { entries[e.id] = e }
            }
        }
        // Downloads made before the move (Application Support) go to the visible folder.
        queue.sync {
            for e in entries.values where e.state == "done" {
                let old = Self.folder.appendingPathComponent("\(e.id).\(e.ext)")
                if FileManager.default.fileExists(atPath: old.path) { try? FileManager.default.moveItem(at: old, to: file(e)) }
            }
        }
        // Reattach to what iOS kept downloading while the app was closed; anything left "downloading" without a task
        // was cut off (the app was force-quit): it waits as paused (resuming where it can).
        session.getAllTasks { all in
            self.queue.async {
                for t in all { if let id = t.taskDescription, let dt = t as? URLSessionDownloadTask, self.entries[id] != nil { self.tasks[id] = dt } else { t.cancel() } }
                for (id, e) in self.entries where e.state == "downloading" && self.tasks[id] == nil {
                    self.entries[id]?.state = "paused"
                }
                self.save(force: true)
                self.pump()
            }
        }
    }

    // MARK: actions (any thread)

    func start(id: String, url: URL, ext: String, headers: [String: String], meta: String) {
        queue.async {
            if let old = self.entries[id], old.state == "done" { self.emit(old); return }
            let clean = ext.lowercased().filter { $0.isLetter || $0.isNumber }
            let ex = clean.isEmpty ? "mp4" : String(clean.prefix(5))
            self.entries[id] = Entry(id: id, url: url.absoluteString, ext: ex, headers: headers, meta: meta,
                                     state: "queued", at: Date().timeIntervalSince1970 * 1000, fname: self.friendlyName(id: id, ext: ex, meta: meta))
            self.save(force: true)
            self.emit(self.entries[id]!)
            self.pump()
        }
    }

    /// The saved file of a finished download.
    func fileURL(_ id: String) -> URL? { queue.sync { entries[id].flatMap { $0.state == "done" ? file($0) : nil } } }

    func pause(_ id: String) {
        queue.async {
            guard var e = self.entries[id], e.state == "downloading" || e.state == "queued" else { return }
            e.state = "paused"; self.entries[id] = e
            if let t = self.tasks[id] {
                self.pausing.insert(id)
                t.cancel(byProducingResumeData: { data in
                    self.queue.async {
                        if let data = data { try? data.write(to: self.resumeFile(id)) }
                        self.tasks[id] = nil
                        self.pausing.remove(id)
                        self.save(force: true)
                        self.pump()
                    }
                })
            }
            self.save(force: true)
            self.emit(e)
        }
    }

    func resume(_ id: String) {
        queue.async {
            guard var e = self.entries[id], e.state == "paused" || e.state == "failed" else { return }
            e.state = "queued"; e.error = nil; self.entries[id] = e
            self.save(force: true)
            self.emit(e)
            self.pump()
        }
    }

    func remove(_ id: String) {
        queue.async {
            guard let e = self.entries[id] else { return }
            self.tasks[id]?.cancel(); self.tasks[id] = nil
            try? FileManager.default.removeItem(at: self.file(e))
            try? FileManager.default.removeItem(at: self.resumeFile(id))
            self.entries[id] = nil
            self.save(force: true)
            self.pump()
        }
    }

    /// Everything known, plus the space used by downloads and still free on the phone.
    func list(_ done: @escaping ([String: Any]) -> Void) {
        queue.async {
            let items = self.entries.values.sorted { $0.at > $1.at }.map { self.json($0) }
            let used = self.entries.values.reduce(Int64(0)) { $0 + (($1.state == "done") ? $1.total : $1.bytes) }
            let free = (try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
            done(["items": items, "used": used, "free": free])
        }
    }

    // MARK: the queue (on `queue`)

    /// Starts the oldest waiting download when nothing is downloading.
    private func pump() {
        if entries.values.contains(where: { $0.state == "downloading" }) || !pausing.isEmpty { return }
        guard var e = entries.values.filter({ $0.state == "queued" }).sorted(by: { $0.at < $1.at }).first, let url = URL(string: e.url) else { return }
        let task: URLSessionDownloadTask
        if let data = try? Data(contentsOf: resumeFile(e.id)) {
            try? FileManager.default.removeItem(at: resumeFile(e.id))
            task = session.downloadTask(withResumeData: data)
        } else {
            var r = URLRequest(url: url)
            for (k, v) in e.headers { r.setValue(v, forHTTPHeaderField: k) }
            task = session.downloadTask(with: r)
            e.bytes = 0
        }
        task.taskDescription = e.id
        tasks[e.id] = task
        e.state = "downloading"; entries[e.id] = e
        save(force: true)
        emit(e)
        task.resume()
    }

    private func json(_ e: Entry) -> [String: Any] {
        var o: [String: Any] = ["id": e.id, "state": e.state, "bytes": e.bytes, "total": e.total, "meta": e.meta, "at": e.at]
        if e.state == "done" { o["path"] = file(e).absoluteString }
        if let err = e.error { o["error"] = err }
        return o
    }

    private func emit(_ e: Entry, throttle: Bool = false) {
        let now = ProcessInfo.processInfo.systemUptime
        if throttle, now - (lastEmit[e.id] ?? 0) < 0.5 { return }
        lastEmit[e.id] = now
        let o = json(e)
        DispatchQueue.main.async { self.onEvent?(o) }
    }

    private func save(force: Bool = false) {
        let now = ProcessInfo.processInfo.systemUptime
        if !force && now - lastSave < 5 { return }
        lastSave = now
        if let d = try? JSONEncoder().encode(Array(entries.values)) { try? d.write(to: manifest, options: .atomic) }
    }

    // MARK: URLSession (delegate calls arrive on the session's own queue)

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        queue.async {
            guard var e = self.entries[id], e.state == "downloading" else { return }
            e.bytes = totalBytesWritten
            if totalBytesExpectedToWrite > 0 { e.total = totalBytesExpectedToWrite }
            self.entries[id] = e
            self.save()
            self.emit(e, throttle: true)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        // The file must be moved before this returns; the bookkeeping follows on our queue.
        var moved: URL?
        var problem: String?
        if !(200..<300).contains(status) { problem = status == 401 || status == 403 ? "The provider refused it (\(status))" : status == 404 ? "Not found on the provider" : "The provider replied \(status)" }
        else if let e = queue.sync(execute: { entries[id] }) {
            let dest = file(e)
            try? FileManager.default.removeItem(at: dest)
            do { try FileManager.default.moveItem(at: location, to: dest); moved = dest } catch { problem = "Couldn't save the file" }
        }
        queue.async {
            guard var e = self.entries[id] else { if let m = moved { try? FileManager.default.removeItem(at: m) }; return }
            if let m = moved {
                let size = ((try? FileManager.default.attributesOfItem(atPath: m.path))?[.size] as? NSNumber)?.int64Value ?? e.bytes
                if size < 100_000 { try? FileManager.default.removeItem(at: m); e.state = "failed"; e.error = "The provider sent no video" }
                else { e.state = "done"; e.bytes = size; e.total = size; e.error = nil }
            } else { e.state = "failed"; e.error = problem }
            self.entries[id] = e
            self.tasks[id] = nil
            self.save(force: true)
            self.emit(e)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let id = task.taskDescription else { return }
        queue.async {
            if self.tasks[id] === task { self.tasks[id] = nil }
            guard var e = self.entries[id] else { self.pump(); return }
            if let error = error as NSError?, e.state == "downloading" {
                // Cut off (no network, the provider closed it): keep what came, so Resume carries on from there.
                if let data = error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data { try? data.write(to: self.resumeFile(id)) }
                if !(error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) {
                    e.state = "failed"
                    e.error = error.domain == NSURLErrorDomain && error.code == NSURLErrorNotConnectedToInternet ? "No connection" : "Connection lost"
                    self.entries[id] = e
                    self.emit(e)
                }
            }
            self.save(force: true)
            self.pump()
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { self.backgroundCompletion?(); self.backgroundCompletion = nil }
    }
}

/// The web app's way in: Downloads.start / pause / resume / remove / list (see js/app.js, "Offline downloads").
@objc(DownloadsPlugin)
public class DownloadsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "DownloadsPlugin"
    public let jsName = "Downloads"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pause", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "share", returnType: CAPPluginReturnPromise),
    ]

    override public func load() {
        DownloadManager.shared.onEvent = { [weak self] event in
            guard let data = try? JSONSerialization.data(withJSONObject: event), let json = String(data: data, encoding: .utf8) else { return }
            self?.bridge?.webView?.evaluateJavaScript("window.__downloads && window.__downloads(\(json))", completionHandler: nil)
        }
    }

    @objc func start(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let s = call.getString("url"), let url = URL(string: s), url.scheme?.hasPrefix("http") == true else { call.reject("Missing or invalid url"); return }
        var headers: [String: String] = [:]
        for (k, v) in call.getObject("headers") ?? [:] { if let v = v as? String { headers[k] = v } }
        DownloadManager.shared.start(id: id, url: url, ext: call.getString("ext") ?? "mp4", headers: headers, meta: call.getString("meta") ?? "{}")
        call.resolve()
    }
    @objc func pause(_ call: CAPPluginCall) { if let id = call.getString("id") { DownloadManager.shared.pause(id) }; call.resolve() }
    @objc func resume(_ call: CAPPluginCall) { if let id = call.getString("id") { DownloadManager.shared.resume(id) }; call.resolve() }
    @objc func remove(_ call: CAPPluginCall) { if let id = call.getString("id") { DownloadManager.shared.remove(id) }; call.resolve() }
    @objc func list(_ call: CAPPluginCall) { DownloadManager.shared.list { call.resolve($0) } }
    /// "Save to Files / Share": the system share sheet for a finished download (Save to Files, AirDrop, open in VLC...).
    @objc func share(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let url = DownloadManager.shared.fileURL(id) else { call.reject("Not downloaded yet"); return }
        DispatchQueue.main.async {
            let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let pop = vc.popoverPresentationController, let v = self.bridge?.viewController?.view { pop.sourceView = v; pop.sourceRect = CGRect(x: v.bounds.midX, y: v.bounds.midY, width: 1, height: 1); pop.permittedArrowDirections = [] }
            self.bridge?.viewController?.present(vc, animated: true)
            call.resolve()
        }
    }
}

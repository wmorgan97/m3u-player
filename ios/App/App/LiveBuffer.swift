import Foundation
import Network

/// Pause and rewind for live TV, without a second connection to the provider (it allows one).
///
/// The live channel is fetched here, once, and kept on disk in Caches (a ring: the last 60 minutes or 2 GB, whichever is
/// smaller, deleted when you leave the channel). VLC plays it from a tiny HTTP server on 127.0.0.1, as a "session": a
/// live feed that starts at some point in the recording and then runs at real time, exactly as the provider sent it.
/// Live is just a session at the newest data; rewinding or resuming is a new session further back.
///
/// Two kinds of channel:
/// - MPEG-TS (`.ts` / Xtream `/live/…`): one endless HTTP response, stored as it arrives; sessions start on a keyframe.
/// - HLS (`.m3u8`): the playlist is followed and each segment stored; sessions get their own sliding playlist.
/// Anything unusual (encrypted HLS, fMP4, separate audio renditions, a page that isn't video) is handed straight to VLC
/// (an HTTP redirect to the provider), so playback never depends on this working.
final class LiveBuffer: NSObject, URLSessionDataDelegate {
    enum Kind { case ts, hls }
    let id = UUID().uuidString.prefix(8).lowercased()
    let kind: Kind
    let source: URL
    private let headers: [String: String]
    private let q = DispatchQueue(label: "m3u.livebuffer")
    private let dir: URL
    private var session: URLSession!
    private var stopped = false
    /// Called (on the main queue) when the buffer gives up on the provider: the player shows its error / plays direct.
    var onFail: ((String) -> Void)?

    // Limits of the recording.
    static let maxAge: Double = 60 * 60
    private let maxBytes: Int64

    // What the player reads (any thread).
    private let lock = NSLock()
    private var _start: Double = 0      // oldest moment still recorded (wall clock, s)
    private var _edge: Double = 0       // newest
    private var _direct = false         // handed to VLC directly: no timeshift
    var window: (start: Double, edge: Double, direct: Bool) { lock.lock(); defer { lock.unlock() }; return (_start, _edge, _direct) }
    private func publish(start: Double, edge: Double) { lock.lock(); _start = start; _edge = edge; lock.unlock() }

    init(url: URL, headers: [String: String]) {
        source = url
        self.headers = headers
        let path = url.path.lowercased()
        kind = path.hasSuffix(".m3u8") || path.hasSuffix(".m3u") ? .hls : .ts
        dir = LiveBuffer.root.appendingPathComponent(String(id), isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let free = (try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage ?? 0
        maxBytes = max(200_000_000, min(2_000_000_000, free / 4))   // never more than a quarter of the free space
        super.init()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpMaximumConnectionsPerHost = 2
        cfg.timeoutIntervalForRequest = 15
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        var h = headers; h.removeValue(forKey: "X-Test-Mute")
        cfg.httpAdditionalHeaders = h
        let oq = OperationQueue(); oq.underlyingQueue = q; oq.maxConcurrentOperationCount = 1
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: oq)
    }

    static let root: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Timeshift", isDirectory: true)
    /// Leftovers from a crash or a killed app.
    static func purge() { try? FileManager.default.removeItem(at: root) }

    func start() {
        LocalStreamServer.shared.register(self)
        q.async { self.kind == .ts ? self.tsConnect() : self.hlsPoll() }
    }

    /// Stops fetching (closing the provider connection now: the next channel may need it) and deletes the recording.
    func stop() {
        LocalStreamServer.shared.unregister(self)
        q.async {
            guard !self.stopped else { return }
            self.stopped = true
            self.session.invalidateAndCancel()
            for s in self.sessions.values { s.conn?.cancel() }
            self.sessions = [:]
            for c in self.segConns { c.conn.cancel() }
            self.segConns = []
            try? self.tsWriter?.close()
            for h in self.readers.values { try? h.close() }
            self.readers = [:]
            try? FileManager.default.removeItem(at: self.dir)
        }
    }

    // MARK: sessions (what VLC plays)

    final class Session {
        let token: String
        var target: Double?           // nil = live
        var conn: NWConnection?
        var cursor: Int64 = -1        // TS: next byte to send
        var anchorContent: Double = 0 // TS: arrival time of the first byte sent; HLS: content start of the first segment
        var anchorWall: Double = 0    // when the session started running
        var paused: Double? = nil     // when it was paused
        var pausedTotal: Double = 0
        var firstSeq = -1             // HLS: the first segment of a delayed session
        var startedContent: Double?   // the content time the viewer starts at (reported to the player)
        var sending = false
        var headerSent = false
        init(token: String, target: Double?) { self.token = token; self.target = target }
    }
    private var sessions: [String: Session] = [:]

    /// A URL for VLC: live (target nil) or starting at `target` (wall-clock seconds of the recording).
    func sessionURL(target: Double?) -> URL? {
        guard let port = LocalStreamServer.shared.port else { return nil }
        let token = UUID().uuidString.prefix(6).lowercased()
        let s = Session(token: String(token), target: target)
        q.sync {
            // One viewer at a time: an older session's connection is done with.
            for old in sessions.values { old.conn?.cancel() }
            sessions = [s.token: s]
        }
        let path = kind == .ts ? "/b/\(id)/\(token).ts" : "/b/\(id)/\(token)/index.m3u8"
        return URL(string: "http://127.0.0.1:\(port)\(path)")
    }
    /// Where the current session started in the recording (wall clock), once it has (nil until then).
    func sessionStart() -> Double? { q.sync { sessions.values.first?.startedContent } }
    func pauseSession() { q.async { for s in self.sessions.values where s.paused == nil { s.paused = Date().timeIntervalSince1970 } } }
    func resumeSession() {
        q.async {
            let now = Date().timeIntervalSince1970
            for s in self.sessions.values { if let p = s.paused { s.pausedTotal += now - p; s.paused = nil } }
            self.wakeAll()
        }
    }

    // MARK: HTTP requests from VLC (on q)

    fileprivate func handle(path: [Substring], conn: NWConnection) {
        q.async {
            guard !self.stopped else { return LocalStreamServer.respond(conn, status: "404 Not Found") }
            if self.direct { return LocalStreamServer.redirect(conn, to: self.source) }
            if self.kind == .ts, path.count == 1, let s = self.sessions[String(path[0].dropLast(3))] {
                s.conn?.cancel(); s.conn = conn
                return self.tsBegin(s)
            }
            if self.kind == .hls, path.count == 2, path[1] == "index.m3u8", let s = self.sessions[String(path[0])] {
                return self.hlsPlaylist(s, conn)
            }
            if self.kind == .hls, path.count == 2, path[0] == "seg", let seq = Int(path[1].dropLast(3)) {
                return self.hlsServeSegment(seq, conn)
            }
            LocalStreamServer.respond(conn, status: "404 Not Found")
        }
    }

    private var direct = false {
        didSet { lock.lock(); _direct = direct; lock.unlock() }
    }
    /// Gives up on buffering: pending and future requests are redirected to the provider.
    private func goDirect(_ why: String) {
        guard !direct else { return }
        NSLog("M3UTS direct: %@", why)
        direct = true
        session.invalidateAndCancel()   // free the provider connection before VLC opens its own
        for s in sessions.values {
            if let c = s.conn { s.conn = nil; if s.headerSent { c.cancel() } else { LocalStreamServer.redirect(c, to: source) } }
        }
        for p in pendingPlaylists { LocalStreamServer.redirect(p.conn, to: source) }
        pendingPlaylists = []
    }
    private func fail(_ why: String) {
        NSLog("M3UTS fail: %@", why)
        for s in sessions.values { s.conn?.cancel(); s.conn = nil }
        DispatchQueue.main.async { self.onFail?(why) }
    }

    // MARK: MPEG-TS

    private struct Mark { let at: Double; let offset: Int64 }   // arrival time of the byte at offset
    private var marks: [Mark] = []
    private var keyframes: [Mark] = []          // offsets of video keyframes (packet starts)
    private var files: [(first: Int64, url: URL)] = []
    private var tsWriter: FileHandle?
    private var written: Int64 = 0              // bytes received in all
    private var floorOffset: Int64 = 0          // oldest byte still on disk
    private var fileBytes: Int64 = 0
    private var tsTask: URLSessionDataTask?
    private var tsFails = 0
    private var gotData = false
    private var carry = Data()                  // part of a packet at the end of the last chunk (keyframe scan)
    private var readers: [Int: FileHandle] = [:]
    private var sniffed = false

    private func tsConnect() {
        guard !stopped, !direct else { return }
        var r = URLRequest(url: source)
        r.timeoutInterval = 15
        tsTask = session.dataTask(with: r)
        tsTask?.resume()
    }

    func urlSession(_ s: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if dataTask === tsTask, let h = response as? HTTPURLResponse, h.statusCode >= 400 {
            completionHandler(.cancel)
            tsFails += 1
            if !gotData { fail("HTTP \(h.statusCode)") } else { retryTS() }
            return
        }
        completionHandler(.allow)
    }

    func urlSession(_ s: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if dataTask === tsTask { return tsReceived(data) }
        if let seg = downloading, dataTask === segTask { return hlsSegmentData(seg, data) }
    }

    func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if task === tsTask {
            tsTask = nil
            if stopped || direct { return }
            if !gotData && (tsFails > 2 || (error as? URLError)?.code == .cancelled) { return fail(error?.localizedDescription ?? "No data") }
            retryTS()   // a live stream shouldn't end: the provider dropped it; carry on (VLC rides over the gap)
        } else if task === segTask, let seq = downloading {
            segTask = nil; downloading = nil
            hlsSegmentDone(seq, ok: error == nil && ((task.response as? HTTPURLResponse)?.statusCode ?? 200) < 400)
        }
    }

    private func retryTS() {
        tsFails += 1
        let wait = min(5, 0.3 * Double(tsFails))
        q.asyncAfter(deadline: .now() + wait) { [weak self] in self?.tsConnect() }
    }

    private func tsReceived(_ data: Data) {
        guard !stopped else { return }
        if !sniffed {
            sniffed = true
            // Not a transport stream (an HLS playlist behind a .ts link, an error page…): let VLC have it as it is.
            if data.first != 0x47 && !(data.prefix(1024).contains(0x47)) { return goDirect("not MPEG-TS") }
            if data.starts(with: Array("#EXTM3U".utf8)) { return goDirect("playlist behind a .ts link") }
        }
        gotData = true; tsFails = 0
        let now = Date().timeIntervalSince1970
        if tsWriter == nil || fileBytes > 8_000_000 { newTSFile(); trimTS(now) }   // (the ring is trimmed a file at a time)
        tsWriter?.write(data)
        fileBytes += Int64(data.count)
        scanKeyframes(data, base: written, at: now)
        marks.append(Mark(at: now, offset: written))
        written += Int64(data.count)
        publish(start: marks.first?.at ?? now, edge: now)
        wakeAll()
    }

    private func newTSFile() {
        try? tsWriter?.close()
        let url = dir.appendingPathComponent("\(written).ts")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        tsWriter = try? FileHandle(forWritingTo: url)
        files.append((written, url))
        fileBytes = 0
    }

    /// Video keyframes: a TS packet that starts a video PES (00 00 01 E0–EF) with the random-access flag set.
    private func scanKeyframes(_ data: Data, base: Int64, at now: Double) {
        var buf = carry; buf.append(data)
        let start = base - Int64(carry.count)
        buf.withUnsafeBytes { (p: UnsafeRawBufferPointer) in
            let b = p.bindMemory(to: UInt8.self)
            var i = 0
            // Find packet alignment.
            while i + 188 * 2 < b.count && !(b[i] == 0x47 && b[i + 188] == 0x47) { i += 1 }
            while i + 188 <= b.count {
                if b[i] != 0x47 { i += 1; continue }
                let pusi = b[i + 1] & 0x40 != 0, afc = (b[i + 3] >> 4) & 3
                if pusi && afc == 3 && b[i + 4] > 0 && b[i + 5] & 0x40 != 0 {
                    let pes = i + 5 + Int(b[i + 4])
                    if pes + 4 <= i + 188 && b[pes] == 0 && b[pes + 1] == 0 && b[pes + 2] == 1 && (b[pes + 3] & 0xF0) == 0xE0 {
                        keyframes.append(Mark(at: now, offset: start + Int64(i)))
                    }
                }
                i += 188
            }
            carry = Data(buf.suffix(from: buf.startIndex + i))
        }
    }

    private func trimTS(_ now: Double) {
        while files.count > 1 {
            let size = written - files[0].first
            let secondStart = files[1].first
            let oldestAt = marks.first(where: { $0.offset >= secondStart })?.at ?? now
            guard size > maxBytes || now - oldestAt > Self.maxAge else { break }
            let gone = files.removeFirst()
            readers.removeValue(forKey: Int(gone.first)).map { try? $0.close() }
            try? FileManager.default.removeItem(at: gone.url)
            floorOffset = secondStart
            marks.removeAll { $0.offset < floorOffset }
            keyframes.removeAll { $0.offset < floorOffset }
        }
    }

    private func arrival(_ offset: Int64) -> Double {
        // The latest mark at or before offset.
        var lo = 0, hi = marks.count - 1, best = marks.first?.at ?? 0
        while lo <= hi { let m = (lo + hi) / 2; if marks[m].offset <= offset { best = marks[m].at; lo = m + 1 } else { hi = m - 1 } }
        return best
    }
    /// The last byte that arrived by `t`.
    private func offset(arrivedBy t: Double) -> Int64 {
        var lo = 0, hi = marks.count - 1, best: Int64 = floorOffset
        while lo <= hi {
            let m = (lo + hi) / 2
            if marks[m].at <= t { best = m + 1 < marks.count ? marks[m + 1].offset : written; lo = m + 1 } else { hi = m - 1 }
        }
        return best
    }

    private func tsBegin(_ s: Session) {
        let now = Date().timeIntervalSince1970
        var startAt: Int64
        if let t = s.target {
            // The keyframe at or before the target (or the oldest one we still have).
            startAt = keyframes.last(where: { $0.at <= t })?.offset ?? keyframes.first?.offset ?? floorOffset
        } else {
            // Live: from the newest keyframe, so VLC has a picture to start on straight away (less than a GOP behind).
            startAt = keyframes.last?.offset ?? max(floorOffset, written)
        }
        startAt = max(startAt, floorOffset)
        s.cursor = startAt
        s.anchorContent = startAt < written ? arrival(startAt) : now
        s.anchorWall = now
        s.startedContent = s.anchorContent
        pump(s)
    }

    private func wakeAll() {
        for s in sessions.values where s.conn != nil && !s.sending && kind == .ts { pump(s) }
        for c in segConns where !c.sending { pumpSegment(c) }
    }

    /// Sends what this session may have by now: data that arrived no later than (its start + time it has been running).
    private func pump(_ s: Session) {
        guard !stopped, let conn = s.conn, !s.sending, s.paused == nil else { return }
        if !s.headerSent {
            // Only once the provider's first bytes have shown it's a transport stream (else VLC is redirected to it).
            guard gotData else { return }
            s.headerSent = true
            let head = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
            conn.send(content: head.data(using: .utf8), completion: .contentProcessed { _ in })
        }
        let now = Date().timeIntervalSince1970
        if s.cursor < floorOffset { s.cursor = floorOffset }   // fell off the end of the ring (paused for an hour)
        // A small lead (0.5 s) keeps VLC's buffer topped up; the first moment of a delayed session goes at once.
        let allowed = s.anchorContent + (now - s.anchorWall - s.pausedTotal) + 0.5
        let end = min(written, offset(arrivedBy: allowed))
        guard end > s.cursor else { return }   // nothing yet: woken by the next chunk (or the timer below)
        let n = Int(min(end - s.cursor, 512 * 1024))
        guard let data = read(at: s.cursor, count: n), !data.isEmpty else { return }
        s.sending = true
        let sentAt = now
        conn.send(content: data, completion: .contentProcessed { [weak self, weak s] err in
            guard let self = self, let s = s else { return }
            self.q.async {
                s.sending = false
                if err != nil { s.conn = nil; return }
                s.cursor += Int64(data.count)
                // VLC stopped reading for a while (its own pause or a stall): don't burst to catch up afterwards.
                let late = Date().timeIntervalSince1970 - sentAt
                if late > 0.5 { s.anchorWall += late }
                self.pump(s)
            }
        })
    }

    private func read(at offset: Int64, count: Int) -> Data? {
        guard let f = files.last(where: { $0.first <= offset }) else { return nil }
        let h: FileHandle
        if let r = readers[Int(f.first)] { h = r } else {
            guard let r = try? FileHandle(forReadingFrom: f.url) else { return nil }
            readers[Int(f.first)] = r; h = r
        }
        try? h.seek(toOffset: UInt64(offset - f.first))
        let next = files.first(where: { $0.first > f.first })?.first ?? written
        return try? h.read(upToCount: Int(min(Int64(count), next - offset)))
    }

    // MARK: HLS

    private struct Seg {
        let seq: Int
        let url: URL
        let dur: Double
        let disc: Bool
        var start: Double = 0     // content time (wall clock when it was live)
        var state = 0             // 0 known, 1 wanted, 2 downloading, 3 done, 4 failed
        var bytes = 0
    }
    private var segs: [Seg] = []
    private var seqOf: [String: Int] = [:]
    private var nextSeq = 0
    private var mediaURL: URL?
    private var target: Double = 6
    private var segTask: URLSessionDataTask?
    private var downloading: Int?
    private var segWriter: FileHandle?
    private var polls = 0
    private var pendingPlaylists: [(s: Session, conn: NWConnection)] = []
    private final class SegConn { let seq: Int; let conn: NWConnection; var sent = 0; var sending = false; init(_ s: Int, _ c: NWConnection) { seq = s; conn = c } }
    private var segConns: [SegConn] = []
    private var hlsFails = 0

    private func hlsPoll() {
        guard !stopped, !direct else { return }
        let url = mediaURL ?? source
        var r = URLRequest(url: url); r.timeoutInterval = 10
        session.dataTask(with: r) { [weak self] data, resp, err in
            self?.q.async { self?.hlsPlaylistArrived(data, resp, err) }
        }.resume()
    }

    private func hlsPlaylistArrived(_ data: Data?, _ resp: URLResponse?, _ err: Error?) {
        guard !stopped, !direct else { return }
        guard let data = data, let text = String(data: data, encoding: .utf8), let base = resp?.url,
              ((resp as? HTTPURLResponse)?.statusCode ?? 200) < 400, text.hasPrefix("#EXTM3U") else {
            hlsFails += 1
            if segs.isEmpty && hlsFails >= 2 { return goDirect("playlist: \(err?.localizedDescription ?? "unreadable")") }
            q.asyncAfter(deadline: .now() + 1) { [weak self] in self?.hlsPoll() }
            return
        }
        hlsFails = 0
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }
        if lines.contains(where: { $0.hasPrefix("#EXT-X-STREAM-INF") }) {
            // Master playlist: take the best picture (one connection: no switching about). Separate audio → VLC directly.
            if lines.contains(where: { $0.hasPrefix("#EXT-X-MEDIA:") && $0.contains("URI=") }) { return goDirect("alternate renditions") }
            var best: (bw: Int, url: URL)?
            for (i, l) in lines.enumerated() where l.hasPrefix("#EXT-X-STREAM-INF") {
                let bw = Int(l.components(separatedBy: "BANDWIDTH=").dropFirst().first?.prefix { $0.isNumber } ?? "") ?? 0
                if let u = lines[(i + 1)...].first(where: { !$0.isEmpty && !$0.hasPrefix("#") }), let url = URL(string: u, relativeTo: base)?.absoluteURL,
                   bw >= (best?.bw ?? -1) { best = (bw, url) }
            }
            guard let b = best else { return goDirect("empty master") }
            mediaURL = b.url
            return hlsPoll()
        }
        mediaURL = base
        if lines.contains(where: { ($0.hasPrefix("#EXT-X-KEY") && !$0.contains("METHOD=NONE")) || $0.hasPrefix("#EXT-X-MAP") || $0.hasPrefix("#EXT-X-BYTERANGE") }) {
            return goDirect("encrypted / fMP4 / byte ranges")
        }
        if lines.contains("#EXT-X-ENDLIST") && segs.isEmpty { return goDirect("not live") }
        if let t = lines.first(where: { $0.hasPrefix("#EXT-X-TARGETDURATION:") }).flatMap({ Double($0.dropFirst(22)) }) { target = max(1, t) }
        var fresh: [Seg] = []
        var dur = 0.0, disc = false
        for l in lines {
            if l.hasPrefix("#EXTINF:") { dur = Double(l.dropFirst(8).prefix { $0 != "," }) ?? target }
            else if l == "#EXT-X-DISCONTINUITY" { disc = true }
            else if !l.isEmpty && !l.hasPrefix("#"), let u = URL(string: l, relativeTo: base)?.absoluteURL {
                let key = u.absoluteString
                if seqOf[key] == nil {
                    seqOf[key] = nextSeq
                    fresh.append(Seg(seq: nextSeq, url: u, dur: dur > 0 ? dur : target, disc: disc && !segs.isEmpty))
                    nextSeq += 1
                }
                dur = 0; disc = false
            }
        }
        let now = Date().timeIntervalSince1970
        if segs.isEmpty {
            // The first playlist: the newest segment ends now; the older ones before it.
            var end = now
            for i in fresh.indices.reversed() { fresh[i].start = end - fresh[i].dur; end = fresh[i].start }
            // Fetch the newest few straight away (VLC starts near the live edge); older ones only if VLC asks.
            for i in fresh.indices where i >= fresh.count - 3 { fresh[i].state = 1 }
        } else {
            var end = segs.last.map { $0.start + $0.dur } ?? now
            for i in fresh.indices { fresh[i].start = end; end += fresh[i].dur; fresh[i].state = 1 }
        }
        segs.append(contentsOf: fresh)
        trimHLS(now)
        if let first = segs.first(where: { $0.state == 3 }) ?? segs.first, let last = segs.last {
            publish(start: first.start, edge: max(last.start + last.dur, now - 0))
        }
        // Playlists VLC asked for before we had one.
        let waiting = pendingPlaylists; pendingPlaylists = []
        for p in waiting { hlsPlaylist(p.s, p.conn) }
        downloadNext()
        polls += 1
        let again = lines.contains("#EXT-X-ENDLIST") ? 5 : max(1, min(target / 2, 4))
        q.asyncAfter(deadline: .now() + again) { [weak self] in self?.hlsPoll() }
    }

    private func trimHLS(_ now: Double) {
        var total = segs.reduce(0) { $0 + $1.bytes }
        while let f = segs.first, segs.count > 8, (Int64(total) > maxBytes || now - f.start > Self.maxAge) {
            total -= f.bytes
            try? FileManager.default.removeItem(at: segFile(f.seq))
            segs.removeFirst()
        }
    }

    private func segFile(_ seq: Int) -> URL { dir.appendingPathComponent("s\(seq).ts") }
    private func segIndex(_ seq: Int) -> Int? {
        guard let f = segs.first?.seq, seq >= f, seq - f < segs.count else { return nil }
        return seq - f
    }

    private func downloadNext() {
        guard segTask == nil, !stopped, !direct else { return }
        guard let i = segs.firstIndex(where: { $0.state == 1 }) else { return }
        segs[i].state = 2
        downloading = segs[i].seq
        FileManager.default.createFile(atPath: segFile(segs[i].seq).path, contents: nil)
        segWriter = try? FileHandle(forWritingTo: segFile(segs[i].seq))
        var r = URLRequest(url: segs[i].url); r.timeoutInterval = 15
        segTask = session.dataTask(with: r)
        segTask?.resume()
    }

    private func hlsSegmentData(_ seq: Int, _ data: Data) {
        segWriter?.write(data)
        if let i = segIndex(seq) { segs[i].bytes += data.count }
        for c in segConns where c.seq == seq && !c.sending { pumpSegment(c) }
    }

    private func hlsSegmentDone(_ seq: Int, ok: Bool) {
        try? segWriter?.close(); segWriter = nil
        if let i = segIndex(seq) { segs[i].state = ok ? 3 : 4 }
        if let f = segs.first(where: { $0.state == 3 }), let l = segs.last { publish(start: f.start, edge: l.start + l.dur) }
        for c in segConns where c.seq == seq && !c.sending { pumpSegment(c) }
        downloadNext()
    }

    /// The session's own playlist: from its first segment, growing at real time (so VLC starts on that first segment and
    /// stays exactly that far behind). Live starts on the provider's newest few.
    private func hlsPlaylist(_ s: Session, _ conn: NWConnection) {
        if segs.isEmpty { pendingPlaylists.append((s, conn)); return }
        let now = Date().timeIntervalSince1970
        var list: [Seg]
        if s.target == nil && s.firstSeq < 0 {
            // Live: the provider's newest few, then growing at real time like any session (so a pause holds its place).
            let i = max(0, segs.count - 6)
            s.firstSeq = segs[i].seq
            s.anchorContent = segs[i].start
            let edge = segs.last.map { $0.start + $0.dur } ?? now
            s.anchorWall = now - (edge - s.anchorContent)
        }
        if let t = s.target, s.firstSeq < 0 {
            // Delayed: from the recorded segment holding that moment.
            let i = segs.lastIndex(where: { $0.start <= t && $0.state == 3 }) ?? segs.firstIndex(where: { $0.state == 3 }) ?? 0
            s.firstSeq = segs[i].seq
            s.anchorContent = segs[i].start
            s.anchorWall = now
            s.startedContent = segs[i].start
        }
        let running = (s.paused ?? now) - s.anchorWall - s.pausedTotal
        let lead = s.target == nil ? 0.5 : max(target * 1.5, 6)   // delayed: the first two at once, so VLC starts on the first
        let upTo = s.anchorContent + running + lead
        list = segs.filter { $0.seq >= s.firstSeq && $0.start <= upTo }
        if list.isEmpty, let i = segIndex(s.firstSeq) { list = [segs[i]] }
        list = Array(list.suffix(8))
        // Anything VLC may now ask for is fetched in order.
        for seg in list { if let i = segIndex(seg.seq), segs[i].state == 0 { segs[i].state = 1 } }
        downloadNext()
        var out = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:\(Int(ceil(list.map { $0.dur }.max() ?? target)))\n#EXT-X-MEDIA-SEQUENCE:\(list.first?.seq ?? 0)\n"
        for seg in list {
            if seg.disc { out += "#EXT-X-DISCONTINUITY\n" }
            out += String(format: "#EXTINF:%.3f,\n", seg.dur) + "/b/\(id)/seg/\(seg.seq).ts\n"
        }
        let body = out.data(using: .utf8)!
        let head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: \(body.count)\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        conn.send(content: head.data(using: .utf8)! + body, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in conn.cancel() })
    }

    private func hlsServeSegment(_ seq: Int, _ conn: NWConnection) {
        guard let i = segIndex(seq), segs[i].state != 4 else { return LocalStreamServer.respond(conn, status: "404 Not Found") }
        if segs[i].state == 0 { segs[i].state = 1 }
        if let s = sessions.values.first, s.startedContent == nil { s.startedContent = segs[i].start }   // (live: the segment VLC starts on)
        let head = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n" + (segs[i].state == 3 ? "Content-Length: \(segs[i].bytes)\r\n" : "") + "Connection: close\r\n\r\n"
        conn.send(content: head.data(using: .utf8), completion: .contentProcessed { _ in })
        let c = SegConn(seq, conn)
        segConns.append(c)
        downloadNext()
        pumpSegment(c)
    }

    private func pumpSegment(_ c: SegConn) {
        guard !stopped, !c.sending, let i = segIndex(c.seq) else { return }
        let have = segs[i].bytes
        if c.sent >= have {
            if segs[i].state == 3 || segs[i].state == 4 {
                c.conn.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.conn.cancel() })
                segConns.removeAll { $0 === c }
            }
            return
        }
        guard let h = try? FileHandle(forReadingFrom: segFile(c.seq)) else { return }
        try? h.seek(toOffset: UInt64(c.sent))
        let data = (try? h.read(upToCount: min(have - c.sent, 512 * 1024))) ?? Data()
        try? h.close()
        guard !data.isEmpty else { return }
        c.sending = true
        c.conn.send(content: data, completion: .contentProcessed { [weak self] err in
            self?.q.async {
                c.sending = false
                if err != nil { self?.segConns.removeAll { $0 === c }; return }
                c.sent += data.count
                self?.pumpSegment(c)
            }
        })
    }
}

/// The tiny HTTP server VLC reads the recordings from: 127.0.0.1 only, any free port.
final class LocalStreamServer {
    static let shared = LocalStreamServer()
    private let q = DispatchQueue(label: "m3u.localserver")
    private var listener: NWListener?
    private var buffers: [String: LiveBuffer] = [:]
    private let lock = NSLock()
    private var _port: UInt16?
    var port: UInt16? { lock.lock(); defer { lock.unlock() }; return _port }

    /// Starts listening (once); `ready` runs on the main queue when the port is known (or nil if it can't listen).
    func start(_ ready: ((UInt16?) -> Void)? = nil) {
        if let p = port { ready?(p); return }
        q.async {
            if self.listener == nil {
                let params = NWParameters.tcp
                params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: .any)
                params.allowLocalEndpointReuse = true
                guard let l = try? NWListener(using: params) else { DispatchQueue.main.async { ready?(nil) }; return }
                self.listener = l
                l.newConnectionHandler = { [weak self] c in self?.accept(c) }
                l.stateUpdateHandler = { [weak self] st in
                    guard let self = self else { return }
                    switch st {
                    case .ready:
                        self.lock.lock(); self._port = l.port?.rawValue; self.lock.unlock()
                        NSLog("M3UTS server on %d", Int(l.port?.rawValue ?? 0))
                    case .failed: self.lock.lock(); self._port = nil; self.lock.unlock(); self.listener = nil
                    default: break
                    }
                }
                l.start(queue: self.q)
            }
            // Wait (briefly) for the port.
            func check(_ n: Int) {
                if let p = self.port { DispatchQueue.main.async { ready?(p) }; return }
                if n <= 0 { DispatchQueue.main.async { ready?(nil) }; return }
                self.q.asyncAfter(deadline: .now() + 0.02) { check(n - 1) }
            }
            check(50)
        }
    }

    func register(_ b: LiveBuffer) { lock.lock(); buffers[String(b.id)] = b; lock.unlock() }
    func unregister(_ b: LiveBuffer) { lock.lock(); buffers.removeValue(forKey: String(b.id)); lock.unlock() }

    private func accept(_ c: NWConnection) {
        c.start(queue: q)
        readRequest(c, Data())
    }
    private func readRequest(_ c: NWConnection, _ got: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, done, err in
            guard let self = self else { return }
            var all = got; if let d = data { all.append(d) }
            guard err == nil else { c.cancel(); return }
            guard let end = all.range(of: Data("\r\n\r\n".utf8)) else {
                if done || all.count > 16384 { c.cancel() } else { self.readRequest(c, all) }
                return
            }
            let head = String(decoding: all[..<end.lowerBound], as: UTF8.self)
            let parts = head.split(separator: "\r\n").first?.split(separator: " ") ?? []
            guard parts.count >= 2, parts[0] == "GET" else { return Self.respond(c, status: "405 Method Not Allowed") }
            let path = parts[1].split(separator: "?").first?.split(separator: "/") ?? []
            // /b/<buffer>/…
            guard path.count >= 3, path[0] == "b" else { return Self.respond(c, status: "404 Not Found") }
            self.lock.lock(); let b = self.buffers[String(path[1])]; self.lock.unlock()
            guard let buffer = b else { return Self.respond(c, status: "404 Not Found") }
            buffer.handle(path: Array(path[2...]), conn: c)
        }
    }

    static func respond(_ c: NWConnection, status: String) {
        let r = "HTTP/1.1 \(status)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        c.send(content: r.data(using: .utf8), contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
    }
    static func redirect(_ c: NWConnection, to url: URL) {
        let r = "HTTP/1.1 302 Found\r\nLocation: \(url.absoluteString)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        c.send(content: r.data(using: .utf8), contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
    }
}

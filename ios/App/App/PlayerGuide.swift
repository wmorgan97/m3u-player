import UIKit

/// Shared look for the player's panels: one accent, one type scale, one radius family.
enum PlayerStyle {
    static let accent = UIColor(red: 0.357, green: 0.576, blue: 1, alpha: 1)   // the app's blue (#5b93ff)
    static let secondary = UIColor(white: 1, alpha: 0.62)
    static let tertiary = UIColor(white: 1, alpha: 0.42)
    static let track = UIColor(white: 1, alpha: 0.16)
    static func clock(_ ms: Double) -> String {
        guard ms > 0 else { return "" }
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: ms / 1000))
    }
}

/// Logos for the tuning card and the guide: fetched once, kept in memory.
enum LogoCache {
    private static let cache = NSCache<NSString, UIImage>()
    private static var waiting: [String: [(UIImage?) -> Void]] = [:]
    static func image(_ url: String) -> UIImage? { cache.object(forKey: url as NSString) }
    static func load(_ url: String, _ done: @escaping (UIImage?) -> Void) {
        guard !url.isEmpty, let u = URL(string: url) else { return done(nil) }
        if let i = cache.object(forKey: url as NSString) { return done(i) }
        if waiting[url] != nil { waiting[url]?.append(done); return }
        waiting[url] = [done]
        URLSession.shared.dataTask(with: u) { data, _, _ in
            let img = data.flatMap { UIImage(data: $0) }
            DispatchQueue.main.async {
                if let img = img { cache.setObject(img, forKey: url as NSString) }
                for d in waiting.removeValue(forKey: url) ?? [] { d(img) }
            }
        }.resume()
    }
}

/// A channel's logo on a quiet tile; the channel's initials until (or unless) the logo arrives.
final class LogoTile: UIView {
    private let image = UIImageView()
    private let initials = UILabel()
    private var url = ""
    init(size: CGFloat, radius: CGFloat) {
        super.init(frame: CGRect(x: 0, y: 0, width: size, height: size))
        backgroundColor = UIColor(white: 1, alpha: 0.1)
        layer.cornerRadius = radius
        layer.cornerCurve = .continuous
        clipsToBounds = true
        image.contentMode = .scaleAspectFit
        initials.textAlignment = .center
        initials.textColor = UIColor(white: 1, alpha: 0.8)
        initials.font = .systemFont(ofSize: size * 0.32, weight: .bold)
        for v in [initials, image] { v.translatesAutoresizingMaskIntoConstraints = false; addSubview(v) }
        let inset = size * 0.12
        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: size), heightAnchor.constraint(equalToConstant: size),
            image.leadingAnchor.constraint(equalTo: leadingAnchor, constant: inset), image.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -inset),
            image.topAnchor.constraint(equalTo: topAnchor, constant: inset), image.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -inset),
            initials.centerXAnchor.constraint(equalTo: centerXAnchor), initials.centerYAnchor.constraint(equalTo: centerYAnchor),
            initials.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -4),
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    func set(logo: String, name: String) {
        url = logo
        let words = name.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).prefix(2)
        initials.text = words.map { String($0.prefix(1)).uppercased() }.joined()
        if let i = LogoCache.image(logo) { image.image = i; initials.isHidden = true; return }
        image.image = nil; initials.isHidden = false
        LogoCache.load(logo) { [weak self] i in
            guard let self = self, self.url == logo, let i = i else { return }
            self.image.image = i; self.initials.isHidden = true
        }
    }
}

/// Shown the moment you change channel, until the new picture arrives: logo, number and name, and what's on.
/// Calm on purpose: a dimmed last frame (or black), centred text, a small spinner. It fades when the picture comes.
final class TuningCard: UIView {
    private let scrim = UIView()
    private let logo = LogoTile(size: 64, radius: 15)
    private let name = UILabel()
    private let now = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private(set) var showing = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        alpha = 0
        scrim.backgroundColor = UIColor(white: 0, alpha: 0.66)
        scrim.frame = bounds
        scrim.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(scrim)
        name.font = .systemFont(ofSize: 22, weight: .semibold)
        name.textColor = .white
        name.textAlignment = .center
        now.font = .systemFont(ofSize: 15)
        now.textColor = UIColor(white: 1, alpha: 0.72)
        now.textAlignment = .center
        now.numberOfLines = 1
        spinner.color = UIColor(white: 1, alpha: 0.7)
        let stack = UIStackView(arrangedSubviews: [logo, name, now, spinner])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 6
        stack.setCustomSpacing(14, after: logo)
        stack.setCustomSpacing(18, after: now)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: safeAreaLayoutGuide.leadingAnchor, constant: 32),
            name.widthAnchor.constraint(lessThanOrEqualToConstant: 520),
            now.widthAnchor.constraint(lessThanOrEqualToConstant: 520),
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func show(name n: String, number: Int, logo l: String, now title: String) {
        let text = NSMutableAttributedString()
        if number > 0 { text.append(NSAttributedString(string: "\(number)  ", attributes: [.foregroundColor: UIColor(white: 1, alpha: 0.5), .font: UIFont.monospacedDigitSystemFont(ofSize: 22, weight: .medium)])) }
        text.append(NSAttributedString(string: n, attributes: [.foregroundColor: UIColor.white, .font: UIFont.systemFont(ofSize: 22, weight: .semibold)]))
        name.attributedText = text
        now.text = title
        now.isHidden = title.isEmpty
        logo.set(logo: l, name: n)
        spinner.startAnimating()
        showing = true
        UIView.animate(withDuration: 0.12, delay: 0, options: [.beginFromCurrentState]) { self.alpha = 1 }
    }
    func hide(animated: Bool = true) {
        guard showing else { return }
        showing = false
        UIView.animate(withDuration: animated ? 0.3 : 0, delay: 0, options: [.beginFromCurrentState]) { self.alpha = 0 } completion: { _ in
            if !self.showing { self.spinner.stopAnimating() }
        }
    }
}

/// One channel in the mini guide.
struct GuideRow {
    var name: String
    var number: Int
    var logo: String
    var now: String
    var start: Double   // ms
    var end: Double     // ms
}
struct GuideData {
    var rows: [GuideRow]
    var current: Int          // the playing row
    var group: String
    var nowTitle: String, nowStart: Double, nowEnd: Double, nowDesc: String
    var nextTitle: String, nextStart: Double
}

/// The mini guide inside the player: what's on this channel now and next, and the channels around it with what's on
/// each, a thin line for how far through it is. Sideways it's a panel over the lower part of the picture (the picture
/// keeps playing above it); upright, a sheet under the picture, which moves up to make room. Tap a channel to switch,
/// swipe down or tap outside to close.
final class GuidePanel: UIView, UITableViewDataSource, UITableViewDelegate, UIGestureRecognizerDelegate {
    var onPick: ((Int) -> Void)?
    var onClose: (() -> Void)?
    private(set) var data: GuideData
    private let backdrop = UIControl()
    let sheet = UIView()
    private let blur = UIVisualEffectView(effect: UIBlurEffect(style: .systemThickMaterialDark))
    private let grabber = UIView()
    private let table = UITableView(frame: .zero, style: .plain)
    private let header = UIView()              // this channel: now / next
    private let divider = UIView()
    private let listTitle = UILabel()
    private let logo = LogoTile(size: 44, radius: 11)
    private let chName = UILabel()
    private let chGroup = UILabel()
    private let times = UILabel()
    private let progTitle = UILabel()
    private let progress = ProgressLine()
    private let left = UILabel()
    private let nextLine = UILabel()
    private var wide = true
    private var refresh: Timer?

    init(data: GuideData) {
        self.data = data
        super.init(frame: .zero)
        backdrop.backgroundColor = UIColor(white: 0, alpha: 0.28)
        backdrop.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        addSubview(backdrop)

        sheet.layer.cornerRadius = 22
        sheet.layer.cornerCurve = .continuous
        sheet.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        sheet.clipsToBounds = true
        addSubview(sheet)
        blur.backgroundColor = UIColor(white: 0.04, alpha: 0.5)   // darker than the material alone: text stays crisp over bright pictures
        sheet.addSubview(blur)
        let hairline = UIView()
        hairline.backgroundColor = UIColor(white: 1, alpha: 0.1)
        hairline.frame = CGRect(x: 0, y: 0, width: 4000, height: 1 / UIScreen.main.scale)
        sheet.addSubview(hairline)
        grabber.backgroundColor = UIColor(white: 1, alpha: 0.3)
        grabber.layer.cornerRadius = 2.5
        sheet.addSubview(grabber)

        chName.font = .systemFont(ofSize: 15, weight: .semibold); chName.textColor = .white
        chGroup.font = .systemFont(ofSize: 13); chGroup.textColor = PlayerStyle.secondary
        times.font = .monospacedDigitSystemFont(ofSize: 12, weight: .medium); times.textColor = PlayerStyle.secondary
        progTitle.font = .systemFont(ofSize: 17, weight: .semibold); progTitle.textColor = .white; progTitle.numberOfLines = 2
        left.font = .monospacedDigitSystemFont(ofSize: 12, weight: .medium); left.textColor = PlayerStyle.secondary; left.textAlignment = .right
        nextLine.font = .systemFont(ofSize: 13); nextLine.textColor = PlayerStyle.secondary; nextLine.numberOfLines = 1
        for v in [logo, chName, chGroup, times, progTitle, progress, left, nextLine] { header.addSubview(v) }
        logo.translatesAutoresizingMaskIntoConstraints = true
        sheet.addSubview(header)
        divider.backgroundColor = UIColor(white: 1, alpha: 0.1)
        sheet.addSubview(divider)
        listTitle.font = .systemFont(ofSize: 13, weight: .semibold)
        listTitle.textColor = PlayerStyle.secondary
        sheet.addSubview(listTitle)

        table.backgroundColor = .clear
        table.separatorStyle = .none
        table.rowHeight = 60
        table.showsVerticalScrollIndicator = false
        table.dataSource = self
        table.delegate = self
        table.register(GuideCell.self, forCellReuseIdentifier: "c")
        table.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: 8, right: 0)
        sheet.addSubview(table)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.delegate = self
        sheet.addGestureRecognizer(pan)
        fill()
        refresh = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.fill(); self?.table.reloadData() }
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    deinit { refresh?.invalidate() }

    func update(_ d: GuideData) {
        data = d
        fill()
        table.reloadData()
        scrollToCurrent(animated: false)
    }

    private func fill() {
        let cur = data.rows.indices.contains(data.current) ? data.rows[data.current] : nil
        logo.set(logo: cur?.logo ?? "", name: cur?.name ?? "")
        let nameText = NSMutableAttributedString()
        if let n = cur?.number, n > 0 { nameText.append(NSAttributedString(string: "\(n)  ", attributes: [.foregroundColor: PlayerStyle.tertiary, .font: UIFont.monospacedDigitSystemFont(ofSize: 15, weight: .medium)])) }
        nameText.append(NSAttributedString(string: cur?.name ?? "", attributes: [.foregroundColor: UIColor.white, .font: UIFont.systemFont(ofSize: 15, weight: .semibold)]))
        chName.attributedText = nameText
        chGroup.text = data.group
        let now = Date().timeIntervalSince1970 * 1000
        if !data.nowTitle.isEmpty {
            times.text = "\(PlayerStyle.clock(data.nowStart)) – \(PlayerStyle.clock(data.nowEnd))"
            progTitle.text = data.nowTitle
            let span = data.nowEnd - data.nowStart
            progress.value = span > 0 ? CGFloat((now - data.nowStart) / span) : 0
            progress.isHidden = span <= 0
            let mins = Int(max(0, (data.nowEnd - now) / 60000).rounded(.up))
            left.text = span > 0 ? (mins >= 60 ? "\(mins / 60) h \(mins % 60) min left" : "\(mins) min left") : ""
        } else {
            times.text = ""
            progTitle.text = "No guide information"
            progress.isHidden = true
            left.text = ""
        }
        if !data.nextTitle.isEmpty {
            let t = NSMutableAttributedString(string: "Next  ", attributes: [.foregroundColor: PlayerStyle.tertiary, .font: UIFont.systemFont(ofSize: 13, weight: .semibold)])
            t.append(NSAttributedString(string: PlayerStyle.clock(data.nextStart) + "  ", attributes: [.foregroundColor: PlayerStyle.secondary, .font: UIFont.monospacedDigitSystemFont(ofSize: 13, weight: .medium)]))
            t.append(NSAttributedString(string: data.nextTitle, attributes: [.foregroundColor: UIColor(white: 1, alpha: 0.85), .font: UIFont.systemFont(ofSize: 13)]))
            nextLine.attributedText = t
        } else { nextLine.attributedText = nil }
        listTitle.text = data.group.isEmpty ? "Channels" : data.group
        setNeedsLayout()
    }

    // MARK: layout
    /// `videoBottom`: upright, where the (moved-up) picture ends; the sheet starts just below it.
    var videoBottom: CGFloat = 0
    override func layoutSubviews() {
        super.layoutSubviews()
        backdrop.frame = bounds
        wide = bounds.width > bounds.height
        let safe = safeAreaInsets
        let h: CGFloat = wide ? min(bounds.height * 0.64, 300) + safe.bottom : bounds.height - max(videoBottom, bounds.height * 0.3)
        let y = bounds.height - h
        if sheetOffset == 0 || sheet.bounds.height != h { sheet.bounds = CGRect(x: 0, y: 0, width: bounds.width, height: h) }
        sheet.center = CGPoint(x: bounds.midX, y: y + h / 2 + sheetOffset)
        blur.frame = sheet.bounds
        grabber.frame = CGRect(x: (bounds.width - 36) / 2, y: 6, width: 36, height: 5)
        let inL = max(safe.left, 0) + 20, inR = max(safe.right, 0) + 20
        let top: CGFloat = 20
        let body = h - safe.bottom
        if wide {
            // Two columns: this channel on the left (the thing you're watching), the list on the right.
            let colW = min(300, (bounds.width - inL - inR) * 0.36)
            header.frame = CGRect(x: inL, y: top, width: colW, height: body - top - 12)
            layoutHeader(width: colW)
            divider.frame = CGRect(x: inL + colW + 20, y: top + 2, width: 1 / UIScreen.main.scale, height: body - top - 16)
            let lx = divider.frame.maxX + 12
            listTitle.frame = CGRect(x: lx + 8, y: top - 4, width: bounds.width - lx - inR, height: 18)
            table.frame = CGRect(x: lx, y: listTitle.frame.maxY + 4, width: bounds.width - lx - inR + 8, height: h - listTitle.frame.maxY - 4)
            table.contentInset.bottom = safe.bottom + 4
        } else {
            let w = bounds.width - inL - inR
            let hh = layoutHeader(width: w)
            header.frame = CGRect(x: inL, y: top, width: w, height: hh)
            divider.frame = CGRect(x: inL, y: header.frame.maxY + 14, width: w, height: 1 / UIScreen.main.scale)
            listTitle.frame = CGRect(x: inL, y: divider.frame.maxY + 12, width: w, height: 18)
            table.frame = CGRect(x: 12, y: listTitle.frame.maxY + 4, width: bounds.width - 24, height: h - listTitle.frame.maxY - 4)
            table.contentInset.bottom = safe.bottom + 4
        }
    }

    @discardableResult
    private func layoutHeader(width w: CGFloat) -> CGFloat {
        logo.frame = CGRect(x: 0, y: 0, width: 44, height: 44)
        chName.frame = CGRect(x: 56, y: 12, width: w - 56, height: 20)
        chGroup.isHidden = true
        var y: CGFloat = 60
        times.frame = CGRect(x: 0, y: y, width: w * 0.6, height: 16)
        left.frame = CGRect(x: w * 0.4, y: y, width: w * 0.6, height: 16)
        y += 20
        let th = min(progTitle.sizeThatFits(CGSize(width: w, height: 100)).height, 44)
        progTitle.frame = CGRect(x: 0, y: y, width: w, height: th)
        y += th + 10
        progress.frame = CGRect(x: 0, y: y, width: w, height: 3)
        y += progress.isHidden ? 0 : 15
        nextLine.frame = CGRect(x: 0, y: y, width: w, height: 18)
        y += nextLine.attributedText == nil ? 0 : 18
        return y
    }

    // MARK: show / hide
    private var sheetOffset: CGFloat = 0
    func present() {
        layoutIfNeeded()
        backdrop.alpha = 0
        sheetOffset = sheet.bounds.height
        layoutSubviews()
        scrollToCurrent(animated: false)
        sheetOffset = 0
        UIView.animate(withDuration: 0.42, delay: 0, usingSpringWithDamping: 0.92, initialSpringVelocity: 0.2, options: [.allowUserInteraction]) {
            self.backdrop.alpha = 1
            self.layoutSubviews()
        }
    }
    func dismiss(velocity: CGFloat = 0, done: @escaping () -> Void) {
        refresh?.invalidate()
        sheetOffset = sheet.bounds.height
        UIView.animate(withDuration: 0.28, delay: 0, usingSpringWithDamping: 1, initialSpringVelocity: velocity / max(sheet.bounds.height, 1), options: [.beginFromCurrentState]) {
            self.backdrop.alpha = 0
            self.layoutSubviews()
        } completion: { _ in done() }
    }
    @objc private func closeTapped() { onClose?() }

    private func scrollToCurrent(animated: Bool) {
        guard data.rows.indices.contains(data.current), table.bounds.height > 0 else { return }
        table.layoutIfNeeded()
        // The playing channel in the middle of the list where it can be (clamped: the first one stays at the top).
        let h = table.bounds.height, inset = table.adjustedContentInset
        let maxY = max(-inset.top, table.contentSize.height + inset.bottom - h)
        let y = min(maxY, max(-inset.top, CGFloat(data.current) * table.rowHeight - (h - table.rowHeight) / 2))
        table.setContentOffset(CGPoint(x: 0, y: y), animated: animated)
    }

    // MARK: swipe down to close
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    override func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard let pan = g as? UIPanGestureRecognizer else { return true }
        let v = pan.velocity(in: self)
        guard v.y > abs(v.x) else { return false }
        // From the list, only once it's scrolled to its top.
        let p = pan.location(in: sheet)
        return !table.frame.contains(p) || table.contentOffset.y <= -table.adjustedContentInset.top + 1
    }
    @objc private func panned(_ g: UIPanGestureRecognizer) {
        let dy = max(0, g.translation(in: self).y)
        switch g.state {
        case .changed:
            if dy > 0 && table.isTracking { table.contentOffset.y = -table.adjustedContentInset.top }
            sheetOffset = dy
            backdrop.alpha = 1 - min(1, dy / max(sheet.bounds.height, 1))
            setNeedsLayout(); layoutIfNeeded()
        case .ended, .cancelled:
            let v = g.velocity(in: self).y
            if dy > sheet.bounds.height * 0.3 || v > 700 { onClose?() } else {
                sheetOffset = 0
                UIView.animate(withDuration: 0.3, delay: 0, usingSpringWithDamping: 0.9, initialSpringVelocity: 0) { self.backdrop.alpha = 1; self.layoutSubviews() }
            }
        default: break
        }
    }

    // MARK: list
    func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { data.rows.count }
    func tableView(_ t: UITableView, cellForRowAt i: IndexPath) -> UITableViewCell {
        let c = t.dequeueReusableCell(withIdentifier: "c", for: i) as! GuideCell
        c.set(data.rows[i.row], playing: i.row == data.current)
        return c
    }
    func tableView(_ t: UITableView, didSelectRowAt i: IndexPath) {
        t.deselectRow(at: i, animated: false)
        if i.row == data.current { onClose?(); return }   // the one you're watching: just back to it
        onPick?(i.row)
    }

    // Touches on the panel stay on the panel: no player swipes (brightness, volume, minimise) from here.
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {}
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {}
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {}
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {}
}

/// A 2–3 pt line: how far through the programme it is.
final class ProgressLine: UIView {
    var value: CGFloat = 0 { didSet { setNeedsLayout() } }
    private let fill = UIView()
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = PlayerStyle.track
        fill.backgroundColor = PlayerStyle.accent
        addSubview(fill)
        clipsToBounds = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.height / 2
        fill.frame = CGRect(x: 0, y: 0, width: bounds.width * min(1, max(0, value)), height: bounds.height)
        fill.layer.cornerRadius = bounds.height / 2
    }
}

final class GuideCell: UITableViewCell {
    private let num = UILabel()
    private let logo = LogoTile(size: 38, radius: 9)
    private let name = UILabel()
    private let now = UILabel()
    private let line = ProgressLine()
    private let playing = UIImageView(image: UIImage(systemName: "speaker.wave.2.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 12, weight: .semibold)))
    private let bg = UIView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        bg.layer.cornerRadius = 12
        bg.layer.cornerCurve = .continuous
        contentView.addSubview(bg)
        num.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        num.textColor = PlayerStyle.tertiary
        num.textAlignment = .right
        name.font = .systemFont(ofSize: 15, weight: .semibold)
        name.textColor = .white
        now.font = .systemFont(ofSize: 13)
        now.textColor = PlayerStyle.secondary
        playing.tintColor = PlayerStyle.accent
        playing.contentMode = .center
        logo.translatesAutoresizingMaskIntoConstraints = true
        for v in [num, logo, name, now, line, playing] { contentView.addSubview(v) }
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func set(_ r: GuideRow, playing on: Bool) {
        num.text = r.number > 0 ? "\(r.number)" : ""
        num.isHidden = r.number <= 0
        name.text = r.name
        now.text = r.now.isEmpty ? " " : r.now
        logo.set(logo: r.logo, name: r.name)
        let t = Date().timeIntervalSince1970 * 1000
        line.isHidden = !(r.end > r.start && r.start > 0)
        line.value = r.end > r.start ? CGFloat((t - r.start) / (r.end - r.start)) : 0
        playing.isHidden = !on
        bg.backgroundColor = on ? UIColor(white: 1, alpha: 0.1) : .clear
        setNeedsLayout()
    }
    override func setHighlighted(_ h: Bool, animated: Bool) {
        super.setHighlighted(h, animated: animated)
        if h { bg.backgroundColor = UIColor(white: 1, alpha: 0.14) } else if playing.isHidden { bg.backgroundColor = .clear } else { bg.backgroundColor = UIColor(white: 1, alpha: 0.1) }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        bg.frame = b.insetBy(dx: 0, dy: 2)
        var x: CGFloat = 8
        if !num.isHidden { num.frame = CGRect(x: x, y: 0, width: 30, height: b.height); x += 38 }
        logo.frame = CGRect(x: x, y: (b.height - 38) / 2, width: 38, height: 38)
        x += 50
        let right: CGFloat = playing.isHidden ? 12 : 34
        let w = b.width - x - right
        name.frame = CGRect(x: x, y: 10, width: w, height: 19)
        now.frame = CGRect(x: x, y: 29, width: w, height: 17)
        line.frame = CGRect(x: x, y: 49, width: min(w, 160), height: 2)
        playing.frame = CGRect(x: b.width - 30, y: 0, width: 20, height: b.height)
    }
}

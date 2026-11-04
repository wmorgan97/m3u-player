import UIKit

/// "Play on TV" in the native player: a TV glyph in the tools row, beside the picture-size button. Shown only when the
/// web app says a TV app is set up (window.__tvReady); a tap hands the player's position to the web app, which sends
/// the request through the server, closes this player (the provider allows one connection) and confirms.
enum Handoff {
    static func addButton(to overlay: UIView, after anchor: UIView, ask: ((String, @escaping (Any?) -> Void) -> Void)?, onTap: @escaping () -> Void) {
        let b = UIButton(type: .system)
        b.addAction(UIAction { _ in onTap() }, for: .touchUpInside)
        b.setImage(UIImage(systemName: "tv", withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .semibold)), for: .normal)
        b.tintColor = .white
        b.accessibilityLabel = "Play on TV"
        b.translatesAutoresizingMaskIntoConstraints = false
        b.isHidden = true
        overlay.addSubview(b)
        NSLayoutConstraint.activate([
            b.widthAnchor.constraint(equalToConstant: 36), b.heightAnchor.constraint(equalToConstant: 36),
            b.leadingAnchor.constraint(equalTo: anchor.trailingAnchor, constant: 6),
            b.centerYAnchor.constraint(equalTo: anchor.centerYAnchor),
        ])
        ask?("window.__tvReady ? window.__tvReady() : false") { r in
            DispatchQueue.main.async { b.isHidden = (r as? Bool) != true }
        }
    }
}

import ActivityKit
import SwiftUI
import WidgetKit

// A followed team's live match on the Lock Screen and in the Dynamic Island. The app starts it, updates it while it
// runs and ends it at full time (see LiveScores in the app). Once a state passes its stale date (5 minutes after it
// was fetched: the app has stopped updating it), it stops looking live and says when it was last updated.

private func crest(_ url: String) -> UIImage? { M3UShared.image(url).flatMap(UIImage.init(data:)) }

private struct Side: View {
    var name: String
    var short: String
    var crestURL: String
    var size: CGFloat
    var label: Bool = true
    var body: some View {
        VStack(spacing: 4) {
            Crest(image: crest(crestURL), name: name, size: size)
            if label { Text(short).font(.caption2.weight(.semibold)).foregroundColor(Style.secondary).lineLimit(1) }
        }
    }
}

private struct Status: View {
    var state: MatchAttributes.ContentState
    var stale: Bool
    var body: some View {
        if state.status == "final" {
            Text("Full time").foregroundColor(Style.secondary)
        } else if stale {
            Text("Last update \(Fmt.time(state.updated))").foregroundColor(Style.tertiary)
        } else {
            HStack(spacing: 4) {
                Circle().fill(Style.accent).frame(width: 5, height: 5)
                Text(state.minute.isEmpty ? "Live" : state.minute).foregroundColor(Style.accent)
            }
        }
    }
}

private struct Score: View {
    var state: MatchAttributes.ContentState
    var size: CGFloat
    var body: some View {
        Text("\(state.homeScore) – \(state.awayScore)")
            .font(.system(size: size, weight: .semibold, design: .rounded)).monospacedDigit()
            .foregroundColor(.white).contentTransition(.numericText())
    }
}

struct MatchLockScreen: View {
    var context: ActivityViewContext<MatchAttributes>
    var body: some View {
        let a = context.attributes, s = context.state
        VStack(spacing: 10) {
            HStack(alignment: .center) {
                Side(name: a.home, short: a.homeShort, crestURL: a.homeCrest, size: 40).frame(width: 72)
                Spacer()
                VStack(spacing: 4) {
                    Score(state: s, size: 32)
                    Status(state: s, stale: context.isStale).font(.caption.weight(.medium))
                }
                Spacer()
                Side(name: a.away, short: a.awayShort, crestURL: a.awayCrest, size: 40).frame(width: 72)
            }
            HStack {
                Text([a.comp, a.channel].filter { !$0.isEmpty }.joined(separator: " · ")).lineLimit(1)
                Spacer()
                if !context.isStale && s.status != "final" { Text("Updated \(Fmt.time(s.updated))") }
            }
            .font(.caption2).foregroundColor(Style.tertiary)
        }
        .padding(.horizontal, 18).padding(.vertical, 14)
        .activityBackgroundTint(Style.bg.opacity(0.92))
        .activitySystemActionForegroundColor(.white)
        .widgetURL(DeepLink.fixture(a.fixtureId))
    }
}

struct MatchLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MatchAttributes.self) { context in
            MatchLockScreen(context: context)
        } dynamicIsland: { context in
            let a = context.attributes, s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Side(name: a.home, short: a.homeShort, crestURL: a.homeCrest, size: 34).padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Side(name: a.away, short: a.awayShort, crestURL: a.awayCrest, size: 34).padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 2) {
                        Score(state: s, size: 28)
                        Status(state: s, stale: context.isStale).font(.caption.weight(.medium))
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text([a.comp, a.channel, context.isStale || s.status == "final" ? "" : "Updated \(Fmt.time(s.updated))"].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption2).foregroundColor(Style.tertiary).lineLimit(1)
                }
            } compactLeading: {
                HStack(spacing: 5) {
                    Crest(image: crest(a.homeCrest), name: a.home, size: 18)
                    Text(s.homeScore).font(.system(.subheadline, design: .rounded).weight(.semibold)).monospacedDigit()
                }
            } compactTrailing: {
                HStack(spacing: 5) {
                    Text(s.awayScore).font(.system(.subheadline, design: .rounded).weight(.semibold)).monospacedDigit()
                    Crest(image: crest(a.awayCrest), name: a.away, size: 18)
                }
            } minimal: {
                Text("\(s.homeScore)–\(s.awayScore)").font(.system(size: 12, weight: .semibold, design: .rounded)).monospacedDigit()
            }
            .widgetURL(DeepLink.fixture(a.fixtureId))
            .keylineTint(Style.accent)
        }
    }
}

// Previews (Xcode canvas) — no server needed.
extension MatchAttributes {
    static let preview = MatchAttributes(fixtureId: "p", comp: "Premier League", sport: "football", home: "Arsenal", away: "Tottenham Hotspur",
                                         homeShort: "ARS", awayShort: "TOT", homeCrest: "", awayCrest: "", channel: "Sky Sports Main Event")
}
extension MatchAttributes.ContentState {
    static let live = MatchAttributes.ContentState(homeScore: "2", awayScore: "1", minute: "63'", status: "live", updated: Date())
    static let full = MatchAttributes.ContentState(homeScore: "3", awayScore: "1", minute: "FT", status: "final", updated: Date())
}

#Preview("Lock Screen", as: .content, using: MatchAttributes.preview) { MatchLiveActivity() } contentStates: { MatchAttributes.ContentState.live; MatchAttributes.ContentState.full }
#Preview("Island expanded", as: .dynamicIsland(.expanded), using: MatchAttributes.preview) { MatchLiveActivity() } contentStates: { MatchAttributes.ContentState.live }
#Preview("Island compact", as: .dynamicIsland(.compact), using: MatchAttributes.preview) { MatchLiveActivity() } contentStates: { MatchAttributes.ContentState.live }

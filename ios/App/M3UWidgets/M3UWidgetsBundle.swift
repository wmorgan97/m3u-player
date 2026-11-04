import SwiftUI
import WidgetKit

@main
struct M3UWidgetsBundle: WidgetBundle {
    var body: some Widget {
        ContinueWatchingWidget()
        MyTeamsWidget()
        NextGameWidget()
        MatchLiveActivity()
    }
}

import SwiftUI
import Shared

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onAppear { reportScenePhase(scenePhase) }
        }
        .onChange(of: scenePhase) { newPhase in
            reportScenePhase(newPhase)
        }
    }

    private func reportScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            MainViewControllerKt.onIosAppForeground()
        case .inactive, .background:
            MainViewControllerKt.onIosAppBackground()
        @unknown default:
            break
        }
    }
}

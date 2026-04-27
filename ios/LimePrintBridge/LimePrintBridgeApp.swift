import SwiftUI

@main
struct LimePrintBridgeApp: App {

    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            SettingsView()
                .environmentObject(appState)
        }
    }
}

import SwiftUI

@main struct SoberyObratnoApp: App {
    @StateObject private var library = MemoryLibrary()

    var body: some Scene {
        WindowGroup {
            Group {
                #if DEMO_SCREENSHOTS
                if ProcessInfo.processInfo.arguments.contains("--demo-session") {
                    NavigationStack { SessionView(sessionID: "11111111-1111-4111-8111-111111111111") }
                } else if ProcessInfo.processInfo.arguments.contains("--demo-sync") {
                    SyncView()
                } else { LibraryView() }
                #else
                LibraryView()
                #endif
            }
                .environmentObject(library)
                .task {
                    #if DELETION_CHECKS
                    await DeletionChecks.run()
                    #endif
                }
        }
    }
}

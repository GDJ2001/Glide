import SwiftUI

@main
struct GlideIOSApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel = IosTransferViewModel()

    var body: some Scene {
        WindowGroup {
            MainTabView(viewModel: viewModel)
                .onChange(of: scenePhase) { phase in
                    if phase == .active { viewModel.importSharedFiles() }
                }
        }
    }
}

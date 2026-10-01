import SwiftUI

@main
struct GlideIOSApp: App {
    @StateObject private var viewModel = IosTransferViewModel()

    var body: some Scene {
        WindowGroup {
            MainTabView(viewModel: viewModel)
        }
    }
}

import SwiftUI

@main
struct GlideMacApp: App {
    @StateObject private var viewModel = MacTransferViewModel()

    var body: some Scene {
        WindowGroup {
            MacMainWindowView(viewModel: viewModel)
        }
        .windowStyle(.titleBar)
        .windowToolbarStyle(.unified)
        .commands {
            SidebarCommands()
            CommandGroup(replacing: .newItem) {
                Button("Open Downloads") {
                    viewModel.openDownloadsDirectory()
                }
                .keyboardShortcut("o", modifiers: .command)
            }
        }

        MenuBarExtra("Glide", systemImage: "arrow.triangle.2.circlepath") {
            Button("Open Glide") {
                #if os(macOS)
                NSApp.activate(ignoringOtherApps: true)
                #endif
            }
            Divider()
            Text(viewModel.isListening ? "Listening on port \(viewModel.listeningPort)" : "Listener: Idle")
            Divider()
            Button("Open Downloads") {
                viewModel.openDownloadsDirectory()
            }
            Divider()
            Button("Quit Glide") {
                #if os(macOS)
                NSApplication.shared.terminate(nil)
                #endif
            }
            .keyboardShortcut("q")
        }
    }
}

import SwiftUI

public struct MacMainWindowView: View {
    @ObservedObject var viewModel: MacTransferViewModel

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationSplitView {
            List(selection: $viewModel.selectedSidebarTab) {
                Section("Transfers") {
                    NavigationLink(value: "send") {
                        Label("Send", systemImage: "paperplane.fill")
                    }
                    NavigationLink(value: "receive") {
                        Label("Receive", systemImage: "arrow.down.circle.fill")
                    }
                    NavigationLink(value: "pair") {
                        Label("Pair Devices", systemImage: "qrcode")
                    }
                }

                Section("History & Configuration") {
                    NavigationLink(value: "activity") {
                        Label("Activity", systemImage: "clock.fill")
                    }
                    NavigationLink(value: "settings") {
                        Label("Settings", systemImage: "gearshape.fill")
                    }
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 180, ideal: 200, max: 240)
            .safeAreaInset(edge: .bottom) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(viewModel.isListening ? Color.green : Color.gray)
                        .frame(width: 8, height: 8)
                    Text(viewModel.isListening ? "Listening on \(viewModel.listeningPort)" : "Listener Idle")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .padding(12)
            }
        } detail: {
            switch viewModel.selectedSidebarTab {
            case "send":
                MacSendView(viewModel: viewModel)
            case "receive":
                MacReceiveView(viewModel: viewModel)
            case "pair":
                MacPairView(viewModel: viewModel)
            case "activity":
                MacActivityView(viewModel: viewModel)
            case "settings":
                MacSettingsView(viewModel: viewModel)
            default:
                MacSendView(viewModel: viewModel)
            }
        }
        .frame(minWidth: 700, minHeight: 480)
    }
}

import SwiftUI

#if os(macOS)
import AppKit
#endif

public struct MacSettingsView: View {
    @ObservedObject var viewModel: MacTransferViewModel

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("Settings")
                        .font(.title2)
                        .fontWeight(.bold)
                    Text("Configure identity and transfer preferences.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }

                // Identity Section
                VStack(alignment: .leading, spacing: 12) {
                    Text("Device Identity")
                        .font(.headline)

                    HStack {
                        Text("Device Name:")
                            .frame(width: 140, alignment: .leading)
                        TextField("Name", text: $viewModel.deviceName)
                            .textFieldStyle(.roundedBorder)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)

                // Storage Section
                VStack(alignment: .leading, spacing: 12) {
                    Text("Storage & Downloads")
                        .font(.headline)

                    HStack {
                        Text("Destination:")
                            .frame(width: 140, alignment: .leading)
                        Text(viewModel.downloadDirectory.path)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                        Spacer()
                        Button("Choose...") {
                            chooseDestinationFolder()
                        }
                    }

                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)

                // Network & About Section
                VStack(alignment: .leading, spacing: 12) {
                    Text("About Glide")
                        .font(.headline)

                    HStack {
                        Text("Version:")
                            .frame(width: 140, alignment: .leading)
                        Text("1.0.0-macos (Native SwiftUI)")
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("Protocol:")
                            .frame(width: 140, alignment: .leading)
                        Text("Profile 1 (TLS 1.3 / TCP)")
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("Listener Port:")
                            .frame(width: 140, alignment: .leading)
                        Text("\(viewModel.listeningPort)")
                            .foregroundColor(.secondary)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)
            }
            .padding()
        }
    }

    private func chooseDestinationFolder() {
        #if os(macOS)
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url {
            viewModel.downloadDirectory = url
        }
        #endif
    }
}

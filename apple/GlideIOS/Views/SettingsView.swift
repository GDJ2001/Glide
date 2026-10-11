import SwiftUI
import GlideCoreKit

public struct SettingsView: View {
    @ObservedObject var viewModel: IosTransferViewModel

    public init(viewModel: IosTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Device Identity")) {
                    HStack {
                        Text("Device Name")
                        Spacer()
                        TextField("Name", text: $viewModel.deviceName)
                            .multilineTextAlignment(.trailing)
                    }
                }

                Section(header: Text("Trusted Devices")) {
                    if viewModel.trustedPeers.isEmpty {
                        Text("No trusted devices saved yet.")
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(viewModel.trustedPeers, id: \.fingerprintHex) { peer in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(peer.displayName)
                                        .fontWeight(.semibold)
                                    Spacer()
                                    Button("Revoke", role: .destructive) {
                                        viewModel.revokeTrustedPeer(peer)
                                    }
                                    .buttonStyle(.borderless)
                                }
                                DisclosureGroup("Details") {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(peer.fingerprintHex)
                                            .font(.system(.caption2, design: .monospaced))
                                            .foregroundColor(.secondary)
                                        Text("Last verified: \(Date(timeIntervalSince1970: Double(peer.lastSeenAtUnixMs) / 1000.0).formatted())")
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }

                Section(header: Text("Transfer Preferences")) {
                    Toggle("Export Images to Photos Library", isOn: $viewModel.saveToPhotos)
                }

                Section(header: Text("Network")) {
                    HStack {
                        Text("Local Port")
                        Spacer()
                        Text("\(viewModel.listeningPort)")
                            .foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Transport")
                        Spacer()
                        Text("TLS 1.3 / TCP")
                            .foregroundColor(.secondary)
                    }
                }

                Section(header: Text("About")) {
                    HStack {
                        Text("Glide Version")
                        Spacer()
                        Text("1.0.0")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
            .onAppear {
                viewModel.refreshTrustedPeers()
            }
        }
    }
}

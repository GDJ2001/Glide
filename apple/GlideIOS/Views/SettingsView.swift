import SwiftUI

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
                        Text("1.0.0-ios (Native)")
                            .foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Protocol")
                        Spacer()
                        Text("Profile 1 (LAN-TLS)")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

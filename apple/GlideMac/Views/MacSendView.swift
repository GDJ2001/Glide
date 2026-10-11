import SwiftUI
import GlideCoreKit

#if os(macOS)
import AppKit
#endif

public struct MacSendView: View {
    @ObservedObject var viewModel: MacTransferViewModel
    @State private var isTargeted: Bool = false

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Send Files")
                        .font(.title2)
                        .fontWeight(.bold)
                    Text("Drag files into this window or select files to send.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Spacer()
                HStack(spacing: 10) {
                    Button("Add Files...") {
                        openFilePicker(directories: false)
                    }
                    Button("Add Folder...") {
                        openFilePicker(directories: true)
                    }
                    Button("Clear") {
                        viewModel.clearStaged()
                    }
                    .disabled(viewModel.stagedUrls.isEmpty)
                }
            }
            .padding()

            Divider()

            // Main Staged Table / Drop Zone
            if viewModel.stagedUrls.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "arrow.down.doc.fill")
                        .font(.system(size: 48))
                        .foregroundColor(isTargeted ? .accentColor : .secondary)
                    Text("Drop Files or Folders Here")
                        .font(.headline)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(isTargeted ? Color.accentColor.opacity(0.08) : Color.clear)
            } else {
                List {
                    ForEach(viewModel.stagedUrls, id: \.self) { url in
                        HStack {
                            Image(systemName: "doc.fill")
                                .foregroundColor(.blue)
                            VStack(alignment: .leading) {
                                Text(url.lastPathComponent)
                                    .font(.body)
                                    .fontWeight(.medium)
                                Text(url.path)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            if let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber {
                                Text(ByteCountFormatter.string(fromByteCount: size.int64Value, countStyle: .file))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Button {
                                viewModel.removeStagedUrl(url)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            Divider()

            // Footer: Only Quick share with QR and Paired device
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    Picker("Send Method", selection: $viewModel.sendMethod) {
                        Text("Quick share with QR").tag(MacSendMethod.quickShareQr)
                        Text("Paired device").tag(MacSendMethod.pairedDevice)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 340)

                    Spacer()

                    Text("\(viewModel.stagedUrls.count) items staged (\(ByteCountFormatter.string(fromByteCount: viewModel.stagedSize, countStyle: .file)))")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                if viewModel.sendMethod == .quickShareQr {
                    HStack(spacing: 12) {
                        TextField("Peer Invitation URI...", text: $viewModel.invitationInput)
                            .textFieldStyle(.roundedBorder)

                        Button("Send Files") {
                            viewModel.sendStagedFiles()
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(viewModel.stagedUrls.isEmpty || viewModel.invitationInput.isEmpty || viewModel.isTransferActive)
                    }
                } else {
                    HStack(spacing: 12) {
                        if viewModel.trustedPeers.isEmpty {
                            Text("No paired devices saved yet.")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            Spacer()
                        } else {
                            Picker("Paired device", selection: $viewModel.selectedTrustedPeer) {
                                ForEach(viewModel.trustedPeers, id: \.fingerprintHex) { peer in
                                    Text(peer.displayName).tag(Optional(peer))
                                }
                            }
                            .frame(width: 220)

                            TextField("Local address (e.g. 192.168.1.20:42424)", text: $viewModel.selectedPeerEndpoint)
                                .textFieldStyle(.roundedBorder)

                            Button("Send Files") {
                                viewModel.sendToPairedDevice()
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(viewModel.stagedUrls.isEmpty || viewModel.selectedTrustedPeer == nil || viewModel.isTransferActive)
                        }
                    }
                }
            }
            .padding()
            .background(Color(NSColor.controlBackgroundColor))
        }
        .onAppear {
            viewModel.refreshTrustedPeers()
        }
        .onDrop(of: [.fileURL], isTargeted: $isTargeted) { providers in
            for provider in providers {
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    if let url = url {
                        DispatchQueue.main.async {
                            viewModel.stageUrls([url])
                        }
                    }
                }
            }
            return true
        }
    }

    private func openFilePicker(directories: Bool) {
        #if os(macOS)
        let panel = NSOpenPanel()
        panel.canChooseFiles = !directories
        panel.canChooseDirectories = directories
        panel.allowsMultipleSelection = true
        if panel.runModal() == .OK {
            viewModel.stageUrls(panel.urls)
        }
        #endif
    }
}

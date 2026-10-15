import SwiftUI
import CoreImage.CIFilterBuiltins
import GlideCoreKit

#if os(macOS)
import AppKit
#endif

public struct MacPairView: View {
    @ObservedObject var viewModel: MacTransferViewModel
    @State private var qrImage: Image?
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // The desktop pairing actions retain the same order on all platforms.
                VStack(alignment: .leading, spacing: 12) {
                    Text("Pair devices")
                        .font(.title2)
                        .fontWeight(.bold)

                    pairActions

                    switch viewModel.pairingPhase {
                    case .idle:
                        EmptyView()

                    case .scanning:
                        HStack(spacing: 10) {
                            ProgressView()
                                .controlSize(.small)
                            Text("Looking for Glide devices…")
                                .font(.headline)
                        }

                    case .peersFound:
                        ForEach(viewModel.discoveredPairPeers) { peer in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer.name)
                                        .fontWeight(.semibold)
                                        .lineLimit(1)
                                        .help(peer.name)
                                    Text("Available to pair")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                Button("Select") {
                                    viewModel.selectPairPeer(peer)
                                }
                            }
                            .padding(8)
                        }

                    case .empty:
                        Text("Open Glide on the other computer and choose Show my QR and code.")
                            .font(.body)
                        HStack(spacing: 10) {
                            Button("Try again") {
                                viewModel.beginFindDevices()
                            }
                            .buttonStyle(.borderedProminent)

                            Button("Pair by address") {
                                viewModel.showManualPairEntry()
                            }
                            .buttonStyle(.bordered)
                        }

                    case .targetReady:
                        Text("Scan this code in Glide on your phone to pair.")
                            .font(.headline)

                        HStack(alignment: .center, spacing: 20) {
                            if viewModel.isPairQrVisible, let qr = qrImage {
                                qr
                                    .interpolation(.none)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 240, height: 240)
                                    .padding(10)
                                    .background(Color.white)
                                    .cornerRadius(10)
                                    .accessibilityLabel("Pair-only QR code for this Mac")
                            } else {
                                Text("QR unavailable. Copy the link or get a new code.")
                                    .foregroundColor(.secondary)
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                Text("Pairing code for another computer")
                                    .font(.subheadline)
                                    .fontWeight(.semibold)
                                Text(viewModel.pairingCodeDisplay)
                                    .font(.system(size: 32, weight: .bold, design: .monospaced))
                                    .accessibilityLabel("Pairing code \(viewModel.pairingCodeDisplay)")
                                Text(viewModel.pairingExpiryText)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }

                        if !viewModel.pairingDiagnostic.isEmpty {
                            DisclosureGroup("Connection details") {
                                Text(viewModel.pairingDiagnostic)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundColor(.secondary)
                            }
                        }

                        HStack(spacing: 10) {
                            Button("Copy link") {
                                #if os(macOS)
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(viewModel.pairingQrUri, forType: .string)
                                #endif
                            }
                            Button("New code") {
                                viewModel.startReadyToPair()
                            }
                            Button("Cancel") {
                                viewModel.cancelPairing()
                            }
                        }

                    case .initiatorPinEntry:
                        Text("Pair with \(viewModel.pairingPeerName)")
                            .font(.headline)
                        Text("Six-digit pairing code")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("000000", text: $viewModel.pairingPinInput)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 180)
                            .font(.system(.title3, design: .monospaced))
                        HStack(spacing: 10) {
                            Button("Pair") {
                                viewModel.submitInitiatorPinPairing()
                            }
                            .buttonStyle(.borderedProminent)
                            Button("Cancel") {
                                viewModel.cancelPairing()
                            }
                        }

                    case .manualEntry:
                        Text("Local address (IP:port)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("192.168.1.20:42424", text: $viewModel.pairingManualEndpointInput)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 240)
                        Text("Six-digit pairing code")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("000000", text: $viewModel.pairingPinInput)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 180)
                        HStack(spacing: 10) {
                            Button("Pair") {
                                viewModel.submitInitiatorPinPairing()
                            }
                            .buttonStyle(.borderedProminent)
                            Button("Enter pairing link") {
                                viewModel.showPairLinkEntry()
                            }
                            Button("Cancel") {
                                viewModel.cancelPairing()
                            }
                        }

                    case .useLink:
                        Text("Pair-only link")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("glide://pair/v2#...", text: $viewModel.pairingLinkInput)
                            .textFieldStyle(.roundedBorder)
                        HStack(spacing: 10) {
                            Button("Pair") {
                                viewModel.submitPairLink()
                            }
                            .buttonStyle(.borderedProminent)
                            Button("Cancel") {
                                viewModel.cancelPairing()
                            }
                        }

                    case .targetApproval:
                        Text("\(viewModel.pairingPeerName) wants to pair")
                            .font(.headline)
                        HStack(spacing: 10) {
                            Button("Approve") {
                                viewModel.approveIncomingPair(accept: true)
                            }
                            .buttonStyle(.borderedProminent)
                            Button("Decline") {
                                viewModel.approveIncomingPair(accept: false)
                            }
                        }

                    case .verifying:
                        HStack(spacing: 10) {
                            ProgressView()
                                .controlSize(.small)
                            Text("Verifying…")
                                .font(.headline)
                        }

                    case .success:
                        Text("Paired with \(viewModel.pairingPeerName)")
                            .font(.headline)
                            .foregroundColor(.green)

                    case .failed:
                        Text(viewModel.pairingMessage)
                            .font(.headline)
                            .foregroundColor(.red)
                        if !viewModel.pairingDiagnostic.isEmpty {
                            DisclosureGroup("Diagnostic details") {
                                Text(viewModel.pairingDiagnostic)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundColor(.secondary)
                            }
                        }
                        HStack(spacing: 10) {
                            Button(viewModel.pairingRecoveryAction) {
                                viewModel.executePairRecovery()
                            }
                            .buttonStyle(.borderedProminent)
                            Button("Pair by address") {
                                viewModel.showManualPairEntry()
                            }
                        }
                    }

                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)

                // Paired devices list
                VStack(alignment: .leading, spacing: 10) {
                    Text("Paired devices")
                        .font(.headline)

                    if viewModel.trustedPeers.isEmpty {
                        Text("No paired devices saved yet.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(viewModel.trustedPeers, id: \.fingerprintHex) { peer in
                            HStack {
                                Text(peer.displayName)
                                    .fontWeight(.medium)
                                    .lineLimit(1)
                                    .help(peer.displayName)
                                Spacer()
                                Button("Remove") { viewModel.revokeTrustedPeer(peer) }
                                    .buttonStyle(.bordered)
                                    .accessibilityLabel("Remove \(peer.displayName) from paired devices")
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)
            }
            .padding()
        }
        .onAppear {
            viewModel.refreshTrustedPeers()
            generateQr()
        }
        .onChange(of: viewModel.pairingQrUri) { _ in
            generateQr()
        }
    }

    private var pairActions: some View {
        HStack(spacing: 8) {
            Button("Find devices") { viewModel.beginFindDevices() }
                .buttonStyle(.borderedProminent)
            Button("Show my QR and code") {
                viewModel.startReadyToPair()
            }
            Button("Enter pairing link") { viewModel.showPairLinkEntry() }
            Button("Pair by address") { viewModel.showManualPairEntry() }
        }
    }

    private func generateQr() {
        qrImage = nil
        guard !viewModel.pairingQrUri.isEmpty else { return }
        filter.message = Data(viewModel.pairingQrUri.utf8)

        if let outputImage = filter.outputImage,
           let cgimg = context.createCGImage(outputImage, from: outputImage.extent) {
            #if os(macOS)
            let nsImage = NSImage(cgImage: cgimg, size: NSSize(width: 180, height: 180))
            qrImage = Image(nsImage: nsImage)
            #endif
        }
    }
}

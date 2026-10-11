import SwiftUI
import GlideCoreKit

#if os(iOS)
import UIKit
#endif

public struct PairView: View {
    @ObservedObject var viewModel: IosTransferViewModel

    public init(viewModel: IosTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Single active pair-only panel (QR/link intake, peer approval, and result — no desktop PIN page)
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Pair with a computer")
                            .font(.headline)

                        switch viewModel.pairStage {
                        case .idle:
                            Text("Scan a pair-only QR code on the computer or paste its pairing link.")
                                .font(.subheadline)
                                .foregroundColor(.secondary)

                            HStack {
                                TextField("glide://pair/v2#...", text: $viewModel.pairLinkInput)
                                    .textFieldStyle(.roundedBorder)

                                Button {
                                    viewModel.isScannerPresented = true
                                } label: {
                                    Image(systemName: "qrcode.viewfinder")
                                        .font(.title2)
                                }
                            }

                            Button("Use QR or link") {
                                viewModel.submitPairOnlyInvitation(viewModel.pairLinkInput)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(viewModel.pairLinkInput.isEmpty)

                        case .approvalRequired(let peerName):
                            Text("Pair with \(peerName)")
                                .font(.headline)
                            HStack(spacing: 12) {
                                Button("Approve") {
                                    viewModel.confirmPairOnlyApproval(approved: true)
                                }
                                .buttonStyle(.borderedProminent)

                                Button("Decline") {
                                    viewModel.confirmPairOnlyApproval(approved: false)
                                }
                                .buttonStyle(.bordered)
                            }

                        case .verifying:
                            HStack(spacing: 10) {
                                ProgressView()
                                Text("Verifying…")
                                    .font(.headline)
                            }

                        case .success(let peerName):
                            Text("Paired with \(peerName)")
                                .font(.headline)
                                .foregroundColor(.green)
                            Button("Done") {
                                viewModel.resetPairOnlyState()
                            }
                            .buttonStyle(.bordered)

                        case .failed(let message):
                            Text(message)
                                .font(.headline)
                                .foregroundColor(.red)
                            if !viewModel.pairDiagnostic.isEmpty {
                                DisclosureGroup("Diagnostic details") {
                                    Text(viewModel.pairDiagnostic)
                                        .font(.system(.caption, design: .monospaced))
                                        .foregroundColor(.secondary)
                                }
                            }
                            Button("Try again") {
                                viewModel.resetPairOnlyState()
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .cornerRadius(16)

                    // Paired devices list
                    VStack(alignment: .leading, spacing: 12) {
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
                                    Spacer()
                                    Text("Paired")
                                        .font(.caption)
                                        .foregroundColor(.green)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .cornerRadius(16)
                }
                .padding()
            }
            .navigationTitle("Pair devices")
            .onAppear {
                viewModel.refreshTrustedPeers()
            }
            .sheet(isPresented: $viewModel.isScannerPresented) {
                #if os(iOS)
                QrScannerView { code in
                    viewModel.handleScannedCode(code)
                }
                #endif
            }
        }
    }
}

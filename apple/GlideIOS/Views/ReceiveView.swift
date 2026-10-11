import SwiftUI
import GlideCoreKit

#if os(iOS)
import UIKit
#endif

public struct ReceiveView: View {
    @ObservedObject var viewModel: IosTransferViewModel

    public init(viewModel: IosTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // Active Transfer Progress Banner
                    if viewModel.isTransferActive {
                        VStack(spacing: 12) {
                            HStack {
                                Text(viewModel.transferStatusText)
                                    .font(.headline)
                                Spacer()
                                Text(viewModel.transferredBytesText)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }

                            ProgressView(value: viewModel.transferProgress)
                                .progressViewStyle(.linear)

                            if !viewModel.currentFileName.isEmpty {
                                Text(viewModel.currentFileName)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .padding()
                        .background(Color(uiColor: .secondarySystemBackground))
                        .cornerRadius(12)
                    }

                    // Standby Listener Card
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Circle()
                                .fill(viewModel.isListening ? Color.green : Color.gray)
                                .frame(width: 10, height: 10)
                            Text("Ready to Receive Nearby")
                                .font(.headline)
                            Spacer()
                            Button("Copy receive link") {
                                #if os(iOS)
                                UIPasteboard.general.string = viewModel.currentInvitationUri
                                #endif
                            }
                            .buttonStyle(.bordered)
                            .disabled(viewModel.currentInvitationUri.isEmpty)
                        }

                        Text("Devices on the same local Wi-Fi network can discover and send files to this device.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)

                        HStack {
                            Text("Device Name: \(viewModel.deviceName)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Spacer()
                            Text("Port: \(viewModel.listeningPort)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding()
                    .background(Color(uiColor: .secondarySystemBackground))
                    .cornerRadius(12)

                    // Receiver-as-Client Card (Connect to Sender QR)
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Connect to Sender Invitation")
                            .font(.headline)
                        Text("Scan a sender QR code or paste an invitation URI to download directly:")
                            .font(.subheadline)
                            .foregroundColor(.secondary)

                        HStack {
                            TextField("glide://pair/v1#...", text: $viewModel.invitationInput)
                                .textFieldStyle(.roundedBorder)

                            Button {
                                viewModel.isScannerPresented = true
                            } label: {
                                Image(systemName: "qrcode.viewfinder")
                                    .font(.title2)
                            }
                        }

                        Button {
                            viewModel.receiveFromInvitation()
                        } label: {
                            HStack {
                                Image(systemName: "arrow.down.circle.fill")
                                Text("Connect & Receive")
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(viewModel.invitationInput.isEmpty || viewModel.isTransferActive)
                    }
                    .padding()
                    .background(Color(uiColor: .secondarySystemBackground))
                    .cornerRadius(12)
                }
                .padding()
            }
            .navigationTitle("Glide Receive")
            .sheet(isPresented: $viewModel.isIncomingOfferVisible) {
                VStack(spacing: 20) {
                    Image(systemName: "tray.and.arrow.down.fill")
                        .font(.system(size: 48))
                        .foregroundColor(.blue)

                    Text("Incoming Transfer")
                        .font(.title2)
                        .fontWeight(.bold)

                    Text("\(viewModel.incomingOfferPeer) wants to send you files:")
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    Text(viewModel.incomingOfferSummary)
                        .font(.headline)
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(Color(uiColor: .secondarySystemBackground))
                        .cornerRadius(8)

                    HStack(spacing: 16) {
                        Button("Decline") {
                            viewModel.resolveOffer(accept: false)
                        }
                        .buttonStyle(.bordered)
                        .foregroundColor(.red)
                        .frame(maxWidth: .infinity)

                        Button("Accept") {
                            viewModel.resolveOffer(accept: true)
                        }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                    }
                }
                .padding(28)
                .presentationDetents([.medium])
            }
            .sheet(isPresented: $viewModel.isSasConfirmationVisible) {
                VStack(spacing: 20) {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 48))
                        .foregroundColor(.green)

                    Text("Verify Security Code")
                        .font(.title2)
                        .fontWeight(.bold)

                    Text("Compare the 6-digit code below with the code on \(viewModel.sasPeerName):")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)

                    Text(viewModel.sasCode)
                        .font(.system(size: 40, weight: .bold, design: .monospaced))
                        .foregroundColor(.blue)
                        .padding()
                        .background(Color(uiColor: .secondarySystemBackground))
                        .cornerRadius(12)

                    HStack(spacing: 16) {
                        Button("Decline") {
                            viewModel.resolveSas(accept: false)
                        }
                        .buttonStyle(.bordered)
                        .foregroundColor(.red)
                        .frame(maxWidth: .infinity)

                        Button("Match & Confirm") {
                            viewModel.resolveSas(accept: true)
                        }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                    }
                }
                .padding(28)
                .presentationDetents([.medium])
            }
        }
    }
}

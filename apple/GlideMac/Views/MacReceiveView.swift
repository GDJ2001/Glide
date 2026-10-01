import SwiftUI
import GlideCoreKit

public struct MacReceiveView: View {
    @ObservedObject var viewModel: MacTransferViewModel

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Header
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Receive Files")
                            .font(.title2)
                            .fontWeight(.bold)
                        Text("Manage nearby availability and receive inbound transfers.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                }

                // Active Transfer Banner
                if viewModel.isTransferActive {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text(viewModel.transferStatusText)
                                .font(.headline)
                            Spacer()
                            Text(viewModel.transferredBytesText)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }

                        ProgressView(value: viewModel.transferProgress)

                        if !viewModel.currentFileName.isEmpty {
                            Text(viewModel.currentFileName)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding()
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(8)
                }

                // Nearby Listener Card
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Circle()
                            .fill(viewModel.isListening ? Color.green : Color.gray)
                            .frame(width: 10, height: 10)
                        Text("Continuous Nearby Listener")
                            .font(.headline)
                        Spacer()
                        Button(viewModel.isListening ? "Stop Listening" : "Start Listening") {
                            viewModel.toggleListener()
                        }
                    }

                    Text("Advertises this Mac via Bonjour (_glide._tcp) for nearby device transfers.")
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
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)

                // Receiver-as-Client Card
                VStack(alignment: .leading, spacing: 12) {
                    Text("Receive from Sender Invitation")
                        .font(.headline)
                    Text("Connect to a sender device that displayed a QR code or provided an invitation URI:")
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    HStack {
                        TextField("glide://pair/v1#...", text: $viewModel.invitationInput)
                            .textFieldStyle(.roundedBorder)

                        Button("Connect & Receive") {
                            viewModel.receiveFromInvitation()
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(viewModel.invitationInput.isEmpty || viewModel.isTransferActive)
                    }
                }
                .padding()
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)
            }
            .padding()
        }
        .sheet(isPresented: $viewModel.isIncomingOfferVisible) {
            VStack(spacing: 16) {
                Text("Incoming Transfer Request")
                    .font(.headline)
                Text("\(viewModel.incomingOfferPeer) wants to send files:")
                    .font(.subheadline)
                Text(viewModel.incomingOfferSummary)
                    .font(.body)
                    .padding()
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(6)

                HStack {
                    Button("Decline") {
                        viewModel.resolveOffer(accept: false)
                    }
                    Spacer()
                    Button("Accept") {
                        viewModel.resolveOffer(accept: true)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .frame(width: 360)
        }
        .sheet(isPresented: $viewModel.isSasConfirmationVisible) {
            VStack(spacing: 16) {
                Text("Verify Security Code")
                    .font(.headline)
                Text("Confirm that this 6-digit code matches the screen on \(viewModel.sasPeerName):")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)

                Text(viewModel.sasCode)
                    .font(.system(size: 36, weight: .bold, design: .monospaced))
                    .foregroundColor(.blue)

                HStack {
                    Button("Decline") {
                        viewModel.resolveSas(accept: false)
                    }
                    Spacer()
                    Button("Match & Confirm") {
                        viewModel.resolveSas(accept: true)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .frame(width: 360)
        }
    }
}

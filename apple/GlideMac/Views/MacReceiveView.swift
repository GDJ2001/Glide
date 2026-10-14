import SwiftUI
import CoreImage.CIFilterBuiltins
import GlideCoreKit

#if os(macOS)
import AppKit
#endif

public struct MacReceiveView: View {
    @ObservedObject var viewModel: MacTransferViewModel
    @State private var qrImage: Image?
    private let context = CIContext()
    private let qrFilter = CIFilter.qrCodeGenerator()

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Receive").font(.title2).fontWeight(.bold)
                    Text("Make this Mac available to nearby Glide devices. You approve each incoming transfer.")
                        .font(.subheadline).foregroundColor(.secondary)
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text(viewModel.isListening ? "Ready to receive" : "Receiving is off")
                        .font(.headline)
                    Text("Nearby devices can scan your one-time QR code. You will still review every offer.")
                        .font(.subheadline).foregroundColor(.secondary)
                    Button(viewModel.isListening ? "Stop receiving" : "Turn on receiving") {
                        viewModel.toggleListener()
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)

                VStack(alignment: .leading, spacing: 12) {
                    Text("Connect a phone").font(.headline)
                    Text("Open Glide on the phone, choose Send files, then scan this code.")
                        .font(.subheadline).foregroundColor(.secondary)

                    if viewModel.isListening {
                        HStack(alignment: .center, spacing: 20) {
                            if let qrImage {
                                qrImage
                                    .interpolation(.none)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 180, height: 180)
                                    .padding(10)
                                    .background(Color.white)
                                    .cornerRadius(10)
                                    .accessibilityLabel("One-time QR code for receiving files")
                            } else {
                                Text("Preparing code…")
                                    .foregroundColor(.secondary)
                                    .frame(width: 200, height: 200)
                            }
                            VStack(spacing: 8) {
                                Button("Show larger code") { showLargeQr = true }
                                    .buttonStyle(.borderedProminent)
                                    .disabled(qrImage == nil)
                                Button("Copy receive link") {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(viewModel.currentInvitationUri, forType: .string)
                                }
                                .disabled(viewModel.currentInvitationUri.isEmpty)
                                Button("Refresh code") { viewModel.generateLocalInvitation() }
                            }
                            .frame(width: 192)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)

                // Keep sender-hosted invitations available without competing with the main receive flow.
                DisclosureGroup("Use a sender link") {
                    HStack {
                        TextField("glide://pair/v1#...", text: $viewModel.invitationInput)
                            .textFieldStyle(.roundedBorder)
                        Button("Connect and receive") { viewModel.receiveFromInvitation() }
                            .disabled(viewModel.invitationInput.isEmpty || viewModel.isTransferActive)
                    }
                    .padding(.top, 8)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)
            }
            .padding()
        }
        .onAppear(perform: generateQr)
        .onChange(of: viewModel.currentInvitationUri) { _ in generateQr() }
        .sheet(isPresented: $showLargeQr) {
            VStack(spacing: 16) {
                Text("Scan with Glide").font(.headline)
                if let qrImage {
                    qrImage.interpolation(.none).resizable().scaledToFit()
                        .frame(width: 320, height: 320)
                        .padding(16).background(Color.white).cornerRadius(12)
                }
                Button("Close") { showLargeQr = false }
            }
            .padding(24)
        }
        .sheet(isPresented: $viewModel.isIncomingOfferVisible) {
            VStack(spacing: 16) {
                Text("Incoming transfer request").font(.headline)
                Text("\(viewModel.incomingOfferPeer) wants to send files:")
                Text(viewModel.incomingOfferSummary)
                    .padding()
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(6)
                HStack {
                    Button("Decline") { viewModel.resolveOffer(accept: false) }
                    Spacer()
                    Button("Accept") { viewModel.resolveOffer(accept: true) }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .frame(width: 360)
        }
        .sheet(isPresented: $viewModel.isSasConfirmationVisible) {
            VStack(spacing: 16) {
                Text("Verify security code").font(.headline)
                Text("Confirm that this 6-digit code matches the screen on \(viewModel.sasPeerName):")
                    .multilineTextAlignment(.center)
                Text(viewModel.sasCode)
                    .font(.system(size: 36, weight: .bold, design: .monospaced))
                    .foregroundColor(.blue)
                HStack {
                    Button("Decline") { viewModel.resolveSas(accept: false) }
                    Spacer()
                    Button("Match and confirm") { viewModel.resolveSas(accept: true) }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .frame(width: 360)
        }
    }

    @State private var showLargeQr = false

    private func generateQr() {
        guard !viewModel.currentInvitationUri.isEmpty else {
            qrImage = nil
            return
        }
        qrFilter.message = Data(viewModel.currentInvitationUri.utf8)
        guard let output = qrFilter.outputImage,
              let image = context.createCGImage(output, from: output.extent) else {
            qrImage = nil
            return
        }
        qrImage = Image(decorative: image, scale: 1)
    }
}

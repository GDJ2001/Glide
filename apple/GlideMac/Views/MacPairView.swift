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
            VStack(spacing: 24) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Device Pairing")
                            .font(.title2)
                            .fontWeight(.bold)
                        Text("Pair with other devices using QR codes or direct invitation links.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                }

                // QR Code Display Card
                VStack(spacing: 16) {
                    Text("This Mac's Pairing QR Code")
                        .font(.headline)

                    if let qr = qrImage {
                        qr
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 200, height: 200)
                            .padding()
                            .background(Color.white)
                            .cornerRadius(12)
                    } else {
                        Rectangle()
                            .fill(Color.gray.opacity(0.1))
                            .frame(width: 200, height: 200)
                            .cornerRadius(12)
                    }

                    HStack(spacing: 16) {
                        Button("Copy Pairing Link") {
                            #if os(macOS)
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(viewModel.currentInvitationUri, forType: .string)
                            #endif
                        }

                        Button("Refresh QR") {
                            viewModel.generateLocalInvitation()
                            generateQr()
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)

                // Manual Connect Card
                VStack(alignment: .leading, spacing: 12) {
                    Text("Connect to Peer Invitation")
                        .font(.headline)
                    Text("Paste an invitation URI from another device:")
                        .font(.subheadline)
                        .foregroundColor(.secondary)

                    HStack {
                        TextField("glide://pair/v1#...", text: $viewModel.invitationInput)
                            .textFieldStyle(.roundedBorder)

                        Button("Connect") {
                            viewModel.receiveFromInvitation()
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(viewModel.invitationInput.isEmpty)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)
            }
            .padding()
        }
        .onAppear {
            generateQr()
        }
    }

    private func generateQr() {
        guard !viewModel.currentInvitationUri.isEmpty else { return }
        filter.message = Data(viewModel.currentInvitationUri.utf8)

        if let outputImage = filter.outputImage,
           let cgimg = context.createCGImage(outputImage, from: outputImage.extent) {
            #if os(macOS)
            let nsImage = NSImage(cgImage: cgimg, size: NSSize(width: 200, height: 200))
            qrImage = Image(nsImage: nsImage)
            #endif
        }
    }
}

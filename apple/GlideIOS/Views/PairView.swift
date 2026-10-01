import SwiftUI
import CoreImage.CIFilterBuiltins
import GlideCoreKit

#if os(iOS)
import UIKit
#endif

public struct PairView: View {
    @ObservedObject var viewModel: IosTransferViewModel
    @State private var qrImage: Image?
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    public init(viewModel: IosTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    // Local QR Code Card
                    VStack(spacing: 16) {
                        Text("Share This Device")
                            .font(.headline)
                        Text("Show this QR code to another device to connect.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)

                        if let qr = qrImage {
                            qr
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 220, height: 220)
                                .padding()
                                .background(Color.white)
                                .cornerRadius(16)
                                .shadow(radius: 4)
                        } else {
                            Rectangle()
                                .fill(Color.gray.opacity(0.2))
                                .frame(width: 220, height: 220)
                                .cornerRadius(16)
                        }

                        HStack(spacing: 16) {
                            Button {
                                #if os(iOS)
                                UIPasteboard.general.string = viewModel.currentInvitationUri
                                #endif
                            } label: {
                                Label("Copy Link", systemImage: "doc.on.doc")
                            }
                            .buttonStyle(.bordered)

                            Button {
                                viewModel.generateLocalInvitation()
                                generateQrCode()
                            } label: {
                                Label("Refresh", systemImage: "arrow.clockwise")
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(20)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .cornerRadius(16)

                    // Connect to Peer Card
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Connect with Code")
                            .font(.headline)
                        Text("Scan or paste a pair URI from another Glide device:")
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
                                Image(systemName: "link")
                                Text("Connect to Peer")
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(viewModel.invitationInput.isEmpty)
                    }
                    .padding(20)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .cornerRadius(16)
                }
                .padding()
            }
            .navigationTitle("Device Pairing")
            .onAppear {
                generateQrCode()
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

    private func generateQrCode() {
        guard !viewModel.currentInvitationUri.isEmpty else { return }
        filter.message = Data(viewModel.currentInvitationUri.utf8)

        if let outputImage = filter.outputImage,
           let cgimg = context.createCGImage(outputImage, from: outputImage.extent) {
            #if os(iOS)
            let uiImage = UIImage(cgImage: cgimg)
            qrImage = Image(uiImage: uiImage)
            #endif
        }
    }
}

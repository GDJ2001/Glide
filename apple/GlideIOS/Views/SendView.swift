import SwiftUI
import PhotosUI
import GlideCoreKit

public struct SendView: View {
    @ObservedObject var viewModel: IosTransferViewModel
    @State private var isFileImporterPresented: Bool = false
    @State private var isPhotoPickerPresented = false

    public init(viewModel: IosTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Staged Items List
                if viewModel.stagedUrls.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "arrow.up.circle.badge.plus")
                            .font(.system(size: 64))
                            .foregroundColor(.blue)
                        Text("No Files Selected")
                            .font(.headline)
                        Text("Add files or photos to share with a nearby device.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        Section {
                            ForEach(viewModel.stagedUrls, id: \.self) { url in
                                HStack {
                                    Image(systemName: "doc.fill")
                                        .foregroundColor(.blue)
                                    VStack(alignment: .leading) {
                                        Text(url.lastPathComponent)
                                            .font(.body)
                                            .lineLimit(1)
                                        if let size = viewModel.stagedSizes[url] {
                                            Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                        }
                                    }
                                }
                            }
                            .onDelete { indexSet in
                                let urls = indexSet.map { viewModel.stagedUrls[$0] }
                                viewModel.removeStagedFiles(urls: urls)
                            }
                        } header: {
                            HStack {
                                Text("\(viewModel.stagedUrls.count) items (\(ByteCountFormatter.string(fromByteCount: viewModel.stagedSize, countStyle: .file)))")
                                Spacer()
                                Button("Clear All") {
                                    viewModel.clearStaged()
                                }
                                .font(.caption)
                            }
                        }
                    }
                }

                // Bottom Action Bar
                VStack(spacing: 12) {
                    HStack(spacing: 16) {
                        Button {
                            isFileImporterPresented = true
                        } label: {
                            Label("Add Files", systemImage: "folder.badge.plus")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.bordered)

                        Button {
                            isPhotoPickerPresented = true
                        } label: {
                            Label("Photos", systemImage: "photo.badge.plus")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.bordered)
                    }

                    if !viewModel.stagedUrls.isEmpty {
                        Button {
                            if viewModel.invitationInput.isEmpty {
                                viewModel.isScannerPresented = true
                            } else {
                                viewModel.sendStagedFiles()
                            }
                        } label: {
                            HStack {
                                Image(systemName: "paperplane.fill")
                                Text(viewModel.invitationInput.isEmpty ? "Scan QR & Send" : "Send to Peer")
                                    .fontWeight(.semibold)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .padding()
                .background(Color(uiColor: .secondarySystemBackground))
            }
            .disabled(viewModel.isStagingFiles || viewModel.isTransferActive)
            .overlay {
                if viewModel.isStagingFiles {
                    VStack(spacing: 12) {
                        ProgressView("Preparing files…")
                        if viewModel.isImportingPhotos {
                            Button("Cancel") { viewModel.cancelPhotoImport() }
                                .frame(minHeight: 44)
                        }
                    }
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .navigationTitle("Glide Send")
            .fileImporter(
                isPresented: $isFileImporterPresented,
                allowedContentTypes: [.item],
                allowsMultipleSelection: true
            ) { result in
                switch result {
                case .success(let urls):
                    viewModel.stageFiles(urls: urls)
                case .failure:
                    break
                }
            }
            .sheet(isPresented: $isPhotoPickerPresented) {
                PhotoFilePicker { providers in
                    isPhotoPickerPresented = false
                    viewModel.importPhotos(providers: providers)
                }
            }
            .sheet(isPresented: $viewModel.isScannerPresented) {
                #if os(iOS)
                QrScannerView { code in
                    viewModel.handleScannedCode(code)
                    viewModel.sendStagedFiles()
                }
                #endif
            }
        }
    }
}

/// The system picker supplies file representations through NSItemProvider, avoiding
/// loading a whole video into Data or changing its extension to .jpg.
private struct PhotoFilePicker: UIViewControllerRepresentable {
    let onSelection: ([NSItemProvider]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = ShareExtensionCoordinator.maxItemCount
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onSelection: onSelection) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onSelection: ([NSItemProvider]) -> Void
        init(onSelection: @escaping ([NSItemProvider]) -> Void) { self.onSelection = onSelection }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            onSelection(results.map(\.itemProvider))
        }
    }
}

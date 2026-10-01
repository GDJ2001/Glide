import SwiftUI
import PhotosUI
import GlideCoreKit

public struct SendView: View {
    @ObservedObject var viewModel: IosTransferViewModel
    @State private var isFileImporterPresented: Bool = false
    @State private var selectedPhotoItems: [PhotosPickerItem] = []

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
                                        if let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber {
                                            Text(ByteCountFormatter.string(fromByteCount: size.int64Value, countStyle: .file))
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                        }
                                    }
                                }
                            }
                            .onDelete { indexSet in
                                for index in indexSet {
                                    viewModel.removeStagedFile(url: viewModel.stagedUrls[index])
                                }
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

                        PhotosPicker(
                            selection: $selectedPhotoItems,
                            matching: .any(of: [.images, .videos])
                        ) {
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
            .onChange(of: selectedPhotoItems) { items in
                Task {
                    var urls: [URL] = []
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self) {
                            let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
                            try? data.write(to: tempUrl)
                            urls.append(tempUrl)
                        }
                    }
                    await MainActor.run {
                        viewModel.stageFiles(urls: urls)
                        selectedPhotoItems.removeAll()
                    }
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

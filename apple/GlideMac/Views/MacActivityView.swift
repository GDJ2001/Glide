import SwiftUI

public struct MacActivityView: View {
    @ObservedObject var viewModel: MacTransferViewModel
    @State private var searchText = ""
    @State private var directionFilter = "All"

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    private var filteredHistory: [MacHistoryItem] {
        viewModel.historyItems.filter { item in
            (directionFilter == "All" || item.direction == directionFilter) &&
            (searchText.isEmpty || item.peerName.localizedCaseInsensitiveContains(searchText))
        }
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Activity").font(.title2).fontWeight(.bold)
                        Text("Current transfers and verified file history.")
                            .font(.subheadline).foregroundColor(.secondary)
                    }
                    Spacer()
                    Button("Received files") { viewModel.openDownloadsDirectory() }
                    Button("Clear history") { viewModel.historyItems.removeAll() }
                        .disabled(viewModel.historyItems.isEmpty)
                }

                if viewModel.isTransferActive {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text(viewModel.transferStatusText).font(.headline)
                            Spacer()
                            Text(viewModel.transferredBytesText)
                                .font(.caption).foregroundColor(.secondary)
                        }
                        ProgressView(value: viewModel.transferProgress)
                        if !viewModel.currentFileName.isEmpty {
                            Text(viewModel.currentFileName)
                                .font(.subheadline).lineLimit(1)
                                .help(viewModel.currentFileName)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(8)
                }

                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text("Verified history").font(.headline)
                        Spacer(minLength: 12)
                        TextField("Search history", text: $searchText)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 170)
                            .accessibilityLabel("Search transfer history")
                        Picker("Show", selection: $directionFilter) {
                            Text("All").tag("All")
                            Text("Sent").tag("Sent")
                            Text("Received").tag("Received")
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                        .frame(width: 230)
                    }

                    if filteredHistory.isEmpty {
                        Text("No verified transfers match.")
                            .foregroundColor(.secondary)
                            .frame(maxWidth: .infinity, minHeight: 80)
                    } else {
                        LazyVStack(spacing: 0) {
                            ForEach(filteredHistory) { item in
                                HStack(spacing: 12) {
                                    Image(systemName: "clock.arrow.circlepath")
                                        .frame(width: 32, height: 32)
                                        .foregroundColor(.accentColor)
                                        .background(Color.accentColor.opacity(0.1))
                                        .cornerRadius(6)
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 8) {
                                            Text(item.peerName).fontWeight(.semibold).lineLimit(1)
                                            Text(item.direction).font(.caption).foregroundColor(.secondary)
                                        }
                                        Text("\(item.fileCount) item(s) • \(item.formattedDate)")
                                            .font(.caption).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    Text(item.formattedSize).font(.subheadline).foregroundColor(.secondary)
                                    Text(item.status).font(.caption).fontWeight(.semibold)
                                        .foregroundColor(item.status == "Completed" ? .green : .red)
                                        .padding(.horizontal, 8).padding(.vertical, 4)
                                        .background((item.status == "Completed" ? Color.green : Color.red).opacity(0.12))
                                        .cornerRadius(6)
                                    if let destination = item.destinationUrl {
                                        Button("Open folder") { viewModel.revealInFinder(url: destination) }
                                            .controlSize(.small)
                                    }
                                }
                                .padding(.vertical, 10)
                                Divider()
                            }
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
    }
}

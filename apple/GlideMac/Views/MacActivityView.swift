import SwiftUI

public struct MacActivityView: View {
    @ObservedObject var viewModel: MacTransferViewModel

    public init(viewModel: MacTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Transfer Activity")
                        .font(.title2)
                        .fontWeight(.bold)
                    Text("History of sent and received file transfers.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Spacer()
                Button("Open Downloads") {
                    viewModel.openDownloadsDirectory()
                }
                Button("Clear History") {
                    viewModel.historyItems.removeAll()
                }
                .disabled(viewModel.historyItems.isEmpty)
            }
            .padding()

            Divider()

            if viewModel.historyItems.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("No Recent Transfers")
                        .font(.headline)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(viewModel.historyItems) { item in
                        HStack(spacing: 12) {
                            Image(systemName: item.direction == "Sent" ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                                .font(.title2)
                                .foregroundColor(item.direction == "Sent" ? .blue : .green)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.peerName)
                                    .font(.body)
                                    .fontWeight(.medium)
                                Text("\(item.fileCount) item(s) • \(item.formattedDate)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }

                            Spacer()

                            Text(item.formattedSize)
                                .font(.subheadline)
                                .foregroundColor(.secondary)

                            Text(item.status)
                                .font(.caption)
                                .fontWeight(.bold)
                                .foregroundColor(item.status == "Completed" ? .green : .red)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background((item.status == "Completed" ? Color.green : Color.red).opacity(0.1))
                                .cornerRadius(4)

                            if let destUrl = item.destinationUrl {
                                Button("Reveal") {
                                    viewModel.revealInFinder(url: destUrl)
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
    }
}

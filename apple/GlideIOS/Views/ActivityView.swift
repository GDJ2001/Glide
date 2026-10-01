import SwiftUI

public struct ActivityView: View {
    @ObservedObject var viewModel: IosTransferViewModel

    public init(viewModel: IosTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Group {
                if viewModel.historyItems.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 64))
                            .foregroundColor(.secondary)
                        Text("No Transfer Activity")
                            .font(.headline)
                        Text("Completed and incoming transfers will be logged here.")
                            .font(.subheadline)
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

                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.peerName)
                                        .font(.body)
                                        .fontWeight(.medium)
                                    Text("\(item.fileCount) item(s) • \(item.formattedDate)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }

                                Spacer()

                                VStack(alignment: .trailing, spacing: 4) {
                                    Text(item.formattedSize)
                                        .font(.subheadline)
                                        .fontWeight(.medium)
                                    Text(item.status)
                                        .font(.caption2)
                                        .fontWeight(.bold)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(item.status == "Completed" ? Color.green.opacity(0.15) : Color.red.opacity(0.15))
                                        .foregroundColor(item.status == "Completed" ? .green : .red)
                                        .cornerRadius(4)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("Activity")
            .toolbar {
                if !viewModel.historyItems.isEmpty {
                    Button("Clear") {
                        viewModel.historyItems.removeAll()
                    }
                }
            }
        }
    }
}

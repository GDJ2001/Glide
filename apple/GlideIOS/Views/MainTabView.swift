import SwiftUI

public struct MainTabView: View {
    @ObservedObject var viewModel: IosTransferViewModel

    public init(viewModel: IosTransferViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        TabView(selection: $viewModel.selectedTab) {
            SendView(viewModel: viewModel)
                .tabItem {
                    Label("Send", systemImage: "paperplane.fill")
                }
                .tag("send")

            ReceiveView(viewModel: viewModel)
                .tabItem {
                    Label("Receive", systemImage: "arrow.down.circle.fill")
                }
                .tag("receive")

            PairView(viewModel: viewModel)
                .tabItem {
                    Label("Pair", systemImage: "qrcode")
                }
                .tag("pair")

            ActivityView(viewModel: viewModel)
                .tabItem {
                    Label("Activity", systemImage: "clock.fill")
                }
                .tag("activity")

            SettingsView(viewModel: viewModel)
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
                .tag("settings")
        }
        .alert("Incoming transfer", isPresented: $viewModel.isIncomingOfferVisible) {
            Button("Decline", role: .cancel) { viewModel.resolveOffer(accept: false) }
            Button("Accept") { viewModel.resolveOffer(accept: true) }
        } message: {
            Text("\(viewModel.incomingOfferPeer) · \(viewModel.incomingOfferSummary)")
        }
        .alert("Verify security code", isPresented: $viewModel.isSasConfirmationVisible) {
            Button("Decline", role: .cancel) { viewModel.resolveSas(accept: false) }
            Button("Match & Confirm") { viewModel.resolveSas(accept: true) }
        } message: {
            Text("Compare \(viewModel.sasCode) with \(viewModel.sasPeerName).")
        }
    }
}

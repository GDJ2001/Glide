using System.Collections.Specialized;
using Glide.Desktop;
using Glide.Windows.UI.Common;
using Glide.Windows.UI.ViewModels;

namespace Glide.Windows.UI.Tests;

public sealed class MainViewModelHard015Tests
{
    [Fact]
    public async Task IncomingOffer_RemainsPendingUntilExplicitAccept()
    {
        var dispatcher = new RecordingDispatcher();
        var store = new FakeStateStore();
        var vm = new MainViewModel(
            store,
            dispatcher,
            startDiscovery: false);

        await vm.InitializationTask;

        var offer = new DesktopOffer(
            "11111111-1111-4111-8111-111111111111",
            "Pixel Receiver",
            [
                new DesktopOfferItem(
                    "22222222-2222-4222-8222-222222222222",
                    "photo.jpg",
                    4096,
                    "photo.jpg"),
                new DesktopOfferItem(
                    "33333333-3333-4333-8333-333333333333",
                    "notes.txt",
                    12,
                    "notes.txt")
            ],
            4108);

        Task<bool> decision = vm.ConfirmOfferAsync(
            offer,
            CancellationToken.None);

        Assert.False(decision.IsCompleted);
        Assert.True(vm.IsIncomingOfferModalVisible);
        Assert.Equal("Pixel Receiver", vm.IncomingOfferPeerName);
        Assert.Equal(2, vm.IncomingOfferItems.Count);
        Assert.Contains("2 file(s)", vm.IncomingOfferSummary);

        vm.AcceptIncomingOfferCommand.Execute(null);

        Assert.True(await decision);
        Assert.False(vm.IsIncomingOfferModalVisible);
        Assert.Empty(vm.IncomingOfferItems);
        Assert.True(dispatcher.AsyncInvocations >= 2);
    }

    [Fact]
    public async Task IncomingOffer_ExplicitRejectReturnsFalse()
    {
        var vm = new MainViewModel(
            new FakeStateStore(),
            new RecordingDispatcher(),
            startDiscovery: false);

        await vm.InitializationTask;

        Task<bool> decision = vm.ConfirmOfferAsync(
            new DesktopOffer(
                "11111111-1111-4111-8111-111111111111",
                "Remote Sender",
                [
                    new DesktopOfferItem(
                        "22222222-2222-4222-8222-222222222222",
                        "reject.bin",
                        1,
                        "reject.bin")
                ],
                1),
            CancellationToken.None);

        Assert.True(vm.IsIncomingOfferModalVisible);

        vm.RejectIncomingOfferCommand.Execute(null);

        Assert.False(await decision);
        Assert.False(vm.IsIncomingOfferModalVisible);
    }

    [Fact]
    public async Task IncomingOffer_CancellationClosesPromptAndCancelsDecision()
    {
        var vm = new MainViewModel(
            new FakeStateStore(),
            new RecordingDispatcher(),
            startDiscovery: false);

        await vm.InitializationTask;

        using var cts = new CancellationTokenSource();

        Task<bool> decision = vm.ConfirmOfferAsync(
            new DesktopOffer(
                "11111111-1111-4111-8111-111111111111",
                "Remote Sender",
                [
                    new DesktopOfferItem(
                        "22222222-2222-4222-8222-222222222222",
                        "cancel.bin",
                        1,
                        "cancel.bin")
                ],
                1),
            cts.Token);

        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            async () => await decision);
        Assert.False(vm.IsIncomingOfferModalVisible);
        Assert.Empty(vm.IncomingOfferItems);
    }

    [Fact]
    public async Task HistoryProjection_IsPersistedBeforeUiCollectionMutation_AndMarshaled()
    {
        var dispatcher = new RecordingDispatcher();
        var store = new FakeStateStore();
        var vm = new MainViewModel(
            store,
            dispatcher,
            startDiscovery: false);

        await vm.InitializationTask;

        bool collectionChangedInsideDispatcher = false;
        vm.HistoryItems.CollectionChanged +=
            (_, args) =>
            {
                if (args.Action == NotifyCollectionChangedAction.Add)
                {
                    collectionChangedInsideDispatcher =
                        dispatcher.IsDispatching;
                }
            };

        var entry = new DesktopHistoryEntry(
            DesktopTransferDirection.Receive,
            "verified.bin",
            1234,
            "C:\\Downloads\\verified.bin",
            "Pixel",
            DateTimeOffset.UtcNow);

        await Task.Run(
            async () =>
                await vm.OnHistoryAsync(
                    entry,
                    CancellationToken.None));

        Assert.Single(store.AppendedHistory);
        Assert.Equal(entry, store.AppendedHistory[0]);
        Assert.Single(vm.HistoryItems);
        Assert.True(collectionChangedInsideDispatcher);
        Assert.True(dispatcher.AsyncInvocations > 0);
    }

    [Fact]
    public async Task Startup_LoadsPersistedSettingsAndHistory()
    {
        var persistedEntry = new DesktopHistoryEntry(
            DesktopTransferDirection.Send,
            "old.bin",
            99,
            "C:\\Source\\old.bin",
            "Laptop",
            DateTimeOffset.UtcNow.AddMinutes(-3));

        var store = new FakeStateStore
        {
            State = new DesktopPersistentState(
                new DesktopSettings(
                    "My Glide PC",
                    "C:\\GlideDownloads",
                    77),
                [persistedEntry])
        };

        var vm = new MainViewModel(
            store,
            new RecordingDispatcher(),
            startDiscovery: false);

        await vm.InitializationTask;

        Assert.Equal("My Glide PC", vm.DeviceName);
        Assert.Equal("C:\\GlideDownloads", vm.ReceiveDirectory);
        Assert.Equal(77, vm.HistoryLimit);
        Assert.Single(vm.HistoryItems);
        Assert.Equal("old.bin", vm.HistoryItems[0].ItemName);
    }

    private sealed class RecordingDispatcher : IUiDispatcher
    {
        public int InvokeInvocations { get; private set; }
        public int AsyncInvocations { get; private set; }
        public bool IsDispatching { get; private set; }

        public bool CheckAccess() => IsDispatching;

        public void Invoke(Action action)
        {
            InvokeInvocations++;
            Execute(action);
        }

        public Task InvokeAsync(Action action)
        {
            AsyncInvocations++;
            Execute(action);
            return Task.CompletedTask;
        }

        private void Execute(Action action)
        {
            bool previous = IsDispatching;
            IsDispatching = true;
            try
            {
                action();
            }
            finally
            {
                IsDispatching = previous;
            }
        }
    }

    private sealed class FakeStateStore : IDesktopStateStore
    {
        public DesktopPersistentState State { get; set; } =
            new(
                DesktopSettings.CreateDefault(),
                []);

        public List<DesktopHistoryEntry> AppendedHistory { get; } = [];

        public Task<DesktopPersistentState> LoadAsync(
            CancellationToken cancellationToken = default) =>
            Task.FromResult(State);

        public Task SaveSettingsAsync(
            DesktopSettings settings,
            CancellationToken cancellationToken = default)
        {
            State = State with
            {
                Settings = settings
            };
            return Task.CompletedTask;
        }

        public Task AppendHistoryAsync(
            DesktopHistoryEntry entry,
            CancellationToken cancellationToken = default)
        {
            AppendedHistory.Add(entry);
            State = State with
            {
                History = [entry, .. State.History]
            };
            return Task.CompletedTask;
        }

        public Task ClearHistoryAsync(
            CancellationToken cancellationToken = default)
        {
            State = State with
            {
                History = []
            };
            return Task.CompletedTask;
        }
    }
}

using System.Net;
using System.Net.NetworkInformation;

namespace Glide.Desktop.Network;

public sealed class SystemNetworkSnapshotProvider : IDesktopNetworkSnapshotProvider, IDisposable
{
    private static readonly Lazy<SystemNetworkSnapshotProvider> _lazyInstance =
        new(() => new SystemNetworkSnapshotProvider());

    public static SystemNetworkSnapshotProvider Instance => _lazyInstance.Value;

    private readonly object _lock = new();
    private Timer? _debounceTimer;
    private bool _disposed;

    public event EventHandler? NetworkAddressChanged;

    public SystemNetworkSnapshotProvider()
    {
        try
        {
            NetworkChange.NetworkAddressChanged += OnSystemNetworkAddressChanged;
        }
        catch
        {
            // Some constrained environments do not support NetworkChange events
        }
    }

    public IReadOnlyList<DesktopNetworkInterface> GetSnapshot()
    {
        try
        {
            var adapters = NetworkInterface.GetAllNetworkInterfaces();
            var list = new List<DesktopNetworkInterface>(adapters.Length);

            foreach (var adapter in adapters)
            {
                IPInterfaceProperties ipProps;
                try
                {
                    ipProps = adapter.GetIPProperties();
                }
                catch
                {
                    continue;
                }

                var unicast = new List<DesktopUnicastAddress>();
                try
                {
                    foreach (var u in ipProps.UnicastAddresses)
                    {
                        unicast.Add(new DesktopUnicastAddress(u.Address, u.IPv4Mask, u.PrefixLength));
                    }
                }
                catch
                {
                    // Ignore transient network property read errors
                }

                var gateways = new List<IPAddress>();
                try
                {
                    foreach (var g in ipProps.GatewayAddresses)
                    {
                        if (g?.Address != null)
                        {
                            gateways.Add(g.Address);
                        }
                    }
                }
                catch
                {
                    // Ignore transient gateway read errors
                }

                list.Add(new DesktopNetworkInterface(
                    Id: adapter.Id,
                    Name: adapter.Name,
                    InterfaceType: adapter.NetworkInterfaceType,
                    OperationalStatus: adapter.OperationalStatus,
                    UnicastAddresses: unicast,
                    GatewayAddresses: gateways));
            }

            return list;
        }
        catch
        {
            return Array.Empty<DesktopNetworkInterface>();
        }
    }

    private void OnSystemNetworkAddressChanged(object? sender, EventArgs e)
    {
        lock (_lock)
        {
            if (_disposed) return;
            _debounceTimer?.Dispose();
            _debounceTimer = new Timer(OnDebounceElapsed, null, 500, Timeout.Infinite);
        }
    }

    private void OnDebounceElapsed(object? state)
    {
        lock (_lock)
        {
            if (_disposed) return;
            _debounceTimer?.Dispose();
            _debounceTimer = null;
        }

        try
        {
            NetworkAddressChanged?.Invoke(this, EventArgs.Empty);
        }
        catch
        {
            // Ignore subscriber exceptions
        }
    }

    public void Dispose()
    {
        lock (_lock)
        {
            if (_disposed) return;
            _disposed = true;
            _debounceTimer?.Dispose();
            _debounceTimer = null;
        }

        try
        {
            NetworkChange.NetworkAddressChanged -= OnSystemNetworkAddressChanged;
        }
        catch
        {
            // Ignore
        }
    }
}

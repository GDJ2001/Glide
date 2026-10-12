using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Input;
using System.Windows.Threading;
using Glide.Windows.UI.Services;
using Glide.Windows.UI.ViewModels;

namespace Glide.Windows.UI.Views;

public partial class MainWindow : Window
{
    private HwndSource? _windowSource;
    private IInputElement? _focusBeforeTrustedPicker;
    private IInputElement? _focusBeforeSavedDeviceConnect;
    private bool _fileCardWasPopulated;

    public MainWindow()
    {
        InitializeComponent();
        UpdateResponsiveLayout();
    }

    private void Window_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateResponsiveLayout();

    private void UpdateResponsiveLayout()
    {
        if (SidebarColumn is null || MainContent is null)
            return;

        // Use the window's WPF units, not physical screen pixels: Snap zones can be
        // substantially narrower after Windows display scaling. The requested Width
        // must participate too, or a wide child's desired size can block the resize
        // before its compact layout has a chance to activate.
        double layoutWidth = double.IsNaN(Width) ? ActualWidth :
            ActualWidth > 0 ? Math.Min(Width, ActualWidth) : Width;
        double layoutHeight = double.IsNaN(Height) ? ActualHeight :
            ActualHeight > 0 ? Math.Min(Height, ActualHeight) : Height;
        bool compactNavigation = layoutWidth < 1008;
        bool narrowContent = layoutWidth < 640;
        SidebarColumn.Width = new GridLength(compactNavigation ? 64 : 212);
        Sidebar.Padding = compactNavigation ? new Thickness(8, 20, 8, 20) : new Thickness(16, 20, 16, 20);
        BrandName.Visibility = compactNavigation ? Visibility.Collapsed : Visibility.Visible;
        BrandSymbol.Margin = compactNavigation ? new Thickness(0) : new Thickness(0, 0, 10, 0);
        LocalSharingNote.Visibility = compactNavigation ? Visibility.Collapsed : Visibility.Visible;
        MainContent.Margin = narrowContent
            ? new Thickness(16, 20, 16, 16)
            : compactNavigation
                ? new Thickness(24, 24, 24, 20)
                : new Thickness(32, 28, 32, 24);

        RadioButton[] navigation = [NavSend, NavReceive, NavActivity, NavPairDevices, NavSettings];
        System.Windows.Shapes.Path[] icons =
            [NavSendIcon, NavReceiveIcon, NavActivityIcon, NavPairDevicesIcon, NavSettingsIcon];
        TextBlock[] labels =
            [NavSendLabel, NavReceiveLabel, NavActivityLabel, NavPairDevicesLabel, NavSettingsLabel];
        for (int index = 0; index < navigation.Length; index++)
        {
            navigation[index].Padding = compactNavigation ? new Thickness(14, 0, 0, 0) : new Thickness(12, 0, 12, 0);
            icons[index].Margin = compactNavigation ? new Thickness(0) : new Thickness(0, 0, 12, 0);
            labels[index].Visibility = compactNavigation ? Visibility.Collapsed : Visibility.Visible;
        }

        Grid.SetRow(SelectedFileActions, narrowContent ? 1 : 0);
        Grid.SetColumn(SelectedFileActions, narrowContent ? 0 : 1);
        bool narrowHistory = layoutWidth - SidebarColumn.Width.Value -
            MainContent.Margin.Left - MainContent.Margin.Right < 620;
        Grid.SetRow(ActivityHistoryActions, narrowHistory ? 1 : 0);
        Grid.SetColumn(ActivityHistoryActions, narrowHistory ? 0 : 1);
        Grid.SetColumnSpan(ActivityHistoryActions, narrowHistory ? 2 : 1);
        ActivityHistoryActions.Margin = narrowHistory ? new Thickness(0, 12, 0, 0) : new Thickness(0);
        Grid.SetRow(ActivityTransferActions, narrowHistory ? 1 : 0);
        Grid.SetColumn(ActivityTransferActions, narrowHistory ? 0 : 1);
        Grid.SetColumnSpan(ActivityTransferActions, narrowHistory ? 2 : 1);
        ActivityTransferActions.Margin = narrowHistory ? new Thickness(0, 12, 0, 0) : new Thickness(0);
        SelectedFileActions.Margin = narrowContent ? new Thickness(0, 8, 0, 0) : new Thickness(0);
        SelectedFileActions.HorizontalAlignment = narrowContent ? HorizontalAlignment.Left : HorizontalAlignment.Right;
        ReceiveQrLayout.Orientation = narrowContent ? Orientation.Vertical : Orientation.Horizontal;
        ReceiveQrActions.Margin = narrowContent ? new Thickness(0, 12, 0, 0) : new Thickness(20, 4, 0, 0);
        bool shortWindow = layoutHeight < 600;
        HostedQrImage.Width = HostedQrImage.Height = shortWindow ? 196 : 260;
        ZoomQrImage.Width = ZoomQrImage.Height = shortWindow ? 232 : 320;
        TrustedDevicesScroll.MaxHeight = shortWindow ? 160 : 260;
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        FitInitialWindowToWorkArea();
        _windowSource = HwndSource.FromHwnd(new WindowInteropHelper(this).Handle);
        _windowSource?.AddHook(WindowMessage);
        WindowsTitleBar.Apply(this);
    }

    private void FitInitialWindowToWorkArea()
    {
        // SystemParameters.WorkArea is in device pixels; Window dimensions use DIPs.
        var toDip = PresentationSource.FromVisual(this)?.CompositionTarget?.TransformFromDevice;
        if (toDip is null)
            return;

        Rect workArea = SystemParameters.WorkArea;
        Point topLeft = toDip.Value.Transform(workArea.TopLeft);
        Point bottomRight = toDip.Value.Transform(workArea.BottomRight);
        double availableWidth = bottomRight.X - topLeft.X;
        double availableHeight = bottomRight.Y - topLeft.Y;
        Width = Math.Min(Width, Math.Max(MinWidth, availableWidth - 24));
        Height = Math.Min(Height, Math.Max(MinHeight, availableHeight - 24));
        Left = topLeft.X + Math.Max(0, (availableWidth - Width) / 2);
        Top = topLeft.Y + Math.Max(0, (availableHeight - Height) / 2);
    }

    protected override void OnPropertyChanged(DependencyPropertyChangedEventArgs e)
    {
        base.OnPropertyChanged(e);
        if (e.Property == WidthProperty || e.Property == HeightProperty)
            UpdateResponsiveLayout();
        if (e.Property == BackgroundProperty || e.Property == ForegroundProperty)
            WindowsTitleBar.Apply(this);
    }

    protected override void OnActivated(EventArgs e)
    {
        base.OnActivated(e);
        WindowsTitleBar.Apply(this);
    }

    private IntPtr WindowMessage(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        // Reapply after Windows updates non-client theme/high-contrast settings.
        if (message is 0x001A or 0x031A) // WM_SETTINGCHANGE / WM_THEMECHANGED
            Dispatcher.BeginInvoke(() => WindowsTitleBar.Apply(this));
        return IntPtr.Zero;
    }

    protected override void OnClosed(EventArgs e)
    {
        _windowSource?.RemoveHook(WindowMessage);
        _windowSource = null;
        base.OnClosed(e);
    }

    private void DropZone_DragOver(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            e.Effects = DragDropEffects.Copy;
        }
        else
        {
            e.Effects = DragDropEffects.None;
        }
        e.Handled = true;
    }

    private void SendDropZone_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        bool isPopulated = DataContext is MainViewModel { HasSelectedFiles: true };
        if (_fileCardWasPopulated && !isPopulated)
        {
            Dispatcher.BeginInvoke(() =>
            {
                SendPage.ScrollToTop();
                EmptyAddFilesButton.Focus();
            }, DispatcherPriority.Loaded);
        }
        _fileCardWasPopulated = isPopulated;
    }

    private void DropZone_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            string[] files = (string[])e.Data.GetData(DataFormats.FileDrop);
            if (files != null && DataContext is MainViewModel vm)
            {
                vm.AddDroppedFiles(files);
            }
        }
    }

    private void TrustedPicker_IsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (e.NewValue is true)
        {
            _focusBeforeTrustedPicker = Keyboard.FocusedElement;
            Dispatcher.BeginInvoke(() => CloseTrustedDevicePickerButton.Focus(), DispatcherPriority.Input);
        }
        else if (_focusBeforeTrustedPicker is { } previous)
        {
            _focusBeforeTrustedPicker = null;
            Dispatcher.BeginInvoke(() => Keyboard.Focus(previous), DispatcherPriority.Input);
        }
    }

    private void SavedDeviceConnect_IsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (e.NewValue is true)
        {
            _focusBeforeSavedDeviceConnect = Keyboard.FocusedElement;
            Dispatcher.BeginInvoke(() => SavedDeviceInvitationInput.Focus(), DispatcherPriority.Input);
        }
        else if (_focusBeforeSavedDeviceConnect is { } previous)
        {
            _focusBeforeSavedDeviceConnect = null;
            Dispatcher.BeginInvoke(() => Keyboard.Focus(previous), DispatcherPriority.Input);
        }
    }

}

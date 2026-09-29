using System.Windows;
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

    public MainWindow()
    {
        InitializeComponent();
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

}

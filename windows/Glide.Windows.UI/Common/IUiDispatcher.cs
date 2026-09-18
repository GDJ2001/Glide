using System.Windows;

namespace Glide.Windows.UI.Common;

public interface IUiDispatcher
{
    bool CheckAccess();
    void Invoke(Action action);
    Task InvokeAsync(Action action);
}

public sealed class WpfUiDispatcher : IUiDispatcher
{
    public bool CheckAccess()
    {
        var dispatcher = Application.Current?.Dispatcher;
        return dispatcher == null || dispatcher.CheckAccess();
    }

    public void Invoke(Action action)
    {
        ArgumentNullException.ThrowIfNull(action);

        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher == null || dispatcher.CheckAccess())
        {
            action();
            return;
        }

        dispatcher.Invoke(action);
    }

    public Task InvokeAsync(Action action)
    {
        ArgumentNullException.ThrowIfNull(action);

        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher == null || dispatcher.CheckAccess())
        {
            action();
            return Task.CompletedTask;
        }

        return dispatcher.InvokeAsync(action).Task;
    }
}

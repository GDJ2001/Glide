namespace Glide.Linux.UI.ViewModels;

/// <summary>
/// Owns one kind of interactive decision. Replacing a prompt declines the older
/// request, and its eventual cleanup cannot hide the newer request.
/// </summary>
internal sealed class LinuxConsentDecision(Func<Action, Task> onUiAsync)
{
    private TaskCompletionSource<bool>? _current;
    private Action? _hide;

    public async Task<bool> RequestAsync(CancellationToken cancellationToken, Action show, Action hide)
    {
        if (cancellationToken.IsCancellationRequested) return false;

        var decision = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        await onUiAsync(() =>
        {
            _current?.TrySetResult(false);
            _current = decision;
            _hide = hide;
            show();
        });

        try
        {
            return await decision.Task.WaitAsync(cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return false;
        }
        finally
        {
            await onUiAsync(() =>
            {
                if (!ReferenceEquals(_current, decision)) return;
                _current = null;
                _hide = null;
                hide();
            });
        }
    }

    public void Resolve(bool accepted)
    {
        // Modal dismissal must be immediate for keyboard Escape as well as a click.
        if (_current?.TrySetResult(accepted) == true) _hide?.Invoke();
    }
}

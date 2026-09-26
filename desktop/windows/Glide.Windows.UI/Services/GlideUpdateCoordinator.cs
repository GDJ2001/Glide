using NetSparkleUpdater;
using NetSparkleUpdater.Enums;
using NetSparkleUpdater.SignatureVerifiers;
using System.Reflection;

namespace Glide.Windows.UI.Services;

/// <summary>
/// Starts the signed update feed only for production builds that provide an HTTPS feed and the
/// pinned Ed25519 public key. Development and portable builds deliberately have no update feed.
/// </summary>
public sealed class GlideUpdateCoordinator : IDisposable
{
    private readonly Func<bool> _isTransferActive;
    private readonly GlideUpdateConfiguration _configuration;
    private SparkleUpdater? _updater;

    public GlideUpdateCoordinator(
        GlideUpdateConfiguration configuration,
        Func<bool> isTransferActive)
    {
        _configuration = configuration;
        _isTransferActive = isTransferActive;
    }

    public bool IsConfigured => _configuration.IsValid;

    public string DisabledReason => _configuration.ValidationError ??
        "Updates are not configured for this build.";

    public void StartDailyChecks()
    {
        if (!IsConfigured || _updater != null)
        {
            return;
        }

        _updater = CreateUpdater();
        // NetSparkle's loop is 24 hours. The first check is quiet and an update is never offered
        // while a transfer, consent, preparation, or queued work is active.
        _updater.UpdateDetected += (_, args) =>
        {
            if (_isTransferActive())
            {
                args.NextAction = NextUpdateAction.ProhibitUpdate;
            }
        };
        _updater.StartLoop(doInitialCheck: true);
    }

    public void CheckForUpdatesAtUserRequest()
    {
        if (!IsConfigured)
        {
            throw new InvalidOperationException(DisabledReason);
        }

        if (_isTransferActive())
        {
            throw new InvalidOperationException(
                "Finish, cancel, or clear the transfer queue before checking for an update.");
        }

        (_updater ??= CreateUpdater()).CheckForUpdatesAtUserRequest();
    }

    private SparkleUpdater CreateUpdater()
    {
        return new SparkleUpdater(
            _configuration.FeedUrl!,
            new Ed25519Checker(SecurityMode.Strict, _configuration.PublicKey!))
        {
            UIFactory = new NetSparkleUpdater.UI.WPF.UIFactory(),
            // The NSIS installer performs the replacement and starts Glide from its finish page.
            RelaunchAfterUpdate = false,
            ShouldKillParentProcessWhenStartingInstaller = true,
            CustomInstallerArguments = "/UPDATE"
        };
    }

    public void Dispose()
    {
        _updater?.Dispose();
        _updater = null;
    }
}

/// <summary>
/// Immutable build-time update configuration. The feed and key are public values, but they must
/// be supplied only by the protected release pipeline so development builds cannot follow an
/// accidental or unreviewed update channel.
/// </summary>
public sealed record GlideUpdateConfiguration(string? FeedUrl, string? PublicKey)
{
    public static GlideUpdateConfiguration FromBuildProperties()
    {
        var values = (Assembly.GetEntryAssembly() ?? typeof(GlideUpdateConfiguration).Assembly)
            .GetCustomAttributes<AssemblyMetadataAttribute>()
            .ToDictionary(attribute => attribute.Key, attribute => attribute.Value, StringComparer.Ordinal);
        values.TryGetValue("GlideUpdateFeedUrl", out string? feedUrl);
        values.TryGetValue("GlideUpdatePublicKey", out string? publicKey);
        return new GlideUpdateConfiguration(feedUrl, publicKey);
    }

    public bool IsValid => ValidationError is null;

    public string? ValidationError
    {
        get
        {
            if (!Uri.TryCreate(FeedUrl, UriKind.Absolute, out Uri? uri) || uri.Scheme != Uri.UriSchemeHttps)
            {
                return "The update feed must be a configured HTTPS URL.";
            }

            if (string.IsNullOrWhiteSpace(PublicKey))
            {
                return "The update feed public key is not configured.";
            }

            try
            {
                return Convert.FromBase64String(PublicKey).Length == 32
                    ? null
                    : "The update feed public key is not a 32-byte Ed25519 key.";
            }
            catch (FormatException)
            {
                return "The update feed public key is not valid Base64.";
            }
        }
    }
}

namespace Glide.Tests;

public sealed class DesktopEngineArchitectureTests
{
    [Fact]
    public void CliAndWpf_DelegateProtocolLoopsToSharedDesktopRuntime()
    {
        string root = ResolveRepositoryRoot();
        string cliPath = Path.Combine(
            root,
            "desktop",
            "src",
            "Glide.App",
            "Program.cs");
        string wpfPath = Path.Combine(
            root,
            "desktop",
            "windows",
            "Glide.Windows.UI",
            "ViewModels",
            "MainViewModel.cs");

        string cli = File.ReadAllText(cliPath);
        string wpf = File.ReadAllText(wpfPath);

        Assert.Contains("DesktopTransferEngine", cli);
        Assert.Contains("DesktopTransferRuntime", wpf);

        Assert.DoesNotContain("new LanRecordChannel", cli);
        Assert.DoesNotContain("new LanRecordChannel", wpf);
        Assert.DoesNotContain("MessageTypes.ItemStart", cli);
        Assert.DoesNotContain("MessageTypes.ItemStart", wpf);
        Assert.DoesNotContain("File.ReadAllBytesAsync", cli);
        Assert.DoesNotContain("File.ReadAllBytesAsync", wpf);
    }

    [Fact]
    public void WpfPersistentTrust_RequiresExplicitVerifiedFingerprintConsent()
    {
        string root = ResolveRepositoryRoot();
        string viewModelPath = Path.Combine(
            root,
            "desktop",
            "windows",
            "Glide.Windows.UI",
            "ViewModels",
            "MainViewModel.cs");
        string viewPath = Path.Combine(
            root,
            "desktop",
            "windows",
            "Glide.Windows.UI",
            "Views",
            "MainWindow.xaml");

        string viewModel =
            File.ReadAllText(
                viewModelPath);
        string view =
            File.ReadAllText(
                viewPath);

        Assert.Contains(
            "new DesktopPersistentTrustManager",
            viewModel);
        Assert.True(
            viewModel
                .Split(
                    "TrustManager: _trustManager",
                    StringSplitOptions.None)
                .Length - 1 >= 2,
            "Both WPF send and receive requests must carry the persistent trust manager.");

        Assert.Contains(
            "ConfirmPeerEnrollmentAsync",
            viewModel);
        string consentViewModel = File.ReadAllText(Path.Combine(
            root, "desktop", "windows", "Glide.Windows.UI", "ViewModels", "DesktopConsentViewModel.cs"));
        Assert.Contains(
            "_trustDecision",
            consentViewModel);
        Assert.Contains(
            "AcceptTrustEnrollmentCommand",
            consentViewModel);
        Assert.Contains(
            "RejectTrustEnrollmentCommand",
            consentViewModel);

        Assert.Contains(
            "IsTrustEnrollmentModalVisible",
            view);
        Assert.Contains(
            "TrustEnrollmentFingerprint",
            view);
        Assert.Contains(
            "Trust Device",
            view);
        Assert.Contains(
            "Not Now",
            view);
        Assert.Contains(
            "LocalIdentityFingerprint",
            view);
        Assert.Contains(
            "compare the full fingerprint",
            view,
            StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void SolutionAndEntrypoints_ReferenceSharedDesktopProject()
    {
        string root = ResolveRepositoryRoot();

        string solution = File.ReadAllText(
            Path.Combine(root, "desktop", "Glide.slnx"));
        string cliProject = File.ReadAllText(
            Path.Combine(root, "desktop", "src", "Glide.App", "Glide.App.csproj"));
        string wpfProject = File.ReadAllText(
            Path.Combine(
                root,
                "desktop",
                "windows",
                "Glide.Windows.UI",
                "Glide.Windows.UI.csproj"));

        Assert.Contains("Glide.Desktop/Glide.Desktop.csproj", solution);
        Assert.Contains("Glide.Desktop", cliProject);
        Assert.Contains("Glide.Desktop", wpfProject);
    }

    [Fact]
    public void WpfSendFlow_ChoosesOneMethodBeforeShowingTheSendAction()
    {
        string root = ResolveRepositoryRoot();
        string viewModel = File.ReadAllText(
            Path.Combine(root, "desktop", "windows", "Glide.Windows.UI", "ViewModels", "MainViewModel.cs"));
        string view = File.ReadAllText(
            Path.Combine(root, "desktop", "windows", "Glide.Windows.UI", "Views", "MainWindow.xaml"));

        int primarySendAction = view.IndexOf(
            "Content=\"{Binding SendActionText}\"",
            StringComparison.Ordinal);

        Assert.True(primarySendAction >= 0, "The chosen-device card must expose a send action.");
        Assert.Contains("Visibility=\"{Binding IsSendActionVisible", view);
        Assert.Contains("Quick share with QR code", view);
        Assert.Contains("Nearby device", view);
        Assert.Contains("Saved device", view);
        Assert.Contains("PairDevicesPage", view);
        Assert.Contains("ExpectedPeerFingerprintHex: _selectedTrustedDevice?.FingerprintHex", viewModel);
        Assert.Contains("StartHostedSendPairingAsync", viewModel);
        Assert.Contains("SendHostedAsync", viewModel);
        Assert.Contains("IsHostedSendQrVisible", viewModel);
        Assert.Contains("ExecuteSendFilesSafelyAsync", viewModel);
        Assert.Contains("QR code for receiving selected files", view);
        Assert.Contains("Scan with Glide on your phone", viewModel);
    }

    [Fact]
    public void WpfHostedSend_LeavesNoIdentityPromptBehindTheQrSheet()
    {
        string root = ResolveRepositoryRoot();
        string viewModel = File.ReadAllText(
            Path.Combine(root, "desktop", "windows", "Glide.Windows.UI", "ViewModels", "MainViewModel.cs"));
        string view = File.ReadAllText(
            Path.Combine(root, "desktop", "windows", "Glide.Windows.UI", "Views", "MainWindow.xaml"));

        Assert.Contains("IsPairModalVisible = false", viewModel);
        Assert.Contains("Confirm the device, then choose whether to remember it", viewModel);
        Assert.Contains("Consent.IsTrustEnrollmentModalVisible, Converter={StaticResource BoolToVis}", view);
        Assert.Contains("Panel.ZIndex=\"102\"", view);
        Assert.Contains("Consent.IsRecipientConfirmationModalVisible, Converter={StaticResource BoolToVis}", view);
        Assert.Contains("Panel.ZIndex=\"103\"", view);
    }

    [Fact]
    public void SenderHostedQr_BindsPersistentIdentityToTheTlsServerCertificate()
    {
        string root = ResolveRepositoryRoot();
        string engine = File.ReadAllText(
            Path.Combine(root, "desktop", "src", "Glide.Desktop", "DesktopTransferEngine.cs"));
        string authenticator = File.ReadAllText(
            Path.Combine(root, "desktop", "src", "Glide.Desktop", "DesktopTrustedSessionAuthenticator.cs"));

        Assert.Contains("localEndpointHostsTls:", engine);
        Assert.Contains("hostedInvitationAuthority != null", engine);
        Assert.Contains("localEndpointHostsTls", authenticator);
        Assert.Contains("? connection.LocalSpkiSha256", authenticator);
        Assert.Contains(": connection.RemoteSpkiSha256", authenticator);
    }

    private static string ResolveRepositoryRoot()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory != null)
        {
            if (Directory.Exists(Path.Combine(directory.FullName, ".git")) ||
                (Directory.Exists(Path.Combine(directory.FullName, "android")) && Directory.Exists(Path.Combine(directory.FullName, "desktop"))))
            {
                return directory.FullName;
            }

            directory = directory.Parent;
        }

        throw new DirectoryNotFoundException(
            "Unable to locate Glide repository root.");
    }
}

namespace Glide.Tests;

public sealed class DesktopEngineArchitectureTests
{
    [Fact]
    public void CliAndWpf_DelegateProtocolLoopsToSharedDesktopEngine()
    {
        string root = ResolveRepositoryRoot();
        string cliPath = Path.Combine(
            root,
            "windows",
            "Glide.App",
            "Program.cs");
        string wpfPath = Path.Combine(
            root,
            "windows",
            "Glide.Windows.UI",
            "ViewModels",
            "MainViewModel.cs");

        string cli = File.ReadAllText(cliPath);
        string wpf = File.ReadAllText(wpfPath);

        Assert.Contains("DesktopTransferEngine", cli);
        Assert.Contains("DesktopTransferEngine", wpf);

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
            "windows",
            "Glide.Windows.UI",
            "ViewModels",
            "MainViewModel.cs");
        string viewPath = Path.Combine(
            root,
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
        Assert.Contains(
            "_trustEnrollmentTcs",
            viewModel);
        Assert.Contains(
            "AcceptTrustEnrollmentCommand",
            viewModel);
        Assert.Contains(
            "RejectTrustEnrollmentCommand",
            viewModel);

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
            Path.Combine(root, "windows", "Glide.slnx"));
        string cliProject = File.ReadAllText(
            Path.Combine(root, "windows", "Glide.App", "Glide.App.csproj"));
        string wpfProject = File.ReadAllText(
            Path.Combine(
                root,
                "windows",
                "Glide.Windows.UI",
                "Glide.Windows.UI.csproj"));

        Assert.Contains("Glide.Desktop/Glide.Desktop.csproj", solution);
        Assert.Contains("Glide.Desktop\\Glide.Desktop.csproj", cliProject);
        Assert.Contains("Glide.Desktop\\Glide.Desktop.csproj", wpfProject);
    }

    private static string ResolveRepositoryRoot()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory != null)
        {
            if (Directory.Exists(Path.Combine(directory.FullName, ".git")) ||
                File.Exists(Path.Combine(directory.FullName, "settings.gradle.kts")))
            {
                return directory.FullName;
            }

            directory = directory.Parent;
        }

        throw new DirectoryNotFoundException(
            "Unable to locate Glide repository root.");
    }
}

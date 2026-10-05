# Linux UI rebuild verification — 2026-10-05

Source reviewed: `codex/linux-ui-rebuild` at `6a8836d75b75a5513c4de0bd706a04691a230247`. This is an audit of the implementation against `doc/planning/native-platforms/LINUX_UI_REBUILD_ANTIGRAVITY.md`, not a release approval.

## Result

**Incomplete.** The overlapping-page defect is repaired and the visual rebuild is substantial. Automated checks pass, but production consent can be bypassed with a command-line flag, live invitation material is written to a predictable temporary file, the final package is not installed in the Ubuntu VM, and the plan's responsive/accessibility/real-UI/package-lifecycle gates are not cleared.

| Gate | Evidence | Result |
|---|---|---|
| A0, V0 | `MainWindow.axaml` has per-page visibility bindings and selected navigation; rendered tests cover the single-visible-page behavior. `tools/check-architecture.ps1 -FailFast` passed 7/7. `dotnet test desktop/Glide.slnx -c Release --no-restore` passed 308 core/desktop and 1 Windows UI tests. | Passed for tested behaviors |
| A1–A4 | Dedicated Send, Receive, Pair, Activity, Settings views and shared resources exist. Captured screens show a coherent light/dark visual system and real transfer handlers. Compiled binding remains globally disabled in `Glide.Linux.UI.csproj`; page state remains in `LinuxMainViewModel`. | Substantial implementation; not all exit criteria verified |
| A5, V4 | Final-commit `.deb` has matching SHA-256 `08b3651c12e79c87f0f6845ddeb6a2d2d0f74611f50925dff0a0bd18c64b2715` and embeds the source commit. Its dependency metadata allows `libicu78 | libicu74`; `apt -s install` succeeds in Ubuntu 26.04.1. The VM's installed `/usr/lib/glide/SOURCE_COMMIT` is still `7a8afa1233ba4a0329c5c7d63110f4afb1373837`. The visible UI process runs from `/home/gdj/glide-bin`, not `/usr/lib/glide`. The final `.deb` is staged at `/home/gdj/Downloads/glide-current.deb` but installation needs the VM user's administrator password. Fresh install, launcher, upgrade, uninstall, and retained-data checks were not performed on the final package. | Blocked/open |
| V1 | Screenshots exist in this folder for major pages and dialogs. `responsive-760x520.png` shows clipped text in the Send intro/drop area and truncated Quick share content. Screenshots are not tied to an installed final-commit package. | Failed/open |
| V2 | Modal screenshots and some headless tests exist. No recorded keyboard-only or Orca walkthrough, High Contrast, large text, reduced motion, or 150% scaling evidence. | Open |
| V3 | `tools/test_two_way_transfers.py` uses loopback forwarding, `--auto-confirm`, and scripted process interaction. It does not demonstrate manual UI consent or Windows/Android physical LAN journeys. | Open |
| A6 | Graphify query still returned old `LinuxMainViewModel` locations after the view restructure. | Open |

## Release-blocking findings

1. **Production auto-consent:** `App.axaml.cs:68-70` accepts `--auto-confirm` without a Debug/test-build guard. `LinuxMainViewModel.cs:1115`, `:1133`, and `:1156` then approve recipient, offer, and persistent trust enrollment after a delay. This violates the explicit-consent requirement even when the default UI asks for approval. Remove the production flag and auto-approval paths; give automated tests a separate in-process interaction implementation that is absent from release binaries.
2. **Invitation secret persistence:** `LinuxMainViewModel.cs:1012` writes the complete live invitation URI to `Path.GetTempPath()/glide_active_invitation.uri` without a private file mode or cleanup. The test script reads and logs that URI, and passes it in process arguments. Remove the production temp-file write. Tests should acquire invitations through an isolated test seam and redact them in logs.
3. **Small-window layout:** Fix the clipping visible in `responsive-760x520.png`, then repeat the 760×520 matrix in the final installed package with long Unicode names and 150% scaling.
4. **Installed-package mismatch:** The staged final `.deb` must be installed by the VM owner through normal administrator authentication before V1/V4 can be credited. Do not enter credentials into chat or automation logs. Recheck `SOURCE_COMMIT`, launch from the desktop entry, single-instance behavior, upgrade/uninstall, and retained data.

The package was produced in WSL2, not on a native Ubuntu 26 build host as A5 specified. Dependency simulation and matching manifest are useful build evidence, but they do not replace a native build and package-lifecycle test.

## Next verification sequence

Remove findings 1–2, rebuild a new package and manifest from the corrected commit, update Graphify, and rerun architecture plus targeted tests. Then install that package in Ubuntu 26.04.1 and run V1/V2/V4 through the actual desktop launcher. Finally perform V3 manually against Windows and Android on a documented LAN, record consent screens and source/destination SHA-256, and keep each unavailable case open.

## Recovery follow-up

The original audit above is retained as historical evidence. Candidate `86894bd341ec8c266364ec269250caee4e364d45` contains the recovery changes merged into `main`:

- Production auto-confirm and mock-modal command-line paths are removed, as is the predictable live-invitation temporary file. The obsolete `tools/test_two_way_transfers.py` bypass harness is removed. Explicit recipient, offer, and trust decisions remain separate; cancellation declines pending dialogs and disposal is idempotent.
- Avalonia compiled bindings are enabled. Initial wrapping changes in `238fefd` did not resolve all 760×520 clipping in the VM. `installed-238fefd-clipping.png` records that failed attempt. `53681ff` additionally constrains horizontal scrolling, stretches the QR tab's content, and wraps selected-file actions. That later native visual result is **not yet verified**.
- Windows automated suites passed 310 desktop/core plus 1 Windows UI test. WSL Ubuntu 24.04 passed all 310 desktop/core tests. The 7 architecture checks passed. Headless layout assertions are supporting evidence, not a substitute for native text rendering.
- The code graph was refreshed through Graphify; AXAML/XML files are not supported by its AST extractor and remain source-review evidence.
- The VM installed and launched candidate `238fefd` from `/usr/lib/glide`. Final candidate `86894bd` was subsequently reinstalled, and its package/source-marker readback is documented in the [handoff report](MERGE_AND_UI_HANDOFF_2026-10-05.md).
- At the user's request, further UI inspection is reserved for human testing in the Ubuntu VM. Builds and automated checks run on Windows/WSL. WSL packaging is now the explicitly selected workflow; it is not evidence of a native Ubuntu 26 build.

V1's full theme/window/text matrix, V2 accessibility, V3 physical transfers, and V4 full install/upgrade/uninstall/retained-data lifecycle remain open. Neither merge nor package generation clears them.

# Native title-bar color — 2026-09-27

Related: [Windows ledger](WINDOWS_COMPLETION_LEDGER.md).

The Windows 11 caption now uses the window's active background and foreground brushes.
The window observes palette changes and Windows theme messages; dark/light caption-button
appearance derives from the background. High contrast restores system-owned caption colors.
Native resizing, caption controls, dragging and Snap layouts are retained.

The main window uses dynamic resources for its background and foreground. This change
supports both palettes at the caption boundary; it does not introduce a new app-wide theme
selector or claim that the currently dark-only desktop resource dictionary is a complete
light-mode design.

Validation:
- Full Release solution build: zero warnings and errors.
- A temporary WPF harness using the production helper rendered dark and light captions;
  both were visually inspected, including readable native caption buttons and text.
- Installed application screenshot confirmed its caption matches the dark app background.
- Local silent installation exited 0; installed executable matched the staged payload.
- Updated unsigned `build/distributions/windows/setup.exe` SHA-256:
  `39068D1493CE077D1A314909F4002E18A9FFF1189E016890E14AC006667FD556`.

API reference: [Microsoft DWM caption/text attributes](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute),
checked 2026-09-27. Caption colors are set-only attributes; visual validation was used
after an initial readback probe could not retrieve them.

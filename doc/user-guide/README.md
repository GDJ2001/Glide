# Glide v1.0.0 visual user guides

This folder contains two guides with identical section/figure numbers:
- [English user guide](USER_GUIDE_EN.md)
- [Mixed Sinhala–English user guide](USER_GUIDE_SI_MIXED.md)

## Visual asset provenance

The 7 SVG assets contain embedded **actual Glide screenshots** from the repository, with numbered badges and a matching legend. An SVG is maintained as the editable annotation master; each PNG is an exported lossless rendering.

| Output | Source screenshot | Platform |
|---|---|---|
| 01_home_discovery_annotated | `doc/release/store_assets/screenshots/phone/01_home_discovery.png` | Android |
| 02_file_picker_annotated | `doc/release/store_assets/screenshots/phone/02_file_selection.png` | Android |
| 03_active_transfer_annotated | `doc/release/store_assets/screenshots/phone/03_active_transfer.png` | Android |
| 04_receive_consent_annotated | `doc/testing/evidence/screenshots/glide_emulator_offer_incoming_multi.png` | Android |
| 05_activity_history_annotated | `doc/release/store_assets/screenshots/phone/05_transfer_history.png` | Android |
| 06_pair_devices_annotated | `doc/evidence/linux-ui/page-pair.png` | Ubuntu desktop |
| 07_settings_privacy_annotated | `doc/release/store_assets/screenshots/phone/06_settings_privacy.png` | Android |

**Generate PNGs from the repository root:**

```sh
python -m pip install cairosvg
python tools/generate-user-guide-assets.py
```

**Architecture verification** (requires PowerShell 7 and repository dependencies):

```powershell
pwsh -File tools/check-architecture.ps1 -FailFast
```

### Accuracy boundary

Screenshots came from existing store/UI evidence. They have not been re-captured from each exact v1.0.0 signed binary or verified on every platform. Seven representative screenshots cannot show every dialog, button, setting, error state and OS permission sheet. Both guides clearly document that limitation and list the additional release QA captures required.

The original request's `Apps` picker category, `Pause` button, `LAN PIN` input, `Wi-Fi Direct` toggle, and `Auto-accept trusted` toggle must **not** be depicted as present without real source/UI evidence.

### QA checklist

- [x] Seven authentic screenshot-backed SVG annotation masters
- [x] English guide with matching numbered badge explanations
- [x] Mixed Sinhala guide retaining English UI labels
- [x] PNG export source tool
- [x] Seven PNG exports generated and sample renderings visually reviewed
- [ ] Fresh screenshots for every release dialog/state and Windows pages
- [x] Architecture check passed in GitHub Actions (`pwsh -File tools/check-architecture.ps1 -FailFast`; run #43166227206)
- [ ] Pixel-level comparison against signed release distributions


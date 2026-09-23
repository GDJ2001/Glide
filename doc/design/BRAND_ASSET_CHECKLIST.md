# Brand delivery checklist and gap list
Checked2026-09-12. [Per-file inventory](BRAND_ASSET_INVENTORY.md) · [Canonical catalog](../../assets/index.html). No artwork generated or modified during planning.

| Deliverable | Status | Evidence / gap / next work |
| --- | --- | --- |
| Master logo | AVAILABLE |20 high-resolution PNG masters; raster, not editable vector |
| Horizontal logo | AVAILABLE | Color/inverse/black/white masters and256/512/1024 exports |
| Stacked logo | AVAILABLE | Same four variants; optional in ordinary app UI |
| Wordmark | AVAILABLE | Indigo/black/white masters and exports |
| Light/dark variants | AVAILABLE | Color/inverse and black/white; choose intended background |
| Launcher flat icon | PARTIAL | Android density PNGs exist; no integrated resources or mask testing |
| Adaptive foreground | MISSING | Needs faithful derived layer from accepted symbol and safe-zone review; do not regenerate |
| Adaptive background | MISSING | Existing indigo square is reference; native opaque background resource needed |
| Themed monochrome icon | PARTIAL | White/black symbol reference exists; Android monochrome layer and mask proof missing |
| Notification small icon | MISSING | Native single-color silhouette/alpha resource and status-bar legibility required |
| Splash icon | PARTIAL | Symbol available; SplashScreen-compatible resource/safe-zone integration absent |
| Play Store512 icon | AVAILABLE | Opaque export exists; final console validation/legal clearance still required |
| Editable vector master | MISSING | Recommended faithful tracing/export with human approval; no evidence an SVG already exists |
| Favicon/web icons | FUTURE | Existing PNG/ICO exports available; web app integration outside V1 |
| iOS/desktop icons | FUTURE | Existing export mappings/ICO available; no corresponding application |
| Social graphic | FUTURE | No dedicated social artwork found; optional |
| Play feature graphic | MISSING | Store marketing asset required before launch; separate approved design task |
| Store screenshots | MISSING | Must capture real implemented screens; no fabricated feature screenshots |
| Typography source/license | PARTIAL | Wordmark raster; no editable font file; UI uses system sans |
| Brand/color guide | PARTIAL | Existing prompt targets plus planned UI tokens; raster colors not calibrated |
| Trademark/name/store clearance | MISSING | Neither original prompt nor asset validation performs legal clearance |

Categories: existing and usable = logo/wordmark raster exports; existing but needs export/integration = launcher/themed/splash; referenced but missing = editable/native layers not found, not presumed never designed; recommended future = editable canonical vector and store materials; optional = stacked marketing/social; future = non-Android integrations.

GLIDE-036 integrates only faithful platform derivatives after approval of their visual proof; original master files remain unchanged. GLIDE-061 resolves store graphic/legal readiness. Missing assets do not block the early transfer proof.


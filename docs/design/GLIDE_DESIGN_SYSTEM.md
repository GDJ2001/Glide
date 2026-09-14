# Glide design system
Status: implementation specification grounded in accepted assets; no logo redesign. [Inventory](BRAND_ASSET_INVENTORY.md) · [Gaps](BRAND_ASSET_CHECKLIST.md) · [Flows](GLIDE_USER_FLOWS.md) · [UI gate](UI_QUALITY_CHECKLIST.md).

## Brand
The existing mark is two flowing forms separated by an ascending open channel, with a geometric Glide wordmark. Use committed horizontal color on light and inverse on dark backgrounds. Prefer symbol for tiny contexts; never reconstruct the wordmark with a font, change geometry, recolor master PNGs, add shadows to the logo or crop intrinsic clear space. Existing independent variants have slight geometry differences; select consistent files per context and flag concerns for human brand review.

Provisional additional clear space: at least one-quarter of visible symbol height on all sides, in addition to intrinsic image padding. Minimum visible symbol24dp in app UI, wordmark visible width80dp, horizontal lockup120dp; verify small-scale legibility rather than squeezing. App launcher uses opaque square basis only after adaptive-resource integration. White symbol is a reference for monochrome resources, not a finished Android notification drawable.

## Color tokens
These are new UI tokens consistent with the brief's art direction, not claims of exact raster color sampling. Do not modify artwork to match them.
| Role | Light | Dark |
| --- | --- | --- |
| Primary / onPrimary | #375BEE / #FFFFFF | #BBC6FF / #10256E |
| Primary container / onContainer | #E0E5FF / #10256E | #263D91 / #E0E5FF |
| Secondary / onSecondary | #465475 / #FFFFFF | #BDC7E8 / #24314E |
| Secondary container / onContainer | #DEE5FA / #151B38 | #364362 / #DEE5FA |
| Tertiary accent / onAccent | #006879 / #FFFFFF | #75D7EA / #003640 |
| Tertiary container / onContainer | #B4F0FA / #003640 | #004F5D / #B4F0FA |
| Background / onBackground | #F8F9FF / #151B38 | #0D1121 / #EDF0FF |
| Surface / onSurface | #F8F9FF / #151B38 | #111629 / #EDF0FF |
| Surface low / high | #F0F2FA / #E6EAF5 | #171E34 / #252D45 |
| Outline / outlineVariant | #69738C / #C5CDDF | #929DB8 / #3B4660 |
| Success / onSuccess | #126C45 / #FFFFFF | #80DBAD / #003921 |
| Warning / onWarning | #795400 / #FFFFFF | #EBC46A / #402D00 |
| Error / onError | #BA1A1A / #FFFFFF | #FFB4AB / #690005 |
| Information / onInformation | #2755A4 / #FFFFFF | #AAC7FF / #003063 |

Validate WCAG contrast of actual rendered token pairs before shipping; do not assume gradients or alpha composites pass from hex values alone. Cyan #22CBE5 is decorative brand accent, not small text on white. Semantic colors accompany text/icons. Derive semantic containers with tested tonal pairs in GLIDE-035; don't invent per-screen hex values.

Default branded color, system light/dark mode. Appearance offers System/Light/Dark and independent “Use device colors” toggle on API31+, off by default. Dynamic color replaces Material semantic roles; logo, spacing, typography, shapes and motion retain identity. Semantic success/warning/error remain contrast-reviewed. On older OS hide unavailable dynamic option with explanatory text.

## Typography, spacing and shapes
Use Android system sans (no external font download). Logo typography remains image artwork. Text uses sp and scalable line height; numbers use tabular figures where available.
| Role | Size / line height / weight |
| --- | --- |
| Display (rare onboarding) | 36 / 44 / 500 |
| Headline | 28 / 36 / 600 |
| Title large / medium | 22 / 28 / 600; 16 / 24 / 600 |
| Body / secondary metadata | 16 / 24 / 400; 14 / 20 / 400 |
| Label | 14 / 20 / 500 |
| Transfer percentage | 48 / 56 / 500; adapts at large font scale |
| Speed / file detail | 14 / 20 / 500; no critical text below12sp |

Spacing scale4,8,12,16,24,32,48,64dp. Compact page padding20dp, medium24dp, expanded32dp; card content16/24dp. Use8dp icon-label gap and16dp section-item gap. Main content max1200dp with readable detail column max640dp. Touch targets >=48dp, never shrink to match glyph.

Shapes: small controls/chips12dp, device/transfer cards20dp, sheets28dp top corners, dialogs28dp, primary button full rounded with min56dp height. Compact status chips are informational unless clearly labeled actions. Shared vector icons from a small licensed Material Symbols set, imported individually; avoid huge legacy icons-extended dependency. Never use brand symbol as generic send/cancel control.

## Depth, light and dark
Hierarchy comes from tonal surfaces and spacing. Home/discovery may use one subtle static blue/cyan ambient wash at <=8% opacity behind nontext decoration. Selected peer uses primary container +2dp elevation +outline and selected semantics. Active transfer progress may carry a narrow gradient stroke; track and phase text remain legible without it. Cards otherwise0–2dp, modal surfaces6dp. Dark mode uses layered navy surfaces, slightly lighter selected surface and restrained highlights, not black plus neon.

No full-screen blur, backdrop filters, animated shadows or continuous particle fields. Disable decorative discovery animation while backgrounded, under reduced-motion preference, or when no discovery is running. Performance wins over effects.

## Motion and haptics
| Event | Motion |
| --- | --- |
| Control press/selection | 80–120ms state change, small standard spring with no bounce overshoot |
| Card appearance / device discovered | 160–220ms fade +<=8dp travel; maintain list position/focus |
| Sheet/dialog | 220–300ms Material easing; focus moves to title |
| Navigation | 200–300ms relationship-preserving transition; predictive back driven by gesture progress |
| Pairing / connection | Phase crossfade180ms; no fake “secure” animation before confirmation |
| Transfer progress | Interpolate samples150–250ms, linear within known byte progress; never simulated increments |
| Completion | One240ms check transition, then static state |
| Failure | Calm160ms change, no shaking or repeated vibration |
| Discovery | Slow2.4s low-amplitude tonal breathing only while active; no radar implying measured distance |

Use standard Material easing (enter decelerating, exit accelerating, shared standard), not per-screen curves. Respect system animator scale0 and an in-app reduced motion override; motion-off still shows every state. No forced60Hz cap; animations follow display refresh. Haptics follow system touch feedback settings: light selection, one connect/accept confirmation and success; error only when immediate user attention warranted. Device-found haptic off by default to avoid repeated alerts; never use vibration alone to convey status.

## Adaptive and accessible
Compute window size/posture live, not device labels. Compact<600dp: bottom navigation Home/History/Settings, single pane. Medium600–839dp: rail, single or balanced detail pane when space permits. Expanded>=840dp: rail, list/detail history and peer/transfer panes with a coherent navigation back stack. Larger widths constrain content rather than stretching controls. Fold hinges separate panes; no key action across occlusion. Landscape/height constraints can override side-by-side layout. Support multi-window resize, external keyboard, mouse hover/focus, Enter/Space activation, Escape/back and logical Tab order.

TalkBack exposes device name plus availability, selected state and explicit actions; transfer progress announcements at phase change or10% increments at most, never5Hz. Pairing digits read individually with clear comparison instruction. Text scale200% and display scaling must preserve all actions through wrapping/scrolling. Support RTL; isolate filenames and codes safely in bidirectional text, keep numerical code order. Contrast >=4.5:1 normal text, >=3:1 large text/nontext controls. Color never sole status cue; focused controls clearly visible. Screen magnification and switch access must not require precise gestures. [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility) and [adaptive guidance](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps) inform this specification.


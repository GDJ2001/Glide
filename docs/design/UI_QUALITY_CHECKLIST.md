# UI quality gate
[Design system](GLIDE_DESIGN_SYSTEM.md) · [Flows](GLIDE_USER_FLOWS.md). Apply per major route and attach screenshots/video plus test/device details.

- [ ] Primary action and phase hierarchy obvious; no engineering jargon or fictitious progress.
- [ ] Spacing, typography, shapes and assets use documented tokens.
- [ ] Light, dark and dynamic modes have measured contrast and restrained depth.
- [ ] Edge-to-edge insets, IME, cutouts and system bars do not cover actions.
- [ ] Loading, empty, blocked, error, partial, cancelled and success states reviewed.
- [ ] File names wrap safely; long names, Unicode, RTL and200% text scale tested.
- [ ] TalkBack roles, traversal, code reading and throttled progress announcements correct.
- [ ] >=48dp targets; keyboard/mouse/switch access and visible focus work.
- [ ] Compact, landscape, tablet, fold, split-screen and resize retain state.
- [ ] Predictive back matches actual action; cancelled gesture has no side effect.
- [ ] Motion-off preserves meaning; active animations stop offscreen/background.
- [ ] No decorative blur/glow causes jank;60/90/120Hz paths profiled.
- [ ] Verification and offer consent remain separate and cannot be accidentally accepted.
- [ ] Cancel, retry, Open/Export and retained-file behavior match protocol/storage semantics.
- [ ] Existing brand art preserved; platform masks/themed/splash icons visually verified.

Failing accessibility, consent or misleading completion is a release blocker, not optional polish.


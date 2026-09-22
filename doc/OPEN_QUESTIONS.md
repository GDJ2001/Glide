# Open questions
Only unresolved facts; engineering defaults are already chosen. [Assumptions](ASSUMPTIONS.md) · [change control](development/CHANGE_CONTROL.md).

| Question | Why it matters | Best current assumption | How answered | Deadline / blocking |
| --- | --- | --- | --- | --- |
| Which actual phones/OEM/API levels are available? | Real radio and background proof | None inferred from IDE | Hardware inventory from test owner | GLIDE-017, blocks physical gate |
| Does19.5 STREAM meet private-I/O throughput target? | Large-file viability | STREAM selected pending benchmark | Controlled comparison | GLIDE-018, blocks M3 closure |
| Does pinned compiler/processor graph compile cleanly? | Foundation compatibility | Official supported versions selected | Clean Gradle/processor build | GLIDE-001, blocks foundation |
| Exact API32 Nearby permission behavior? | Official sample differs from platform boundary | Fine/coarse compatibility through32, nearby Wi-Fi33+ | SDK/merged manifest + physical test | GLIDE-009, blocks claimed API32 discovery |
| Final publisher-owned package ID and legal name clearance? | Immutable publication identity/name collision | dev.glide.app provisional only; name Glide retained | Owner/domain/Console/legal review | GLIDE-060/061, blocks public distribution |
| Artwork/application license and faithful native derivatives approved? | Redistribution/brand fidelity | Existing accepted art preserved | Owner review and visual proof | GLIDE-036/061, blocks final branding/release |
| Play account type and testing eligibility? | Closed-test and developer verification rules | Account-specific requirements apply | Console inspection | GLIDE-063, blocks production |
| Does private-library wording meet user expectations? | Uninstall can remove unexported received files | Explicit private destination + export | Usability review | GLIDE-043, blocks UX gate if misleading |
| CI permissions/billing and test-lab availability? | Remote evidence cannot be assumed | Local commands available after foundation | Actual workflow run/access check | GLIDE-003/057, blocks remote CI claim |

Do not turn these into unnecessary startup questions. Implement independent READY work, collect evidence at the named gate, and report true external blockers. Scope-changing answers require ADR updates.


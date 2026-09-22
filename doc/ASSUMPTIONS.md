# Assumptions register
[Open questions](OPEN_QUESTIONS.md) · [risks](RISK_REGISTER.md). Architecture assumptions are explicit, not implemented facts.

| ID | Assumption / reason | Confidence | Validation | Blocking? | Task |
| --- | --- | --- | --- | --- | --- |
| A01 | GMS devices acceptable for initial Android V1; fastest proof route | Medium | Product review and device availability | Before M3 acceptance | GLIDE-010/018 |
| A02 | STREAM can meet practical throughput with private I/O | Medium | Same-pair FILE comparison | Before M3 closure | GLIDE-018 |
| A03 | min26 is an acceptable compatibility/maintenance tradeoff | Medium | Library manifest/API26 install and intended device review | Foundation if dependency conflict | GLIDE-001 |
| A04 | AGP9.4/Kotlin2.4.20/processors resolve together | Medium | Clean compile with effective version report | Foundation | GLIDE-001 |
| A05 | User-facing private received library acceptable | Medium | Offer/result usability review | UX gate | GLIDE-023/043 |
| A06 | Two GMS physical phones available for proof | Unknown | Confirm actual hardware, not IDE catalog | Physical proof | GLIDE-017 |
| A07 | FGS connectedDevice fits shipped peer behavior | High design confidence | Manifest/policy/device validation | Background/release gate | GLIDE-028/033/061 |
| A08 | No persistent trust/resume/folders needed for public V1 | Medium | Explicit scoped release review | V1 scope acceptance | GLIDE-027/064 |
| A09 | Existing committed raster art is canonical | High, user instruction | Preserve checksums, human derivative review | No block to architecture | GLIDE-036 |
| A10 | No own backend/analytics required | High | Dependency/privacy review | Security gate | GLIDE-049 |
| A11 | Provisional dev.glide.app only for development | High as policy | Owner supplies final ID before public builds | Publication | GLIDE-060 |
| A12 | Local raw bytes/hash/JSON protocol supports future native clients | High design confidence | Independent fixtures now, second implementation later | Protocol tests now | GLIDE-006/072 |
| A13 | API32 Nearby permission behavior needs platform-compatible conservative grants | Medium | Physical31/32/33 test and SDK manifest inspection | Discovery validation | GLIDE-009 |
| A14 | Store account/legal rights can be finalized by owner | Unknown | Console and ownership evidence | Release | GLIDE-060/061/063 |

No market-share figures, universal speed, guaranteed background survival or legal clearance are assumed.


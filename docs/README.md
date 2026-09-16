# Glide documentation portal
Engineering blueprint and Gemini handoff. The original planning baseline was researched on 2026-09-12; application components have since been added. Read the current source audit in the next-cycle plan before relying on historical completion labels. Existing Glide branding is preserved.

## Start here
- [Current next cycle: LAN, QR pairing and Quick Share — first READY: LAN-001](planning/LAN_QR_QUICK_SHARE_MASTER_PLAN.md)
- [LAN authentication, framing and routing decision — ADR-0018](adr/ADR-0018-lan-qr-authentication-and-routing.md)
- [Master plan](GLIDE_MASTER_PLAN.md)
- [Roadmap](ROADMAP.md)
- [Original GLIDE task ledger](TODO_MASTER.md)
- [Task dependency graph](TASK_DEPENDENCY_GRAPH.md)
- [End-to-end workflow](GLIDE_END_TO_END_WORKFLOW.md)
- [Definition of done / quality gates](DEFINITION_OF_DONE.md)

## For Gemini developers
- [Antigravity / Gemini prompt for the current LAN/QR/Quick Share release](development/ANTIGRAVITY_GEMINI_LAN_IMPLEMENTATION_PROMPT.md)
- [Complete end-to-end development prompt](development/GEMINI_END_TO_END_DEVELOPMENT_PROMPT.md)
- [Implementation guide](development/GEMINI_IMPLEMENTATION_GUIDE.md)
- [Engineering rules](development/GEMINI_ENGINEERING_RULES.md)
- [Prompt templates](development/GEMINI_PROMPT_TEMPLATES.md)
- [Session handoff template](development/SESSION_HANDOFF_TEMPLATE.md)
- [Change control](development/CHANGE_CONTROL.md)
- [CI/CD plan](development/CI_CD_PLAN.md)

## Planning and evidence
- [Repository audit](planning/REPOSITORY_AUDIT.md)
- [Technology/version research](planning/TECHNOLOGY_RESEARCH.md)
- [Transport comparison](planning/TRANSPORT_RESEARCH.md)
- [Primary sources](planning/SOURCES.md)
- [Completeness/Gemini readiness audit](planning/PLANNING_COMPLETENESS_AUDIT.md)
- [Risks](RISK_REGISTER.md), [assumptions](ASSUMPTIONS.md), [open questions](OPEN_QUESTIONS.md)

## Architecture
- [Architecture and modules](architecture/GLIDE_ARCHITECTURE.md)
- [State and data models](architecture/STATE_AND_DATA_MODEL.md)
- [Failure model](architecture/GLIDE_FAILURE_MODEL.md)
- [Architecture decision records](adr/README.md)

## Protocol and Android
- [Glide Protocol V1](protocol/GLIDE_PROTOCOL_V1.md)
- [Permission matrix](android/PERMISSION_MATRIX.md)
- [Storage and background execution](android/STORAGE_AND_BACKGROUND.md)

## Design
- [Design system, motion, adaptive and accessibility](design/GLIDE_DESIGN_SYSTEM.md)
- [Screen inventory/user flows](design/GLIDE_USER_FLOWS.md)
- [Per-file brand inventory](design/BRAND_ASSET_INVENTORY.md)
- [Brand gaps/checklist](design/BRAND_ASSET_CHECKLIST.md)
- [UI quality checklist](design/UI_QUALITY_CHECKLIST.md)
- [Existing asset catalog](../asset/index.html)

## Security
- [Threat/privacy model](security/GLIDE_THREAT_MODEL.md)
- [Security checklist](security/SECURITY_CHECKLIST.md)

## Testing and performance
- [Test strategy](testing/GLIDE_TEST_STRATEGY.md)
- [Performance/transfer benchmarking plan](performance/GLIDE_PERFORMANCE_PLAN.md)
- [Performance checklist](performance/PERFORMANCE_CHECKLIST.md)

## Release and future
- [Release plan](release/GLIDE_RELEASE_PLAN.md)
- [Cross-platform path](future/CROSS_PLATFORM_PATH.md)

Original GLIDE task status lives in TODO_MASTER. Next-cycle LAN-001–033 status lives in section 26 of the LAN/QR/Quick Share plan. Protocol semantics live in their specification, design tokens in the design system, and rationale in ADRs. Read ADR-0018's precedence over the earlier LAN proposal. Follow links instead of inventing replacements.

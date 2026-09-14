# Task dependency graph

[TODO_MASTER](TODO_MASTER.md) is status authority. Arrows mean prerequisites, not optional suggestions. Future tasks69–72 are outside V1 and require separate scope authorization. Every edge below is generated from the task definitions at planning time; update both on change.

```mermaid
flowchart TD
 G1["GLIDE-001"]
 G2["GLIDE-002"]
 G3["GLIDE-003"]
 G4["GLIDE-004"]
 G5["GLIDE-005"]
 G6["GLIDE-006"]
 G7["GLIDE-007"]
 G8["GLIDE-008"]
 G9["GLIDE-009"]
 G10["GLIDE-010"]
 G11["GLIDE-011"]
 G12["GLIDE-012"]
 G13["GLIDE-013"]
 G14["GLIDE-014"]
 G15["GLIDE-015"]
 G16["GLIDE-016"]
 G17["GLIDE-017"]
 G18["GLIDE-018"]
 G19["GLIDE-019"]
 G20["GLIDE-020"]
 G21["GLIDE-021"]
 G22["GLIDE-022"]
 G23["GLIDE-023"]
 G24["GLIDE-024"]
 G25["GLIDE-025"]
 G26["GLIDE-026"]
 G27["GLIDE-027"]
 G28["GLIDE-028"]
 G29["GLIDE-029"]
 G30["GLIDE-030"]
 G31["GLIDE-031"]
 G32["GLIDE-032"]
 G33["GLIDE-033"]
 G34["GLIDE-034"]
 G35["GLIDE-035"]
 G36["GLIDE-036"]
 G37["GLIDE-037"]
 G38["GLIDE-038"]
 G39["GLIDE-039"]
 G40["GLIDE-040"]
 G41["GLIDE-041"]
 G42["GLIDE-042"]
 G43["GLIDE-043"]
 G44["GLIDE-044"]
 G45["GLIDE-045"]
 G46["GLIDE-046"]
 G47["GLIDE-047"]
 G48["GLIDE-048"]
 G49["GLIDE-049"]
 G50["GLIDE-050"]
 G51["GLIDE-051"]
 G52["GLIDE-052"]
 G53["GLIDE-053"]
 G54["GLIDE-054"]
 G55["GLIDE-055"]
 G56["GLIDE-056"]
 G57["GLIDE-057"]
 G58["GLIDE-058"]
 G59["GLIDE-059"]
 G60["GLIDE-060"]
 G61["GLIDE-061"]
 G62["GLIDE-062"]
 G63["GLIDE-063"]
 G64["GLIDE-064"]
 G65["GLIDE-065"]
 G66["GLIDE-066"]
 G67["GLIDE-067"]
 G68["GLIDE-068"]
 G69["GLIDE-069"]
 G70["GLIDE-070"]
 G71["GLIDE-071"]
 G72["GLIDE-072"]
 G1 --> G2
 G1 --> G3
 G2 --> G3
 G2 --> G4
 G2 --> G5
 G5 --> G6
 G6 --> G7
 G5 --> G8
 G7 --> G8
 G4 --> G9
 G5 --> G9
 G8 --> G10
 G9 --> G10
 G10 --> G11
 G5 --> G12
 G9 --> G12
 G5 --> G13
 G8 --> G13
 G7 --> G14
 G11 --> G14
 G12 --> G14
 G13 --> G14
 G4 --> G15
 G8 --> G15
 G14 --> G15
 G13 --> G16
 G15 --> G16
 G16 --> G17
 G17 --> G18
 G18 --> G19
 G19 --> G20
 G19 --> G21
 G12 --> G22
 G19 --> G22
 G13 --> G23
 G20 --> G23
 G16 --> G24
 G20 --> G24
 G23 --> G24
 G24 --> G25
 G19 --> G26
 G22 --> G26
 G20 --> G27
 G21 --> G27
 G23 --> G27
 G24 --> G27
 G25 --> G27
 G26 --> G27
 G27 --> G28
 G28 --> G29
 G13 --> G30
 G28 --> G30
 G30 --> G31
 G20 --> G32
 G29 --> G32
 G30 --> G32
 G29 --> G33
 G30 --> G33
 G31 --> G33
 G32 --> G33
 G33 --> G34
 G27 --> G35
 G35 --> G36
 G35 --> G37
 G10 --> G37
 G35 --> G38
 G11 --> G38
 G20 --> G38
 G35 --> G39
 G32 --> G39
 G24 --> G40
 G25 --> G40
 G35 --> G40
 G37 --> G41
 G38 --> G41
 G39 --> G41
 G40 --> G41
 G36 --> G42
 G41 --> G42
 G34 --> G43
 G42 --> G43
 G43 --> G44
 G44 --> G45
 G45 --> G46
 G46 --> G47
 G34 --> G48
 G48 --> G49
 G26 --> G49
 G30 --> G49
 G49 --> G50
 G47 --> G50
 G34 --> G51
 G43 --> G52
 G51 --> G53
 G52 --> G53
 G53 --> G54
 G50 --> G54
 G50 --> G55
 G54 --> G55
 G3 --> G55
 G30 --> G56
 G55 --> G56
 G47 --> G57
 G55 --> G57
 G54 --> G58
 G56 --> G58
 G57 --> G58
 G58 --> G59
 G59 --> G60
 G60 --> G61
 G36 --> G61
 G40 --> G61
 G60 --> G62
 G61 --> G62
 G62 --> G63
 G63 --> G64
 G64 --> G65
 G65 --> G66
 G66 --> G67
 G67 --> G68
 G68 --> G69
 G68 --> G70
 G68 --> G71
 G68 --> G72
```

Safe parallel examples after prerequisites: GLIDE-003/004; source access012 and receive store013 while discovery progresses; UI token work035 after MVP while background reliability proceeds; performance051/052 after their separate prerequisites. Shared coordinator, manifest, catalog and Gradle files require a single owner or sequential integration. Do not run multiple tasks against an unfrozen protocol contract.

Critical early path:001→002→005→006→007→008→010→011→014→015→016→017→018, with009/012/013 supporting prerequisites. This proves real transfer before full branding polish. All release gates converge at059 before signing/publication work. No future task is a shortcut around the Android V1 gate.


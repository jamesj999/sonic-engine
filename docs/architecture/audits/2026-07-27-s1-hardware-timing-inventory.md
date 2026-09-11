# Sonic 1 hardware-timing inventory

Date: 2026-07-27

## Scope and classification

This audit applies the five replay contracts from
[`2026-07-27-cross-game-hardware-timing-trace-contract.md`](../designs/2026-07-27-cross-game-hardware-timing-trace-contract.md)
to the Sonic 1 disassembly. It distinguishes main-loop admission (`LAG`), ordering within
an admitted frame (`PHASE`), persistent native work (`NATIVE_SERVICE_QUEUE`),
hardware-relative state that predates a trace (`INITIAL_BASE`), and presentation-only
evidence (`DIAGNOSTIC_ONLY`).

Sonic 1 has a persistent PLC queue, but this audit does not promote it to a version-1
hardware-timing event kind. A prepared entry advances by a fixed budget in the selected
VBlank routine: three patterns in the level handler and nine patterns in the title,
title-card, ending, and fade handlers
(`docs/s1disasm/sonic.asm:775-784,860-870,909-967`). Immediate `QuickPLC` work and the
Nemesis, Kosinski, and Enigma decoders run synchronously on the 68000.

## Inventory

| ROM owner | service point | polled gate | main loop admitted while pending | gameplay consumer | existing replay symptom | disposition |
|---|---|---|---|---|---|---|
| VBlank dispatcher and `v_vblank_routine` | The VBlank vector selects a handler, clears the requested routine, and increments `v_vblank_count` after the handler (`docs/s1disasm/sonic.asm:635-700`). | `WaitForVBla` waits for the requested VBlank routine to be cleared (`docs/s1disasm/sonic.asm:1779-1786`). | No. A missed admission uses `VBlank_Lag`, whose limited work does not execute the gameplay loop (`docs/s1disasm/sonic.asm:709-745`). | Every game mode whose outer loop requests VBlank. | Already represented by raw lag/admission; a second completion stream would duplicate it. | `LAG` |
| `v_plc_buffer` and `v_plc_patternsleft` | `RunPLC` starts the next queued entry only when no pattern remains; `ProcessPLC` resumes decoder state and removes entries when their pattern count reaches zero (`docs/s1disasm/sonic.asm:1376-1515`). | Consumers test the queue head or buffer for zero. | Yes. Level service advances three patterns per serviced VBlank while objects and gameplay continue (`docs/s1disasm/sonic.asm:860-870`). | Title cards, results, game-over flow, special-stage results, and the final boss all poll the queue; the complete list is below. | There is no dedicated recorded completion boundary and no general engine PLC service queue. Current replay does not reproduce this lifecycle; Final Zone has only a narrow admitted-frame countdown. | `NATIVE_SERVICE_QUEUE_PENDING_REVIEW` |
| PLC list selected by `QuickPLC` | `QuickPLC` walks every entry and calls the Nemesis decoder before returning (`docs/s1disasm/sonic.asm:1519-1543`). | Return from the call. | No. | Synchronous art setup callers. | Any elapsed VBlank is represented as admission lag, not an asynchronous completion. | `LAG` |
| Nemesis, Kosinski, and Enigma decoder call sites | The 68000 executes the decoder to its return (`docs/s1disasm/sonic.asm:1828-1981`; `docs/s1disasm/_inc/Decompression/Nemesis Decompression.asm`; `docs/s1disasm/_inc/Decompression/Kosinski Decompression.asm`; `docs/s1disasm/_inc/Decompression/Enigma Decompression.asm`). | Return from the decoder. | No. | Level, object-art, mapping, and layout setup. | Long setup intervals appear as raw lag; no independently polled ROM readiness flag exists. | `LAG` |
| Palette fade step and PLC service | Each fade iteration requests VBlank, runs the next colour step, and calls `RunPLC` (`docs/s1disasm/_inc/Palette Fading.asm:45-48,139-142,229-232,318-321`). | Fixed fade counter and VBlank acknowledgement. | No ordinary gameplay loop; the fade loop itself advances. | Fade-in, fade-out, white-in, and white-out mode transitions. | Replay needs the correct admitted-frame ordering, not a codec-completion event. | `PHASE` |
| Controller ports sampled by VBlank handlers | The level and special-stage VBlank routines call `ReadJoypads` before screen transfers (`docs/s1disasm/sonic.asm:812-835,879-900`); title-card, ending, and continue handlers do likewise (`docs/s1disasm/sonic.asm:909-995`). | The next game-mode loop consumes the published press/held bytes. | Yes, after the VBlank handler returns. | Player control, menus, title cards, special stage, and continue flow. | Input visibility is an ordering property already captured by raw phase and input state. | `PHASE` |
| Special-stage loop and its VBlank handler | The special-stage loop requests its dedicated VBlank routine, while that handler samples controllers and transfers special-stage display state (`docs/s1disasm/sonic.asm:879-900,3301-3302`). | VBlank acknowledgement. | The special-stage loop is admitted after the service phase; no separate hardware queue is polled. | Special-stage movement and rendering. | Ordering differences are phase/admission differences. | `PHASE` |
| `v_vblank_count` | The dispatcher increments the byte once after each handled VBlank (`docs/s1disasm/sonic.asm:651-684`). | Objects read the low bits directly for animation, sound, and periodic behavior. | Yes. | Rings, animals, chained stompers, prison capsule, bosses, results cards, continue objects, and several badniks (`docs/s1disasm/_incObj/25, 37 Rings.asm:322`; `docs/s1disasm/_incObj/sub BossDefeated & BossMove.asm:7`; `docs/s1disasm/_incObj/31 MZ Chained Stompers.asm:230-288`). | A trace segment can begin with a nonzero counter inherited from earlier hardware time. | `INITIAL_BASE` |
| VDP status and transfer completion | Synchronous display setup waits or transfers finish before their caller continues; recurring display transfers run inside the selected VBlank handler (`docs/s1disasm/sonic.asm:709-1115`). | VDP status/handler return, with no ordinary gameplay readiness flag. | No for synchronous waits; yes only after the VBlank phase completes. | Display setup and presentation. | A synchronous overrun is lag; transfer ordering is phase. No gameplay completion identity is exposed. | `PHASE` |
| Z80 bus ownership and sound-driver work | Startup waits for bus ownership and loads the driver (`docs/s1disasm/sonic.asm:241-252`); the stop/start macros poll the Z80 bus grant (`docs/s1disasm/Macros.asm:100-132`); VBlank handlers bracket sound work (`docs/s1disasm/sonic.asm:812-835`). | Bus-grant status or return from synchronous setup. | No during a synchronous bus wait; normal gameplay resumes after VBlank sound service. | Audio command delivery and DAC-driver setup. | Bus stalls contribute admission lag. Audio-chip presentation has no gameplay readiness consumer. | `LAG` |
| Raster, sprite, and audio presentation state | VBlank transfers sprite/table state and runs sound service (`docs/s1disasm/sonic.asm:775-1115`). | No gameplay lifecycle poll. | Yes. | Visual and audio presentation only. | Useful when diagnosing a mismatch, but not completion authority. | `DIAGNOSTIC_ONLY` |

## Ordinary PLC polling while gameplay continues

These are the ROM consumers that make the S1 PLC queue structurally different from
synchronous decompression. Their containing loop or object scan continues to execute while
the queue remains nonzero.

| ROM consumer | poll and admitted work | classification |
|---|---|---|
| Level-select loop | Calls `RunPLC`, tests `v_plc_buffer`, and continues controls/display work while queued art remains (`docs/s1disasm/sonic.asm:2195-2203`). | `NATIVE_SERVICE_QUEUE` |
| Level title-card loop | Waits for VBlank, executes objects and sprite building, calls `RunPLC`, then tests the PLC buffer (`docs/s1disasm/sonic.asm:2811-2839`). | `NATIVE_SERVICE_QUEUE` |
| Special-stage results loop | Continues its results loop and calls `RunPLC` until the queue drains (`docs/s1disasm/sonic.asm:3400-3410`). | `NATIVE_SERVICE_QUEUE` |
| Credits loop | Continues credits processing around `RunPLC` and the queue test (`docs/s1disasm/sonic.asm:3877-3886`). | `NATIVE_SERVICE_QUEUE` |
| Level-results card | Its object routine waits for the PLC buffer to clear before advancing the card lifecycle (`docs/s1disasm/_incObj/3A Got Through Card.asm:28-31`). | `NATIVE_SERVICE_QUEUE` |
| Game-over card | The object scan continues while the object waits for queued art (`docs/s1disasm/_incObj/39 Game Over.asm:17-20`). | `NATIVE_SERVICE_QUEUE` |
| Special-stage results and emerald object | The object remains in its readiness routine while other objects continue (`docs/s1disasm/_incObj/7E, 7F Special Stage Results and Chaos Emeralds.asm:29-32`). | `NATIVE_SERVICE_QUEUE` |
| Final-zone boss | The boss object polls the queue before entering the next art-dependent routine (`docs/s1disasm/_incObj/85,84,86 Boss - FZ.asm:123-133`). | `NATIVE_SERVICE_QUEUE` |
| Unused special-stage entry object | The dormant object has the same poll shape but is not a production gameplay consumer (`docs/s1disasm/_incObj/4A Unused - Special Stage Entry.asm:19-22`). | `DIAGNOSTIC_ONLY` |

The title loop also submits PLC work while its timer and objects continue
(`docs/s1disasm/sonic.asm:2065-2072`), but it does not expose a distinct readiness poll.
Submission alone is insufficient completion authority.

## Version-1 conclusion

| candidate | version-1 status | evidence needed for any later promotion |
|---|---|---|
| `KOS_MODULE_QUEUE` | Sole authoritative version-1 kind, owned by the cross-game design; Sonic 1 does not produce it. | None for this audit. |
| Sonic 1 PLC queue | `NATIVE_SERVICE_QUEUE_PENDING_REVIEW`. | Proof that ROM submissions plus selected interrupt handler, lag/phase, preparation bubbles, and HBlank deferral predict every empty edge; explicit disposition of the retail preparation race; and, only if native prediction fails, stable submission identity and a recorder boundary that cannot alias different queued work. |
| Synchronous decoders, VDP waits, and Z80 bus waits | Not an event-kind candidate. | A future proposal would first need an explicit persistent ROM readiness poll while the ordinary loop remains admitted; none is present here. |

No Sonic 1 mechanism adds an authoritative hardware-timing kind in schema version 1.

## 2026-07-28 PLC evidence status

The Task 1 probe records `AddPLC`/`NewPLC`, retail `RunPLC` preparation,
three- and nine-pattern service, and the queue shift with execute hooks. The
head is `0xFFF680`, destination `0xFFF684`, `PatternsLeft` `0xFFF6F8`, and
the per-service counter `0xFFF6FA`; the retail buffer has sixteen six-byte
slots and no overflow protection. `f_doupdatesinhblank` controls the
water-split deferral path. The retail source deliberately publishes
`PatternsLeft` before `NemDec_BuildCodeTable`, so this remains a race that
must be observed rather than normalized to the `FixBugs` ordering.

The present disposition remains `NATIVE_SERVICE_QUEUE_PENDING_REVIEW`; the
capture gate is recorded as `EVIDENCE_INCOMPLETE` in
[`2026-07-28-s1-s2-plc-readiness-evidence.md`](../research/trace/2026-07-28-s1-s2-plc-readiness-evidence.md).

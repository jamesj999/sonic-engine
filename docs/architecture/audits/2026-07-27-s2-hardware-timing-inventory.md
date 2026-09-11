# Sonic 2 hardware-timing inventory

Date: 2026-07-27

## Scope and classification

This audit applies the five replay contracts from
[`2026-07-27-cross-game-hardware-timing-trace-contract.md`](../designs/2026-07-27-cross-game-hardware-timing-trace-contract.md)
to the Sonic 2 disassembly. The classifications are `LAG`, `PHASE`,
`NATIVE_SERVICE_QUEUE`, `INITIAL_BASE`, and `DIAGNOSTIC_ONLY`.

The persistent Sonic 2 PLC/DPLC queue is resumed by selected interrupt service: six patterns in
`ProcessDPLC` and three in `ProcessDPLC2`
(`docs/s2disasm/s2.asm:2202-2289`). `RunPLC_ROM` is the synchronous immediate path
(`docs/s2disasm/s2.asm:2299-2318`). Neither path becomes a version-1 authoritative event
kind.

## Inventory

| ROM owner | service point | polled gate | main loop admitted while pending | gameplay consumer | existing replay symptom | disposition |
|---|---|---|---|---|---|---|
| VInt dispatcher, `Vint_routine`, and `Vint_Lag` | The dispatcher selects the handler, clears the request, and increments `Vint_runcount`; an unrequested interrupt runs the lag handler (`docs/s2disasm/s2.asm:481-543`). | `WaitForVint` waits until the request byte is cleared (`docs/s2disasm/s2.asm:3957-3963`). | No. A missed admission runs limited lag work instead of the ordinary gameplay loop. | Every game-mode loop synchronized to VInt. | Raw admission lag already represents the skipped loop. | `LAG` |
| PLC queue RAM and resumable Nemesis state | `RunPLC_RAM` starts queued work, and `ProcessDPLC`/`ProcessDPLC2` decode six/three patterns per selected handler (`docs/s2disasm/s2.asm:2148-2289`; `docs/s2disasm/s2.constants.asm:1469-1481`). | Gameplay routines test the PLC queue head/buffer for zero. | Yes. The level and special-stage interrupt paths resume three patterns while admitted loops continue (`docs/s2disasm/s2.asm:698-909`); some work can be HBlank-deferred. | Title cards, results, game-over flow, special-stage results, and the ARZ boss; complete list below. | The engine currently loads a requested PLC synchronously (`src/main/java/com/openggf/game/sonic2/Sonic2ObjectArtProvider.java:264-277`) and immediately refreshes object art from the zone event (`src/main/java/com/openggf/game/sonic2/events/Sonic2ZoneEvents.java:170-183`). It has no pending PLC queue/service model, so ROM poll intervals and their lifecycle delays are absent. | `NATIVE_SERVICE_QUEUE_PENDING_REVIEW` |
| PLC list selected by `RunPLC_ROM` | The routine decompresses each PLC entry synchronously before returning (`docs/s2disasm/s2.asm:2299-2318`). | Return from the call. | No. | Immediate art setup callers. | Elapsed hardware frames are admission lag. | `LAG` |
| Nemesis, Kosinski, Enigma, and Saxman decoder call sites | The 68000 decoder runs to return; Saxman is also used during sound-driver unpacking (`docs/s2disasm/s2.asm:1806-1815,2334-2605,91306-91430`). | Return from the decoder. | No. | Level/art/mapping setup and sound-driver initialization. | The long special-stage initialization interval is raw lag rather than external completion. | `LAG` |
| Palette fade step and PLC service | Each fade iteration requests VInt and calls `RunPLC_RAM` around its colour step (`docs/s2disasm/s2.asm:3278-3281,3376-3379,3477-3480,3576-3579`). | Fade counter and VInt acknowledgement. | No ordinary gameplay loop; the fade mode advances. | Fade-in, fade-out, white-in, and white-out transitions. | Ordering belongs to the phase contract. | `PHASE` |
| Controller ports sampled by VInt handlers | Level and special-stage handlers read controls before their display/DPLC service (`docs/s2disasm/s2.asm:698-909`); title-card and menu handlers provide their own sampling phase (`docs/s2disasm/s2.asm:1005-1148`). | The following mode loop consumes published held/press state. | Yes, after service returns. | Player movement, special stage, menus, and results flow. | Input visibility is phase, not external work completion. | `PHASE` |
| S2 special-stage initialization and recurring loop | Fade-out is followed by synchronous initialization, ten `VintID_S2SS` waits, one `VintID_CtrlDMA` fence, fade-in, then the recurring special-stage loop (`docs/s2disasm/s2.asm:6546-6690`). | VInt acknowledgement and explicit phase counters. | No during initialization; recurring special-stage frames are then admitted normally. | Special-stage setup, first object scan, display activation, and play. | The recorded pre-roll is explained by fade phases, admission lag during synchronous initialization, ten special-stage service waits, the control/DMA fence, and fade-in. | `PHASE` |
| `Vint_runcount` | Incremented after each serviced VInt (`docs/s2disasm/s2.asm:481-543`). | Gameplay code reads the inherited low bits. | Yes. | Animation, periodic object behavior, and timing effects. | A trace segment can inherit a nonzero hardware-relative counter. | `INITIAL_BASE` |
| DMA command queue and `VintID_CtrlDMA` | Producers append VDP commands to the DMA queue; VInt handlers drain it (`docs/s2disasm/s2.asm:1770-1792,698-1148`; `docs/s2disasm/s2.constants.asm:1185`). | The explicit control/DMA phase returns; ordinary gameplay has no queue-readiness poll. | No across the special-stage control fence; otherwise display work finishes in VInt before the admitted loop. | Special-stage display enable and recurring visual presentation. | The S2 special-stage first visible frame is a phase boundary, not a separately identified DMA completion. | `PHASE` |
| Z80 bus ownership, Saxman driver unpacking, and sound service | Stop/start macros poll bus grant (`docs/s2disasm/s2.macros.asm:85-94`); sound initialization unpacks and installs the driver synchronously (`docs/s2disasm/s2.asm:91306-91430`); the lag handler still services sound (`docs/s2disasm/s2.asm:529-543`). | Bus grant or synchronous return. | No during bus/setup waits; gameplay resumes after service. | Audio command delivery and sound-driver setup. | Bus/setup stalls are admission lag; chip output is presentation. | `LAG` |
| HInt/raster, sprite, and audio presentation state | Interrupt/display handlers publish visual tables and sound state (`docs/s2disasm/s2.asm:679-1181`). | No gameplay lifecycle readiness poll. | Yes after the interrupt phase. | Visual and audio presentation only. | Diagnostic evidence only. | `DIAGNOSTIC_ONLY` |

## Special-stage timeline reconciliation

The observed timeline in
[`s2-special-stage-init-timeline.md`](../research/trace/s2-special-stage-init-timeline.md)
does not require a new external-work kind:

| observed interval | ROM owner | replay contract |
|---|---|---|
| Initial observation and fade-out | Fade loop at `docs/s2disasm/s2.asm:3570-3582`, called from `docs/s2disasm/s2.asm:6546`. | `PHASE` |
| Synchronous special-stage initialization | Setup at `docs/s2disasm/s2.asm:6547-6640`, including synchronous decompression. | `LAG` |
| Ten special-stage display waits | Explicit loop at `docs/s2disasm/s2.asm:6644-6658`. | `PHASE` |
| First object scan and control/DMA fence | Object processing and `VintID_CtrlDMA` at `docs/s2disasm/s2.asm:6660-6666`. | `PHASE` |
| Fade-in and recurring play | Recurring loop at `docs/s2disasm/s2.asm:6674-6690`. | `PHASE` plus ordinary admission |

This agrees with the timeline's transition from the synchronous initialization gap into
the explicit ten-frame service sequence and the one-frame control/DMA fence. There is no
ROM-owned codec-complete state sampled by an otherwise continuing gameplay loop.

## Ordinary PLC polling while gameplay continues

The following production routines poll pending PLC work while their containing mode loop
or object scan continues:

| ROM consumer | poll and admitted work | classification |
|---|---|---|
| Level title-card loop | Executes objects and display construction around `RunPLC_RAM`, then waits for the buffer to clear (`docs/s2disasm/s2.asm:4914-4924`). | `NATIVE_SERVICE_QUEUE` |
| Special-stage results loop | Continues results processing and PLC submission/polling (`docs/s2disasm/s2.asm:6797-6807`). | `NATIVE_SERVICE_QUEUE` |
| Two-player results menu | Continues menu input/display work while testing pending PLC state (`docs/s2disasm/s2.asm:10798-10810`). | `NATIVE_SERVICE_QUEUE` |
| Game/Time Over object | The object remains in its readiness routine while the object scan continues (`docs/s2disasm/s2.asm:27670-27685`). | `NATIVE_SERVICE_QUEUE` |
| Level-results object | Card/ring-bonus lifecycle waits for PLC readiness without stopping the object loop (`docs/s2disasm/s2.asm:27781-27806`). | `NATIVE_SERVICE_QUEUE` |
| Special-stage results object | The results object tests the pending queue before advancing (`docs/s2disasm/s2.asm:28211-28215`). | `NATIVE_SERVICE_QUEUE` |
| ARZ boss object | The boss routine polls queued art while the level loop and other objects continue (`docs/s2disasm/s2.asm:64760-64787`). | `NATIVE_SERVICE_QUEUE` |

## Version-1 conclusion

| candidate | version-1 status | evidence needed for any later promotion |
|---|---|---|
| `KOS_MODULE_QUEUE` | Sole authoritative version-1 kind, owned by the cross-game design; Sonic 2 does not produce it. | None for this audit. |
| Sonic 2 PLC/DPLC queue | `NATIVE_SERVICE_QUEUE_PENDING_REVIEW`. | Proof that ROM submissions plus selected interrupt handler, lag/phase, preparation bubbles, and HBlank deferral predict every empty edge; explicit disposition of the retail preparation race; and, only if native prediction fails, stable submission identity and an unambiguous recorder boundary. |
| DMA queue and control/DMA phase | Not authoritative. | A later proposal would need an ordinary admitted gameplay consumer that polls a persistent hardware-owned readiness flag; the current use is phase/presentation. |
| Synchronous codecs and Z80/VDP waits | Not an event-kind candidate. | They would first need a persistent readiness lifecycle distinct from return/admission lag; none is present here. |

No Sonic 2 mechanism adds an authoritative hardware-timing kind in schema version 1.

## 2026-07-28 PLC evidence status

The Task 1 probe records `LoadPLC`/`LoadPLC2`, retail `RunPLC_RAM`
preparation, six- and three-pattern service, and the queue shift with execute
hooks. The head is `0xFFF680`, destination `0xFFF684`, `PatternsLeft`
`0xFFF6F8`, and the per-service counter `0xFFF6FA`; the retail buffer has
sixteen six-byte slots and no overflow protection. `Do_Updates_in_H_int`
identifies the HInt deferral path. As in S1, retail publishes `PatternsLeft`
before `NemDecPrepare`, so evidence must resolve the race rather than assume
the `fixBugs` build ordering.

The present disposition remains `NATIVE_SERVICE_QUEUE_PENDING_REVIEW`; the
capture gate is recorded as `EVIDENCE_INCOMPLETE` in
[`2026-07-28-s1-s2-plc-readiness-evidence.md`](../research/trace/2026-07-28-s1-s2-plc-readiness-evidence.md).

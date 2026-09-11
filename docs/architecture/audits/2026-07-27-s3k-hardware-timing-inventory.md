# Sonic 3 & Knuckles hardware-timing inventory

Date: 2026-07-27

## Scope and classification

This audit applies the five replay contracts from
[`2026-07-27-cross-game-hardware-timing-trace-contract.md`](../designs/2026-07-27-cross-game-hardware-timing-trace-contract.md)
to the in-scope Sonic 3 & Knuckles disassembly and both S3K recorder loops.

The essential distinction is between the direct Kosinski decompression queue and the
Kosinski module queue:

- `KOS_DECOMPRESSION_QUEUE` owns resumable 68000 decompression. Its count and busy bit
  describe direct decoder work. It remains live in schema version 1 and is
  authoritative in schema version 2.
- `KOS_MODULE_QUEUE` owns a sequence of modules whose decoded output is submitted to the
  DMA queue. Production objects and events poll its module count while the normal level
  loop continues. It is authoritative in schema versions 1 and 2.

The direct queue's promotion and shared-FIFO model were approved in
[`2026-07-28-s3k-kos-decompression-queue.md`](../designs/2026-07-28-s3k-kos-decompression-queue.md).
Schema 1 configures module completion as recorded and direct completion as
live. Schema 2 configures both kinds as recorded; neither schema permits trace
data to submit work or provide bytes.

All cited gameplay consumers below are within the audited scope. No excluded behavior was
used to derive the classification.

## Inventory

| ROM owner | service point | polled gate | main loop admitted while pending | gameplay consumer | existing replay symptom | disposition |
|---|---|---|---|---|---|---|
| VInt request and `Wait_VSync` | `Wait_VSync` publishes the requested routine and waits for VInt acknowledgement (`docs/skdisasm/sonic3k.asm:2976-2983`); VInt samples controllers, services DMA, and bookmarks resumable Kosinski state (`docs/skdisasm/sonic3k.asm:701-840`). | VInt routine acknowledgement. | No for a missed admission; the next level-loop iteration starts only after the wait returns. | All game-mode loops synchronized to VInt. | Raw lag and boundary phase already represent admission. | `LAG` / `PHASE` |
| Direct Kosinski queue: `Kos_decomp_queue_count` | `Queue_Kos` appends a direct job; `Process_Kos_Queue` sets the busy bit, resumes from the bookmark, and clears busy/decrements count when a stream completes (`docs/skdisasm/sonic3k.asm:2803-2967`). KosM children enter this same physical FIFO. | AIZ intro and ICZ transition test the direct queue count for zero (`docs/skdisasm/sonic3k.asm:104575-104590,110259-110287`). | Yes. `LevelLoop` runs direct-queue service before `Wait_VSync`, and admitted gameplay continues while work remains (`docs/skdisasm/sonic3k.asm:7884-7922`). | AIZ intro layout progression and ICZ act-transition progression. | Queue identity, retirement without a sampled zero, identical replacement, append/shift reconciliation, and cross-kind edge ordering are covered by the native recorder and Java/C# golden tests. | `KOS_DECOMPRESSION_QUEUE_AUTHORITATIVE_V2` |
| Kosinski module queue: `Kos_modules_left` | `Queue_Kos_Module` initializes or appends a module job; `Process_Kos_Module_Queue` sets bit 7 while a module is being decoded, waits for the direct queue to finish it, enqueues its DMA, decrements the module count, and advances the module queue (`docs/skdisasm/sonic3k.asm:2668-2791`). | Production consumers test `Kos_modules_left` for zero before art-dependent lifecycle transitions. | Yes. Normal objects run before module service in each admitted `LevelLoop` (`docs/skdisasm/sonic3k.asm:7884-7922`), and the recurring special-stage loop continues sprite processing, collision, and drawing before its readiness poll and module service (`docs/skdisasm/sonic3k.asm:10737-10753,12613-12625`). | Title card, results, special-stage emerald clearing, super-form support objects, transitions, walls/fans, and post-boss refresh flows listed below. | Title-card/ring lifecycle traces show completion-sensitive timing that is not explained by loop admission alone; the direction can differ across captures, which rules out a fixed delay. | `KOS_MODULE_QUEUE_AUTHORITATIVE_V1` |
| Phase-local module-queue loops | Title/load setup and special-stage-results modes call `Process_Kos_Module_Queue` explicitly within their own wait loop (`docs/skdisasm/sonic3k.asm:6459-6470,9721-9745,63073-63112`). | `Kos_modules_left == 0`. | No admitted gameplay loop; the dedicated phase loop advances until ready. | Title presentation, level-load block, and special-stage results. | Their elapsed time is preserved by admission and phase ordering; the queue kind remains the same, but these loops do not establish an admitted gameplay consumer edge. | `PHASE` |
| DMA command queue | `Add_To_DMA_Queue` appends commands and returns if the queue is full; `Process_DMA_Queue` drains and resets it during VInt (`docs/skdisasm/sonic3k.asm:1663-1769,701-840`). | No production gameplay routine polls a persistent DMA-complete owner. | Gameplay resumes after VInt service. | Visual publication, including module output after it has been decompressed. | DMA visibility can diagnose presentation differences, but the ROM lifecycle gates above poll the module queue, not DMA completion. | `DIAGNOSTIC_ONLY` |
| VDP busy status and synchronous transfer/setup paths | The 68000 polls VDP busy state synchronously during setup (`docs/skdisasm/sonic3k.asm:281-289`). | VDP status/return. | No. | Display setup. | Elapsed interrupts appear as admission lag; there is no ordinary continuing loop with a ROM readiness owner. | `LAG` |
| Delayed plane-row software state | Plane draw routines consume and decrement `Draw_delayed_rowcount` one software step at a time (`docs/skdisasm/sonic3k.constants.asm:450`; `docs/skdisasm/sonic3k.asm:103429-103590`). | Condition codes derived from the row counter. | Yes, where event handlers call the draw routine once per admitted iteration. | AIZ, SOZ, and ending/event draw progressions (`docs/skdisasm/sonic3k.asm:104588-104718,114651-114684,121043-121121`). | The owner is a deterministic software phase counter rather than asynchronous hardware completion. | `PHASE` |
| Controller sampling | VInt polls controllers before returning to the selected mode loop (`docs/skdisasm/sonic3k.asm:701-840`). | Subsequent code consumes the published input bytes. | Yes after VInt service. | Player, menus, and scripted control. | Input visibility is captured by the phase contract. | `PHASE` |
| `Level_frame_counter` and other hardware-relative seeds | The level loop increments the counter on admitted iterations before object processing (`docs/skdisasm/sonic3k.asm:7884-7922`). | Objects/events read its inherited value or low bits. | Yes. | Oscillation, periodic behavior, animation, and RNG-adjacent state. | A segment can begin after the counter has accumulated prior hardware time. | `INITIAL_BASE` |
| Z80 bus/audio and visual presentation | VInt brackets sound/driver access and publishes display state (`docs/skdisasm/sonic3k.asm:701-840`). | Bus/status return; no gameplay lifecycle completion poll. | Gameplay resumes after interrupt service. | Audio and visual presentation. | Useful as diagnostic evidence, but not completion authority. | `DIAGNOSTIC_ONLY` |

## Exact queue RAM ownership

The constants file enters a `phase $FFFF0000` RAM namespace
(`docs/skdisasm/sonic3k.constants.asm:283-284`). From that namespace and the adjacent
label layout (`docs/skdisasm/sonic3k.constants.asm:866,896-909`), the queue owners are:

| owner | 68000 address | BizHawk `mainmemory` address | interpretation |
|---|---:|---:|---|
| `Kos_decomp_queue_count` | `$FFFFFF0E` | `$FF0E` | Big-endian word. Bits 0-14 are queued direct streams; bit 15 is the direct decoder busy flag. |
| `Kos_decomp_queue` | `$FFFFFF40-$FFFFFF5F` | `$FF40-$FF5F` | Four-entry physical FIFO. Each eight-byte entry is a big-endian longword source followed by a big-endian longword destination. |
| `Kos_modules_left` | `$FFFFFF60` | `$FF60` | Byte. Bits 0-6 are modules remaining in the active module job; bit 7 marks its current module as busy in the direct decoder. |
| `Kos_last_module_size` | `$FFFFFF62` | `$FF62` | Big-endian word containing the active job's final module size in words; all preceding modules are `$800` words. |
| `Kos_module_queue` | `$FFFFFF64-$FFFFFF7B` | `$FF64-$FF7B` | Four-entry FIFO. `ds.w 3*4` allocates three words (six bytes) per entry, four entries total. |
| `Kos_module_source` | `$FFFFFF64` | `$FF64` | Longword alias for entry 0 offset 0. A newly initialized active job stores the payload pointer after its two-byte size header; module service then advances the field as decoding progresses. |
| `Kos_module_destination` | `$FFFFFF68` | `$FF68` | Word alias for entry 0 offset 4, initially the VRAM destination; module service advances the active head field after each DMA submission. |

The module layout is declared together at
`docs/skdisasm/sonic3k.constants.asm:904-909`. FIFO entry `i` starts at
`$FFFFFF64 + (i * 6)`: a four-byte source followed by a two-byte destination.
The recorder must snapshot all four entries, not only the active aliases, and use one
source-pointer convention before fingerprinting:

- The canonical source is always the **archive header address**, meaning the address of
  the two-byte uncompressed-size word that precedes the compressed module payload.
- When the queue is empty, `Queue_Kos_Module` branches directly to initialization with
  `a1` at that header. Initialization consumes `(a1)+` and stores the resulting `a1` in
  entry 0, so the first active RAM source is `canonical header + 2`
  (`docs/skdisasm/sonic3k.asm:2668-2671,2694-2715`). When a new ordinal first appears
  directly as the active entry, normalize exactly once as
  `canonical_source = observed_active_source - 2`.
- When work is appended, the ROM stores the original, unconsumed `a1` in the free slot
  (`docs/skdisasm/sonic3k.asm:2681-2684`). For entries observed in queued positions,
  normalize as `canonical_source = observed_queued_source` with no subtraction.
- When an appended ordinal later shifts to entry 0, retain the canonical header already
  recorded for that ordinal; do not fingerprint the rewritten active pointer. Likewise,
  once service advances entry 0, never re-normalize its progress pointer.

The stable fingerprint uses this canonical header address, the original destination,
compression variant, and ROM-derived module/size shape. Thus the same submitted work has
the same fingerprint whether it starts in an empty queue or first occupies an appended
slot. `Kos_last_module_size` and the initialized module count provide the active job's
size shape; queued jobs' equivalent shape is derived independently from the canonical ROM
header before activation. The canonical identity remains attached to its ordinal while
entry 0 mutates, and the remaining slot order reconciles a head shift.

Busy state is not a third independent RAM variable. It is encoded in the high bit of each
owner. `Process_Kos_Queue` sets and clears bit 15
(`docs/skdisasm/sonic3k.asm:2840-2847,2941-2942`), while
`Process_Kos_Module_Queue` sets and clears bit 7
(`docs/skdisasm/sonic3k.asm:2732-2752`).

The schema-2 native recorder mirrors the direct FIFO independently from the
module FIFO:

1. Count is always `Kos_decomp_queue_count & 0x7FFF`; occupied slots are never
   discovered by scanning for a zero sentinel.
2. A newly observed slot receives a per-direct-kind ordinal and an independently
   scanned standard-Kosinski fingerprint. Canonical destination retains all
   32 ROM bits, including sign-extended `$FFFFxxxx` RAM addresses.
3. Canonical slot identity survives bit-15 busy progress. A changed slot zero
   while the prior head is still busy is fatal.
4. A busy transition plus maximum suffix/prefix overlap reconciles every proven
   retirement, shift, and append. This covers unchanged-count replacement and
   adjacent identical descriptors without inventing or merging an ordinal.
5. Each proven retirement emits `KOS_DECOMPRESSION_QUEUE` at
   `pre_main_loop`. If a module parent also retires on that raw frame, the
   direct edge is written first and the module edge follows at `post_objects`.

Module-created child streams are ordinary direct submissions. They receive
direct ordinals and fingerprints, occupy the same four physical slots as
ordinary `Queue_Kos` work, and cannot be suppressed merely because their
parent has a module ordinal.

The recorder must mirror the ordered module queue rather than wait for a frame-observed
zero:

1. At each eligible `post_objects` sample, reconcile the ordered six-byte source/destination
   queue entries with the preceding snapshot. Assign every newly observed production
   submission its own per-segment ordinal and ROM-derived fingerprint, including adjacent
   submissions with identical fingerprints.
2. Bind the active head entry to the oldest mirrored ordinal and retain that identity
   while its low-seven-bit count decrements. The count initialization at
   `Process_Kos_Module_Queue_Init` derives and writes the active job's total
   (`docs/skdisasm/sonic3k.asm:2705-2715`).
3. Treat a previously observed final-module state—bit 7 set with low-seven-bit count
   one—as complete when the next module-service boundary retires that active head.
   The ROM clears bit 7, decrements one to zero, and enqueues the final DMA
   (`docs/skdisasm/sonic3k.asm:2750-2770`).
4. Detect retirement either as an empty head/count zero or as the queue shift to the next
   mirrored ordinal. With back-to-back work, the ROM shifts the remaining entries and
   immediately jumps to initialization, which rewrites `Kos_modules_left` to the next
   job's nonzero total before the service call returns
   (`docs/skdisasm/sonic3k.asm:2771-2787,2705-2715`). Therefore a zero sample is not
   required.
5. Emit one `KOS_MODULE_QUEUE` event for the retired ordinal/fingerprint, then activate the
   next mirrored ordinal at the same boundary. FIFO identity—not fingerprint uniqueness—
   keeps identical adjacent submissions distinct.

A busy-bit falling edge by itself is insufficient because bit 7 clears after every
decoded module. The eligible edge requires the tracked head's prior final-module state
and its retirement from the mirrored FIFO. Conversely, requiring
`Kos_modules_left == 0` would lose completions whenever initialization rewrites the count
for a following job in the same service call.

The direct transition is not emitted in schema 1. Schema 1 keeps direct jobs on
the live scheduler so committed fixtures remain loadable; schema 2 emits and
authorizes each proven direct retirement.

## Gameplay consumers

### Module queue consumers

The scan found the following in-scope consumers of `Kos_modules_left`. Internal service
checks inside `Process_Kos_Module_Queue` are omitted because they are queue implementation,
not gameplay consumers.

| consumer | ROM poll | effect while pending or on completion |
|---|---|---|
| SK title loop | `docs/skdisasm/sonic3k.asm:6459-6470` | Holds title progression until module art is ready. |
| Level-load block | `docs/skdisasm/sonic3k.asm:9721-9745` | Remains in synchronous load phase. |
| Recurring special-stage emerald clearing | `docs/skdisasm/sonic3k.asm:10737-10753,12613-12625` | The admitted loop continues sprites, special-stage collision, drawing, and other recurring work while `sub_9B62` polls readiness; completion advances the clear routine and emerald lifecycle. |
| Hyper-form stars | `docs/skdisasm/sonic3k.asm:34455-34490` | Defers star-object lifecycle until queued modules are ready. |
| Super Tails birds | `docs/skdisasm/sonic3k.asm:35005-35040` | Defers support-object lifecycle. |
| AIZ resize/battleship setup, first gate | `docs/skdisasm/sonic3k.asm:38985-39000` | Serializes new module submission behind pending work. |
| AIZ resize/battleship setup, second gate | `docs/skdisasm/sonic3k.asm:39105-39124` | Holds the next scripted setup state. |
| AIZ resize/battleship setup, third gate | `docs/skdisasm/sonic3k.asm:39216-39235` | Holds the next scripted setup state. |
| Title-card object | `docs/skdisasm/sonic3k.asm:62140-62171` | Defers art-dependent title-card creation. |
| Level-results object | `docs/skdisasm/sonic3k.asm:62545-62593` | Defers art-dependent result-card lifecycle after bonus setup. |
| Special-stage results | `docs/skdisasm/sonic3k.asm:63073-63112` | Holds the dedicated results phase. |
| HCZ horizontal water wall | `docs/skdisasm/sonic3k.asm:64854-64863` | Holds wall activation while the level loop continues. |
| HCZ vertical water wall | `docs/skdisasm/sonic3k.asm:65117-65131` | Pulls players upward while pending, then advances the wall routine. |
| HCZ large fan | `docs/skdisasm/sonic3k.asm:65604-65613` | Holds fan art-dependent lifecycle. |
| AIZ transition finish | `docs/skdisasm/sonic3k.asm:104670-104731` | Holds the transition's final art-dependent state. |
| HCZ act transition | `docs/skdisasm/sonic3k.asm:105718-105754` | Holds act-transition progression. |
| MGZ act transition | `docs/skdisasm/sonic3k.asm:106285-106314` | Holds act-transition progression. |
| CNZ teleporter | `docs/skdisasm/sonic3k.asm:108036-108067` | Holds teleporter progression. |
| SOZ act transition | `docs/skdisasm/sonic3k.asm:113758-113775` | Holds act-transition progression. |
| SOZ post-boss refresh | `docs/skdisasm/sonic3k.asm:114651-114684` | Serializes refresh/draw progression behind module readiness. |
| LRZ act transition | `docs/skdisasm/sonic3k.asm:115347-115374` | Holds act-transition progression. |
| DEZ act transition | `docs/skdisasm/sonic3k.asm:118660-118682` | Holds act-transition progression. |
| Ending transition, first gate | `docs/skdisasm/sonic3k.asm:120991-121012` | Holds ending progression. |
| Ending transition, second gate | `docs/skdisasm/sonic3k.asm:121100-121121` | Holds later ending progression. |

The ordinary level-loop consumers and recurring special-stage emerald consumer establish
why queue completion is external work rather than phase alone: gameplay scanning,
collision, and drawing are admitted while the count remains nonzero, and consumers
perform gameplay-visible work during that interval.

### Direct queue consumers

| consumer | ROM poll | effect | disposition |
|---|---|---|---|
| AIZ intro | `docs/skdisasm/sonic3k.asm:104575-104590` | Holds intro layout/event progression while `Kos_decomp_queue_count` is nonzero. | `KOS_DECOMPRESSION_QUEUE_AUTHORITATIVE_V2` |
| ICZ act transition | `docs/skdisasm/sonic3k.asm:110259-110287` | Holds transition progression until the direct queue count reaches zero. | `KOS_DECOMPRESSION_QUEUE_AUTHORITATIVE_V2` |

The engine independently submits the AIZ/ICZ descriptors and module children,
scans their ROM stream shape, and computes their identity. Recorded authority
can only release a matching prepared job; `decompressionsPending()` remains
owned by the production FIFO and consumers still perform their own mutations.

## Committed schema-1 consumer-boundary inventory

The following committed fixtures reach one of the two direct-count consumers
while declaring `hardware_timing_schema: 1`:

| fixture directory | boundary reached | compatibility status |
|---|---|---|
| `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun` | AIZ intro | Loadable; module edges remain authoritative, but direct completion is live and this fixture cannot certify the AIZ queue-empty boundary. |
| `src/test/resources/traces/s3k/aiz_completerun` | AIZ intro | Loadable; cannot certify the AIZ queue-empty boundary. |
| `src/test/resources/traces/s3k/icz_completerun` | ICZ1-to-ICZ2 act transition | Loadable; cannot certify the ICZ queue-empty boundary. |

`runs/s3-knux-multibonus-ss/aiz` begins at camera X `$1300`, after the AIZ
intro direct-count consumer; its later AIZ resumptions are also outside that
boundary. The checked inventory in `TestCommittedHardwareTimingFixtures`
asserts the exact three paths above, schema 1, and the absence of direct events.
The publication manifest's frozen hashes continue to guard every payload byte.
Publishing schema-2 replacements is a separate approval-gated task.

## Recorder frame and boundary ownership

Both recorder loops capture an emitted row at frame end, use the current `trace_frame` as
that row's `raw_frame`, perform semantic/object/auxiliary emission, increment
`trace_frame`, and only then call `emu.frameadvance()`:

- Standard recorder: row handling and increment at
  `tools/bizhawk/s3k_trace_recorder.lua:4521-4893`; outer loop at
  `tools/bizhawk/s3k_trace_recorder.lua:4940-4968`.
- Complete-run recorder: row handling and increment at
  `tools/bizhawk/s3k_complete_run_recorder.lua:5282-5793`; outer loop at
  `tools/bizhawk/s3k_complete_run_recorder.lua:5862-5912`.

Consequently:

- `raw_frame` is the segment-local frame-end observation index, not
  `emu.framecount()`.
- The movie/input mapping is `movie frame = bk2_frame_offset + raw_frame`, as documented
  by the standard recorder (`tools/bizhawk/s3k_trace_recorder.lua:39`) and used by both
  recorder end guards (`tools/bizhawk/s3k_trace_recorder.lua:4623-4626`;
  `tools/bizhawk/s3k_complete_run_recorder.lua:5519-5522`).
- A profile that records its arming frame maps that observation to `raw_frame = 0`. A
  reset-aware profile returns after arming and records the next emulator frame as zero;
  `bk2_frame_offset` intentionally remains the arming frame
  (`tools/bizhawk/s3k_trace_recorder.lua:4643-4655`;
  `tools/bizhawk/s3k_complete_run_recorder.lua:5480-5483`).
- Complete-run recording applies its level-family mode guard before polling/writing a
  level row (`tools/bizhawk/s3k_complete_run_recorder.lua:5488-5509`). That guard applies
  only to level segments. Special-stage timing uses the separate path below.

### Complete-run special-stage clock and sampling

The complete-run recorder arms each special-stage detour as its own structural segment.
`start_ss_segment` sets `bk2_frame_offset = emu.framecount()`, resets the segment-local
`trace_frame` to zero, and clears its special-stage observation state
(`tools/bizhawk/s3k_complete_run_recorder.lua:5145-5156`). Entry handling then returns
without writing a row. On each following special-stage continuation, `write_ss_row` writes
the current `trace_frame` as that row's frame and increments it exactly once at the end
(`tools/bizhawk/s3k_complete_run_recorder.lua:5182-5236`).

Therefore special-stage `raw_frame` ownership is:

- the special-stage segment's own `trace_frame`, independent of the preceding and
  following level-segment clocks;
- `raw_frame = 0` on the first continuation row after the arm-frame return, with
  `bk2_frame_offset` retaining the emulator frame at which the segment armed; and
- one increment per emitted special-stage row, performed only by `write_ss_row`.

Only the observation clock resets with `start_ss_segment`: the special-stage
`raw_frame` and its segment-local mirror baseline restart at zero. Production submission
identity does not. `KOS_MODULE_QUEUE` ordinals are monotonic across the whole structural
run, never reset at a level/special-stage boundary, and pending production work remains
owned by that same run-scoped ledger. A segment handoff verifies that every pending export
is explicitly exportable and has an exact kind/ordinal/fingerprint edge in the next
segment; non-exportable or unmatched work fails the handoff. Each retiring FIFO head
still consumes exactly one pending run ordinal.

The sampling call belongs in the special-stage continuation branch immediately before
`write_ss_row`, using the current `trace_frame` before that function increments it. The
branch is reached and returns before the normal level-row path
(`tools/bizhawk/s3k_complete_run_recorder.lua:5364-5373`), so applying the level-family
guard would suppress every special-stage completion. Both the FIFO observer and timing
stream write must occur before this early return; special-stage completion events are not
optional diagnostics and must not be omitted.

The design's three raw boundaries map to ROM visibility as follows:

| boundary | ROM work visible | module-completion rule |
|---|---|---|
| `vint_service` | Controller sampling, DMA draining, and `Set_Kos_Bookmark` in VInt (`docs/skdisasm/sonic3k.asm:701-840`). | VInt does not itself run module-queue service. A pending-to-complete transition first observable on a held-counter row is admitted here only when no ROM-owned object-scan loop is active. This is an admission/visibility boundary, not a claim that VInt caused decompression. |
| `pre_main_loop` | Direct decoder progress from `Process_Kos_Queue`, which precedes `Wait_VSync` in `LevelLoop` (`docs/skdisasm/sonic3k.asm:7884-7888`). | Schema 2 emits a proven direct-head retirement here. AIZ/ICZ screen events in the admitted loop can observe the resulting queue-empty state in that same scan. No module-parent completion is emitted at this boundary. |
| `post_objects` | Normal `Process_Sprites` executes before `Process_Kos_Module_Queue` (`docs/skdisasm/sonic3k.asm:7894-7908`). The held-counter title-card loop at `loc_62CC` has the same ordering, then branches on physical slot 8's `objoff_48` word or `Nem_decomp_queue` (`docs/skdisasm/sonic3k.asm:7735-7748`). The recurring special-stage loop likewise performs sprites, collision, drawing, and `sub_9B62` before module service (`docs/skdisasm/sonic3k.asm:10737-10753,12613-12625`). | Emit retirement here when the frame counter advances or the exact title-card lifecycle proves that the held-counter row ran `Process_Sprites`. The lifecycle can arm only from the fixed `Obj_TitleCard` parent and cannot be inferred from a Nemesis job alone. Consumers in that same scan saw the preceding pending state; they consume readiness on their next admitted scan. |

This ordering prevents the recorder from making completion visible to an object one
iteration too early or inventing a main-loop/object scan on a lag row. It also keeps event
identity independent of a particular capture: the event is keyed by submission ordinal
and ROM-derived fingerprint, not by an expected numeric frame. Recorder v6.37 refines the
held-`Level_frame_counter` symptom introduced in v6.36: genuine lag/loading rows remain
`vint_service`, while `loc_62CC` completions retain the loop's actual post-object boundary.

## Versioned conclusion

| candidate | status | remaining boundary |
|---|---|---|
| `KOS_MODULE_QUEUE` | `KOS_MODULE_QUEUE_AUTHORITATIVE_V1_V2`. | None for schemas 1–2; later schema changes still require review. |
| `KOS_DECOMPRESSION_QUEUE` | Live under schema 1; `KOS_DECOMPRESSION_QUEUE_AUTHORITATIVE_V2` under schema 2. | The three checked schema-1 consumer-crossing fixtures cannot certify AIZ/ICZ direct boundaries until an explicitly approved native schema-2 publication replaces them. |
| DMA queue | `NATIVE_SERVICE_QUEUE_PENDING_REVIEW` only if future ROM evidence reveals a gameplay completion poll; currently diagnostic. | A persistent hardware-owned readiness state and a production gameplay lifecycle consumer. |
| Delayed plane drawing | Not authoritative; deterministic `PHASE`. | Evidence of a hardware-owned completion fence distinct from the software row counter and admitted-loop cadence. |
| Synchronous VDP/Z80 waits | Not an event-kind candidate. | A persistent readiness lifecycle distinct from call return and admission lag; none is established here. |

No kind other than `KOS_MODULE_QUEUE` is authoritative in schema version 1.
Schema version 2 admits exactly `KOS_MODULE_QUEUE` and
`KOS_DECOMPRESSION_QUEUE`.

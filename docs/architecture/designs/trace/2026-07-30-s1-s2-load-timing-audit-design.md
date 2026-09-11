# S1/S2 PLC and Player-DPLC Load-Timing Audit Design

## Requirements

### Goals

- Every newly captured canonical S1/S2 level trace reports Nemesis PLC queue
  state on every stored physics row, including prefix and lag rows.
- Traces report player sprite-DPLC submissions and the ROM boundary at which
  their art transfer completes.
- Replay compares both event families against production-owned engine
  diagnostics at zero tolerance.
- Audit data remains comparison-only and cannot schedule, release, hydrate, or
  mutate gameplay, renderer, PLC, or DMA state.
- Regenerate the complete native-reproducible fleet, retain only fixtures with
  no BK2 source, run every replay class, and publish an exhaustive frontier
  report.

### Non-goals

- Do not turn S1/S2 PLC or DPLC work into `hardware_timing.jsonl` authority.
- Do not infer active PLC identity after the ROM has overwritten its descriptor
  with Nemesis decoder cursor state.
- Do not call the historical ROM `ProcessDPLC` routine a player-DPLC path; it
  services the Nemesis PLC queue.
- Do not make a trace drive rendering or repair an engine mismatch.

### Constraints and acceptance criteria

- S1 World REV01 and S2 World REV01 ROM addresses and callback opcodes are
  pinned by ROM-invariant tests.
- Canonical capture has no opt-in gate: level traces always advertise and emit
  PLC and player-DPLC audit capabilities.
- Legacy fixtures without either capability remain readable.
- Every advertised capability is complete for every stored physics row or
  trace loading fails.
- Sparse DPLC submissions/completions preserve callback order, same-frame
  submit/service, lag-forward publication, empty-entry reuse, and duplicate
  mapping-frame suppression.
- Isolation guards prove that audit parsing and comparison cannot reach
  gameplay mutation, renderer mutation, or hardware-authority admission.

## Exploration synthesis

`LoadQueueStateProjector.CaptureS1/CaptureS2` already projects the physical
Nemesis PLC FIFO from `$FFF680` and `$FFF6F8`. `TraceData`,
`TraceBinder.compareLoadQueues`, and production `QueueDiagnosticsProvider`
implement a strict comparison-only consumer. The capability is absent from
ordinary captures only because `Program.CommandLineOptions.LoadQueueState`
defaults false behind `--load-queue-state`.

The existing projection proves end-of-logical-frame FIFO order, preparation,
remaining pattern work, and retirement. It does not prove the sub-frame VBlank
call or populate schema-v1 `service_observations`. The engine already exposes
service observations, but schema v1 requires the trace array to remain empty.

Player DPLCs are separate:

- S1 `Sonic_LoadGfx` suppresses duplicate mapping frames with
  `$FFF766`, expands the selected ROM DPLC into the Sonic graphics buffer,
  raises `$FFF767`, and a VBlank variant transfers the buffer and clears the
  flag.
- S2 Sonic, Tails, and tails-tail update their `LastLoadedDPLC` byte, decode
  ROM DPLC runs, submit each run through `QueueDMATransfer`, and
  `ProcessDMAQueue` services the mixed DMA FIFO during VBlank.
- Existing CSV mapping-frame fields describe requested display state, not
  submitted or completed art. End-frame RAM sampling cannot recover all
  same-frame transfers.
- Engine DPLC application is currently coupled to rendering through
  `DynamicPatternBank`/`PlayerSpriteRenderer`; headless replay therefore needs
  a production runtime-owned diagnostic lifecycle rather than renderer calls
  from trace code.

Independent explorations agreed on separate PLC and player-DPLC event families,
ROM callback observation, strict comparison-only Java consumption, and
runtime-owned engine diagnostics. They rejected overloading the PLC queue kind
or deriving player transfers from mapping-frame samples alone.

## Architecture decision

### Ownership and boundaries

1. The native recorder owns ROM observation only.
2. Existing `load_queue_state` remains the PLC physical-state contract and is
   mandatory for every newly captured S1/S2 level row.
3. Metadata capability
   `dynamic_art_transfer_state_per_frame_v1` requires exactly one typed
   `dynamic_art_transfer_state` envelope per stored physics row. Its
   `edges` array may be empty, proving the observer was active even when no
   transfer occurred. The envelope also carries the ordered outstanding
   `transfer_id` list after all published edges.
4. Each lifecycle edge has a globally unique `edge_ordinal`, a stable
   `transfer_id` shared by its submission and optional completion,
   `phase`, `owner`, `mapping_frame`, normalized integral `logical_frame`
   and `logical_edge_index`,
   `publication_frame`, `terminal_forwarded`, `rom_callback_pc`, and an ordered
   `requests` list. `logical_frame` is a nonnegative segment-local source-frame
   index and `logical_edge_index` is a nonnegative, zero-based semantic edge
   sequence within that source frame. The pair is strictly increasing
   lexicographically; both ROM and engine derive it independently and reset it
   at the same segment boundary.
   `rom_callback_pc` is validated ROM evidence but is not compared to an engine
   address. Each request pins its
   address domain explicitly:
   `rom_source_address`, `ram_source_address`, `source_tile_index`,
   `vram_destination`, and `byte_length`; exactly one source-address domain is
   active and inapplicable fields are `-1`.
5. S1 changed-frame paths create an unpublished prepared batch after raising
   the pending flag. Its ordered requests decode every ROM DPLC run, but the
   preparation has no transfer id or lifecycle edge yet because
   `Sonic_LoadGfx` owns one staging buffer: another changed-frame path may
   overwrite it before VBlank. Such replacement supersedes the earlier
   preparation without fabricating a submission or completion. A verified
   VBlank pre-transfer probe with nonzero `f_sonframechg` promotes only the
   final prepared batch to one semantic submission and allocates its stable
   transfer id. Completion is the matching physical
   RAM-staging-buffer-to-VRAM transfer with separate
   `ram_source_address`, `vram_destination`, and `byte_length` fields; it does
   not pretend that one ROM request was the physical DMA source.
   The four REV01 VBlank variants require two callback classes. Pre-transfer
   probes at `$0D20`, `$0E34`, `$0F24`, and `$1030` may promote the final
   prepared batch and latch a nonzero `f_sonframechg`; they must never emit
   completion. A flagged probe without exactly one compatible preparation
   fails closed. Because BizHawk execute
   callbacks observe state before the instruction at the callback PC,
   completion is emitted only at `$0D50`, `$0E64`, `$0F54`, and `$1060`, the
   instructions following the DMA command and pending-flag clear. A completion
   additionally requires the matching latched pre-transfer probe, so the
   no-change branch cannot fabricate one. Both callback classes and the
   intervening transfer/clear windows are pinned in the ROM profile.
6. S2 observes `QueueDMATransfer` entry and return, but emits a submission only
   after proving the command was accepted and the command-buffer slot advanced.
   Queue-full attempts receive no transfer id and may be reported separately as
   rejected observations. Accepted commands for one mapping-frame decision are
   grouped into one ordered request batch. `ProcessDMAQueue` retires the exact
   pending batches. Caller/return-PC, ROM art spans, destination banks, and
   opcode windows jointly classify Sonic, Tails, and tails-tail; unrelated DMA
   remains excluded.
   Because VBlank may interrupt 68K code between a gated owner entry and its
   mapping probe/return, `ProcessDMAQueue` at `$14AC` may retire only ledger
   batches accepted before the interrupted decision while marking that
   incomplete pre-mapping gate VBlank-interrupted. This exception is valid
   only when no `QueueDMATransfer` call is active and the incomplete gate has
   accepted no request. A later matching mapping probe or return resumes and
   closes it normally. If neither occurs, only the next identical pinned entry
   may expire and replace the zero-request interrupted gate: owner, typed entry
   kind, and required caller/context latch must all match. A different owner or
   entry kind, context mismatch, active queue overlap, accepted current-
   decision work, or ambiguous replacement fails closed.
   Retail evidence is the S2 halfpipe run: deferred `ss-tails-tails` enters
   `$34AB0` at movie frame 15076; `$14AC` at 15078 retires prior transfer
   9419; no `$34AC4` mapping or `$34B1A` return follows; the next identical
   `$34AB0` at 15079 safely replaces the stale zero-request gate. Normal
   evidence at 15070 remains `$34AB0→$34AC4→$34B1A`.
   Each batch is opened and closed by verified entry/return windows around the
   owning Sonic, Tails, or tails-tail changed-frame DPLC decision routine.
   Accepted DMA commands collected inside that invocation form exactly one
   ordered batch. Repeated forced decisions receive distinct transfer ids even
   when owner and mapping frame match; a decision with no accepted command
   creates no submitted transfer.
   The REV01 level owner-decision profile has typed entry kinds. Normal Sonic
   `$1B848` and Tails `$1D1AC` entries derive the mapping frame from object
   state. Direct Part2 entries Sonic `$1B84E` and Tails `$1D1B2` derive the
   frame from CPU register `d0`; they are genuine player-art lifecycles for the
   same `sonic`/`tails` owners because verified pilot callers preload `d0` and
   jump into the same DPLC decoder and shared owner return. The observer must
   distinguish these entry kinds by pinned callback/caller evidence, never by
   source range or by accepting an unmatched shared return. Normal entries
   fall through the Part2 PC; while an object-state decision is already open,
   that callback is continuation only and must not open a second decision.
   A direct Part2 decision may open only after a separately pinned pilot-caller
   probe latched the `d0` preload/jump path and no owner decision is open.
   Tails-tails is independently profiled at `$1D184` with shared return
   `$1D1FE`; its object-state frame, suppression byte, accepted commands, and
   queue rejection are owned by `tails-tails`, not folded into Tails.
   S2 special-stage player-art DMA may source decompressed art from main RAM.
   Such submissions use the explicit RAM source domain and remain bounded by
   the verified special-stage owner decision plus accepted
   `QueueDMATransfer` return; normal level player-art requests remain in their
   pinned ROM source spans.
   Sonic and Tails converge on the special-stage shared decoder
   `$33ADA→$33B3E`, so wrapper callbacks are not the owner boundary. The typed
   shared-decoder entry classifies only the exact prepared context:
   `ss-sonic` has `a4=$F766`, `d4=$5CA0`, and `d1=0`; `ss-tails` has
   `a4=$F7DE`, `d4=$6000`, and `d1=$12`. The mapping frame is then read from
   `mapping_frame(a0)` at `$33ADE`. Any other context or unmatched return
   fails closed. This catches Sonic fallthrough and Tails' verified
   `$349EE→$33ADA` branch exactly once without relying on wrapper reachability.
   Special-stage Tails-tails remains an independent decision
   `$34AB0→$34B1A`, with mapping read at `$34AC4`, suppression `$F7DF`, and
   VRAM destination `$62C0`.
7. A game-owned production `DynamicArtDiagnosticsProvider` captures the engine
   lifecycle. Runtime animation/DPLC owners submit semantic transfers;
   rendering consumes resulting art state but does not own audit truth.
   `PlcFrameLifecycleCoordinator` owns the production iteration seam for both
   live `GameLoop` and headless `RecordingFrameDriver`: `claim(phase)` performs
   VBlank service from production phase state (including `LAG`), and
   `finish()` publishes the row after owner updates. Expected trace events
   never arm, step, lag, or populate the service.
   Read-only visual comparison observes publication only after
   `runLogicalIteration(...)` returns to `GameLoop`. The loop then invokes the
   value-free `TraceSessionLauncher.afterProductionIteration()` hook, and the
   launcher pulls an immutable snapshot through
   `GameServices.captureDynamicArtDiagnostics()`. The lifecycle service never
   retains a callback, listener, comparator, launcher, trace object, or any
   other reference capable of reaching comparison or segment mutation.
   Every immutable snapshot also carries a production-owned, service-lifetime
   delivery serial. Segment-local row numbers may restart at zero after a
   level-to-special-stage rebind, so freshness is determined only by this
   serial, never by comparing row numbers across segments. The serial is
   monotonic for the lifetime of the diagnostics service: `beginRun()`,
   `finishRun()`, and segment changes preserve it. It is delivery identity,
   not gameplay state, so rewind snapshots exclude it; rewind restore
   reconstitutes the restored payload with the current serial and emits
   nothing. Opening a segment likewise preserves the current serial in its
   empty snapshot. Publishing a row is the only operation that increments the
   serial, exactly once. Each snapshot also carries a production-owned,
   service-lifetime segment generation that increments only when a comparison
   segment opens. The launcher records that generation after the new
   production segment opens and binds its comparator. If a named run begins
   directly in a special-stage segment that is already the current open
   segment, arming its first visual row binds that already-open production
   generation instead; anticipated level-to-SS transitions still overwrite
   the target only after the new segment opens. Visual SS delivery
   requires an explicitly published snapshot plus all three identities: a
   delivery serial newer than the pre-iteration baseline, generation equality
   with the bound target segment, and row equality with the pending target
   row. Segment open installs an unpublished snapshot for its new generation;
   only actual row publication atomically installs `published=true`, serial,
   generation, and row for that same row. A terminal publication from the old
   segment may advance the serial and may share row zero with the new segment,
   but opening the new segment cannot turn that old publication into a valid
   mixed tuple. An empty or omitted SS publication therefore fails.
   End-of-run or teardown requested from inside the iteration body is deferred
   until this post-`finish()` hook drains the pending terminal row; teardown
   then closes the run deterministically.
   Run lifetime and comparison-segment lifetime are distinct. A run-active
   observer remains authoritative while a segment is closed; closed-segment
   decisions and completions journal immutable gap edges. Opening a segment
   resets only segment-local publication cursors. S1 requires an empty
   submitted-transfer ledger; an unpublished staging preparation may cross a
   named-run arm because it is production/native observer state, not a
   lifecycle transfer or trace-seeded descriptor. S2 may instead carry exact
   already-accepted `QueueDMATransfer` FIFO descriptors across a named-run arm.
   Their immutable initial-ledger descriptors and continuity fingerprint are
   comparison evidence only; production/native state already owns the queued
   work. Only the matching later `$14AC` service retires the same stable IDs.
   Transfer IDs, ordinals, pending FIFO, and native duplicate cursors are
   run-wide and rewind-atomic.
   Authoritative normal playable decisions originate from
   `PlayableSpriteAnimation` only when
   `LevelPlayableArtInitializer` injects a production decision capability.
   Generic Sonic/Tails mapping setters and `GhostTraceRenderer` never receive
   a mutating audit capability. `TailsTailsController` receives the same
   explicit production capability.
   S1 owner decisions update one production staging preparation; repeated
   decisions before coordinator `claim()` replace it without allocating an ID
   or publishing an edge. The claim promotes only the final preparation,
   submits and completes that transfer in ROM order, and clears the staging
   state. Rewind captures the unpublished preparation as production state;
   trace data cannot populate or select it.
   S2 owners submit into a production pending FIFO and never complete
   synchronously. A typed `DynamicArtDmaServiceModel` field in `GameRules`
   owns the game-wide semantic DMA-service policy and distinguishes
   ordinary logical-iteration claims from claims that represent the ROM's
   `ProcessDMAQueue` boundary. Only a service claim retires the previous FIFO
   in submission order before current-iteration decisions; non-service claims
   publish the unchanged FIFO. The policy consumes only production lifecycle
   phase/mode state. In the pinned halfpipe transition, rows 0–125 are
   production transition/fade claims and preserve transfer 8078; row 126 is
   the first production `SPECIAL_STAGE` service claim and retires it. No trace
   frame, expected edge, descriptor, or cursor may select a service claim.
   `PlcFrameLifecycleCoordinator` consumes the injected typed policy and
   contains no game-name check. The S2 model enumerates its ROM-equivalent
   service phases and fails closed for transition/fade or unsupported phases;
   the S1 model preserves its existing claim behavior.
   A structural named-run comparison-segment open is not a VBlank boundary and
   must never call the service or drain the FIFO. It preserves independently
   production-submitted S2 ledger/FIFO state across the arm and initializes
   the new segment's read-only publication baseline from that state. Only a
   subsequent real logical-iteration claim approved by the production
   DMA-service policy may retire work. Segment
   descriptors/fingerprints validate the resulting production snapshots but
   cannot create, identify, delay, or release the pending work.
   `Sonic2EndingCutsceneManager.update()` owns normal ending and direct-Part2
   pilot decisions, including repeated semantic pilot frames; draw methods
   consume prepared state and cannot mutate diagnostics.
   S2 special-stage DPLC plans are decoded and cached by
   `Sonic2SpecialStageDataLoader` from the supplied ROM (`$345FA` frame table,
   `$33AA2/$349B8/$34AA0` source sections). Production diagnostics never use
   Java copies of those runtime tables.
8. `TraceBinder` compares expected and actual transfer envelopes for each frame.
   No parser or comparator has a reference to a mutating runtime owner.

### Lifecycle and data flow

ROM callback → native pending FIFO → next stored non-lag row, with deterministic
lag forwarding → typed per-row envelope → strict fixture validation → replay
comparator → ordinary frontier field. Normalized logical source position and
publication row are independent immutable fields. Same-row edges retain
`edge_ordinal` order.
The recorder buffers one complete stored row before publication. If trailing
lag or finish processing produces callbacks with no later non-lag row, those
edges attach to the final buffered row with `terminal_forwarded: true`; their
normalized `logical_frame`, `logical_edge_index`, `rom_callback_pc`, and
ordinal order remain unchanged. Capture fails only when no
stored row exists to carry a real callback.

Engine animation/DPLC decision → runtime diagnostic FIFO → logical-frame
snapshot → comparator. The engine diagnostics publisher independently applies
the same one-row buffer and lag rule using production lag state, never trace
events. On intervening lag envelopes, both ROM and engine repeat the last
published outstanding ledger; buffered edges and their ledger mutations become
visible together on the selected non-lag or terminal row. Trace data never
flows back into either FIFO.

### Migration and rollback

Recorder versions and aux capabilities advance. Old fixtures omit the
capabilities and remain compatible. New canonical level fixtures must carry
both. S1/S2 special-stage segments must carry the DPLC capability after their
distinct ROM paths and engine owners pass the same invariant/comparison tests;
they omit the level-only Nemesis PLC capability where those RAM addresses are
not PLC-owned. Fleet publication remains blocked until special-stage DPLC
coverage is implemented, so named-run ledger continuity is never interrupted
by an unsupported segment.

Rollback is removal of the proposed fixture publication and capability-bearing
recorder changes together. There is no gameplay-state migration.

## Feature design

### Mandatory capture behavior

- Remove positive opt-in behavior from canonical trace mode. PLC snapshots are
  always on for S1/S2 level rows.
- Do not offer a normal canonical opt-out. Byte-parity research may use a
  clearly named scratch-only legacy mode that publication tests reject.
- Mandatory S2 DPLC auditing materially increases complete-emeralds capture
  time but does not hang: the isolated 35-segment route completes in 21:56.50
  at 99% CPU with 243,744 KiB maximum RSS. Its differential child therefore
  uses a route-specific 2,400,000 ms (40 minute) timeout; shorter capture tests
  retain their existing limits. Timeout is a harness budget only and cannot
  suppress, truncate, or normalize audit output. Child capture always writes
  to a fresh staging path; promotion to a candidate/final path occurs only
  after exit zero and complete semantic validation, so timeout termination
  cannot expose partial output as publishable.
- Emit exactly one PLC state of the expected game kind per stored row.
- Emit exactly one DPLC state envelope per stored row, containing zero or more
  ordered lifecycle edges. Preserve both normalized logical source position and publication
  row.
- Every standalone independently replayable level or special-stage segment
  arms with an empty submitted-transfer DPLC ledger. In continuous named runs,
  S1 may carry only an unpublished staging preparation, which is not serialized
  as an initial ledger and receives no transfer id until VBlank promotion. S2
  may carry already-accepted DMA FIFO work: the next segment declares the
  exact immutable initial descriptors/fingerprint, which must equal the final
  gap ledger, and later real `$14AC` callbacks retire those same IDs. Both are
  ROM/BK2-derived observer state, never trace hydration or fabricated drain.
  Each segment records its full terminal ledger. No trace metadata seeds
  production pending state.
- A run-wide observer remains active in unrepresented results/fade/menu gaps.
  The run manifest contains ordered `dynamic_art_gap_transitions`. Each embeds
  a distinct `dynamic_art_gap_edge` with canonical nonnegative
  `movie_logical_frame` and zero-based `gap_edge_index`; it does not carry
  segment-local cursor fields. It also carries the before-ledger hash and
  after-ledger descriptor set. Gap-created transfers use a `run_gap`
  submission origin inside manifest validation only. The preceding segment's terminal ledger seeds the first
  gap transition; transitions order lexicographically by their run-gap cursor;
  the final post-gap submitted-transfer ledger must be empty for S1. For S2 it
  may instead exactly equal the next segment's declared immutable initial
  descriptors/fingerprint. An unpublished S1 staging preparation is not a gap
  edge or ledger descriptor. Gap edges participate in manifest lifecycle
  validation and ROM audit, not per-row replay comparison. A callback in a gap
  can never be silently attached to a segment or discarded.
  In particular, if a segment-terminal S1 submission completes after the
  segment closes, the completion is a gap edge that retires the same stable
  transfer id; it is not terminal-forwarded back into the closed segment.
  Segment-local logical cursors reset only when the next segment arms, while
  run-wide ordinals and transfer ids never reset.
  Standalone movie termination may legitimately retain submitted but
  uncompleted transfers. Flush only a completion whose callback actually
  occurred; reject impossible or ambiguous lifecycle state, not valid pending
  ROM work.

### Error handling

Capture fails on an unverified callback window, unknown player-art DMA caller,
out-of-range ROM DPLC entry, impossible FIFO transition, ordinal reuse,
completion without submission, or inconsistent terminal pending identity.
Replay loading fails on malformed fields, duplicate edge ordinals, inconsistent
transfer pairing, illegal owner/phase/address domain, missing advertised row
coverage, unknown advertised event types, or out-of-domain frames. The exact
event name, capability literal, and schema-v1 parser dispatch are mandatory;
unknown events must not fall back to generic snapshots when the capability is
advertised.

Special-stage fixtures use their own physics schemas, but audit completeness
still has a strict frame domain. The Java loader adds a schema-independent
streaming scanner that validates the first CSV column as contiguous,
zero-based frame indices and counts every stored row from plain or gzip
payloads. The metadata-only/run-interior path parses DPLC envelopes and runs
the same per-frame completeness and lifecycle validation over that domain
without interpreting special-stage gameplay columns.

### Acceptance tests

- CLI tests prove canonical default-on behavior and publication rejection of
  scratch-only opt-out.
- PLC projector/parser/comparator tests cover idle, prepare, service progress,
  FIFO order, prefix/lag rows, missing/extra kinds, and legacy metadata.
- S1 observer tests cover level and special-stage paths, duplicate suppression,
  empty reuse, every verified
  VBlank completion site, same-frame completion, lag forwarding, and terminal
  state.
- S2 observer tests cover level and special-stage owners, multiple DPLC runs,
  unrelated DMA
  interleaving, queue capacity behavior, same-frame service, lag forwarding,
  and callback failure propagation.
- ROM-invariant tests pin addresses, opcodes, DPLC tables, art spans,
  destinations, and RAM variables.
- Engine provider tests prove matching lifecycle snapshots independent of
  rendering.
- Parser/comparator and authority guards prove comparison-only isolation.
- Representative S1/S2 native captures and replays prove exact event ordering
  before the full fleet is regenerated.

## Assumptions and risks

- Retail player-DPLC completion sites can be exhaustively identified for the
  two pinned ROM revisions. Failure to prove a site blocks publication.
- Moving engine DPLC lifecycle ownership out of rendering may expose existing
  parity gaps; those become frontiers and are not normalized away.
- Full-fleet aux size increases materially. Deterministic gzip remains the
  canonical storage format.
- S1/S2 special-stage implementations may currently preload art instead of
  modeling ROM DPLC transfers. The mandatory capability exposes that
  discrepancy as a frontier; it must not fabricate parity.

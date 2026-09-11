# Unfinished-Code Remediation Roadmap

## Purpose

Turn the findings in the 2026-08-08 dead/unfinished-code audit into verified,
incremental improvements without deleting coherent unfinished functionality or
letting cleanup displace S3K playable-route work.

This is intentionally a high-level orchestration plan. The source of truth for
scope and evidence is the existing documentation:

- `docs/architecture/audits/2026-08-08-dead-and-unfinished-code.md`
- `docs/architecture/validation/2026-08-08-dead-and-unfinished-code-sweep.md`
- `docs/status/s3k-known-bugs.md`
- `docs/guide/playing/game-status.md`
- the zone research under `docs/architecture/research/s3k-zones/`

## Completion rule

An audited finding is remediated only when it has one explicit disposition:

1. **Implemented accurately** from the owning disassembly or audio reference,
   with focused tests, rewind coverage where state is introduced, and route or
   trace evidence where gameplay is affected.
2. **Removed after proof** that the API or code has no supported caller and no
   retained design value.
3. **Made explicitly unavailable** through a capability boundary when the
   engine does not support the advertised operation.
4. **Documented as intentional/deferred** with an owning subsystem, dependency,
   and concrete acceptance evidence for future completion.

Marker removal alone is never completion. Runtime assets remain ROM-backed,
and traces remain comparison evidence rather than gameplay authority.

## Execution status — 2026-08-08

The first remediation swarm is complete on the local human-review branch
`feature/ai-unfinished-remediation-review` (local only; not merged or pushed). It
implemented LRZ1 falling startup, the AIZ miniboss napalm route, AIZ2 end-boss
splash children, explicit special-stage debug capabilities, authoritative S1
background-scroll ownership, and the host-owned title-menu audio path including
presentation-sink recreation after a title exit/reset. The no-op
`AbstractLevel.markAllDirty()` contract was removed after confirming that rewind
invalidation is manager-owned. The SMPS investigation closed the loader-supported
and S3-native SFX portions with ROM-backed full-bank proof. Mecha Sonic ordering
was corrected and replayed against the existing DEZ fixture/trace. `FAST` and
`REALISTIC` remain P2 narrowed/reserved aliases, not resolved. Two Big Arm
attempts were rejected/unintegrated; the inert placeholder remains P0.

Commands, test outcomes, unresolved work, and the rejected-branch rationale
are recorded in
`docs/architecture/validation/2026-08-08-unfinished-code-remediation.md`.
This roadmap remains the path for the unresolved waves rather than implying
that every audited feature is now complete.

## Priority waves

### Wave 1 — route-critical S3K behavior

1. **Open:** `AizMinibossNapalmProjectile` has native movement/floor behavior,
   harmful touch response, ROM mappings/art, explosion children, rewind, and
   production barrel routing. Capture a Knuckles AIZ route trace proving the
   remaining activation, slot, and lifetime behavior.
2. **P0 open:** `LbzFinalBoss2Instance` / Big Arm remains an inert placeholder.
   The committed `98d968d7f` candidate was rejected for invented phases,
   mapping ownership, and defeat flow. A second uncommitted v2 proved
   articulated anchors/tables (`$AD`/`$9A`), grab, and debris and passed six
   focused plus 28 graph/rewind guards, but root choreography, post-capsule
   continuation, and a Knuckles LBZ route trace remain unproven. Reuse only
   those proven pieces in a ROM-owned port.
3. **Resolved 2026-08-08:** LRZ1 non-Knuckles falling-introduction
   initialization is ported from `SpawnLevelMainSprites` `loc_68A6` with
   character/zone/act bootstrap tests; SSZ has no corresponding branch in the
   owning routine.
4. **Resolved 2026-08-08:** AIZ2 end-boss splash children from
   `ChildObjDat_69D2E` now have native slot order, ROM assets, rendering,
   allocation, and rewind coverage; an end-to-end trace remains follow-up
   evidence.

### Wave 2 — semantic correctness and honest capabilities

1. [Resolved 2026-08-08] Inventory ROM reachability of S3K SMPS meta commands
   `SND_CMD`, `MUS_PAUSE`, and `COPY_MEM`. Both native 173-entry SFX banks and
   all loader streams have closed full-bank control flow, ROM-asserted alias
   dispatch, and no reached target command; custom-stream handling remains
   operand-alignment-only pending a deliberately supported custom driver.
2. [Resolved 2026-08-08] Replace S1/S3K special-stage shortcut no-ops with an
   explicit capability contract: a shortcut either works and has tests, or is
   unavailable.
3. [Resolved 2026-08-08] Resolve `Sonic1.getBackgroundScroll()` ownership
   against the current parallax/runtime updater. The redundant query was
   removed; per-zone handlers remain authoritative and no second parallax model
   was introduced.
4. [Resolved 2026-08-08] `AbstractLevel.markAllDirty()` was removed after
   existing rewind and tilemap tests proved manager-owned invalidation.

### Wave 3 — polish, configuration, and S2 parity

1. [Resolved 2026-08-08] `MasterTitleScreen` navigation, confirmation, and
   error audio now use injected host-owned cues. Engine startup owns the
   `AudioManager` route, the unified presentation fallback synthesizes
   deterministic PCM without a selected game ROM, and title-exit/reset
   lifecycle tests prove the retained backend recreates exactly one closed/
   replaced presentation sink before re-entry.
2. **P2 narrowed/reserved — not resolved:** retain `FAST` and `REALISTIC` as
   warning-emitting aliases until cross-game measurements, explicit semantics,
   and readiness/rewind/trace-authority tests exist. Do not invent delays or
   delete the aliases; see the load-time validation report.
3. **Resolved 2026-08-08:** Corrected Mecha Sonic outer-loop `ObjectMove` and
   child ordering from the S2 disassembly. Focused DEZ phase/child-order tests
   and existing graph rewind tests are green. The dedicated
   `TestS2DezEndingLevelSelectTraceReplay#replayMatchesTrace` is 1/1 green on
   both base `5cc94d457` and candidate `4b4572cc3` with the verified REV01 ROM
   and existing `s2/dez_ending` fixture; ObjAF appears from auxiliary frame
   127, and no frontier advanced.
4. **Resolved boundary / explicit deferral 2026-08-08:** Treat CPZ placement
   debug and human-P2 monitor behavior as engine/game-mode capabilities, not
   object-local exceptions. CPZ now rejects the supported engine free-fly debug
   mode at entry and tests that supported engine boundary; native
   `Debug_placement_mode`/ring-item placement remains deferred. S2 has no competition-mode owner wired
   into level gameplay, so Monitor retains the ROM-faithful lead-player/
   CPU-sidekick gate; human-P2 parity is deferred to a dedicated
   competition-mode owner/design. The title provider's unconditional default
   action is not treated as evidence that this mode is absent.

## Swarm execution model

- Work is split by independent owner and implemented on isolated local branches
  and worktrees.
- Every worker starts from this roadmap commit, reads the audit and relevant
  current-status/research documents, and cites the source-of-truth routine.
- Behavior work is test-first. Approximate constants fitted from a trace are
  forbidden.
- Workers may deliver a verified implementation or a research-backed deferral.
  They must not delete well-written unfinished functionality merely because it
  is not currently wired.
- Each worker reports changed files, focused commands and outcomes, trace or ROM
  evidence, unresolved risks, and required documentation updates.
- Integration is serial. Conflicting or cross-cutting changes are reviewed and
  rebased before merge into the remediation branch.
- A final independent review checks the combined diff against this roadmap and
  the audit before any integration into `develop`.

## Wave acceptance gates

For each delivered item:

- the focused owner tests pass on JDK 21 with the correct verified ROM property;
- new dynamic object state satisfies rewind coverage guards;
- ROM assets are loaded through the production ROM pipeline;
- relevant route/trace evidence advances or remains non-regressed;
- the audit, known-bug ledger, zone research, and player-facing status are
  updated together where their claims change;
- no baseline-passing test regresses in the deterministic full-suite comparison;
- every deferral names the blocker, owner, and next evidence-producing action.

## Target end state

Wave 1 has no inert, invisible, harmless, or missing-introduction behavior on
the audited AIZ/LBZ/LRZ/SSZ paths. Wave 2 exposes no silently accepted debug or
audio operation and leaves no ambiguous public dirty/scroll contract. Wave 3
closes the remaining audited polish and S2 accuracy debt or records an explicit
product-level non-goal. The audit then contains no unresolved P0/P1 item and no
unowned P2/P3 item.

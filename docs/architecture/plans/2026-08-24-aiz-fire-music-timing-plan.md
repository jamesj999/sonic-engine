# AIZ fire music timing implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development. Add the failing regression contract before production code, then implement the smallest ROM-owned change.

**Goal:** Restore AIZ miniboss level music at the ROM-owned escape-timer boundary, after the seamless AIZ1-to-AIZ2 reload has completed, while preserving the fire curtain's existing trace-synchronised progression and frontier.

**Architecture:** Keep the transition-scoped timer in `Sonic3kAIZEvents`, the owner that already carries the fire sequence across the in-place act reload. Arm it when the AIZ1 fire sequence begins, tick it from the event pass with the same `0x120`/negative-test semantics as `AIZMinibossCutscene_Escape`, carry its remaining value through `PendingFireSequence`, and publish `restoreMusic()` when the timer expires. Remove the reload request's eager AIZ1 music override; the request must only reload production-created ROM resources and preserve the existing fire state.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito, ROM-backed S3K headless fixtures, `tools/testing/test-session.sh`.

**Spec:** The source of truth is `docs/skdisasm/sonic3k.asm`: `AIZMinibossCutscene_StartEscape` sets `$2E(a0) = $120` and `Events_fg_5`; `AIZMinibossCutscene_Escape` decrements the timer and restores music only after it becomes negative (`:136869-136901`). `AIZ1BGE_Finish` reloads AIZ2 without restoring music (`:104727-104775`), while `AIZ2BGE_WaitFire` continues the visual tail until `$310` (`:105054-105105`).

## Global constraints

- The AIZ fire state remains ROM-derived and trace-independent; no frame number, route name, fixture, or trace value may drive the timer.
- The existing AIZ1-to-AIZ2 `PendingFireSequence` handoff, load queues, visual curtain state, and trace comparison fields must remain unchanged except for the additional rewind-captured timer state.
- The timer must survive both normal play and trace replay, including the synchronous in-frame act reload.
- Runtime assets remain ROM-only; no asset or gameplay value may be read from the trace or disassembly tree.
- All certifying builds/tests/replays/captures use `tools/testing/test-session.sh`; no `--no-verify`.

## Test-first sequence

1. Add a focused AIZ event regression that starts the fire transition with a recording audio seam, proves no music restore occurs at the reload boundary, and proves exactly one restore occurs at the ROM timer boundary across the AIZ2 continuation. Run it before the production change and record the expected failure.
2. Add the minimal timer state and pending-handoff field, remove `.musicOverrideId(...)`, and keep the existing fire state machine untouched.
3. Run the focused AIZ event/curtain tests, schema/rewind coverage, and the AIZ trace replay. Compare first-error frame/field and error totals with the clean baseline; the trace frontier must not regress.
4. Run the required cross-game/guard checks, ordinary full suite, packaging, and post-merge verification. Update `CHANGELOG.0.6.md` and `docs/status/trace-frontier-log.md` with the delivered fix and exact evidence.

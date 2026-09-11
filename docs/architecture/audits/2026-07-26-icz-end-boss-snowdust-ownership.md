# ICZ end-boss snowdust ownership audit

Date: 2026-07-26
Branch: `bugfix/ai-trace-int-icz-24140`
Baseline: `9a6dc54ca2988393f1b1ac183654b2e6c80d91a2`

## Reproduction

The assigned ICZ complete-run baseline reported 31 errors, 0 warnings, with
the first mismatch at f24140 `rings` (expected 3, actual 2):

```bash
mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=false \
  -Ds3k.rom.path=s3k.gen \
  -Dtest=com.openggf.tests.trace.s3k.TestS3kIczCompleteRunTraceReplay#replayMatchesTrace \
  test
```

The trace's proximity-filtered `object_state` records were not used as a
complete object-pool oracle. A temporary stage-gated native probe inspected
the full SST only after ICZ1 gameplay and the late boss-area camera gate were
both live. It used the shared probe runtime's invisible fast emulation and
late hook registration. The probe was removed after the investigation.

## ROM ownership

Immediately before the boss arena, the engine had one placed snowdust emitter
in slot 9. When `IczEndBossInstance` armed the arena gate, it allocated a
second emitter in slot 10. The first particle was therefore displaced to the
following slot. Native execution retained only the placed emitter in slot 9
and allocated its first particle in slot 10.

The disassembly establishes that ownership:

- `Obj_ICZEndBoss` initialization does not call `AllocateObject`
  (`docs/skdisasm/sonic3k.asm:150578-150589`).
- The placed subtype-`$18` routine at `loc_8B660` stores its own SST address in
  `_unkFAAE` (`docs/skdisasm/sonic3k.asm:189930-189950`).
- Boss teardown reads `_unkFAAE`, verifies that it still points to
  `loc_8B660`, and sets the emitter's stop bit
  (`docs/skdisasm/sonic3k.asm:149867-149873,151245-151254`).

The engine now follows the same arrangement. Arena initialization no longer
synthesizes an emitter or owns a child reference. Teardown continues to find
the active placed `IczSnowPileObjectInstance` through the object manager and
requests its existing semantic stop operation. The placed emitter and boss
therefore rewind independently.

## TDD and verification

Before the production change, the focused command failed both new ownership
canaries: the no-placed-emitter harness observed a synthesized child, and the
production ICZ2 boss-area harness observed two emitters:

```bash
mvn -q -Dmse=relaxed -Ds3k.rom.path=s3k.gen \
  '-Dtest=com.openggf.tests.TestS3kIczEndBossObject#arenaGateDoesNotSynthesizeSnowdustWhenNoPlacedEmitterExists+productionIcz2BossAreaKeepsSnowingAfterArenaGateArms' \
  test
```

After the fix, the focused boss and rewind selection passed:

```bash
mvn -q -Dmse=relaxed -Ds3k.rom.path=s3k.gen \
  '-Dtest=com.openggf.tests.TestS3kIczEndBossObject,com.openggf.game.rewind.TestS3kIczEndBossGraphRewind,com.openggf.game.rewind.schema.TestRewindSchemaRegistry,com.openggf.game.rewind.schema.TestRewindPolicyRegistry' \
  test
```

Surefire ran 102 explicitly selected tests: 54 boss-object tests, four
end-boss graph-rewind tests, 25 rewind-schema tests, and 19 rewind-policy
tests. Maven Silent Extension additionally selected the four
`TestBizhawkProbeContractGuard` tests. All 106 passed. Its session aggregate
also reported the one expected-red ICZ closure replay; that replay is not part
of the 102-test selector.

The fresh replay after the fix reports 29 errors, 0 warnings, with the same
first mismatch at f24140 `rings` (expected 3, actual 2). This is a genuine
two-error reduction and corrects native object-slot topology, but it does not
advance the frontier.

## Remaining f24140 investigation

Read-only RNG diagnostics found that the engine seed immediately before the
first boss-area snow particle is four `Random_Number` calls ahead of native.
The native call immediately preceding the emitter in the inspected window
comes from an animal initialization. That is evidence for the next
investigation, not evidence for another fix: no RNG synchronization, trace
hydration, call suppression, or second production change is included here.

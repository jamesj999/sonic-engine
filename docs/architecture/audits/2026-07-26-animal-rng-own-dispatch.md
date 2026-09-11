# S2/S3K animal RNG own-dispatch audit

Date: 2026-07-26
Branch: `bugfix/ai-trace-int-icz-24140`
Baseline: `6dda95a2dab953cee4c4f58bdc198f8cf2614d27`

## Finding

Native and engine ICZ RNG seeds matched ordinal-for-ordinal through all 4,895
engine calls inspected before the trace ended. The RNG algorithm and initial
seed were therefore correct. Call ownership and dispatch order differed.

S3K `Obj_Explosion` allocates an `Obj_Animal` SST and copies its position and
points value without consuming RNG
(`docs/skdisasm/sonic3k.asm:42164-42175`). The subtype-zero animal consumes one
`Random_Number` result only when its own SST reaches `loc_2C924`
(`docs/skdisasm/sonic3k.asm:61049-61055`). S2 has the same ownership split in
`Obj27_InitWithAnimal` and `Obj28_InitRandom`.

The engine's S3K destruction configuration previously used
`AnimalObjectInstance::new`. That constructor selected the art variant
immediately while the explosion's slot was still executing. The result could
therefore move ahead of RNG consumers in intervening SST slots. S2 already
had a dedicated deferred constructor path, but its game-specific name allowed
S3K to remain on the eager path.

## Correction

`AnimalObjectInstance.deferredArtVariant` is now the single public factory for
the shared S2/S3K subtype-zero animal. It constructs without drawing RNG.
`initializeDeferredArtVariant` consumes exactly one result at the beginning of
the animal's first actual `update`. S2 badniks and direct animal spawns, plus
S3K badnik and Clamer destruction configurations, all use this factory.

The public two-argument constructor is deliberately retained for generic
rewind probing, but its former eager draw is removed: it now has the same
deferred semantics as the named factory. No public constructor can silently
choose allocation-time RNG. S1 remains on its separate ROM-ported animal
implementation. Rewind recreation continues through the private placeholder
constructor and restores captured scalar state without consuming RNG.

## TDD evidence

The new tests were first compiled against the absent generic factory and
failed on that missing behavior boundary. After implementation:

- construction preserves the initial seed;
- the first animal dispatch advances it exactly once;
- later dispatches do not redraw the variant;
- rewind recreation consumes no RNG;
- the end-to-end generic round-trip harness recognizes and passes the animal
  through its deferred public probe constructor;
- an explosion/consumer/animal scheduler test proves the intervening object
  receives the first result and the allocated animal receives the next result
  only on its own dispatch;
- the existing S2 animal timing test uses and verifies the same factory.

Focused command:

```bash
mvn -q -Dmse=off \
  '-Dtest=com.openggf.level.objects.TestAnimalObjectRngOwnership,com.openggf.game.sonic2.objects.TestSonic2AnimalObjectTiming,com.openggf.level.objects.TestDestructionEffects' \
  test
```

Result: seven tests pass.

## ICZ remeasurement

```bash
mvn -q -Dmse=off -Dsurefire.forkCount=1 -DreuseForks=false \
  -Ds3k.rom.path=s3k.gen \
  '-Dtest=com.openggf.tests.trace.s3k.TestS3kIczCompleteRunTraceReplay#replayMatchesTrace' \
  test
```

The final pre-boss animal draw moves from engine f22485 to f22486, matching
native. Its seed remains `1A2FF813`. The first boss snow particle at f22733
still consumes `ECB9BB0B`; native consumes `73EF3BAB`. The replay therefore
remains expected-red with 29 errors, 0 warnings, and first mismatch f24140
`rings` (expected 3, actual 2).

This slice corrects one source-backed ownership error but does not claim a
frontier or error-count advance. Earlier within-frame caller ordering,
including ICZ Freezer puff/debris ordering, remains separate triage.

Temporary RNG logging and the native stage-gated probe were removed. No trace
state was written into the engine.

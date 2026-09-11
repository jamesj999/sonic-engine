# S3K Blue Sphere Source-Timed Audio Validation

Date: 2026-08-22
Disposition: automated gates complete; merge and push blocked on listening

## Scope and identity

This report validates the local branch
`feature/ai-smps-playback-verification` through the Task 7 implementation HEAD
`dc8abae501297d6563d2885419f0e891c9a5444d` plus the Task 8 verification fixes
and documentation in the final handoff commit. The exact final commit and JAR
SHA-256 are deliberately recorded in the listening handoff because a commit
cannot embed its own object id.

The source documents verified here are:

- design SHA-256:
  `c4736b7708e729c3d2006979fc018f48535c0bff8e3e47fa3139d2fa28e4f9ed`;
- plan SHA-256:
  `ffab40680ff718eaeae8c680d6f98871d336fa09db1d70bf03c7703acc43ec69`;
- feature base: `914ac9a87badbad5c574cd8edaadc81c743e390a`;
- pre-Task-8 implementation HEAD:
  `dc8abae501297d6563d2885419f0e891c9a5444d`.

The runs used Maven 3.9.16 on OpenJDK 21.0.11 from
`/usr/lib/jvm/java-21-openjdk`. The authenticated absolute ROM paths were
`$OPENGGF_MAIN_WORKSPACE/s1.gen`, `s2.gen`, and `s3k.gen`, with the
project-specified SHA-1 values `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`,
`8bca5dcef1af3e00098666fd892dc1c2a76333f9`, and
`cfbf98c36c776677290a872547ac47c53d2761d6` respectively.

## Native evidence

The retained lab was captured twice for S3K and twice for Sonic 2. Each pair
was byte-identical. No superseded diagnostic spike remained in `src`, `tools`,
or `docs`; the approved GPGX lab and its regeneration scripts remain tracked.

S3K evidence:

- observer patch SHA-256:
  `9c204d55e1c7524bf94180aa930d6be6a88e332d5227f187a2ed3d048b6bd375`;
- patched native core SHA-256:
  `3e2cddbb22c93676046f980926fd14d0689bb5bfd36ee75d0d630c2289b940a3`;
- compact oracle SHA-256:
  `5115c7e2bb5443ae7ccf1fa32d3d41dc1f77d17f086405e29bd3c258e96ee7e2`;
- raw write SHA-256:
  `8b55ae5833651fc3cdbe6caddee54dd604cbea2b7e906615e6edd55ddd9614d0`;
- six-column projection SHA-256:
  `33cef3472ad2c9c0d0d50e27f6ae574b51e02755420cd9c542b0443996013f99`;
- FM5 PCM SHA-256:
  `4277bc5f29fa086013b49f006fd887b9795ebfbb17e8288de4c50005bb97e6d8`;
- 12 groups, 34 writes per group, zero DMA stalls, zero observer fault, and
  zero overflow.

Every group carries this exact source-relative master-cycle vector:

```text
[0,3150,6300,9450,15885,19110,22875,26445,30015,33585,37155,
 40725,44295,47865,51435,55005,58575,62145,65715,69285,72855,
 76425,79995,83565,87135,90705,95850,99675,103500,107325,
 115380,146010,148710,151590]
```

The capture wrapper was corrected during final verification to publish the CPU
instruction ledger only for the S1/S2 instruction-audit games. S3K never emits
that ledger. A failing test demonstrated the unconditional publication bug
before the wrapper fix; its final SHA-256 is
`b518761c57e7123ad086e6560616929be5cf6a7d91280af4f61ce0d14f618b1e`.
Fresh S2 capture pairs remained byte-identical with compact-oracle SHA-256
`e3ce0fa19db864cfdbc79f7f53568c1cdb323ec8b7a1d536444f6e6f8ee9e56b`
and instruction-ledger SHA-256
`d03eed2d2679b2287c626c5098b96140c22e3746e425a23901ef023998826c3c`.
This refresh changed
provenance only, not the S2 ruling.

## Automated verification

The native/oracle Java gate was run twice with all three ROM properties:

```text
-Dtest=TestYm2612ChipGpgxParity,TestS1S2YmWriteTimingAudit,
TestSonic3kYmServiceTimingProfile,TestS3kBlueSphereSfxParity test
```

Both runs passed 51 tests with zero failures, errors, or skips. The explicit
rewind/observer/cadence/presentation group passed 263 tests with zero failures,
errors, or skips. It covered S3K service order and contention, transactional
timeline publication, modulation, pause, fade, one-up and speed state, PAL
cadence, ring panning, special-stage speed, OpenAL packetization, speaker FIFO,
deterministic synth/driver/sequencer/presentation snapshots, and observer
rollback. The Task 7 three-ROM audio selector passed 102 tests with zero
failures, errors, or skips.

Final-verification tests also corrected stale assumptions exposed by the new
delayed timeline. The bounded playback trace requires a closed-world drained
prefix: the exact 13-event committed Spring tail is followed immediately by
the exact Blue Sphere attack stream, including its four derived key-on trace
events, with no intervening or extra event inside that prefix. The pending Blue
Sphere service must contain exactly the reviewed 34 source/segment-tagged
writes and rejects every `BASE_MUSIC` or `COMPLETION_RESTORE` prefix, including
`B5`, `B1`, key-off, and operator programming. The architecture guard no longer
exempts a helper globally by name: it parses the helper's top-level statements,
requires exactly one unconditional rollback capture as the first executable
statement, and admits only the reviewed active-transaction guard, capacity
preflight, cursor calculation, transaction construction, return, and
timing-dominated callsites. Conditional capture, late capture after mutation,
unexpected work, and duplicate capture poisons were each observed failing
before the structural constraint was implemented. The pre-review focused run
actually passed 53 tests, not the 52 reported in the first handoff. The final
corrected focused group passes 63 tests with zero failures, errors, or skips:
37 architecture tests, 7 playback tests, and 19 parity tests.

The exact focused commands in the correction round used this common prefix:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn -Dmse=off \
  -Dsonic1.rom.path="$OPENGGF_MAIN_WORKSPACE/s1.gen" \
  -Dsonic2.rom.path="$OPENGGF_MAIN_WORKSPACE/s2.gen" \
  -Ds3k.rom.path="$OPENGGF_MAIN_WORKSPACE/s3k.gen"
```

The twice-run native/oracle selector appended:

```text
-Dtest=TestYm2612ChipGpgxParity,TestS1S2YmWriteTimingAudit,
TestSonic3kYmServiceTimingProfile,TestS3kBlueSphereSfxParity test
```

Each run passed 51 tests. The review selector appended
`-Dtest=TestAudioPresentationArchitectureGuard,TestS3kBlueSpherePlaybackTrace,TestS3kBlueSphereSfxParity test`
and passed 63 tests. The rewind/observer/cadence/presentation selector appended:

```text
-Dtest=TestSmpsSequencerSnapshot,TestS3kPalDriverCadence,
TestSmpsSequencerDriverCadence,TestS3kSpecialStageAudioPlaybackTrace,
TestAudioVoiceRegistry,TestVirtualSynthesizerSnapshot,TestSmpsFadeHybridParity,
TestSmpsPauseProtocol,TestSmpsDriverServiceOrder,TestSfxContentionObserver,
TestSmpsDriverYmWriteTimeline,TestS3kBlueSphereSfxParity,
TestSmpsDriverSnapshot,TestSpeakerPacketFifo,TestOpenAlPcmSink,
TestAudioPresentationSnapshotParity,TestSmpsSequencerFadeTiming,
TestAudioDiagnosticObservers,TestSonic3kUnifiedAudioPresentationRomIntegration test
```

It passed 263 tests. Final correction-round log SHA-256 values are
`cfafcc43c830cc32cd9501f3f99f75f8cabaabca49a6f1622613c2cd13121ba4`
and `d608db3cbbb70f3f73224ef53f2d0c6ca8ca5161fea0720e4333a4df0fa670b7`
for the oracle repetitions,
`1f62d679b0fca5136b58c382b54d2d5a85c31cb1584dd9d9df172b3aa613b6ec`
for the review selector, and
`13c4e7ad49e0c547d21b274bae6b34eeff50961a72a93e8d4b96d923350b1a6c`
for the 263-test selector. All commands used Maven 3.9.16 and OpenJDK 21.0.11.

## Full-suite comparison

The earlier `d473365ed72facfffcd36d9e07af09666b094d37` result is not a
valid regression baseline: it is not an ancestor of this feature branch and
its merge base is `683b1a3984958b4b6ae53baf383a81bee6727078`.

The comparable baseline was therefore run in an isolated detached worktree at
the documented feature base `914ac9a87badbad5c574cd8edaadc81c743e390a`.
Its log SHA-256 is
`3c6266ba425ae8b20ddbf3c456c8b0d870c782a0511b4cc85fa821b4fc3e09bc`.
It ran 15,298 tests with 53 failures, 64 errors, and 18 skips.

The final three-ROM test command was:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn -Dmse=off \
  -Dsonic1.rom.path="$OPENGGF_MAIN_WORKSPACE/s1.gen" \
  -Dsonic2.rom.path="$OPENGGF_MAIN_WORKSPACE/s2.gen" \
  -Ds3k.rom.path="$OPENGGF_MAIN_WORKSPACE/s3k.gen" test
```

The round-two correction candidate ran 15,409 tests with 53 failures, 64
errors, and 18 skips. Its log SHA-256 is
`02c642e58735cb63de51b338ce0ff4bde17030d6a5cdf30bb1ba8e86f285630e`.
The higher test total is the feature's added coverage, not a changed selector.

XML parsing compared the fully qualified class and test method plus failure
versus error status. Both baseline and candidate contain exactly 117 unique red
identities. The two sorted identity ledgers are byte-identical, each with
SHA-256
`584fe83dcf7311fbee0dce80c3308439608318b814a9c243e2f098786fcedaa5`.
No baseline-passing test fails, no baseline-red test changes failure class, and
no red identity is added, removed, or waived. This is the applicable regression
comparison for the feature branch.

An earlier correction-round repetition reported one additional
`TestHeaderNameRomDetectors` failure. The method and its complete 14-test class
both passed in isolation; inspection showed its first test can inherit a stale
global bootstrap module from a prior class in a reused Surefire fork because it
resets only after each test. The identical full command was therefore repeated
rather than waiving the result, and the accepted run above restored the exact
117-identity baseline ledger. The rejected log remains preserved with SHA-256
`f0dc8210dfac094d478777b8f7795de3e920a7a8e25f1c5a2c988273d5c9a286`.

The detached feature-base worktree was removed after confirming it was clean
and detached at `914ac9a87badbad5c574cd8edaadc81c743e390a`; worktree metadata was
pruned and no branch was deleted. Its preserved baseline log remains at
`$AGENT_SCRATCH_ROOT/tasks/smps-ym-task8-round1-baseline-20260822T215753Z-1620546-d3ab87e0/baseline.log`
with SHA-256
`3c6266ba425ae8b20ddbf3c456c8b0d870c782a0511b4cc85fa821b4fc3e09bc`.

## Cross-game ruling and limits

Sonic 1 and Sonic 2 keep `YmServiceTimingProfile.none()`. Their native
instruction audits establish different service owners and do not justify
copying S3K's source-cycle vector by symmetry. This is an explicit ruling, not
missing implementation.

The retained S3K evidence proves source-relative write spacing and ordering.
It does not prove absolute VInt phase, and the captured groups contain no DMA
contention. Accordingly this report makes no absolute-phase or DMA-contended
parity claim. Runtime timing comes from the profile and ROM-owned service work;
no trace data is consulted by gameplay.

## Listening handoff

Automated verification is complete, but this change is not approved for merge
or push. The exact clean-HEAD handoff package is built with tests enabled via
the same JDK 21 and three absolute ROM properties as the full-suite command;
`-Dmaven.test.failure.ignore=true` permits assembly only after Surefire executes
the known-red suite. Its commit identity, JAR SHA-256, log SHA-256, and exact
117-red identity comparison are recorded in the listening handoff because a
commit cannot contain its own final identity. Listen to that packaged commit for:

1. the first Blue Sphere pickup;
2. rapid consecutive pickups;
3. the first pickup after completion or a turn;
4. a pickup after another FM5 SFX;
5. ring pickups; and
6. special-stage entry while speed shoes were active.

Only a positive result lifts the integration gate.

## Whole-review correction: service-end PSG snapshot fidelity

Review after `a80640ac84b9f05d3c6eda3259ef20585ccf3771` found one
transactional observer defect. The outer driver batch correctly withheld PSG
hardware writes and all logical callbacks until every sibling service
succeeded, but each service-end snapshot was frozen before those withheld PSG
writes were applied. A successful service could therefore report committed YM
state alongside stale pre-service PSG state.

The corrected driver retains the no-live-mutation rule. PSG publications are
now typed transaction entries and an outer reservation restores a private
`PsgChip` from its pre-batch synth snapshot. Each successful service replays
only its PSG prefix into that private chip before freezing the service-end
snapshot. The live chip still receives the writes only after the whole batch
succeeds, then logical observers receive the already-frozen per-service
snapshots. A later poisoned sibling discards the private chip and publishes no
hardware write or callback.

The regression uses two sibling services with distinct non-default PSG volume
writes plus eight timed YM writes each. Its first RED run observed the startup
PSG latch in the first successful service snapshot. GREEN proves exact PSG
snapshot equality against an independently restored/replayed `PsgChip`, YM
pending counts of 8 then 16, zero live mutation/callback after a later sibling
poison, dense retry equality against a clean run, and deferred observer
exception delivery after all hardware and logical publications.

Verification on OpenJDK 21:

- focused RED/then-GREEN method:
  `TestSmpsDriverYmWriteTimeline#outerBatchPublishesOnlyAfterEverySiblingCommits`
  (1 test, GREEN after correction);
- complete timeline class: 37 tests, zero failures/errors/skips;
- timeline, contention, service-observer, driver/synth snapshot, chip-observer,
  audio-rewind wildcard, and diagnostic-observer selector: 111 tests, zero
  failures/errors/skips;
- the established ROM-backed rewind/observer/cadence/presentation selector:
  266 tests, zero failures/errors/skips.

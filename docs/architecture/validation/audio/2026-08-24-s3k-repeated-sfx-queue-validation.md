# S3K Repeated-SFX Queue Validation

**Date:** 2026-08-24
**Implementation base:** `4618e882bdee5ca9c9f94f8344a60833f6e20ee1`
**Branch:** `bugfix/ai-s3k-sfx-overwrite-parity`

## Result

The first divergence is closed in the development worktree. Locked-on S3K
requests now enter two bounded driver input cells and are consumed only after
the incumbent SFX pass. A repeated Explosion therefore receives its current
boundary service before FM5 is reloaded, and a queued Collapse owns no physical
track before the native admission boundary.

This is a driver-order correction, not an Explosion or Collapse exception.
Sonic 1 and Sonic 2 remain on immediate admission because their shipped sound
drivers consume the queue before their SFX loops.

## Native evidence

The retained AIZ gameplay movie was captured twice against the pinned
diagnostic core. Both preflight JSON files were byte-identical:

- SHA-256: `8320079e657af25b00bb58fc2f7da4a444c6e324606737b04077777052ef8a8e`;
- 21,309 frames;
- 134 physical FM5 Explosion reloads, including three-frame boss-death runs;
- Collapse active from frame 1,558 through 1,678 inclusive (121 frames);
- later SFX traffic intersects the Collapse interval;
- 14,041,158 ordinary trace events and zero overflow.

The injected `explode-repeat` manifest case requests B4 every three frames
through frame 30. Its two captures were byte-identical at SHA-256
`4bbce8c45d924f5acba364d1b234ceb93ca7aff6c79801e7870cbb30266daea8`;
the final residence reaches its native stop after the repeated sequence.

## Java verification

JDK 21.0.11 and authenticated ROM identities were used:

- S1 SHA-1 `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`;
- S2 SHA-1 `8bca5dcef1af3e00098666fd892dc1c2a76333f9`;
- S3K SHA-1 `cfbf98c36c776677290a872547ac47c53d2761d6`.

The final cross-game command selected the S1/S2/S3K unified presentation ROM
integrations, prepared admission, cadence, snapshots, diagnostics,
architecture, repeated Explosion/Collapse parity, and the active-music AIZ
rock route. Run `20260824T191626Z-p4184185-83577b` completed:

- 169 tests;
- 0 failures;
- 0 errors;
- 0 skips.

The earlier combined focused run
`20260824T190915Z-p4176071-71b4f8` completed 140/140, and the post-callback
timing run `20260824T191300Z-p4179222-c978da` completed 145/145.

The AIZ headless route keeps AIZ1 music active, breaks the ROM-backed rock,
then hits the later spring. It proves the first three Collapse FM key-offs and
physical PSG3/noise ownership at the later burst boundaries after the spring.

## Native managed-suite status

- `GpgxAudioTraceNativeTests`: 1/1 green.
- `CompleteRunAudioObserverTests`: 32/32 green.
- `GpgxS3kAudioParityManifestTests`: 1/1 green.
- `GpgxZ80AudioCapabilityTests`: one green and one inherited identity-lock
  failure. The failure hashes the unchanged production headless executable as
  `5811e310...` against the pre-existing frozen `0b96b3df...` value. No
  production headless source or capability fixture is changed in this branch,
  so the unrelated lock is preserved rather than repinned.

## Cross-game ruling

Every discovery in this slice was checked against all three shipped drivers:

- S1 `s1.sounddriver.asm:180-245`: request consumption precedes music/SFX
  service, so immediate admission and immediate `onSfxStart` remain correct.
- S2 `s2.sounddriver.asm:400-455`: request consumption precedes music/SFX
  service, so the same immediate policy remains correct.
- S3K `Z80 Sound Driver.asm:653-701`: active SFX service precedes queue fill and
  consumption, so deferred input cells and consumption-time `onSfxStart` are
  required.

The explicit controls guard both S1/S2 policy shapes, while the S3K duplicate
test proves a slot-0 duplicate produces no callback or coordination mutation.

The final lifecycle audit also caught a presentation-specific S3K case: a
driver with no installed sequencer but one queued input cell must remain live
until `zPlaySound` consumes that cell. `SmpsDriver.isComplete()` now includes
both cells. S1/S2 never own deferred cells, so the corresponding completion
path and ROM integrations remain unchanged.

## Full-suite comparison

The detached baseline at `4618e882bdee5ca9c9f94f8344a60833f6e20ee1`
and the candidate used the identical JDK 21/all-three-ROM Maven command.

- Baseline run `20260824T192100Z-p2065-4326ef`: 15,103 tests, 52
  failures, 9 errors, 19 skips.
- Candidate run `20260824T193759Z-p31383-9299be`: 15,111 tests, 52
  failures, 9 errors, 19 skips.
- Both red ledgers contain 61 unique failure/error identities. The baseline
  ledger SHA-256 is
  `409f1495720b6f7bd0661d69e9b467000f8f04520125d058dffd8e16ff8acb3e`;
  the candidate ledger SHA-256 is
  `e4e68a639e8993e127dcae575470e51b9ec30efe2c0afa690ce61340d8a80dcc`.

The only identity exchange is ambient reused-fork churn already documented in
the repository: baseline-only
`TestS3kLbzFlameThrowerObject#registryCreatesLbzFlameThrowerAndProfileMarksS3klSlotImplemented`
and candidate-only
`TestCutsceneKnucklesAiz1Instance#exitHandoffReadsPreviousFrameRenderFlag`.
Both pass together in the isolated two-test rerun
`20260824T194227Z-p36988-1296bd`; the latter is explicitly documented as
order-sensitive in the prior S1 FM5 validation and trace-frontier log. No
audio, S1, or S2 baseline-passing test became red.

## Remaining handoff

The feature still requires an exact-HEAD package and human listening of an
AIZ1 miniboss or AIZ2 boss death plus the music-enabled Collapse route before
integration.

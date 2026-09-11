# S2 YM write-profile follow-up

## Dependency and boundary

Start only from the retained v2 S2 oracle/calculation and hash-locked decoded
Z80 instruction ledger. Preserve
`FixDriverBugs = fixBugs = 0`; owner admission is semantic `zSFX_FM5`, not a
channel synthesis. This is separate from Task 7.

## Task 1: typed Z80 source variants

- Files: add an S2-owned profile/configuration variant and focused profile
  tests; do not alter S1 or S3K selection.
- RED: snapshot that S2 returns `none()` and that wrong `IX`, intervening
  `cfSetVoice`, busy taken/not-taken, and bank-wait variants are unmodelled.
- GREEN: type `zWriteFMIorII`/`zWriteFMI`/`zWriteFMII`,
  `zFinishTrackUpdate`, `zSetMaxRelRate`, `cfSetVoice`/`zSetVoice`, and
  `cfStopTrack` from `s2.sounddriver.asm:343-389,947+,2090+,3271-3432,3514+`.
  Model GPGX's three-T-state uncontended bank read separately from instruction
  T-states and preserve every branch outcome.
- Acceptance: the typed sequence reproduces every exact ordered PC/opcode,
  branch outcome, 3-T-state bank wait, and retained gap; poison tests reject
  `IX != $1D90` and replacement owners. Aggregate primitive buckets are not an
  acceptable substitute.

## Task 2: snapshot-safe bus transaction

- Files: scheduling transaction/snapshot and S2 audio integration tests.
- RED: partial voice upload fails rewind/save restore and simultaneous bus
  ownership is silently treated as uncontended.
- GREEN: snapshot owner IX, write ordinal, branch variant, bank state, pending
  wait, and completion restore; expose any DMA contention through a typed bus
  timing input, never a fitted constant.
- Acceptance: rollback/restore at every boundary is deterministic and trace
  comparison cannot decide work or values.

## Task 3: ROM/native verification

- Command: JDK 21 focused Maven tests with the absolute SHA-1-verified REV01
  S2 ROM; capture the pinned complete-run BK2 twice with
  `capture-ym-write-timing.sh --game s2 --sound-id 0xB5 --fm-channel 4`.
- RED/GREEN: demonstrate the current atomic mismatch, then require exact gaps,
  owner lifetime, key-off/music restore, true-context attenuation, zero
  DMA/fault markers, and byte-identical A/B for isolated and overlap groups.
- Acceptance: focused and full tests add no regression; hashes and any
  contention limitation are recorded in the audit/report.

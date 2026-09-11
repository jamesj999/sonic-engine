# Sound-driver RE handover (2026-08-31, Claude → Codex)

Written at down-tools. Read `AGENTS.md` first; this adds only what it doesn't carry.

## Where everything stands

- `develop` (origin `f087b8947`): all 2026-08-29/30 audio PRs are merged —
  clean-room PSG (#176), Nuked-OPN2 FM core (#177), FM:PSG mix calibration
  (#178), S3K special-stage ring panning (#179), ROM-less CI + flake fixes
  (#180). Suites on develop were green at merge time.
- **`feature/ai-sound-driver-re`** (worktree `.worktrees/sdre`, head
  `b5cdcbdda`, NOT pushed): phase 1 of the SMPS driver reverse-engineering,
  fully verified (guards 565/0, ordinary 15,910/0/0/16). Index of all
  artifacts: `docs/architecture/designs/audio/2026-08-30-sound-driver-re-index.md`.
  Highlights: per-game behaviour specs (adversarially reviewed), gap analysis,
  and a live oracle per game — S1 GHZ music **MATCH over 14,690 ticks**
  (committed fixture), S1 SFX red at tick 351 (`psg $9F` on admission), S2 EHZ
  window red, S3K tick-3 boot burst red. Frontier ledger:
  `docs/status/audio-frontier-log.md`.
- **Phase 2 resumed on `feature/ai-sdre2-cadence-resume`.** Cadence targets
  1-4 are implemented. The S1 sound-test SFX oracle is now a full **MATCH over
  1,967 ticks** while the protected GHZ music oracle remains a **MATCH over
  14,690 ticks**. The S2 prefix reaches tick 210, where the reference consumes
  an SFX request absent from the v1 pre-service capture. The S3K service
  projection reaches service 128/source frame 242, where a mid-frame `FEh`
  request is consumed before the v1 mailbox snapshot. Both remaining red
  frontiers require better request capture, not inferred engine behavior.
- The original `feature/ai-sdre2-cadence` worktree still contains the flagged
  unreviewed PAL scratch and remains untouched.

## The working method (proven this cycle — keep it)

1. Fix ONLY the first divergence the oracle reports; cite the driver routine
   (S1 `s1.sounddriver.asm`, S2 `s2.sounddriver.asm`, S3K
   `Sound/Z80 Sound Driver.asm`) in a comment; FixBugs/fix_sndbugs=0 path.
2. Gates, no exceptions: frontier advances; S1 music MATCH is inviolable;
   never weaken a comparator or edit a committed fixture; audio package +
   `TestSmpsFadeAudioThroughput` (±10%) + `-Pguards` green; frontier-log entry
   per movement.
3. Sources-closed: disassembly + phase-1 specs only. SMPSPlay/libvgm/GPGX
   sound code and branch `feature/ai-smps-transaction-parity` are NOT evidence
   (that branch is a claims checklist only:
   `docs/architecture/audits/2026-08-29-smps-parity-branch-claims-inventory.md`).
4. No agent self-certifies audibility: append affected rows from the
   2026-08-21 listening checklist to a human listening queue.

## Remaining phase-2 targets

1. Add a true pre-consumption request probe to the S2 and S3K reference
   producers, then regenerate authenticated fixtures before advancing their
   current frontiers. Do not derive requests from observed output bursts or
   fixture tick/frame numbers.
2. Run the audio package, fade-throughput, ordinary, and guard verification
   gates. The human listening queue remains a release gate and cannot be
   self-certified by an agent.

## Traps that cost us time (beyond AGENTS.md)

- Worktrees get ROM symlinks from the post-checkout hook; pass ABSOLUTE
  `-D...rom.path` anyway and check skip counts (18 expected with all ROMs).
- Oracle wrappers need an EXTERNAL `--output-root`.
- BizHawk only via the sanctioned launchers/headless harness, detached with a
  timeout; the GPGX core has no "System Bus" memory domain — the
  TraceChaser-pinned copy of `s1_audio_driver_parity_probe.lua` still carries
  that bug (fixed consumer-side in `tools/audio/probes/`).
- Two sessions have shared this checkout and committed under different git
  identities mid-lane before; if a "foreign writer" appears in your worktree,
  suspect a duplicated lane or the collaborator, and review rather than reset.

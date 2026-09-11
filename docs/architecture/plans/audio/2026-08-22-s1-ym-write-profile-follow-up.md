# S1 YM write-profile follow-up

## Dependency and boundary

Start only from the retained v2 S1 oracle, v2 calculation, and hash-locked
representative instruction ledger in `docs/architecture/research/audio/`.
Preserve `FixBugs = 0`; do not select on
sound id, route, frame, or register fingerprint. This is separate from Task 7.

## Task 1: typed source model

- Files: add an S1-owned `YmServiceTimingProfile` variant beside the existing
  audio configuration/profile registry; update its unit test only.
- RED: snapshot `FinishTrackUpdate`, `WriteFMIorII`, `WriteFMI`, `WriteFMII`,
  `cfSetVoice`/`SetVoice`, and `cfStopTrack` variants and show S1 still returns
  `none()`.
- GREEN: encode busy-poll taken/not-taken, call/return, voice loop, key-on,
  key-off, and music-voice restore as typed operations sourced from
  `s1.sounddriver.asm:436-456,1713-1769,2313-2375,2489+`.
- Acceptance: typed operations reproduce the exact ordered PC/opcode ledger
  and every retained gap; no aggregate fitted duration or primitive bucket
  exists.

## Task 2: transactional scheduling

- Files: YM write scheduler plus its snapshot/rewind transaction type and
  focused synth tests.
- RED: atomic submission disagrees with the retained isolated native
  attenuation; snapshot/restore during a partial group loses pending work.
- GREEN: submit production writes with typed delays, snapshot pending ordinal,
  branch variant and owner, and commit/rollback as one transaction.
- Acceptance: rewind and save/restore at every write boundary are byte-stable;
  no trace data supplies values or creates work.

## Task 3: ROM/native verification

- Command: JDK 21 focused Maven tests with absolute verified S1 ROM, then
  `capture-ym-write-timing.sh --game s1 --sound-id 0xB5 --fm-channel 4` twice
  against the pinned BK2.
- RED/GREEN: first prove current runtime misses native isolated/overlap timing;
  then require exact register order, every relative gap, completion restore,
  context replay attenuation, zero DMA/fault markers, and byte-identical A/B.
- Acceptance: isolated and overlap classifications both pass, full suite adds
  no regression, and the audit/report records exact hashes.

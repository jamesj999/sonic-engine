# Sound-driver reverse-engineering artifact index (2026-08-30)

Index of every artifact produced by the 2026-08-30 sound-driver
reverse-engineering programme on `feature/ai-sound-driver-re`. Each lane's
output is one line here; the artifacts themselves carry the detail.

## Maps

- [Engine SMPS architecture map](../../audits/audio/2026-08-30-smps-engine-architecture-map.md)
  — audit of the engine-side SMPS driver/sequencer/chip-core stack as it stands.
- [S1 sound-driver routine map](../../research/audio/2026-08-30-s1-sound-driver-routine-map.md)
  — routine-by-routine map of the Sonic 1 68k SMPS driver from s1disasm.
- [S2 sound-driver routine map](../../research/audio/2026-08-30-s2-sound-driver-routine-map.md)
  — routine-by-routine map of the Sonic 2 Z80 Saxman-compressed driver from s2disasm.
- [S3K sound-driver routine map](../../research/audio/2026-08-30-s3k-sound-driver-routine-map.md)
  — routine-by-routine map of the Sonic 3 & Knuckles Z80 driver from skdisasm.
- [Audio oracle tooling map](../../audits/audio/2026-08-30-audio-oracle-tooling-map.md)
  — audit of the existing audio parity/oracle tooling and capture pipeline.
- [SMPS behaviour claims digest](../../audits/audio/2026-08-30-smps-behaviour-claims-digest.md)
  — digest of behaviour claims the engine's audio code currently embodies, with provenance.

## Gap analysis

- [Sound-driver RE gap analysis](2026-08-30-sound-driver-re-gap-analysis.md)
  — engine-vs-ROM driver gap analysis, hardened by two adversarial refutation
  passes (structural-fit and oracle-plan) folded into the document.

## Behaviour specs (each with an in-file adversarial review record)

- [S1 sound-driver behaviour spec](2026-08-30-s1-sound-driver-behaviour-spec.md)
  — disassembly-cited behaviour spec of the S1 driver; review record in section 20.
- [S2 sound-driver behaviour spec](2026-08-30-s2-sound-driver-behaviour-spec.md)
  — disassembly-cited behaviour spec of the S2 driver, with its review record.
- [S3K sound-driver behaviour spec](2026-08-30-s3k-sound-driver-behaviour-spec.md)
  — disassembly-cited behaviour spec of the S3K driver, with its review record.

## Oracles

- [S1 audio oracle validation](../../validation/audio/2026-08-30-s1-audio-oracle-validation.md)
  — validation record for the committed S1 GHZ music + sound-test SFX oracle
  (fixtures under `src/test/resources/audio/parity/s1/`, probes under
  `tools/audio/probes/`, runner `tools/audio/run_s1_audio_parity.sh`).
- [S2 driver oracle](../../research/audio/2026-08-30-s2-driver-oracle.md)
  — the S2 windowed driver oracle: capture method, comparator, first measurement
  (fixtures under `src/test/resources/audio/parity/s2/`, TraceChaser window-capture
  patch alongside as
  [2026-08-30-s2-oracle-tracechaser-window-capture.patch](../../research/audio/2026-08-30-s2-oracle-tracechaser-window-capture.patch)).
- [S3K audio oracle design](2026-08-30-s3k-audio-oracle-design.md)
  — the S3K AIZ1-intro driver oracle: design, comparator, first frontier
  (fixtures under `src/test/resources/audio/parity/s3k/`, capture tool
  `tools/audio/s3k/S3kAudioOracleReferenceCapture.cs`, runner
  `tools/audio/run_s3k_audio_oracle_reference.sh`).

## Running record

- [Audio frontier log](../../../status/audio-frontier-log.md)
  — the audio counterpart of the trace frontier log: every oracle comparison's
  command, context, result, and first divergence.

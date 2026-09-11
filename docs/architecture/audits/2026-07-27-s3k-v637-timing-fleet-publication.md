# S3K v6.37 hardware-timing fleet publication

## Decision

Publish the five fresh native identities captured from commit
`94d7356df860ed1149c18a7a41804522fc91f757`:

- standard CNZ and MGZ;
- Sonic+Tails complete run through the terminal ending;
- Knuckles run identity B (`s3-knux-multibonus-ss`);
- Knuckles run identity C (`s3k-multibonus`).

LBZ is regenerated and validated as part of the complete-run identity. Its
replay frontier is deliberately not diagnosed or remediated.

The exact installed evidence is
`src/test/resources/traces/s3k/hardware-timing-publication.tsv`. It freezes 47
fixture destinations plus the B run manifest, including ownership, raw source
token, schemas, recorder version, frame count, exact compressed-file hashes and
lengths, hardware-event counts by boundary, and first/last edge identities.

## Semantic destination ownership

The recorder tokens after LRZ expose internal ROM zone/act encodings, not
stable public fixture names. The publication maps them as follows:

| Raw native token | Metadata zone/act | Published destination | Meaning |
|---|---:|---|---|
| `fbz` | `$04/$01` | `fbz_completerun` | Flying Battery |
| `soz` | `$08/$01` | `soz_completerun` | Sandopolis |
| `lrz` | `$09/$01` | `lrz_completerun` | Lava Reef |
| `hpz22` | `$16/$01` | `hpz_completerun` | Hidden Palace |
| `hpz` | `$0A/$01` | `ssz_completerun` | Sky Sanctuary |
| `ssz` | `$0B/$01` | `dez_completerun` | Death Egg |
| `dez23` | `$17/$01` | `ddz_completerun` | Doomsday act 2 internal owner |
| `ddz` | `$0D/$02` | `ending_completerun` | Post-Doomsday ending |

This preserves the exact native metadata bytes while preventing raw internal
tokens from becoming misleading public directory names. The publication
manifest records both sides of the mapping.

## Delta classification

For every pre-existing destination, the freshly captured decompressed
`physics.csv` and `aux_state.jsonl` bytes match the committed logical payload
byte-for-byte. Changes are therefore limited to:

- v6.37 metadata and hardware-timing streams;
- native gzip container bytes;
- the eight newly published later-route destinations;
- a fresh B run manifest and fresh B/C identity metadata.

The B and C passes have identical physics, aux, and hardware-timing files for
all corresponding segments. Their intended identity difference remains
metadata/run ownership.

No trace row or event was edited. Compression used the reviewed native
`TracePayloadCompressor` with threshold zero so every committed payload is
compressed. During validation, Mono 6.12 was found to publish a zero-byte file
when compressing an empty stream. A focused failing test established the
defect; the compressor now emits the canonical empty gzip member and verifies
both bulk and streamed empty payloads.

## Validation

The following evidence was run against the installed fleet:

- strict Java metadata, physics, aux, and hardware-timing loading across all
  47 destinations;
- immutable publication, compression, and trace-reference guards (17 tests);
- native `TracePayloadCompressor` filter (12 passing cases);
- native non-gate `HardwareTiming` filter (22 passes, one expected ROM-backed
  skip before the ROM-filtered gate run);
- the ROM-backed S3K native gate fleet using the verified `s3k.gen` ROM:
  9 passes, zero failures or skips. The 15-segment Sonic+Tails gate and both
  25-segment Knuckles identity gates were rerun after updating their frozen
  v6.37 expectations.

The full Sonic+Tails capture recorded 15 segments over 466,334 movie frames.
The B and C runs each recorded 25 segments and 22 transitions. CNZ recorded
42,253 rows and MGZ 35,912 rows.

Replay frontiers were not remeasured during this publication-only phase.
Accordingly all newly added later-route replay frontiers are **unmeasured**,
not green or red. Existing frontier observations remain the prior log entries.
LBZ is specifically regeneration-and-load validated only.

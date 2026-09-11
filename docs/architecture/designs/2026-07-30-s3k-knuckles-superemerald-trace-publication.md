# S3K Knuckles Super-Emerald Complete-Run Trace Publication

## Goal

Publish comparison-only BizHawk traces for
`s3k-knuckles-complete-superemeralds.bk2` using the same native S3K
complete-run recorder and segmented run layout already used by the repository's
S3K complete runs.

## Scope

The source movie is curated into
`src/test/resources/traces/s3k/_movies/`. A single native recorder invocation
with run id `s3k-knuckles-complete-superemeralds` produces a new immutable run
tree at:

`src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds/`

The publication includes every segment discovered by the recorder, each
segment's compressed physics and auxiliary payloads, hardware-timing stream,
metadata, and the root `run_manifest.json`. Existing Sonic and diagnostic
Knuckles fixtures remain unchanged.

This task does not add engine behavior, recorder features, or trace-derived
runtime synchronization. It extends the existing immutable hardware-timing
publication inventory and its guard assertions for the new run.

The source BK2 has SHA-256
`aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc`.
Its header identifies BizHawk 2.11, `Genplus-gx`, locked-on S3K, and checksum
token `C5B1C655C19F462ADE0AC4E17A844D10`; despite the header's `SHA1` label, that
32-hex-digit token is the ROM MD5 and is published as `rom_checksum`. The
source ROM is independently verified by SHA-1
`CFBF98C36C776677290A872547AC47C53D2761D6` and CRC32 `63522553`.

## Data Flow

1. Resolve the source BK2 and a discovered locked-on S3K ROM from the main
   workspace, then validate the BK2 header, its 434,417 input rows, byte-exact
   MD5/checksum token, and the ROM's independent SHA-1 and CRC32 identity.
2. Copy the BK2 byte-for-byte into the trace movie catalogue.
3. Run `tools/bizhawk-headless/run.sh` against the discovered S3K ROM with
   `--run-id s3k-knuckles-complete-superemeralds`.
4. Validate the staged publication before copying it into test resources:
   manifest segment inventory, per-segment metadata identity, compressed
   payload and hardware-timing presence, and absence of uncompressed committed
   payloads.
5. Copy the complete output tree without renaming or coalescing repeated zone,
   bonus-stage, or special-stage segments.
6. Generate a point-in-time validation report under
   `docs/architecture/validation/` that freezes the ordered segment and
   transition inventory, final movie coverage, and exact lengths and SHA-256
   hashes for the manifest and every published file. A reviewer must compare
   the inventory with the movie/capture evidence before it becomes the
   committed baseline.
7. Add every segment to
   `src/test/resources/traces/s3k/hardware-timing-publication.tsv` and extend
   `TestCommittedHardwareTimingFixtures`' exact destination, ownership, and run
   manifest assertions. The inventory records schema/version/frame/event/edge
   facts as well as all four per-segment files.
8. Classify every schema-2 segment whose represented route reaches a direct
   Kosinski consumer. Extend `SCHEMA_TWO_DIRECT_CONSUMER_FIXTURES` for the new
   AIZ, HCZ, MGZ, CNZ, or ICZ destinations that satisfy that semantic contract;
   do not infer inclusion from the zone token alone.

## Naming and Ownership

The run id follows the BK2 basename without its extension. Keeping the result
under `runs/` preserves the movie's ordered transitions and allows repeated
visits to the same zone or stage without colliding with canonical Sonic
`*_completerun` directories.

The BK2 remains controller input. Recorded physics, auxiliary state, hardware
timing, and manifest data are comparison-only evidence and cannot drive engine
gameplay state.

## Failure Handling

The native publisher writes into a new output directory and refuses to
overwrite it. A failed or interrupted capture is discarded and rerun into
another new staging path. No partial run tree is copied into test resources.

If the movie exposes a recorder limitation, the limitation is handled as a
separate recorder change with its own tests and regeneration review; captured
files are not hand-edited to bypass it.

## Validation

- Verify the curated BK2 hash equals the source hash and distinguish its
  MD5-shaped header checksum token from the independently hashed ROM SHA-1 and
  CRC32.
- Verify `run_manifest.json` parses and names the expected run id, source BK2,
  ROM checksum, recorder version, and every published segment.
- Verify each manifest segment directory contains `metadata.json`,
  `physics.csv.gz`, `aux_state.jsonl.gz`, and `hardware_timing.jsonl`.
- Verify metadata frame offsets/counts and identities match the manifest.
- Independently review the ordered token/kind/zone/act inventory, offsets, row
  counts, transitions, exact file hashes/lengths, and proof that the last
  active segment finalized through the terminal arithmetic of the 434,417-row
  movie. Self-consistency alone is not sufficient evidence against premature
  termination.
- Verify every committed hardware stream is nonempty and has valid first/last
  edges; classify schema-2 direct-consumer routes against the owning ROM
  behavior.
- Run the native BizHawk complete-run publication/unit suite and a ROM-backed
  capture verification for this exact movie and run id.
- Run `TestCommittedHardwareTimingFixtures`,
  `TestTraceFixtureCompressionGuard`, `TestTraceCatalogRunDiscovery`, and the
  run manifest/catalog loader tests.
- Run the full Maven suite on JDK 21 during integration and compare against the
  updated baseline as required by the project workflow.

## Documentation and Integration

Update `tools/bizhawk/README.md` or the native publication documentation with
the exact capture command and the new immutable capture identity. Record the
fixture addition in `CHANGELOG.md`, and update the `README.md` release/change
log before merging the worktree branch into `develop`. Stage this design, the
implementation plan, the validation report, and all supporting publication
artifacts together with the generated fixtures.

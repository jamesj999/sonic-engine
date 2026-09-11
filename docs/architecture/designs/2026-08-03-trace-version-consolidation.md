# Trace Version Consolidation

## Status

Proposed for the pre-release `develop` trace fleet. This design replaces every
earlier trace generation, including the fixtures present on `master`. Old trace
files are not a supported interchange format or user-facing compatibility
surface.

## Problem

`master` contains eleven trace metadata files. Its production trace contract is
identified by `csv_version: 4`; it has no `trace_schema`, hardware-timing
schema, run-manifest schema, S2 fleet, or S3K fleet. The only two commits on
`origin/master` after its common ancestor with `develop` do not touch tracing.

During development, independent recorder campaigns incremented several version
axes:

- native recorder stamps range across S1 `3.x`, S2 `9.x`, and S3K `6.x`;
- level trace schemas range through 3, 5, 6, 7, 9, and 10;
- the current 42-column level CSV is called version 7;
- S3K hardware timing has schemas 1 and 2;
- special-stage rows carry a separate schema counter that the Java metadata
  loader does not consume;
- run manifests have schemas 1 and 2; and
- the native-prelude bootstrap decision parses the S2 recorder version.

Most of those generations were never released. Supporting them makes
`develop` backward-compatible with its own discarded intermediate states and
forces recorder differential tests to enumerate historical literals that no
current fixture uses.

## Decision

There is exactly one supported generation: **develop v5**. Version 5 is one
format generation above master's `csv_version: 4` contract, but v4 is a
numbering baseline rather than a compatibility target. No old trace is required
to load.

Every useful route is regenerated into v5. The eight S1 credits-demo fixtures,
their concrete replay classes, and focused fixture consumers are retained. A
first-class native credits-demo mode in the BizHawk headless harness ports the
ROM ending-demo selection and lifecycle from
`tools/retro/s1_credits_trace_recorder.py`. The stable-retro script is reference
behavior only; it is not part of the resulting capture pipeline. The native
mode records the ROM's own controller stream and does not invent a BK2
dependency. Credits replay continues to source that same ROM demo data through
`DemoInputPlayer`.

The noncanonical `metadata_retro.json`, `physics_retro.csv`, and
`aux_state_retro.jsonl` alternate sidecars are deleted after their evidence is
accounted for. No canonical credits fixture directory is deleted.

## Canonical develop contract

### Metadata envelope

Every current fixture, including special-stage segments, declares:

```json
"recorder": "native-bizhawk-headless",
"recorder_version": "3.0",
"trace_schema": 5
```

`3.0` is one recorder major above the highest ordinary recorder version on
master (`2.2`). Game and mode remain explicit in existing fields such as
`game`, `trace_profile`, `trace_type`, and `run_id`; they are not encoded in the
version string.

Current fixtures do not emit `lua_script_version`, and the Java loader does not
parse it. Neither parser behavior nor replay behavior may branch on
`recorder_version`.

### Level physics rows

For `trace_schema: 5`, the level `physics.csv` contract is the current symmetric
42-column player/sidekick row formerly called CSV v7. Current metadata does not
emit a separate `csv_version`; the trace schema identifies the row shape.

All 11-, 18-, 19-, 20-, 22-, 37-, and 38-column compatibility readers and
positive-path tests are removed. Resource-backed legacy level synthetics have
no place in the publication candidate: active semantic tests build v5 inputs
under test-owned temporary roots, and the obsolete installed synthetic files
are explicit deletion deltas at publication. Small legacy documents may remain
only as inline negative inputs that prove the strict v5 parser rejects them.
A source guard rejects `TraceData.load` or fixture-loader dependencies beneath
`src/test/resources/traces/synthetic` and rejects retired metadata keys or row
widths in positive-path test helpers. Legacy literals are permitted only in a
dedicated rejection-test class whose methods assert load failure. The guard has
its own accepted/rejected source samples, so an indirect schema-3/4/6/8/9
fixture dependency cannot return. The ordinary level parser accepts exactly
the 42-column v5 row.

`TraceMetadata` no longer models `luaScriptVersion` or `csvVersion`.
`TraceData` performs strict profile-aware dispatch instead of passing a version
fallback to `TraceFrame`: ordinary level profiles require one 42-column v5 row,
while special-stage profiles use their dedicated game-owned readers.
Animation and subpixel availability are inherent in the v5 level contract;
their predicates no longer inspect a removed CSV version.

### Special-stage rows

Special stages use their dedicated, game-owned row readers. They also carry
`trace_schema: 5`. The game and `trace_profile` select one fixed current row
shape: 14 columns for S1, 48 for S2, and 20 for S3K. `ss_csv_version` is removed;
ordinary level parsing never interprets a special-stage row.

### Hardware timing

V5 has one hardware-timing grammar: the current complete authority registry
formerly called timing schema 2. It supports both `kos_module_queue` and
`kos_decompression_queue`, with the existing kind, ordinal, stable submission
fingerprint, and service-boundary checks.

`hardware_timing_schema` is removed. The presence of
`hardware_timing.jsonl` in a v5 trace opts that fixture into the timing port;
absence means no recorded timing input. The former module-only registry is
removed. This removes a redundant develop-only version axis without broadening
the hardware-timing authority described by hard rule 4.

### Run manifests

Run manifests declare `trace_schema: 5`, not `run_schema`. V5 includes the
current `dynamic_art_gap_transitions` structure formerly called run schema 2.
All manifests emit the array, including an empty array when there are no gaps.
The permissive schema-1 path is removed.

Their provenance envelope matches trace metadata:

```json
"recorder": "native-bizhawk-headless",
"recorder_version": "3.0",
"trace_schema": 5
```

They do not emit `lua_script_version`. `TraceRunManifest` removes that property
and all schema-1/schema-2 branches.

### Capabilities and bootstrap

Optional diagnostic data remains opt-in through `aux_schema_extras`. Capability
names describe a semantic feature, not a migration generation. The
develop-only `dynamic_art_transfer_state_per_frame_v1` name becomes
`dynamic_art_transfer_state_per_frame`.

The S2 `lua_script_version >= 9.2-s2` bootstrap gate is replaced by an explicit
`native_prelude_bootstrap` capability. The recorder advertises it only when the
frame-0 snapshot evidence required by `compareBootstrapFrame0` is present.
Fixtures without the capability are ineligible; no recorder-version inference
exists.

## Recorder and fixture migration

All native writers move to the shared v5 metadata contract before capture.
The Lua recorders remain non-authoritative but their emitted metadata and
manifest envelopes also move to v5, using
`recorder: lua-bizhawk-diagnostic` and `recorder_version: 3.0`. Their internal
source-history comments may retain historical names. This keeps hook-driven
scratch output loadable by the strict v5 tools without creating a compatibility
parser or allowing Lua output to become canonical.

Each production capture family is regenerated with the reviewed native writer.
The matrix is derived from the reconciled retained fixture tree rather than
copied from the historical July list. The current matrix contains 36 serial
invocations: the historical 32, the upstream S1 emerald complete run, the
independently published 67-segment S3K Knuckles complete-super-emerald run,
and two independent movie-free S1 credits captures. The S3K super-emerald run
has its own curated BK2 and `--run-id`; it cannot be represented by the Sonic
and Tails complete-run or multi-bonus invocations without dropping retained
segments and committed timing evidence.

Publication follows the existing exact-byte contract:

1. capture to scratch;
2. freeze segment inventory, lengths, row/event counts, and SHA-256 values;
3. compare decompressed physics, aux, timing, and manifests with the predecessor;
4. classify every byte delta;
5. install the native output byte-for-byte; and
6. run the recorder gates, Java fixture guards, and replay frontier sweep.

Metadata and manifest envelope changes, manifest gap-array additions, and the
removal of redundant version fields are expected one-time migration deltas.
They are frozen literally in the candidate report and require exact-byte user
approval just like payload changes; this design does not create a normalization
exception. Physics and auxiliary payloads must otherwise remain byte-identical
unless the recorder correction already approved for that family explains a
delta. Timing must remain identical to the former schema-2 semantics; its
authority and events do not change.

### S1 credits predecessor-oracle failure

The first native all-eight capture proved that the stable-retro credits
fixtures are useful predecessor evidence but are not an exact ROM oracle. All
eight legacy files record `v_framecount` as constant `0000`, even though both
recorders name `$FE04` and the S1 disassembly resets then increments that word
before `MoveSonicInDemo`; the native capture observes `0001` on the first
stored row. Six routes also contain disclosed in-route input or physical-state
deltas, so those differences cannot be described as metadata, new-column, or
end-boundary changes.

This discovery does not permit normalization or silent exclusions. The
20-to-42 comparator remains literal, reports every shared-field mismatch, and
must remain red when the predecessor differs. For S1 credits only,
predecessor equality is diagnostic evidence rather than a publication oracle.
A native candidate may advance to explicit approval only when all of the
following are frozen in the candidate report:

1. the complete unmodified predecessor delta inventory, including row-count
   differences and the first mismatch for every shared field;
2. ROM/disassembly evidence for the capture boundary, controller source,
   setup writes, and every claimed recorder correction;
3. two clean, independent all-eight native captures with byte-identical
   logical physics and auxiliary payloads, segment inventory, and metadata
   other than deliberately injected recording-date provenance;
4. raw-host-to-writer checks showing that rows at the disclosed first native
   divergences contain the values actually present in emulated RAM; and
5. successful candidate validation plus all eight Java credits replays, with
   any changed replay frontier classified before approval.

The remaining physical deltas are not pre-approved by this amendment. They
stay visible for Task 9 adjudication and the Task 10 exact-byte user approval.
The installed credits fixtures remain untouched until that approval.

The native S1 credits mode captures all eight ROM ending-demo routes with the
canonical 42-column v5 level writer and current S1 auxiliary-event engine. It
preserves the existing `trace_type: credits_demo`,
`input_source: rom_ending_demo`, demo index, and demo slug semantics. For each
route, the candidate report compares every column shared with the predecessor's
20-column evidence, field by field. New v5 columns and auxiliary-event changes
are reported and classified separately. A mismatch is reported rather than
normalized and is adjudicated under the predecessor-oracle failure contract
above. All eight candidates remain subject to
the same inventory, hash, exact-byte approval, and replay gates as every other
fixture family. The old fixture remains installed until its native replacement
has passed those gates and is approved.

The credits CLI also supports an optional audit-only
`--credits-raw-observations <path>` sidecar and stable capture identity. They
are valid only for movie-free `credits_demo --credits-target all`. Before
capture, candidate root, installed root, final sidecar, and sidecar parent are
resolved through existing-ancestor and real-path checks. The command rejects
overlap, symlink traversal, existing final paths, and non-absent output roots.
The sidecar never changes metadata, physics, auxiliary payloads, or publication
membership.

The identity is supplied by `--credits-raw-observation-id <id>`. It is an
opaque, non-empty printable ASCII token with no control characters, path
separators, `.` or `..` path identities, and no wall-clock-derived default.

The sidecar is a deterministic JSONL stream with format
`openggf-s1-credits-raw-observations-v1`. Its header identifies the capture,
resolved candidate root, ROM, and recorder; each record contains only route and
demo identity, row ordinal, predecessor-common field, canonical RAM address and
endianness or a named derivation, and the raw formatted value. It contains no
predecessor value, comparator result, emitted CSV value, candidate hash, or
candidate payload bytes. The collector reads directly from emulated RAM before
the writer formats the row and spools records incrementally to a private file
under the sidecar parent. It enforces canonical demo/row/field order, at most
86,400 observations and 64 MiB, and never retains the complete capture in
memory. Its final completion record repeats the stable capture identity and
resolved candidate root and records all-eight completion, per-route and total
row counts, total observation count, pre-completion byte count, and the SHA-256
of every preceding sidecar byte. The reader rejects any missing, duplicate, or
inconsistent completion record.

On capture or candidate-publication failure, only the private spool is removed
and the final sidecar remains absent. After successful candidate publication,
the recorder writes a completion record and seals the spool using a no-replace
atomic move. A seal failure makes the already-published scratch candidate
quarantined and ineligible for approval; it is never reused or silently paired
with another sidecar.

Task 9 freezes one distinct sidecar with each credits invocation. The
postprocessor, not the collector, reads the selected sidecar, frozen comparator
report, predecessor inventory, and candidate payload; it selects every
disclosed first divergence and binds it to the candidate logical-payload hash.
The verifier independently rereads the raw sidecar and recomputes the selection
instead of trusting the final evidence fields. Raw sidecar, comparator report,
and final evidence all use no-replace publication and must resolve outside both
candidate and installed roots, including through symlinks. Missing, extra,
duplicate, out-of-order, malformed, swapped-capture, stale-hash, or
raw-versus-emitted evidence is rejected. Candidate CSV values alone are never
accepted as raw-host observations.

Every deletion is recorded separately from regenerated candidate output so it
cannot be mistaken for a capture delta. The immutable candidate report lists
each obsolete `*_retro` sidecar and unused resource-backed legacy synthetic
with its predecessor stored and logical hashes. The approved installer rejects
any deletion outside that exact manifest and unconditionally rejects an
`s1/credits_*` directory, credits replay class, or focused credits consumer.

## Guards

A committed-fleet guard enforces:

- `trace_schema: 5` on every fixture;
- no `lua_script_version` on current fixtures;
- `recorder: native-bizhawk-headless` and `recorder_version: 3.0` on current
  fixtures;
- no `csv_version`, `ss_csv_version`, `hardware_timing_schema`, or `run_schema`;
- the fixed v5 row width selected by the fixture's game and profile;
- the current full timing grammar for every `hardware_timing.jsonl`; and
- required dynamic-art gap evidence in every run manifest; and
- no historical S1/S2/S3K recorder-stamp compatibility literals in native
  differential gates.

`TestHardwareTimingAuthorityGuard` follows the unversioned
`dynamic_art_transfer_state_per_frame` capability so the rename cannot weaken
its authority scan.

There is no legacy path allowlist.

The Java metadata record, strict level loader, and hardware-timing loader form
one compile-time migration boundary. Removing `hardware_timing_schema` also
removes its generated record accessor, which the old timing loader and timing
tests consume. Tasks 4 and 5 therefore land as one green implementation
checkpoint: no intermediate commit retains a dummy accessor, default schema,
or other compatibility shim. This atomic sequencing changes no timing
authority; the resulting loader still admits only matching, prepared,
production-submitted ROM work under hard rule 4.

## Testing strategy

Implementation is test-first:

- writer tests first require the v5 metadata keys and reject historical keys;
- parser tests first prove the current v5 shape and reject every old row width;
- timing tests first prove v5 authorizes both S3K work kinds without a second
  schema selector;
- run-manifest tests first prove v5 requires gap transitions;
- bootstrap tests first prove capability-based eligibility without recorder
  version parsing;
- credits-recorder tests first prove all eight ROM ending-demo identities,
  lifecycle boundaries, ROM-owned input capture, and canonical v5 output;
- the fleet guard first fails on all intermediate develop metadata; and
- differential and publication tests compare current output directly rather
  than normalizing historical version literals.

After fixture publication, the native recorder suite, focused Java contract
tests, all `*TraceReplay` tests, and the full Maven suite run on JDK 21.

## Documentation and policy migration

The version consolidation updates `AGENTS.md` and `CLAUDE.md` together. Hard
rule 4 describes the one v5 timing grammar rather than the removed timing
schema-1/schema-2 split. The cross-game hardware-timing architecture contract
gets an explicit supersession section; maintained recorder behavior,
publication, trace-guide, and README documentation describes v5 only.

Both mirrored trace-replay skill trees are updated together because their
current workflow text treats S3K timing schemas 1 and 2 as live choices.
Historical audits, validation reports, and old frontier entries remain
historical evidence and are not rewritten to pretend their captures used v5.

## Alternatives rejected

### Keep all fields and merely renumber them

Resetting schema 7 to 5 and timing schema 2 to 1 without removing old branches
would make the labels smaller while retaining the maintenance debt.

### Retain independent schema counters reset to 1

This still leaves four version axes whose only supported combination is the
current one. Because traces and manifests are repository-owned evidence rather
than public interchange formats, lockstep migration is preferable to permanent
cross-product validation. The single `trace_schema` covers the complete trace
evidence contract.

### Preserve old committed fixtures unchanged

That makes both released test data and unshipped intermediate outputs permanent
compatibility obligations even though traces are repository-owned test
evidence, not a public interchange format. The native recorder and exact-byte
publication gates make a one-time migration safer and cheaper than maintaining
the branches indefinitely.

# Load-time profile cardinality and storage audit

Date: 2026-07-29

## Question

This audit estimates how many deterministic load-time records the proposed
`PROFILED` mode needs across Sonic 1, Sonic 2, and Sonic 3 & Knuckles, and whether a
human-readable manifest is an efficient runtime representation.

The unit stored by the runtime is not a caller, PLC list, trace occurrence, or ordinal.
It is one measured service cost for a stable hardware submission fingerprint under a
versioned service model. Repeated observations contribute to that record's aggregate;
they do not create runtime rows.

## S3K evidence

The existing
[`S3K Kosinski queue caller audit`](2026-07-29-s3k-kos-queue-caller-audit.md)
found:

- 187 queue call instructions;
- 124 adjacent submission clusters;
- 90 stable routine/object owners;
- 24 `Kos_modules_left` polls and two direct-queue-count polls.

Those are useful implementation-coverage counts, but none is the manifest row count.
One call site can submit different ROM descriptors, and many call sites can submit the
same descriptor.

The 47 committed S3K `hardware_timing.jsonl` streams contain 1,110 completion
observations:

| Kind | Observations | Unique fingerprints |
|---|---:|---:|
| Direct Kos queue | 321 | 125 |
| KosM parent queue | 789 | 173 |
| Total | 1,110 | 298 |

The repetition distribution confirms that aggregation materially reduces the runtime
dataset: 131 fingerprints occur once, 77 twice, and the remaining 90 occur between three
and 53 times.

Of those streams, 41 use schema 1: they contain 569 KosM parent completions but omit the
direct child completions created by those parents. Only six schema-2 streams provide the
321 direct observations. KosM parents are composite coordinators in the approved service
model. Their own
additional profile cost is always zero and can be expressed as one typed provider rule,
not 173 manifest rows. The child standard-Kos jobs receive measured costs. Therefore the
125 unique direct fingerprints are a lower bound from the six schema-2 streams, not an
upper bound for the current movie corpus. Replaying the 41 schema-1 source movies with the
diagnostic recorder will expose additional child identities. The current completion-only
streams establish partial cardinality, not duration.

The counts are reproducible with:

```bash
find src/test/resources/traces/s3k -name hardware_timing.jsonl -type f -print0 |
  xargs -0 jq -r '[.kind,.submission_fingerprint]|@tsv' |
  sort | uniq -c
```

Classify a stream as schema 2 for this audit when its distinct `kind` values include
`kos_decompression_queue`; otherwise it is one of the 41 schema-1 streams.

## S1 and S2 static bounds

S1's `docs/s1disasm/_inc/Pattern Load Cues.asm` has 32 `plcid_*` pointer rows and
32 `PLC_*: plcheader` lists containing 192 `plcm` entries. Normalizing fields 2 and 3
(art-source and VRAM destination after splitting on whitespace/comma and removing comma
suffixes) yields 138 distinct descriptors:

```bash
awk 'BEGIN{FS="[\\t ,]+"}
  /^[[:space:]]*plcm[[:space:]]/ {
    key=$2 FS $3; gsub(/[,;]/,"",key); seen[key]=1
  }
  END { for (key in seen) count++; print count }' \
  "docs/s1disasm/_inc/Pattern Load Cues.asm"
```

S2's `ArtLoadCues` table is at `docs/s2disasm/s2.asm:89194`; its primary list region
ends before the duplicate layout lists at line 89880. The 67 `PLCptr_*` rows address
59 distinct primary `PlrList_*: plrlistheader` definitions. The region contains 251
`plreq` requests and 173 distinct normalized destination/art-source pairs:

```bash
sed -n '89194,89879p' docs/s2disasm/s2.asm |
  awk 'BEGIN{FS="[\\t ,]+"}
    /^[[:space:]]*plreq[[:space:]]/ {
      key=$2 FS $3; gsub(/[,;]/,"",key); seen[key]=1
    }
    END { for (key in seen) count++; print count }'
```

These are static upper estimates for PLC submissions, not measured manifests. The S1/S2
queue branch may include additional identity fields, aliasing, or runtime-submitted jobs,
so its production fingerprints remain authoritative after that branch is integrated.
DPLCs, animated tiles, eager level-layout art, and unrelated decompression are excluded
because they are not jobs in the proposed hardware timing queue.

## Combined estimate

Using current S3K observed direct fingerprints and normalized S1/S2 PLC descriptors gives
an initial cross-game estimate of:

| Game | Estimated cost-bearing rows |
|---|---:|
| S1 | 138 |
| S2 | 173 |
| S3K schema-2 lower bound | 125 |
| Total | 436 |

The 436 total is a provisional lower-bound estimate, not a coverage target or capacity
limit. Allowing generously for schema-1 child identities, untraced S3K routes, future
queue parity, fingerprint distinctions hidden by the static S1/S2 scan, and future
service-model versions, the practical scale is still hundreds to low thousands of rows,
not tens of thousands.

## Storage recommendation

Keep one versioned JSON manifest per game and service model. At this cardinality a binary
format, database, compression layer, or caller-event indirection would add complexity
without a meaningful runtime or distribution benefit.

The 298 unique S3K completion keys occupy about 27 KiB as raw tab-separated
`kind + SHA-256 fingerprint` values. A full auditable JSON row with cost, sample count,
range, and provenance is expected to average roughly 250–400 bytes. On that basis:

- the 125-row S3K schema-2 lower bound is roughly 31–50 KiB;
- the provisional 436-row cross-game lower bound is roughly 109–174 KiB;
- even 2,000 rows remain roughly 0.5–0.8 MiB before JAR compression.

Runtime loading should parse the JSON once per game/profile version into a hash map keyed
by a compact value object:

```text
(HardwareWorkKind, submissionFingerprint, serviceModelVersion) -> LoadTimeRecord
```

The manifest should store repeated samples only in a tooling/provenance artifact. The
runtime row stores the deterministic aggregate (`serviceFrames`) plus audit metadata such
as sample count, min/max, and compact fixture identifiers. If provenance becomes the
dominant size, use a manifest-level fixture dictionary and let rows contain integer
fixture indexes. This retains readable diffs while avoiding repeated long fixture paths.

Do not key by PLC ID or caller site. Those identifiers are smaller but are not stable
submission identities: aliases, destinations, module children, and the same asset loaded
at different destinations would otherwise collide or require game-specific lookup logic.

## Plan consequences

1. The runtime manifest remains JSON and is loaded into a map; no storage optimization is
   needed for the first implementation.
2. KosM composite-parent zero cost is a typed S3K service-model rule, not manifest data.
3. Raw observations live only in the measurement/publication artifact. Runtime manifests
   contain one lower-median aggregate per fingerprint.
4. A manifest-level fixture dictionary is the only worthwhile compacting measure to
   include now.
5. Cardinality and duration must remain separate: existing completion streams support
   these counts but cannot supply profiled service times.

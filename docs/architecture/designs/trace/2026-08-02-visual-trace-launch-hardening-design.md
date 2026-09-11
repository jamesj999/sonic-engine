# Visual trace launch hardening

Date: 2026-08-02

## Problem

The master-title visual trace picker has fallen behind the headless replay
pipeline in five related ways:

1. legacy stage-free complete-run recordings are shown only as independent
   level traces because they predate `run_manifest.json`;
2. a standalone level trace with per-frame dynamic-art state compares row zero
   before the live PLC/DPLC lifecycle has established a trace-local publication
   origin;
3. standalone special-stage dispatch recognises and parses only the Sonic 2
   profile, so Sonic 1's 14-column rows are sent through the ordinary level
   parser;
4. run launch rejects legacy level metadata whose manifest correctly declares
   `complete_run` while the older segment metadata has no `trace_profile`;
5. strict hardware-timing parsing rejects an older schema-2 recorder ordering
   in which a same-frame direct `pre_main_loop` completion was emitted before a
   module `post_objects` completion.

Run parsing is synchronous and duplicates work between launch validation and
the replay walker. The picker consequently appears frozen and then reports
launch failures only to the console.

## Constraints

- Trace data remains comparison-only. No physics, auxiliary state, or recorded
  hardware work may hydrate gameplay.
- Hardware timing may affect only readiness of matching production-submitted
  ROM work. Kind, ordinal, fingerprint, and service-boundary validation remain
  strict.
- Shared runtime code receives no game, zone, route, or frame carve-outs.
- Existing independent trace entries remain available alongside grouped runs.
- The launch UI must show a frame of feedback before potentially expensive
  synchronous parsing starts and must retain a readable failure until the user
  acknowledges it.
- Run transfer playback remains production-driven. The catalog may describe
  legacy segment adjacency, but it may not force a level or mode transition.

## Design

### 1. Legacy complete-run catalog grouping

After ordinary trace discovery, `TraceCatalog` groups catalog-level trace
entries that meet all of these structural conditions:

- their directory name ends in `_completerun`;
- metadata resolves the same nonblank `source_bk2`;
- the metadata profile is `complete_run`, or is absent on a legacy recording;
- at least two non-overlapping segments remain after sorting by BK2 offset.

The entire cohort must be strictly ordered and non-overlapping. If any member
overlaps or has an invalid range, no grouped run is advertised; catalog code
never drops a conflicting member and presents a partial cohort as complete.

For each valid cohort, the catalog builds an in-memory schema-1
`TraceRunManifest`. Segments retain their real catalog directory, BK2 range,
ROM zone and act. The comparison-row count excludes an optional recorder CSV
header. Catalog counting, ordinary `TraceData`, the stored frame-domain scan,
and typed special-stage loaders share the same first-meaningful-line rule, so
older headerless fixtures retain row zero and generated manifests match the
payload actually prepared for launch.
Its transition list is empty because a segment offset is not
evidence of the precise production mode-change row. The run coordinator treats
a transitionless level adjacency as satisfied only by a production-created
distinct level load with a matching destination identity and an ordinary or
level-advance load cause; death restarts and interior returns remain excluded.
Recorder-published transition records retain their stricter kind and boundary
window checks. The run id is the shared movie basename. The synthetic
manifest's segment root is the game catalog directory, so no payload is copied
and no parent-traversing manifest path or symlink is introduced.

The individual entries remain in the picker. The grouped entry is an
additional run row labelled by run id and segment count. Existing manifest
runs under `runs/` are unchanged and take precedence if they already use the
same game/run id. Picker rows, loading feedback, selected-entry text, and held
errors all derive a run entry's identity from the manifest run id rather than
the synthetic entry's game-directory root.

Schema 1 is intentional: these recordings have no published run-gap dynamic
art journal. Their per-segment dynamic-art rows are still compared, while
unrepresented gaps remain production-driven and cannot invent transfer data.

### 2. Profile compatibility and special-stage dispatch

Run validation accepts a missing segment metadata profile only when all of the
following hold:

- the manifest segment kind is `level`;
- the manifest profile is `complete_run`;
- metadata `trace_profile` is absent.

Every explicit non-null mismatch and every special/bonus profile mismatch
still fails. This is a schema-era compatibility rule, not a route exception.

Standalone special-stage dispatch recognises `s1_special_stage`,
`s2_special_stage`, and `s3k_special_stage`. The launcher consumes the existing
profile-polymorphic `TraceRunSpecialStageRows` view rather than storing the
Sonic 2 payload type directly. The view exposes metadata, row count, row
admission, optional terminal row, and hardware schedule. Sonic 2 preserves its
captured `stage_finished` boundary; Sonic 1 and S3K use their last recorded
row. Every adapter invokes the strict `HardwareTimingStreamLoader` whenever
metadata advertises a timing stream. Only metadata with no timing-stream schema
receives an empty schedule. Input and lag admission continue to be derived from
typed rows only, including S3K lag rows; no special-stage adapter may infer
that every recorded row advances gameplay.

### 3. Trace-local dynamic-art publication origin

Single-level visual sessions adopt the existing whole-run launch ordering. In
the game-bootstrap callback, after the new gameplay context exists but before
`TraceReplayDriver.start` installs the comparator, the launcher asks that
context's PLC coordinator to transfer comparison-segment ownership from its
ordinary automatic policy to external management. The transfer closes any
completed automatic diagnostics window, then the launcher opens trace segment
zero through `TraceRunReplayWalker.DynamicArtSegmentController`.

This is deliberately not deferred to `beforeProductionIteration`: by then a
replay-owned production claim could already have published against the wrong
origin. It is also not applied to the old master-title/fade context. The
master-title launch callback itself is deferred until the enclosing logical
iteration has finished, so a newly selected game's ordinary lifecycle may
already have opened and published a diagnostics window before the callback.
That completed automatic window is valid prior state, not stale trace
ownership. “Completed” is proven by its latest diagnostics snapshot carrying a
published row; an open automatic window with no published row remains an
in-flight invariant failure even when its edge buffer is empty. Closing a
completed window preserves production-created ledger and delivery state;
opening the externally managed window then establishes trace row zero before
`TraceReplayDriver.start` performs its first nested replay iteration.

Comparator row zero is then followed by exactly one production publication
with the same generation and row number. Segment close and session abort paths
release external ownership and close or reset the comparison window without
leaving it armed for ordinary gameplay. Abort closes through the stored owning
context before consulting or destroying the process-global current context, so
a changed/null locator or a later teardown failure cannot strand the owner.
Graceful close remains strict: if production buffered an edge before the first
row, it throws a dedicated unpublished-row exception rather than inventing a
terminal row. Abort catches only that typed failure and invokes a
production-owned window-abandon operation on the stored context. Abandoning
clears only buffered comparison edges and row-publication coordinates. It
preserves mapping-frame decisions, the production ledger, pending S1/S2 work,
preparations, gap state, and monotonic transfer/edge identities, so real queued
work retires normally before automatic segment ownership resumes. Unrelated
close and gap-journal invariant failures remain visible. The ownership-transfer
operation rejects a context that is already externally managed, so a stale
visual-session owner still fails visibly. It also refuses to abandon or rebase
an unpublished automatic window: callback-after-step ordering guarantees the
supported automatic window is completed, and a violation of that ordering
remains an error rather than discarding production evidence.

No expected edge, frame value, or trace payload is sent into the production
lifecycle; the comparator still pulls immutable diagnostics after production.

### 4. Schema-2 hardware ordering compatibility

The stream loader keeps canonical order strict, but recognises one legacy
same-frame emission pair:

1. `KOS_DECOMPRESSION_QUEUE` at `PRE_MAIN_LOOP`;
2. `KOS_MODULE_QUEUE` at `POST_OBJECTS`.

When that adjacent inversion is encountered under hardware timing schema 2,
the loader validates both events normally and canonicalises the pair in memory
to the current production service order. It does not alter raw frames,
boundaries, kinds, ordinals, or fingerprints. Any other inversion remains an
error. Per-kind ordinal monotonicity and unique `(kind, ordinal)` identity are
checked on the source stream before canonicalisation, and the entire normalized
list must pass `CANONICAL_ORDER` before a schedule is constructed. Thus a swap
which exposes an inversion against a predecessor or successor is still
rejected.

This compatibility is cross-game and schema-based. It changes only when two
already-recorded matching jobs become eligible at their declared boundaries;
it neither creates work nor changes what work is submitted.

### 5. One-pass run preparation

`TraceCatalog` gains a prepared-run result containing the parsed movie and the
single `TraceRunReplayWalker.plan` result. Each segment plan also retains its
typed `TraceRunSpecialStageRows` payload when the segment is a special stage;
later admission reuses that payload instead of reopening and reparsing its CSV.
The metadata-only `TraceData` view remains alongside it for shared dynamic-art
and hardware coordination, but the large profile-specific physics rows are
parsed only once. Launch-time checks use those loaded objects to validate:

- segment zero is a level;
- all segment BK2 ranges fit the parsed movie;
- profile compatibility and row counts;
- manifest/dynamic-art structural validation already performed by the walker.

`TraceSessionLauncher.launchRun` consumes that result directly. The existing
diagnostic validation API delegates to the same preparation path, so tests and
UI receive identical first-failure messages without launch parsing every
segment twice.

### 6. Loading and failure presentation

Pressing Enter changes the picker to `LOADING <entry>` without launching in
that update. On the following update the picker returns the launch action. The
intervening draw guarantees the loading screen is presented before synchronous
parsing begins. Input is ignored while loading.

If launch returns false, the picker clears loading and presents a held launch
failure containing the entry label and the deepest useful exception message.
Bootstrap-callback failures and replay failures that tear down to the title
screen record through the same presentation state. Existing structural run
failure details (segment, cursor, steps, expected/actual identity) remain
available. Enter or Escape acknowledges the error; moving selection dismisses
it as today.

The first implementation intentionally stays single-threaded. Parsing no
longer happens twice, and a pre-rendered loading state gives honest feedback
without introducing background access to engine/configuration singletons.

## Testing

- Catalog tests prove grouped S1/S3K complete runs appear once, preserve their
  ordered parser-accurate segment counts, keep individual traces, and do not
  group unrelated shared movies.
- Run launch validation tests prove only null-metadata `complete_run` level
  profiles receive compatibility.
- Special-stage tests load committed S1 and S2 standalone fixtures through the
  profile-polymorphic path and verify pacing/terminal selection.
- Dynamic-art tests begin from a previously published automatically managed
  lifecycle with a pending production transfer, transfer it to visual
  ownership, and prove the old window closes, transfer identity and outstanding
  state survive without duplicate submission, normal VBlank retirement still
  occurs, and row zero receives a fresh atomic publication. An unpublished
  automatic window and a separately externally managed open window both remain
  launch errors without changing their ownership or generation. A fresh-window
  open failure after successful acquisition restores automatic ownership. An
  abort regression buffers production work before row zero, removes the global
  gameplay locator, and proves stored-owner abandonment disarms the window,
  preserves production identity/pending retirement, and lets automatic
  lifecycle ownership resume. A separate regression proves unrelated close
  failures remain visible.
- Hardware loader tests accept and canonicalise only the legacy schema-2 pair
  while retaining rejection coverage for every other inversion.
- Picker tests prove one loading render precedes launch and launch/parser
  exceptions remain visible and acknowledgeable.
- Focused launch/catalog/timing tests run with JDK 21, followed by the complete
  Maven suite and ROM-backed trace regression checks appropriate to touched
  S1/S2/S3K paths. The committed Knuckles LBZ timing regression loads the
  reported segment directly; retaining the complete run's decoded payload in
  the long-lived full-suite fork is deliberately avoided because it exhausts
  that fork's 1 GiB heap after unrelated tests. Full preparation behavior is
  covered by the shared representative run contract.

## Follow-up boundary

This change includes production-driven adjacent level/zone transfers for the
legacy grouped runs because they are necessary for those entries to be useful.
It does not add rewind across run segments or fabricate missing special-stage
segments for old stage-free recordings. Complete runs that include special or
bonus stages continue to use recorder-published manifests and the existing run
coordinator. Incremental/background decoding of very large run payloads is a
separate performance improvement; this change renders the loading state before
the current synchronous preparation begins.

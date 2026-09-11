# Trace contract remediation implementation plan

## 1. Correct live timing semantics

- Change `HardwareTimingService` no-argument recorded-admission methods to use
  one complete recorded policy map for every known work kind.
- Change `HardwareTimingSchedule` so every present v5 schedule, including an
  explicitly empty stream, exposes the same complete recorded policy map. Add
  a dedicated recorded-empty factory; retain `empty()` for absent/no-port
  schedules and audit loader, compiler, replay reset, fixtures, benchmarks,
  and segment handoff callers. Keep `hasRecordedInput()` as the discriminator.
- Remove the schema-1 inference helper and update comments/names that describe
  it as a live format.
- Cover both `beginRecordedAdmission()` and
  `beginRecordedAdmissionAfterLiveEpoch()`, including their production
  `GameplayModeContext` call sites.

## 2. Repair and extend tests

- Rename/replace schema-1 behavior assertions in the timing service and S3K
  queue tests with v5 both-recorded assertions.
- Convert tests that intentionally exercise mixed `LIVE`/`RECORDED` maps to
  explicit generic policy-map calls and names.
- Add direct-edge, present-empty-stream, absent-stream, and segment-handoff
  policy-consistency coverage, plus module-only, direct-only, mixed, and
  boundary-negative cases.
- Run the focused timing/replay suite before and after the change.

## 3. Reconcile evidence and documentation

- Add a deterministic exact deletion/rename manifest under
  `docs/architecture/validation/trace/` listing every replaced/deleted
  publication path, predecessor stored/logical hash, archive target, and the
  protected S1 credits archive; add a verifier/guard for it.
- Pin installed aggregate `04851c0a146eeb101a0ce0d76c78ba9c861a4eb3d6c9ff50612c84112d868790`,
  mark the freeze completed/superseded, and point the old regeneration plan at
  the v5 closure rather than leaving old checkboxes and schema-7 criteria.
- Update active guide, known-discrepancy, native-recorder, and timing-contract
  wording to v5; retain old details only in clearly labelled historical
  evidence or negative rejection tests.
- Audit `TraceMetadata`, `TraceEvent`, `TraceCatalog`, replay bootstrap, and
  `.bk2` discovery paths for active legacy fallbacks; remove or explicitly
  classify any that are not format compatibility, with rejection/fallback tests.
- Add a trace-frontier entry with commands, commit/worktree context, inventory
  identity, validation outcomes, and the known LZ3/S3K frontiers.

## 4. Validation and integration

- Run focused Java (named timing, loader, replay, schedule compiler, and
  committed-fleet classes), strict validator, Python tooling, and native
  no-gate tests.
- Run the full Maven suite against the pre-change JDK-21 baseline and record
  `/tmp/openggf-remediation-baseline.log`, then run it in this worktree and
  compare exact pass-to-fail and new-error names.
- Fetch/pull and merge into current `develop` without touching concurrent dirty
  files; stage the required README release note and trailers, then run the same
  focused and full suites on merged `develop` and compare against the recorded
  baseline.
- Push only `develop`, verify ancestry/remote equality, then remove the clean
  worktree and local branch.

## Review remediation closure

- The README's schema-1 wording is now explicitly historical; v5 remains the
  sole live trace contract.
- The publication manifest contains all 24 predecessor archive hashes, and its
  guard checks the exact synthetic and non-synthetic deletion sets plus the
  bytes and table entries for every archived file.
- The capture-matrix tool no longer carries a second stale `FREEZE` authority;
  the reviewed JSON document is the single source for capture freeze data.
- TraceCatalog's per-directory `.bk2` path is documented and tested as a
  movie-placement fallback for v5 fixtures, not as legacy schema support.
- The full-suite baseline/post comparison is recorded in
  `2026-08-04-trace-contract-remediation-suite-comparison.md`.

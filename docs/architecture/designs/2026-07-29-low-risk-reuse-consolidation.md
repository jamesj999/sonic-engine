# Low-Risk Reuse Consolidation Design

**Date:** 2026-07-29

## Goal

Delete three narrow categories of duplicated infrastructure without changing
ROM detection, HUD presentation, trace parsing, runtime scheduling, or public
game-specific entry points. One malformed-input edge case is intentionally
tightened: a directory named like a trace payload is no longer resolved as a
file.

1. gzip/plain trace-file resolution and reader construction;
2. domestic/international ROM-header detector orchestration; and
3. HUD static-label and lives-frame assembly.

## Scope

This tranche is deliberately limited to pure file I/O, header matching
orchestration, and immutable render-data construction. It does not change ROM
offsets, runtime assets, gameplay state, object behavior, trace authority, or
frame ordering.

The audit also identified static debug dependencies as low-risk candidates.
They are excluded here. `DebugSpriteMovementManager` has no production callers,
while `LevelDebugRenderer` consumes debug palette state still owned by
`Engine`. Deleting dead code or relocating that state requires its own
reachability and ownership decision; adding callbacks now would create an
abstraction without completing the ownership migration.

## Approaches considered

### A. Minimal templates and policy wrappers — recommended

Extract only the repeated algorithms:

- a neutral trace-file utility;
- an abstract detector template with game-owned predicates; and
- a shared HUD builder driven by a compact immutable layout profile.

Retain the public `Sonic*RomDetector` and `Sonic*HudStaticArtFactory` types as
thin game-owned policies.

This deletes duplication while preserving source compatibility and keeping
game differences visible.

### B. Declarative registries for all three areas

Represent detector names, module suppliers, priorities, HUD palettes, and file
formats as descriptors registered in central tables.

This would reduce more wrapper code, but it would also move module construction
and game policy into broad registries. That conflicts with the preference for
the smallest accurate owner and makes optional behavior less readable.

### C. Opportunistic helper methods inside existing classes

Keep `TraceData` as the trace I/O owner, add default methods to `RomDetector`,
and expose HUD mapping helpers from one game-local factory.

This is a smaller edit, but it preserves the wrong dependency directions:
catalog code would depend on a parsed trace model, the detector interface would
acquire one implementation convention, and game packages would cross-depend for
HUD assembly.

## Design

### Trace file I/O

Add `com.openggf.trace.TraceFiles`, a final utility with:

```java
public static Path resolve(Path directory, String fileName)
public static BufferedReader openReader(Path path) throws IOException
```

`resolve` prefers an existing regular plain file, then an existing regular
`fileName + ".gz"`, otherwise returns `null`. `openReader` uses UTF-8
explicitly for both paths and wraps `.gz` files in `GZIPInputStream`. If gzip
construction fails, it closes the raw stream before rethrowing.

`TraceData`, `TraceCatalog`, and special-stage trace loaders consume the
utility. The existing public `TraceData.resolveTraceFile` and
`TraceData.openTraceReader` methods remain as deprecated forwarding methods in
this tranche so external tooling is not broken. Test-local readers migrate when
their semantics match; fixture code that intentionally prefers compressed
input remains local and documented.

The utility owns no parsing, metadata, catalog, hardware-timing, or gameplay
logic.

`TraceData.resolveTraceFile` currently uses `Files.exists`, so a directory with
the plain payload name can win over a valid gzip sibling and later fail during
opening. The shared utility intentionally uses `Files.isRegularFile`; this is a
malformed-input correction, not a supported-input compatibility change.

### ROM-header detectors

Add `com.openggf.game.AbstractHeaderNameRomDetector`. Its protected final
`canHandleHeaderName(Rom)` template:

1. rejects null or closed ROMs;
2. reads and tests the domestic name;
3. short-circuits on a domestic match;
4. reads the international name only after a domestic miss;
5. catches an `IOException` across that sequence, logs it, and returns false;
   and
6. preserves the existing case and whitespace normalization behavior.

Subclasses provide:

```java
protected abstract boolean matchesNormalizedName(String normalizedName);
protected abstract Logger logger();
```

The three public concrete detectors retain matching constants/predicates,
priorities, game names, and module construction. S1 exclusions and all S3K
aliases remain explicit. `RomDetectionService` registration and priority
sorting do not change. Each non-final concrete detector continues to declare a
public, non-final `canHandle(Rom)` method that forwards to the protected final
template. This preserves the pre-extraction subclass override surface while
keeping the shared orchestration non-overridable.

Fine-level detector log wording is diagnostic rather than contractual. The
template preserves success/miss/failure logging and detector identity, but does
not need byte-identical messages.

The refactor intentionally does not add the header-format validation mentioned
by the S2 detector comment; current behavior is name matching only.

### HUD static art

Add `com.openggf.level.objects.HudStaticArtFactory`, a final shared builder. It
contains a nested immutable layout:

```java
public record Layout(
        int labelPalette,
        Integer flashLabelPalette,
        int livesNamePalette,
        boolean requireNonEmptyTextAndLives) {}
```

The shared builder owns:

- text/lives pattern concatenation, preserving identity and order;
- score, debug-score, time, and rings label geometry;
- normal and flash palette selection;
- empty flash frames when `flashLabelPalette` is null; and
- the common two-piece lives geometry.

The game-local wrappers express only policy:

| Wrapper | Layout |
|---|---|
| S1 | label 0, flash 0, lives name 0, require non-empty inputs |
| S2 native | label 1, flash 0, lives name 1, allow empty inputs |
| S2 donor | label 1, flash 0, lives name 0, allow empty inputs |
| S3K | label 1, empty flash, lives name 1, allow empty inputs |

S1 continues returning `null` when either input is null or empty. S2 and S3K
continue normalizing null inputs to empty arrays and returning a bundle.

## Error and compatibility behavior

- Missing trace files resolve to `null`; opening a missing or malformed file
  throws `IOException`.
- A directory with a payload filename is not a trace file.
- Plain trace files keep precedence over gzip files.
- Detector I/O failures remain non-matches; domestic-read failure does not fall
  through to the international name.
- Concrete detector and HUD factory classes remain public and retain their
  existing method signatures.
- Each concrete detector declares a public, non-final `canHandle(Rom)` so
  downstream subclasses retain their existing override compatibility.
- No `Pattern` instance is cloned or mutated.

## Testing

Each production extraction follows red-green-refactor.

### Trace tests

Add focused tests for plain read, gzip read, plain precedence, missing files,
directory rejection, malformed gzip, and explicit UTF-8 behavior. Directly
exercise the deprecated `TraceData.resolveTraceFile` and
`TraceData.openTraceReader` forwarding APIs so source-compatible entry points
remain behaviorally pinned. Keep catalog gzip scanning and trace parsing tests
as integration coverage.

### Detector tests

Add cross-game tests for null/closed ROM rejection, domestic short-circuit,
international fallback, read failures, normalization, S1 exclusions, S3K
aliases, priorities, names, module types, and reflection-based verification
that each concrete detector declares a public, non-final `canHandle(Rom)`.

### HUD tests

Expand the current lives-frame test to cover concatenation identity/order,
every label frame and palette, S3K empty flash frames, S1 null contracts,
S2/S3K empty-input contracts, and the S2 donor-only palette difference.

### Verification surface

Focused verification:

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.trace.TraceFilesTest,com.openggf.trace.catalog.TraceCatalogTest,com.openggf.tests.trace.TestTraceDataParsing,com.openggf.tests.trace.TestS1SpecialStageTraceParsing,com.openggf.tests.trace.TestS3kSpecialStageTraceParsing,com.openggf.trace.timing.TestHardwareTimingAuthorityGuard,com.openggf.game.TestHeaderNameRomDetectors,com.openggf.game.TestHudStaticArtLivesFrameMappings,com.openggf.game.sonic1.TestSonic1LivesHudDonation,com.openggf.game.sonic2.TestSonic2LivesHudDonation,com.openggf.game.sonic3k.TestSonic3kLivesHudPaletteOverride,com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalReviewGuard" \
  test
```

The focused baseline has one known fixture failure. At fork baseline
`eb1b138c4`, the comparable command (excluding this tranche's new tests) ran
127 tests with one failure and zero errors:
`TestTraceDataParsing.parsesRecordedRingFloorCheckCounterPhase` expected `2`
and received `null`. The HCZ/MGZ fixture metadata omit
`ring_floor_check_counter_phase`. The tranche command must retain that exact
single failure and introduce no additional focused failure or error; it ran 150
tests before the compatibility correction and 151 afterward, with the same 1/0
result.

The full JDK 21 suite runs after all three tasks because these shared utilities
affect bootstrap and trace tooling even though they do not alter gameplay. Use
`mvn clean test` for the comparison so its Surefire XML contains only that run;
the recorded clean baseline and tranche comparison is in
`docs/architecture/validation/2026-07-29-low-risk-reuse-consolidation.md`.

## Delivery sequence

1. Extract and migrate trace-file I/O.
2. Extract ROM detector orchestration.
3. Extract HUD assembly.
4. Update `CHANGELOG.md` only if the final commit type or policy requires it.
5. Run focused and full verification, then integrate through the repository's
   worktree workflow.

Each extraction is independently reviewable and revertible.

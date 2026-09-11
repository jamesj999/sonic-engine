# ROM Location Resolver

**Date:** 2026-07-29
**Base:** `develop` at `aa26f44942e7cf95218a02fd72289938bd0d4c00`

## Purpose

Give production runtime and trace tools one typed policy for selecting a
game-specific ROM path while retaining each consumer's opening and failure
semantics.

This design covers `RomManager`, `TraceCaptureTool`, and
`TraceBenchmarkTool`. It does not change JUnit ROM discovery, master-title UI,
SoundTest, object/disassembly tools, or ROM identity validation.

## Current problem

`RomManager.resolveRomForGame(String)` owns a string switch for game-to-config
mapping. The two trace tools call that compatibility method directly.
`RomManager` separately resolves relative paths for existence checks but opens
the original string. No result explains where a path came from or distinguishes
the configured spelling from its filesystem target.

JUnit has a materially different contract: it searches property, environment,
configuration, and default candidates until one exists, then disables a test
when none works. Production must not adopt that policy. A nonblank configured
path must win even if missing so configuration errors fail visibly.

## Considered approaches

### One universal locator

Put production, tools, and JUnit behind one precedence algorithm.

This would maximize syntactic reuse but hide incompatible terminal behavior:
production is select-then-fail, while tests are search-existing-then-skip. It
would also entangle ROM caching and JUnit conditions with runtime composition.
Rejected.

### Typed production resolver with consumer-owned I/O

Add an injectable resolver that returns a typed, provenance-carrying location
without touching the filesystem. Migrate the exact production consumers that
currently share config-only lookup. Preserve compatibility forwarders.

Selected. It creates one accurate production policy without absorbing opening,
validation, UI, or test semantics.

### Mapping-only helper

Move only the ROM-family-to-`SonicConfiguration` switch.

This removes a few lines but leaves relative-path resolution, provenance, raw
diagnostics, and future fingerprint policy duplicated. Rejected as too narrow.

## Architecture

### `RomLocation`

Add an immutable public record in `com.openggf.data`:

```java
public record RomLocation(
        RomGame game,
        String configuredValue,
        Path resolvedPath,
        RomLocationSource source,
        RomFingerprintPolicy fingerprintPolicy) {
}
```

`configuredValue` preserves the exact configured string or `Path.toString()`
value supplied by an explicit caller for diagnostics and compatibility.
`resolvedPath` is absolute and normalized against the resolver's captured
working directory. The record performs null validation only; it does not check
existence, readability, contents, or fingerprints.

`RomLocationSource` initially has `CONFIGURATION` and `EXPLICIT_OVERRIDE`.
`RomFingerprintPolicy` initially has only `NONE`. Carrying the policy makes the
result ready for a separately designed identity-validation boundary without
pretending this tranche validates a ROM.

`RomGame` is the data-layer identity for the three ROM families: `S1`, `S2`,
and `S3K`. It deliberately does not depend on the runtime-layer `GameId`.
`GameId.romGame()` owns the sole exhaustive runtime-to-data conversion; tools
and future runtime consumers call that method rather than repeating switches.
This uses the permitted `game -> data` direction and avoids new `data -> game`
dependencies, which the architecture ratchet forbids.

### `RomLocationResolver`

Add a public final resolver in `com.openggf.data` with injected
`SonicConfigurationService` and working directory:

```java
public RomLocationResolver(
        SonicConfigurationService configuration,
        Path workingDirectory)

public Optional<RomLocation> resolve(RomGame game)

public RomLocation explicit(RomGame game, Path path)
```

`resolve` maps `RomGame.S1`, `S2`, and `S3K` to their typed configuration keys.
It selects the nonblank configured value without filesystem fallback. Blank
configuration returns empty. Relative paths resolve against the captured
working directory; absolute paths remain absolute after normalization.

`explicit` applies the same path normalization but reports
`EXPLICIT_OVERRIDE`. It rejects null ROM-family/path inputs and does no I/O.

A named factory captures `System.getProperty("user.dir")` for production
composition. If `user.dir` is unavailable or blank, it uses
`Path.of("").toAbsolutePath()` at construction. Tests use the injectable
constructor.

### `RomManager`

`RomManager` creates a resolver from the current configuration and current
working directory for each active or secondary resolution. It does not retain
a resolver field in the singleton. This preserves the existing behavior where
changing `user.dir` before a later reopen changes relative-path resolution,
while each individual resolution remains deterministic.

It checks and opens `resolvedPath`, but its missing-file diagnostics retain the
exact `configuredValue` so existing error classification and messages stay
stable. ROM object lifecycle and secondary caching remain owned by
`RomManager`.

Retain `resolveRomForGame(String)` as a deprecated public compatibility
forwarder. It preserves the exact legacy behavior:

- case-insensitive `s1` and `s3k`;
- null, unknown, and every other string select S2;
- the returned string is the raw configured spelling; and
- blank configuration returns the existing blank/null value.

Do not normalize secondary cache keys in this tranche.

The legacy compatibility parser remains deliberately separate because its
null/unknown-to-S2 behavior differs from strict `GameId.fromCode`. A single
private `RomManager` helper maps that legacy input to `RomGame`; it is not a
second strict runtime identity conversion.

### Trace tools

`TraceCaptureTool` and `TraceBenchmarkTool` resolve the catalog entry's strict
`GameId` with `GameId.fromCode(entry.gameId())`, convert it through the
authoritative `GameId.romGame()`, then pass `resolvedPath` to
`HeadlessGameBoot`. Unknown/malformed catalog game IDs are rejected by that
typed boundary instead of silently selecting S2; tests pin the existing
`Unknown game: <value>` message from `GameId`.

The tools continue to own entry selection and command failure presentation.
`HeadlessGameBoot` remains path-explicit and continues to own ROM opening,
detection, reboot reuse, and error messages. Resolution never searches for a
fallback existing file.

Blank configuration cannot produce a path. Each trace tool therefore fails at
its own selection boundary with
`IllegalStateException("No ROM configured for game: " + gameId)`. This is an
intentional improvement over the current blank-string path reaching
`HeadlessGameBoot` as an empty path; it does not search for or silently select a
default file. Nonblank missing and unopenable paths still reach the unchanged
`HeadlessGameBoot` boundary.

## Error and compatibility contracts

- A missing configured file is still selected and fails at the current
  consumer boundary.
- A blank configuration produces the trace tools' explicit
  `No ROM configured for game: <id>` failure and `RomManager`'s existing
  `ROM filename not configured` failure. It does not fall back to `s1.gen`,
  `s2.gen`, or `s3k.gen`.
- Relative paths remain working-directory-relative, not config-file-relative.
- Raw configured spelling remains available for diagnostics.
- No CRC32, SHA-1, header, or file-content validation moves into the resolver.
- `RomManager.resolveRomForGame(String)` preserves null/unknown-to-S2 fallback.
- No public `HeadlessGameBoot` or trace-tool CLI signature changes.

## Testing

Resolver unit tests characterize:

- all three config-key mappings;
- blank values;
- relative and absolute normalization;
- captured working-directory behavior;
- configuration and explicit provenance;
- no existence requirement; and
- `RomFingerprintPolicy.NONE`.

`RomManager` tests pin legacy string fallback, raw missing-path diagnostics,
relative path opening after `user.dir` changes between resolutions,
active/secondary behavior, and existing missing-ROM classification.
Because those tests configure process-wide engine services, they use the
approved full-reset singleton lifecycle fixture rather than an ambient manual
bootstrap.

Trace-tool tests pin strict game mapping and that missing/unopenable configured
paths still reach the same `HeadlessGameBoot` failure boundary, while blank
configuration fails at the explicit tool boundary and unknown metadata retains
the `GameId.fromCode` error. Existing trace capture/benchmark and headless boot
tests remain the integration gate.

New behavior uses red-green-refactor TDD. Behavior-preserving migrations first
characterize the existing behavior green, then run the identical tests after
the refactor; the resolver task supplies the new-behavior RED. Every task
receives independent specification and code-quality review.

The clean-suite comparison also guards against reused-fork ordering changes
caused by adding test classes. Any registry test whose expected factory depends
on the active S3K zone set must establish that state with the full-reset
fixture, rather than inheriting a level from another class in the same fork.

## Deferred work

- JUnit `RomTestUtils`, `RomCache`, and `RequiresRomCondition`: preserve their
  first-existing property/environment/config/default search and skip/cache
  semantics in a separate adapter design.
- Fingerprint enforcement: design separately with policy `NONE` remaining the
  compatibility default.
- Master-title availability, preview, display, and launch paths: UI lifecycle
  and relative-path presentation require a separate migration.
- SoundTest: preserve explicit `--rom` precedence in its own task.
- `ObjectDiscoveryTool`, `RomOffsetFinder`, and `RomArtIntakeTool`: their
  positional overrides, generic `-Drom.path`, multi-game skip behavior, and
  profile defaults are distinct command contracts.

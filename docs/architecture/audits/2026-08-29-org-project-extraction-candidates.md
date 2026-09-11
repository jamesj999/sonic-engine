# OpenGGF-org project extraction candidates

Date: 2026-08-29

## Question

Which parts of the OpenGGF repository have a strong enough ownership and dependency
boundary to become separately released OpenGGF-org projects?

This began as a point-in-time boundary audit. Its first recommendation was implemented on
2026-08-29 as [`OpenGGF/TraceChaser`](https://github.com/OpenGGF/TraceChaser) `v0.1.0`.
OpenGGF pins exact commit `9e51ff79e7a542f3c50d96618a7e24e6fc72397e` at the optional
`tools/tracechaser` gitlink. The remaining candidates are still recommendations.

## Decision criteria

A strong extraction candidate should satisfy most of these tests:

1. It has its own executable or library entry point and test lifecycle.
2. Its useful operation does not load OpenGGF engine classes.
3. It can release independently without forcing an engine release in lockstep.
4. It owns a stable interchange contract rather than reaching into engine internals.
5. Another repository or user can consume it without checking out OpenGGF.
6. Moving it does not turn an optional workflow into a mandatory network dependency.

## Current inventory

| Surface | Measured shape | Coupling signal |
|---|---:|---|
| `tools/bizhawk-headless/` | 227 source, test, script, and documentation files; about 4.9 MiB | Independent C# executables, build scripts, 372-test runner, BizHawk DLL boundary, ROM/BK2 inputs, trace-v5 outputs |
| `tools/bizhawk/` | 105 Lua, launcher, probe, and reference files; about 1.6 MiB | Emulator-facing only; legacy recorders and diagnostic probes share the native recorder's ROM-state vocabulary |
| `tools/retro/` | 5 Python files | Alternative emulator capture path using the same trace contract |
| `tools/traces/` | 9 Python/PowerShell files | Validates, compares, compresses, and inventories recorder output and committed fixtures |
| `src/test/resources/traces/` | 1,519 files; about 683 MiB | Direct test input to OpenGGF replay and guard suites |
| `com.openggf.tools.disasm` | 9 Java files | Imports `Rom` plus four production decompression implementations |
| `com.openggf.tools.audio` | 43 Java files | Imports game modules, SMPS runtime, rewind, ROM, and synthesizer internals |
| `TraceCaptureTool` family | Java CLI plus capture session | Imports game sessions, game loop, rendering, audio capture, replay, timing, and level services |

The ordinary and release GitHub Actions checkouts do not initialize submodules. This
already proves that the disassemblies are outside the build graph. Repository search also
finds disassembly paths in source citations and research tooling, not as Maven resources or
runtime asset inputs.

`gh repo list OpenGGF --limit 100` reported four current organization repositories on the
audit date: `OpenGGF`, `OpenGGF-WebZone`, `Actworks`, and `scddisasm`. None occupies the
provisional recorder-project name below.

## Extracted first: TraceChaser recorder and fixture-publication toolchain

### Recommended project

The published `OpenGGF/TraceChaser` repository contains:

- the supported native harness from `tools/bizhawk-headless/`;
- legacy/differential Lua recorders, launchers, shared Lua modules, and probes from
  `tools/bizhawk/`;
- the stable-retro capture adapter from `tools/retro/`;
- recorder-output validation, compression, comparison, and candidate-publication tools
  from `tools/traces/`; and
- the two direct script tests under `tools/testing/`:
  `test_trace_v5_capture_matrix.py` and `test_compare_trace_v5_candidates.py`.

This should be one project rather than separate “headless”, “Lua”, and “trace scripts”
repositories. They are different producers and validators for one artifact contract. The
native harness is already a standalone application with its own build, test runner,
dependencies, documentation, scratch policy, and deterministic-build verification. It
does not import OpenGGF Java code.

### Boundary with OpenGGF

The new project owns capture and publication. OpenGGF owns replay semantics and committed
comparison fixtures:

```text
ROM + BK2 ── TraceChaser ── trace schema v5 files ── OpenGGF replay tests
```

Keep `src/test/resources/traces/` in OpenGGF during the first extraction. A normal build
must not clone or build the recorder repository. Fixture regeneration is an explicit
maintainer workflow that records the recorder release and output hashes.

The extraction needs a versioned trace-v5 interface document and cross-repository
compatibility fixtures. The recorder must validate what it emits; OpenGGF must continue
validating what it consumes. Neither repository should reach into the other's source tree.

### Migration prerequisites

1. Freeze the live trace-v5 schema, file naming, compression, and manifest rules into a
   portable contract document and machine-readable positive/negative fixtures.
2. Replace repository-relative assumptions such as `src/test/resources/traces` and
   `tools/bizhawk-headless/docs` with CLI arguments or paths relative to the recorder
   checkout.
3. Move recorder tests and their small golden inputs with the code. Do not move ROMs,
   canonical OpenGGF trace fixtures, or engine replay tests.
4. Publish a recorder release, then update OpenGGF documentation and the
   `bizhawk-headless-trace` skill to invoke that checkout or release.
5. Remove the old directories only after a clean external checkout reproduces a candidate
   and OpenGGF consumes it successfully.

## Prepare, then reconsider

### ROM/disassembly analysis CLI

`RomOffsetFinder`, `DisassemblySearchTool`, `RomOffsetCalculator`, constants export, and
compression testing form a coherent developer CLI. They would be useful beside the Sonic
Retro repositories even when OpenGGF is not checked out.

They are not ready to extract today: all decompression verification imports OpenGGF's
production `Rom`, Nemesis, Kosinski, Enigma, and Saxman implementations. Moving only the
CLI would either duplicate accuracy-sensitive codecs or make an external tool depend on an
unpublished application artifact.

Reconsider `OpenGGF/RomWorkbench` when either a second consumer exists or the ROM-access
and decompression layer has a deliberately supported library API. Until then, keeping the
tool beside the implementations it verifies is the smaller and safer design.

### Trace fixture corpus

At roughly 683 MiB, the committed trace corpus is the largest obvious repository-weight
candidate. It is nevertheless a hard test input, not an independently useful program.
Moving it now would make ordinary test setup download an external data repository or
release archive and would introduce cache, offline, integrity, retention, and schema-skew
failure modes.

Reconsider `OpenGGF/TraceFixtures` only when repository size or GitHub limits become an
active constraint. A viable design requires immutable content-addressed archives, a small
manifest committed in OpenGGF, verified download caching, an offline subset for ordinary
tests, and explicit retention guarantees. It should not be a Git submodule required by
Maven.

### Audio parity laboratory

The native GPGX audio observers that live inside the recorder should move with the recorder
first. The Java audio parity, complete-run, and timeline tools should remain in OpenGGF:
their 43 files consume the engine's SMPS, game-module, rewind, ROM, and synthesizer state.

If those tools later stabilize around a public stream of chip writes and semantic driver
snapshots, an `OpenGGF/AudioParityLab` could consume that contract. Extracting them before
that interface exists would simply export engine internals across a repository boundary.

## Keep inside OpenGGF

| Surface | Reason to keep |
|---|---|
| Trace replay parsers, comparators, run-chain harnesses, and timing authority | They verify OpenGGF production behavior and deliberately share engine state models. |
| `TraceCaptureTool`, `TraceTriageTool`, benchmarks, headless boot, and object/zone scaffolding | These are engine operators with broad imports across sessions, rendering, level, object, audio, and trace services. |
| Runtime decompression | It is part of ROM-backed asset loading. A separate repository would add release coordination without an established external consumer. |
| Rewind inventory tools | They inspect OpenGGF-specific snapshot policies and production classes. |
| `.githooks/`, the remaining `tools/testing/` scripts, and test-report helpers | They enforce this repository's commit, documentation, CI, and regression policy. The two recorder-owned Python tests named above move with their subjects. |
| `.agents/skills/` and `.claude/skills/` | They encode OpenGGF workflows. Skills that operate an extracted recorder should become thin consumer guidance after that project exists. |
| Sonic Retro disassembly submodules | They are external, optional, pinned research references—not OpenGGF-owned code to extract. |

## Recommended order

1. **Freeze the portable trace-v5 contract and compatibility fixtures.** Both producer
   and consumer must validate the same positive and negative cases before their source
   trees are separated.
2. **Extract and publish `TraceChaser` — completed.** The history-preserved `v0.1.0`
   release passed the six-capture reproduction and source-only policy gates before
   OpenGGF removed the in-tree implementation and pinned the immutable release commit.
3. **Reassess `RomWorkbench` only after a reusable ROM/codec API or second consumer exists.**
4. **Reassess trace-fixture hosting only when repository weight becomes an active problem.**
5. **Keep engine-facing capture, audio, replay, build-policy, and agent tooling in OpenGGF.**

This order creates one meaningful new product boundary without turning development helpers
into a constellation of repositories that must evolve in lockstep.

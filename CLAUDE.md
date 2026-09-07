# OpenGGF agent guidance

Keep this file and `CLAUDE.md` identical. Skills are mirrored between
`.agents/skills/` and `.claude/skills/`.

## Project and scope

OpenGGF is an alpha Java 21 reimplementation of Sonic 1, 2, and 3&K for
research and preservation. Runtime assets come from user-supplied ROMs; this
is an independent fan project with no Sega affiliation. The editor is
experimental and the modding framework is planned, not shipped.

Accuracy means reproducing shipped-ROM behavior. Use the disassembly to
explain differences; do not tune gameplay to make a fixture pass. Prioritize
S3K playable routes, keep AIZ → HCZ stable, and choose later-zone work by
current route blockers and trace frontiers. Broader cleanup should serve the
requested work or remove an active risk.

Carry authorized work through verification and the user's delivery flow.
Use skills for relevant domain knowledge; load supporting references only
for the current question. Routine implementation choices do not need a
planning ceremony or another approval. User instructions override skill
workflow defaults.

## Build and verification

```bash
mvn -v                              # must report Java 21
tools/testing/install-hooks.sh     # once per worktree
mvn package
mvn test
mvn "-Dtest=TestCollisionLogic" test
mvn -Dmse=off -Psmoke test -B         # what every branch push runs in CI
mvn -Dmse=off -Pguards test -B        # separate fresh JVM for structural guards
```

- Surefire inherits Maven's JVM. Set `JAVA_HOME` to JDK 21 if needed.
- Use Lua 5.4 for the TraceChaser forwarder guard; set `LUA_BIN` if needed.
- Maven output belongs in the current worktree's `target/` directory. Do not share or
  copy build trees. The per-Surefire-fork LWJGL extraction uses
  `target/test-tmp`. Concurrent Maven runs need separate worktrees.
- Use JUnit 5/Jupiter. `-Dmse=off` exposes full Maven logs. PowerShell quotes
  `-D...` arguments and uses `tools/testing/install-hooks.ps1`.
- CI runs only `-Psmoke` on pushes. The full suite and `-Pguards` run on pull
  requests, on a manually dispatched CI run, and in release validation; nothing
  runs them unattended. `smoke` does not select the structural guards, so run
  both the full suite and `-Pguards` locally before delivery -- a red guard on
  develop will not surface on its own.
- Match focused checks to the change and complete required integration
  checks. Release evidence includes ordinary tests and `-Pguards` separately.
- Before reporting suite results, read the measurement-hazard table in
  [briefing-trace-rounds.md](docs/agent-workflow/briefing-trace-rounds.md#measurement-hazards--all-produce-plausible-output).
  Attribute results to the command, commit, and completed run; inspect skips.

## ROM and reference setup

Discover existing root `.gen` files and pass absolute paths to ROM-backed
tests. Missing/wrong paths silently skip `@RequiresRom` tests. Do not rename,
copy, delete, or create ROM links to satisfy an example. Verify identity when
it matters:

| ROM | Test property | CRC32 | SHA-1 |
|---|---|---|---|
| S1 World REV01 | `-Dsonic1.rom.path=` | `AFE05EEE` | `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| S2 World REV01 | `-Dsonic2.rom.path=` | `7B905383` | `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| S3&K locked-on | `-Ds3k.rom.path=` | `63522553` | `CFBF98C36C776677290A872547AC47C53D2761D6` |

Disassemblies in `docs/s1disasm`, `docs/s2disasm`, and `docs/skdisasm` are
optional development references. Builds, tests, and runtime do not require them.
Use `git submodule update --init` when needed.
Trace production/probes live in the optional pinned `tools/tracechaser/` submodule; initialize it with
`git submodule update --init --recursive tools/tracechaser` for trace work.
Follow its current guide and verified BizHawk 2.11 dependency. Use
`tools/tracechaser/...` paths; old paths are compatibility forwarders.

## Runtime invariants

1. Load every runtime asset byte through the ROM pipeline. Disassembly
   trees provide research and labels, never runtime fallback assets.
2. Shared runtime code consumes semantic rules, not game/zone-name
   carve-outs. Use `GameRules` for game-wide gates and existing
   providers/profiles/registries for narrower differences. See
   [rule placement](docs/architecture/per-game-rule-placement.md).
3. Trace fixes model ROM state and generalize to another BK2. Cite the
   owning routine for constants and branch conditions. Do not key behavior
   on a fixture, route, frame index, or fitted measurement.
4. **Trace data is comparison-only by default.** Never hydrate or sync engine
   gameplay from physics/aux rows. The only input exception is the isolated
   [dedicated hardware-timing input contract](docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md):
   It may release only the readiness of a matching, prepared, production-submitted
   ROM-backed job after kind, ordinal, stable fingerprint, and service boundary match;
   per-row lag admission may select an already-existing ROM loop. Neither
   shape supplies gameplay values, calls gameplay owners, creates work,
   uses physics/aux comparison data, or keys on frame/zone/route/game name.
   Keep authority inside the timing port and its guard. Consult the
   contract for implemented kinds and fixture coverage; scope is not proof
   of implementation or coverage.
5. V5 (`trace_schema: 5`) is the sole live trace contract across metadata,
   rows, timing, and manifests. Recorder provenance never selects behavior;
   `lua_script_version` is removed. Commit compressed trace payloads only.
6. Objects use injected `services()`, never `getInstance()`. Gameplay tile
   edits use `ZoneLayoutMutationPipeline` / `LevelMutationSurface`; editor
   commands and initial decoders are exempt.
7. Model `FixBugs = 0` / `fixBugs = 0`, matching shipped ROMs. Near conditional
   code, comment which branch is used, why, and what the fixed branch changes.

## Implementation details that change decisions

- ROM `x_pos`/`y_pos` are `getCentreX()`/`getCentreY()`. `getX()`/`getY()` and
  HUD `Pos:` are top-left. Playable native writes use `NativePositionOps`.
- Object `update` receives `V_int_run_count`, not executed-frame count or
  `Level_frame_counter`. Name the ROM clock a gate actually reads.
- Preserve rewind: new objects need recreation and captured state; persistent
  global managers need a registered `RewindSnapshottable` adapter.
- Keep logic in managers rather than `Engine.java`; match nearby Java idioms.
- Read [implementation pitfalls](docs/architecture/implementation-pitfalls.md)
  for collision, tiles, headless setup, rewind, and audio source references.
  Read [AGENTS_S3K.md](AGENTS_S3K.md) for S3K half/table selection and zone work.
- S3K changes keep `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`,
  `TestSonic3kBootstrapResolver`, and `TestSonic3kDecodingUtils` green.

## Delivery and documentation

Follow the user's global branch/integration workflow. Never switch the main
workspace branch. New branches use isolated `.worktrees/` checkouts and
`feature/ai-*` or `bugfix/ai-*` names, based on the current main branch.
Preserve unrelated changes, including dirty submodules.

Install and obey `.githooks/`; never use `--no-verify`. Non-master,
non-merge commits need all seven trailers (`Changelog`, `Guide`,
`Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`,
`Configuration-Docs`, `Skills`), each beginning `updated` or `n/a`.
Mapped files and trailers must agree. A source `feat`/`fix`/`perf` needs a
changelog update or an inline reason for skipping it. A merge into `develop`
touches the README release section only when it adds or changes a version
theme; most merges leave it alone, and no hook requires it.

Use the [documentation obligation checklist](docs/agent-workflow/documentation-obligation-checklist.md)
when staging. `pom.xml`'s `<version>` names the version `develop` carries and
so the `CHANGELOG.<version>.md` that receives release prose (`master` is the
last released version and `next` the one after `develop`; today that is master
0.5.20260411, develop 0.6.prerelease, next 0.7.prerelease; promoting them at
release time follows [release rollover](docs/project/release-rollover.md)). Root `CHANGELOG.md` is the index (its exact
path owns the hook trailer). The README release section is only a very
high-level summary of the version's themes, not a change log; a fix to a
feature introduced in that same unreleased version folds into the existing
entry rather than earning one of its own.
Update guides/config/discrepancies when their behavior changes. Update
`docs/status/trace-frontier-log.md` when a frontier moves, a trace fix lands,
a passing trace regresses, or a sweep selects the next target; include
command, commit/worktree, errors, and first-error frame/field.

Keep engineering artifacts under the matching `docs/architecture/`
subdirectory from [docs/README.md](docs/README.md), release material under
`docs/changelog/`, and stage relevant artifacts. Durable captures belong in
an explicit task directory outside the repo; temporary Maven output stays
under `target/`.

## Find the owning reference

- Architecture/services: [engine map](docs/architecture/engine-map.md).
- Objects/bosses: matching `s1-`, `s2-`, or `s3k-implement-*` skill and
  [implementation reference](docs/architecture/object-implementation-reference.md).
- Disassembly lookup: matching `s1disasm-guide`, `s2disasm-guide`, or
  `s3k-disasm-guide` skill.
- Trace failures: `trace-replay-bug-fixing`; multiple independent traces:
  `trace-green-fleet`; video: `trace-capture`; recording: `bizhawk-headless-trace`.
- PLC/art queues: `plc-system`, plus `s3k-plc-system` for S3K.
- Zone work: relevant S3K zone/events/parallax/animated-tiles/palette skill;
  whole-zone delivery: `s3k-zone-bring-up`.
- Headless tests: [headless testing](docs/guide/contributing/headless-testing.md).
- Current gaps: [general](docs/status/known-discrepancies.md),
  [S3K](docs/S3K_KNOWN_DISCREPANCIES.md). Configuration: [CONFIGURATION.md](CONFIGURATION.md).

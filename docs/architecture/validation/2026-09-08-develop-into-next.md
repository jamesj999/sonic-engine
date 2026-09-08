# Develop into next — merge validation

## Inputs and scope

- Destination: local `next` at `988b5d73a5a80ecd0b9c5be655bb08474c55b3fd`.
  This includes the unpushed Time Attack extraction roadmap commit over
  `origin/next` at `949e49072`.
- Source: `develop` at `8392494a0a1f1af966776da1dcbe4698db4bb7df`, fetched and
  confirmed current before work began.
- Merge worktree: `.worktrees/ai-develop-into-next-20260908`, branch
  `feature/ai-develop-into-next-20260908`.
- The main workspace stays on `develop`. Integration targets the existing
  `.worktrees/next-merge` checkout of `next`.

The merge preview reported 40 conflicting files, including 17 runtime Java files.
The version remains `0.7.prerelease`; macOS bundle metadata follows it as `0.7.0`.

## Resolution decisions

- Preserve custom module loading before stock prepared loads, discard stale prepared
  results on override/deferred paths, and prevent synthetic mod destinations from
  reaching the stock ROM preparer. Preserve FBZ resource handoffs and mod parallax
  while adopting the pure prepared-art builders and AIZ preparation work.
- Port special-stage entry/reveal and continue-screen behavior into `next`'s
  extracted lifecycle helpers, retaining Time Attack, mod notices, and widescreen.
  Wrapped modules forward the new continue-screen factory.
- Retain queued streamed fades while adopting physical-write observers and the
  session's speed-shoes/fade state. Keep streamed and SMPS restore admission with
  their respective owners. A coexistence regression exposed an existing omission:
  streamed voices must implement the existing PCM marker to be mixed alongside an
  SMPS session and advance their fade. Queued SMPS fades go through the session
  host so PSG pre-fade effects run, while inactive retained SMPS state cannot
  intercept a streamed foreground fade.
- Retain the configurable save root with an owned asynchronous writer that flushes
  on root change/shutdown. Menu launch commits remain synchronous and wait for
  older queued writes; menu readers and both cleanup paths flush the retained
  writer. In-game progression saves remain asynchronous. Both paths capture the
  durable character selection rather than a destination-forced launch team.
- Adopt sparse configuration, the FAST load manifest, and the FM core selection;
  retain `next`'s configuration keys and derived viewport dimensions.
- Adopt smoke-only branch-push CI while preserving explicit Mod API destination
  arguments. Release validation retains the reviewed trace-debt comparison.
  Structural checks follow the owner-aware report validator and compact guidance;
  Python bytecode produced by the release-tool tests is excluded from text scans.
- Adopt the rewritten 0.6 release notes and compact agent guidance; retain 0.7
  feature/roadmap content and move unique next-line domain knowledge into linked,
  mirrored references.

## Verification method

Baseline checkouts are detached and pinned to the two input commits, with separate
`target/` directories. Java is OpenJDK 21.0.11; guards use `/usr/bin/lua5.4`.
`OPENGGF_REPO` denotes the main checkout containing the verified ROMs;
`MERGE_EVIDENCE_DIR` denotes this task's external scratch evidence directory.
The archived command JSON retains the exact resolved absolute arguments.
All ordinary and focused Maven commands use `-Dmse=off` and these verified files:

```text
-Dsonic1.rom.path=${OPENGGF_REPO}/Sonic The Hedgehog (W) (REV01) [!].gen
-Dsonic2.rom.path=${OPENGGF_REPO}/Sonic The Hedgehog 2 (W) (REV01) [!].gen
-Ds3k.rom.path=${OPENGGF_REPO}/Sonic and Knuckles & Sonic 3 (W) [!].gen
```

CRC32/SHA-1 match the repository's S1 REV01, S2 REV01, and locked-on S3K identities.
Surefire reports are separated below each worktree's `target/merge-validation/`.
Failure comparisons retain full nested class names, test names, messages, and
occurrence multiplicities. Dynamic test display names absent from Surefire XML
cannot be reconstructed; changed dynamic groups require focused inspection.

The develop full run reports 17,049 tests in Maven but 16,947 in XML headers.
All 2,050 logged suite names have reports. Repeated nested suites account for the
102 difference: testcase elements retain 90 additional occurrences, while 12
repeated executions were overwritten. Every corresponding log event passed.
Use completed Maven totals together with suite events and testcase evidence.

## Mod API candidate integration

The `next` baseline passed `TestModApiSignatureSurface`, `TestModApiPinPolicy`,
`TestModApiReleasePolicy`, and `TestModApiRuntimePolicy`. The first merged run's
surface/pin failures and Javadoc inventory error were new integration defects.
Existing annotated owners exposed four new unannotated engine types:
`GameModule`/`GameLoop` expose `ContinueScreenProvider`, `GraphicsManager` exposes
`PaletteFadePresentation` and its `Mode` enum, and `LevelTilemapManager` exposes
`PrebuiltTilemaps`. All four are now annotated; no platform or third-party
allowlist was expanded.

The [candidate policy](../mod-api-compatibility.md) permits replacing the
unpublished major/minor pin in place. `mod-api-signatures-0.7.txt` changes from
18,851 to 18,947 sorted, unique LF lines: 103 additions and seven removals.
The additions include four types, eight annotation metadata lines, Continue/fade
contracts, prebuilt tilemaps, async saves, pattern refresh, and FM/SMPS controls.
The removed signatures are accounted for as follows:

- `MutationEffects` gains `patternLookupRefreshRequired`; its canonical constructor
  gains one boolean component.
- `CoordFlagContext.releaseChannelToMusic(TrackType,int)` becomes the owning
  `Track`-based method.
- `Rom.open`, `close`, `write16BitAddr`, and `write32BitAddr` retain their arguments
  and returns but gain `synchronized`, changing their normalized signatures.
- The obsolete `SpriteManager.primePlayableVisualState()` entry is removed.

`ModApiVersion` remains `0.7.0`. Descriptor version, candidate status, and empty
published-baseline set remain unchanged; this is neither publication nor a new
compatibility promise. AGENTS/CLAUDE retain compact guidance and explicitly state
that `mod-api-release-policy.properties` is the sole authority.

After compilation, the snapshot was generated and reviewed with:

```bash
mvn dependency:build-classpath -Dmdep.outputFile=target/mod-api-snapshot-classpath.txt
java -cp "target/classes:$(cat target/mod-api-snapshot-classpath.txt)" \
  com.openggf.mods.code.ModApiSignatureSurface --snapshot \
  > target/mod-api-signatures-0.7.candidate.txt
```

The generated file was compared with the candidate pin before installing it.
Snapshot generation rejected neither missing annotations nor external signature
leaks; `git diff --check` passed. Runtime/test validation belongs to the final
candidate run, not to snapshot generation. Focused selectors cover
`TestModApiSignatureSurface`, `TestModApiPinPolicy`, `TestModApiReleasePolicy`,
`TestModApiRuntimePolicy`, `TestModApiJavadocTool`, `TestModApiSdkPackager`, the
maintained sample integrations, and the sole-authority documentation assertion.

## Baseline results and completeness

| Pinned tree | Completed Maven total | XML occurrence evidence | Completion |
|---|---|---|---|
| develop `8392494a0` | 17,049 tests; 0 failures; 0 errors; 24 skips | 17,037 cases; 17,013 passed; 24 skipped | BUILD SUCCESS, 2026-09-08 14:52:42 Europe/London |
| next `988b5d73a` | 19,528 tests; 36 failures; 21 errors; 22 skips | 19,531 cases; 39 failures; 21 errors; 22 skips | BUILD FAILURE from test results, 2026-09-08 15:17:55 Europe/London |

Develop's XML header sum is 16,947; the repeated nested-suite behavior described
above accounts for its difference from the completed Maven total. Next has 2,413
suite names, with no missing or extra XML suites relative to the log. Its XML
headers and suite-event sum both equal 19,531. Neither run reports a crashed fork
or leaves a Surefire dump file in the archived report directory.

Next's three-count difference is specifically
`TestFbz2SubbossRewind#songFadeCallersUseInclusiveNativeSignedWaitWords`.
Its `@TestFactory` emits waits 2, 30, 90, and 120. All four fail the same
`fadeOutMusic(40, 6)` verification because production calls `fadeOutMusic()`.
XML retains four cases; Maven's final result groups them under one method as
“Run 1” through “Run 4,” subtracting three tests and three failures. These are
four dynamic cases, not rerun attempts. Failure comparison preserves all four.

Separate fresh-JVM structural-guard baselines (`mvn -Dmse=off -Pguards test -B`,
with the same ROM/report-root arguments) completed with 613 tests and no failures
on develop, and 646 tests with one failure on next. The next failure is
`TestBuildToolingGuard#macosBundleMetadataShouldMatchMavenVersion`: the old plist
version does not match Maven's 0.7 line. The merged plist uses numeric `0.7.0`
metadata and the assembler receives Maven's version. These guard outcomes are
separate from the ordinary-suite totals.

The exact **57 baseline failing identities** (36 assertion identities and 21
error identities), occurrence counts, exception types, assertions, and nested
causes are retained in the [baseline appendix](2026-09-08-develop-into-next/next-baseline-failures.md)
and its deduplicated JSON evidence. Raw stacks remain in the original XML.

All skips were inspected. Both runs share 22 skipped identities: seven absent
local audio reference captures; five explicit S2 ROM/BK2 measurement inputs;
one S3K request-observation input; one deterministic S1 BizHawk reference;
one opt-in timeline capture; five opt-in benchmark/soak/shader/background
measurements; one object-dispatch measurement; and the existing CPZ spin-tube
capture/release assumption. Develop adds two opt-in checkpoint/rewind-allocation
probes. There are no blanket missing-ROM class skips; the report properties
contain Java 21.0.11 and all three absolute ROM paths.

The ordinary invocation in each pinned baseline worktree is:

```bash
LUA_BIN=/usr/bin/lua5.4 mvn -Dmse=off \
  "-Dsonic1.rom.path=${OPENGGF_REPO}/Sonic The Hedgehog (W) (REV01) [!].gen" \
  "-Dsonic2.rom.path=${OPENGGF_REPO}/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  "-Ds3k.rom.path=${OPENGGF_REPO}/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  -Dopenggf.surefire.reports=target/merge-validation/full-reports test -B
```

Original artifacts, relative to the repository root:

- `.worktrees/ai-develop-merge-base-20260908/target/merge-validation/full.log`
  and `full-reports/` at the same directory.
- `.worktrees/ai-next-merge-base-20260908/target/merge-validation/full.log`
  and `full-reports/` at the same directory.
- `.worktrees/ai-develop-into-next-20260908/target/merge-validation/` for candidate
  logs/reports. The first completed full run is diagnostic evidence, not final approval.

The session's parser and frozen summaries are under
`${MERGE_EVIDENCE_DIR}/`:
`summarize_multiset.py`, `develop-full-multiset.json`, `next-full-multiset.json`,
`merge-first-full-multiset.json`, and `mod-api-reviewed-delta.txt`.
The parser accepts `TREE RUN OUTPUT [BASELINE_SUMMARY]`. It preserves every
occurrence and compares status/type/message/text multisets by full test identity;
worktree-root prefixes and Java source line numbers are normalized. It reports added/missing identities
separately from changed outcomes. Raw baseline/candidate logs and reports will be archived under that scratch
session directory before worktree cleanup; the paths above identify their original
locations. The durable appendix survives worktree cleanup.

## Missing-identity audit

The first candidate full run omits 14 identities from the next baseline.
Inspection found intentional upstream replacements in ten test classes, with
31 added/replacement identities in those classes, all passing in that run.
The [exact inventory](2026-09-08-develop-into-next/test-identity-replacements.json)
retains both sets, including parameter indices.

| Changed coverage | Reason for replacement |
|---|---|
| S1/S2 SEGA PCM | Real DAC transport replaces the previous unsupported-policy assertion; gain, exit, interruption, rewind and batching checks are added. |
| Load-time default and configuration bootstrap | FAST and sparse persistence replace NONE and default-backfilling assertions. |
| Master title previews | Preserve the opaque S2 emblem and compose copyright/trademark pixels instead of removing them. |
| Special-stage entry and S2 startup | Exercise ROM fade/startup, load-hold and rewind/reinitialization boundaries instead of immediate fast-forward. |
| Load profile factory | Add FAST manifest selection while retaining per-resolution missing-manifest/REALISTIC fallback checks. |
| Display shader state | Expand the single test into texture-unit 0 and 1 cases. |
| S1 override/resume reference | Rename the existing unavailability assertion honestly; it did not previously prove PCM parity. |
| S3K audio sidecar | Advance the recorded frontier and add ordered service-write gates. |

Eight affected files match develop exactly. The other two preserve next's extra
configuration-bootstrap assertions and stronger per-resolution warning checks.
No missing identity was explained by a skipped or unexecuted replacement.

The final candidate has **16** missing next-baseline identities: the same 14
upstream replacements plus two integration tests whose `@TempDir Path` parameter
changes the XML method signature. Both replacements pass:

- `TestGameDataSourceSharedFetches#sourceDefaultsRequireRomCapabilityButPreserveStockRomBytes(Path)`.
- `TestModZoneAdapterRouting#sonic2AdapterBuildsThroughTheImmutableDefinitionLoader(Path)`.

Their added temporary directories isolate save-root state; their asserted source
and adapter contracts remain covered. The JSON inventory keeps the initial
14-to-31 upstream audit separately from these two final signature replacements.

The final 24 skipped identities exactly match develop's inspected skip inventory.
Cross-baseline review found 11 identities that pass on develop but remain red in
the merged candidate. All 11 already fail on next: ten match next's normalized
status/type/message/stack outcome exactly; the empty-BK2 failure differs only in
the diagnostic `GameLoop` object identity hash. These remain next baseline debt,
not new merge failures, but the result must not be described as preserving a
fully green develop-common set. No final failing identity is newly red relative
to both baselines.


## Candidate results

The corrected ordinary suite completed on staged tree
`58d9bc858f2451c5c19c7e0324b0ec8f38a44bcc` with **20,118 tests, 35 failures,
21 errors, and 24 skips** at 15:48:10 Europe/London (15m59s). The invocation is the
ordinary command above with report label `full-final`. XML headers, testcase
occurrences, and suite events agree at 20,121 cases / 38 failures / 21 errors /
24 skips; the same four-case FBZ factory explains Maven's three-count difference.
Every logged suite has its report, with no extra report suites.

There are **no new or worsened failures relative to next**. Of 57 baseline failing
identities, 56 retain their failures; `TestRomAudioIntegration`'s
`sonic2DacUsesTheShippedTwoSampleCycleBudget` now passes. The only other changed
outcome records are unchanged failures with nondeterministic presentation:

- FBZ registry expected/actual sets contain exactly the same 49/175 integers in
  both runs; only iteration order changes. Both sets were compared explicitly.
- The empty-BK2 assertion still expects null but sees a `GameLoop`; only its JVM
  identity hash differs. Assertion type, remaining text, and stack match.

All other failure/error status, types, messages, stacks, and occurrence counts
match after normalizing worktree roots and Java source line numbers. The frozen
comparison is `merge-final-full-multiset.json` in the scratch evidence directory.
All 429 added identities were inspected through outcome comparison; none fails.
The required AIZ skip, S3K loading, bootstrap resolver and decoding classes pass.
Focused `focused-final` ran 158 tests, with one incomplete S2 title fixture; after
setting its already-active palette-loaded state, `title-fix` passed all 32 title,
streamed audio, fade and save checks. All affected regressions pass in the full run.

The full fresh-JVM guard command (`-Pguards`, label `guard`) completed 651 checks
with two failures, zero errors, zero skips at 15:53:16. Both were integration
bookkeeping mismatches in `TestArchitecturalSourceGuard`: adopt develop's
capitalized bounded-timing phrase and reconcile the sprite's exact effective-line
inventory from 3256 to 3258. Upstream removes two duplicate reset assignments and
replaces one assignment with five native animation reset operations, a net two
lines; the ratchet retains zero growth allowance. No runtime code changed for
these guard corrections. The affected 72-check class passed in a fresh JVM
(`-Pguards -Dtest=TestArchitecturalSourceGuard`, label `guard-fix`) at 15:53:52
with zero failures/errors/skips. Together the complete guard run and focused
correction cover all 651 guard checks without an outstanding failure.

`mvn -Dmse=off -DskipTests verify -B` passed at 15:54:52 (28.708s), including
application, bundled application, Mod SDK binary/Javadoc jars and the SDK artifact
verifier. Tests were deliberately skipped only in this packaging command; ordinary
and guard evidence comes from the separate completed runs above. Release tooling
checks also passed: 41 release-collection/comparison tests, skip-classifier tests
(20 total, one historical-report-only skip), workflow YAML parsing and all embedded
bash script syntax checks. Guidance/skill mirrors, validation JSON and whitespace
checks pass.

The pre-integration fetch confirms the original remote develop/next commits are
unchanged. Merge commit `973fa3ab823610feda75f945486b1f44188daa15` has the pinned next
and develop commits as its two parents. It was fast-forwarded into the existing
next checkout without changing the main workspace branch.

## Post-integration verification

The ordinary suite ran from a fresh `target/` in `.worktrees/next-merge` at
`973fa3ab8`, using the ordinary command above with label `postintegration-full`.
It completed at 16:14:36 Europe/London in 16m26s: **20,118 tests, 35 failures,
21 errors, 24 skips**. XML headers, cases, and suite events agree at 20,121 / 38 /
21 / 24, with the already-explained dynamic-factory difference. There are no
missing/extra suite reports and no added/missing identities relative to the
validated candidate. Every outcome multiset matches, allowing only the two
explicitly verified set-order/object-hash presentation differences described
above. All 49 focused audio/save/standalone regression cases pass again without
skips. The inherited 56 failing identities remain documented baseline debt;
this is a no-regression integration, not an all-green suite claim.

The only source-file change between the ordinary candidate's recorded tree and
the merge commit is the separately verified architectural guard correction.
Runtime source is identical. The evidence-only follow-up changes no executable
contract; its JSON, links and whitespace are checked without repeating Maven.

Completed raw logs, command metadata and reports are archived under
`${MERGE_EVIDENCE_DIR}` in `ai-next-merge-base-20260908-evidence.tar.gz`,
`ai-develop-merge-base-20260908-evidence.tar.gz`,
`ai-next-merge-guards-20260908-evidence.tar.gz`, `merge-candidate-evidence.tar.gz`
and `postintegration-evidence.tar.gz`. The final comparison is
`postintegration-full-multiset.json`. These archives contain validation evidence,
not shared/copied Maven build trees. The tracked appendix provides portable
baseline failure and coverage inventories after temporary worktree cleanup.

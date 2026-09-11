# Dead and unfinished code sweep validation

**Worktree:** `bugfix/ai-dead-unfinished-sweep` at `345fa27c2`
**Purpose:** reproducible pre-mutation baseline evidence.

## Environment and exploratory output

`mvn -v` reported Maven 3.9.16 on Java 21.0.11 (Arch Linux). The selected ROMs
match `AGENTS.md` after `cksum -a crc32b`, decimal-to-hex normalization, and
`sha1sum`:

```bash
# Run from the discovered-ROM repository root.
WORKTREE=$(pwd)
```

| Game | Selected path | CRC32 | SHA-1 |
|---|---|---|---|
| S1 World REV01 | `${WORKTREE}/Sonic The Hedgehog (W) (REV01) [!].gen` | `AFE05EEE` | `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| S2 World REV01 | `${WORKTREE}/Sonic The Hedgehog 2 (W) (REV01) [!].gen` | `7B905383` | `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| S3&K locked-on | `${WORKTREE}/Sonic and Knuckles & Sonic 3 (W) [!].gen` | `63522553` | `CFBF98C36C776677290A872547AC47C53D2761D6` |

The initial exploratory `mvn test` regenerated the tracked rewind-gap report.
Its diff contained generated gap-inventory additions/reordering only, with no
source change. It was restored using the exact command below before every later
Maven run and is unstaged:

```bash
git restore --source=HEAD --worktree -- docs/status/rewind-round-trip-gaps.md
```

The design's recorded exploratory baseline is 14,258 tests, 33 failures, 15
errors, and 35 skips.

## Surefire outcome manifest

`tools/test-reports/surefire-outcome-manifest.xsl` is an XSLT 1.0 normalizer.
Each sorted row has this exact structure:

```text
classname<TAB>method-or-display-name<TAB>PASS|FAILURE|ERROR|SKIPPED<TAB>exception-type<TAB>single-line-message
```

Passed and skipped rows are retained, so an execution disappearing or changing
class is observable. Use:

```bash
for report in target/surefire-reports/TEST-*.xml; do
  xsltproc tools/test-reports/surefire-outcome-manifest.xsl "$report"
done | LC_ALL=C sort | gzip -n > docs/architecture/validation/evidence/dead-code-sweep/RUN_NAME.tsv.gz
diff -u <(gzip -dc BASELINE.tsv.gz) <(gzip -dc CANDIDATE.tsv.gz)
```

The normalized row count must equal the Surefire test total. Every diff needs
review; only stated upstream or baseline changes are acceptable.

## Focused pre-mutation baselines

| Exact command | Result and manifest | Baseline outcome |
|---|---|---|
| `mvn clean -Dmse=off "-Dtest=com.openggf.tests.TestBuildToolingGuard,com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalSourceGuard" test` | 98 rows = 98 tests; 95 PASS, 3 FAILURE, 0 ERROR, 0 SKIPPED. `focused-orphans-baseline.tsv.gz`. | ArchUnit rejects `trace -> graphics`; source ratchets reject `ObjectManager` (2972 vs 2914) and `AbstractPlayableSprite` (3175 vs 3161). |
| `mvn clean -Dmse=off "-Ds3k.rom.path=${WORKTREE}/Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.tests.TestBuildToolingGuard,com.openggf.tools.disasm.TestRomOffsetFinderIncludedAsmLabels,com.openggf.trace.TestTraceV5LoadingContract,com.openggf.game.sonic3k.objects.TestCnzMinibossDefeatPhase,com.openggf.level.TestLevelManagerInitialPresentationPlcLifecycle,com.openggf.tests.TestS3kSpecialStageHeadlessBoot" test` | 91 rows = 91 tests; 89 PASS, 1 FAILURE, 1 ERROR, 0 SKIPPED. `focused-compat-baseline.tsv.gz`. | Build tooling guard cannot lock shared `.git/config`; S3K headless boot cannot load `liblwjgl.so`. |
| `mvn clean -Dmse=off "-Dtest=com.openggf.game.sonic2.objects.TestTodo4_MCZBossCollision,com.openggf.tests.TestS3kSpringObjectInstance,com.openggf.game.sonic3k.objects.TestSonic3kSpringObjectInstance,com.openggf.sprites.playable.TestSidekickCpuControllerCatchUpFlight,com.openggf.sprites.playable.TestSidekickCpuControllerFlightAutoRecovery" test` | 137 rows = 137 tests; 96 PASS, 1 FAILURE, 40 ERROR, 0 SKIPPED. `focused-comments-baseline.tsv.gz`. | Build tooling guard cannot create a temporary Git pack; 40 errors start with `liblwjgl.so` and cascade through `GlfwKeyNameResolver$Holder` across S3K spring and sidekick tests. |

The commands were serial and each used `mvn clean`. The generated rewind report
was clean after every command. These known red outcomes are the pre-mutation
comparison baseline, not sweep regressions.

## Reserved delivery comparisons

### Previous updated integration baseline (`3f0fd4a70`)

The main workspace remained on `develop`; fetch and `git merge --ff-only
origin/develop` left it current at `3f0fd4a70`. Existing untracked user files
were not touched. Maven again reported Java 21.0.11, and all three ROM hashes
matched the table above.

The planned default-four-fork command was run first:

```bash
WORKTREE=$(pwd)
mvn -Dmse=off \
  "-Dsonic1.rom.path=${WORKTREE}/Sonic The Hedgehog (W) (REV01) [!].gen" \
  "-Dsonic2.rom.path=${WORKTREE}/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  "-Ds3k.rom.path=${WORKTREE}/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  clean test
```

Surefire reported 14,364 tests, 33 failures, 14 errors, and 31 skips. Its XML
contained 14,355 normalized testcase rows: 14,277 PASS, 33 FAILURE, 14 ERROR,
and 31 SKIPPED. This contextual run is preserved as
`updated-baseline-default-fork.tsv.gz`.

Task 4 had already exposed a shared LWJGL extraction race between default
forks. When the development default-fork run reproduced it at full-suite scale,
the design and plan were amended to use the repository's documented CI fork
mode for both sides of the comparison. The first serial baseline used
`-Dsurefire.forkCount=1` but remained diagnostic because the default
`filesystem` class order differed between clean trees; it is preserved as
`updated-baseline-filesystem-order.tsv.gz`. The accepted baseline adds
`-Dsurefire.runOrder=alphabetical`. It reported and normalized exactly 14,341
tests: 14,262 PASS, 34 FAILURE, 14 ERROR, and 31 SKIPPED. That deterministic
manifest is `updated-baseline.tsv.gz`. After each main run, the only tracked
change was the generated rewind-gap report, which was inspected and restored.

### Renewed integration baseline (`a8bfbcd7a`)

Main and `origin/develop` now both resolve to
`a8bfbcd7a85e00d760409e0dc9e02d16ef9763c8`. The seven formerly staged files
were committed there outside this sweep; main has no tracked/index changes.
Its unrelated untracked files are preserved untouched. Because the new commit
changes runtime/test owners, the accepted baseline was regenerated with Java
21.0.11 and the same three-ROM alphabetical one-fork command:

```bash
MAIN_WORKSPACE=$(pwd)
mvn clean -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  "-Dsonic1.rom.path=${MAIN_WORKSPACE}/Sonic The Hedgehog (W) (REV01) [!].gen" \
  "-Dsonic2.rom.path=${MAIN_WORKSPACE}/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  "-Ds3k.rom.path=${MAIN_WORKSPACE}/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  test
```

Surefire and the normalized XML both contained 14,341 tests: 14,262 PASS, 34
FAILURE, 14 ERROR, and 31 SKIPPED. The renewed manifest is
`updated-baseline.tsv.gz`. The previous accepted manifests are retained as
`updated-baseline-3f0fd4a70.tsv.gz` and
`development-3f0fd4a70.tsv.gz`. The only tracked main change after the suite
was the generated rewind report; it was inspected and restored. Unrelated
untracked main files remained untouched.

### Development worktree result

Task 2 ran serially in `bugfix/ai-dead-unfinished-sweep` after deleting the
seven proven-obsolete types and relocating the unused Kosinski reference:

| Exact command | Result |
|---|---|
| `mvn clean -Dmse=off -DskipTests package` | Passed. Production and test sources compiled; the removed classes and `kosinski.txt` were absent from both `target/classes` and `target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar`. |
| `mvn clean -Dmse=off "-Dtest=com.openggf.tests.TestBuildToolingGuard,com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalSourceGuard" test` | 98 rows = 98 tests; 95 PASS, 3 FAILURE, 0 ERROR, 0 SKIPPED. `focused-orphans-development.tsv.gz` is byte-equivalent by normalized decompressed rows to `focused-orphans-baseline.tsv.gz`. The same `trace -> graphics`, `ObjectManager`, and `AbstractPlayableSprite` ratchets remain. |

The focused suite did not modify `docs/status/rewind-round-trip-gaps.md`, so no
rewind report was restored. Native resource JSON validated with `jq empty`; the
Kosinski include is absent and the broad `shaders/.*\\.(glsl|vert|frag)` include
remains.

### Task 3 compatibility cleanup result

After removing only the caller-free compatibility aliases, the protected
no-argument `LevelManager` constructor, and the six unverified duplicate S3K
results constants, Task 3 ran the following commands serially from this
worktree:

```bash
mvn clean -Dmse=off -DskipTests test-compile
WORKTREE=$(pwd)
mvn clean -Dmse=off \
  "-Ds3k.rom.path=${WORKTREE}/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  "-Dtest=com.openggf.tests.TestBuildToolingGuard,com.openggf.tools.disasm.TestRomOffsetFinderIncludedAsmLabels,com.openggf.trace.TestTraceV5LoadingContract,com.openggf.game.sonic3k.objects.TestCnzMinibossDefeatPhase,com.openggf.level.TestLevelManagerInitialPresentationPlcLifecycle,com.openggf.tests.TestS3kSpecialStageHeadlessBoot" \
  test
```

Both commands passed. The focused run produced 91 normalized rows: 91 PASS, 0
FAILURE, 0 ERROR, 0 SKIPPED. Its normalized manifest is
`focused-compat-development.tsv.gz`. Compared with
`focused-compat-baseline.tsv.gz`, the only two changes are environmental
improvements: `TestBuildToolingGuard` can now write its temporary Git config,
and `TestS3kSpecialStageHeadlessBoot` can load `liblwjgl.so`; both changed from
the recorded baseline failure/error to PASS. The suite did not modify
`docs/status/rewind-round-trip-gaps.md`, so no generated rewind report was
restored. Exact-symbol scans found the removed trace aliases only in the
intentional guard literals; remaining non-code hits are historical sweep
evidence.

### Task 5 pre-merge focused reruns

After merging `develop` at `3f0fd4a70` into the worktree without conflict, the
Task 2–4 focused groups were rerun cleanly:

| Group | Result | Evidence |
|---|---|---|
| Orphan/source guards | 176 rows: 173 PASS, 3 FAILURE, 0 ERROR, 0 SKIPPED | `focused-orphans-premerge.tsv.gz`; the same pre-existing ArchUnit, `ObjectManager`, and `AbstractPlayableSprite` ratchets. |
| Compatibility owners | 91 rows: 90 PASS, 0 FAILURE, 1 ERROR, 0 SKIPPED | `focused-compat-premerge.tsv.gz`; only `TestS3kSpecialStageHeadlessBoot` failed to locate `liblwjgl.so`, the already documented host-native condition. |
| Comment owners, `-Dsurefire.forkCount=1` | 60 rows: 60 PASS | `focused-comments-premerge.tsv.gz`; includes the reverse-gravity characterization. |

### Task 5 development full-suite result

The rejected default-four-fork attempt failed native initialization early.
Once one reused fork could not load `liblwjgl.so`,
`GlfwKeyNameResolver$Holder` poisoned later configuration setup. Surefire
reported 12,271 tests, 31 failures, 2,860 errors, and 28 skips; the incomplete
XML held only 12,256 rows (9,344 PASS, 31 FAILURE, 2,853 ERROR, 28 SKIPPED).
`development-default-fork-incomplete.tsv.gz` is diagnostic evidence only; no
missing row is waived.

The first serial development command added `-Dsurefire.forkCount=1` to the exact
clean ROM-backed baseline command. It completed 14,342 normalized rows:
14,261 PASS, 34 FAILURE, 16 ERROR, and 31 SKIPPED. The baseline/development
manifest diff was reviewed row by row:

- the expected new
  `reverseGravitySwapsVerticalSpringDirectionDuringNativeInit` row is PASS;
- `TestTraceSessionLauncherProductionFailureCleanup` retained the same failure
  type/message apart from a volatile object identity hash;
- six pre-existing Tornado errors retained the same test identity and
  `NullPointerException` type, with only helpful-NPE message rendering absent;
- `productionObjectLifecycleRawCallCountsDoNotGrow` retained the exact same 13
  violation paths and budget; only filesystem traversal order changed after
  dead source files were removed; and
- three `TestGameLoopSpecialStageRewindGate` rows changed from PASS to two null
  focused-sprite errors and one failed rewind-engagement assertion.

That serial comparison is rejected, not waived. Full-stack and lifecycle
inspection showed that default Surefire `filesystem` order placed the already
failing `TestTraceSessionLauncherProductionFailureCleanup` before the rewind
gate only in the clean development tree. This focused one-fork order reproduced
the exact gate outcomes:

```bash
mvn -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=reversealphabetical \
  "-Dtest=com.openggf.TestTraceSessionLauncherProductionFailureCleanup,com.openggf.TestGameLoopSpecialStageRewindGate" test
```

The cleanup class retained its known one failure and three errors, followed by
the gate's exact two errors and one failure. A separate requested negative
control ran the spring characterization before the gate in one fork and passed
21/21; `TestSpecialStageLogicalInput` before the gate also passed 5/5. Finally,
an exact complete serial development rerun repeated 14,342 rows with the same
14,261 PASS / 34 FAILURE / 16 ERROR / 31 SKIPPED totals. This establishes
pre-existing launcher leakage plus unstable filesystem ordering as the cause,
and rejects both a spring leak and a one-off run. No production or test code
was changed.

The accepted gate reruns the full baseline and development suites with both
`-Dsurefire.forkCount=1` and `-Dsurefire.runOrder=alphabetical`. The original
filesystem-order manifests are retained as diagnostics. The generated rewind
report is restored after each run. Acceptance requires every baseline PASS row
to remain PASS, no disappeared test, and exactly the intended added spring PASS.

Both deterministic runs completed. Baseline contained 14,341 rows: 14,262
PASS, 34 FAILURE, 14 ERROR, and 31 SKIPPED. Development contained 14,342 rows:
14,263 PASS, 34 FAILURE, 14 ERROR, and 31 SKIPPED. A four-column
class/name/outcome/type comparison found zero removed or reclassified outcomes
and exactly one addition, the expected passing reverse-gravity spring
characterization. A direct baseline-PASS comparison found zero missing rows.
The raw 60-line diff was reviewed completely and contains only:

- the expected added spring PASS;
- one volatile launcher object identity hash on the same pre-existing failure;
- six unchanged Tornado `NullPointerException` errors whose helpful-NPE message
  is empty in development; and
- the same 13 raw lifecycle-call guard paths and budget in a different message
  order.

All four `TestGameLoopSpecialStageRewindGate` rows are PASS in both accepted
manifests. No baseline-passing test regressed, no executed test disappeared,
and no red outcome changed status or exception type. The accepted manifests are
`updated-baseline.tsv.gz` and `development.tsv.gz`; the two repeated rejected
development manifests are retained as
`development-filesystem-order.tsv.gz` and
`development-filesystem-order-rerun.tsv.gz`.

### Renewed `a8bfbcd7a` baseline/development comparison

The exact `a8bfbcd7a` baseline described above was merged into the clean
feature branch without conflict or data loss as merge commit `d9e552fbc`. The
feature worktree then ran the identical Java 21.0.11, three-ROM,
`-Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical` clean suite. Its
normalized `development.tsv.gz` contains 14,342 rows: 14,263 PASS, 34 FAILURE,
14 ERROR, and 31 SKIPPED.

Machine comparisons produced:

```text
missing baseline PASS rows:                         0
removed/reclassified class/name/outcome/type rows: 0
added class/name/outcome/type rows:                 1
```

The sole addition is:

```text
com.openggf.game.sonic3k.objects.TestSonic3kSpringObjectInstance<TAB>reverseGravitySwapsVerticalSpringDirectionDuringNativeInit<TAB>PASS<TAB><empty type>
```

The complete raw diff is 28 lines. It contains that expected PASS, the same
pre-existing `TestTraceSessionLauncherProductionFailureCleanup` failure with
only a volatile object identity hash changed, and the same 13
`productionObjectLifecycleRawCallCountsDoNotGrow` violation paths/budget in a
different message order. Every test identity, outcome, and exception type is
otherwise identical. No baseline PASS regressed, no test disappeared, and no
red outcome was reclassified. The generated development rewind report was the
only tracked test output and was inspected and restored.

After the living documentation was refreshed, the focused documentation/build
guard command
`mvn -Dmse=off -Dsurefire.forkCount=1
"-Dtest=com.openggf.tests.TestBuildToolingGuard,com.openggf.configuration.TestModifierSupportDocumentation"
test` passed all 85 tests.

### Task 4 stale-marker characterization

The new
`reverseGravitySwapsVerticalSpringDirectionDuringNativeInit` characterization
passed before any comment edit, proving native UP becomes DOWN and native DOWN
becomes UP while reverse gravity is active. Two clean executions of the planned
default-fork focused command did not complete: the first emitted 51 passing
rows before `SIGBUS` terminated the `TestS3kSpringObjectInstance` and
`TestSidekickCpuControllerCatchUpFlight` forks; the second emitted 53 passing
rows before the catch-up fork terminated with the same `SIGBUS` in
`ld-linux-x86-64.so.2`. Neither partial run had a Java failure or error row,
but neither manifest is accepted because testcase rows are missing.

The before/after comparison therefore used the identical class set with the
repository's documented CI fork setting:

```bash
mvn clean -Dmse=off -Dsurefire.forkCount=1 \
  "-Dtest=com.openggf.game.sonic2.objects.TestTodo4_MCZBossCollision,com.openggf.tests.TestS3kSpringObjectInstance,com.openggf.game.sonic3k.objects.TestSonic3kSpringObjectInstance,com.openggf.sprites.playable.TestSidekickCpuControllerCatchUpFlight,com.openggf.sprites.playable.TestSidekickCpuControllerFlightAutoRecovery" \
  test
```

Both serial runs passed 60 tests with 60 PASS, 0 FAILURE, 0 ERROR, and 0
SKIPPED. `focused-comments-characterization.tsv.gz` and
`focused-comments-development.tsv.gz` each contain 60 normalized rows and
their decompressed diff is empty. The earlier 137-row baseline is not treated
as equivalent: it contains unrelated `TestBuildToolingGuard` rows and native
initialization errors, so its removed/reclassified rows remain unaccepted
environmental differences pending the required Task 5 full-suite comparison.
The stale-string scan returned no matches, and
`docs/status/rewind-round-trip-gaps.md` remained clean.

### Historical staged-main blocker (resolved)

Integration was paused before any merge or fast-forward when main `develop`
contained seven staged user-owned modifications. The planning
snapshot was recorded read-only at main
`3f0fd4a70b00e733b88445be7cf8425d8b431ffc` with:

```bash
git diff --cached --binary --output=/tmp/openggf-develop-staged-before-integration.patch
sha256sum /tmp/openggf-develop-staged-before-integration.patch
git rev-parse HEAD
git diff --cached --name-status
git diff --cached --numstat
```

The patch SHA-256 was
`a513e9a6804cc5f027636e3406ec3329954ca11fe03a64744553470185ce14ac`.
The staged name/status stream SHA-256 was
`e60a615e71499365bd84bb60e54b497a8c7a93efc63cf1466f993f6859747d0b`,
and the complete porcelain-status SHA-256 (including unrelated untracked user
paths that also must remain untouched) was
`9972fef2ffde5958fdfbbbabbb47935ee8223bd54f21c90db42edcbcc62788ee`.
All seven entries were staged modifications:

```text
M  docs/status/trace-frontier-log.md
M  src/main/java/com/openggf/TraceSessionLauncher.java
M  src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java
M  src/main/java/com/openggf/trace/replay/runs/RunPlaybackObservation.java
M  src/main/java/com/openggf/trace/replay/runs/TraceRunPlaybackCoordinator.java
M  src/test/java/com/openggf/tests/trace/runs/AbstractRunChainTest.java
M  src/test/java/com/openggf/tests/trace/runs/TestS2EhzHalfpipeRoundTripChain.java
```

Sorting those paths against the 57 paths changed by
`3f0fd4a70..94f0a2f14` produced an empty intersection, and
`git merge-base --is-ancestor 3f0fd4a70 94f0a2f14` exited zero. No main index
or worktree state was changed. The then-amended integration contract would not
have unstaged, stashed, reset, checked out, or committed these files. It would
have required a fresh binary patch/hash/path/status/HEAD snapshot immediately
before each guarded fast-forward and byte-for-byte verification afterward.
That contract is now superseded.

The superseded contract would have allowed main to fast-forward to the
descendant feature tip only if its `HEAD` and staged fingerprint remained
unchanged. A separate clean detached worktree would then have owned the exact
alphabetical one-fork post-merge suite, `merged.tsv.gz`, validation update, and
evidence commit. Main could have fast-forwarded to that evidence commit under
the same disjointness and fingerprint gates. Task 5 would have compared every
merged row against `updated-baseline.tsv.gz`; the seven user modifications
would have remained staged and untouched through push and task-owned worktree
cleanup.

That staged-state blocker was subsequently resolved outside this sweep. Main
advanced to `a8bfbcd7a`, which commits exactly the seven paths, matches
`origin/develop`, and has a clean tracked/index state. The prior fingerprints
remain historical evidence only. No sweep integration occurred during that
blocker. The renewed main baseline and identical development run after merging
`a8bfbcd7a` into the feature branch have since completed; the unrelated main
untracked paths remain protected throughout later integration.

### Merged result

Main `develop` fast-forwarded from `a8bfbcd7a` to the independently reviewed
feature tip `ea06c006f` with a clean tracked/index state. The unrelated
untracked-status SHA-256 remained
`ef8e4e023f712b7b3ec1862430119227968fb687ba562d099c733f33ab783a26`
before and after the fast-forward.

The exact deterministic JDK 21 / three-ROM command was then run from the clean
detached post-merge validation worktree. Maven exited 1 for the recorded red
baseline and reported 14,342 tests: 34 failures, 14 errors, and 31 skipped.
`merged.tsv.gz` contains 14,342 normalized rows: 14,263 PASS, 34 FAILURE, 14
ERROR, and 31 SKIPPED.

Machine comparison against the renewed `a8bfbcd7a`
`updated-baseline.tsv.gz` found zero missing baseline PASS rows and zero
removed or reclassified class/name/outcome/type rows. The sole added outcome
is the expected passing
`TestSonic3kSpringObjectInstance.reverseGravitySwapsVerticalSpringDirectionDuringNativeInit`.
All four `TestGameLoopSpecialStageRewindGate` rows pass in both manifests.
The development and merged four-column manifests are identical; their complete
raw diff is 11 lines and contains only volatile message text in the same
pre-existing outcomes. The suite-generated rewind report was inspected and
restored, leaving only this validation update and `merged.tsv.gz` for the
post-merge evidence commit.

The evidence commit, push, and task-owned worktree/branch cleanup remain to be
completed.

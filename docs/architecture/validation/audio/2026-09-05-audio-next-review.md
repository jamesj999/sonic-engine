# Audio next milestone review and integration record

## Delivery status

Delivered on `develop` as merge `e258282e0`, after refreshing the original
`bbf28b7dc` base to `ce3b9e291`. Verified code and the post-merge evidence
record were pushed through `b8f474379`. All five milestone worktrees and
their fully merged local branches were then removed; no worktree branch was
pushed. This final documentation update records that completed cleanup.
This is a bounded milestone, not completion of the release gates listed below.

## Independent task reviews

| Deliverable | Commits | Review and verification |
|---|---|---|
| S3K PSG takeover | `5ee8bb8ae` | Independent DAC investigator approved scope, retail source, ownership and hard prefix; 74 focused tests passed |
| DAC run provenance | `b710033b2`, `c877fca10` | Independent PSG investigator approved attribution and unchanged comparison semantics; 47 focused tests passed, then 14 after EOF diagnostic correction |
| Bounded capture and slice tests | `2d1ecf68d` | Independent PSG investigator found queued non-bus reset-origin gap; reproducing test failed, queue predicate fixed it, re-review approved; final 40 focused tests passed |
| Performance tooling | `c43e61e2e`, `de1f964f3` | 896 focused tests and standalone tools passed; independent review found missing direct-proof failure enforcement, fixed with three subprocess mutation controls; re-review approved and all five integrated tool checks passed |

Lead independently inspected the retail PSG admission/noise routines and the
engine ownership policy before implementation. The semantic change uses the
existing S3K profile setting, not fixture-specific byte filtering. The DAC lane
overturned the initial stranded-byte hypothesis using actual service context;
missing external control input is kept distinct from a playback defect.

The original PSG plan target of 1,571 complete matching services was revised
after measurement exposed another write in service 1570. The retained gate is
1,570 complete services plus the first 43 exact ordered writes of the next
service. No comparison was relaxed to obtain a green result.

## Integration conflicts and environment

The PSG merge into coordination conflicted only in the newest-first changelog:
both independent entries were retained. DAC integration was clean. Existing
user-modified disassembly submodules are untouched.

The first main baseline failed in native OpenGL initialization/context use on
unchanged code. Its editor class passed alone. The following full retry
collided with another agent's main-workspace Maven build, yielding class-loading
failure and changing reports during archival. Neither truncated run is accepted
as the regression baseline. The lead stopped only its identified overlapping
Maven process and requested coordination; the other agent's work was preserved.
An unchanged isolated baseline worktree was created at the same source commit.
Its complete run at `bbf28b7dc` passed: 16,482 reported executions, zero
failures/errors, 22 skips (16,386 distinct XML cases; nested-class reporting
accounts for the difference). Develop subsequently advanced to `ce3b9e291`
with agent-guidance/documentation changes only. A fresh isolated baseline on
that updated commit passed independently; the earlier result is not relabeled.

The combined oracle/capture/presentation focused run on `d27d4992e` plus the
lead's provenance-assertion and documentation edits passed 51 tests with zero
failures/errors/skips (`target/audio-next-final-focused.log`). Its oracle DAC
test was deliberately renamed to describe different run-start services rather
than imply a decoder attribution; baseline comparison must account for that
name change, not silently treat a missing old name as coverage loss.

Final independent runtime review inspected reset/endpoint proof, the actual CLI
read/close lifecycle, post-render output gating, queued-operation semantics and
repeated-jingle preconditions. It approved the bounded change with no new
blocker; it did not claim listening or sample-identical rewind validation.
The performance review's P2 was confined to the research proof's success path:
false rejection-control booleans now throw before emitting success JSON. Three
copied-source mutants exercise direct subprocess failure, and a fresh Native
Image executable passed the corrected positive path. No production synthesis
change was needed.

Benchmarks are deliberately sequenced outside build/capture windows. A quiet
window is a checked host condition, not a claim that CPU affinity reserves a
core or that a Ryzen 9950X establishes low-end performance.

## Release gates not discharged by these tests

- Human listening against equivalent retail-reference events.
- Equivalent whole-slice reference/engine audio clips, including interactive
  SFX, repeated 1-up and rewind context; standalone music renders are not that.
- Lower-end hardware and Windows/macOS native packaging.
- Observed producer tempo-control input for the S3K oracle; never hydrate
  speed-up state from later comparison snapshots.
- Next PSG volume-tail discrepancy and previously documented stale-IX behavior.

## Full-suite comparison

All runs use JDK 21.0.11, Lua 5.4, `-Dmse=off`, all three verified absolute
ROM paths and the same host graphics environment. Reports were archived only
after each process exited and before a later run reused Surefire output.

| Arm | Head | Ordinary: reported / failures / errors / skips | Separate guards |
|---|---|---|---|
| Updated baseline | `ce3b9e291` | 16,482 / 0 / 0 / 22 | 609 / 0 / 0 / 0 |
| Combined development | `e3e156c04` | 16,497 / 0 / 0 / 22 | 609 / 0 / 0 / 0 at `532e3d1af` |
| Merged develop | `e258282e0` | 16,497 / 0 / 0 / 22 | 609 / 0 / 0 / 0 |

The development arm adds 15 executions. Per-test comparison has no failures,
errors or newly skipped tests; guards have identical case sets and outcomes.
The only missing old name is the deliberately
renamed DAC provenance test, whose replacement retains the old assertions and
adds both run-start services. Distinct XML cases are 16,386 baseline versus
16,401 development; the 96-execution nested-class reporting difference is
present in both. The 22 skips include seven absent legacy PCM reference files,
opt-in diagnostics/soak/movie inputs, and the pre-existing CPZ spin-tube
assumption. They are not hidden ROM-fixture skips or proof of those gates.

`e3e156c04..532e3d1af` changes only the standalone JNI research proof and its
shell test, not `src/`, `pom.xml` or `.mvn/`. Those corrected tools are tested
separately; the final merged suite still runs on the delivered tree.

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Dsonic1.rom.path=$S1_ROM" "-Dsonic2.rom.path=$S2_ROM" "-Ds3k.rom.path=$S3K_ROM" test
LUA_BIN=lua5.4 mvn -Dmse=off -B -Pguards "-Dsonic1.rom.path=$S1_ROM" "-Dsonic2.rom.path=$S2_ROM" "-Ds3k.rom.path=$S3K_ROM" test
```

Merged comparison found no failures/errors or newly skipped tests. The
ordinary report has one additional reporting issue, independently checked:
the ICZ nested-suite XML finishes with nine cases then three in the merged
run, opposite the baseline/development order. The later file overwrites six
parent-method records; parent XML itself has zero cases in every arm. The
test source blob is identical across all three heads. Thus merged final XML
contains 16,395 cases, not 16,401; this is not silently treated as a test skip
or pass. The complete ordinary archive remains unchanged.

A separate explicit six-method run on `e258282e0` passed six tests with zero
failures/errors/skips. Its exact method set equals the six missing records;
after that supplemental check there are no unexplained case differences
against development. Baseline comparison also retains the documented DAC
test rename. Guards have identical case sets and outcomes. Evidence is in
`audio-next-merged-icz-supplement-evidence.json` and
`audio-next-merged-report-gap-closure.json`, alongside the original archives.

```bash
LUA_BIN=lua5.4 mvn -Dmse=off -B "-Ds3k.rom.path=$S3K_ROM" '-Dtest=TestSonic3kIczRewindRoundTrip#capturedBytesAreStableAcrossRoundTrip+roundTripPreservesPubliclyObservableState+roundTripPreservesSeamlessTransitionOrdinalsAndPublicationFences+roundTripRetainsSuccessfulPublicationIdentityWithoutClearingOrdinals+schemaCaptureIgnoresLiveSnowboardIntroReference+schemaCaptureProducesNonEmptyPayload' test
```

The final main merge was clean. A separate main performance baseline and its
guard run delayed integration; the lead's precondition aborted before merging
or overwriting reports while either was active. Main's final other-task guard
reports were preserved before the post-merge run. No other agent was stopped.
The earlier guidance update and both README summaries remain included.

The final focused oracle/capture/presentation run at `532e3d1af` passed 51
tests, zero failures/errors/skips (`audio-next-integrated-focused.log`). The
five standalone checks also passed: `tests/test-tool.sh`,
`tests/test-profile-tool.sh`, `tests/test-capture-validator.sh` at `e3e156c04`,
then `tests/test-jni-proof.sh --nuked-source "$NUKED_SOURCE"` and
`tests/test-fast-capture.sh --ymfm-source "$YMFM_SOURCE"` at `532e3d1af`, all
under `tools/audio/fm-core-benchmark/`. Their verified upstream source trees
are external; the latter runs verify the pinned compiled inputs. No integrated
check collects timing. Logs are under `target/fm-core-integrated-test-logs/`.

The external `performance-evidence/manifest.json` preserves 38 original
research/profile/results/log payloads with manifest SHA-256
`225d9db1511a2d5a7f37676bc8d0a10db635f9a5371af97c977a222ba183d6f2`.
Every copied payload was compared byte-for-byte and rehashed. No native
binary, Java class, upstream source, SDK, ROM or generated audio is in this
evidence archive; audition material has its own separate manifest.

## Delivery and retained evidence

Only `develop` was pushed. The final merge was clean; the earlier coordination
changelog conflict retained both entries and the independent guidance update
retained both README summaries. Main's user-modified disassembly submodules
and all pre-existing unrelated worktrees remain untouched.

Removed this milestone's `audio-next-{psg,dac,performance,baseline,coordination}`
worktrees after verifying clean tracked/untracked state and merged ancestry.
Ignored files were classified: generated Maven output, generated rewind/image
cache reports, hook-created ROM/reference links, and config copies identical
to surviving main files. Internal review sidecars and useful results were
archived before removal. Commit history remains recoverable from develop;
discarded build products are regenerable.

The external task archive is `audio-next-20260905`. Besides the audition and
performance manifests above, verified archives include:

- `evidence/baseline.tar.gz` — original and updated isolated baselines;
- `evidence/coordination-premerge.tar.gz` — focused, ordinary, guard and
  negative-test evidence;
- `evidence/merged-verification.tar.gz` — original post-merge runs,
  comparisons, six-method supplement and report-gap closure, SHA-256
  `6993f3ea23c7e1f8fe41b0c468c7faca29105114e89519fdac29dfb7277b0404`;
- `evidence/final-review-sidecars.tar.gz` — independent reviews including
  the closed reporting-gap audit;
- `evidence/{dac,psg}/` — individually hashed original investigation outputs.

The invalid main-baseline attempts are separately retained and explicitly
excluded from regression evidence. No ROM or compiled native backend was
committed or included in the evidence archives.

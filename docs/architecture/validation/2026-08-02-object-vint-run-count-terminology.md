# Object V-int run-count terminology validation

**Date:** 2026-08-02
**Branch:** `feature/ai-object-vint-terminology`
**Baseline:** `develop` at `fe9c2a596`

## Baseline

Clean JDK 21 default suite with all three ROMs:

- passing: 13,989
- failing: 26
- errors: 7
- skipped: 31
- report archive: `/tmp/object-vint-clean-baseline-surefire-reports`

The baseline is red independently of this terminology refactor. Delivery requires testcase
identity comparison and no new or attributable regression.

An earlier non-clean run reported 14,083 passing and 30 failing because its Surefire report
directory still contained excluded trace-replay XML. The clean comparison supersedes those
aggregate numbers.

## TDD red

Command:

```bash
mvn -Dtest=TestObjectUpdateClockTerminologyGuard,TestObjectScaffoldTool test
```

Result before the rewrite: 20 passed, 2 failed. The new attributed guard reported 967
violations across the object boundary, framework hooks/overrides, and retained boss fields.
The scaffold test independently failed on the generated non-badnik signature.

## Attributed rewrite audit

The temporary JDK-compiler refactor parsed and attributed both source roots with Maven's
test classpath. It used resolved `VariableElement` and `ExecutableElement` identities,
`Elements.overrides`, all project-source call sites for private helper formals, and
source-position replacements.

Audit-only result:

| Category | Count |
|---|---:|
| Exact `update(int, PlayableEntity)` roots | 809 |
| Framework hook declarations/overrides | 154 |
| Private helper parameters with unanimous identity V-int flow | 219 |
| Identity-only local aliases | 1 |
| Proven retained fields | 25 |
| Permitted test-only literal retained-clock seeds | 8 |
| Renamed symbols | 1,208 |
| Attributed source-position replacements | 1,849 |
| Inventoried reflective-string replacements | 8 |
| Final changed production files | 667 |
| Final changed test files | 51 |
| Initially excluded mixed/non-identity candidates | 87 |

All required seeds and overrides resolved. No mixed or unresolved candidate was proposed
for automatic editing.

### Exclusion classification

Eleven private formals have genuinely mixed identity sources and remain excluded. They are
called from both the V-int object update path and another clock-bearing callback or value:

- `Sonic1PushBlockObjectInstance.handlePush` — 1/2 V-int-derived calls
- `Sonic1SpikeObjectInstance.handleSolidContact` — 1/2
- `OOZLauncherObjectInstance.updateInvisibleLauncher` — 1/2
- `SpiralObjectInstance.engagePlayer` — 1/2
- `SpiralObjectInstance.tryActivateCylinder` — 1/2
- `CnzCannonInstance.advanceSpin` — 1/2
- `CnzCannonInstance.launchPlayer` — 1/2
- `GumballItemObjectInstance.handleGumballReward` — 1/3
- `MGZPulleyObjectInstance.releasePlayer` — 2/3
- `MhzMushroomCapObjectInstance.updatePosition` — 1/2
- `PachinkoMagnetOrbObjectInstance.releasePlayer` — 3/4

The other 76 excluded formals receive no identity-preserving V-int input. They are mapping
frames, animation frames, durations, fragment frame indexes, genuine callback frame
counters, arithmetic expressions, or other non-clock integers and remain unchanged.

`AbstractResultsScreen.frameCounter` is also intentionally unchanged. Its object update
path supplies V-int, but the separate `ResultsScreen.update(int, Object)` bridge supplies
frames since the results screen started, so the retained field is mixed-source. The guard
does not falsely label it as V-int.

The terminology pass exposed a pre-existing comment in `GumballMachineObjectInstance`
which claimed the update argument was not `V_int_run_count`. The comment is corrected, but
the established RNG ownership/reseed behavior is unchanged; any ROM-parity change there
requires a separate measured investigation.

## Post-rewrite verification

### Independent-review amendment

The first implementation review found a blocking completeness gap in the retained-state
pass: the initial audit renamed six fields but had not swept every field written from the
renamed V-int parameters. `Sonic2EHZBossInstance.currentFrameCounter`, for example, had one
write from `vIntRunCount` and used that retained value for an eight-tick gate. The design
and plan were amended to require a complete attributed all-source field/local inventory.

The follow-up attributed pass found 18 additional fields whose complete production write
set retains the V-int symbol, and a source-access sweep found one more field populated from
`ObjectManager.getVblaCounter()`: `Sonic1EggPrisonObjectInstance.buttonTriggerFrame`.
Together the supplemental passes renamed 19 field symbols with 69 attributed replacements
across 15 production files. Eight inventoried reflection strings in six tests followed the
same fields. The durable guard now pins all 25 retained field names plus the one proven
identity-only LZ boss local.

Five lookalikes remain deliberately excluded:

- `AbstractResultsScreen.frameCounter` receives V-int from object dispatch and results age
  through its separate `ResultsScreen` bridge.
- `ClamerObjectInstance.lastObservedFrameCounter` receives V-int and the touch callback's
  counter.
- `S3kSlotRingRewardObjectInstance.lastFrameCounter` receives object-manager V-int and the
  slot runtime's counter.
- `Sonic1RingInstance.gameplayFrameCounter` is initialized from V-int but overwritten by
  the executed-object frame counter when the manager is available.
- `AizPlaneIntroInstance.lastFrameCounter` has a production literal-zero write in addition
  to V-int, so it fails the declared production-literal rule and remains unrenamed.

### Compilation and terminology guards

```bash
mvn -DskipTests package
mvn -Dtest=TestObjectUpdateClockTerminologyGuard,TestObjectScaffoldTool test
```

The skipped-test package build passed. The attributed terminology guard and all scaffold tests passed: 22
tests, zero failures and zero errors. The guard attributed every production and test source
file and found no noncanonical object-update boundary, hook override, or inventoried
retained-clock field.

The rewind-sensitive focused set passed 137 tests. Its only two failures were the same
pre-existing `TestArchitecturalSourceGuard` failures present on the baseline; all selected
rewind and boss-clock tests passed.

### Clean full-suite comparison

Both commands used `mvn clean test` on JDK 21 with the discovered S1, S2, and S3K ROM paths.

| Run | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|
| Clean `develop` baseline | 13,989 | 26 | 7 | 31 |
| Clean final feature worktree | 13,991 | 26 | 7 | 31 |

The final clean feature run has exactly the same 33 failing/erroring testcase identities as
the clean baseline, with two additional passing tests and no new failure or error. An
earlier feature run transiently failed
`TestBubblerObjectInstance.makerBeginsFirstProductionDispatchBeforeRenderVisibilityRefresh`;
all five Bubbler tests passed immediately in isolation, and the failure did not recur in the
final clean comparison.

A final focused run of the attributed guard, all scaffold tests, and the Bubbler regression
passed 27 tests with zero failures and zero errors.

After the retained-field amendment, the expanded focused set covered the attributed guard,
all scaffold cases, CNZ boss reflection fixtures, the AIZ2 capsule fixture, and the MGZ
mechanism rewind scalar list. All 78 tests passed with zero failures and zero errors.

# Object V-int run-count terminology implementation plan

> **Execution note:** implement in the isolated
> `feature/ai-object-vint-terminology` worktree. Use the JDK compiler API for source
> selection and binding; do not perform a repository-wide textual replacement.

**Goal:** Rename the object execution clock to `vIntRunCount` across the object boundary,
its proven forwarding dataflow, generated scaffolds, and retained boss state without
changing runtime behavior.

**Design:** [Object V-int run-count terminology](../designs/2026-08-02-object-vint-run-count-terminology.md)

**Baseline:** clean, unchanged `develop` at `fe9c2a596` ran 13,989 passing, 26 failing,
7 errors, and 31 skipped on JDK 21 with all three ROMs. The complete clean Surefire report
set is retained at `/tmp/object-vint-clean-baseline-surefire-reports` for post-change
identity comparison. This clean run supersedes an earlier report directory polluted by
stale XML from excluded trace-replay tests.

## Task 1: Add the red terminology guard

**Files:**

- Create `src/test/java/com/openggf/level/objects/TestObjectUpdateClockTerminologyGuard.java`
- Modify `src/test/java/com/openggf/tools/TestObjectScaffoldTool.java`

1. Implement a JUnit 5 guard using `JavaCompiler`, `JavacTask`, `Trees`, and `Elements`.
   Parse and attribute main/test sources with the current Surefire classpath.
2. Require every exact `update(int, PlayableEntity)` first parameter to be named
   `vIntRunCount`.
3. Resolve the declared framework seed methods and use `Elements.overrides` to require the
   same name on their overrides:
   - `AbstractBadnikInstance.updateMovement` and `updateAnimation`;
   - `AbstractProjectileInstance.updateExtra`;
   - `AbstractBossInstance.updateBossLogic` and `updateOwnerManagedChildren`;
   - `AbstractBossChild.beginUpdate` and `shouldUpdate`; and
   - `AbstractBossInstance.BossDefeatSequencer.update`.
4. Require the canonical names for the complete attributed retained-clock inventory and
   reject every old declaration. Seed the two boss fields and the four initially audited
   object fields, then extend this inventory with every field/local discovered by the
   all-source audit in Task 2; do not limit the guard to the initial six.
5. Add scaffold assertions for all five generated clock signatures: the non-badnik
   `update`, S1/S2 badnik `updateMovement` and `updateAnimation`, and S3K badnik
   `updateMovement` and `updateAnimation`. Require `vIntRunCount` in each.
6. Run:

   ```bash
   mvn -Dtest=TestObjectUpdateClockTerminologyGuard,TestObjectScaffoldTool test
   ```

   Expected RED: the guard inventories 809 old object-update declarations plus the old
   framework hooks/fields, and the scaffold assertion sees `frameCounter`.

## Task 2: Build and audit the attributed rewrite

**Files:**

- Create temporary ignored
  `target/refactor/ObjectVIntTerminologyRefactor.java` with `apply_patch`
- Create `docs/architecture/validation/2026-08-02-object-vint-run-count-terminology.md`

1. Compile main/test sources and materialize the Maven dependency classpath:

   ```bash
   mvn -DskipTests test-compile dependency:build-classpath \
     -Dmdep.outputFile=/tmp/object-vint-dependencies.classpath
   ```

2. Implement the temporary refactor with javac attribution over both source roots.
   Record every `VariableElement`, `ExecutableElement`, override relation, invocation
   target, argument position, and field write before editing.
3. Seed the exact object-update parameters and the framework families from Task 1.
4. Compute an identity-preserving provenance fixpoint for private helper formals. Rename a
   formal only when every resolved project-source invocation supplies a currently
   V-int-derived symbol in that position. Do not treat arithmetic expressions as the same
   value; inventory them for manual naming.
5. Audit every field and local reached by an identity V-int write, not only the initial six
   explicit retained fields. Rename each after verifying every production
   non-initialization write is V-int-derived; accept `-1` only as the unset sentinel.
   Permit and inventory test-only literal assignments that explicitly arrange this retained
   V-int clock for a fixture. Reject any production literal assignment. Propagate into a
   local alias only when its initializer and every later assignment preserve V-int
   identity; inventory and exclude mixed locals.
   Keep `AbstractResultsScreen.frameCounter`: its `ResultsScreen.update(int, Object)` bridge
   supplies a distinct results-age counter, making that field intentionally mixed-source.
6. Collect source-position replacements from symbol-bound declarations and references,
   sort them descending per file, reject overlaps, and preserve each file's existing line
   endings.
7. Run the tool in audit-only mode. Record counts for root seeds, hook seeds/overrides,
   safely propagated helpers and locals, retained fields, mixed-source formals/locals,
   unresolved targets,
   permitted test fixture clock seeds, and edits by source root in the validation document.
8. Treat mixed-source candidates as a valid excluded outcome: inventory them and make no
   automatic edit. Stop and amend the design/plan only if attribution cannot resolve a
   required seed/override or the tool proposes renaming a mixed or unresolved candidate.
   Do not downgrade to regex replacement.

## Task 3: Apply the programmatic rename

**Files:**

- Modify the attributed set under `src/main/java` and `src/test/java`
- Modify `src/main/java/com/openggf/tools/ObjectScaffoldTool.java`
- Modify Javadocs/comments attached to the renamed boundary and hooks

1. Run the attributed tool in apply mode and capture its final audit.
2. Update all five scaffold string literals to emit `vIntRunCount`: the non-badnik
   `update` plus the S1/S2 and S3K badnik `updateMovement`/`updateAnimation` pairs.
3. Update only comments and Javadocs whose subject is the renamed object clock. Replace
   inaccurate “global frame counter” text with “object-visible V-int run count.”
4. Classify any ambiguity inventory manually. A helper may be renamed only after all call
   sites prove the same provenance; otherwise give it a more precise local name or leave a
   genuine `frameCounter` intact.
5. Delete the temporary refactor source/classes from `target/refactor/` after its audit is
   recorded.
6. Re-run the parser inventory and require:
   - 809 exact object-update declarations named `vIntRunCount`;
   - zero exact object-update declarations using another name; and
   - zero misleading retained-field/local names in the complete proven V-int inventory;
   - zero unexpected edits outside the attributed files and planned docs.

## Task 4: Make the tests green and verify retained state

**Files:**

- Modify tests only where compilation or canonical terminology requires it

1. Compile and run the red tests again:

   ```bash
   mvn -Dtest=TestObjectUpdateClockTerminologyGuard,TestObjectScaffoldTool test
   ```

   Expected GREEN.
2. Run focused object-framework and rewind verification:

   ```bash
   mvn -Dtest=TestArchitecturalSourceGuard,TestRewindCoverageGuard,TestRewindSchemaRegistry,BossStateContextTest,TestBossChildExactStateRewind,TestBossChildNoDoubleSpawnParity,TestEHZBossWheelOrphanAfterSiblingDestroy test
   ```

3. Run `mvn -DskipTests package` to compile all production and test-independent tooling.
4. Inspect `git diff --check`, changed-file counts, and representative diffs from shared,
   S1, S2, S3K, nested classes, and tests. Confirm every executable diff is identifier-only.

## Task 5: Publish terminology guidance

**Files:**

- Modify `AGENTS.md`
- Modify `CLAUDE.md`
- Modify `README.md`
- Modify `docs/architecture/designs/trace/2026-08-02-handover-followups-design.md`
- Complete `docs/architecture/validation/2026-08-02-object-vint-run-count-terminology.md`

1. Add the same gotcha to both agent guides: object
   `update(int vIntRunCount, ...)` receives `V_int_run_count`; it is distinct from the
   manager/level executed-frame clock and can de-phase on lag frames.
2. Keep `AGENTS.md` and `CLAUDE.md` byte-identical in the mirrored guidance region.
3. Add a concise current-development release bullet to `README.md`.
4. Preserve the handover document's historical deferral and add only its supersession
   pointer.
5. Finalize the validation report with the exact rewrite inventory, ambiguity disposition,
   focused-test results, and full-suite comparison.

## Task 6: Full verification and independent review

1. Run the full suite with JDK 21 and all three ROM properties.
2. Compare every failing/erroring testcase with
   `/tmp/object-vint-clean-baseline-surefire-reports`; require no attributable new testcase
   and no attributable change in a baseline failure. Re-run any new failure in isolation
   before classifying a suite-order/shared-state flake.
3. Discard only the generated `docs/status/rewind-round-trip-gaps.md` change authorized by
   the user.
4. Request an independent code review covering provenance safety, completeness, accidental
   non-object renames, guard quality, docs mirroring, and test evidence. Fix every valid
   issue and repeat review until green.

## Task 7: Commit, integrate, and clean up

1. Fetch `origin`, reconcile the feature branch with the latest `develop`, and rerun the
   focused tests if conflicts touch renamed files.
2. Commit with required policy trailers. Stage all design, plan, and validation artifacts;
   set `Agent-Docs: updated` because both mirrored guides change.
3. In the main workspace, fast-forward pull `develop`, run the updated-baseline suite if
   upstream changed, and merge `feature/ai-object-vint-terminology` without switching the
   main branch.
4. Run the full suite on merged `develop` and compare it with the applicable baseline.
5. Push only `develop`.
6. Verify the worktree has no unmerged/user-authored changes, remove it, delete the fully
   merged local feature branch, and prune worktree metadata.

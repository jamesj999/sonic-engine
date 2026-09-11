# OpenGGF 0.6 release blocker assessment

Assessed 2026-09-06 at `develop` **b3ab55ca9cb76f37b872e840737c8f9b3e3ffb8e**.
Scope: identify blockers; no gameplay, audio, CI or release-policy changes.
Brainpipe problem **P4** records the independent-review request and evidence.

## Decision

0.6 is not ready for sign-off. The actionable queue is release-gate alignment,
one frozen candidate's complete automated/platform evidence, and human gameplay
and listening approval. Making every known-red trace or audio oracle green is
not the release criterion.

The [August 26 closure decision](../../status/trace-scope-release-6.md#2026-08-26-release-closure-decision)
supersedes older parity-completion plans: preserve passing traces, compare known
failures by identity and frontier, retain all integrity/authority guards, and fix
confirmed release-impacting real-play defects. S1/S2 Sonic routes and the primary
S3K AIZ → HCZ slice need gameplay evidence; broader S3 trace scope does not make
every S3K route a shipped completeness claim.

## Blockers and exit criteria

| Priority | Finding | Evidence | Exit criterion |
|---|---|---|---|
| 1 | **Release skip classification rejects available ordinary-suite evidence.** | The unmodified Python checker in [release.yml](../../../.github/workflows/release.yml), step `Assert default test skip classification`, exits **1**, rejecting **27 of 43 distinct skipped identities** from the completed `f56d4fae1` ordinary run. Its allowlist contains 19 entries, 16 present in that run. | Required tests execute with the intended ROMs, display and fixtures; optional diagnostic/benchmark skips are explicitly classified. Rerun the actual checker on the candidate's own reports. Do not broadly allow missing required evidence. |
| 1 | **Executable trace gate does not implement the agreed no-regression policy.** | `Run trace replay policy tests` runs `mvn -Dmse=off test -Ptrace-replay -B` with normal failure semantics; neither the workflow nor POM provides a baseline comparison. `Assert trace replay coverage` rejects every warning count above zero. Dependent build jobs require test-job success. Recent completed trace evidence still contains seven known failures. | Run and retain full candidate and baseline evidence, compare test identity/failure/frontier and warnings under explicit policy, reject new/worsened failures and missing reports, and preserve mandatory guards. A blanket failure-ignore switch is insufficient. |
| 2 | **No complete final-candidate evidence bundle has been established.** | Recent ordinary/guard delivery evidence is green, but the later audio delivery did not establish a fresh full release trace comparison and all platform distributions for that same candidate. | Freeze a commit; produce package, ordinary suite, a separate fresh guard JVM, trace no-regression comparison, classified skips, and Windows/macOS/Linux distribution and universal-JAR validation. Attribute every completed run and artifact to that commit. |
| 2 | **Human gameplay sign-off remains open.** | [Release status](../../changelog/v0.6-release-summary.md#known-limitations-and-release-status) explicitly requires end-to-end QA. No completed candidate gameplay approval was found in the reviewed records. | Record tester, candidate, ROM revision, platform, supported route and result; exercise transitions, death/time-over/Continue, restart and return-to-title, as well as route completion. Fix or explicitly disposition release-impacting defects. |
| 2 | **Human audio sign-off remains open.** | The [SMPS listening checklist](../validation/audio/2026-08-21-smps-playback-listening-checklist.md) has unchecked rows and overall **PENDING**. Automated register/state checks do not establish listening approval. | On the release build, record listener/date/reference/output and PASS or a concrete KNOWN DIFFERENCE with a linked bounded follow-up for every applicable row, including overlapping SFX, pause, fades, speed shoes and repeated 1-up restoration. |

The skip reproduction uses saved evidence, not a new release-runner execution.
Many of the 27 skips depend on local GL availability or optional audio inputs;
they must not all be called inevitable CI failures. One definite workflow-default
mismatch is
`TestLiveRewindCheckpointCost#compareCheckpointCadencesOnTheSameRecordedRoute`:
[its class](../../../src/test/java/com/openggf/game/rewind/TestLiveRewindCheckpointCost.java)
requires `openggf.checkpoint.measure=true`, which the workflow does not supply,
and its identity is absent from the skip allowlist. The ordinary profile includes
it. The 27 comprise that benchmark, 20 GL/visual cases, five S2 audio-oracle
cases and one S3K observation-sidecar case.

Trace rejection is established by the workflow's control flow and the documented
completed red trace results, not by running a new GitHub release job. An unchanged
seven-failure result would still stop that job under its current commands.
Warning policy needs an explicit reconciliation with no-regression policy;
this assessment does not authorize suppressing or downgrading comparisons.

## Evidence already available

The [SMPS delivery group](../validation/audio/2026-09-05-sol-smps-parity-delivery-group.md)
records completed combined verification at **f56d4fae1**:

- `mvn -Dmse=off` with all three absolute ROM properties, `test -B`:
  **16,687 executions, 0 failures, 0 errors, 43 skips**; finished
  2026-09-05 22:43:36 BST.
- Separate `mvn -Dmse=off -Pguards` with the same ROM properties, `test -B`:
  **609 tests, 0 failures/errors/skips**.
- The focused evidence checks: **24 tests, 0 failures/errors/skips**.

This assessment read the preserved `commands.txt`, completed logs and ordinary
XML under main-workspace `target/audio-parity-delivery-postmerge-evidence/`.
These are historical results attributed to their source commit, not newly run
suites or proof that this assessment's documentation commit passed a full suite.
The delivery ledger retains archive identity and outcome-comparison provenance.
`git diff f56d4fae1 b3ab55ca9` contains documentation changes only; production
and test sources have not changed between that completed run and the assessed
head. This strengthens the ordinary/guard evidence without supplying the missing
trace, platform or human checks.

The [Continue validation](../validation/2026-09-05-continue-screens.md) records a
candidate trace profile with **852 executions, seven existing failures and six
existing skips**, unchanged in failure messages and first divergence from its
updated base. This supports a known-red baseline, not current final sign-off.

Read-only GitHub inspection with
`gh run list --workflow release.yml --limit 5 --json databaseId,headSha,headBranch,status,conclusion,createdAt,url`
returned July 24 as the newest release-workflow run: successful
[run 30085626780](https://github.com/OpenGGF/OpenGGF/actions/runs/30085626780),
head `ad40b6a3b024cd1114e40f989b2a5e522ea0cc89`, on a dependency-update branch.
The latest returned `master` success was June 30, head `99b3f481023ef06b9394f06f4f2f3cc4459a4820`.
Those historical green jobs do not certify the September candidate or its
current workflow. This assessment did not dispatch a release job.

Reproduction performed here: extract the original embedded Python block from
`Assert default test skip classification` in `release.yml`; run it in a temporary
directory whose `target/surefire-reports` points to the preserved ordinary XML.
Exit **1** lists 27 rejected identities. No test report, ROM, or workflow was
modified. The local transcript is retained outside the repository at
`${EVIDENCE_ROOT}/brainpipe-release-0.6/release-skip-check.log`, where
`EVIDENCE_ROOT` is the external task scratch directory for this session.

## Triage, rather than automatic release blockers

- Known trace/run-chain parity frontiers remain visible limitations unless they
  worsen or demonstrate a real-play release defect. No trace sweep was run here
  and no frontier was selected or changed.
- Complete SMPS/full-game audio parity remains unfinished; the latest
  [coverage report](../validation/audio/2026-09-05-complete-run-coverage-summary.md)
  explicitly avoids claiming full parity. This does not replace listening QA or
  make every bounded oracle mismatch a mandatory 0.6 repair.
- S&K-half and Knuckles completeness, the dormant editor and a modding framework
  are outside the promised release scope.
- The [known-bug ledger](../../status/known-bugs.md) lists narrow Game Over/Continue
  differences and three unreproduced March AIZ observations. Validate their
  real-play impact before promoting them to blockers. Its legacy schema-v3
  recorder section conflicts with the current V5-only contract and is stale
  documentation, not evidence that legacy trace hydration is currently active.

## Review and limits

CS recorded an initial position before collecting peer results. P4 was submitted
at epoch **1788649428**; final collection at **1788650037** found no BG receipt
or substantive response after 609 seconds. Receipt and answer times are unknown;
mailbox storage does not establish receipt. P4 was closed **unresolved** with the
CS findings preserved. This is **CS's assessment, not department consensus**.
Confidence is high in the concrete workflow mismatch and the explicitly pending
approvals; runtime/platform findings remain bounded by the supplied evidence.

The brainpipe repair itself was integrated locally at `cb0bd9f`, with installed
verification recorded at `d12f373`. It resolves physical paths before walking
to the source checkout and installs the missing Codex user-level link. The six
adapter scenarios and all six isolated brainpipe suites passed on the integrated
repair; baseline and development suites also passed. The task worktree and branch
were removed. Brainpipe has no remote, so that repair could not be pushed.
Native skill discovery in a fresh Codex session remains unverified; the installed
adapter was exercised from OpenGGF. The optional skill validator was unavailable
because PyYAML is missing; frontmatter, source links and shell syntax were checked
directly.

No new OpenGGF Maven suite, release workflow, platform binary launch, gameplay
session or listening session was performed for this assessment. The checks here
cover source/evidence consistency and documentation, not release certification.

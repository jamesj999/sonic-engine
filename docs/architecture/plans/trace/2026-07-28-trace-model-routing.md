# Trace Model Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILLS: Use superpowers:writing-skills for the skill pressure tests and edits, plus superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add enforceable Sol/Terra routing, bounded escalation, and measurable token-efficiency experiments to the trace bug-fixing fleet.

**Architecture:** Keep the existing Discover → Triage → Fix → Verify pipeline and parity gates. Extend its structured handoffs with conductor-owned routing metadata, choose exact child model/effort before each spawn, and use a pinned manual benchmark manifest because Codex does not expose a repository-native fleet runner or guaranteed token counters.

**Tech Stack:** Markdown agent skills and runbooks, JSON benchmark artifacts, Git worktrees, Codex `spawn_agent`.

## Global Constraints

- The active conductor model and effort cannot be changed by a running skill.
- Child workers use only currently supported `gpt-5.6-terra` and `gpt-5.6-sol` model identifiers.
- Trace comparison-only, ROM-citation, no-carve-out, cross-game, regression, worktree, and commit gates remain unchanged.
- `.agents/skills/trace-green-fleet/SKILL.md` and `.claude/skills/trace-green-fleet/SKILL.md` retain their harness-specific prose while expressing identical routing semantics.
- Benchmark worktrees are retained by default and are never force-removed without explicit destructive approval.

---

### Task 1: Encode model routing in the trace fleet skills

**Files:**
- Modify: `.agents/skills/trace-green-fleet/SKILL.md`
- Modify: `.claude/skills/trace-green-fleet/SKILL.md`
- Create: `docs/architecture/validation/trace/trace-model-routing-pressure-tests.json`

**Interfaces:**
- Consumes: existing failing, triage, fix, and verify stage objects.
- Produces: exact worker model/effort choices; the common routing object; deterministic Triage, Fix, and Verify escalation decisions.

- [ ] **Step 1: Capture the RED pressure scenario**

Save and run this exact case from `trace-model-routing-pressure-tests.json` against
the feature branch's base revision of the skill:

```json
{
  "id": "stalled-narrow-and-shared-direct-route",
  "workerModel": "gpt-5.6-terra",
  "workerEffort": "medium",
  "input": {
    "narrowTriage": {
      "complexity": "narrow",
      "confidence": "high",
      "hypothesis": "Obj15 applies ROM x_pos contact logic",
      "disasmCites": ["docs/s2disasm/s2.asm:123"]
    },
    "narrowAttempts": [
      {"attempt": 1, "beforeFrame": 100, "afterFrame": 100},
      {"attempt": 2, "beforeFrame": 100, "afterFrame": 100}
    ],
    "sharedTriage": {
      "complexity": "shared",
      "confidence": "high",
      "sharedSurfaces": ["shared-sidekick-runtime"],
      "hypothesis": "position history consumption differs",
      "disasmCites": ["docs/s1disasm/_incObj/09 Sonic & Object Interaction.asm:10"]
    }
  }
}
```

The prompt asks for exact discovery, triage, fix, and verification model IDs/efforts,
the escalation sequence, and all routing/usage fields. Save the raw RED response in
the JSON artifact. RED passes when any route, escalation rule, or common routing
field is unspecified.

Use this byte-for-byte prompt template, replacing only the two marked JSON values:

```text
Read the trace fleet skill at <SKILL_PATH> from revision <SKILL_REVISION>.
Do not read any design or plan documents. Do not edit files.

Scenario input:
<CASE_INPUT_JSON>

Return one JSON object only with:
- discovery, triage, narrowFix, sharedFix, ordinaryVerify, sharedVerify as arrays
  of exact "<model-id>/<effort>" routes;
- narrowEscalatesAfterAttempt as an integer or null;
- escalationSequence as an array of stage/reason/ownership objects;
- routingFields and usageFields as arrays of exact field names;
- unspecified as an array describing anything the skill does not define.
```

Spawn the pressure worker with `fork_turns="none"` as
`gpt-5.6-terra`/medium with no forked conversation context. Set `<SKILL_PATH>` to the absolute path under this worktree,
`<SKILL_REVISION>` to `git rev-parse HEAD` for RED and the literal
`working-tree-after-routing-edit` for GREEN, and `<CASE_INPUT_JSON>` to the saved
case's `input` object serialized by `jq -c`. Copy the worker's raw final output
verbatim into `red.rawResponse` or `green.rawResponse`; do not paraphrase it.

- [ ] **Step 2: Add the routing policy and supported identifiers**

Document the exact defaults:

```text
Discovery: gpt-5.6-terra/low
Triage: gpt-5.6-terra/medium
Narrow Fix: gpt-5.6-terra/medium
Shared/deep Fix: gpt-5.6-sol/high
Ordinary Verify: gpt-5.6-terra/medium
Shared, Sol-fixed, disputed, or escalated Verify: gpt-5.6-sol/high
```

State that Sol/medium is recommended for fleet launch but the active conductor cannot reroute itself.

- [ ] **Step 3: Extend structured stage contracts**

Add the common routing fields and enums from the design to Discovery, Triage, Fix,
and Verify examples. Every applicable stage also records `beforeFrame`,
`afterFrame`, `status`, cumulative edit `attemptCount`, and `regressionCount`.
Discovery, Triage, and Verify use zero attempts; totals count only Fix edit/test
attempts.
Specify conductor ownership of `modelRoute`, nullable runtime telemetry, and one
schema-only repair followed by at most one rerun; a second malformed result is a
stage error.

Extend the final fleet JSON with:

```json
{
  "routing": {
    "stages": [],
    "totalUsage": {
      "inputTokens": null,
      "cachedInputTokens": null,
      "outputTokens": null,
      "reasoningTokens": null
    },
    "totalDurationMs": null,
    "acceptedResults": 0,
    "tracesGreened": 0,
    "escalations": 0
  }
}
```

Aggregate token/duration values only when every contributing value is exposed;
otherwise the aggregate is `null`.

- [ ] **Step 4: Add deterministic pre-spawn and escalation rules**

Require:

```text
Terra Triage -> Sol Triage for multiple owners, low confidence,
unresolved ownership, or missing ROM basis.
Accepted shared/deep Triage -> Sol Fix directly.
Terra Fix -> stop after two unsuccessful attempts -> Sol Fix for one final attempt.
Terra Verify -> Sol Verify after detecting a regression.
```

Define sequential worktree ownership and retained-diff review exactly as the design specifies.
Add an explicit decision table covering:

- unsupported model ID: reject before spawn;
- unavailable Sol: block the escalated stage, never fall back silently;
- at most one escalation per stage;
- Sol Triage still missing a ROM basis: block Fix;
- Sol worker failure: return a blocker;
- direct Sol Fix for shared runtime physics, collision, sidekick, camera,
  oscillation, bootstrap, object lifecycle, recorder/publication contracts, or
  cross-game semantics;
- object-local collision/profile changes remain narrow;
- Terra Fix escalation for no frontier advance, context contradiction, newly
  discovered shared surface/cross-game semantics, recorder evidence required,
  causal-thread loss, or insufficient reasoning;
- direct Sol Verify after any Sol Fix, shared edit, or disputed ROM evidence;
- Terra Verify escalation after newly detected regression.

- [ ] **Step 5: Update worker prompt templates**

Put the invariant rules in a stable prefix, dynamic JSON afterward, and instruct workers to start with compact evidence but expand context when causality requires it. Include exact Codex spawn overrides with `fork_turns="none"` in the Codex skill; describe equivalent tier intent without claiming unsupported overrides in the Claude skill. Discovery, when needed, is a real fresh Terra-low child.

- [ ] **Step 6: Run the GREEN pressure scenario**

Run the same saved prompt with `gpt-5.6-terra`/medium. Expected assertions stored
beside the GREEN response:

```json
{
  "discovery": "gpt-5.6-terra/low",
  "triage": "gpt-5.6-terra/medium",
  "narrowFix": [
    "gpt-5.6-terra/medium",
    "gpt-5.6-sol/high"
  ],
  "sharedFix": ["gpt-5.6-sol/high"],
  "ordinaryVerify": ["gpt-5.6-terra/medium"],
  "sharedVerify": ["gpt-5.6-sol/high"],
  "narrowEscalatesAfterAttempt": 2
}
```

Add saved edge-case rows asserting every decision-table entry from Step 4,
including malformed-result repair and Sol-triage blocker behavior. GREEN passes
only when all exact route assertions and required routing keys match.

- [ ] **Step 7: Verify mirror semantics**

Run:

```bash
git diff --no-index .agents/skills/trace-green-fleet/SKILL.md .claude/skills/trace-green-fleet/SKILL.md
```

Expected: only known harness-specific wording differs; routing tables, enums, schemas, and decision rules are semantically identical.

### Task 2: Document operation and reproducible measurement

**Files:**
- Modify: `docs/agent-workflow/runbooks/runbook-multi-agent-trace-orchestration.md`
- Modify: `docs/agent-workflow/trace-green-fleet-decisions.md`
- Create: `docs/architecture/validation/trace/trace-model-routing-benchmark.json`
- Create: `docs/architecture/validation/trace/trace-model-routing-benchmark.schema.json`
- Create: `docs/architecture/validation/trace/trace-model-routing-result.schema.json`
- Create: `docs/architecture/validation/trace/trace-model-routing-result.template.json`

**Interfaces:**
- Consumes: the routing contract from Task 1 and historical trace-frontier evidence.
- Produces: operator-facing route/escalation instructions and a pinned, machine-readable benchmark protocol.

- [ ] **Step 1: Add the operator routing table and escalation lifecycle**

Update the runbook with exact model IDs/efforts, direct Sol classification, Terra stall escalation, verification escalation, and the rule that the lead keeps sole worktree ownership between sequential workers.

- [ ] **Step 2: Record the routing decision**

Add a dated entry to the fleet decision history covering:

- Terra-first rather than universal Luna-first;
- Sol for shared/deep work and escalation;
- why Luna remains future-only;
- why tokens per accepted result and per trace greened are primary;
- why frontier movement is only a secondary within-trace metric.

- [ ] **Step 3: Create the frozen benchmark manifest**

Define the policies `sol-only`, `terra-sol`, and disabled-until-supported
`luna-terra-sol`. Define at least one case from each game and a mix of
narrow/shared/deep historical roots. Select each case by finding a ROM-backed
`fix(trace)` commit, checking out its first parent in a temporary clean worktree,
running the named trace to confirm the recorded starting frontier, and hashing its
fixture files. Reject any candidate whose parent does not reproduce the logged
frontier. Each accepted case contains:

```json
{
  "id": "stable-case-id",
  "baseCommit": "40-character commit",
  "testClass": "fully qualified class",
  "game": "s1|s2|s3k",
  "zone": "token",
  "complexity": "narrow|shared|deep",
  "startingFrontier": {"frame": 123, "field": "x_pos"},
  "fixtures": [{"path": "repo-relative path", "sha256": "64 hex"}],
  "rom": {"property": "s1.rom.path", "sha1": "expected hash"},
  "targetCommand": "exact Maven command with <ROM_PATH>",
  "verificationClasses": [
    "com.openggf.tests.trace.s2.TestS2Ehz1TraceReplay"
  ],
  "historicalFailureConfirmed": true
}
```

Every class is fully qualified and executable. S1/S2 use known same-game green
traces beyond the target; S3K uses the exact four fallback guards, and the
lifecycle records every outcome. Commands use `<ROM_PATH>` only for
the user-supplied ROM location. Store results as
`target/trace-model-routing/<policy>/<case>.json` and patches as the same path with
`.patch`; record both SHA-256 values in the result. Pin only cases whose base commit,
frontier, fixture hashes, and ROM expectation can be verified from repository
history.

- [ ] **Step 4: Create the result schema**

Create a Draft 2020-12 manifest schema that requires unique case IDs (enforced by
the validation command), 40-hex commits, 64-hex SHA-256 fixture hashes, ROM
property/SHA-1, exact commands, fully qualified verification classes, all three
games, historical confirmation, and policy route definitions.

Create a Draft 2020-12 result schema requiring policy name, case ID,
base/result-tree hashes, patch path/hash, stage routes, nullable token categories,
wall time, Fix-only attempts, before/after frontier, fleet status, independent
accepted/genuine/reviewer-rejected fields, structured ROM citations, exact
verification outcomes, and structured regressions.
Create a concrete result template populated with schema-valid sentinel values for
one `terra-sol` case; it is copied and edited for each benchmark run.

- [ ] **Step 5: Document safe benchmark execution**

Specify one clean worktree per case and policy, ROM/fixture hash verification, retained-by-default results, patch capture, enumerated-owned-file restoration, clean-status confirmation, and ordinary worktree removal. Explicitly prohibit resetting or force-removing a dirty benchmark worktree.

- [ ] **Step 6: Validate JSON artifacts**

Run:

```bash
jq empty docs/architecture/validation/trace/trace-model-routing-benchmark.json
jq empty docs/architecture/validation/trace/trace-model-routing-benchmark.schema.json
jq empty docs/architecture/validation/trace/trace-model-routing-result.schema.json
check-jsonschema \
  --schemafile docs/architecture/validation/trace/trace-model-routing-benchmark.schema.json \
  docs/architecture/validation/trace/trace-model-routing-benchmark.json
check-jsonschema \
  --check-metaschema \
  docs/architecture/validation/trace/trace-model-routing-benchmark.schema.json \
  docs/architecture/validation/trace/trace-model-routing-result.schema.json
check-jsonschema \
  --schemafile docs/architecture/validation/trace/trace-model-routing-result.schema.json \
  docs/architecture/validation/trace/trace-model-routing-result.template.json
jq -e '
  ([.cases[].id] | length == (unique | length)) and
  ([.cases[].game] | unique | sort == ["s1","s2","s3k"]) and
  (all(.cases[]; .historicalFailureConfirmed == true))
' docs/architecture/validation/trace/trace-model-routing-benchmark.json
while IFS= read -r commit; do git cat-file -e "${commit}^{commit}"; done < <(
  jq -r '.cases[].baseCommit' docs/architecture/validation/trace/trace-model-routing-benchmark.json
)
jq -r '.cases[] as $case | $case.fixtures[] |
  [$case.baseCommit,.path,.sha256] | @tsv' \
  docs/architecture/validation/trace/trace-model-routing-benchmark.json |
while IFS=$'\\t' read -r commit path expected; do
  git cat-file -e "${commit}:${path}" &&
  test "$(git show "${commit}:${path}" | sha256sum | cut -d' ' -f1)" = "$expected"
done
```

Expected: every command exits 0. If `check-jsonschema` is unavailable, install/use
the repository-supported validator or validate through an existing equivalent;
do not replace schema validation with syntax-only parsing.

### Task 3: Review, policy verification, and integration

**Files:**
- Modify as required by review: all Task 1–2 files
- Modify: `README.md` during integration
- Stage: design, plan, skills, runbook, decisions, and benchmark artifacts

**Interfaces:**
- Consumes: completed documentation and skill changes.
- Produces: independently reviewed, policy-compliant commit integrated into `develop`.

- [ ] **Step 1: Run focused static checks**

Run:

```bash
rg -n "gpt-5\\.6-(terra|sol)|requestedModel|needsEscalation|modelRoute|missing-rom-basis" \
  .agents/skills/trace-green-fleet/SKILL.md \
  .claude/skills/trace-green-fleet/SKILL.md \
  docs/agent-workflow/runbooks/runbook-multi-agent-trace-orchestration.md
rg -n "gpt-5\\.6-luna" .agents/skills/trace-green-fleet/SKILL.md
```

Expected: all required routing concepts appear; Luna is not requested as a current worker.
Also complete a semantic comparison checklist covering route table, classification
exceptions, escalation enums, schemas, attempt limits, and failure behavior across
both skills; store the checklist result in the pressure-test validation artifact.

- [ ] **Step 2: Delegate implementation review**

Ask a fresh reviewer to check exact routes, schema completeness, escalation ownership, unchanged parity gates, Claude/Codex semantic parity, benchmark reproducibility, and documentation placement. Fix every valid blocking issue and repeat until green.

- [ ] **Step 3: Run repository policy checks**

Run `git config core.hooksPath .githooks`, stage only authored files, and let
`.githooks/commit-msg` invoke `.githooks/run-policy commit-msg` during the real
commit. Confirm `git status --short` contains no authored artifact left untracked.

- [ ] **Step 4: Commit the feature branch**

Stage only the authored files. Commit with all required trailers, including:

```text
Changelog: n/a: agent workflow documentation only
Guide: updated
Known-Discrepancies: n/a: no engine discrepancy changed
S3K-Known-Discrepancies: n/a: no S3K discrepancy changed
Agent-Docs: n/a: no AGENTS.md or CLAUDE.md change
Configuration-Docs: n/a: no runtime configuration changed
Skills: updated
```

Use the policy hook and never `--no-verify`.

- [ ] **Step 5: Integrate per the repository workflow**

In the main workspace, preserve unrelated user changes, then run:

```bash
git fetch origin
git pull --ff-only origin develop
mvn test
```

Back in the development worktree, rerun the JSON/schema/pressure checks from Tasks
1–2. Update and stage `README.md`'s release/change-log section in the main workspace,
then merge `feature/ai-trace-model-routing` without switching the main workspace
branch. Run:

```bash
mvn test
git push origin feature/ai-trace-model-routing
git push origin develop
```

If dirty user state prevents a safe pull, README update, merge, or test, report the
exact unresolved integration state rather than claiming completion. The final
report lists upstream changes/conflicts reconciled, every test command/outcome, and
the pushed branches and commit IDs.

## Self-review

- Spec coverage: routing, escalation, structured telemetry, prompt/cache discipline, safe benchmarking, and harness-specific skill parity are each assigned to a task.
- Placeholder scan: angle-bracket ROM paths and trace values are schema examples, not unfinished requirements.
- Type consistency: routing field names and enums match the design; model IDs match the currently exposed Codex overrides.

# Trace model routing design

## Objective

Reduce agent tokens spent per verified trace-frontier advance without weakening
ROM evidence, comparison-only replay, cross-game validation, or independent
verification.

The fleet will route bounded work to the least expensive available GPT-5.6 tier
that can safely perform it, then escalate on observable evidence. The primary
optimization targets are total tokens per accepted result and total tokens per
trace greened. Acceptance rate, wall time, reviewer rejection rate, and regression
detection must not worsen. Frontier frames advanced remains a secondary,
within-the-same-trace diagnostic; different trace frame numbers are not comparable.

## Constraints

- The conductor remains responsible for queueing, conflicts, escalation, and
  accepting verification results.
- Trace workers retain every existing non-negotiable parity rule.
- Model routing cannot decide whether a fix is genuine. Tests, ROM citations, and
  independent verification remain authoritative.
- Codex currently exposes `gpt-5.6-sol` and `gpt-5.6-terra` as worker overrides,
  with `low`, `medium`, `high`, `xhigh`, `max`, and `ultra` effort values. Luna is
  documented as a future mechanical tier and is never requested while unavailable.
- The active conductor model is selected before the skill runs and cannot be
  rerouted in-session. Sol is recommended when starting a fleet, but only child
  worker routing is enforceable by this design.
- Reasoning effort is selected before a worker starts. Escalation therefore creates
  a new worker with the prior structured handoff.
- The existing maximum of four active trace pipelines remains unchanged.

## Routing policy

The Codex fleet uses these defaults:

| Responsibility | Model | Effort | Reason |
|---|---|---:|---|
| Conductor | Active model; Sol recommended at fleet launch | Active effort; medium recommended at fleet launch | Neither can be changed by the running skill |
| Discovery | `gpt-5.6-terra` | low | Test execution and report classification are mechanically gated |
| Triage | `gpt-5.6-terra` | medium | Requires report, engine, and disassembly correlation |
| Narrow fix | `gpt-5.6-terra` | medium | Most object-local fixes are bounded |
| Deep/shared fix | `gpt-5.6-sol` | high | Shared timing and lifecycle changes have a broad regression surface |
| Verification | `gpt-5.6-terra` | medium | Commands and structured genuineness checks dominate |
| Escalated verification | `gpt-5.6-sol` | high | Required for shared code, regressions, or disputed ROM evidence |

If Luna becomes available, only discovery and mechanical result collection move to
Luna at low effort initially. It does not become the default fix or genuineness
model without benchmark evidence.

## Classification and objective escalation

Terra Triage must produce one precise ROM-cited hypothesis with at least medium
confidence. Multiple plausible owners, low confidence, unresolved causal ownership,
or a missing ROM basis escalate to one Sol-high Triage worker. If Sol Triage still
lacks a ROM basis, it returns `missing-rom-basis` as a blocker and Fix never starts.

Accepted Triage classifies the work before Fix starts. Fix routes directly to Sol
if triage identifies changes to shared runtime owners for physics, collision,
sidekick, camera, oscillation, bootstrap, object lifecycle, recorder/publication
contracts, or cross-game semantics. Object-local collision/profile code remains
narrow. Otherwise Fix starts on Terra.

Verification routes directly to Sol when the fix touched a shared surface, was
performed by Sol, or has disputed ROM evidence. Otherwise it starts on Terra. The
existing Verify stage is the first stage that runs green guards; if Terra detects a
regression it returns an escalation handoff and Sol independently repeats
verification before the result can be accepted.

An initially narrow Terra Fix escalates to Sol when any of these is true:

- two fix attempts produced no frontier advance;
- the proposed change touches shared physics, collision, sidekick, camera,
  oscillation, bootstrap, object lifecycle, or recorder/publication contracts;
- the relevant games have different disassembly semantics;
- generated context contradicts the proposed ROM explanation;
- diagnosis requires new recorder fields or hook-driven evidence;
- the worker returns `needsEscalation=true` with reason `causal-thread-lost`.

The Terra worker stops after the second unsuccessful attempt and returns its
attempt history and dirty-worktree state. It may retain an edit only when every
retained change is ROM-backed and listed in `filesTouched`; otherwise it restores
only its own edits before handoff. The conductor ends that worker's ownership
before spawning Sol. Sol first reviews the retained diff, may restore only the
predecessor's listed files, and then gets at most one further edit/test attempt.
Total attempts remain capped at three and workers never concurrently own a
worktree.

Effort is also immutable after spawn. A Terra-medium worker that needs more
reasoning returns an escalation result; the replacement is Sol-high rather than a
same-model retry. A missing ROM citation blocks Fix rather than triggering more
speculation.

## Structured routing contract

Every stage result includes:

```json
{
  "requestedModel": "gpt-5.6-terra",
  "requestedEffort": "medium",
  "actualModel": null,
  "actualEffort": null,
  "complexity": "narrow",
  "confidence": "high",
  "attemptCount": 0,
  "sharedSurfaces": [],
  "needsEscalation": false,
  "escalationReasons": [],
  "modelRoute": ["gpt-5.6-terra/medium"],
  "usage": {
    "inputTokens": null,
    "cachedInputTokens": null,
    "outputTokens": null,
    "reasoningTokens": null
  },
  "durationMs": null
}
```

Allowed `complexity` values are `mechanical`, `narrow`, `shared`, and `deep`.
Allowed confidence values are `high`, `medium`, and `low`. Escalation reasons are:
`no-frontier-advance`, `multiple-owners`, `missing-rom-basis`,
`low-confidence`, `unresolved-ownership`, `reasoning-insufficient`,
`shared-surface-discovered`, `cross-game-semantics`, `regression`,
`context-contradiction`, `recorder-evidence-required`, and `causal-thread-lost`.

The existing Triage, Fix, and Verify objects embed these fields alongside their
current fields. Discovery uses the same routing fields with `complexity=mechanical`
and `attemptCount=0`. `attemptCount` is cumulative across replacement workers for
the same stage. `modelRoute` is conductor-authored and preserved in each handoff;
workers do not guess it. The conductor rejects missing fields, validates enums and
requested route against the rules above, and appends the replacement route on
escalation. It records `actualModel`, `actualEffort`, usage, and duration only when
the runtime exposes them; it never asks a worker to estimate them.

The conductor spawns workers with explicit overrides, for example:

```text
spawn_agent(fork_turns="none", model="gpt-5.6-terra", reasoning_effort="medium", ...)
spawn_agent(fork_turns="none", model="gpt-5.6-sol", reasoning_effort="high", ...)
```

When fleet discovery is needed, it is performed by a fresh Terra-low child, not
by the conductor. A caller-supplied failing list skips that child.

## Prompt and cache discipline

Every stage prompt begins with the same byte-stable policy prefix. Dynamic trace
data follows it in a compact JSON handoff. Stages do not repeat narrative summaries
already represented in the handoff.

Workers start with compact first-divergence evidence and paths to large reports and
disassemblies, expanding context when causal analysis requires it. Fresh stage
workers are intentional for independent verification. A stable prompt prefix may
benefit provider caching, but neither session reuse nor cache retention is assumed.
Only exposed cached-token telemetry can demonstrate a cache benefit.

## Measurement

Each stage result records:

- requested model and effort;
- escalation reason, if any;
- input, cached-input, output, and reasoning tokens when the runtime exposes them;
- wall-clock duration;
- before and after frontier;
- green/advanced/rejected/error status;
- edit-attempt count and regression count. Discovery, Triage, and Verify always
  record zero attempts; totals count only Fix edit/test attempts.

When runtime token counters are unavailable, fields are `null`; they are never
estimated. The conductor includes a machine-readable `routing` block in its
ordinary final JSON result.

The repository will also contain a versioned manual benchmark manifest. Each case
pins a repository base commit, fixture paths and hashes, expected ROM hash and
property name (the ROM path remains user-supplied), test class, game, zone, failure
field, starting frontier, exact targeted command, verification set, and complexity.
A result template records all stages, usage, wall time, fleet statuses including
`advanced-with-regression` and `rejected-not-genuine`, independent `accepted`,
`genuine`, and `reviewerRejected` flags, structured ROM citations, exact
regression-guard outcomes, and structured regressions.

Each policy runs in a newly created benchmark worktree and branch from the pinned
base commit. The operator verifies fixture and ROM hashes before starting. Retaining
the worktree is the default. The result captures a patch plus the exact base and
resulting tree hashes against the pinned benchmark base, including Verify commits,
later working edits, and enumerated new owned files. Cleanup is permitted only after restoring the benchmark
worker's enumerated files to the pinned base, confirming the worktree is clean, and
using ordinary `git worktree remove <exact-benchmark-path>`. Unrepresented or
foreign changes are never restored or force-removed; cleanup that would discard
them requires explicit destructive approval. Policies never reuse or reset a dirty
worktree. Running the benchmark means executing the same manifest once per policy:

1. Sol-only;
2. Terra-first with objective Sol escalation;
3. Luna/Terra-first when Luna becomes available.

A routing policy is promoted only when the same frozen cases show lower median
tokens per accepted result and per trace greened without lowering acceptance rate,
ROM-citation completeness, or regression detection, and without worsening median
wall time unacceptably. If the runtime exposes no token telemetry, token-efficiency
promotion is impossible; correctness and wall-time observations may still be
recorded but cannot justify promotion.

## Failure handling

- Unsupported model identifier: reject before spawn. Current routes never request
  Luna. A missing Sol route stops an escalated stage rather than silently using
  Terra.
- Worker result omits routing/escalation fields: request one schema-only repair
  from the completed worker when possible; otherwise rerun once. A second malformed
  result records a stage error rather than looping.
- No token telemetry: continue correctness work and mark metrics unavailable.
- Escalation loops: at most one model escalation per stage. A failed Sol stage
  returns a blocker rather than recursively spawning workers.

## Scope

This change updates the Codex trace fleet skill, its Claude counterpart without
erasing harness-specific differences, the multi-agent trace runbook, and a frozen
manual benchmark manifest/result template. The legacy Claude JavaScript workflow
receives policy documentation only where its agent API cannot express GPT-5.6
model selection. It must not pretend to enforce a model override that the runtime
does not support.

The existing fleet skill permits recording a genuine
`advanced-with-regression` result, while the lead integration runbook rejects
regressions from integration. This design does not resolve that pre-existing
boundary: routing preserves the stage's status, and the owning integration workflow
continues to decide whether the commit can land.

# Agent guidance audit — 2026-09-05

## Scope and sources

Reviewed OpenGGF's root agent guides, S3K guide, all 23 mirrored skill
entrypoints and relevant supporting instructions. Personal scope included the
user's Codex instructions, personal skills, bundled system skills, and installed
plugin skill entrypoints. Optional external submodules and other tasks' worktrees
are independent repositories and were not rewritten.

The current configured model is GPT-6 Astra. Sources read on 2026-09-05:

- [GPT-6 Astra prompting best practices](https://developers.openai.com/api/docs/guides/latest-model#prompting-best-practices):
  audit conflicting skill instructions, support authorized follow-through, and
  match verification to the change.
- [Build skills](https://learn.chatgpt.com/docs/build-skills): concise task
  descriptions, conditional references, and prompt-based routing checks.
- [AGENTS.md discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md):
  persistent instructions are composed from global and repository files.
- [System card: respecting auto-review](https://deploymentsafety.openai.com/gpt-6-astra/respecting-auto-review)
  and [coding deception](https://deploymentsafety.openai.com/gpt-6-astra/coding-deception):
  the evaluated boundaries concern respecting denied actions and honest claims
  about actions and verification. They are not evidence that game-specific
  invariants or permission boundaries can be dropped.

The editing approach is an engineering judgment based on these sources, not an
OpenAI-endorsed project configuration or proof of improved model performance.

## Decisions

- Keep the root guide focused on project purpose, build identity, runtime
  invariants, delivery policy, and routes to deeper knowledge. Move shared
  implementation pitfalls into a conditional architecture reference.
- Preserve ROM-only assets, shipped FixBugs=0 behavior, semantic shared-runtime
  ownership, comparison-only gameplay, both bounded timing-input shapes, v5
  identity, rewind, mutation ownership, ROM properties/hashes, and hook rules.
- Replace large skill entrypoints with task-specific procedures and narrowly
  scoped technical references. Remove generic scaffolding, repeated policy,
  dated implementation inventories, forced model ladders, fixed worker counts,
  and approval ceremonies that conflict with existing authorization.
- Preserve source-backed pitfall catalogs; search relevant symptoms rather than
  loading thousands of lines before every object change.
- Correct old trace-schema references and misleading test commands. Remove the
  assumption that shared merge conflicts can always be resolved additively.
- Correct the documentation checklist's obsolete automatic-hook setup and
  release-index advice; remove its unsafe generic history-reset recipe.

## Personal setup disposition

Personal changes are outside this repository and are not included in its Git
commit. Backups were retained in the explicit local task directory.

- Global instructions: consolidate autonomy, branch safety, evidence, delivery,
  and cleanup. Preserve full baseline/development/merged comparisons for code;
  use focused validation for documentation-only edits.
- `thorough-mode`: retain explicit invocation, design/plan and independent
  review; review changed risks instead of unbounded repeated unchanged reviews.
  Preserve its explicit-only metadata and align its display description.
- `cowtree`: keep its concrete reflink and dirty-source safety contract.
- Superpowers: already disabled in the CLI configuration before this audit.
  Keep it disabled; avoid editing versioned plugin caches. This running session
  still contains injected Superpowers instructions, so configuration state is
  not evidence that they have disappeared from its context.
- Bundled system skills, Deep Research, Plugin Management, and template skills:
  retain their scoped protocol, permission, and artifact requirements. The
  system OpenAI Docs skill has docs-first wording that can conflict with a
  higher-priority tool's local-first rule; normal instruction precedence applies.
  No connector permissions or model settings were changed.

## Behavioral review

Independent reviewers inspected routing and decision scenarios. These are
behavioral walkthroughs, not measured live-task success rates:

| Request | Expected route and boundary |
|---|---|
| Documentation typo | Relevant syntax/link/mirror/policy checks, authorized delivery; no design ceremony |
| Code fix with a red baseline | Compare failures by identity/details; new or worsened regressions block delivery |
| Explicit thorough-mode planning pause | Review design/plan, then honor requested pause before implementation |
| Denied external action | Respect denial, continue unaffected work, report unresolved authority |
| Implement an MHZ object | S3K object skill; relevant disassembly/pitfall sections only |
| HCZ waterline seam | Parallax and matching renderer boundary; no whole-zone orchestration |
| Queue or dynamic-art divergence | Trace diagnosis plus owning PLC contract; no gameplay hydration |
| Capture replay video | Capture skill; finished video is not parity evidence |
| Multiple excluded trace routes | Concrete allowed test selection; no wildcard then post-filtering |
| Find a mapping ROM offset | Matching disassembly guide; no implementation workflow |

## Validation

Measured entrypoint size (`AGENTS.md`, `AGENTS_S3K.md`, and one copy of all
23 skills): **12,060 → 1,518 lines (87.4% reduction)**. Root guidance is
419 → 159 lines; S3K guidance is 232 → 44. Four new conditional skill
references total 199 lines. All three existing pitfall catalog bodies after
the first separator are byte-identical to baseline.

`git diff --check`, frontmatter/name checks for all 23 skills, mirror equality,
and relative links across 30 guides/entrypoints/references passed. Independent
review corrected a moved link and retained the hook's master-merge exemption.
The packaged skill validator could not import PyYAML; the explicit structural
checks above were used instead.

Integration base: `develop` at `bbf28b7dc895ded72dfbb63842aa6c43d3d06a7b`;
implementation tree: `feature/ai-agent-guidance`. Fetch/fast-forward found no
upstream changes. Existing dirty disassembly submodules were preserved.

Ordinary command, on both base and development tree (variables below represent
the discovered absolute S1 REV01, S2 REV01, and locked-on S3K paths):

```bash
mvn -Dmse=off -B \
  "-Dsonic1.rom.path=$S1_ROM" \
  "-Dsonic2.rom.path=$S2_ROM" \
  "-Ds3k.rom.path=$S3K_ROM" test
```

- Baseline: fork error `ClassNotFoundException:
  com.openggf.trace.live.TestRecordingFrameObserver`; no assertion failures
  in emitted testcase results. This incomplete run is not a green baseline.
  `mvn -Dmse=off -B -Dtest=TestRecordingFrameObserver test` then passed all five
  tests in isolation.
- Development: Surefire reported 16,482 tests, zero failures/errors, 40 skips.
  Matched testcase identities from the baseline had no new failures. Three
  formerly executed visual cases skipped due to GLFW initialization, requiring
  a separate display-enabled check. That check passed all three cases with
  zero skips using `mvn -Dmse=off -B -Dsurefire.forkCount=1
  "-Dtest=TestS3kCnzVisualCapture,TestS3kDataSelectPresentation#visualCapture_selectedSaveSlotShowsRightBodyRail"
  "-Ds3k.rom.path=<same absolute S3K ROM path>" test`. The five observer cases
  that hit the baseline fork error passed in development.
- One development Surefire XML had a malformed JVM-property attribute. Intact
  testcase elements and the completed Maven log were used for comparison;
  the malformed envelope was not silently treated as valid XML.
- `mvn -Dmse=off -Pguards test -B`: **609 tests, zero failures/errors/skips** on
  both baseline and development. This includes the existing checks on agent
  document mirroring, timing-boundary wording, direct Maven, and optional tools.

No gameplay, test implementation, fixture, or build configuration is changed.
Post-merge verification and pushed commits are reported with delivery.

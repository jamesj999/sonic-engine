# Release rollover

The process for shipping the version on `develop` and promoting every branch
to the next one. Run it once per major release.

## The invariant

`pom.xml`'s `<version>` is the **only** declaration of which release a branch
carries. Runtime identity and generated artifact coordinates derive from it:

- `src/main/resources-filtered/version.properties` filters `${project.version}`
  into the jar, and `com.openggf.version.AppVersion` reads it at runtime.
- `.github/workflows/release.yml` greps the version out of `pom.xml` to name
  and smoke-validate artifacts.

Each branch is self-describing. Keep the macOS bundle version in
`src/packaging/Info.plist` aligned with the engine release line, and update
runnable artifact examples alongside each POM bump. The unpublished Mod API
version is independent: update its branch topology descriptor without implying
API publication or changing compatibility ranges.

## Version naming

| Branch | Role | Version form | Today |
|---|---|---|---|
| `master` | last released | `<major>.<YYYYMMDD>` | `0.6.20260911` |
| `develop` | in development | `<major>.prerelease` | `0.7.prerelease` |
| `next` | after that | `<major>.prerelease` | `0.8.prerelease` |

A release stamps the date at the moment it is cut; prerelease branches never
carry a date.

## Rollover steps

The September 12 rollover preserves `release/0.6.20260911` at the published
tag, synchronizes `master` into `develop`, merges `develop` into `next`, then
`next` back into `develop` before advancing the two development versions.
Keep the release branch at the tag; ordinary rollover work does not push
`master` and retrigger publication of an existing version.

Worked below as "0.6 ships", which promotes master 0.5 → 0.6, develop 0.6 →
0.7, next 0.7 → 0.8. Substitute your own numbers.

### 1. Gate

Do not start until `develop` satisfies the release gates: ordinary `mvn test`,
`mvn -Dmse=off -Pguards test -B` separately, the trace no-regression policy in
`docs/status/trace-scope-release-<n>.md`, and the human end-to-end gameplay and
audio QA that automated gates cannot cover. Record the evidence in
`docs/changelog/v0.6-release-summary.md`.

### 2. Finalize 0.6 on `develop`

- `pom.xml` → `0.6.<YYYYMMDD>`, dropping `.prerelease`.
- `CHANGELOG.0.6.md`: final pass. It is frozen after release and changes only
  for factual corrections.
- `docs/changelog/v0.6-release-summary.md`: final validation results, known
  limitations, and the blockers that were accepted rather than fixed.
- `README.md` release section: rewrite the 0.6 entry as shipped rather than a
  development snapshot, and drop the "not a final release yet" status block.
  Keep it a **high-level summary** — see
  [the documentation obligation checklist](../agent-workflow/documentation-obligation-checklist.md).
- `RELEASE_NOTES_v0.6.prerelease.md` → `RELEASE_NOTES_v0.6.<YYYYMMDD>.md`,
  still a pointer to the release summary.

### 3. Release

Merge the reviewed release into `master` in its separate worktree. Follow
[Publishing a GitHub release](release-publishing.md): check that the remote
version tag does not already exist, then push `master` when publication is
authorized. **The push automatically publishes** after validation and builds
succeed; no manual dispatch is needed.

Do not create `v0.6.<YYYYMMDD>` yourself; the workflow creates it at the pushed
commit and rejects an existing tag. Manual dispatch runs validation only.
Confirm the GitHub release and all four downloads before promoting branches.

### 4. Promote `develop` to 0.7

- `pom.xml` → `0.7.prerelease`.
- Create `CHANGELOG.0.7.md` from the accumulated "Unreleased (next / 0.7)"
  section of `CHANGELOG.md`, which is where next-branch prose lives until its
  release file is cut.
- `CHANGELOG.md`: move `0.6.<YYYYMMDD>` into the release-files list, point the
  0.7 line at the new file, and replace the 0.6 release-documentation section
  with 0.7's.
- Create `docs/changelog/v0.7-release-summary.md` and
  `docs/changelog/v0.7-prerelease-detailed.md`.
- Create `docs/status/trace-scope-release-7.md` with the no-regression policy
  for the new cycle.
- `README.md`: new 0.7 development-snapshot section; move 0.6 into previous
  releases.

### 5. Promote `next` to 0.8

- `pom.xml` → `0.8.prerelease`.
- `CHANGELOG.md`: retitle the unreleased section to "Unreleased (next / 0.8)"
  and empty it — its 0.7 contents moved to `CHANGELOG.0.7.md` in step 4.

### 6. Update the stated mapping

The following files describe the current branch topology. Update them,
keeping `CLAUDE.md` and `AGENTS.md` byte-identical:

- `CLAUDE.md` and `AGENTS.md` — delivery and documentation section.
- `docs/agent-workflow/documentation-obligation-checklist.md` — "Which version
  am I writing for?".
- This guide, the README, and the current roadmap.
- `mod-api-release-policy.properties` — engine branch lines and candidate
  ownership; retain the API version and publication state unless separately
  releasing the API.

### 7. Sweep for stragglers

Version numbers leak into guides and status docs. After the bumps:

```bash
rg -l '0\.6' -g '*.md' . \
  | rg -v '\.worktrees|docs/(s1|s2|sk)disasm|target/|tools/tracechaser|CHANGELOG\.0|docs/changelog|docs/architecture'
```

Historical references stay as they are; only forward-looking statements ("the
current release is…", "deferred to 0.7") need editing. `CONTRIBUTING.md`,
`CONFIGURATION.md`, `ROADMAP.md`, `docs/guide/**`, and `docs/status/**` were
the ones that needed it last time.

## Verification

- `mvn package` on each promoted branch, and confirm `AppVersion` reports the
  new version rather than a stale filtered resource — `mvn clean` first, since
  incremental builds keep the old `version.properties`.
- `git show <branch>:pom.xml | grep -m1 '<version>'` for all three branches:
  they must be three different versions.
- No `.prerelease` on `master`, and no date suffix on `develop` or `next`.


### Reviewed pre-rollover next resource history

The September 2026 promotion pins the already-published `next` snapshot
`218b8fff1a829c7a509331c453d2f3be7e51533b` as
`NEXT_ROLLOVER_RESOURCE_BASELINE` in both policy implementations. Its complete
9,689-entry tree passed the release resource audit before promotion. The pin
bounds resource-history replay alongside the existing released snapshot; it
is an immutable commit, not a moving branch or remote reference.

When that snapshot is an ancestor of the delivered tip, policy audits the
complete delivered tree and excludes only the pinned snapshots' ancestor
histories from per-commit resource replay. New commits and merge resolutions
remain checked, including violations introduced and removed after the pin.
This prevents old, already-published intermediate documentation from blocking
promotion while preserving checks on current work.

The same immutable snapshot separately bounds historical trailer and Mod API
coupling replay through `NEXT_ROLLOVER_TRAILER_BASELINE`. Promotion exposed
commit `262d46cc497bd98777f7fdc710f72f9984da6e02`, whose
`Known-Discrepancies: updated` declaration has no matching change to the
currently mapped discrepancy file. Rewriting this already-published history
would replace shared commits. Instead, the explicit frozen boundary admits
its ancestor declarations while every subsequent nonmerge commit retains
trailer mapping and API-coupling checks. The existing merge-trailer exemption
is unchanged. This declaration boundary does not bypass the complete resource
audit or extend resource admission to later commits.


`PUBLISHED_RELEASE_TRAILER_BASELINE` additionally pins published release commit
`37aeb6b84d6634457616c942187cdd40dd93c24f`, whose 8,415-entry tree passed the
resource audit. This admits only its ancestor trailer/API-coupling history
when that exact release is an ancestor of the delivered tip. In particular,
master dependency commit `53bf83e60721138622e21f4394655f40463577a2` predates
the release and has no seven-trailer declaration; master publication already
excluded it as previously published target history. Promotion must preserve
those shared commits rather than rewrite their messages. The existing master
release range handling is unchanged. Every commit after the immutable release
boundary retains normal checks, and the complete delivered resource tree is
still audited independently.

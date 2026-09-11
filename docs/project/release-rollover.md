# Release rollover

The process for shipping the version on `develop` and promoting every branch
to the next one. Run it once per major release.

## The invariant

`pom.xml`'s `<version>` is the **only** declaration of which release a branch
carries. Nothing else derives it, and nothing else may hardcode it:

- `src/main/resources-filtered/version.properties` filters `${project.version}`
  into the jar, and `com.openggf.version.AppVersion` reads it at runtime.
- `.github/workflows/release.yml` greps the version out of `pom.xml` to name
  and smoke-validate artifacts.

So each branch is self-describing, and the rollover is fundamentally three pom
bumps plus the prose that follows them.

## Version naming

| Branch | Role | Version form | Today |
|---|---|---|---|
| `master` | last released | `<major>.<YYYYMMDD>` | `0.5.20260411` |
| `develop` | in development | `<major>.prerelease` | `0.6.prerelease` |
| `next` | after that | `<major>.prerelease` | `0.7.prerelease` |

A release stamps the date at the moment it is cut; prerelease branches never
carry a date.

## Rollover steps

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

Three files quote the current triple as a worked example. Update all of them,
keeping `CLAUDE.md` and `AGENTS.md` byte-identical:

- `CLAUDE.md` and `AGENTS.md` — delivery and documentation section.
- `docs/agent-workflow/documentation-obligation-checklist.md` — "Which version
  am I writing for?".

### 7. Sweep for stragglers

Version numbers leak into guides and status docs. After the bumps:

```bash
grep -rln '0\.6' --include='*.md' . \
  | grep -vE '\.worktrees|docs/(s1|s2|sk)disasm|target/|tools/tracechaser|CHANGELOG\.0|docs/changelog|docs/architecture'
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

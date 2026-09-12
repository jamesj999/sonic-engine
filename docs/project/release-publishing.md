# Publishing a GitHub release

**Pushing `master` automatically publishes a release** after validation and
all native and universal-JAR builds succeed. No manual dispatch is required.
The workflow is `.github/workflows/release.yml`; read its definition at the
commit being investigated when diagnosing a run.

## Trigger and outcome

| Action | Publish job |
|---|---|
| Push to `master` | Publishes after required builds succeed, provided the version tag is new. |
| Pull request targeting `master` | Validates and builds; does not publish. |
| Manual dispatch | Validation/build run only; does not publish, even on `master`. |
| Push to another branch or delete `master` | Does not publish. |
| Re-run a master push run | Can publish if the previous attempt did not create its version tag; inspect partial results first. |

The publish condition requires a non-deletion `push` event on
`refs/heads/master`. Builds or dependency failures can still prevent the
publish job from running. A manual dispatch is not a way to publish.

## Prepare and publish

1. Complete the release gates and prepare the reviewed release in a separate
   `master` worktree. Follow [release rollover](release-rollover.md) when
   promoting versions. Read the version from that commit's `pom.xml`.
2. Check the remote `v<version>` tag before pushing. The workflow rejects an
   existing tag rather than overwriting a published release. A later push
   with the same POM version will build but fail the existing-tag check; use
   a new release version when another release is intended. **Do not create
   the tag yourself** or delete/move an existing tag as a routine retry.
3. Once the reviewed release is ready and publication is authorized, push
   from the `master` worktree:

   ```bash
   git push origin master
   ```

   This is the publication trigger. It starts validation and builds, then
   creates a public, non-draft GitHub release. The workflow tags the exact
   pushed `github.sha`, not whichever commit a branch points to later.
4. Find the push run and verify its commit and job results:

   ```bash
   gh run list --workflow release.yml --branch master --event push --limit 5
   gh run view <run-id> --json headSha,event,status,conclusion,jobs,url
   ```

5. Confirm the `release` job succeeded and the release exists at `v<version>`,
   with Windows, macOS, Linux, and universal-JAR downloads:

   ```bash
   gh release view v<version> --json tagName,isDraft,isPrerelease,url,assets
   ```

   A push accepted by GitHub, a green build, and uploaded Actions artifacts
   are intermediate results. Report publication after checking the release
   itself. For a failed or cancelled run, inspect its tag and any partial
   release before retrying.

## Website refresh after publication

After `release` succeeds, the separate `notify-website` job sends an
`engine-release` repository dispatch to `OpenGGF/OpenGGF-WebZone`. That site's
refresh workflow updates its release cache and pushes a commit when it changes;
Cloudflare Pages then rebuilds the static site. The notification is part of the
publishing workflow because a release created with `GITHUB_TOKEN` does not
trigger another workflow listening for `release: published`.

Configure the **engine repository's** Actions secret `WEBZONE_DISPATCH_PAT`
with a fine-grained token whose resource owner is `OpenGGF`, repository access
is limited to `OpenGGF-WebZone`, and repository permission is **Contents: write**
(Metadata: read is included). Complete any organization approval required for
the token. The engine's ordinary `GITHUB_TOKEN` cannot write to the website repo.
See GitHub's [repository dispatch permissions](https://docs.github.com/en/rest/repos/repos#create-a-repository-dispatch-event).

A missing secret or rejected API request fails `notify-website`; the engine
release has already been published. Fix the credential and rerun **only the
failed job**, rather than all jobs, to avoid attempting to publish the same tag.
For immediate recovery, run:

```bash
gh workflow run refresh-on-release.yml --repo OpenGGF/OpenGGF-WebZone
```

The website also has a daily scheduled refresh as a backstop. Confirm the cache
refresh run, its Cloudflare Pages check, and the version/download links served
at [openggf.com](https://openggf.com) before reporting that the website updated.

## When `release` is skipped

Inspect the run's event, branch, commit, and required `build` and
`universal-jar` job results. Pull-request and manual-dispatch runs skip
publication by design. A failed or cancelled dependency can skip publication
on a push run. Older commits may still contain the former manual-only gate;
re-running an old run uses that old workflow definition. Deliver the current
workflow to `master` to trigger automatic publishing.

On `master`, manual dispatch can opt into the additional `rom-validation`
job with `validate_roms=true` and a configured `release-fixtures` runner.
That job is skipped on push and does not control publication. Packaging steps
for other operating systems are also expected to be skipped.

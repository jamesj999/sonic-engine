# 07 — Launch the current Maven artifact deterministically

Medium; low complexity; estimated 1–2 hours.

## Problem

run.sh/run.cmd package the current version, then select the last matching fat jar in target. Old higher-version artifacts can win. The Windows launcher also depends on the caller working directory.

## Owners and evidence

Owners: `run.sh`, `run.cmd`, relevant Maven build-tooling tests. Read current finalName/assembly configuration before selecting a mechanism.

## Implementation steps

1. Add executable launcher coverage with stubbed mvn/java and both stale/current artifacts; verify the current configured artifact is selected.
2. Resolve the exact Maven artifact name using existing build configuration or a reliable generated manifest. Honor configured project.build.finalName; do not hardcode the current release version or select by timestamp.
3. Fail clearly when expected packaging output is absent, even when a stale matching jar exists. Preserve argument quoting and paths containing spaces.
4. Make run.cmd enter its own script directory and preserve command failures. Avoid expensive repeated package operations to query metadata.
5. Validate Bash syntax and portable command construction; report Windows execution limitations honestly if no Windows host is available.

## Acceptance

Both launchers select the artifact produced for the current Maven project, never an arbitrary stale glob match. Packaging errors/missing output stop launch and invocation outside the repository resolves correctly.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.

# 09 — Make standalone sound-test cleanup respect audio ownership

Lower priority developer tooling; low–medium complexity; estimated 1–3 hours.

## Problem

SoundTestApp installs host.close on a shutdown-hook thread. AudioPresentationProducer rejects off-owner close, but StandaloneAudioPresentationHost marks itself closed before destruction, preventing a later valid retry.

## Owners and evidence

Owners: `audio/debug/SoundTestApp.java`, `StandaloneAudioPresentationHost.java`, audio host tests and owner-thread contracts. Root audit reproduced exception on worker close then owner retry returning with producer still open.

## Implementation steps

1. Reproduce rejected cleanup and lost retry with a no-device host and controlled threads. Assert actual producer/sink closure, not merely that close returns.
2. Design a narrow shutdown path that executes audio destruction on its owning thread or a properly owned executor. Preserve strict AudioPresentationProducer thread confinement; do not disable its assertion.
3. Mark host cleanup complete only when ownership rejection cannot leave a live producer permanently inaccessible. Preserve idempotency and aggregate cleanup failures appropriately.
4. Handle normal exit, JVM shutdown request, partial initialization and repeated close without deadlock or unbounded process-exit waits. Avoid moving unrelated engine audio work to new threads.
5. Verify sound-test command/presentation behavior with no-device tests; do not claim OpenAL/device coverage from them.

## Acceptance

Normal and shutdown-requested cleanup execute on the correct owner, or safely signal/await that owner with a bounded policy. A rejected close remains retryable; no live producer is hidden behind a permanently closed facade.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.

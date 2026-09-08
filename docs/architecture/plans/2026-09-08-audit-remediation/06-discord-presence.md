# 06 — Keep Discord IPC off the game thread

Medium conditional freeze; medium complexity; estimated 3–6 hours.

## Problem

GameLoop.step invokes PresenceManager.tick synchronously. Default local IPC transport writes on blocking channels without a deadline; stalled peers can freeze the entire game when presence is enabled.

## Owners and evidence

Owners: `integration/presence/PresenceManager.java`, `discord/DiscordIpcPresenceClient.java`, transports and appropriate lifecycle wiring in `GameLoop.java`; presence tests. Feature remains disabled by default.

## Implementation steps

1. Add deterministic blocked-client tests proving tick returns without waiting for connect/write and that shutdown is bounded.
2. Capture immutable gameplay presence data on the game thread; submit/coalesce latest status to a bounded worker. Never read mutable gameplay owners on the worker.
3. Bound pending work, keep serialization/connection ownership coherent, handle failed client and close races, and make blocked I/O cancellable/closable with bounded shutdown.
4. Preserve privacy defaults, timer throttling, no-op behavior when unavailable, and existing payload formatting. Do not retry indefinitely or flood Discord.
5. Exercise repeated ticks under backpressure, failure then shutdown, disable/close before connection, and resource cleanup. Avoid unbounded threads/queues or sleeps as race proofs.

## Acceptance

Enabled presence cannot block a gameplay step on IPC. Work remains bounded/coalesced, gameplay state is captured only on its owner thread, and shutdown has a tested deadline.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.

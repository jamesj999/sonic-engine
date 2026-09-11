# AIZ f2707 live-push ownership audit

## Scope

This audit covers the standalone AIZ comparison frontier at trace frame
`2707`: ROM Tails `anim=$00` / `mapping_frame=$AC`, versus engine
`anim=$05` / `mapping_frame=$AD`. It does not use or modify trace data as
runtime input. LBZ was not inspected, run, changed, or used as a guard.

Baseline `origin/develop` was `41f3bc62f`:

```text
1277 errors, 0 warnings
first error f2707 tails_animation_id ROM $00 / engine $05
```

Every compared physical value at f2707 already agreed. Tails was at
`$1CED.EE00,$03C0.2800`, with zero final velocity, routine `$02`, status
`$20` (`Status_Push`), and CPU routine `$06`.

## Disassembly ownership

`TailsCPU_Normal` reads the live Tails `Status_Push` bit at `loc_13DD0`.
When the delayed leader status does not also contain Push, it branches
directly to `loc_13E9C`, preserving the already-loaded `Ctrl_2_logical`
sample (`docs/skdisasm/sonic3k.asm:26696-26705,26775-26785`).

The following ordinary `Tails_InputAcceleration_Path` tests `ground_vel`
before its no-input friction:

- non-zero inertia skips the stationary `anim=$05` write;
- friction then decays `$000C` to zero;
- the next player dispatch enters the stationary branch and writes `$05`.

The relevant ordering is
`docs/skdisasm/sonic3k.asm:27798-27837,27898-27920`.

## Native observation

`tools/bizhawk/probes/aiz_tails_anim_2707_probe.lua` uses the shared
`ProbeRuntime`: invisible emulation, sound disabled, 6400% speed, semantic
AIZ1/gameplay/level-counter gating before hooks, bounded callbacks, cleanup,
and self-exit. It observes only the Tails animation byte and player dispatch;
it performs no RAM writes.

The probe was run twice against the committed BK2 and verified locked-on ROM.
At the frontier:

```text
gfc=0971 Tails_Normal entry: status=20 gvel=0000
gfc=0971 anim write PC=014CDE: anim=00 gvel=000C
gfc=0972 Tails_Normal entry: status=20 gvel=000C
gfc=0972 no animation-byte write
gfc=0973 Tails_Normal entry: status=20 gvel=0000
gfc=0973 anim write PC=014A3A: anim=00 (write instruction selects $05)
```

BizHawk's memory-write callback observes the old byte at the write boundary,
so the next dispatch shows `$05`. The important ownership fact is independent
of callback value timing: there is no animation write at `$0972`, while the
stationary write begins at `$0973`.

## Engine root cause

`SidekickCpuController` could classify the same frame as both:

1. the direct ROM-visible `currentPushBypass`; and
2. the synthetic provider-approved `objectOrderGrace`.

It exposed the second classification through
`usedObjectOrderGracePushBypassThisFrame()`. The movement layer treats that
flag as authority to clear stale `$000C` inertia before ground movement.
That made its pre-friction snapshot zero and selected Wait one dispatch early.

The bridge is not an owner when direct native `Status_Push` already owns
`loc_13DD0`. Restricting the exposed bridge flag to
`objectOrderGrace && !currentPushBypass` preserves the direct ROM branch and
its ordinary no-input friction. No zone, trace, route, or frame predicate is
introduced.

## Verification

The RED test
`s3kLivePushBypassDoesNotMasqueradeAsObjectOrderGraceNearAizGiantVine`
reproduced the overlapping classification, then passed after the ownership
restriction. The complete `TestSidekickCpuFollowParity` and
`TestPlayableSpriteMovement` classes passed.

Fresh replays after the change:

| Trace | Result |
| --- | --- |
| AIZ standalone | 1,275 errors; frontier f8215 `player_animation_id`, ROM `$05`, engine `$13` |
| AIZ complete | unchanged: 26 errors; f26107 `x`, ROM `$0000`, engine `$4A9B` |
| CNZ standalone | unchanged: 3,714 errors; f4801 `tails_mapping_frame`, ROM `$07`, engine `$55` |
| MGZ standalone | unchanged: 16 errors; f23561 `rings`, ROM `0`, engine `1` |
| S2 EHZ1 | green |

The standalone AIZ frontier therefore advances from f2707 to f8215 and sheds
two full-run comparison errors. The remaining f8215 ending-pose mismatch is a
separate ownership incident.

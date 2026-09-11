# AIZ end-sign owner lifecycle audit

## Scope

This audit covers the AIZ1 miniboss defeat bridge, `Obj_EndSignControl`, and
the falling `Obj_EndSign` through results control restoration. The ROM source
of truth is `docs/skdisasm/sonic3k.asm`, especially `Obj_EndSignFall`,
`EndSign_CheckPlayerHit`, `Check_PlayerInRange`, and
`Wait_FadeToLevelMusic`.

## Findings

The engine kept the defeated miniboss interactive for twelve object entries
after the ROM had installed `Obj_EndSignControlWait`. Those entries belong to
the control object's `$77` wait. The old bridge instead left the wait intact
and subtracted sixteen entries from the later physical signpost timer. That
collapsed two owners and prevented the complete-run sign from participating
in its native player-hit path.

The sign's falling dispatch also differed from `Obj_EndSignFall` in two ways:

- gravity and movement ran before the player-hit check;
- `EndSign_Range` was read as four endpoints, although
  `Check_PlayerInRange` reads each pair as an origin offset and a width.

The native interaction bounds are therefore `x[-$20,+$20)` and
`y[-$18,+$18)`. A nonzero cooldown byte decrements and returns, including the
`1 -> 0` entry.

## Implemented ownership

`AizMinibossInstance` counts only the live defeated-boss bridge entries that
overlap `Obj_EndSignControlWait` and carries that elapsed state into the
control object. The physical signpost receives no post-land catch-up. Its
falling routine now executes sparkle, player-hit/cooldown, gravity, movement,
wall handling, animation, and floor handling in ROM order.

On the complete route this produces the two native hits at sign/player
centres `$110A,$033B` / `$10F0,$034D` and `$1182,$0329` /
`$1188,$0340`, then the native landing at `$115C,$034F`. Restoring this real
owner boundary consumes one entry previously represented by AIZ's results
wait adjustment, reducing that adjustment from three to two.

## Validation

The standalone AIZ frontier advances from f8215 to f8837 with 1,200 errors.
The complete-run AIZ acceptance frontier remains f26107 with 26 errors, so
the physical sign lifecycle improvement does not regress the later route.

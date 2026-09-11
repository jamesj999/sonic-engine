# Object-side pushing-bit clears outside `Solid_NotPushing`

Point-in-time audit, 2026-08-19, against `origin/develop` `966ffada6`.

## Why this exists

A solid object carries per-character "pushing" bits in its **own** status byte:
`p1_pushing_bit`/`p2_pushing_bit` in S2 and S3K, and bit 5 of `obStatus` in S1
(single character). The canonical clear is `Solid_NotPushing` — S2
`docs/s2disasm/s2.asm:35484-35488`, S1
`docs/s1disasm/_incObj/sub SolidObject.asm:261-263`, S3K `sub_1E0C2`
`docs/skdisasm/sonic3k.asm:41533-41537`.

Those bits are not bookkeeping. `SolidObject_TestClearPush` gates its
`move.w #(Walk<<8)|Run,anim(a1)` animation-restart write on
`btst d4,status(a0)` — the **object's own** bit
(`docs/s2disasm/s2.asm:35462-35466`). An object that clears its own bit inside
its routine therefore suppresses the next frame's restart write.

The engine's model of those bits is `ObjectSolidContactController`'s
`objectPushingBitSet`. Before this audit it was written **only** by that class:
no object could clear its own bit, so every ROM site below was unmodelled. That
is why the CPZ2 push-gate correction could not land — see
`docs/status/trace-frontier-log.md`, 2026-08-19 round 5.

Sites were found by grepping each disassembly for `bclr` of the pushing bits on
`status(a0)`/`obStatus(a0)`, plus every dynamic `bclr dN,status(a0)` traced back
to the `moveq` that loaded the bit number, plus mask/whole-byte writes
(`andi.b`, `move.b #0`, `clr.b`) that drop those bits.

**Excluded as player-side, not object-side:** `bclr #5,obStatus(a0)` throughout
`docs/s1disasm/_incObj/01 Sonic.asm` and `_incObj/Sonic AnglePos.asm`, and
`bclr #5,status(a0)` at `docs/skdisasm/sonic3k.asm:24747`. In all of those `a0`
is the character, so bit 5 is the player's own pushing flag.

## Sonic 2

| ROM site | Object | Bits | Shape | Engine | Ported |
|---|---|---|---|---|---|
| `s2.asm:29443`, `:29449` | `Obj36_Sideways` — sideways retracting spikes | p1 / p2 **separately**, each in its own character branch | after `Touch_ChkHurt2`, on that character's `touch_side` bit; **no** player-side clear | `SpikeObjectInstance` (via `AbstractSpikeObjectInstance`) | **No — blocked**, see "What is still blocked" |
| `s2.asm:34074-34075` | `Obj41` horizontal spring, `loc_18BAA` | both, unconditional | launch tail; also clears the character's flag | `SpringObjectInstance` | Yes (landed 2026-08-19, `27f83a42e`) |
| `s2.asm:50544` | `Obj45` OOZ pressure spring, `loc_243EA` | p1 or p2 by branch | **test-and-clear**: the following `beq` abandons the launch when it was already clear | `OOZSpringObjectInstance` | Yes |
| `s2.asm:53393-53394` | `Obj66` MTZ yellow spring walls | both, unconditional | launch tail; also clears the character's flag | `MTZSpringWallObjectInstance` | Yes |
| `s2.asm:55775`, `:55781` | `Obj76_Main` — MCZ sliding spike block | p1 / p2 separately | same shape as `Obj36_Sideways`; **no** player-side clear | `SlidingSpikesObjectInstance` | **No — blocked** |
| `s2.asm:25691` | `Obj26_SpawnIcon` — monitor break | both, via wholesale `clr.b status(a0)` | break path; the block above separately clears each touching character's flag | `MonitorObjectInstance` | Yes |

Checked and **not** sites: `andi.b #~standing_mask,status(a0)` at `:22830`,
`:49278`, `:49427`, `:51046`, `:55914` clear standing bits only; every other
`bclr dN,status(a0)` in `s2.asm` resolves to a *standing* bit.

## Sonic 1

Single character, so every site is the one bit.

| ROM site | Object | Shape | Engine | Ported |
|---|---|---|---|---|
| `_incObj/41 Springs.asm:155` | Obj41 horizontal spring, `.clearPush` | also clears Sonic's flag | `Sonic1SpringObjectInstance` | Yes |
| `_incObj/66 SBZ Rotating Junction.asm:87` | Obj66 junction grab | also clears Sonic's flag | `Sonic1JunctionObjectInstance` | Yes |
| `_incObj/3C GHZ, SLZ Smashable Wall.asm:68` | Obj3C smashable wall | also clears Sonic's flag in ROM; the **engine clears neither** | `Sonic1BreakableWallObjectInstance` | **No — blocked** |
| `_incObj/72 SBZ Teleporter.asm:83` | Obj72 SBZ teleporter | also clears Sonic's flag in ROM; the engine models bit 5 here as `setOnObject(false)`, which is bit 3 | `Sonic1TeleporterObjectInstance` | **No — blocked**, and see "Defects found" |
| `_incObj/26, 2E Monitors and Power-Ups.asm:163` | Obj26 monitor `.stoppushing` | this is the monitor's own inline copy of `Solid_NotPushing`/`TestClearPush`, reached from `.checkpush` | framework: `SolidRoutineProfile.monitorSolidity()` in `ObjectSolidContactController` | n/a — framework, not an object-routine site |
| `_incObj/sub SolidWall.asm:52` | shared `SolidWall` helper `.no_collision`/`.air` | same: a sibling of `Solid_NotPushing` | framework | n/a |
| `_incObj/56 SYZ, SLZ Floating Blocks and LZ Doors.asm:515` | Obj56 `FBlock_SLZStair_MoveSquare` | **incidental**: `addq.b #1` then `andi.b #3` cycles the flip bits and drops bit 5 as a side effect, at every oscillation corner, regardless of contact | `Sonic1FloatingBlockObjectInstance` | **No — recorded**, see below |

`_incObj/22, 23 Badnik - Buzz Bomber and Missile.asm:172` and
`_incObj/17 GHZ Spiked Pole Helix.asm:26` also drop bit 5, but on non-solid
objects at init; not push sites.

## Sonic 3 & Knuckles

All citations `docs/skdisasm/sonic3k.asm` (S&K half).

| ROM site | Object | Bits | Engine | Ported |
|---|---|---|---|---|
| `:44189`, `:44202`, `:44233` | `Obj_AIZLRZEMZRock` | p1 / p2 by branch | `AizLrzRockObjectInstance` | Yes |
| `:45711`, `:45848`, `:45933` | `Obj_BreakableWall`, Player_1 legs of `loc_21568` / `loc_2172E` / `loc_21818` | p1 | `BreakableWallObjectInstance` | Yes |
| `:45720`, `:45739`, `:45858`, `:45871`, `:45942`, `:45954` | same object, Player_2 legs | p2 | `BreakableWallObjectInstance` | Yes |
| `:47950-47951` | `sub_23190`, shared launch tail of `Obj_Spring_Horizontal` and `Obj_2PSpring_Horizontal` | both, unconditional | `Sonic3kSpringObjectInstance` | Yes |
| `:51930-51931` | `Obj_LBZPlayerLauncher`, `sub_261F2` | both | `LbzPlayerLauncherInstance` | Yes |
| `:53562`, `:53587`, `:53592` | `Obj_LBZPipePlug` | p2, p2, p1 — **test-and-clear**, and the p1 leg clears p2's bit first | `LbzPipePlugObjectInstance` | Yes |
| `:57246` | `Obj_AutoTunnelInit` | **p1 only, even when the captured character is Player_2** | `AutomaticTunnelObjectInstance` | Yes, modelled as written |
| `:58249` | `Obj_LBZTubeElevatorClosed` | **p1 only**, same asymmetry | `LbzTubeElevatorInstance` | Yes, modelled as written |
| `:68818-68819` | `Obj_CGZTriangleBumpers`, `sub_32D16` | both (`bclr #5` + `bclr #6`) | `CnzTriangleBumperObjectInstance` | Yes |
| `:49066`, `:49073`, `:49160`, `:49167` | `Obj_Spikes`, `loc_240E2` and `loc_241DC` | p1 / p2 separately, on the swapped `d6` touch-side bits; **no** player-side clear | `Sonic3kSpikeObjectInstance` | **No — blocked** |
| `:71091`, `:71102` | `Obj_MGZMovingSpikePlatform` | p1 / p2 separately; **no** player-side clear; runs before the `$28` Y test | `MGZMovingSpikePlatformObjectInstance` | **No — blocked** |
| `:85939`, `:85951` | `Obj_SOZRisingSandWall` | p1 / p2, test-and-clear | **object not implemented** | No — object missing |

`Obj_HCZ2Wall` (`:106231`) and `Obj_ICZBreakableWall` (`:187693`) are separate
ROM objects from `Obj_BreakableWall` and carry no pushing-bit clear of their
own; their engine classes correctly have none.

## What is still blocked, and why

Every unported site above shares one property: **the ROM clears the object's
bit and does NOT clear the character's own pushing flag in the same block.**
Spikes, sliding spike blocks and the MGZ spiked platform hurt the character and
release only their own bit; the S1 smashable wall and teleporter do clear both
in ROM, but the engine models neither at those sites.

Porting those sites alone regresses the suite. Measured: adding them took
`-Ptrace-replay` from 790/4 to 790/8, with `TestS3kAizTraceReplay`,
`TestS3kCnzTraceReplay`, `TestS3kMgzTraceReplay` and
`TestS3kReplayReferenceClosureIntegration` newly red on `status_byte`
`expected=0x0000 actual=0x0020` — the character left holding `Status_Push`.

The cause is the defect this audit exists to unblock. `ObjectSolidContactController`
gates its player-side clear on `clearObjectPushingBit` succeeding, i.e. it uses
the object latch as a proxy for *"was I the object that set the player's flag?"*.
Make the latch more accurate and that gate strands the flag: the object released
its own bit early, so the controller's later pass finds no latch and never clears
the character. Under ROM semantics the two are independent —
`Solid_NotPushing`'s `bclr #status.player.pushing,status(a1)` is ungated.

So the sequencing in the round-5 write-up was not quite right. Object-bit-only
sites and the ungated player-side clear are **not** separable: they must land
together. Sites where the ROM clears both bits in the same block are separable,
and those are what landed here.

## Defects found in passing, not changed here

1. `MTZSpringWallObjectInstance` takes the **fixBugs-ON** branch: it calls
   `player.setRollingJump(false)` for the REV01 roll-jumping fix at
   `s2.asm:53395-53399`, which is inside `if fixBugs` and therefore **not in the
   shipped ROM** (`fixBugs = 0`, `s2.asm:27`). Per the hard rule the engine must
   model the un-fixed path.
2. `Sonic1TeleporterObjectInstance` reads `bclr #5,obStatus(a1)` as the
   "object interaction bit" and models it with `setOnObject(false)`. Bit 5 is
   the pushing flag; on_object is bit 3.
3. `Sonic1BreakableWallObjectInstance` comments the two `bclr`s as "handled
   implicitly by the engine". Neither is.
4. `TestS3kSonicTailsCompleteEmeraldRunChain` fails **differently in two
   worktrees checked out at the same commit with no local changes** — one
   reports the `awaitBoundary` step-cap walk-failure, the other an
   `IllegalStateException: non-exportable pending hardware submission`. It
   survives `mvn clean`. Attributing a failure-mode change in that class to a
   code change is therefore unsound without a same-worktree control.

## Correction, 2026-08-19 — the firing conditions of the blocked sites are wrong

The catalogue above records where each `bclr` *exists*, and that part stands. It
also implies each site fires whenever its branch's touch-side bit is set, and for
the spike-family sites that is **wrong**, proven from committed fixture data.

The S3K fixtures carry a per-frame `object_state` aux event with the object's own
`status` byte, so whether a given object-side `bclr` fires is checkable directly —
no probe needed. Over `src/test/resources/traces/s3k/cnz`:

- `Obj_Spikes`' second routine (`loc_2413E`, docs/skdisasm/sonic3k.asm:48965) holds
  status `0x22` — `p1_pushing_bit` **set** — continuously across rows 1266-1271
  while the character pushes against it, and drops to `0x02` on row 1272, the same
  row the character's own `Status_Push` clears. That is the ordinary end-of-contact
  `sub_1E0C2` clearing both bits, not the object's own `bclr`.
- Across the whole route the spike's `p1_pushing_bit` drops four times and on **none**
  of them is the character still pushing.

So `Obj_Spikes`' `bclr #p1_pushing_bit,status(a0)` (:49066, :49073, :49160, :49167)
does **not** fire on a mere side touch. A port that fires it on every
`contact.touchSide()` clears a bit the ROM keeps, and the object then never reaches
`sub_1E0C2` to clear the character's flag — which is exactly the `status_byte`
`0x0000` vs `0x0020` regression those ports produced.

The same scan is the way to settle every remaining site. On
`src/test/resources/traces/s3k/mgz` it finds objects that genuinely do clear their
own bit mid-push, including `loc_21692` (docs/skdisasm/sonic3k.asm:45757), the
`Obj_BreakableWall` break routine already landed in `8cd07b700` — so the method
distinguishes real sites from mis-derived ones rather than rejecting all of them.

**Before porting any remaining site, derive its firing condition from the ROM's
`d6` mask semantics and confirm against the recorded `object_state` status byte
that the bit actually drops mid-push on some route.** Existence in the listing is
not evidence that the branch is reached.

### Revised status of the blocked set

| Site | Verdict |
|---|---|
| S3K `Obj_MGZMovingSpikePlatform` (:71091, :71102) | **Correct as ported.** With (1)+(2) it closes `TestS3kMgzTraceReplay` frame 31111 (`player_animation_id` `0x1A` vs `0x00`) and leaves AIZ, CNZ and the reference-closure test green. |
| S3K `Obj_Spikes` (:49066, :49073, :49160, :49167) | **Firing condition wrong**, per the CNZ evidence above. Needs re-derivation. |
| S2 `Obj36_Sideways` (:29443, :29449), `Obj76_Main` (:55775, :55781) | Same ROM shape as `Obj_Spikes`; treat as unverified until the same scan is run against S2 fixtures. |
| S1 smashable wall, SBZ teleporter | Untested since they were reverted with the spike group; no evidence either way. |

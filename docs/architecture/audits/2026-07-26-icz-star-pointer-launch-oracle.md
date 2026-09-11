# ICZ Star Pointer launch oracle

Date: 2026-07-26

Worktree: `.worktrees/integration-icz-next`

Baseline: `d73649824`

## Result

The ICZ complete-run divergence at frame 15940 was caused by Star Pointer's
parent routine beginning active movement one engine object pass earlier than
the ROM. The resulting `$40` subpixel phase error was inherited by a launched
orbiting point. At its exact `$FF80` velocity, that point crossed the integer
collision boundary one touch pass early and hurt Tails.

Preserving the object-owned `Obj_WaitOffscreen` to active-routine dispatch
phase advances the replay from 1,320 errors, first at frame 15940
`tails_x_speed`, to 32 errors, first at frame 16361 `y_speed`.

## Reference capture

The diagnostic used `tools/bizhawk/diag_template_fast.lua` conventions:
headless fast-forward, semantic game-mode/zone polling before hooks, hook
unregistration, output close, and self-exit. It did not write RAM, inputs, or
states.

Inputs:

- Movie: `src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2`
- ROM: `Sonic and Knuckles & Sonic 3 (W) [!].gen`
- Movie metadata offset: 138117
- Probe SHA-256:
  `da48c40e3c33feccbae4c75618555fa8d24f6d448b15eea804d77adc9e45be51`

Invocation:

```bash
BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
OGGF_START=<start> OGGF_STOP=<stop> OGGF_OUT=<output> \
tools/bizhawk/run_bizhawk_lua.sh /tmp/icz_star_pointer_probe.lua \
src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2 \
'Sonic and Knuckles & Sonic 3 (W) [!].gen'
```

Captured output hashes:

- Narrow orbit/launch window:
  `e42eb910da6d65bcbdd53e82466e6cec96b916f87c74ff75c5e5d795a5859ca2`
- Wider launch/order window:
  `ce09c90681afe3d2082a4154147f9fed77a9a84dd32ac284b415f7e90cbc70af`
- Admission/activation window:
  `86c0ec31314c27ce98c0af4a74762b173bea634715007e69eb355c74d09a3de8`

## ROM oracle

Relevant locked-on ROM PCs and disassembly labels:

- `Obj_StarPointer`: `$8BE2E`
- Parent active routine `loc_8BE74`: `$8BE74`; return `$8BE7A`
- Child active routine `loc_8BEE6`: `$8BEE6`
- Child angle test: `$8BF06`; launch write: `$8BF10`
- Circular path `loc_8BF3E`: `$8BF3E`
- `MoveSprite_CircularSimple`: call `$8BF44`; helper `$84C42-$84C70`
- Launched path `loc_8BF4C`: `$8BF4C`; `MoveSprite2` return `$8BF52`
- collision response list add: `$1040C-$1041C`
- `Touch_Process`: `$FF06`; `Touch_Loop` object pointer loaded at `$FF10`

Source references are `docs/skdisasm/sonic3k.asm:190760-190899` and the
corresponding listings around source lines 204775 and 217810.

At trace frame 15929 the ROM:

1. lets P1 and P2 touch child slot `$B534` at position `$0C44A000`;
2. decrements its angle from `$01` to `$00`;
3. writes launched routine `$8BF4C` and x velocity `$FF80`;
4. completes the same-pass circular refresh from parent `$0C440000`,
   writing child position `$0C440000/$04500000`;
5. republishes `$B534` into the collision response list.

The next touch positions are `$0C440000` at frame 15930,
`$0C3F0000` at frame 15940, and `$0C3E8000` at frame 15941. Thus the
ROM correctly presents integer x `$C3F` to Tails before the child moves on
frame 15940.

The parent starts at `$0C840000`. Its first observed `loc_8BE74` movement is
at trace frame 15674, changing it to `$0C83C000`; it reaches exactly
`$0C440000` on the launch pass. The engine admitted and initialized the
object correctly, but executed its first `$FFC0` movement on the immediately
following engine update, one dispatch earlier than this oracle. Its parent
therefore reached launch at `$0C444000`, and the launched child crossed the
`$C3F/$C3E` boundary one touch pass early.

## Rejected hypotheses

- Collision-response ordering is correct: both native players touch `$B534`
  before the child executes and republishes itself.
- Live versus previous collision-list position is not the discrepancy.
- Child launch velocity is the exact ROM `$FF80`.
- Circular trigonometry is correct. At angle `$3B`, the ROM sine outputs
  `$00FE/$001F`, scales them to `$000FE000/$0001F000`, and adds them to
  parent `$0C414000/$04400000`, producing
  `$0C512000/$0441F000`.
- The child launch fall-through correctly refreshes its circular position
  before launched movement begins.

## Verification

Baseline:

```bash
mvn -q '-Dsurefire.argLine=-Xshare:off -Xmx4g' \
  -Ds3k.rom.path=s3k.gen \
  -Dtest=com.openggf.tests.trace.s3k.TestS3kIczCompleteRunTraceReplay test
```

Result: 1,320 errors; first frame 15940 `tails_x_speed`
(`expected=-001A`, `actual=-0200`).

With the object-owned active-routine admission phase:

Result: 32 errors; first frame 16361 `y_speed`
(`expected=0x0093`, `actual=-006D`).

The 13-test Star Pointer suite also covers offscreen re-entry through
initialization, single child-set spawning, the one-pass routine transition,
continued movement without respawning, and a production rewind snapshot
captured after initialization but before the active pass is consumed.
`TestRewindCoverageGuard`, `TestRewindRoundTripProbe`, and
`TestArchitecturalSourceGuard` pass together.

The remaining frame-16361 divergence is a separate frontier and was not used
to broaden this fix.

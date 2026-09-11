# Sonic 2 special-stage ROM initialization timeline

This is the ROM reference for the engine's special-stage pre-roll. The source
of truth is `docs/s2disasm/s2.asm`; the committed trace is a verification of
observable state, not a source for timing constants.

## Result

`Sonic2SpecialStageIntro.PRE_ROLL_FRAMES` must be **23**.

The causal basis is **one mode-entry observation plus 22
`Pal_FadeToWhite` VInts**. The recorder writes raw f0 immediately when it first
observes `Game_Mode=$10`, before the main loop dispatches `SpecialStage`; the
22 fade waits then occupy raw f1-f22. This is why the replay has 23 absent/zero
samples before its first non-lag observation after synchronous loading.
`PRE_ROLL_FRAMES` models only that observable empty-player/zero-speed window;
it does not mean the full ROM startup is complete, because the track waits,
control/DMA wait, fade from white, and `SpecialStage_Started` gate follow it. It
is an engine/replay frame count, not a claim that all 23 samples are physical
ROM VInts.

Three terms must remain distinct:

- **Raw CSV frame** is the physical BizHawk capture row. The ROM spends many
  physical VInts in synchronous decompression and PLC work; these are correctly
  present as lag rows.
- **Physical VInt** is the interrupt actually serviced by the ROM during a raw
  emulator frame. Raw f0 is only the recorder's mode-entry observation; it is
  not one of the special-stage routine's fade VInts.
- **Replay step** is the trace-paced, non-lag clock used by
  `AbstractS2SpecialStageTraceReplayTest`. It skips CSV rows whose `lag` column
  is 1. This is the clock the engine pre-roll models.

The recorder contract is explicit at
`tools/bizhawk/s2_ss_trace_recorder.lua:371-382`: the frame that first shows
`Game_Mode=$10` is recorded immediately and no initial frame is skipped.

## Raw-frame / ROM-phase timeline

`Pal_FadeToWhite` initializes `d4=$15` and uses `dbf`, so its loop executes 22
times (`s2.asm:3570-3582`). Each iteration selects `VintID_Fade`, waits for one
VInt, updates the palette, and services the PLC.

| Raw CSV frame(s) | ROM phase / VInt handler | Observable RAM effect |
|---:|---|---|
| f0 | **No special-stage init VInt yet.** Recorder observes the `Game_Mode=$10` transition before `SpecialStage` dispatch. | Player slots empty; `SS_Cur_Speed_Factor=0`; `SSTrack_anim_frame=0`. |
| f1-f22 | 22 `VintID_Fade` interrupts for `Pal_FadeToWhite` (`s2.asm:3573-3580`; call at `:6546`). | Players, current speed, and track frame remain zero. |
| f23-f68 | **Disassembly-and-lag-derived inference:** post-fade synchronous initialization. After interrupts are restored at `s2.asm:6613`, the disassembly leaves `Vint_routine=0`, whose dispatch is `Vint_Lag` (`s2.asm:483-484`, `:529-543`), while buffer initialization/decompression/PLC work proceeds (`:6613-6627`). The artifact records lag state and RAM, not PC or `Vint_routine`, so it does not directly prove the handler for each row. | Rows are `lag=1`; player ids are still zero. Raw f23 is the first post-fade lag row and is skipped by replay; inferring `Vint_Lag` is consistent with the disassembly but is not direct capture evidence. |
| f69-f126 | **Same inference, later init phase:** the Obj09/Obj10 id stores (`s2.asm:6628-6634`) have become visible, followed by background/player-art/palette setup and the `SS_New_Speed_Factor` write (`:6635-6640`). No explicit S2SS wait is armed until `:6644`. | Player ids are visible from f69, but their routine and `ss_x_pos`/`ss_y_pos`/`ss_z_pos`/angle fields remain zero because `RunObjects` has not executed them. Rows remain `lag=1`; recorded `SS_Cur_Speed_Factor` stays zero. The artifact does not capture the exact PC at the f69 transition. |
| f127-f131 | Five `VintID_S2SS` interrupts in the drawing-index wait (`s2.asm:6644-6649`). | Obj09/Obj10 are present but still routine 0 with zeroed special-stage fields. Drawing index starts at 0; the five interrupts leave it at 1, 2, 3, 4, then back at 0. At f127 new speed is promoted to current (`s2.asm:960-975`), so `speed_factor` becomes `0xC`; `track_anim_frame` becomes 1 when the index wraps at f131. |
| f132-f136 | Five `VintID_S2SS` interrupts in the duration wait (`s2.asm:6651-6658`). | Obj09/Obj10 remain present and zeroed through f135. At f136 the second wrap advances `track_anim_frame` to 2, the wait exits, and the first `RunObjects` initializes Sonic then Tails (`:6660-6663`, `:69040-69076`, `:70319-70372`). |
| f137 | The one `VintID_CtrlDMA` wait at `s2.asm:6665-6666`. | Raw row is lagged and replay skips it. |
| f138-f159 | 22 `VintID_Fade` interrupts for `Pal_FadeFromWhite` (`s2.asm:3460-3483`, call at `:6672`). | Obj09/Obj10 remain initialized and present, but are frozen because the fade handler does not run objects or track animation. |
| f160 onward | `VintID_S2SS` intro/gameplay-gate loop (`s2.asm:6674-6690`). | Before the wait, the loop copies physical controls to the logical control words (`:6675-6676`); after the wait it updates the track and calls `RunObjects` (`:6681-6687`) before testing `SpecialStage_Started` (`:6689-6690`). Because the recorder samples at the VInt boundary, raw f160 shows the first drawing-index update while player fields are still at their initialized values; the post-wait `RunObjects` mutations first appear on lag row f161 and the next compared row f162. The loop cannot enter the playable body while that byte is zero. |

## Frame-by-frame replay observations

This table uses the comparator's non-lag step number. A replay step samples the
result of a physical frame but is not itself synonymous with a ROM VInt.

| Replay step | Raw frame | Sampled ROM phase | Observable committed-trace effect |
|---:|---:|---|---|
| 0 | f0 | Mode-entry observation; `SpecialStage` has not yet dispatched. | Players absent; current speed and track frame zero. |
| 1 | f1 | `Pal_FadeToWhite` iteration 1 (`s2.asm:3573-3580`). | No tracked init-state change. |
| 2 | f2 | Fade iteration 2 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 3 | f3 | Fade iteration 3 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 4 | f4 | Fade iteration 4 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 5 | f5 | Fade iteration 5 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 6 | f6 | Fade iteration 6 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 7 | f7 | Fade iteration 7 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 8 | f8 | Fade iteration 8 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 9 | f9 | Fade iteration 9 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 10 | f10 | Fade iteration 10 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 11 | f11 | Fade iteration 11 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 12 | f12 | Fade iteration 12 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 13 | f13 | Fade iteration 13 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 14 | f14 | Fade iteration 14 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 15 | f15 | Fade iteration 15 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 16 | f16 | Fade iteration 16 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 17 | f17 | Fade iteration 17 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 18 | f18 | Fade iteration 18 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 19 | f19 | Fade iteration 19 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 20 | f20 | Fade iteration 20 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 21 | f21 | Fade iteration 21 (`s2.asm:3575-3580`). | No tracked init-state change. |
| 22 | f22 | Fade iteration 22; `dbf` falls through and the fade returns (`s2.asm:3575-3582`). | Last absent/zero pre-roll sample. |
| 23 | f127 | First `VintID_S2SS` drawing-index wait (`s2.asm:6644-6647`). | Obj09/Obj10 first compare as present but routine 0 and zeroed; `speed_factor` 0→`0xC`; drawing index 0→1, duration 0→5. |
| 24 | f128 | Second drawing-index wait. | Players remain present/zeroed; drawing index 1→2; duration 5→4. |
| 25 | f129 | Third drawing-index wait. | Players remain present/zeroed; drawing index 2→3; duration 4→3. |
| 26 | f130 | Fourth drawing-index wait. | Players remain present/zeroed; drawing index 3→4; duration 3→2. |
| 27 | f131 | Fifth drawing-index wait; index wraps and loop exits (`s2.asm:6644-6649`). | Players remain present/zeroed; `track_anim_frame` 0→1; duration 2→1. |
| 28 | f132 | First duration-loop wait (`s2.asm:6651-6658`). | Players remain present/zeroed; drawing index 0→1; duration reloads 1→5. |
| 29 | f133 | Second duration-loop wait. | Players remain present/zeroed; drawing index 1→2; duration 5→4. |
| 30 | f134 | Third duration-loop wait. | Players remain present/zeroed; drawing index 2→3; duration 4→3. |
| 31 | f135 | Fourth duration-loop wait. | Players remain present/zeroed; drawing index 3→4; duration 3→2. |
| 32 | f136 | Fifth duration-loop wait; loop exits and reaches the first `RunObjects`. | `track_anim_frame` 1→2; Obj09 initializes first, then Obj10; both routines become 2 with their ROM positions/depth/angle. |
| 33-54 | f138-f159 | 22 `Pal_FadeFromWhite` waits (`s2.asm:3460-3483`, call at `:6672`). | Initialized players remain present but frozen; track animation stays paused. |
| 55 onward | f160 onward | Intro/gameplay-gate loop (`s2.asm:6674-6690`). | Physical controls are copied to logical controls before the wait; track/object updates and `RunObjects` occur before `SpecialStage_Started` gates entry to the playable body. The f160 recorder sample is taken after the VInt but before that iteration's post-wait `RunObjects` result becomes observable; raw f161 carries the result but is lagged, so replay next compares it at f162. |

## Initialization work between fade and player creation

After `Pal_FadeToWhite` returns, the ROM performs the following work in order:

1. It configures the special-stage VDP registers and masks interrupts
   (`s2.asm:6547-6571`), then DMA-fills the name tables and horizontal-scroll
   table (`:6578-6581`).
2. It clears `SpecialStage_Started` (`s2.asm:6585`) and clears the sprite,
   special-stage shared, display-list, and object RAM regions (`:6587-6603`).
   Thus no stale object id can make a player present during the pre-roll.
3. It restores interrupts, builds the scroll/name-table buffers, decompresses
   the special-stage data, resets the segment, and runs the special-stage PLC
   directly from ROM (`s2.asm:6613-6620`). `ssInitTableBuffers` and
   `RunPLC_ROM` contain no `WaitForVint`; the expensive decompression/PLC work
   nevertheless spans physical VInts. Because the preceding wait leaves
   `Vint_routine=0`, the disassembly implies `Vint_Lag` dispatch during this
   interval. The trace's lag rows support that reading, but do not prove it
   directly because the artifact records neither PC nor `Vint_routine`.
4. It writes the special-stage object ids: Obj09 (Sonic) at `s2.asm:6628`,
   Obj10 (Tails) at `:6631` when selected, then HUD/banner/ring-count objects at
   `:6632-6634`. These stores make the players present but do not execute their
   routine-0 initializers.
5. It builds the background, decompresses player art, initializes palettes and
   stage data, and writes `SS_New_Speed_Factor=$000C0000`
   (`s2.asm:6635-6640`). This is the *new* factor; the recorded current factor
   remains zero until the next `Vint_S2SS` copies it.

The PLC/track startup waits are therefore downstream of player-id creation, but
upstream of player routine initialization. The first loop waits five `VintID_S2SS`
interrupts for `SSTrack_drawing_index` to return to zero
(`s2.asm:6644-6649`). The second waits another five interrupts for
`SSTrack_duration_timer-1` to reach zero (`:6651-6658`), calling
`SSObjectsManager` after each wait (`:6655`). That routine is strictly
streaming/allocation work: it returns unless the drawing index is 4 and the
segment changed, then allocates ring/bomb/message slots without executing their
object routines (`:6935-7001`). Only after the wait does the first `RunObjects`
scan execute Obj09 and Obj10 before the later allocated slots (`:6660-6663`,
`:29805-29846`). After object setup and
`RunPLC_RAM`, a single `VintID_CtrlDMA` wait occurs at `:6665-6666`, followed
by the 22-frame fade from white.

`SpecialStage_Started` remains clear through all of this. The start-banner
object sets it only after its countdown/message hand-off (`s2.asm:9734-9746`),
which releases the gate at `s2.asm:6689`. The current committed trace does not
record that byte directly; the campaign's recorder-extension task adds that
auxiliary transition evidence.

## Committed trace cross-check

The check below is against
`src/test/resources/traces/s2/special_stage/physics.csv.gz`, pinned by the
compressed artifact's SHA-256:
`de6174b8de48f4aa1541e654d9e334c0442183778cf1fbd42c4e749f499a43df`.

| Event | Replay/non-lag clock | Raw CSV evidence | ROM interpretation |
|---|---:|---:|---|
| Last absent/zero pre-roll sample | step 22 | f22 | End of the 23-slot pre-roll window f0-f22. |
| Sonic/Tails first present | step 23 | ids first appear during lag at f69; first compared observation is f127 | Object-id writes at `s2.asm:6628-6631`. |
| Sonic/Tails first initialized | step 32 | f136 | First `RunObjects` after both startup waits; Obj09 then Obj10 (`s2.asm:6660-6663`, `:29805-29846`). |
| `speed_factor` 0→`c` | step 23 | f127 | New factor written at `s2.asm:6640`, then promoted to current by the first `Vint_S2SS` (`:960-975`). |
| `track_anim_frame` first moves 0→1 | step 27 | f131 | Fifth VInt of the first drawing-index wait, when index 4 wraps to 0. |
| `track_anim_frame` moves 1→2 | step 32 | f136 | Fifth VInt of the duration wait. |
| Track VInt/draw pipeline resumes after fade from white | step 55 | f160 | First `VintID_S2SS` in the gated intro loop (`s2.asm:6679-6690`): drawing index becomes 1, duration reloads to elapsed 0, and track animation remains frame 2. |

The Stage-1 fade/cadence correction splits the VInt-owned duration countdown
from `SSTrack_Draw` animation advancement (`s2.asm:837-876,960-982,7026-7091`).
The first promoted VInt reloads duration to elapsed 0; subsequent startup waits
produce elapsed 1,2,3,4, and only the documented startup `SSTrack_Draw` calls at
drawing index zero advance frames 0→1 and 1→2. Fade therefore freezes frame 2,
drawing index 0, and elapsed duration 4. The f160 VInt reloads elapsed 0 and sets
drawing index 1 without advancing the track frame.

In the recurring loop, current `SSTrack_Draw`, decode/perspective, streaming, and
scroll remain synchronous. Only later `RunObjects`-phase publication (players,
Tails input history, intro objects, and active objects/collisions) is scheduled
for the next executed logical update. The logical word scheduled for that pass is
the previous physical P1/P2 sample copied before the current `WaitForVint`
(`s2.asm:6674-6680`); current-row input becomes the previous sample only after
the VInt schedules its pass. Sonic and Tails history therefore receive the same
delayed word. Frozen phases continuously replace this sample rather than OR-carrying
edges, so a press released before DROP cannot leak. If a button remains held across
a lag-skipped update, the next executed VInt derives one edge from the held transition
against the last executed held sample; unchanged held input does not repeat it. P2 is
level-only. Thus f160 still exposes Sonic
`ss_y=$80`, while the pending main pass publishes `$6E` on the next compared
update, matching raw lag f161/f162. Conversely, drawing index zero at non-lag f166
advances track frame 2→3 synchronously. Pending fixed-slot order is Sonic/Tails,
then the START banner, then later dynamic objects/collisions.

The f791 ring root confirms that recurring streaming and object execution are
one ROM loop even though they have distinct owners. `SSObjectsManager` allocates
the ring/bomb slots before `RunObjects` (`s2.asm:6679-6688,6935-6967`). Obj60 and
Obj61 routine 0 both set routine 2 and fall through immediately through the
normal depth, projection, animation, and collision paths
(`:70631-70665,70720-70752,70880-70923`). Therefore an Obj60 allocated at depth
`$00400000` while drawing index is 4 ends that allocation-associated object scan
at `$003F3334`; the next scheduled active pass applies the next ordinary depth
step. Startup duration-wait allocation remains different: the wait calls
`SSObjectsManager` without `RunObjects`, and the already-modeled final startup
scan owns its first execution. Marker-created emerald/message objects also keep
their own init routines rather than inheriting Obj60/Obj61 fallthrough.

`Obj61_TestCollision` orders the two players, tests the first, and returns carry
immediately on success after clearing the object's collision byte
(`s2.asm:70807-70877`). Its unsigned `ss_z_pos` comparison tests Sonic first only
when Sonic Z is strictly lower; Tails is first when Sonic Z is greater or equal.
The Obj60 caller increments exactly one player's counter and changes routine
(`:70753-70804`). The engine consequently uses the same candidate order, treats
collect or explode as a consumed transition, and stops the player scan after the
first success; a ring overlapping both players can no longer add twice.

The same collision routine uses object-specific half-open circular angle
intervals, not a single symmetrically inclusive threshold. Obj60 loads
`d6=$A`, while Obj61 loads `d6=8` (`s2.asm:70674-70676,70767-70769`). Its
ordinary path first rejects
`playerAngle >= objectAngle+d6`, then accepts
`playerAngle >= objectAngle-d6` (`s2.asm:70846-70877`), yielding
`objectAngle-d6 <= playerAngle < objectAngle+d6` with the corresponding wrap
branches. This matters for the f1331 paired rings: with Obj60's `d6=$A`, Sonic
at `$42` collects the `$48` ring but must reject the `$38` ring at the exact
positive boundary. On the next pass Sonic is at `$41`, so the second ring is
then legitimately collected. Modeling that byte comparison moves the report
from 1,311 errors at f1331 to 606 at f4845 without a timing offset.

The targeted PC-execute capture at `C:\tmp\s2ss-ring-probe.txt` (SHA-256
`CFF5570952D0D4319E6EB0086EFBBC2F00D6392F7999B618D61CDE90B53D6DD8`) verifies
those disassembly-owned transitions: trace f674 shows Obj60 routine 0→2 and
depth `$00400000`→`$003F3334` in one allocation-associated scan, while trace
f790 shows depth `$0005999A`→`$0004CCCD`, animation 7→8, collision clear, and a
single Sonic count increment before compared f791. The probe values are evidence,
not engine constants.

The later observations are exact only when replay follows completed `RunObjects`
passes rather than assuming one pass per non-lag VBlank. Recorder v1.3 uses the
shared `ReadJoypads` return `$1156` (filtered to completed P2 state `A0=$F608`
and the `Vint_S2SS` return PC `$88E`) plus `RunObjects_End` `$15FE4`
(`s2.asm:837-840,1361-1387,29805-29849`). Each pass identifies its exact current
and previous physical BK2 polls and queues forward to the first non-lag
observation, so an observation may
execute zero, one, or multiple engine updates. The VInt sample preceding the
`SpecialStage_Started` transition still reports zero; that transition pass stays
on startup VBlank pacing and the following already-started sample begins recurring
pass pacing. The committed artifact contains 2,990 recurring passes, 596
multi-pass observations, and 1,257 delayed bindings through `stage_finished`,
with none from the results tail. This removes the former f916 one-pass-ahead
mismatch. Replay replaces only the controller fields on the already-pending
owning pass before executing it, so the captured sample is not shifted to the
following pass. Held and pressed values are mapped from the identified BK2 rows;
the auxiliary held bytes are cross-checked diagnostics only, and no gameplay
state is hydrated. The last recurring pass and canonical `stage_finished` share
logical f5180; the flag rise is observed on raw f5181. Obj6F appears later as
`results_started` at f5219, and that uncompared tail remains in the recording.
The next independent frontier is
f1019 signed-coordinate comparison (`sonic_ss_x` 65530 versus -6).

The raw rows explain why a direct `frame`-column lookup appears different from
the required f23 boundary: raw f23-f126 are `lag=1` init rows and the replay
does not step the engine for them. Specifically, players are still absent
through raw f68, their ids are visible from raw f69, and current speed remains
zero through raw f126. The first non-lag row after that interval is raw f127,
which is replay step 23 and simultaneously exposes both players and current
speed `0xC`. There is no conflict between the disassembly and the committed
trace once the recorder's physical-frame clock and the replay's non-lag clock
are kept distinct.

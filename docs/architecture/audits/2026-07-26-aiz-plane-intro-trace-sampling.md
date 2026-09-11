# AIZ plane-intro trace sampling audit

## Question and result

The standard AIZ trace first diverges at row 719: the fixture reports player
X `$0040`, while the engine reports `$0050`. A temporary engine diagnostic
showed the AIZ plane-intro accumulator reaching zero during engine frame 718
and moving the player during frame 719. That initially suggested the engine
might be dispatching `Obj_AIZPlaneIntro` once too often.

An attended ROM probe disproved the specific inference that row alignment
itself demonstrates an extra dispatch. The ROM executes the first player-X
add during the frame-719 instruction interval, but the committed recorder
exposes its post-write value in physics row 720. The probe therefore provides
no evidence for an extra engine object dispatch; the generic engine/recorder
sampling lifecycle still requires the audit described below.

The initial setup `Process_Sprites` pass is independently established ROM
behavior and **must not be removed or skipped** to address this divergence.
See
[the initial Process_Sprites oracle](2026-07-26-s3k-initial-process-sprites-oracle.md).

## Capture identity and safety

The capture used BizHawk 2.11 GPGX, the committed AIZ BK2 at
`src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2`,
and the locked-on World ROM with SHA-1
`CFBF98C36C776677290A872547AC47C53D2761D6`.

The diagnostic is
`tools/bizhawk/probes/aiz_plane_intro_scroll_probe.lua`. It delegates directly
to the canonical `ProbeRuntime.run` contract. The shared runtime owns:

- unlimited framerate and 6400% speed;
- invisible emulation and disabled sound;
- delayed hook registration;
- hook removal, output flush/close, and emulator exit.

The probe artifact must be applied after the canonical probe-contract change
that introduces `tools/bizhawk/probes/probe_runtime.lua` and launcher-provided
`OGGF_BIZHAWK_PROBE_RUNTIME` (reviewed as commit `171067021` in the capture
workspace).

The probe is observation-only. It contains no emulated-memory writes, input
injection, savestate mutation, register mutation, fixture regeneration, or
frame-number execution selector. `emu.framecount()` is logged only as an
output label.

## Semantic arming and observed addresses

Before installing hooks, the probe polls a cheap semantic gate:

- level-mode family: low nibble of `Game_Mode` at `$F600` is `$C`;
- raw `Current_zone_and_act` at `$FE10` is `$0000` (AIZ1);
- fixed plane slot at `$B172` contains `Obj_AIZPlaneIntro` pointer
  `$00067472`;
- `Events_fg_1` at `$EEB6` is in unsigned range `$FFC0..$FFFF`;
- plane scroll-speed word `$40` at `$B1B2` is `$0010`.

After arming, snapshots include:

| Meaning | Address |
|---|---:|
| plane routine | `$B177` |
| plane `$40` / scroll speed | `$B1B2` |
| `Events_fg_1` | `$EEB6` |
| `Player_1+x_pos` | `$B010` |
| `Level_frame_counter` | `$FE04` |
| low word of `V_int_run_count` | `$FE0E` |

The execute hooks bracket
`AIZPlaneIntro_UpdateScrollVelocity`
(`docs/skdisasm/sonic3k.asm:135945-135957`):

| PC | Observation point |
|---|---|
| `$067A08` | routine entry, before loading `$40` |
| `$067A10` | sign branch after loading `Events_fg_1` |
| `$067A18` | post-store state on the negative accumulator path |
| `$067A1E` | post-add state on the player-X path |

The run terminates only after observing the accumulator become zero and the
first two subsequent player-X additions. This produced 18 records rather than
leaving hooks active for the remainder of the movie.

## Native instruction timeline

The following table describes state at the hooked ROM instructions. “Emu”
is BizHawk's output-only frame label; the two native counters make the
instruction phase explicit.

| Emu | Level | V-int | Native action | Post-state |
|---:|---:|---:|---|---|
| 1226 | `$01AB` | `$04A5` | add `$0010` to `$FFC0` | events `$FFD0`, X `$0040` |
| 1227 | `$01AC` | `$04A6` | add `$0010` to `$FFD0` | events `$FFE0`, X `$0040` |
| 1228 | `$01AD` | `$04A7` | add `$0010` to `$FFE0` | events `$FFF0`, X `$0040` |
| 1229 | `$01AE` | `$04A8` | add `$0010` to `$FFF0` | events `$0000`, X `$0040` |
| 1230 | `$01AF` | `$04A9` | nonnegative branch; add to player X | events `$0000`, X `$0050` |
| 1231 | `$01B0` | `$04AA` | nonnegative branch; add to player X | events `$0000`, X `$0060` |

The scroll-speed word is already `$0010` throughout this window. A later
routine-14 store of the same value is unrelated to the first movement and is
not part of the final probe's arming or termination contract.

## Recorder-row visibility

The fixture metadata declares `bk2_frame_offset: 511`, but an execute callback
is intra-frame evidence while `physics.csv` is a once-per-frame sample.
Counter matching, rather than subtracting frame labels alone, establishes the
visibility boundary:

| Native callback state | Committed physics row where that state is visible |
|---|---:|
| level `$01AB`, V-int `$04A5` | f716 |
| zero store at level `$01AE`, V-int `$04A8`, X still `$0040` | f719 |
| first player add at level `$01AF`, V-int `$04A9`, X `$0050` | f720 |
| second player add at level `$01B0`, V-int `$04AA`, X `$0060` | f721 |

Thus two statements are simultaneously true:

1. the ROM executes the first player add during the native f719 interval; and
2. the committed recorder first exposes the post-add value in row f720.

The engine reporting `$0050` while comparing row f719 is consistent with a
sampling/lifecycle-ordering mismatch. It is not, by itself, proof that the
engine dispatched the plane-intro object an extra time; the generic audit
below must locate the exact engine-side comparison boundary.

## Engine-side resolution

A canonical replay diagnostic localized the first state change before trace
row 0, not at the comparison boundary:

| Observation | Intro offset | Object dispatch count | Pending setup |
|---|---:|---:|---|
| fixture built, before replay bootstrap | `$E920` | 0 | yes |
| canonical replay bootstrap complete | `$E928` | 1 | no |

`Sonic3kAIZEvents.spawnIntroObject` both installed the fixed intro object and
called `intro.update(0, focused)` to emulate setup `Process_Sprites`.
`TraceReplaySessionBootstrap.applyBootstrap` then consumed the production-owned
pending `InitialObjectSetupLifecycle`, whose
`ObjectManager.runInitialS3kLoadThenExecutePass` dispatched that same live
object again. The event-local call was therefore an obsolete second owner
after the generic lifecycle landed.

The diagnostic drove rows f0 through f289 through the canonical
`TraceReplayFrameClosureDriver`: every row classified VBlank-only, consumed
exactly one BK2 row, preserved rewind-reference closure, and produced no
object dispatch or intro-offset change. A current-schema MGZ control passed
through the identical driver. This rules out the prefix skip and comparator
snapshot boundary as the source of the one-dispatch lead.

The correction removes only the event-local update. Object installation
leaves routine 0 and `Events_fg_1=$E918`; consuming the generic setup authority
once advances routine 2 and `$E920`; a second consume is inert. After 430
ordinary closures the accumulator is zero and player X remains `$0040`; the
next closure moves it to `$0050`.

Fresh standalone replay advances from f719 `x` with 1,331 errors to f2707
`tails_animation_id` with 1,277 errors. The AIZ complete-run control remains
at f26107 `x` with 26 errors because represented complete-run restoration
already discards fresh setup authority.

No trace hydration, recorder regeneration, comparison shift, frame/route
carve-out, or removal of the ROM-authoritative initial `Process_Sprites` was
used.

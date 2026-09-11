# LBZ2 post-boss ending audit — 2026-09-09

Scope: Sonic/Tails in locked-on S3&K, from the killing hit on
`Obj_LBZFinalBoss1` through `StartNewLevel $0700`. Big Arms is the Knuckles
branch in this ROM. Work is directly on `develop`, based on
`192cbeaa0b0f03c9f8338b50683f63452d901d08`; no worktree or branch switch.

## Source comparison

Primary reference: `docs/skdisasm/sonic3k.asm`, shipped `FixBugs=0`.

| Owner | Required behavior | Review/change |
| --- | --- | --- |
| `loc_735B6`, `loc_72B18` | Score 1000; disable collision; detach the bottom segment; sink one pixel until the next Y reaches camera+$140 | Existing defeat and sink branch retained. |
| `loc_72B34`, `loc_72B46` | Lock P2; signed $3F countdown (64 calls); wait for alive, grounded P1; ending pose/results/P2 watcher | Existing countdown retained; ending pose now also clears spin-dash and pushing. |
| `loc_72B96`, `loc_72BBC` | Wait for results completion; restore music and explosion PLC; $1F countdown (32 calls); restore controls and queue miniature art | Existing control/PLC owners retained. |
| `loc_72C0A`, `loc_72C3C` | Held input approaches camera+$A0; strict distance <4; Stop_Object; face right, hold Up, create two emitters | Removed forced WALK animation, which prevented ordinary pose selection; Stop_Object now clears Y speed too. |
| `loc_72D24`–`loc_72D92` | Emit every four calls; move horizontally for $41+1 calls, vertically for $57+1; spawn subtype-$C explosion controllers and one sequencer | Existing motion/countdowns retained. Random Y now uses the high word after SWAP, and RNG is consumed only after successful allocation. |
| `loc_72DEA`–`loc_72E36` | Increment count before branching; signal collapse on count 8; counts 1–23 allocate debris; count 24 enters $17F wait and decrements it immediately | Removed the 24th debris and extra wait dispatch. Miniatures allocate after the sequencer's slot. |
| `loc_72E54`, `RawAni_72E96` | Debris init consumes RNG: low three bits choose frame, high-word low byte chooses X; init returns without motion/draw | Corrected swapped RNG selection and deferred RNG/setup to the child dispatch. |
| `ObjDat3_73736`–`ObjDat3_7375A` | Debris uses palette 2, priority $300; miniature palette 1, priority $380; smoke palette 1, priority $300; all clear art high-priority bit | Corrected palette override, priority buckets (6/7), and plane priority. |
| `loc_72E9E`, `ChildObjDat_7380C` | Seven parts with signed offsets, fixed velocities/flips; load ending palette during each part's init; no movement/draw on init | Offsets/velocities already match; corrected init/palette timing. |
| `loc_72EF8`, `loc_72F2C` | Lead emits smoke on V_int_run_count & $F == 0; test Y > camera+$80 before moving | Preserved strict threshold and motion order; corrected random Y word and forward child allocation. |
| `loc_72F4C`–`loc_72FD8`, `byte_73864` | Smoke rotates on V_int_run_count & 3 == 0, lives $7F+1 calls; puff raw animation advances on its init pass | Replaced object-age clock, corrected child allocation and puff cadence (4/5/6 for three dispatches each, delete on tenth). |
| `loc_72C68`–`loc_72CC6`, `Animate_ExternalPlayerSprite` | $83 control; zero counters; maps $55/$59/$5A for six calls each; terminal zero on call 19 changes the boss routine; P2 still animates on that call | Replaced immediate phase advance and indefinite animation with the terminal callback. Preserved velocities during setup. Published actual external animation counters. No further animation calls while waiting for milestone B or falling. |
| `loc_5452E`, `LBZ2BGE_PlatformDetach`, `SpecialVInt_LBZ2WindowCopy`, `SpecialVInt_LBZ2ScrollAClear` | Arm copy without executing the new routine; 28 row copies plus one clear VBlank; detach gate runs when special VInt becomes zero | Added the separate clear boundary and prevented consuming the first copy on the arm dispatch. Detach now precedes acceleration/motion, including direct fall-through to final fall. |
| `loc_72CDA` | Transition when P1 Y >= camera Y+$120, using pre-event camera | Existing unsigned threshold and transition API retained. |

The longer P2 animation table does **not** imply that P2 finishes that table:
P1's terminal callback replaces the owning boss routine. The shipped
`FixBugs=0` setup clears the boss's animation byte instead of P2's; P2 is
cleared by the next `loc_72C9E` dispatch. Rendering flip remains separate from
status facing.

## Screenshot: grounded Sonic overlapping the platform

The supplied screenshot shows Sonic at map `$C4` with a grounded centre near
`$070C`, while the platform has moved upward. The old overlay captured the
upper Death Egg terrain region (`$4380,$0580`, 384 by 256 pixels) and applied
`Events_bg+$16` to both that overlay and the entire foreground. It omitted the
VDP window covering the standing platform at world Y=`$0720`.

`SpecialVInt_LBZ2WindowCopy` reads 64 cells per row, rotating Plane A columns
50..63,0..49, and copies 28 rows into the window. With the locked camera at
`($4390,$0668)`, these are screen-relative descriptors. `SpecialVInt_LBZ2ScrollAClear`
clears Plane A VRAM rows 18..23 and writes `$9285`: the window replaces the
foreground from screen Y=40 downward. It has no H/V-scroll input; transparent
window pixels reveal the background, not the displaced Plane A underneath.
The shared renderer now applies this split in low/high tile passes and the
foreground priority buffer. At detach completion, the next two VBlanks execute
`SpecialVInt_LBZ2ScrollAClear2` and `SpecialVInt_LBZ2WindowClear`; the latter
repeats window row 5 into the six hidden Plane A rows before disabling the window.
The event-side copied buffer has fixed dimensions so rewind sidecar length is
stable, and the GPU cache compares descriptor contents after restoration.

## Background deformation

`LBZ2_DeathEggDeform` subtracts launch displacement before scaling the relative
camera Y, then subtracts the distinct `Events_fg_3` one-shot origin latch.
The old handler subtracted foreground displacement a second time. The `$100`
origin latch takes effect on the following dispatch. Negative BG speed or
negative equilibrium delta substitutes `$7FFF` before all band calculations.
The waterline remap for positive deltas below `$40` reads/writes the same
intermediate table in forward order.

`loc_549A0` uses an unsigned wrapped word count and a byte offset
`(Level_frame_counter >> 1) & $7E`. Its predecrement reads can reach 96 words
before `LBZ_WaterWaveArray2`; these bytes now come from the ROM, starting at
`$4F778 - 96*2`, rather than a modulo lookup into the named table. Intermediate
words outside each dispatch's fills persist. `LBZ2BGE_Falling` stops deformation
altogether, holding the last table and cloud phase while the foreground camera
moves. The handler captures its retained table, origin latch, and phase for rewind.
A same-frame rebuild also restores the already-consumed one-shot shake rather
than advancing the table again; the regression test failed before this correction.

## ROM identity and limits

The existing root S1/S2 ROMs match the documented identities. The supplied
4 MiB S3&K ROM has CRC32 `0C06AA82`, SHA-1
`b711a909cce238ca4af3e517a2edca306228efa5`, **not** the reference identity in
`AGENTS.md`. It was used in place, without renaming or creating links.
The bytes at `$7386A`, `$73874`, `$72E96`, `$72EDA`, `$72EE8`, `$7380C`, and
`$73864` match the disassembly's reviewed animation, frame, velocity, and
child tables. This verifies those tables, not the whole ROM's identity.

Tests of state-machine boundaries and source review cannot establish
pixel-perfect parity of the full rendered sequence. The replay results below
must be read with their actual compared rows and first divergence; an earlier
route divergence is not evidence about an accurately reached finale.

## Validation

Maven uses Java 21 at `${JAVA_HOME}`, `-Dmse=off`,
and absolute paths to the existing root ROM files. The delivery runs use the
changed source/test file manifest `target/lbz-source-manifest.txt`, SHA-256
`99687b0532d86227645bb28ca4d94f38e07c43ab5277c1954a20afb4803754f3`,
on base commit `192cbeaa0b0f03c9f8338b50683f63452d901d08`, directly on `develop`.
New source and test files are included in that manifest. Command variables below
resolve to the unchanged local files:

```sh
absolute_existing_s1_rom="${PWD}/Sonic The Hedgehog (W) (REV01) [!].gen"
absolute_existing_s2_rom="${PWD}/Sonic The Hedgehog 2 (W) (REV01) [!].gen"
absolute_existing_s3k_rom="${PWD}/Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

### Focused gameplay, rendering, and architecture checks

```sh
mvn -Dmse=off "-Ds3k.rom.path=$absolute_existing_s3k_rom" \
  -Dopenggf.test.gl.native=true \
  -Dtest=TestArchUnitRules,TestForegroundWindowRendering,SwScrlLbzTest,TestSonic3kLbzLaunchSignals,TestLbzFinalBoss1Instance,TestLbzFinalBossKosOwnerRewind,TestSonic3kLbzRewindRoundTrip,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils \
  -Dopenggf.surefire.reports=target/lbz-delivery-focused-reports test -B
```

Completed: **141 tests, zero failures/errors/skips**, log
`target/lbz-delivery-focused.log`. The hidden native GL context required
access to the display socket; the sandbox's surfaceless EGL backend had no
suitable config. The pixel test reads back actual framebuffer pixels at three
foreground scroll offsets, checks transparent window cells, high-priority
mask output, and preservation of an existing scissor rectangle. This proves
the renderer's composition for the tested inputs; it is not a full-game
reference-video comparison.

The shake/rewind assertion in `SwScrlLbzTest` was run before the correction
and failed at packed scroll row 41 (`target/lbz-shake-regression.log`), then
passed in the above delivery run. The architecture guard caught the initial
window data type's placement in `game.render`; moving it to `graphics` resolved
the dependency without changing the guard baseline.

### Earlier integration measurements

The preceding full run (`target/lbz-complete.log`, source/test manifest SHA-256
`5ed49d4139b9a961b34df6ba9188f4a31ac4e718b918783ed12d951622d26254` in
`target/lbz-pre-rewind-source-manifest.txt`) completed **17,126 tests, 7 failures,
32 errors, 108 skips**. It had no missing report classes or fork-death markers
relative to the earlier run. Failing test names, exception types, and complete
failure messages were identical. They concern the missing secondary `s3k.gen`
donor and the supplied image differing from pinned audio-test ROM identity.
The sandbox GL skip is covered by the native-context run above.

The earlier replay (`target/lbz-replay.log`, its exact source-diff identity in
[the frontier log](../../status/trace-frontier-log.md)) completed **one test,
one failure, no errors/skips**, with **4,585 comparison errors, 46,075 total_frames**,
first at **row 23,533 / x_speed / expected $016F / actual $0200**. This reproduces
the documented Ribot frontier; it cannot certify an accurately reached finale.

The final-source integration results follow below. No skipped check is counted
as parity evidence.

### Final full suite

```sh
mvn -Dmse=off "-Dsonic1.rom.path=$absolute_existing_s1_rom" \
  "-Dsonic2.rom.path=$absolute_existing_s2_rom" \
  "-Ds3k.rom.path=$absolute_existing_s3k_rom" \
  -Dopenggf.surefire.reports=target/lbz-delivery-full-reports test -B
```

Completed **17,126 tests, 7 failures, 32 errors, 108 skips**, log
`target/lbz-delivery-full.log`. The report class set matches the preceding
complete run; there are no fork-death/corrupted-stdout markers. Failing test
names, exception types, and complete failure messages are identical.

Failures/errors are confined to `TestSonic2LivesHudDonation`,
`TestCrossGameFeatureProviderRefactor$S3kTailsDonationIntegration`,
`TestS3kAudioOracleFixtureContract`/`V2`, `TestS3kOracleRequestSidecarWiring`,
`TestS3kCompleteRunReferenceProducer`, and `TestS3kCompleteRunStateDecoder`.
They report the unconfigured secondary `s3k.gen` donor or reject the supplied
S3&K image's identity before the requested audio comparison. The donation
assertion fails while its donor cannot be opened. This is a failed full-suite
run, despite the passing ending and required S3K regression checks.

Skips include hardcoded `s2.gen` lookups despite the absolute test property,
GL initialization, optional reference audio/capture inputs, and disabled
performance probes. The new window pixel test's sandbox skip is separately
covered by its successful native GL execution in the 141-check focused run.

### Final structural guards

Completed **613 tests, zero failures/errors/skips**, log
`target/lbz-delivery-guards.log`, reports `target/lbz-delivery-guards-reports`.
Command: `mvn -Dmse=off -Pguards test -B`, the same three absolute ROM properties,
and `-Dopenggf.surefire.reports=target/lbz-delivery-guards-reports`.

The run used `LUA_BIN=/tmp/lua-5.4.8/src/lua` and prepended
`/tmp/openggf-lbz-pwsh` to `PATH`. Lua 5.4.8 was built from official lua.org
source, and PowerShell 7.4.6 extracted from its official release under `/tmp`.
`XDG_CACHE_HOME`, `XDG_CONFIG_HOME`, and `XDG_DATA_HOME` were respectively
`/tmp/openggf-lbz-pwsh-cache`, `/tmp/openggf-lbz-pwsh-config`, and
`/tmp/openggf-lbz-pwsh-data`, so the guard's PowerShell process could initialize
inside the sandbox. No guard baseline or assertion was relaxed.

### Final LBZ zone-slice replay

```sh
mvn -Dmse=off -Ptrace-replay -Dsurefire.forkCount=1 \
  -Dsurefire.runOrder=alphabetical -Dtest=TestS3kLbzZoneSliceTraceReplay \
  "-Ds3k.rom.path=$absolute_existing_s3k_rom" \
  -Dopenggf.surefire.reports=target/lbz-delivery-replay-reports test -B
```

Completed **one test, one failure, no errors/skips**, log
`target/lbz-delivery-replay.log`. The freshly written JSON reports **4,585
comparison errors, zero warnings/bootstrap errors, 46,075 total_frames**;
first mismatch **frame 23,533, `x_speed`, expected `$016F`, actual `$0200`**.
Report: `target/trace-reports/trace/s3k_lbz1-single-439d8056b9f465ee.json`.
This matches the earlier recorded frontier and total exactly. It is evidence
that this replay's frontier did not move, not proof of whole-sequence parity.
A matched reference ROM and a replay reaching the finale without earlier
state divergence remain necessary for that claim.

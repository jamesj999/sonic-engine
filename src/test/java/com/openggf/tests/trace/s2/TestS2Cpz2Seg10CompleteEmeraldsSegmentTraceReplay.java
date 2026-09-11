package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of {@code seg10_cpz2} — the CPZ act 2 level segment the run
 * resumes after {@code ss_6} (bk2 offset 82342, 7088 rows), manifest segment 15
 * as {@link com.openggf.tests.trace.runs.TestS2CompleteEmeraldRunChain} counts
 * them.
 *
 * <p>Exists for the same reason as
 * {@link TestS2Cpz1Seg8CompleteEmeraldsSegmentTraceReplay}: the chain's
 * per-segment comparator report is only written when a segment closes, so a
 * divergence bad enough to kill the player is never reported there — it
 * surfaces only as "segment 15 lost production ownership before source
 * closure" with no frame and no field.
 *
 * <p><b>Status (landed red, deliberately).</b> 2491 errors, 0 bootstrap
 * errors, over 7088 rows. The collision-path root cause below is fixed: with
 * the regenerated fixture's entry {@code top_solid_bit} seeded, the frame-394
 * divergence is gone, {@code x}/{@code y} stay exact to frame 6600/6611, and
 * the first main-player physics divergence is frame 2252 ({@code air} rom 1,
 * engine 0). What remains is the S2 art-loading frontier: 2302 of the 2491
 * errors are {@code dynamic_art.*} and the first error overall is frame 52
 * {@code queue.s2_nemesis_plc.busy} (expected false, actual true).
 *
 * <p><b>Root cause, part 1 -- collision path (fixed).</b> {@code AnglePos}
 * points {@code Collision_addr} at {@code Secondary_Collision} and passes
 * {@code d5 = top_solid_bit} whenever {@code top_solid_bit != $C}
 * (docs/s2disasm/s2.asm:43005-43011). The engine used to enter this segment on
 * the primary path because the aux recorder read {@code top_solid_bit} from SST
 * offsets +0x46/+0x47, which are S3K's; S2's player SST is 0x40 bytes and
 * defines {@code top_solid_bit = $3E} (s2.constants.asm:70-71), so
 * PlayerBase+0x46 landed in the *sidekick* slot and every installed fixture
 * carried out-of-slot bytes. With the offsets corrected the recapture reads
 * {@code $0E} at this segment's entry and {@code $0C} at seg8/seg9 entry --
 * the save/restore behaviour of {@code Obj79_SaveData} / {@code Obj79_LoadData}
 * (s2.asm:44740, 44787) over {@code Obj01_Init}'s {@code $C} default
 * (s2.asm:36192-36199). {@code TraceReplaySessionBootstrap
 * .seedSegmentEntrySolidBits} seeds that entry pair for a metadata-start
 * segment; path selection stays engine-derived thereafter. The frame-394
 * divergence is gone and {@code x}/{@code y} are exact to frame 6600/6611.
 *
 * <p><b>What is left in this lane.</b> The S2 art-loading frontier: 2302 of the
 * 2491 errors are {@code dynamic_art.*}, in two clusters (frames 1725-1733 and
 * 5554 to the end of the segment). The 5554 cluster begins with one edge the
 * engine submits and the ROM does not, after which every {@code edge_ordinal}
 * and {@code transfer_id} is skewed and the rest cascades. The first error
 * overall is frame 52 {@code queue.s2_nemesis_plc.busy}: the fingerprints match
 * exactly, but the engine starts draining the entry Nemesis PLC queue two
 * frames before the ROM does (ROM busy 54-97, engine 52-95) -- a phase offset,
 * not a content mismatch.
 *
 * <p><b>What this lane does NOT explain -- correction.</b> An earlier revision
 * of this note said the chain's segment-15 failure was the same collision-path
 * defect reached from upstream, i.e. "the CPZ act 2 plane switcher that should
 * leave the player on path 2 before the star post is hit". That is refuted by
 * direct measurement on the chain:
 * <ul>
 *   <li>chain segment 13 is {@code seg9_cpz2} -- CPZ act 2 from level start
 *       through the star post -- and it closes with 0 comparator errors over
 *       5837 frames, which is impossible on the wrong collision path;</li>
 *   <li>instrumenting {@code CheckpointState.savePlayerSolidBitsIfPresent} and
 *       {@code LevelManager}'s checkpoint solid-bit restore shows the chain
 *       saving {@code top=$0E lrb=$0F} at the CPZ act 2 star post and restoring
 *       {@code $0E/$0F} on the special-stage return, and the player still holds
 *       {@code $0E} at the point it diverges.</li>
 * </ul>
 * The chain's real segment-15 divergence is at segment frame 210, x
 * {@code 0x1268}, with both sides airborne and rolling on path 2: the ROM
 * descends {@code y 0x0591 -> 0x0592 -> 0x0593} while the engine reverses to
 * {@code 0x058F} and then oscillates around {@code y 0x058E} with
 * {@code y_vel} repeatedly flipping from positive to roughly {@code -0x1E0}
 * every nine frames. The recorded {@code object_near} rows at those frames show
 * the CPZ Grabber cluster -- {@code ObjA7}/{@code ObjA8}/{@code ObjA9}/
 * {@code ObjAA} (s2.asm:30089-30092) -- parked at x {@code 0x1282}. The engine
 * eventually dies of {@code SPIKE} at {@code (0x174A,0x07CC)}, which is what
 * surfaces as "segment 15 lost production ownership before source closure ...
 * BK2 cursor=83819". This lane cannot reproduce that divergence, because at
 * segment frame 210 the lane is exact; the discriminator is entry state the
 * chain carries and the metadata start does not.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Cpz2Seg10CompleteEmeraldsSegmentTraceReplay extends AbstractTraceReplayTest {

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_2;
    }

    @Override
    protected int zone() {
        return Sonic2ZoneConstants.ZONE_CPZ;
    }

    @Override
    protected int act() {
        return 1;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "seg10_cpz2");
    }
}

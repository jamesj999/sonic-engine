package com.openggf.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.level.objects.RomObjectSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An event from the auxiliary trace file (aux_state.jsonl).
 * Events are frame-keyed and only written for notable moments.
 */
public sealed interface TraceEvent {

    Set<String> KNOWN_GENERIC_NATIVE_EVENTS = Set.of(
            "state_snapshot",
            "cursor_state",
            "slot_dump");

    int frame();

    record ObjectAppeared(int frame, int slot, String objectType, short x, short y)
        implements TraceEvent {}

    record ObjectRemoved(int frame, int slot, String objectType)
        implements TraceEvent {}

    /**
     * Per-frame snapshot of an object near the player, emitted by the S1 recorder
     * for any active object within the proximity window. Diagnostic only: replay
     * must never hydrate engine object state from these values.
     *
     * <p>{@code routine2} (ob2ndRout, object offset +0x25) and {@code objoff3c}
     * (objoff_3C at +0x3C, the 32-bit generic timer / sub-pixel accumulator) are
     * v3.8+ optional fields. {@code objoff34}/{@code objoff36}/{@code objoff38}
     * (per-object counter/timer/sub-state words at +0x34/+0x36/+0x38) are v3.9+
     * optional fields. {@code objoff32} (per-object timer word at +0x32; for maker
     * objects this is gmake_timer, e.g. the MZ2 Lava Geyser maker Obj4C) is a
     * v3.12+ optional field. Older traces omit them; the parser supplies {@code ""}
     * so legacy traces parse unchanged.
     */
    record ObjectNear(int frame, String character, int slot, String objectType, short x, short y,
                      String routine, String status, String objFrame,
                      String routine2, String objoff3c,
                      String objoff34, String objoff36, String objoff38,
                      String objoff32)
        implements TraceEvent {}

    /**
     * Per-frame Sonic 1 Obj64 (Labyrinth Zone air bubbles) SST snapshot emitted
     * by the v3.4+ S1 recorder. Captures the maker scratch fields that gate
     * bubble production timing. Diagnostic only: replay must never hydrate
     * engine object state from these values.
     */
    record S1Obj64State(int frame, int slot, short x, short y,
                        int routine, int status, int renderFlags, int subtype,
                        int anim, int objoff32, int objoff33, int objoff34,
                        int objoff36, int objoff38, long objoff3c)
        implements TraceEvent {}

    record StateSnapshot(int frame, Map<String, Object> fields)
        implements TraceEvent {}

    record CollisionEvent(int frame, String type, String objectType, short x, short y)
        implements TraceEvent {}

    record ModeChange(int frame, String character, String field, int from, int to)
        implements TraceEvent {}

    record RoutineChange(int frame, String character, String from, String to, short x, short y)
        implements TraceEvent {}

    record Checkpoint(int frame, String name, Integer actualZoneId, Integer actualAct,
                      Integer apparentAct, Integer gameMode, String notes)
        implements TraceEvent {}

    record ZoneActState(int frame, Integer actualZoneId, Integer actualAct,
                        Integer apparentAct, Integer gameMode)
        implements TraceEvent {}

    record PlayerHistorySnapshot(int frame, int historyPos, short[] xHistory,
                                 short[] yHistory, short[] inputHistory, byte[] statusHistory)
        implements TraceEvent {}

    /**
     * Pre-trace snapshot of CPU-side sidekick globals emitted by the Lua recorder
     * at the instant gameplay begins (before trace frame 0 is written).
     */
    record CpuStateSnapshot(int frame, String character, int controlCounter,
                            int respawnCounter, int cpuRoutine, short targetX,
                            short targetY, int interactId, boolean jumping)
        implements TraceEvent {}

    /**
     * Per-frame snapshot of the Tails CPU global block plus
     * {@code Ctrl_2_logical} (held + pressed). Emitted on every recorded trace
     * frame by the recorder as diagnostic comparison context for the
     * engine's native {@link com.openggf.sprites.playable.SidekickCpuController}
     * state machine. Older traces never emit this event.
     *
     * <p>Field layout:
     * <ul>
     * <li>{@code interact} - S2 {@code Tails_interact_ID} / sidekick interact id</li>
     * <li>{@code idleTimer} - S2 {@code Tails_CPU_control_counter}</li>
     * <li>{@code flightTimer} - S2 {@code Tails_CPU_respawn_counter}</li>
     * <li>{@code cpuRoutine} - {@code Tails_CPU_routine} (current AI routine index)</li>
     * <li>{@code targetX}/{@code targetY} - flight steering targets</li>
     * <li>{@code autoFlyTimer} - {@code Tails_CPU_auto_fly_timer} (MGZ2 boss carry)</li>
     * <li>{@code autoJumpFlag} - {@code Tails_CPU_auto_jump_flag} (set when AI fires jump)</li>
     * <li>{@code ctrl2Held}/{@code ctrl2Pressed} - {@code Ctrl_2_held_logical}/{@code Ctrl_2_pressed_logical}</li>
     * <li>{@code posTableIndex}/{@code delayedIndex} - follower history ring cursors, when recorded</li>
     * </ul>
     */
    record CpuState(int frame, String character, int interact,
                    int idleTimer, int flightTimer, int cpuRoutine,
                    short targetX, short targetY, int autoFlyTimer,
                    int autoJumpFlag, int ctrl2Held, int ctrl2Pressed,
                    int ctrl2RawHeld, int ctrl1Logical,
                    int posTableIndex, int delayedIndex,
                    short delayedX, short delayedY, int delayedInput,
                    int delayedStatus, int tailsStatus, int tailsInteract,
                    int tailsInertia)
        implements TraceEvent {}

    /**
     * Per-frame snapshot of the ROM's {@code Oscillating_table}
     * (sonic3k.constants.asm:853, $42 bytes at $FFFFFE6E) plus the running
     * {@code Level_frame_counter}. Emitted on every recorded trace frame by
     * the v6.1+ S3K recorder so divergence diagnostics can ROM-verify global
     * oscillator phase, used by HoverFan, platforms, and other oscillating
     * objects. <strong>Diagnostic only:</strong> tests must NOT hydrate the
     * engine's {@code OscillationManager} from these values; the engine must
     * produce the correct oscillator phase natively from the same inputs as
     * the ROM. Older traces (recorder &lt; 6.1) never emit this event.
     */
    record OscillationState(int frame, int levelFrameCounter, byte[] oscTable)
        implements TraceEvent {}

    /**
     * Per-frame snapshot of the S1 object respawn-state bit array
     * ({@code v_objstate}, 192 bytes at $FFFC00..$FFFCC0). Emitted by the v3.7+
     * S1 recorder for the slot-interleave / slot-cadence cluster (LZ2 f1068,
     * MZ3 f9917, SYZ1 f4430, SBZ1). {@code v_objstate[0]}/{@code [1]} are the
     * ObjPosLoad forward/backward counters; the remaining bytes are per-spawn
     * RememberState remember bits. <strong>Diagnostic only:</strong> tests must
     * NOT hydrate the engine's placement/respawn state from these bytes -- they
     * exist so the comparator can show, at a backward-OPL reload, whether ROM's
     * respawn bit is clear (respawn) vs the engine's set (skip). Older traces
     * (recorder &lt; 3.7) never emit this event.
     */
    record VObjState(int frame, byte[] bytes)
        implements TraceEvent {}

    /**
     * Per-frame snapshot of the S1 global oscillation state ({@code v_oscillate}):
     * the 2-byte direction bitfield at $FFFE5E followed by the $40-byte oscillating
     * -values array at $FFFE60 ($42 bytes total). Emitted by the v3.10+ S1 recorder
     * for the osc-phase cluster (e.g. SLZ2 f3353 circling-platform 1px). Per-object
     * oscillators index this array (oscillator N -&gt; offset N*4 into the values
     * array, i.e. byte $2 + N*4 from the start of this record). <strong>Diagnostic
     * only:</strong> tests must NOT hydrate the engine's oscillation state from
     * these bytes -- they let the comparator read the exact oscillator byte per
     * frame to disambiguate an osc-phase-seed offset from a ride-exit seat. Older
     * traces (recorder &lt; 3.10) never emit this event.
     */
    record VOscillate(int frame, byte[] bytes)
        implements TraceEvent {}

    /**
     * Per-frame BizHawk authoritative lag state, emitted by the v3.11+ S1 recorder.
     * {@code lagged} is {@code emu.islagged()} for the frame (the emulator polled
     * input but did NOT complete a full logical/game step), and {@code lagcount}
     * is {@code emu.lagcount()} (cumulative lag-frame total; {@code -1} when the
     * recording emulator did not expose the API). Used to confirm whether the
     * counter/oscillation "skip" frames (SLZ1/SLZ2/MZ1/MZ2/FZ cluster) coincide
     * with emulator lag frames. <strong>Diagnostic only:</strong> tests must NOT
     * change engine stepping from these values; any lag-handling fix is a separate,
     * user-gated decision. Older traces (recorder &lt; 3.11) never emit this event.
     */
    record LagState(int frame, boolean lagged, int lagcount)
        implements TraceEvent {}

    /**
     * Per-frame snapshot of the S1 camera vertical-boundary / look-shift state.
     * Emitted by the v3.7+ S1 recorder for the MZ1 f2101 camera-boundary
     * frontier (engine {@code v_limitbtm2} ~6px too high). Fields are the raw
     * ROM words/byte: {@code limitBtm1}=v_limitbtm1 ($FFF726), {@code limitBtm2}
     * =v_limitbtm2 ($FFF72E, the eased bottom boundary the camera clamps to),
     * {@code lookShift}=v_lookshift ($FFF73E), {@code bgScrollVert}
     * =f_bgscrollvert ($FFF75C). <strong>Diagnostic only:</strong> comparison
     * context, never engine write-back. Older traces never emit this event.
     */
    record CameraBoundary(int frame, int limitBtm1, int limitBtm2,
                          int lookShift, int bgScrollVert)
        implements TraceEvent {}

    /**
     * Pre-trace snapshot of a single ROM SST slot emitted by the Lua recorder
     * at the instant gameplay begins (before trace frame 0 is written).
     * The {@link #frame()} is -1 to keep it out of the frame-0 event bucket,
     * and {@link #slot()} is the ROM SST slot index (not the engine slot).
     */
    record ObjectStateSnapshot(int frame, int slot, int objectType,
                               RomObjectSnapshot fields)
        implements TraceEvent {}

    /**
     * Per-frame snapshot of a CNZ wire cage object's per-player state bytes
     * plus its main status byte. Emitted by the v6.3+ S3K recorder for every
     * OST slot containing {@code Obj_CNZWireCage} ($0001365C) on every frame.
     *
     * <p>Field layout matches the cage's OST offsets:
     * <ul>
     * <li>{@code x}/{@code y} - cage object's world position</li>
     * <li>{@code subtype} - cage subtype byte at offset $2C (vertical extent)</li>
     * <li>{@code status} - cage status byte at offset $2A (bit 3 = p1_standing,
     *     bit 4 = p2_standing)</li>
     * <li>{@code p1Phase} - per-P1 phase byte at offset $30 (sin/cos angle)</li>
     * <li>{@code p1State} - per-P1 state byte at offset $31 (0 = capture-rotate,
     *     1 = released-cooldown one-frame, $10 = unmounted-cooldown 16f)</li>
     * <li>{@code p2Phase} - per-P2 phase byte at offset $34</li>
     * <li>{@code p2State} - per-P2 state byte at offset $35</li>
     * </ul>
     *
     * <p><strong>Diagnostic only:</strong> the comparator uses these to render
     * cage state in the divergence report; engine state must NEVER be hydrated
     * from these values.
     */
    record CageState(int frame, int slot, short x, short y, int subtype,
                     int status, int p1Phase, int p1State, int p2Phase, int p2State)
        implements TraceEvent {}

    /**
     * Per-frame summary of CNZ wire cage execution-hook hits emitted by the
     * v6.3+ S3K recorder. Each hit records which cage-routine branch the M68K
     * CPU entered ({@code sub_338C4} entry, {@code loc_339A0} mounted,
     * {@code loc_33ADE} cooldown, {@code loc_33B1E} continue, {@code loc_33B62}
     * release) along with M68K register state and the per-player state byte.
     *
     * <p>Used to root-cause CNZ1 trace F2222 release-cooldown divergence:
     * ROM-side execution path proves which of {@code loc_33ADE} (button-press
     * release) or {@code loc_33B1E} (continue ride) the cage actually took on
     * each frame for each player, given that the engine's
     * {@link com.openggf.game.sonic3k.objects.CnzWireCageObjectInstance} model
     * fires Tails's release one frame ahead of ROM at this trace frame.
     *
     * <p><strong>Diagnostic only:</strong> never hydrated into engine state.
     */
    record CageExecution(int frame, java.util.List<Hit> hits)
        implements TraceEvent {

        /**
         * Single execution-hook hit at one of the cage's branch entry points.
         */
        public record Hit(String branch, int pc, int cageAddr, int playerAddr,
                          int stateAddr, int d5, int d6, int stateByte,
                          int playerStatus, int playerObjCtrl, int cageStatus) {}
    }

    /**
     * Per-frame snapshot of a player's interact / object_control / status state.
     * The v6.3+ recorder fixes a long-standing bug where the field labelled
     * {@code object_control} was actually reading the {@code status} byte
     * (offset $2A); v6.3 splits these into separate JSON fields and parses
     * the real {@code object_control} byte (offset $2E).
     *
     * <p>Field layout:
     * <ul>
     * <li>{@code character} - "sonic" or "tails"</li>
     * <li>{@code interact} - {@code interact} field at offset $42 (RAM address
     *     of last linked object)</li>
     * <li>{@code interactSlot} - resolved OST slot index</li>
     * <li>{@code status} - status byte at offset $2A (Status_OnObj, In_Air, ...)</li>
     * <li>{@code statusSecondary} - status_secondary byte at offset $2B (shields,
     *     speed shoes, invincibility)</li>
     * <li>{@code objectControl} - object_control byte at offset $2E (cage ride
     *     bits 1+6, CPU-blocking bit 7, jumpable bit 0)</li>
     * </ul>
     *
     * <p><strong>Diagnostic only:</strong> never hydrated into engine state.
     */
    record InteractState(int frame, String character, int interact,
                         int interactSlot, int status, int statusSecondary,
                         int objectControl)
        implements TraceEvent {}

    /**
     * Per-frame summary of all M68K writes to a player's {@code x_vel} and
     * {@code y_vel} RAM addresses, captured by the v6.4+ S3K recorder via
     * {@code event.onmemorywrite} hooks. Each write records the M68K PC of
     * the writing instruction plus the resulting word value.
     *
     * <p>Emitted for Tails and, in newer S3K traces, Sonic.
     * Used to root-cause the CNZ1 trace F3649 divergence where ROM Tails
     * {@code x_speed} jumps from -$48 to -$0A00 in a single frame; the
     * engine arrives at -$0A00 only at F3650 (a 1-frame phase shift).
     * The PC of the ROM instruction that writes -$0A00 pinpoints which
     * code path produced the value.
     *
     * <p><strong>Diagnostic only:</strong> never hydrated into engine state.
     */
    record VelocityWrite(int frame, String character,
                         java.util.List<Hit> xVelWrites,
                         java.util.List<Hit> yVelWrites)
        implements TraceEvent {

        /**
         * Single velocity-write hit. {@code pc} is the M68K program counter
         * at the writing instruction (post-fetch); {@code value} is the full
         * 16-bit word value of the velocity field after the write.
         */
        public record Hit(int pc, int value) {}
    }

    /**
     * Per-frame summary of all M68K writes to a player's {@code x_pos} and
     * {@code y_pos} RAM words, captured by the v6.8+ S3K recorder via
     * {@code event.onmemorywrite} hooks. Each write records the M68K PC of
     * the writing instruction plus the resulting word value.
     *
     * <p>Currently emitted only for Tails ({@code character = "tails"}).
     * Used to root-cause the CNZ1 trace F4790 divergence where ROM Tails
     * {@code x_pos} changes from $7F00 to $6125 after {@code sub_13ECA}'s
     * CPU marker path, while the captured cylinder recapture path does not
     * itself write {@code x_pos} (docs/skdisasm/sonic3k.asm:26800-26809,
     * 67985-68012).
     *
     * <p><strong>Diagnostic only:</strong> never hydrated into engine state.
     */
    record PositionWrite(int frame, String character,
                         java.util.List<Hit> xPosWrites,
                         java.util.List<Hit> yPosWrites)
        implements TraceEvent {

        /**
         * Single position-write hit. {@code pc} is the M68K program counter
         * at the writing instruction (post-fetch); {@code value} is the full
         * 16-bit word value of the position field after the write. {@code a1}
         * and {@code a0} are the 32-bit M68K address registers captured at
         * the same moment (added in v6.11-s3k recorder schema). For
         * pre-v6.11 fixtures the registers default to 0; consumers should
         * treat 0 as "unknown" rather than "address 0x00000000".
         */
        public record Hit(int pc, int value, int a1, int a0) {
            /** Backwards-compatible constructor for pre-v6.11 callers. */
            public Hit(int pc, int value) { this(pc, value, 0, 0); }
        }
    }

    /**
     * Focused S3K AIZ2 battleship autoscroll diagnostic emitted by the
     * recorder around {@code AIZ2_DoShipLoop/sub_50318}
     * (docs/skdisasm/sonic3k.asm:105200-105253). Captures ROM-side
     * execution/register context only; replay code must never hydrate engine
     * state from this event.
     */
    record AizShipLoop(int frame, java.util.List<Hit> hits)
        implements TraceEvent {

        public record Hit(String label, int pc, String character, int a1,
                          int d0, int d1, int cameraX, int cameraMinX,
                          int cameraMaxX, int eventsBg2, int playerX,
                          int playerY, int playerGvel, int playerXvel,
                          int playerAnim, int playerStatus) {}
    }

    /**
     * Per-frame hook-driven diagnostic for Player_1 {@code Sonic_RecordPos}
     * calls. Captures the ROM table index and logical input word that will be
     * written into {@code Stat_table} for later Tails CPU delayed reads
     * (docs/skdisasm/sonic3k.asm:22124-22136, 26683-26700). Diagnostic only:
     * never hydrated into engine state.
     */
    record SonicRecordPos(int frame, java.util.List<Hit> hits)
        implements TraceEvent {

        public record Hit(int pc, int posTableIndex, int ctrl1Logical,
                          int ctrl1Locked, int ctrl1Raw, int objectControl,
                          int status, int statusSecondary, int x, int y) {}
    }

    /**
     * Per-frame focused diagnostic for Tails CPU's normal follow step in S3K.
     * Captures the ROM state around {@code loc_13DD0}, the generated delayed
     * input word, and the state before/after {@code Tails_InputAcceleration_Path}
     * (docs/skdisasm/sonic3k.asm:26702-26705, 26717-26741, 27798-27805,
     * 28103-28122, 27957-28017). Diagnostic only: never hydrated into engine
     * state.
     */
    record TailsCpuNormalStep(int frame, String character, int status,
                              int objectControl, int groundVel, int xVel,
                              int delayedStat, int delayedInput,
                              int posTableIndex, int delayedTargetX,
                              int delayedTargetY, int followDx, int followDy,
                              String loc13dd0Branch, int ctrl2Logical,
                              int ctrl2HeldLogical, int pathPreGroundVel,
                              int pathPreXVel, int pathPreStatus,
                              int pathPostGroundVel, int pathPostXVel,
                              int pathPostStatus)
        implements TraceEvent {}

    /**
     * Per-frame focused diagnostic for the sidekick's interact object in S3K.
     * Captures the sidekick's raw interact pointer and the target object's key
     * bytes needed to diagnose AIZ object ride/grab handoffs
     * (docs/skdisasm/sonic3k.asm:28407-28451, 43758-43810, 46481-46549,
     * 46602-46631, 46709-46743, 46749-46789, 46929-46950). Diagnostic only:
     * never hydrated into engine state.
     */
    record SidekickInteractObjectState(int frame, String character, int interact,
                                       int interactSlot, int tailsRenderFlags,
                                       int tailsObjectControl, int tailsStatus,
                                       int tailsInvulnerabilityTimer,
                                       int tailsWidthPixels,
                                       int tailsHeightPixels,
                                       int cameraXCopy, int cameraYCopy,
                                       boolean tailsOnObject, int objectCode,
                                       int objectRoutine, int objectStatus,
                                       short objectX, short objectY,
                                       int objectSubtype, int objectRenderFlags,
                                       int objectObjectControl,
                                       boolean objectActive,
                                       boolean objectDestroyed,
                                       boolean objectP1Standing,
                                       boolean objectP2Standing)
        implements TraceEvent {}

    /**
     * Per-frame CNZ cylinder OST snapshot. Captures the object bytes that
     * {@code Obj_CNZCylinder} uses as the P1/P2 {@code sub_324C0} state blocks:
     * $32-$35 for P1 and $36-$39 for P2 (docs/skdisasm/sonic3k.asm:67656-67667,
     * 67985-68012). Diagnostic only: never hydrated into engine state.
     */
    record CnzCylinderState(int frame, int slot, short x, short y, int subtype,
                            int status, int routine, int renderFlags,
                            int p1State, int p1Angle, int p1Distance,
                            int p1Threshold, int p2State, int p2Angle,
                            int p2Distance, int p2Threshold)
        implements TraceEvent {}

    /**
     * Per-frame CNZ cylinder execution-hook summary for Tails/P2. Hook hits
     * surround {@code sub_324C0} and the relevant {@code MvSonicOnPtfm}
     * platform carry points so the report can show Tails x/subpixel before
     * and after ROM-side cylinder/platform movement
     * (docs/skdisasm/sonic3k.asm:67985-68012, 68019-68038, 41667-41679).
     * Diagnostic only: never hydrated into engine state.
     */
    record CnzCylinderExecution(int frame, java.util.List<Hit> hits)
        implements TraceEvent {

        public record Hit(String branch, int pc, int cylinderAddr,
                          int playerAddr, int stateAddr, int d2, int d4,
                          int d5, int d6, int cylinderStatus,
                          int slotState, int slotAngle, int slotDistance,
                          int slotThreshold, int playerX, int playerXSub,
                          int playerY, int playerYSub, int playerStatus,
                          int playerObjectControl) {}
    }

    /**
     * Focused CNZ Act 1 post-boss event RAM diagnostic emitted by the v6.22+
     * S3K recorder for the F15620-F15735 window. Captures the ROM-owned
     * {@code Events_bg+$08/$0C}, {@code Events_routine_bg},
     * {@code Background_collision_flag}, and active
     * {@code Obj_CNZMinibossScrollControl} OST state.
     *
     * <p><strong>Diagnostic only:</strong> never hydrated into engine state.
     */
    record CnzEventRamState(
            int frame,
            int eventsBg00Word,
            int eventsBg02Word,
            int eventsBg08Word,
            int eventsBg08Long,
            int eventsBg0cWord,
            int eventsBg0cLong,
            int eventsRoutineBg,
            int backgroundCollisionFlag,
            int eventsFg5,
            int cameraY,
            int cameraMaxY,
            int cameraTargetMaxY,
            java.util.List<ScrollControlSlot> scrollSlots)
        implements TraceEvent {

        public record ScrollControlSlot(
                int slot, int addr, int objectCode, int routine,
                int routineSecondary, int x, int y, int status,
                int subtype, int objoff2e, int objoff30,
                int objoff32, int objoff34, int objoff36, int objoff38) {}
    }

    /**
     * Per-frame S3K fixed {@code Breathing_bubbles} /
     * {@code Breathing_bubbles_P2} diagnostic. These fixed in-level object
     * slots sit after dynamic object RAM and do not consume a dynamic SST slot
     * (docs/skdisasm/sonic3k.constants.asm:307-312). The recorder polls the
     * fixed controller fields used by {@code Obj_AirCountdown}'s countdown and
     * make-item paths (docs/skdisasm/sonic3k.asm:33289-33306,
     * 33490-33610), plus any visible dynamic {@code Obj_AirCountdown}
     * children that currently point at the same owner.
     *
     * <p><strong>Diagnostic only:</strong> this event exposes ROM cadence and
     * child lifetime for reports; replay code must never hydrate engine state
     * from it.
     */
    record AirCountdownState(
            int frame, String owner, int fixedSlot, int objectCode,
            int routine, int subtype, int obj30, int obj36, int obj37,
            int obj38, int obj3a, int obj3c, int obj3e, int ownerPtr,
            String ownerResolved, int ownerAirLeft, int ownerStatus,
            int ownerStatusSecondary, boolean ownerFacingLeft,
            boolean ownerUnderwater, int rngSeed, List<VisibleChild> visibleChildren)
        implements TraceEvent {

        public record VisibleChild(
                int slot, int objectCode, int routine, int subtype,
                int x, int y, int xSub, int ySub, int yVel,
                int renderFlags, int anim, int mappingFrame,
                int animFrame, int animFrameTimer, int angle,
                int obj34, int obj3c, int parentPtr) {}
    }

    /**
     * Hook-driven S3K {@code Random_Number} diagnostic emitted by the v6.25+
     * recorder in focused RNG windows. Each hit records the ROM seed before
     * and after the call, the raw result, return PC, and active {@code a0/a1}
     * object register context. This is comparison-only context for tracking
     * RNG consumption order; replay code must never hydrate engine RNG state
     * from it.
     */
    record RngCall(int frame, List<Hit> hits) implements TraceEvent {

        public record ObjectContext(
                int ptr, int slot, int objectCode, int routine,
                int subtype, int x, int y) {}

        public record Hit(
                int pc, int callerPc, String source,
                int seedBefore, int seedAfter, int result, int resultByte,
                ObjectContext a0, ObjectContext a1) {}
    }

    /**
     * Per-frame AIZ boundary/tree diagnostic for the sidekick around the
     * frame-order-sensitive path where {@code Process_Sprites} runs before
     * {@code DeformBgLayer}/{@code ScreenEvents}
     * (docs/skdisasm/sonic3k.asm:7884-7898), AIZ resize writes
     * {@code Camera_min_X_pos=$2D80} (docs/skdisasm/sonic3k.asm:38961-38974),
     * {@code AIZTree_SetPlayerPos} can reposition and zero velocity
     * (docs/skdisasm/sonic3k.asm:43776-43810), and
     * {@code Tails_Check_Screen_Boundaries} can clamp/despawn
     * (docs/skdisasm/sonic3k.asm:28407-28451). Diagnostic only: never
     * hydrated into engine state.
     */
    record AizBoundaryState(int frame, String character,
                            int cameraMinX, int cameraMaxX,
                            int cameraMinY, int cameraMaxY,
                            int treePreX, int treePreY,
                            int treePreXVel, int treePreYVel,
                            int treePostX, int treePostY,
                            int treePostXVel, int treePostYVel,
                            int boundaryPreX, int boundaryPreY,
                            int boundaryPreXVel, int boundaryPreYVel,
                            int boundaryPostX, int boundaryPostY,
                            int boundaryPostXVel, int boundaryPostYVel,
                            String boundaryAction,
                            int postMoveX, int postMoveY,
                            int postMoveXVel, int postMoveYVel)
        implements TraceEvent {}

    /**
     * Per-frame AIZ1-&gt;AIZ2 fake-fire transition diagnostic. The ROM drives a
     * single continuous {@code Camera_Y_pos_BG_copy} (16.16) ramp via
     * {@code AIZ1_FireRise} (docs/skdisasm/s3.asm:70383: {@code Events_bg+$02}
     * accumulates {@code +$280} capped at {@code $A000}, then
     * {@code Camera_Y_pos_BG_copy += speed&lt;&lt;4}), initialized at
     * {@code $200000} with lerp target {@code Events_bg+$00=$68} in
     * {@code AIZ1_AIZ2_Transition} (docs/skdisasm/sonic3k.asm:104638). The ramp
     * runs uninterrupted through the seamless reload ({@code AIZ1BGE_Finish}
     * Kos wait) and releases the post-reload {@code Camera_max_X_pos=$6000} when
     * it crosses {@code $310} in {@code AIZ2BGE_WaitFire}
     * (docs/skdisasm/sonic3k.asm:105084-105096). {@code eventsFg5} marks the
     * fire-transition start trigger; {@code eventsRoutineBg} is the BG event
     * phase. Diagnostic only: never hydrated into engine state.
     */
    record AizFireTransition(
            int frame,
            int cameraYBgCopy,
            int cameraYBgRounded,
            int eventsBg00Word,
            int eventsBg02Word,
            int eventsRoutineBg,
            int eventsFg5,
            int cameraX,
            int cameraMinX,
            int cameraMaxX,
            int playerX,
            int act)
        implements TraceEvent {}

    /**
     * Per-frame AIZ transition-floor solid diagnostic around the F5415
     * Sonic/Tails split. The ROM spawns {@code Obj_AIZTransitionFloor} during
     * the AIZ1 fire-refresh sequence (docs/skdisasm/sonic3k.asm:104683-104690)
     * and then calls {@code SolidObjectTop} with {@code d1=$A0,d2=$10,d3=$10}
     * (docs/skdisasm/sonic3k.asm:104777-104790). The per-player path strings
     * expose whether {@code SolidObjectTop_1P} used the already-standing path
     * or the first-landing check (docs/skdisasm/sonic3k.asm:41793-41818,
     * 41982-42015). Diagnostic only: never hydrated into engine state.
     */
    record AizTransitionFloorSolidState(
            int frame, int slot, int objectStatus, int objectX, int objectY,
            boolean p1Standing, boolean p2Standing,
            String p1Path, String p2Path,
            int p1D1, int p1D2, int p1D3,
            int p1Status, int p1ObjectControl, int p1YRadius,
            int p1X, int p1Y, int p1YVel, int p1InteractSlot,
            int p2D1, int p2D2, int p2D3,
            int p2Status, int p2ObjectControl, int p2YRadius,
            int p2X, int p2Y, int p2YVel, int p2InteractSlot)
        implements TraceEvent {}

    /**
     * Per-frame AIZ fire-handoff terrain diagnostic around the F5435
     * transition-floor first landing. Captures delayed redraw / Load_Level
     * state from the handoff window (docs/skdisasm/sonic3k.asm:104664-104738)
     * plus ROM floor-check and SolidObjectTop vertical-gate evidence
     * (docs/skdisasm/sonic3k.asm:19839-19891, 41982-42015). Diagnostic only:
     * never hydrated into replay state.
     */
    record AizHandoffTerrainState(
            int frame, int eventsBg, int drawPos, int drawRows,
            int kosModulesLeft, int currentZoneAct,
            int dynamicResize, int objectLoad, int ringsManager,
            int p1X, int p1Y, int p1Status, int p1YRadius, int p1TopSolid,
            boolean sonicFloorSeen, int sonicFloorDistance, int sonicFloorAngle,
            int sonicFloorProbeX, int sonicFloorProbeY,
            boolean solidVerticalSeen, int solidPreY, int solidSurfaceY, int solidDelta)
        implements TraceEvent {}

    /** Comparison-only physical load queue state sampled at end of logical frame. */
    record LoadQueueState(
            int frame,
            String kind,
            boolean busy,
            boolean prepared,
            int activeSource,
            int activeDestination,
            int totalWork,
            int remainingWork,
            List<String> queuedFingerprints,
            List<ServiceObservation> serviceObservations)
            implements TraceEvent {
        public record ServiceObservation(String boundary, int budget) {}
    }

    /** Mandatory per-stored-row player-art lifecycle heartbeat. */
    /**
     * Per-frame snapshot of the ROM's {@code SlotMachineVariables} block, emitted
     * by the S2 recorder on every row of a CNZ capture. Purely a comparison
     * oracle: replay must never hydrate engine slot state from these values.
     *
     * <p>The ROM derives {@code slotN_speed} and {@code timer} from
     * {@code V_int_run_count} by byte arithmetic in {@code SlotMachine_Routine3}
     * and {@code Routine4} (s2.asm:59360-59375), so these fields pin the exact
     * counter the ROM's {@code SlotMachine} consumed. That is what makes them
     * worth comparing rather than merely recording: a clock or call-ordering
     * error at this site shows up here as a field mismatch instead of surfacing
     * thousands of rows later as a ring or speed divergence.
     *
     * <p>{@code pos} packs the reel index in the high byte and the sub-tile
     * offset in the low byte, matching the ROM's 16-bit
     * {@code (index, offset)} word.
     */
    /**
     * Per-frame snapshot of ObjB2's SST, emitted by the S2 recorder on every row
     * of an SCZ or WFZ capture. Comparison-only.
     *
     * <p>The scratch bytes carry routine-specific ROM idioms rather than plain
     * numbers. In the SCZ routine, {@code objoff_2E} is the standing-bit
     * transition — ObjB2 saves
     * {@code status(a0)}, then stores {@code (saved ^ current) & p1_standing}
     * (s2.asm:78827, 78834-78839), so it is `$00` or `$08`;
     * {@code objoff_2F} and {@code objoff_30} are `st.b`/`clr.b` flags, hence
     * `$FF` or `$00` (s2.asm:79382-79383, 79390); and {@code objoff_31} is the
     * vertical-move countdown loaded with `$14` (s2.asm:79385-79388).
     * In the WFZ-end routine, {@code objoff_2E}/{@code objoff_2F} are instead
     * the big-endian leader-wait word, reused as the jump countdown
     * (s2.asm:78972-78979, 79041-79068).
     *
     * <p>{@code y_sub} is the ROM's 16-bit sub-pixel word. Its low byte is zero
     * on all 7629 rows of the SCZ fixture, so the engine's 8-bit sub-pixel is a
     * faithful model of it and the comparison is exact rather than truncating.
     */
    record S2TornadoState(
            int frame, int slot, int x, int y, int ySub, int yVel,
            int routine, int routineSecondary, int statusByte,
            int objoff2E, int objoff2F, int objoff30, int objoff31)
            implements TraceEvent {}

    record CnzSlotMachineState(
            int frame,
            int vbc,
            boolean inUse,
            int routine,
            int timer,
            int index,
            int reward,
            List<Integer> slotPos,
            List<Integer> slotSpeed,
            List<Integer> slotRoutine)
            implements TraceEvent {
        public CnzSlotMachineState {
            slotPos = List.copyOf(slotPos);
            slotSpeed = List.copyOf(slotSpeed);
            slotRoutine = List.copyOf(slotRoutine);
        }
    }

    record DynamicArtTransferState(
            int frame,
            List<DynamicArtTransfer.SegmentEdge> edges,
            List<Long> outstandingTransferIds)
            implements TraceEvent {
        public DynamicArtTransferState {
            edges = List.copyOf(edges);
            outstandingTransferIds = List.copyOf(outstandingTransferIds);
            Set<Long> ids = new java.util.HashSet<>();
            for (long transferId : outstandingTransferIds) {
                if (transferId < 0 || !ids.add(transferId)) {
                    throw new IllegalArgumentException(
                            "outstanding_transfer_ids has negative or duplicate transfer_id");
                }
            }
        }
    }

    /**
     * Parse a single JSONL line into the appropriate TraceEvent subtype.
     * Unknown event types are returned as StateSnapshot with all fields preserved.
     */
    static TraceEvent parseJsonLine(String jsonLine, ObjectMapper mapper) {
        return parseJsonLine(jsonLine, mapper, false);
    }

    static TraceEvent parseJsonLine(
            String jsonLine, ObjectMapper mapper, boolean failOnUnknownEvent) {
        try {
            JsonNode node = mapper.readTree(jsonLine);
            int frame = DynamicArtTransfer.requiredInt(node, "frame");
            String event = node.has("event") ? node.get("event").asText() : "unknown";

            return switch (event) {
                case "load_queue_state" -> parseLoadQueueState(frame, node);
                case "cnz_slot_machine_state" ->
                        parseCnzSlotMachineState(frame, node);
                case "s2_tornado_state" -> new S2TornadoState(
                    frame,
                    node.has("slot") ? node.get("slot").asInt() : -1,
                    parseHexInt(node, "x") & 0xFFFF,
                    parseHexInt(node, "y") & 0xFFFF,
                    parseHexInt(node, "y_sub") & 0xFFFF,
                    parseHexInt(node, "y_vel") & 0xFFFF,
                    parseHexInt(node, "routine") & 0xFF,
                    parseHexInt(node, "routine_secondary") & 0xFF,
                    parseHexInt(node, "status_byte") & 0xFF,
                    parseHexInt(node, "objoff_2e") & 0xFF,
                    parseHexInt(node, "objoff_2f") & 0xFF,
                    parseHexInt(node, "objoff_30") & 0xFF,
                    parseHexInt(node, "objoff_31") & 0xFF
                );
                case "dynamic_art_transfer_state" ->
                        parseDynamicArtTransferState(frame, node);
                case "object_appeared" -> new ObjectAppeared(
                    frame,
                    node.get("slot").asInt(),
                    node.get("object_type").asText(),
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y")
                );
                case "object_removed" -> new ObjectRemoved(
                    frame,
                    node.get("slot").asInt(),
                    node.has("object_type") ? node.get("object_type").asText() : ""
                );
                // Interned text fields: object_near rows are emitted per nearby
                // object per frame, so complete-run traces carry hundreds of
                // thousands of them with heavily repeated hex-string values.
                case "object_near" -> new ObjectNear(
                    frame,
                    parseCharacter(node),
                    node.get("slot").asInt(),
                    internedText(node, "type"),
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y"),
                    internedText(node, "routine"),
                    internedText(node, "status"),
                    // obj_frame is a v3.5+ optional field; older traces omit it.
                    internedText(node, "obj_frame"),
                    // routine2 (ob2ndRout) and objoff_3c are v3.8+ optional fields;
                    // older traces omit them so default to "" (legacy-absent-safe).
                    internedText(node, "routine2"),
                    internedText(node, "objoff_3c"),
                    // objoff_34/36/38 are v3.9+ optional fields; older traces omit
                    // them so default to "" (legacy-absent-safe).
                    internedText(node, "objoff_34"),
                    internedText(node, "objoff_36"),
                    internedText(node, "objoff_38"),
                    // objoff_32 (gmake_timer for makers) is a v3.12+ optional field;
                    // older traces omit it so default to "" (legacy-absent-safe).
                    internedText(node, "objoff_32")
                );
                case "s1_obj64_state" -> new S1Obj64State(
                    frame,
                    node.has("slot") ? node.get("slot").asInt() : -1,
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y"),
                    parseHexInt(node, "routine"),
                    parseHexInt(node, "status"),
                    parseHexInt(node, "render_flags"),
                    parseHexInt(node, "subtype"),
                    parseHexInt(node, "anim"),
                    parseHexInt(node, "objoff_32"),
                    parseHexInt(node, "objoff_33"),
                    parseHexInt(node, "objoff_34"),
                    parseHexInt(node, "objoff_36"),
                    parseHexInt(node, "objoff_38"),
                    parseHexLong(node, "objoff_3c")
                );
                case "collision_event" -> new CollisionEvent(
                    frame,
                    node.get("type").asText(),
                    node.has("object_type") ? node.get("object_type").asText() : "",
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y")
                );
                case "mode_change" -> new ModeChange(
                    frame,
                    parseCharacter(node),
                    node.get("field").asText(),
                    node.get("from").asInt(),
                    node.get("to").asInt()
                );
                case "routine_change" -> new RoutineChange(
                    frame,
                    parseCharacter(node),
                    node.has("from") ? stripHexPrefix(node.get("from").asText()) : "",
                    node.has("to") ? stripHexPrefix(node.get("to").asText()) : "",
                    parseHexShort(node, "x", "sonic_x", "tails_x"),
                    parseHexShort(node, "y", "sonic_y", "tails_y")
                );
                case "checkpoint" -> new Checkpoint(
                    frame,
                    node.has("name") ? node.get("name").asText() : "",
                    parseNullableInt(node, "actual_zone_id"),
                    parseNullableInt(node, "actual_act"),
                    parseNullableInt(node, "apparent_act"),
                    parseNullableInt(node, "game_mode"),
                    node.has("notes") && !node.get("notes").isNull()
                        ? node.get("notes").asText()
                        : null
                );
                case "zone_act_state" -> new ZoneActState(
                    frame,
                    parseNullableInt(node, "actual_zone_id"),
                    parseNullableInt(node, "actual_act"),
                    parseNullableInt(node, "apparent_act"),
                    parseNullableInt(node, "game_mode")
                );
                case "player_history_snapshot" -> new PlayerHistorySnapshot(
                    frame,
                    node.has("history_pos") ? node.get("history_pos").asInt() : 0,
                    parseShortArray(node.get("x_history")),
                    parseShortArray(node.get("y_history")),
                    parseShortArray(node.get("input_history")),
                    parseByteArray(node.get("status_history"))
                );
                case "cpu_state_snapshot" -> new CpuStateSnapshot(
                    frame,
                    node.has("character") ? node.get("character").asText() : "",
                    node.has("control_counter") ? node.get("control_counter").asInt() : 0,
                    node.has("respawn_counter") ? node.get("respawn_counter").asInt() : 0,
                    node.has("cpu_routine") ? node.get("cpu_routine").asInt() : 0,
                    parseHexShort(node, "target_x"),
                    parseHexShort(node, "target_y"),
                    parseHexInt(node, "interact_id"),
                    node.has("jumping") && node.get("jumping").asInt() != 0
                );
                case "cpu_state" -> new CpuState(
                    frame,
                    node.has("character") ? node.get("character").asText() : "",
                    parseHexInt(node, "interact"),
                    node.has("idle_timer") ? node.get("idle_timer").asInt() : 0,
                    node.has("flight_timer") ? node.get("flight_timer").asInt() : 0,
                    node.has("cpu_routine") ? node.get("cpu_routine").asInt() : 0,
                    parseHexShort(node, "target_x"),
                    parseHexShort(node, "target_y"),
                    node.has("auto_fly_timer") ? node.get("auto_fly_timer").asInt() : 0,
                    node.has("auto_jump_flag") ? node.get("auto_jump_flag").asInt() : 0,
                    parseHexInt(node, "ctrl2_held"),
                    parseHexInt(node, "ctrl2_pressed"),
                    parseHexIntOrDefault(node, "ctrl2_raw_held", -1),
                    parseHexIntOrDefault(node, "ctrl1_logical", -1),
                    parseHexIntOrDefault(node, "pos_table_index", -1),
                    parseHexIntOrDefault(node, "delayed_index", -1),
                    parseHexShortOrDefault(node, "delayed_x", (short) 0),
                    parseHexShortOrDefault(node, "delayed_y", (short) 0),
                    parseHexIntOrDefault(node, "delayed_input", -1),
                    parseHexIntOrDefault(node, "delayed_status", -1),
                    parseHexIntOrDefault(node, "tails_status", -1),
                    parseHexIntOrDefault(node, "tails_interact", -1),
                    parseHexIntOrDefault(node, "tails_inertia", -1)
                );
                case "oscillation_state" -> new OscillationState(
                    frame,
                    node.has("level_frame_counter") ? node.get("level_frame_counter").asInt() : 0,
                    parseHexByteString(node.has("osc_table") ? node.get("osc_table").asText() : "")
                );
                case "v_objstate" -> new VObjState(
                    frame,
                    parseHexByteString(node.has("bytes") ? node.get("bytes").asText() : "")
                );
                case "v_oscillate" -> new VOscillate(
                    frame,
                    parseHexByteString(node.has("bytes") ? node.get("bytes").asText() : "")
                );
                case "lag_state" -> new LagState(
                    frame,
                    node.has("lagged") && node.get("lagged").asBoolean(false),
                    node.has("lagcount") ? node.get("lagcount").asInt(-1) : -1
                );
                case "camera_boundary" -> new CameraBoundary(
                    frame,
                    parseHexIntOrDefault(node, "limitbtm1", -1),
                    parseHexIntOrDefault(node, "limitbtm2", -1),
                    parseHexIntOrDefault(node, "lookshift", -1),
                    parseHexIntOrDefault(node, "bgscrollvert", -1)
                );
                case "object_state_snapshot" -> new ObjectStateSnapshot(
                    frame,
                    node.has("slot") ? node.get("slot").asInt() : -1,
                    node.has("object_type")
                        ? Integer.parseInt(stripHexPrefix(node.get("object_type").asText()), 16)
                        : 0,
                    RomObjectSnapshot.fromJsonNode(node.get("fields"))
                );
                case "cage_state" -> new CageState(
                    frame,
                    node.has("slot") ? node.get("slot").asInt() : -1,
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y"),
                    parseHexInt(node, "subtype"),
                    parseHexInt(node, "status"),
                    parseHexInt(node, "p1_phase"),
                    parseHexInt(node, "p1_state"),
                    parseHexInt(node, "p2_phase"),
                    parseHexInt(node, "p2_state")
                );
                case "cage_execution" -> {
                    java.util.List<CageExecution.Hit> hits = new java.util.ArrayList<>();
                    JsonNode hitsNode = node.get("hits");
                    if (hitsNode != null && hitsNode.isArray()) {
                        for (JsonNode h : hitsNode) {
                            hits.add(new CageExecution.Hit(
                                h.has("branch") ? h.get("branch").asText() : "",
                                parseHexInt(h, "pc"),
                                parseHexInt(h, "cage_addr"),
                                parseHexInt(h, "player_addr"),
                                parseHexInt(h, "state_addr"),
                                parseHexInt(h, "d5"),
                                parseHexInt(h, "d6"),
                                parseHexInt(h, "state_byte"),
                                parseHexInt(h, "player_status"),
                                parseHexInt(h, "player_obj_ctrl"),
                                parseHexInt(h, "cage_status")
                            ));
                        }
                    }
                    yield new CageExecution(frame, hits);
                }
                case "interact_state" -> new InteractState(
                    frame,
                    parseCharacter(node),
                    parseHexInt(node, "interact"),
                    node.has("interact_slot") ? node.get("interact_slot").asInt() : 0,
                    parseHexInt(node, "status"),
                    parseHexInt(node, "status_secondary"),
                    parseHexInt(node, "object_control")
                );
                case "velocity_write" -> {
                    java.util.List<VelocityWrite.Hit> xWrites = new java.util.ArrayList<>();
                    JsonNode xWritesNode = node.get("x_vel_writes");
                    if (xWritesNode != null && xWritesNode.isArray()) {
                        for (JsonNode h : xWritesNode) {
                            xWrites.add(new VelocityWrite.Hit(
                                parseHexInt(h, "pc"),
                                parseHexInt(h, "val")
                            ));
                        }
                    }
                    java.util.List<VelocityWrite.Hit> yWrites = new java.util.ArrayList<>();
                    JsonNode yWritesNode = node.get("y_vel_writes");
                    if (yWritesNode != null && yWritesNode.isArray()) {
                        for (JsonNode h : yWritesNode) {
                            yWrites.add(new VelocityWrite.Hit(
                                parseHexInt(h, "pc"),
                                parseHexInt(h, "val")
                            ));
                        }
                    }
                    yield new VelocityWrite(frame, parseCharacter(node), xWrites, yWrites);
                }
                case "position_write" -> {
                    java.util.List<PositionWrite.Hit> xWrites = new java.util.ArrayList<>();
                    JsonNode xWritesNode = node.get("x_pos_writes");
                    if (xWritesNode != null && xWritesNode.isArray()) {
                        for (JsonNode h : xWritesNode) {
                            xWrites.add(new PositionWrite.Hit(
                                parseHexInt(h, "pc"),
                                parseHexInt(h, "val"),
                                h.has("a1") ? parseHexInt(h, "a1") : 0,
                                h.has("a0") ? parseHexInt(h, "a0") : 0
                            ));
                        }
                    }
                    java.util.List<PositionWrite.Hit> yWrites = new java.util.ArrayList<>();
                    JsonNode yWritesNode = node.get("y_pos_writes");
                    if (yWritesNode != null && yWritesNode.isArray()) {
                        for (JsonNode h : yWritesNode) {
                            yWrites.add(new PositionWrite.Hit(
                                parseHexInt(h, "pc"),
                                parseHexInt(h, "val"),
                                h.has("a1") ? parseHexInt(h, "a1") : 0,
                                h.has("a0") ? parseHexInt(h, "a0") : 0
                            ));
                        }
                    }
                    yield new PositionWrite(frame, parseCharacter(node), xWrites, yWrites);
                }
                case "aiz_ship_loop" -> {
                    java.util.List<AizShipLoop.Hit> hits = new java.util.ArrayList<>();
                    JsonNode hitsNode = node.get("hits");
                    if (hitsNode != null && hitsNode.isArray()) {
                        for (JsonNode h : hitsNode) {
                            hits.add(new AizShipLoop.Hit(
                                h.has("label") ? h.get("label").asText() : "",
                                parseHexInt(h, "pc"),
                                h.has("character") ? h.get("character").asText() : "",
                                parseHexInt(h, "a1"),
                                parseHexInt(h, "d0"),
                                parseHexInt(h, "d1"),
                                parseHexInt(h, "camera_x"),
                                parseHexInt(h, "camera_min_x"),
                                parseHexInt(h, "camera_max_x"),
                                parseHexInt(h, "events_bg_2"),
                                parseHexInt(h, "player_x"),
                                parseHexInt(h, "player_y"),
                                parseHexInt(h, "player_gvel"),
                                parseHexInt(h, "player_xvel"),
                                parseHexInt(h, "player_anim"),
                                parseHexInt(h, "player_status")
                            ));
                        }
                    }
                    yield new AizShipLoop(frame, hits);
                }
                case "sonic_record_pos" -> {
                    java.util.List<SonicRecordPos.Hit> hits = new java.util.ArrayList<>();
                    JsonNode hitsNode = node.get("hits");
                    if (hitsNode != null && hitsNode.isArray()) {
                        for (JsonNode h : hitsNode) {
                            hits.add(new SonicRecordPos.Hit(
                                parseHexInt(h, "pc"),
                                parseHexInt(h, "pos_table_index"),
                                parseHexInt(h, "ctrl1_logical"),
                                h.has("ctrl1_locked") ? h.get("ctrl1_locked").asInt() : 0,
                                parseHexInt(h, "ctrl1_raw"),
                                parseHexInt(h, "object_control"),
                                parseHexInt(h, "status"),
                                parseHexInt(h, "status_secondary"),
                                parseHexInt(h, "x"),
                                parseHexInt(h, "y")
                            ));
                        }
                    }
                    yield new SonicRecordPos(frame, hits);
                }
                case "tails_cpu_normal_step" -> new TailsCpuNormalStep(
                    frame,
                    parseCharacter(node),
                    parseHexInt(node, "status"),
                    parseHexInt(node, "object_control"),
                    parseHexInt(node, "ground_vel"),
                    parseHexInt(node, "x_vel"),
                    parseHexInt(node, "delayed_stat"),
                    parseHexInt(node, "delayed_input"),
                    node.has("pos_table_index") ? parseHexInt(node, "pos_table_index") : 0,
                    node.has("delayed_target_x") ? parseHexInt(node, "delayed_target_x") : 0,
                    node.has("delayed_target_y") ? parseHexInt(node, "delayed_target_y") : 0,
                    node.has("follow_dx") ? parseHexInt(node, "follow_dx") : 0,
                    node.has("follow_dy") ? parseHexInt(node, "follow_dy") : 0,
                    node.has("loc_13dd0_branch") ? node.get("loc_13dd0_branch").asText() : "",
                    parseHexInt(node, "ctrl2_logical"),
                    parseHexInt(node, "ctrl2_held_logical"),
                    parseHexInt(node, "path_pre_ground_vel"),
                    parseHexInt(node, "path_pre_x_vel"),
                    parseHexInt(node, "path_pre_status"),
                    parseHexInt(node, "path_post_ground_vel"),
                    parseHexInt(node, "path_post_x_vel"),
                    parseHexInt(node, "path_post_status")
                );
                case "sidekick_interact_object" -> new SidekickInteractObjectState(
                    frame,
                    parseCharacter(node),
                    parseHexInt(node, "interact"),
                    node.has("interact_slot") ? node.get("interact_slot").asInt() : 0,
                    parseHexInt(node, "tails_render_flags"),
                    parseHexInt(node, "tails_object_control"),
                    parseHexInt(node, "tails_status"),
                    parseHexIntOrDefault(node, "tails_invulnerability_timer", -1),
                    parseHexIntOrDefault(node, "tails_width_pixels", -1),
                    parseHexIntOrDefault(node, "tails_height_pixels", -1),
                    parseHexIntOrDefault(node, "camera_x_copy", -1),
                    parseHexIntOrDefault(node, "camera_y_copy", -1),
                    node.has("tails_on_object") && node.get("tails_on_object").asBoolean(),
                    parseHexInt(node, "object_code"),
                    parseHexInt(node, "object_routine"),
                    parseHexInt(node, "object_status"),
                    parseHexShort(node, "object_x"),
                    parseHexShort(node, "object_y"),
                    parseHexInt(node, "object_subtype"),
                    parseHexInt(node, "object_render_flags"),
                    parseHexInt(node, "object_object_control"),
                    node.has("object_active") && node.get("object_active").asBoolean(),
                    node.has("object_destroyed") && node.get("object_destroyed").asBoolean(),
                    node.has("object_p1_standing") && node.get("object_p1_standing").asBoolean(),
                    node.has("object_p2_standing") && node.get("object_p2_standing").asBoolean()
                );
                case "cnz_cylinder_state" -> new CnzCylinderState(
                    frame,
                    node.has("slot") ? node.get("slot").asInt() : -1,
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y"),
                    parseHexInt(node, "subtype"),
                    parseHexInt(node, "status"),
                    parseHexInt(node, "routine"),
                    parseHexInt(node, "render_flags"),
                    parseHexInt(node, "p1_state"),
                    parseHexInt(node, "p1_angle"),
                    parseHexInt(node, "p1_distance"),
                    parseHexInt(node, "p1_threshold"),
                    parseHexInt(node, "p2_state"),
                    parseHexInt(node, "p2_angle"),
                    parseHexInt(node, "p2_distance"),
                    parseHexInt(node, "p2_threshold")
                );
                case "cnz_cylinder_execution" -> {
                    java.util.List<CnzCylinderExecution.Hit> hits = new java.util.ArrayList<>();
                    JsonNode hitsNode = node.get("hits");
                    if (hitsNode != null && hitsNode.isArray()) {
                        for (JsonNode h : hitsNode) {
                            hits.add(new CnzCylinderExecution.Hit(
                                h.has("branch") ? h.get("branch").asText() : "",
                                parseHexInt(h, "pc"),
                                parseHexInt(h, "cylinder_addr"),
                                parseHexInt(h, "player_addr"),
                                parseHexInt(h, "state_addr"),
                                parseHexInt(h, "d2"),
                                parseHexInt(h, "d4"),
                                parseHexInt(h, "d5"),
                                parseHexInt(h, "d6"),
                                parseHexInt(h, "cylinder_status"),
                                parseHexInt(h, "slot_state"),
                                parseHexInt(h, "slot_angle"),
                                parseHexInt(h, "slot_distance"),
                                parseHexInt(h, "slot_threshold"),
                                parseHexInt(h, "player_x"),
                                parseHexInt(h, "player_x_sub"),
                                parseHexInt(h, "player_y"),
                                parseHexInt(h, "player_y_sub"),
                                parseHexInt(h, "player_status"),
                                parseHexInt(h, "player_obj_ctrl")
                            ));
                        }
                    }
                    yield new CnzCylinderExecution(frame, hits);
                }
                case "cnz_event_ram" -> {
                    java.util.List<CnzEventRamState.ScrollControlSlot> slots =
                            new java.util.ArrayList<>();
                    JsonNode slotsNode = node.get("scroll_slots");
                    if (slotsNode != null && slotsNode.isArray()) {
                        for (JsonNode s : slotsNode) {
                            slots.add(new CnzEventRamState.ScrollControlSlot(
                                    s.has("slot") ? s.get("slot").asInt() : -1,
                                    parseHexInt(s, "addr"),
                                    parseHexInt(s, "object_code"),
                                    parseHexInt(s, "routine"),
                                    parseHexInt(s, "routine_secondary"),
                                    parseHexInt(s, "x"),
                                    parseHexInt(s, "y"),
                                    parseHexInt(s, "status"),
                                    parseHexInt(s, "subtype"),
                                    parseHexInt(s, "objoff_2e"),
                                    parseHexInt(s, "objoff_30"),
                                    parseHexInt(s, "objoff_32"),
                                    parseHexInt(s, "objoff_34"),
                                    parseHexInt(s, "objoff_36"),
                                    parseHexInt(s, "objoff_38")
                            ));
                        }
                    }
                    yield new CnzEventRamState(
                            frame,
                            parseHexInt(node, "events_bg_00_word"),
                            parseHexInt(node, "events_bg_02_word"),
                            parseHexInt(node, "events_bg_08_word"),
                            parseHexInt(node, "events_bg_08_long"),
                            parseHexInt(node, "events_bg_0c_word"),
                            parseHexInt(node, "events_bg_0c_long"),
                            parseHexInt(node, "events_routine_bg"),
                            parseHexInt(node, "background_collision_flag"),
                            parseHexInt(node, "events_fg_5"),
                            parseHexInt(node, "camera_y"),
                            parseHexInt(node, "camera_max_y"),
                            parseHexInt(node, "camera_target_max_y"),
                            slots);
                }
                case "air_countdown_state" -> {
                    java.util.List<AirCountdownState.VisibleChild> children =
                            new java.util.ArrayList<>();
                    JsonNode childrenNode = node.get("visible_children");
                    if (childrenNode != null && childrenNode.isArray()) {
                        for (JsonNode child : childrenNode) {
                            children.add(new AirCountdownState.VisibleChild(
                                    child.has("slot") ? child.get("slot").asInt() : -1,
                                    parseHexInt(child, "object_code"),
                                    parseHexInt(child, "routine"),
                                    parseHexInt(child, "subtype"),
                                    parseHexInt(child, "x"),
                                    parseHexInt(child, "y"),
                                    parseHexInt(child, "x_sub"),
                                    parseHexInt(child, "y_sub"),
                                    parseHexInt(child, "y_vel"),
                                    parseHexInt(child, "render_flags"),
                                    parseHexInt(child, "anim"),
                                    parseHexInt(child, "mapping_frame"),
                                    parseHexInt(child, "anim_frame"),
                                    parseHexInt(child, "anim_frame_timer"),
                                    parseHexInt(child, "angle"),
                                    parseHexInt(child, "obj34"),
                                    parseHexInt(child, "obj3c"),
                                    parseHexInt(child, "parent_ptr")
                            ));
                        }
                    }
                    yield new AirCountdownState(
                            frame,
                            node.has("owner") ? node.get("owner").asText() : "",
                            node.has("fixed_slot") ? node.get("fixed_slot").asInt() : -1,
                            parseHexInt(node, "object_code"),
                            parseHexInt(node, "routine"),
                            parseHexInt(node, "subtype"),
                            parseHexInt(node, "obj30"),
                            parseHexInt(node, "obj36"),
                            parseHexInt(node, "obj37"),
                            parseHexInt(node, "obj38"),
                            parseHexInt(node, "obj3a"),
                            parseHexInt(node, "obj3c"),
                            parseHexInt(node, "obj3e"),
                            parseHexInt(node, "owner_ptr"),
                            node.has("owner_resolved") ? node.get("owner_resolved").asText() : "",
                            parseHexInt(node, "owner_air_left"),
                            parseHexInt(node, "owner_status"),
                            parseHexInt(node, "owner_status_secondary"),
                            node.has("owner_facing_left") && node.get("owner_facing_left").asBoolean(),
                            node.has("owner_underwater") && node.get("owner_underwater").asBoolean(),
                            parseHexInt(node, "rng_seed"),
                            children);
                }
                case "rng_call" -> {
                    java.util.List<RngCall.Hit> hits = new java.util.ArrayList<>();
                    JsonNode hitsNode = node.get("hits");
                    if (hitsNode != null && hitsNode.isArray()) {
                        for (JsonNode h : hitsNode) {
                            hits.add(new RngCall.Hit(
                                    parseHexInt(h, "pc"),
                                    parseHexInt(h, "caller_pc"),
                                    h.has("source") ? h.get("source").asText() : "",
                                    parseHexInt(h, "seed_before"),
                                    parseHexInt(h, "seed_after"),
                                    parseHexInt(h, "result"),
                                    parseHexInt(h, "result_byte"),
                                    parseRngObjectContext(h, "a0"),
                                    parseRngObjectContext(h, "a1")
                            ));
                        }
                    }
                    yield new RngCall(frame, hits);
                }
                case "aiz_boundary_state" -> new AizBoundaryState(
                    frame,
                    parseCharacter(node),
                    parseHexInt(node, "camera_min_x"),
                    parseHexInt(node, "camera_max_x"),
                    parseHexInt(node, "camera_min_y"),
                    parseHexInt(node, "camera_max_y"),
                    parseHexInt(node, "tree_pre_x"),
                    parseHexInt(node, "tree_pre_y"),
                    parseHexInt(node, "tree_pre_x_vel"),
                    parseHexInt(node, "tree_pre_y_vel"),
                    parseHexInt(node, "tree_post_x"),
                    parseHexInt(node, "tree_post_y"),
                    parseHexInt(node, "tree_post_x_vel"),
                    parseHexInt(node, "tree_post_y_vel"),
                    parseHexInt(node, "boundary_pre_x"),
                    parseHexInt(node, "boundary_pre_y"),
                    parseHexInt(node, "boundary_pre_x_vel"),
                    parseHexInt(node, "boundary_pre_y_vel"),
                    parseHexInt(node, "boundary_post_x"),
                    parseHexInt(node, "boundary_post_y"),
                    parseHexInt(node, "boundary_post_x_vel"),
                    parseHexInt(node, "boundary_post_y_vel"),
                    node.has("boundary_action") ? node.get("boundary_action").asText() : "",
                    parseHexInt(node, "post_move_x"),
                    parseHexInt(node, "post_move_y"),
                    parseHexInt(node, "post_move_x_vel"),
                    parseHexInt(node, "post_move_y_vel")
                );
                case "aiz_fire_transition" -> new AizFireTransition(
                    frame,
                    parseHexInt(node, "camera_y_bg_copy"),
                    parseHexInt(node, "camera_y_bg_rounded"),
                    parseHexInt(node, "events_bg_00_word"),
                    parseHexInt(node, "events_bg_02_word"),
                    parseHexInt(node, "events_routine_bg"),
                    parseHexInt(node, "events_fg_5"),
                    parseHexInt(node, "camera_x"),
                    parseHexInt(node, "camera_min_x"),
                    parseHexInt(node, "camera_max_x"),
                    parseHexInt(node, "player_x"),
                    parseHexInt(node, "act")
                );
                case "aiz_transition_floor_solid" -> new AizTransitionFloorSolidState(
                    frame,
                    node.has("slot") ? node.get("slot").asInt() : -1,
                    parseHexInt(node, "object_status"),
                    parseHexInt(node, "object_x"),
                    parseHexInt(node, "object_y"),
                    node.has("p1_standing") && node.get("p1_standing").asBoolean(),
                    node.has("p2_standing") && node.get("p2_standing").asBoolean(),
                    node.has("p1_path") ? node.get("p1_path").asText() : "",
                    node.has("p2_path") ? node.get("p2_path").asText() : "",
                    parseHexInt(node, "p1_d1"),
                    parseHexInt(node, "p1_d2"),
                    parseHexInt(node, "p1_d3"),
                    parseHexInt(node, "p1_status"),
                    parseHexInt(node, "p1_object_control"),
                    parseHexInt(node, "p1_y_radius"),
                    parseHexInt(node, "p1_x"),
                    parseHexInt(node, "p1_y"),
                    parseHexInt(node, "p1_y_vel"),
                    node.has("p1_interact_slot") ? node.get("p1_interact_slot").asInt() : -1,
                    parseHexInt(node, "p2_d1"),
                    parseHexInt(node, "p2_d2"),
                    parseHexInt(node, "p2_d3"),
                    parseHexInt(node, "p2_status"),
                    parseHexInt(node, "p2_object_control"),
                    parseHexInt(node, "p2_y_radius"),
                    parseHexInt(node, "p2_x"),
                    parseHexInt(node, "p2_y"),
                    parseHexInt(node, "p2_y_vel"),
                    node.has("p2_interact_slot") ? node.get("p2_interact_slot").asInt() : -1
                );
                case "aiz_handoff_terrain_state" -> new AizHandoffTerrainState(
                    frame,
                    parseHexInt(node, "events_bg"),
                    parseHexInt(node, "draw_pos"),
                    parseHexInt(node, "draw_rows"),
                    parseHexInt(node, "kos_modules_left"),
                    parseHexInt(node, "current_zone_act"),
                    parseHexInt(node, "dynamic_resize"),
                    parseHexInt(node, "object_load"),
                    parseHexInt(node, "rings_manager"),
                    parseHexInt(node, "p1_x"),
                    parseHexInt(node, "p1_y"),
                    parseHexInt(node, "p1_status"),
                    parseHexInt(node, "p1_y_radius"),
                    parseHexInt(node, "p1_top_solid"),
                    node.has("sonic_floor_seen") && node.get("sonic_floor_seen").asBoolean(),
                    parseHexInt(node, "sonic_floor_distance"),
                    parseHexInt(node, "sonic_floor_angle"),
                    parseHexInt(node, "sonic_floor_probe_x"),
                    parseHexInt(node, "sonic_floor_probe_y"),
                    node.has("solid_vertical_seen") && node.get("solid_vertical_seen").asBoolean(),
                    parseHexInt(node, "solid_pre_y"),
                    parseHexInt(node, "solid_surface_y"),
                    parseHexInt(node, "solid_delta")
                );
                default -> {
                    if (failOnUnknownEvent && node.has("event")
                            && !KNOWN_GENERIC_NATIVE_EVENTS.contains(event)) {
                        throw new IllegalArgumentException(
                                "unknown advertised event type: " + event);
                    }
                    // state_snapshot or unknown: preserve all fields as map.
                    // Generic per-frame events (e.g. S3K object_state) dominate
                    // long complete-run aux files by the hundreds of thousands,
                    // so store them as a compact array-backed map with interned
                    // strings instead of one LinkedHashMap per event.
                    List<String> keys = new ArrayList<>();
                    List<Object> values = new ArrayList<>();
                    node.fields().forEachRemaining(entry -> {
                        keys.add(entry.getKey().intern());
                        Object value = nodeToValue(entry.getValue());
                        values.add(value instanceof String s ? s.intern() : value);
                    });
                    yield new StateSnapshot(frame, CompactFieldMap.of(keys, values));
                }
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSONL line: " + jsonLine, e);
        }
    }

    private static DynamicArtTransferState parseDynamicArtTransferState(
            int frame, JsonNode node) {
        List<DynamicArtTransfer.SegmentEdge> edges = new java.util.ArrayList<>();
        for (JsonNode edge : DynamicArtTransfer.requiredArray(node, "edges")) {
            edges.add(DynamicArtTransfer.parseSegmentEdge(edge));
        }
        List<Long> outstanding = new java.util.ArrayList<>();
        for (JsonNode id : DynamicArtTransfer.requiredArray(
                node, "outstanding_transfer_ids")) {
            if (!id.isIntegralNumber() || !id.canConvertToLong()) {
                throw new IllegalArgumentException(
                        "invalid outstanding transfer id");
            }
            outstanding.add(id.asLong());
        }
        return new DynamicArtTransferState(frame, edges, outstanding);
    }

    private static CnzSlotMachineState parseCnzSlotMachineState(int frame, JsonNode node) {
        List<Integer> pos = new ArrayList<>();
        List<Integer> speed = new ArrayList<>();
        List<Integer> routine = new ArrayList<>();
        for (int slot = 1; slot <= 3; slot++) {
            pos.add(parseHexInt(node, "slot" + slot + "_pos") & 0xFFFF);
            speed.add(parseHexInt(node, "slot" + slot + "_speed") & 0xFF);
            routine.add(parseHexInt(node, "slot" + slot + "_routine") & 0xFF);
        }
        return new CnzSlotMachineState(
                frame,
                parseHexInt(node, "vbc") & 0xFFFF,
                (parseHexInt(node, "in_use") & 0xFFFF) != 0,
                parseHexInt(node, "routine") & 0xFF,
                parseHexInt(node, "timer") & 0xFF,
                parseHexInt(node, "index") & 0xFF,
                parseHexInt(node, "reward") & 0xFFFF,
                pos,
                speed,
                routine);
    }

    private static LoadQueueState parseLoadQueueState(int frame, JsonNode node) {
        String kind = requiredText(node, "kind");
        boolean busy = requiredBoolean(node, "busy");
        boolean prepared = requiredBoolean(node, "prepared");
        int activeSource = requiredInt(node, "active_source");
        int activeDestination = requiredInt(node, "active_destination");
        int totalWork = requiredInt(node, "total_work");
        int remainingWork = requiredInt(node, "remaining_work");
        JsonNode queued = requiredArray(node, "queued_fingerprints");
        java.util.ArrayList<String> fingerprints = new java.util.ArrayList<>();
        for (JsonNode value : queued) {
            if (!value.isTextual() || !value.asText().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid queued fingerprint");
            }
            fingerprints.add(value.asText());
        }
        JsonNode observationsNode = requiredArray(node, "service_observations");
        if (!observationsNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "version 1 service observations must be empty");
        }
        if (!busy && (prepared || activeSource != -1 || activeDestination != -1
                || totalWork != -1 || remainingWork != -1
                || !fingerprints.isEmpty())) {
            throw new IllegalArgumentException("idle load queue state is not canonical");
        }
        return new LoadQueueState(frame, kind, busy, prepared, activeSource,
                activeDestination, totalWork, remainingWork,
                List.copyOf(fingerprints), List.of());
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("missing or invalid array field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing or invalid text field: " + field);
        }
        return value.asText();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("missing or invalid boolean field: " + field);
        }
        return value.asBoolean();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("missing or invalid integer field: " + field);
        }
        return value.asInt();
    }

    private static short parseHexShort(JsonNode node, String... fields) {
        for (String field : fields) {
            if (!node.has(field)) {
                continue;
            }
            String hex = stripHexPrefix(node.get(field).asText());
            return (short) Integer.parseInt(hex, 16);
        }
        return 0;
    }

    private static short parseHexShortOrDefault(JsonNode node, String field, short defaultValue) {
        if (!node.has(field)) {
            return defaultValue;
        }
        return parseHexShort(node, field);
    }

    private static String parseCharacter(JsonNode node) {
        if (!node.has("character") || node.get("character").isNull()) {
            return null;
        }
        String value = node.get("character").asText();
        return value == null || value.isBlank() ? null : value.intern();
    }

    /** Optional text field, interned: high-count events repeat a small set of values. */
    private static String internedText(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText().intern() : "";
    }

    private static Integer parseNullableInt(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asInt();
    }

    private static int parseHexInt(JsonNode node, String field) {
        if (!node.has(field)) {
            return 0;
        }
        String hex = stripHexPrefix(node.get(field).asText());
        // Some recorder fields (e.g. M68K address-register snapshots a0/a1
        // captured by the v6.11-s3k Lua recorder) are emitted as 64-bit
        // sign-extended values like "0xFFFFFFFFFFFFB000". Parse as a long
        // and cast to int — only the low 32 bits are semantically used.
        return (int) Long.parseUnsignedLong(hex, 16);
    }

    private static int parseHexIntOrDefault(JsonNode node, String field, int defaultValue) {
        if (!node.has(field)) {
            return defaultValue;
        }
        return parseHexInt(node, field);
    }

    private static long parseHexLong(JsonNode node, String field) {
        if (!node.has(field)) {
            return 0L;
        }
        String hex = stripHexPrefix(node.get(field).asText());
        if (hex.isBlank()) {
            return 0L;
        }
        return Long.parseUnsignedLong(hex, 16) & 0xFFFFFFFFL;
    }

    private static RngCall.ObjectContext parseRngObjectContext(JsonNode node, String prefix) {
        return new RngCall.ObjectContext(
                parseHexInt(node, prefix + "_ptr"),
                node.has(prefix + "_slot") ? node.get(prefix + "_slot").asInt() : -1,
                parseHexInt(node, prefix + "_object_code"),
                parseHexInt(node, prefix + "_routine"),
                parseHexInt(node, prefix + "_subtype"),
                parseHexInt(node, prefix + "_x"),
                parseHexInt(node, prefix + "_y")
        );
    }

    private static String stripHexPrefix(String value) {
        return value.replace("0x", "");
    }

    private static Object nodeToValue(JsonNode node) {
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isDouble()) return node.asDouble();
        if (node.isArray() || node.isObject()) return node.toString();
        return node.asText();
    }

    private static short[] parseShortArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new short[0];
        }
        short[] values = new short[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = (short) node.get(i).asInt();
        }
        return values;
    }

    private static byte[] parseByteArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new byte[0];
        }
        byte[] values = new byte[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = (byte) node.get(i).asInt();
        }
        return values;
    }

    /**
     * Parses a contiguous hex string (e.g. "00FF1234...") into a byte array.
     * Used by {@link OscillationState} to decode the recorder's compact
     * representation of {@code Oscillating_table} bytes.
     */
    private static byte[] parseHexByteString(String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0) {
            return new byte[0];
        }
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

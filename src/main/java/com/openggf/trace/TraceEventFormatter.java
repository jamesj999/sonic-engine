package com.openggf.trace;

import java.util.ArrayList;
import java.util.List;

public final class TraceEventFormatter {

    private TraceEventFormatter() {
    }

    /**
     * Renders a single {@link BootstrapDivergence} as a one-line summary
     * suitable for inclusion in text reports or log lines. Pairs with the
     * {@code "=== Bootstrap (frame 0) ===" } block emitted by
     * {@link DivergenceReport#getContextWindow(int, int)}.
     */
    public static String summariseBootstrapDivergence(BootstrapDivergence divergence) {
        if (divergence == null) {
            return "";
        }
        String context = divergence.context() == null ? "" : divergence.context();
        return String.format("[%s] %s expected=%s actual=%s%s",
                divergence.severity().name(),
                divergence.field(),
                divergence.expected(),
                divergence.actual(),
                context.isBlank() ? "" : " (" + context + ")");
    }

    public static String summariseFrameEvents(List<TraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (TraceEvent event : events) {
            String summary = summarise(event);
            if (!summary.isEmpty()) {
                parts.add(summary);
            }
        }
        return String.join(" | ", parts);
    }

    private static String summarise(TraceEvent event) {
        return switch (event) {
            case TraceEvent.ObjectAppeared appeared ->
                    String.format("obj+ s%d %s @%04X,%04X",
                            appeared.slot(),
                            appeared.objectType(),
                            appeared.x() & 0xFFFF,
                            appeared.y() & 0xFFFF);
            case TraceEvent.ObjectRemoved removed ->
                    String.format("obj- s%d %s", removed.slot(), removed.objectType());
            case TraceEvent.ObjectNear near -> {
                String base = String.format("near %ss%d %s @%04X,%04X",
                        characterPrefix(near.character()),
                        near.slot(),
                        near.objectType(),
                        near.x() & 0xFFFF,
                        near.y() & 0xFFFF);
                if (!near.routine().isEmpty()) {
                    base = base + " rtn=" + stripHexPrefix(near.routine());
                }
                // obj_frame (v3.5+) is the object's tilt/anim mapping frame; only
                // render when the trace carries it (empty on older recordings).
                if (near.objFrame() != null && !near.objFrame().isEmpty()) {
                    base = base + " frm=" + stripHexPrefix(near.objFrame());
                }
                // routine2 (ob2ndRout) and objoff_3c (32-bit generic timer / sub-pixel
                // accumulator) are v3.8+; only render when the trace carries them.
                if (near.routine2() != null && !near.routine2().isEmpty()) {
                    base = base + " r2=" + stripHexPrefix(near.routine2());
                }
                if (near.objoff3c() != null && !near.objoff3c().isEmpty()) {
                    base = base + " o3c=" + stripHexPrefix(near.objoff3c());
                }
                // objoff_34/36/38 (per-object counter/timer/sub-state words) are
                // v3.9+; only render when the trace carries them.
                if (near.objoff34() != null && !near.objoff34().isEmpty()) {
                    base = base + " o34=" + stripHexPrefix(near.objoff34());
                }
                if (near.objoff36() != null && !near.objoff36().isEmpty()) {
                    base = base + " o36=" + stripHexPrefix(near.objoff36());
                }
                if (near.objoff38() != null && !near.objoff38().isEmpty()) {
                    base = base + " o38=" + stripHexPrefix(near.objoff38());
                }
                // objoff_32 (gmake_timer for makers) is v3.12+; only render when
                // the trace carries it.
                if (near.objoff32() != null && !near.objoff32().isEmpty()) {
                    base = base + " o32=" + stripHexPrefix(near.objoff32());
                }
                yield base;
            }
            case TraceEvent.ModeChange mode ->
                    String.format("%smode %s %d->%d",
                            characterPrefix(mode.character()),
                            mode.field(),
                            mode.from(),
                            mode.to());
            case TraceEvent.RoutineChange routine ->
                    String.format("%sroutine %s->%s @%04X,%04X",
                            characterPrefix(routine.character()),
                            routine.from(),
                            routine.to(),
                            routine.x() & 0xFFFF,
                            routine.y() & 0xFFFF);
            case TraceEvent.CpuState cpu ->
                    String.format("%scpu rtn=%04X ctrl=%04X resp=%04X target=%04X,%04X ctrl2=%02X/%02X hist=%02X/%02X stat=%02X in=%04X",
                            characterPrefix(cpu.character()),
                            cpu.cpuRoutine() & 0xFFFF,
                            cpu.idleTimer() & 0xFFFF,
                            cpu.flightTimer() & 0xFFFF,
                            cpu.targetX() & 0xFFFF,
                            cpu.targetY() & 0xFFFF,
                            cpu.ctrl2Held() & 0xFF,
                            cpu.ctrl2Pressed() & 0xFF,
                            cpu.posTableIndex() & 0xFF,
                            cpu.delayedIndex() & 0xFF,
                            cpu.delayedStatus() & 0xFF,
                            cpu.delayedInput() & 0xFFFF);
            case TraceEvent.Checkpoint checkpoint ->
                    String.format("cp %s z=%s a=%s ap=%s gm=%s",
                            checkpoint.name(),
                            nullableInt(checkpoint.actualZoneId()),
                            nullableInt(checkpoint.actualAct()),
                            nullableInt(checkpoint.apparentAct()),
                            nullableInt(checkpoint.gameMode()));
            case TraceEvent.ZoneActState state ->
                    String.format("zoneact z=%s a=%s ap=%s gm=%s",
                            nullableInt(state.actualZoneId()),
                            nullableInt(state.actualAct()),
                            nullableInt(state.apparentAct()),
                            nullableInt(state.gameMode()));
            case TraceEvent.CageState cage ->
                    String.format("cage s%d @%04X,%04X sub=%02X st=%02X p1=%02X/%02X p2=%02X/%02X",
                            cage.slot(),
                            cage.x() & 0xFFFF,
                            cage.y() & 0xFFFF,
                            cage.subtype() & 0xFF,
                            cage.status() & 0xFF,
                            cage.p1Phase() & 0xFF,
                            cage.p1State() & 0xFF,
                            cage.p2Phase() & 0xFF,
                            cage.p2State() & 0xFF);
            case TraceEvent.CageExecution execution ->
                    summariseCageExecution(execution);
            case TraceEvent.VelocityWrite write ->
                    summariseVelocityWrite(write);
            case TraceEvent.PositionWrite write ->
                    summarisePositionWrite(write);
            case TraceEvent.AizShipLoop shipLoop ->
                    summariseAizShipLoop(shipLoop);
            case TraceEvent.SonicRecordPos recordPos ->
                    summariseSonicRecordPos(recordPos);
            case TraceEvent.TailsCpuNormalStep step ->
                    summariseTailsCpuNormalStep(step);
            case TraceEvent.SidekickInteractObjectState state ->
                    String.format("%sInteract slot=%d ptr=%04X obj=%08X rtn=%02X st=%02X @%04X,%04X sub=%02X %s rf=%02X%s%s obj=%02X onObj=%s objP2=%s active=%s destroyed=%s",
                            state.character() == null || state.character().isBlank()
                                    ? "sidekick"
                                    : state.character(),
                            state.interactSlot(),
                            state.interact() & 0xFFFF,
                            state.objectCode(),
                            state.objectRoutine() & 0xFF,
                            state.objectStatus() & 0xFF,
                            state.objectX() & 0xFFFF,
                            state.objectY() & 0xFFFF,
                            state.objectSubtype() & 0xFF,
                            state.character() == null || state.character().isBlank()
                                    ? "sidekick"
                                    : state.character(),
                            state.tailsRenderFlags() & 0xFF,
                            state.tailsInvulnerabilityTimer() >= 0
                                    ? String.format(" inv=%02X", state.tailsInvulnerabilityTimer() & 0xFF)
                                    : "",
                            state.tailsWidthPixels() >= 0
                                    ? String.format(" wh=%02X/%02X camCopy=%04X,%04X",
                                            state.tailsWidthPixels() & 0xFF,
                                            state.tailsHeightPixels() & 0xFF,
                                            state.cameraXCopy() & 0xFFFF,
                                            state.cameraYCopy() & 0xFFFF)
                                    : "",
                            state.tailsObjectControl() & 0xFF,
                            state.tailsOnObject(),
                            state.objectP2Standing(),
                            state.objectActive(),
                            state.objectDestroyed());
            case TraceEvent.CnzCylinderState state ->
                    String.format("cnzCyl s%d @%04X,%04X st=%02X p1=%02X/%02X/%02X/%02X p2=%02X/%02X/%02X/%02X",
                            state.slot(),
                            state.x() & 0xFFFF,
                            state.y() & 0xFFFF,
                            state.status() & 0xFF,
                            state.p1State() & 0xFF,
                            state.p1Angle() & 0xFF,
                            state.p1Distance() & 0xFF,
                            state.p1Threshold() & 0xFF,
                            state.p2State() & 0xFF,
                            state.p2Angle() & 0xFF,
                            state.p2Distance() & 0xFF,
                            state.p2Threshold() & 0xFF);
            case TraceEvent.CnzCylinderExecution execution ->
                    summariseCnzCylinderExecution(execution);
            case TraceEvent.CnzEventRamState state ->
                    summariseCnzEventRamState(state);
            case TraceEvent.AirCountdownState state ->
                    summariseAirCountdownState(state);
            case TraceEvent.RngCall call ->
                    summariseRngCall(call);
            case TraceEvent.AizBoundaryState state ->
                    String.format("%sAizBoundary cam=%04X/%04X y=%04X/%04X tree=%04X,%04X,%04X,%04X->%04X,%04X,%04X,%04X boundary=%s %04X,%04X,%04X,%04X->%04X,%04X,%04X,%04X post=%04X,%04X,%04X,%04X",
                            state.character() == null || state.character().isBlank()
                                    ? "sidekick"
                                    : state.character(),
                            state.cameraMinX() & 0xFFFF,
                            state.cameraMaxX() & 0xFFFF,
                            state.cameraMinY() & 0xFFFF,
                            state.cameraMaxY() & 0xFFFF,
                            state.treePreX() & 0xFFFF,
                            state.treePreY() & 0xFFFF,
                            state.treePreXVel() & 0xFFFF,
                            state.treePreYVel() & 0xFFFF,
                            state.treePostX() & 0xFFFF,
                            state.treePostY() & 0xFFFF,
                            state.treePostXVel() & 0xFFFF,
                            state.treePostYVel() & 0xFFFF,
                            state.boundaryAction(),
                            state.boundaryPreX() & 0xFFFF,
                            state.boundaryPreY() & 0xFFFF,
                            state.boundaryPreXVel() & 0xFFFF,
                            state.boundaryPreYVel() & 0xFFFF,
                            state.boundaryPostX() & 0xFFFF,
                            state.boundaryPostY() & 0xFFFF,
                            state.boundaryPostXVel() & 0xFFFF,
                            state.boundaryPostYVel() & 0xFFFF,
                            state.postMoveX() & 0xFFFF,
                            state.postMoveY() & 0xFFFF,
                            state.postMoveXVel() & 0xFFFF,
                            state.postMoveYVel() & 0xFFFF);
            case TraceEvent.AizFireTransition state ->
                    String.format("aizFire bgCopy=%08X bgInt=%04X bgRound=%04X speed=%04X "
                                    + "bgTarget=%04X rtnBg=%04X fg5=%04X cam=(%04X,min%04X,max%04X) px=%04X act=%02X",
                            state.cameraYBgCopy(),
                            (state.cameraYBgCopy() >>> 16) & 0xFFFF,
                            state.cameraYBgRounded() & 0xFFFF,
                            state.eventsBg02Word() & 0xFFFF,
                            state.eventsBg00Word() & 0xFFFF,
                            state.eventsRoutineBg() & 0xFFFF,
                            state.eventsFg5() & 0xFFFF,
                            state.cameraX() & 0xFFFF,
                            state.cameraMinX() & 0xFFFF,
                            state.cameraMaxX() & 0xFFFF,
                            state.playerX() & 0xFFFF,
                            state.act() & 0xFF);
            case TraceEvent.AizTransitionFloorSolidState state ->
                    String.format("aizFloor s%d @%04X,%04X st=%02X stand=%s/%s p1=%s y=%04X yr=%02X st=%02X obj=%02X d=%04X/%04X/%04X p2=%s y=%04X yr=%02X st=%02X obj=%02X d=%04X/%04X/%04X",
                            state.slot(),
                            state.objectX() & 0xFFFF,
                            state.objectY() & 0xFFFF,
                            state.objectStatus() & 0xFF,
                            state.p1Standing(),
                            state.p2Standing(),
                            state.p1Path(),
                            state.p1Y() & 0xFFFF,
                            state.p1YRadius() & 0xFF,
                            state.p1Status() & 0xFF,
                            state.p1ObjectControl() & 0xFF,
                            state.p1D1() & 0xFFFF,
                            state.p1D2() & 0xFFFF,
                            state.p1D3() & 0xFFFF,
                            state.p2Path(),
                            state.p2Y() & 0xFFFF,
                            state.p2YRadius() & 0xFF,
                            state.p2Status() & 0xFF,
                            state.p2ObjectControl() & 0xFF,
                            state.p2D1() & 0xFFFF,
                            state.p2D2() & 0xFFFF,
                            state.p2D3() & 0xFFFF);
            case TraceEvent.AizHandoffTerrainState state ->
                    String.format("aizHandoff bg=%04X draw=%04X/%04X kos=%02X za=%04X dyn=%02X objLoad=%02X rings=%02X p1=%04X,%04X st=%02X yr=%02X top=%02X floor=%s d=%04X a=%02X probe=%04X,%04X solid=%s preY=%04X surf=%04X d=%04X",
                            state.eventsBg() & 0xFFFF,
                            state.drawPos() & 0xFFFF,
                            state.drawRows() & 0xFFFF,
                            state.kosModulesLeft() & 0xFF,
                            state.currentZoneAct() & 0xFFFF,
                            state.dynamicResize() & 0xFF,
                            state.objectLoad() & 0xFF,
                            state.ringsManager() & 0xFF,
                            state.p1X() & 0xFFFF,
                            state.p1Y() & 0xFFFF,
                            state.p1Status() & 0xFF,
                            state.p1YRadius() & 0xFF,
                            state.p1TopSolid() & 0xFF,
                            state.sonicFloorSeen() ? "seen" : "none",
                            state.sonicFloorDistance() & 0xFFFF,
                            state.sonicFloorAngle() & 0xFF,
                            state.sonicFloorProbeX() & 0xFFFF,
                            state.sonicFloorProbeY() & 0xFFFF,
                            state.solidVerticalSeen() ? "seen" : "none",
                            state.solidPreY() & 0xFFFF,
                            state.solidSurfaceY() & 0xFFFF,
                            state.solidDelta() & 0xFFFF);
            case TraceEvent.S2TornadoState tornado -> summariseS2Tornado(tornado);
            case TraceEvent.StateSnapshot snapshot -> summariseStateSnapshot(snapshot);
            case TraceEvent.VObjState vObjState -> summariseVObjState(vObjState);
            case TraceEvent.VOscillate vOscillate -> summariseVOscillate(vOscillate);
            case TraceEvent.LagState lagState ->
                    String.format("lagState lagged=%s lagcount=%d",
                        lagState.lagged(), lagState.lagcount());
            case TraceEvent.CameraBoundary cameraBoundary ->
                    String.format(
                        "cameraBoundary limitBtm1=%04X limitBtm2=%04X lookShift=%04X bgScrollVert=%02X",
                        cameraBoundary.limitBtm1() & 0xFFFF,
                        cameraBoundary.limitBtm2() & 0xFFFF,
                        cameraBoundary.lookShift() & 0xFFFF,
                        cameraBoundary.bgScrollVert() & 0xFF);
            case TraceEvent.LoadQueueState queue ->
                    String.format(
                            "queue %s busy=%s prepared=%s remain=%d waiting=%d service=%s",
                            queue.kind(), queue.busy(), queue.prepared(),
                            queue.remainingWork(), queue.queuedFingerprints().size(),
                            queue.serviceObservations().stream()
                                    .map(value -> value.boundary() + ":" + value.budget())
                                    .collect(java.util.stream.Collectors.joining(",")));
            case TraceEvent.DynamicArtTransferState state ->
                    String.format("dynamicArt edges=%d pending=%s",
                            state.edges().size(),
                            state.outstandingTransferIds());
            default -> "";
        };
    }

    /**
     * Compact context summary of the S1 {@code v_objstate} respawn-bit array.
     * The full 192 bytes are too long for the inline context window, so show the
     * ObjPosLoad fwd/bwd counters ([0]/[1]) and every NON-zero remember byte as
     * {@code idx=val} pairs (the set respawn bits are the diagnostic signal at a
     * backward-OPL reload). The full bytes remain available on the
     * {@link TraceEvent.VObjState} record for programmatic comparison.
     */
    private static String summariseVObjState(TraceEvent.VObjState state) {
        byte[] b = state.bytes();
        if (b == null || b.length == 0) {
            return "vObjState (empty)";
        }
        StringBuilder sb = new StringBuilder("vObjState fwdCtr=");
        sb.append(String.format("%02X", b[0] & 0xFF));
        sb.append(" bwdCtr=").append(b.length > 1 ? String.format("%02X", b[1] & 0xFF) : "--");
        sb.append(" set=[");
        boolean first = true;
        for (int i = 2; i < b.length; i++) {
            int v = b[i] & 0xFF;
            if (v == 0) {
                continue;
            }
            if (!first) {
                sb.append(' ');
            }
            sb.append(String.format("%d=%02X", i, v));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Compact context summary of the S1 {@code v_oscillate} global oscillation
     * state. Shows the direction-bitfield word ([0..1]) and, for each of the 16
     * oscillators, the value-word high byte (array offset N*4 = record byte
     * 2+N*4) as {@code N=hh} pairs -- the per-oscillator phase the circling/
     * swinging platforms read. The full $42 bytes remain on the
     * {@link TraceEvent.VOscillate} record for programmatic comparison.
     */
    private static String summariseVOscillate(TraceEvent.VOscillate state) {
        byte[] b = state.bytes();
        if (b == null || b.length == 0) {
            return "vOscillate (empty)";
        }
        StringBuilder sb = new StringBuilder("vOscillate bits=");
        sb.append(b.length > 1
                ? String.format("%02X%02X", b[0] & 0xFF, b[1] & 0xFF)
                : String.format("%02X", b[0] & 0xFF));
        sb.append(" osc=[");
        boolean first = true;
        for (int n = 0; n < 16; n++) {
            int valHiIndex = 2 + n * 4;
            if (valHiIndex >= b.length) {
                break;
            }
            if (!first) {
                sb.append(' ');
            }
            sb.append(String.format("%d=%02X", n, b[valHiIndex] & 0xFF));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Renders ObjB2's SST exactly as the generic-snapshot branch used to, so
     * promoting {@code s2_tornado_state} to a typed event leaves every report
     * and context dump byte-identical.
     */
    private static String summariseS2Tornado(TraceEvent.S2TornadoState t) {
        return String.format(
                "s2Tornado s%d @0x%04X,0x%04X sub=0x%04X yv=0x%04X rtn=0x%02X/0x%02X"
                        + " st=0x%02X 2e=0x%02X 2f=0x%02X 30=0x%02X 31=0x%02X",
                t.slot(), t.x(), t.y(), t.ySub(), t.yVel(),
                t.routine(), t.routineSecondary(), t.statusByte(),
                t.objoff2E(), t.objoff2F(), t.objoff30(), t.objoff31());
    }

    private static String summariseStateSnapshot(TraceEvent.StateSnapshot snapshot) {
        String character = String.valueOf(snapshot.fields().getOrDefault("character", ""));
        String prefix = character == null || character.isBlank()
                ? "state"
                : character + " state";
        return String.format("%s st=%s rtn=%s top=%s lrb=%s onObj=%s push=%s input=%s",
                prefix,
                snapshot.fields().getOrDefault("status_byte", "?"),
                snapshot.fields().getOrDefault("routine", "?"),
                snapshot.fields().getOrDefault("top_solid_bit", "?"),
                snapshot.fields().getOrDefault("lrb_solid_bit", "?"),
                snapshot.fields().getOrDefault("on_object", "?"),
                snapshot.fields().getOrDefault("pushing", "?"),
                snapshot.fields().getOrDefault("raw_input", "?"));
    }

    private static String summariseCageExecution(TraceEvent.CageExecution execution) {
        if (execution.hits().isEmpty()) {
            return "cageExec empty";
        }
        List<String> parts = new ArrayList<>();
        int limit = Math.min(3, execution.hits().size());
        for (int i = 0; i < limit; i++) {
            TraceEvent.CageExecution.Hit hit = execution.hits().get(i);
            parts.add(String.format("%s@%05X cage=%04X player=%04X d5=%04X d6=%02X state=%02X obj=%02X cst=%02X",
                    hit.branch(),
                    hit.pc(),
                    hit.cageAddr(),
                    hit.playerAddr(),
                    hit.d5() & 0xFFFF,
                    hit.d6() & 0xFF,
                    hit.stateByte() & 0xFF,
                    hit.playerObjCtrl() & 0xFF,
                    hit.cageStatus() & 0xFF));
        }
        String suffix = execution.hits().size() > limit
                ? String.format(" +%d", execution.hits().size() - limit)
                : "";
        return "cageExec " + String.join("; ", parts) + suffix;
    }

    private static String summariseCnzCylinderExecution(TraceEvent.CnzCylinderExecution execution) {
        if (execution.hits().isEmpty()) {
            return "cnzCylExec empty";
        }
        List<String> parts = new ArrayList<>();
        int limit = Math.min(5, execution.hits().size());
        for (int i = 0; i < limit; i++) {
            TraceEvent.CnzCylinderExecution.Hit hit = execution.hits().get(i);
            parts.add(String.format("%s@%05X x=%04X.%02X y=%04X.%02X st=%02X obj=%02X slot=%02X/%02X/%02X/%02X d2=%04X d4=%04X",
                    hit.branch(),
                    hit.pc(),
                    hit.playerX() & 0xFFFF,
                    hit.playerXSub() & 0xFF,
                    hit.playerY() & 0xFFFF,
                    hit.playerYSub() & 0xFF,
                    hit.playerStatus() & 0xFF,
                    hit.playerObjectControl() & 0xFF,
                    hit.slotState() & 0xFF,
                    hit.slotAngle() & 0xFF,
                    hit.slotDistance() & 0xFF,
                    hit.slotThreshold() & 0xFF,
                    hit.d2() & 0xFFFF,
                    hit.d4() & 0xFFFF));
        }
        String suffix = execution.hits().size() > limit
                ? String.format(" +%d", execution.hits().size() - limit)
                : "";
        return "cnzCylExec " + String.join("; ", parts) + suffix;
    }

    private static String summariseCnzEventRamState(TraceEvent.CnzEventRamState state) {
        String scroll = "sc=none";
        if (!state.scrollSlots().isEmpty()) {
            TraceEvent.CnzEventRamState.ScrollControlSlot slot = state.scrollSlots().getFirst();
            scroll = String.format("sc=s%d @%04X,%04X rtn=%02X/%02X st=%02X 2e=%02X 30=%02X 32=%02X 34=%02X 36=%02X 38=%02X",
                    slot.slot(),
                    slot.x() & 0xFFFF,
                    slot.y() & 0xFFFF,
                    slot.routine() & 0xFF,
                    slot.routineSecondary() & 0xFF,
                    slot.status() & 0xFF,
                    slot.objoff2e() & 0xFF,
                    slot.objoff30() & 0xFF,
                    slot.objoff32() & 0xFF,
                    slot.objoff34() & 0xFF,
                    slot.objoff36() & 0xFF,
                    slot.objoff38() & 0xFF);
            if (state.scrollSlots().size() > 1) {
                scroll += String.format(" +%d", state.scrollSlots().size() - 1);
            }
        }
        return String.format(
                "cnzEventRAM bg00=%04X bg02=%04X bg08=%04X/%08X bg0c=%04X/%08X bgR=%04X bgCol=%02X fg5=%04X camY=%04X maxY=%04X tgtMaxY=%04X %s",
                state.eventsBg00Word() & 0xFFFF,
                state.eventsBg02Word() & 0xFFFF,
                state.eventsBg08Word() & 0xFFFF,
                state.eventsBg08Long(),
                state.eventsBg0cWord() & 0xFFFF,
                state.eventsBg0cLong(),
                state.eventsRoutineBg() & 0xFFFF,
                state.backgroundCollisionFlag() & 0xFF,
                state.eventsFg5() & 0xFFFF,
                state.cameraY() & 0xFFFF,
                state.cameraMaxY() & 0xFFFF,
                state.cameraTargetMaxY() & 0xFFFF,
                scroll);
    }

    private static String summariseAirCountdownState(TraceEvent.AirCountdownState state) {
        String child = "child=none";
        if (!state.visibleChildren().isEmpty()) {
            TraceEvent.AirCountdownState.VisibleChild first = state.visibleChildren().getFirst();
            child = String.format(
                    "child=s%d @%04X,%04X sub=%02X rtn=%02X yv=%04X rf=%02X anim=%02X map=%02X af=%02X/%02X ang=%02X org=%04X 3C=%04X parent=%08X",
                    first.slot(),
                    first.x() & 0xFFFF,
                    first.y() & 0xFFFF,
                    first.subtype() & 0xFF,
                    first.routine() & 0xFF,
                    first.yVel() & 0xFFFF,
                    first.renderFlags() & 0xFF,
                    first.anim() & 0xFF,
                    first.mappingFrame() & 0xFF,
                    first.animFrame() & 0xFF,
                    first.animFrameTimer() & 0xFF,
                    first.angle() & 0xFF,
                    first.obj34() & 0xFFFF,
                    first.obj3c() & 0xFFFF,
                    first.parentPtr());
            if (state.visibleChildren().size() > 1) {
                child += String.format(" +%d", state.visibleChildren().size() - 1);
            }
        }
        return String.format(
                "airCnt %s fixed=s%d code=%08X rtn=%02X sub=%02X 30=%04X 36=%02X 37=%02X 38=%02X 3A=%04X 3C=%04X 3E=%04X owner=%s ptr=%08X air=%02X st=%02X/%02X face=%s water=%s rng=%08X %s",
                state.owner(),
                state.fixedSlot(),
                state.objectCode(),
                state.routine() & 0xFF,
                state.subtype() & 0xFF,
                state.obj30() & 0xFFFF,
                state.obj36() & 0xFF,
                state.obj37() & 0xFF,
                state.obj38() & 0xFF,
                state.obj3a() & 0xFFFF,
                state.obj3c() & 0xFFFF,
                state.obj3e() & 0xFFFF,
                state.ownerResolved(),
                state.ownerPtr(),
                state.ownerAirLeft() & 0xFF,
                state.ownerStatus() & 0xFF,
                state.ownerStatusSecondary() & 0xFF,
                state.ownerFacingLeft() ? "L" : "R",
                state.ownerUnderwater(),
                state.rngSeed(),
                child);
    }

    private static String summariseRngCall(TraceEvent.RngCall call) {
        List<String> parts = new ArrayList<>();
        int limit = Math.min(5, call.hits().size());
        for (int i = 0; i < limit; i++) {
            TraceEvent.RngCall.Hit hit = call.hits().get(i);
            TraceEvent.RngCall.ObjectContext a0 = hit.a0();
            parts.add(String.format(
                    "pc=%05X ret=%06X %s seed=%08X->%08X res=%08X/%02X a0=s%d %08X @%04X,%04X r=%02X sub=%02X",
                    hit.pc() & 0xFFFFFF,
                    hit.callerPc() & 0xFFFFFF,
                    hit.source(),
                    hit.seedBefore(),
                    hit.seedAfter(),
                    hit.result(),
                    hit.resultByte() & 0xFF,
                    a0.slot(),
                    a0.objectCode(),
                    a0.x() & 0xFFFF,
                    a0.y() & 0xFFFF,
                    a0.routine() & 0xFF,
                    a0.subtype() & 0xFF));
        }
        if (call.hits().size() > limit) {
            parts.add(String.format("+%d", call.hits().size() - limit));
        }
        return parts.isEmpty() ? "rng empty" : "rng " + String.join(" | ", parts);
    }

    private static String summariseTailsCpuNormalStep(TraceEvent.TailsCpuNormalStep step) {
        String base = String.format("tailsCpu status=%02X obj=%02X gv=%04X xv=%04X stat=%02X input=%04X",
                step.status() & 0xFF,
                step.objectControl() & 0xFF,
                step.groundVel() & 0xFFFF,
                step.xVel() & 0xFFFF,
                step.delayedStat() & 0xFF,
                step.delayedInput() & 0xFFFF);
        String target = (step.delayedTargetX() != 0 || step.delayedTargetY() != 0
                || step.followDx() != 0 || step.followDy() != 0)
                ? String.format(" target=%04X,%04X dx=%04X dy=%04X",
                        step.delayedTargetX() & 0xFFFF,
                        step.delayedTargetY() & 0xFFFF,
                        step.followDx() & 0xFFFF,
                        step.followDy() & 0xFFFF)
                : "";
        return String.format("%s%s branch=%s ctrl2=%04X/%02X post=%04X,%04X,%02X",
                base,
                target,
                step.loc13dd0Branch(),
                step.ctrl2Logical() & 0xFFFF,
                step.ctrl2HeldLogical() & 0xFF,
                step.pathPostGroundVel() & 0xFFFF,
                step.pathPostXVel() & 0xFFFF,
                step.pathPostStatus() & 0xFF);
    }

    private static String summariseVelocityWrite(TraceEvent.VelocityWrite write) {
        return summariseWriteHits("tailsVelWrite", write.xVelWrites(), write.yVelWrites());
    }

    private static String summarisePositionWrite(TraceEvent.PositionWrite write) {
        return summariseWriteHits(write.character() + "PosWrite", write.xPosWrites(), write.yPosWrites());
    }

    private static String summariseAizShipLoop(TraceEvent.AizShipLoop shipLoop) {
        List<String> parts = new ArrayList<>();
        int limit = Math.min(6, shipLoop.hits().size());
        for (int i = 0; i < limit; i++) {
            TraceEvent.AizShipLoop.Hit hit = shipLoop.hits().get(i);
            parts.add(String.format(
                    "%s@%05X %s d=%04X/%04X cam=%04X minmax=%04X/%04X bg=%04X p=%04X,%04X gv=%04X xv=%04X anim=%02X st=%02X",
                    hit.label(),
                    hit.pc(),
                    hit.character(),
                    hit.d0() & 0xFFFF,
                    hit.d1() & 0xFFFF,
                    hit.cameraX() & 0xFFFF,
                    hit.cameraMinX() & 0xFFFF,
                    hit.cameraMaxX() & 0xFFFF,
                    hit.eventsBg2() & 0xFFFF,
                    hit.playerX() & 0xFFFF,
                    hit.playerY() & 0xFFFF,
                    hit.playerGvel() & 0xFFFF,
                    hit.playerXvel() & 0xFFFF,
                    hit.playerAnim() & 0xFF,
                    hit.playerStatus() & 0xFF));
        }
        if (shipLoop.hits().size() > limit) {
            parts.add(String.format("+%d", shipLoop.hits().size() - limit));
        }
        return parts.isEmpty() ? "aizShipLoop empty" : "aizShipLoop " + String.join(" | ", parts);
    }

    private static String summariseSonicRecordPos(TraceEvent.SonicRecordPos recordPos) {
        List<String> parts = new ArrayList<>();
        int limit = Math.min(4, recordPos.hits().size());
        for (int i = 0; i < limit; i++) {
            TraceEvent.SonicRecordPos.Hit hit = recordPos.hits().get(i);
            parts.add(String.format(
                    "srcF=%d pc=%05X idx=%02X ctrl=%04X lock=%02X raw=%04X obj=%02X st=%02X/%02X @%04X,%04X",
                    recordPos.frame(),
                    hit.pc(),
                    hit.posTableIndex() & 0xFF,
                    hit.ctrl1Logical() & 0xFFFF,
                    hit.ctrl1Locked() & 0xFF,
                    hit.ctrl1Raw() & 0xFFFF,
                    hit.objectControl() & 0xFF,
                    hit.status() & 0xFF,
                    hit.statusSecondary() & 0xFF,
                    hit.x() & 0xFFFF,
                    hit.y() & 0xFFFF));
        }
        if (recordPos.hits().size() > limit) {
            parts.add(String.format("+%d", recordPos.hits().size() - limit));
        }
        return parts.isEmpty() ? "sonicRecord empty" : "sonicRecord " + String.join(" | ", parts);
    }

    private static String summariseWriteHits(String label,
                                             List<? extends Record> xHits,
                                             List<? extends Record> yHits) {
        List<String> parts = new ArrayList<>();
        appendWriteHits(parts, "x", xHits);
        appendWriteHits(parts, "y", yHits);
        return parts.isEmpty() ? label + " empty" : label + " " + String.join(" ", parts);
    }

    private static void appendWriteHits(List<String> parts, String axis,
                                        List<? extends Record> hits) {
        int limit = Math.min(4, hits.size());
        for (int i = 0; i < limit; i++) {
            Record record = hits.get(i);
            int pc;
            int value;
            if (record instanceof TraceEvent.VelocityWrite.Hit hit) {
                pc = hit.pc();
                value = hit.value();
            } else if (record instanceof TraceEvent.PositionWrite.Hit hit) {
                pc = hit.pc();
                value = hit.value();
            } else {
                continue;
            }
            parts.add(String.format("%s@%05X=%04X", axis, pc, value & 0xFFFF));
        }
        if (hits.size() > limit) {
            parts.add(String.format("%s+%d", axis, hits.size() - limit));
        }
    }

    private static String nullableInt(Integer value) {
        return value == null ? "null" : Integer.toString(value);
    }

    private static String stripHexPrefix(String value) {
        return value.replace("0x", "");
    }

    private static String characterPrefix(String character) {
        if (character == null || character.isBlank() || "sonic".equalsIgnoreCase(character)) {
            return "";
        }
        return character + " ";
    }
}

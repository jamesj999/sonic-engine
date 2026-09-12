package com.openggf.sprites.playable;

/**
 * Builds the comparison-only observations emitted around one normal sidekick CPU step.
 *
 * <p>This class receives scalar samples only. It has no gameplay references or mutable
 * state; {@link SidekickCpuController} retains ownership of the latest record and of every
 * CPU decision.</p>
 */
final class SidekickNormalStepDiagnosticRecorder {

    private SidekickNormalStepDiagnosticRecorder() {
    }

    static SidekickCpuController.NormalStepDiagnostics begin(
            int frameCounter,
            SidekickCpuController.State state,
            String branch,
            int status,
            int objectControl,
            short xVel,
            short yVel,
            short groundVel,
            short x,
            short xSubpixel,
            byte angle) {
        return new SidekickCpuController.NormalStepDiagnostics(
                frameCounter, state, branch,
                status, objectControl, xVel, yVel, groundVel, x, xSubpixel, angle,
                -1, -1, 0, 0, 0, -1, -1, 0, 0,
                status, objectControl, xVel, yVel, groundVel, x, xSubpixel, angle,
                0, false,
                0, 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (byte) 0,
                false, false);
    }

    static SidekickCpuController.NormalStepDiagnostics recordCpuResult(
            SidekickCpuController.NormalStepDiagnostics base,
            String branch,
            int followDelayFrames,
            int followHistorySlot,
            int recordedInput,
            int recordedStatus,
            int pushBypassStatus,
            int dx,
            int dy,
            int generatedInput,
            int generatedPressedInput,
            int postCpuStatus,
            int postCpuObjectControl,
            short postCpuXVel,
            short postCpuYVel,
            short postCpuGroundVel,
            short postCpuX,
            short postCpuXSubpixel,
            byte postCpuAngle,
            int appliedFollowNudge,
            boolean inputJumpPress,
            boolean skipFollowSteering) {
        return base.withCpuResult(
                branch, followDelayFrames, followHistorySlot, recordedInput, recordedStatus,
                pushBypassStatus, dx, dy, generatedInput, generatedPressedInput,
                postCpuStatus, postCpuObjectControl, postCpuXVel, postCpuYVel, postCpuGroundVel,
                postCpuX, postCpuXSubpixel, postCpuAngle, appliedFollowNudge, inputJumpPress,
                skipFollowSteering);
    }

    static SidekickCpuController.NormalStepDiagnostics recordPostPhysics(
            SidekickCpuController.NormalStepDiagnostics current,
            int frameCounter,
            int status,
            int objectControl,
            short xVel,
            short yVel,
            short groundVel,
            short x,
            short xSubpixel,
            byte angle) {
        if (current == null || current.frameCounter() != frameCounter) {
            return current;
        }
        return current.withPostPhysics(status, objectControl, xVel, yVel, groundVel, x, xSubpixel, angle);
    }

    static String format(SidekickCpuController.NormalStepDiagnostics d, int normalPushingGraceFrames) {
        if (d == null) {
            return "eng-tails-cpu none";
        }
        return String.format(
                "eng-tails-cpu f=%d state=%s branch=%s hist=%d/%02d in=%04X stat=%02X push=%02X "
                        + "pre=obj%02X st%02X x=%04X.%04X xv%04X yv%04X gv%04X a=%02X "
                        + "gen=%04X jp=%s postCpu=obj%02X st%02X xv%04X yv%04X gv%04X "
                        + "x=%04X.%04X a=%02X nudge=%d postPhys=%s obj%02X st%02X "
                        + "xv%04X yv%04X gv%04X x=%04X.%04X a=%02X dx=%04X dy=%04X skip=%s grace=%d",
                d.frameCounter(), d.state(), d.followBranch(), d.followDelayFrames(), d.followHistorySlot(),
                d.recordedInput() & 0xFFFF, d.recordedStatus() & 0xFF, d.pushBypassStatus() & 0xFF,
                d.preObjectControl() & 0xFF, d.preStatus() & 0xFF, d.preCpuX() & 0xFFFF,
                d.preCpuXSubpixel() & 0xFFFF, d.preXVel() & 0xFFFF, d.preYVel() & 0xFFFF,
                d.preGroundVel() & 0xFFFF, d.preAngle() & 0xFF, d.generatedInput() & 0xFFFF,
                d.inputJumpPress(), d.postCpuObjectControl() & 0xFF, d.postCpuStatus() & 0xFF,
                d.postCpuXVel() & 0xFFFF, d.postCpuYVel() & 0xFFFF, d.postCpuGroundVel() & 0xFFFF,
                d.postCpuX() & 0xFFFF, d.postCpuXSubpixel() & 0xFFFF, d.postCpuAngle() & 0xFF,
                d.appliedFollowNudge(), d.postPhysicsRecorded() ? "seen" : "missing",
                d.postPhysicsObjectControl() & 0xFF, d.postPhysicsStatus() & 0xFF,
                d.postPhysicsXVel() & 0xFFFF, d.postPhysicsYVel() & 0xFFFF,
                d.postPhysicsGroundVel() & 0xFFFF, d.postPhysicsX() & 0xFFFF,
                d.postPhysicsXSubpixel() & 0xFFFF, d.postPhysicsAngle() & 0xFF,
                d.dx() & 0xFFFF, d.dy() & 0xFFFF, d.skipFollowSteering(), normalPushingGraceFrames);
    }
}

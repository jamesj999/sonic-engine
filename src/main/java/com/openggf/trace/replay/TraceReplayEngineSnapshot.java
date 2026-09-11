package com.openggf.trace.replay;

import com.openggf.game.GameServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.trace.EngineSnapshot;

import java.util.Map;

/** Read-only frame-zero engine projection shared by headless and visual replay. */
public final class TraceReplayEngineSnapshot {
    private TraceReplayEngineSnapshot() {
    }

    /**
     * Frame-0 bootstrap coverage is deliberately partial, and the gap must stay
     * visible rather than read as a parity proof. Sidekick CPU state is now
     * captured (see {@code captureFirstSidekickCpu}), so a recorded
     * {@code cpu_state_snapshot} is genuinely compared. Per-slot SST snapshots
     * are still left empty -- the {@code Map.of()} argument below -- because
     * the engine exposes no object-slot view here, so a recorded
     * {@code object_state_snapshot} yields a warning-only bootstrap gap rather
     * than a strict failure. Tracked as REL-035 in
     * docs/architecture/audits/release-architecture-review-issues.md.
     */
    public static EngineSnapshot capture(AbstractPlayableSprite sprite) {
        return EngineSnapshot.capture(
                sprite != null ? sprite.copyXHistory() : null,
                sprite != null ? sprite.copyYHistory() : null,
                sprite != null ? sprite.copyInputHistory() : null,
                sprite != null ? sprite.copyStatusHistory() : null,
                sprite != null ? sprite.historyPos() : 0,
                captureFirstSidekickCpu(),
                Map.of(),
                resolveLevelStartX());
    }

    /**
     * The loaded zone/act's ROM {@code StartLocations} X. ROM
     * {@code LevelSizeLoad} places the leader either from that table or, when
     * {@code Last_star_pole_hit} is non-zero, from {@code Obj79_LoadData}'s
     * {@code Saved_x_pos} checkpoint restore
     * (docs/s2disasm/s2.asm:14773-14778, :44774-44778). The horizontal
     * coordinate is what identifies which branch ran -- the checkpoint restore
     * reinstates a star post's own X, which is never the level's spawn X -- so
     * the bootstrap comparator needs this number to reason about it. Same
     * discriminator {@code TraceReplaySessionBootstrap} already uses to pick
     * the sidekick placement anchor.
     */
    private static int resolveLevelStartX() {
        var level = GameServices.levelOrNull();
        var module = GameServices.currentOrBootstrapGameModule();
        if (level == null || module == null || module.getZoneRegistry() == null) {
            return EngineSnapshot.LEVEL_START_X_UNKNOWN;
        }
        int[] start = module.getZoneRegistry()
                .getStartPosition(level.getCurrentZone(), level.getCurrentAct());
        return start == null || start.length < 1
                ? EngineSnapshot.LEVEL_START_X_UNKNOWN
                : start[0];
    }

    private static EngineSnapshot.SidekickCpuView captureFirstSidekickCpu() {
        var sprites = GameServices.spritesOrNull();
        if (sprites == null || sprites.getSidekicks().isEmpty()) {
            return null;
        }
        SidekickCpuController controller =
                sprites.getSidekicks().getFirst().getCpuController();
        if (controller == null) {
            return null;
        }
        return new EngineSnapshot.SidekickCpuView(
                controller.getDiagnosticControlCounter(),
                controller.getDiagnosticRespawnCounter(),
                controller.getDiagnosticRomCpuRoutine(),
                (short) controller.targetX(),
                (short) controller.targetY(),
                controller.getDiagnosticInteractId(),
                controller.getDiagnosticJumpingFlag() != 0);
    }
}

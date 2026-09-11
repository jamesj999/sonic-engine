package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * HCZ end boss blade water splash (ROM: loc_6B4A2).
 *
 * <p>Spawned by the blade ({@code ChildObjDat_6BDD8}) when the blade's Y
 * position crosses the water level during FALL (transition from routine 8
 * to routine A). This is a simple animated visual splash at the water
 * surface — it has no collision or player interaction.
 *
 * <h3>ROM setup (loc_6B4A2):</h3>
 * <ul>
 *   <li>{@code SetUp_ObjAttributes2} with {@code word_6BD52}:
 *       art_tile = ArtTile_HCZEndBoss, priority = $80,
 *       width = $C, height = $8, mapping_frame = $18.</li>
 *   <li>Main handler = {@code AnimateRaw_DrawTouch}</li>
 *   <li>Animation = {@code byte_6BE36}: Animate_RawMultiDelay script
 *       {@code $18,2 / $18,2 / $30,3 / $19,4 / $F4}</li>
 *   <li>Positioned at {@code (Water_level - 4)} via {@code loc_6B46E}</li>
 * </ul>
 *
 * <p>The splash sits at the blade's X position and at water_level - 4.
 * It plays through a short animation and deletes itself.
 */
public class HczEndBossBladeSplash extends AbstractBossChild implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(HczEndBossBladeSplash.class.getName());

    // =========================================================================
    // Animation: byte_6BE36 (Animate_RawMultiDelay format)
    // Stored as mapping_frame, delay pairs. $F4 = end.
    // =========================================================================
    private static final int[] ANIM_SCRIPT = { 0x18, 2, 0x18, 2, 0x30, 3, 0x19, 4, 0xF4 };

    // =========================================================================
    // Instance state
    // =========================================================================
    private final HczEndBossInstance boss;

    private int animFrameIndex;
    private int animFrameTimer;
    private int mappingFrame;
    private boolean animComplete;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * @param boss   Parent boss instance.
     * @param bladeX World X of the blade when it entered water.
     */
    public HczEndBossBladeSplash(HczEndBossInstance boss, int bladeX) {
        super(boss, "HCZEndBossBladeSplash", 3, 0);
        this.boss = boss;

        // Position at blade's X, water_level - 4 (ROM: loc_6B46E)
        this.currentX = bladeX;
        this.currentY = getWaterLevelY() - 4;

        // ROM: initial mapping_frame = $18 (from word_6BD52)
        this.mappingFrame = 0x18;
        this.animFrameIndex = 0;
        this.animFrameTimer = 0;
        this.animComplete = false;

        updateDynamicSpawn();
    }

    private HczEndBossBladeSplash(ObjectSpawn spawn, HczEndBossInstance boss) {
        this(boss, spawn.x());
    }

    @Override
    public HczEndBossBladeSplash recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        if (restoredBoss == null || ctx.spawn() == null) {
            return null;
        }
        return ObjectConstructionContext.construct(
                ctx.objectServices(),
                () -> new HczEndBossBladeSplash(restoredBoss, ctx.spawn().x()));
    }

    // =========================================================================
    // Update
    // =========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }

        if (boss.isDefeatSignal() || animComplete) {
            setDestroyed(true);
            return;
        }

        // ROM loc_6B46E: keep Y pinned to water level - 4
        currentY = getWaterLevelY() - 4;

        tickAnimation();
    }

    // =========================================================================
    // Animation (Animate_RawMultiDelay emulation)
    // =========================================================================

    private void tickAnimation() {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }

        int nextFrameIndex = animFrameIndex + 2;
        if (nextFrameIndex >= ANIM_SCRIPT.length) {
            animComplete = true;
            return;
        }

        int frameValue = ANIM_SCRIPT[nextFrameIndex];
        if (frameValue >= 0x80) {
            animComplete = true;
            return;
        }

        animFrameIndex = nextFrameIndex;
        mappingFrame = frameValue;
        animFrameTimer = ANIM_SCRIPT[nextFrameIndex + 1];
    }

    // =========================================================================
    // Water level access
    // =========================================================================

    private int getWaterLevelY() {
        try {
            WaterSystem ws = services().waterSystem();
            if (ws == null) {
                return 0x1000;
            }
            int zoneId = services().featureZoneId();
            int actId  = services().featureActId();
            if (ws.hasWater(zoneId, actId)) {
                return ws.getWaterLevelY(zoneId, actId);
            }
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossBladeSplash.getWaterLevelY: " + e.getMessage());
        }
        return 0x1000;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }
}

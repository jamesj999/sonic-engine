package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MGZ Head Trigger rock-spike projectile.
 *
 * <p>ROM: loc_34518 / loc_34530 (sonic3k.asm:70883-70892). A simple
 * constant-velocity projectile:
 * <ul>
 *   <li>{@code jsr MoveSprite2} — signed 8:8 x_vel / y_vel without gravity</li>
 *   <li>{@code jsr Add_SpriteToCollisionResponseList} — participate in touch collision</li>
 *   <li>{@code jmp Draw_Sprite}</li>
 * </ul>
 * When the render_flags on-screen bit is clear (offscreen) the ROM deletes via
 * {@code Delete_Current_Sprite}, which we model as {@link #setDestroyed(boolean)}
 * once a conservative off-screen margin is crossed.
 *
 * <p>Collision: {@code collision_flags = 0x9B} — HURT category (bit 7 set), size
 * index 0x1B. Non-attackable — the projectile can only damage the player.
 */
public class MGZHeadTriggerProjectileInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateZeroScalarArgsRewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_HEAD_TRIGGER;

    // ROM: move.w #$300,priority(a1) — higher sprite-list priority number means
    // drawn earlier (behind) than the head's $280. Also the ROM masks the head's
    // art_tile with drawing_mask (andi.w #drawing_mask,art_tile) so the
    // projectile inherits priority=0/palette=0 at the art_tile level — the
    // mapping piece word still provides its own priority bit, but between the
    // head face and the projectile at the same VRAM-priority layer, the S3K
    // sprite-list ordering puts the projectile behind. We pick the max bucket
    // so the spike emerges from *behind* the face and the pillar's decorative
    // tiles, matching how it looks in-game.
    private static final int PRIORITY_BUCKET = RenderPriority.MAX;

    // ROM: move.b #$9B,collision_flags(a1).
    private static final int COLLISION_FLAGS = 0x9B;

    // ROM: mapping_frame defaults to 0 (Frame_233822 = 8x32 projectile spike).
    private static final int PROJECTILE_FRAME = 0;

    // Off-screen margin before self-delete. The ROM relies on the on-screen bit
    // being cleared by the VDP; we approximate by checking a horizontal radius
    // around the camera that is more than comfortably larger than the sprite.
    private static final int OFFSCREEN_MARGIN = 0x40;

    /** ROM 16:8 fixed-point X position: integer world X in {@code x_pos}, sub-pixel remainder. */
    private int worldX;
    private int xSub;
    /** Y position is fixed — ROM {@code MoveSprite2} with y_vel = 0. */
    private int worldY;
    /**
     * ROM x_vel in 16:8 fixed-point subpixels (e.g. $400 = 4px/frame).
     * Non-final so the generic field capturer reapplies it after a rewind
     * recreate (the captured ObjectSpawn does not encode velocity).
     */
    private int xVel;
    /**
     * ROM child copies the parent's render_flags, including horizontal flip.
     * Non-final so it is reapplied after a rewind recreate.
     */
    private boolean hFlip;

    public MGZHeadTriggerProjectileInstance(int spawnX, int spawnY, int xVel, boolean hFlip) {
        super(new ObjectSpawn(spawnX, spawnY, 0xFF, 0, 0, false, 0),
                "MGZHeadTriggerProjectile");
        this.worldX = spawnX;
        this.xSub = 0;
        this.worldY = spawnY;
        this.xVel = xVel;
        this.hFlip = hFlip;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }

        // ROM MoveSprite2: x_pos += x_vel, y_pos += y_vel (y_vel=0 here).
        // 16:8 fixed-point accumulation.
        int combined = (worldX << 8) | (xSub & 0xFF);
        combined += xVel;
        worldX = combined >> 8;
        xSub = combined & 0xFF;

        // ROM loc_34518: if render_flags on-screen bit is clear, delete.
        if (!isOnScreen(OFFSCREEN_MARGIN)) {
            setDestroyed(true);
        }
    }

    // ===== TouchResponseProvider =====

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // loc_34518 runs MoveSprite2 before adding this SST pointer to
        // Collision_response_list. The following player pass dereferences that
        // pointer at the post-move coordinate, not the older pre-update sample
        // (sonic3k.asm:70883-70892).
        return true;
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        // ROM: `andi.w #drawing_mask,art_tile(a1)` strips the priority/palette/flip
        // bits from the projectile's art_tile. The mapping piece for frame 0
        // (Frame_233822 = $E04E) still has its own priority bit set, but after
        // ROM's art_tile add the piece ends up sharing the column body's low
        // sprite layer rather than the face's high layer. Forcing priority=false
        // here replicates that — without it the renderer picks up the piece's
        // priority bit and draws the spike on top of both the face and the
        // level's high-priority plane-A tiles.
        renderer.drawFrameIndexForcedPriority(
                PROJECTILE_FRAME, worldX, worldY, hFlip, false, -1, false);
    }

    @Override
    public int getX() { return worldX; }

    @Override
    public int getY() { return worldY; }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: after `andi.w #drawing_mask,art_tile`, the projectile's art_tile
        // carries no priority bit. The engine's low-priority flag keeps the
        // sprite rendering behind high-priority plane-A FG tiles so the spike
        // emerges from behind the pillar scenery rather than on top of it.
        return false;
    }
}

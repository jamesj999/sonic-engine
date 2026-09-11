package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * HTZ Boss Flamethrower projectile (Obj52 subtype 4).
 * ROM Reference: s2.asm:63858-63896 (Obj52_FlameThrower)
 *
 * The flamethrower spawns above the boss and drifts horizontally while animating.
 * It damages the player on contact.
 *
 * Animation uses ROM Ani_obj52 animation 5:
 *   byte_302B0: dc.b 3, $C, $D, $FF  ; Delay 3, frames 12, 13, then loop
 * $FF in AnimateSprite means restart from beginning (infinite loop).
 * The flamethrower is destroyed via MarkObjGone when it drifts off-screen.
 */
public class HTZBossFlamethrower extends AbstractBossChild
        implements TouchResponseProvider, RewindRecreatable {

    // Movement constants (ROM: s2.asm:63869-63892)
    /** Horizontal offset from boss (ROM: move.w #-$70,d0) */
    private static final int X_OFFSET = -0x70;
    /** Horizontal velocity (ROM: move.w #-4,d1) */
    private static final int X_VELOCITY = -4;

    // Animation constants (ROM: byte_302B0 - animation 5)
    /** Animation delay (ROM: dc.b 3 = delay of 3+1=4 frames) */
    private static final int ANIM_DELAY = 4;
    /**
     * Obj52 child objects use ArtTile_ArtNem_HTZBoss as their art base, while the
     * parent renderer sheet is aligned to ArtTile_ArtNem_Eggpod_2. The VRAM delta is
     * $60 tiles, so child tile indices must be shifted by +$60 on this combined sheet.
     */
    private static final int CHILD_TILE_BASE_OFFSET = 0x60;
    private static final int TILE_FIRE_1 = CHILD_TILE_BASE_OFFSET + 0x61;
    private static final int TILE_FIRE_2 = CHILD_TILE_BASE_OFFSET + 0x62;

    // Unlike SYZBossSpike, this child DOES live-track its own position: update() calls
    // updateDynamicSpawn() every frame, so its captured spawn is always its current
    // drifted position, not a frozen construction-time snapshot. It spawns at the boss
    // plus X_OFFSET (0x70 = 112px) and flies at X_VELOCITY (4px/frame) until
    // !isOnScreen() self-destroys it (a full, un-margined 320px-wide camera window), so
    // the farthest it can ever legitimately be from the boss position at any live moment
    // is bounded by that spawn offset plus roughly a screen width of travel. Round up
    // generously (0x1C0 = 448px) since the boss itself is not perfectly camera-centered.
    private static final int MAX_PARENT_RELINK_DISTANCE = 0x1C0;

    // State
    private int xVel;
    private boolean flipped;
    private int animFrame;
    private int animTimer;

    HTZBossFlamethrower(ObjectSpawn spawn) {
        this(new Sonic2HTZBossInstance(spawn), spawn.x(), spawn.y(), false);
    }

    public HTZBossFlamethrower(Sonic2HTZBossInstance parent, int spawnX, int spawnY, boolean flipped) {
        super(parent, "HTZ Flamethrower", 4, Sonic2ObjectIds.HTZ_BOSS);
        this.flipped = flipped;

        // Calculate initial position with offset
        // ROM: add.w d0,x_pos(a0)
        int xOffset = flipped ? -X_OFFSET : X_OFFSET;
        this.currentX = spawnX + xOffset;
        this.currentY = spawnY;

        // Set velocity (flip direction if boss is flipped)
        // ROM: neg.w d0 / neg.w d1 if flipped
        this.xVel = flipped ? -X_VELOCITY : X_VELOCITY;

        // Animation state (ROM: animation 5 loops frames $C, $D via $FF restart)
        this.animFrame = 0;  // 0 = frame $C, 1 = frame $D
        this.animTimer = ANIM_DELAY;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        // The flamethrower is a child of the HTZ boss. If the boss was defeated/swept
        // before capture there is no parent to bind to, so drop the child rather than
        // throw. acceptDestroyed relinks to a restored-but-destroyed boss so the
        // child preserves its captured relationship. Bounded (see
        // MAX_PARENT_RELINK_DISTANCE) since this child's live-tracked captured position
        // is directly comparable to the boss's.
        return RewindRecreateObjectLinks.nearestObject(
                        ctx, Sonic2HTZBossInstance.class, true, MAX_PARENT_RELINK_DISTANCE)
                .<AbstractObjectInstance>map(boss ->
                        new HTZBossFlamethrower(boss, ctx.spawn().x(), ctx.spawn().y(), false))
                .orElse(null);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!shouldUpdate(vIntRunCount)) {
            return;
        }

        // Move horizontally
        // ROM: move.w x_vel(a0),d1 / add.w d1,x_pos(a0)
        currentX += xVel;

        // ROM: jmpto JmpTo37_MarkObjGone - destroy when off-screen
        if (!isOnScreen()) {
            setDestroyed(true);
            return;
        }

        // Update animation (ROM: animation 5 - byte_302B0: dc.b 3, $C, $D, $FF)
        // $FF = restart from beginning (infinite loop). Delay of 3 = change every 4 frames.
        animTimer--;
        if (animTimer <= 0) {
            animTimer = ANIM_DELAY;
            animFrame++;
            if (animFrame > 1) {
                // $FF restart: loop back to first frame
                animFrame = 0;
            }
        }

        updateDynamicSpawn();
    }

    /**
     * Returns collision flags for touch response.
     * ROM: move.b #$98,collision_flags(a1) - enemy projectile collision
     */
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        return 0x98;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        ObjectRenderManager renderManager =
                services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer =
                renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // ROM frame mappings are tile $61/$62 relative to ArtTile_ArtNem_HTZBoss.
        // Draw as direct patterns to account for child art base offset.
        int tile = (animFrame == 0) ? TILE_FIRE_1 : TILE_FIRE_2;
        renderer.drawPatternIndex(tile, currentX - 4, currentY - 4, 0);
    }
}

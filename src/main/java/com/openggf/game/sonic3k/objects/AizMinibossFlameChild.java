package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * AIZ miniboss flame child.
 *
 * ROM: Obj_AIZMiniboss_Flame (sub_6890E / loc_68956 / loc_68962)
 * - collision_flags=$8B while active flame
 * - shield_reaction bit 4
 * - wait delay based on subtype, then flame anim, then explosion anim
 */
public class AizMinibossFlameChild extends AbstractObjectInstance implements TouchResponseProvider, RewindRecreatable {
    private static final int COLLISION_FLAGS = 0x8B;
    private static final int SHIELD_REACTION = 1 << 4;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            SHIELD_REACTION,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // ROM byte_69164 (Animate_RawMultiDelay, first pair skipped on initial play):
    // Explosion sequence: frame, duration (ROM timer+1 = visible ticks)
    private static final int[] EXPLODE_FRAMES = {2, 3, 4, 5};
    private static final int[] EXPLODE_DURATIONS = {2, 3, 5, 2};

    private enum Phase {
        WAIT,
        FLAME,
        EXPLODE
    }
    private final AbstractBossInstance parent;
    // xOffset/yOffset/subtype are non-final so the rewind field capturer reapplies
    // them after the recreate hook rebuilds the flame with placeholders 0; parent is relinked.
    private int xOffset;
    private int yOffset;
    private int subtype;

    private Phase phase = Phase.WAIT;
    private int waitTimer;
    private int frame;
    private int animTimer;
    private int explodeIndex;

    private int worldX;
    private int worldY;

    public AizMinibossFlameChild(AbstractBossInstance parent, int xOffset, int yOffset, int subtype) {
        super(new ObjectSpawn(
                parent != null ? parent.getX() + xOffset : xOffset,
                parent != null ? parent.getY() + yOffset : yOffset,
                0x90,
                subtype,
                0,
                false,
                0), "AIZMinibossFlame");
        this.parent = parent;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.subtype = subtype & 0xFF;
        this.worldX = spawn.x();
        this.worldY = spawn.y();

        // ROM loc_68928: timer = (6 - subtype) * 2
        // CreateChild1_Normal gives subtypes 0, 2, 4, 6 → waits 12, 8, 4, 0
        int raw = (6 - this.subtype) * 2;
        this.waitTimer = Math.max(0, raw);
        this.frame = 0;
        this.animTimer = 0;
        this.explodeIndex = 0;
    }

    private AizMinibossFlameChild(
            ObjectSpawn spawn,
            AizMinibossInstance parent,
            int xOffset,
            int yOffset,
            int subtype) {
        this(parent, xOffset, yOffset, subtype);
    }

    @Override
    public AizMinibossFlameChild recreateForRewind(RewindRecreateContext ctx) {
        AizMinibossInstance boss = AizMinibossRewindLinks.nearestBoss(ctx);
        return boss == null ? null : new AizMinibossFlameChild(boss, 0, 0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (parent != null && parent.getState().defeated) {
            setDestroyed(true);
            return;
        }
        syncWithParent();

        switch (phase) {
            case WAIT -> {
                waitTimer--;
                if (waitTimer < 0) {
                    phase = Phase.FLAME;
                    frame = 0;
                    // ROM byte_6915F (Animate_RawMultiDelay, first pair skipped):
                    // data[2]=frame 0, data[3]=delay 1 → 2 ticks, then $F4 → explosion
                    animTimer = 2;
                }
            }
            case FLAME -> {
                // ROM: frame 0 for 2 ticks only, then transition to explosion
                animTimer--;
                if (animTimer <= 0) {
                    phase = Phase.EXPLODE;
                    explodeIndex = 0;
                    frame = EXPLODE_FRAMES[0];
                    animTimer = EXPLODE_DURATIONS[0];
                }
            }
            case EXPLODE -> {
                animTimer--;
                if (animTimer > 0) {
                    return;
                }
                explodeIndex++;
                if (explodeIndex >= EXPLODE_FRAMES.length) {
                    setDestroyed(true);
                    return;
                }
                frame = EXPLODE_FRAMES[explodeIndex];
                animTimer = EXPLODE_DURATIONS[explodeIndex];
            }
        }
    }

    private void syncWithParent() {
        if (parent == null || parent.isDestroyed()) {
            return;
        }

        int signedXOffset = xOffset;
        if ((parent.getState().renderFlags & 1) != 0) {
            signedXOffset = -signedXOffset;
        }
        worldX = parent.getX() + signedXOffset;
        worldY = parent.getY() + yOffset;
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_AIZBossFire,0,1) — priority bit = 1
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (phase == Phase.WAIT) {
            return;
        }
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.AIZ_MINIBOSS_FLAME);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(frame, worldX, worldY, false, false);
    }

    @Override
    public int getX() {
        return worldX;
    }

    @Override
    public int getY() {
        return worldY;
    }

    @Override
    public int getCollisionFlags() {
        if (phase != Phase.FLAME || isDestroyed()) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getShieldReactionFlags() {
        return SHIELD_REACTION;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

}

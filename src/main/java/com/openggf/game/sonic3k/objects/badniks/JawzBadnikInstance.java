package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.objects.HczHarmfulExplosionObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.Tails;

/**
 * S3K Obj $93 - Jawz (HCZ Act 2).
 *
 * <p>ROM reference: {@code Obj_Jawz} (sonic3k.asm:183518-183570).
 * The object is intentionally small: it waits until it is on-screen, then
 * sets its initial horizontal velocity toward the player, animates with a
 * two-frame raw loop, and otherwise uses the shared badnik destruction path.
 */
public final class JawzBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable, TouchResponseListener {

    // ObjDat_Jawz: collision_flags = $D7 -> size index $17, standard badnik body.
    private static final int COLLISION_SIZE_INDEX = 0x17;
    private static final int COLLISION_FLAGS = 0xD7;

    // ObjDat_Jawz: priority $280
    private static final int PRIORITY_BUCKET = 5;

    // Set_VelocityXTrackSonic (d4 = -$200)
    private static final int TRACK_SPEED = 0x200;

    // byte_87924: Animate_RawNoSST script {0, 0, 1, $FC}
    // The raw animation is effectively a fast two-frame loop.
    private static final int FRAME_A = 0;
    private static final int FRAME_B = 1;
    private static final int ANIM_RESET_DELAY = 0;

    // Obj_WaitOffscreen seeds the placeholder with width_pixels = height_pixels
    // = $20 (sonic3k.asm:180271-180276) and that is what Render_Sprites tests
    // when it sets render_flags bit 7, which loc_85AD2 reads on its next
    // dispatch (:180279-180280, :180303-180305). Both margins are therefore the
    // ROM's $20. An earlier $22 y-margin released the wait one frame early
    // wherever the camera was scrolling vertically -- the two pixels were the
    // camera's own per-frame advance, not a ROM quantity.
    private static final int WAIT_PLACEHOLDER_X_MARGIN = 0x20;
    private static final int WAIT_PLACEHOLDER_Y_MARGIN = 0x20;

    private boolean initialized;
    private boolean waitingForOnscreen = true;
    private boolean placeholderRenderedOnscreen;
    private int animTimer = ANIM_RESET_DELAY;
    private int collisionProperty;

    public JawzBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Jawz",
                Sonic3kObjectArtKeys.HCZ_JAWZ, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = FRAME_A;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // Obj_WaitOffscreen installs a $20-by-$20 placeholder and returns on
        // the dispatch that first sees it rendered. The saved Obj_Jawz entry
        // point resumes on the following dispatch.
        if (waitingForOnscreen) {
            if (!placeholderRenderedOnscreen) {
                return;
            }
            waitingForOnscreen = false;
            placeholderRenderedOnscreen = false;
            return;
        }

        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite : null;

        if (!initialized) {
            initializeVelocity(player);
            initialized = true;
            return;
        }

        moveWithVelocity();
        advanceAnimation();
        processPendingTouch();
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (waitingForOnscreen) {
            // Obj_WaitOffscreen tests render_flags bit 7 on the NEXT object
            // dispatch; Render_Sprites sets that bit after object execution.
            // Retain the post-camera placeholder visibility rather than
            // recomputing it early in the following update
            // (sonic3k.asm:180266-180298, 36318-36365).
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(
                    WAIT_PLACEHOLDER_X_MARGIN, WAIT_PLACEHOLDER_Y_MARGIN);
        }
    }

    private void processPendingTouch() {
        int property = collisionProperty & 0xFF;
        if (property == 0) {
            return;
        }
        collisionProperty = 0;

        PlayableEntity target = property == 1
                ? services().playerQuery().mainPlayerOrNull()
                : services().playerQuery().nativeP2OrNull();
        if (!(target instanceof AbstractPlayableSprite player)) {
            return;
        }
        if (!isAttacking(player)) {
            // loc_878E8 creates HCZEndBoss_ExplosionChild through
            // CreateChild1_Normal, then deletes Jawz. The child retains $8B
            // hurt collision through mapping frame 2.
            spawnChild(() -> new HczHarmfulExplosionObjectInstance(currentX, currentY));
            ObjectLifetimeOps.destroyLatched(this);
            return;
        }

        int enemyY = currentY;
        defeat(player);
        applyEnemyDefeatedBounce(player, enemyY);
    }

    private boolean isAttacking(AbstractPlayableSprite player) {
        int animation = player.getAnimationId();
        if (player.getInvincibleFrames() > 0 || animation == 9 || animation == 2) {
            return true;
        }
        if (player instanceof Knuckles) {
            int ability = player.getDoubleJumpFlag();
            return ability == 1 || ability == 3;
        }
        if (player instanceof Tails tails && player.getDoubleJumpFlag() != 0 && !tails.isInWater()) {
            int dx = (short) (player.getCentreX() - currentX);
            int dy = (short) (player.getCentreY() - currentY);
            int angle = (int) Math.round(Math.atan2(dy, dx) * 128.0 / Math.PI) & 0xFF;
            return ((angle - 0x20) & 0xFF) < 0x40;
        }
        return false;
    }

    private void applyEnemyDefeatedBounce(AbstractPlayableSprite player, int enemyY) {
        int ySpeed = player.getYSpeed();
        if (ySpeed < 0) {
            player.setYSpeed((short) (ySpeed + 0x100));
        } else if (player.getCentreY() >= enemyY) {
            player.setYSpeed((short) (ySpeed - 0x100));
        } else {
            player.setYSpeed((short) -ySpeed);
        }
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.fromProvider(this);
    }

    @Override
    public boolean usesS3kTouchSpecialPropertyResponse() {
        return true;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        return true;
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (result.sizeIndex() != COLLISION_SIZE_INDEX || player == null) {
            return;
        }
        PlayableEntity main = services().playerQuery().mainPlayerOrNull();
        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        if (player == main) {
            collisionProperty = (collisionProperty + 1) & 0xFF;
        } else if (player == nativeP2) {
            collisionProperty = (collisionProperty + 2) & 0xFF;
        }
    }

    /**
     * ROM: Set_VelocityXTrackSonic.
     * The enemy starts moving toward the player as soon as it becomes active.
     */
    private void initializeVelocity(AbstractPlayableSprite player) {
        if (player == null || player.getDead()) {
            xVelocity = facingLeft ? -TRACK_SPEED : TRACK_SPEED;
            return;
        }

        if (player.getCentreX() <= currentX) {
            facingLeft = true;
            xVelocity = -TRACK_SPEED;
        } else {
            facingLeft = false;
            xVelocity = TRACK_SPEED;
        }
    }

    /**
     * Animate_RawNoSST parity for byte_87924.
     * The ROM's raw animation is a tight two-frame swim loop.
     */
    private void advanceAnimation() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        animTimer = ANIM_RESET_DELAY;
        mappingFrame = (mappingFrame == FRAME_A) ? FRAME_B : FRAME_A;
    }
}

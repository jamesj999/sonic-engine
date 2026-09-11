package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K S3KL object $1E - Launch Base Zone Act 2 spin launcher.
 *
 * <p>ROM reference: {@code Obj_LBZSpinLauncher} (sonic3k.asm:56463-56622).
 */
public final class LbzSpinLauncherObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, SpawnRewindRecreatable {
    private static final int ON_SCREEN_HALF_SIZE = 0x20;
    private static final int SOLID_HALF_WIDTH = 0x2B;
    private static final int SOLID_HALF_HEIGHT = 0x10;
    private static final int SIDE_WINDOW_OFFSET = 0x18;
    private static final int SIDE_WINDOW_WIDTH = 0x30;
    private static final int STANDING_RELEASE_WINDOW = 0x20;
    private static final int EXIT_X_OFFSET = 0x10;
    private static final int LAUNCH_Y_SPEED = -0x0A00;
    private static final int LAUNCH_GROUND_SPEED = 0x0800;
    private static final int SIDE_LAUNCH_COOLDOWN = 0x10;
    private static final int STANDING_RELEASE_COOLDOWN = 0x20;
    private static final int PRIORITY_BUCKET = 1; // ROM priority $80.
    private static final int MAPPING_FRAME = 0;
    private static final int PALETTE_LINE = 2;
    private static final byte[] SLOPE_DATA = {
            0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x12,
            0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A,
            0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x21,
            0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21,
            0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21,
            0x21, 0x21, 0x21, 0x21
    };

    private int p1Cooldown;
    private int p2Cooldown;
    private boolean p1SolidSuppressedThisFrame;
    private boolean p2SolidSuppressedThisFrame;

    public LbzSpinLauncherObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LBZSpinLauncher");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Native loc_28DE4 tests the byte before decrementing it and skips
        // sub_1DD24 for that whole object pass, including the 1 -> 0 tick.
        p1SolidSuppressedThisFrame = p1Cooldown > 0;
        p2SolidSuppressedThisFrame = p2Cooldown > 0;
        if (p1Cooldown > 0) {
            p1Cooldown--;
        }

        if (p2Cooldown > 0) {
            p2Cooldown--;
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite sprite)) {
            return true;
        }
        return sprite.isCpuControlled()
                ? !p2SolidSuppressedThisFrame
                : !p1SolidSuppressedThisFrame;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public byte[] getSlopeData() {
        return SLOPE_DATA;
    }

    @Override
    public boolean isSlopeFlipped() {
        return facingLeft();
    }

    @Override
    public int getSlopeBaseline() {
        // sub_1DD24 fresh contact path (loc_1DECE) subtracts byte_28FF4[0].
        return SLOPE_DATA[0];
    }

    @Override
    public boolean usesSlopeForNewLanding() {
        return true;
    }

    @Override
    public boolean addsSlopeCatchRangeToVerticalOverlap() {
        // Obj_LBZSpinLauncher passes d2=$10 to sub_1DD24. The new-contact
        // path keeps that value, adds the player's y_radius, and then adds
        // the combined range to the vertical overlap before classifying the
        // contact (sonic3k.asm:56500-56509, 41337-41343).
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        if (contact.standing()) {
            setCooldown(player, tryStandingRelease(player, cooldownFor(player)));
        } else if (contact.touchSide() || contact.touchBottom() || contact.touchTop()) {
            // Obj_LBZSpinLauncher calls sub_28E76 when sub_1DD24 returns d4=-2
            // from its top/bottom branch, not only on left/right side contacts.
            setCooldown(player, trySideLaunch(player, cooldownFor(player)));
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_SPIN_LAUNCHER);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, spawn.x(), spawn.y(), facingLeft(), false, PALETTE_LINE);
        }
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    String artKeyForTesting() {
        return Sonic3kObjectArtKeys.LBZ_SPIN_LAUNCHER;
    }

    int cooldownForTesting(boolean nativeP1) {
        return nativeP1 ? p1Cooldown : p2Cooldown;
    }

    private int trySideLaunch(AbstractPlayableSprite player, int cooldown) {
        if (cooldown > 0 || !insideSideOpening(player)) {
            return cooldown;
        }

        int targetX = spawn.x() + (facingLeft() ? -EXIT_X_OFFSET : EXIT_X_OFFSET);
        // setRolling changes the engine's visual bounds and therefore its
        // derived centre. Apply it before the native x_pos/y_pos writes so
        // the launcher leaves y_pos exactly at the object's centre, as
        // sub_28E76 does when it writes the radius bytes afterward.
        player.setRolling(true);
        NativePositionOps.writeXPosPreserveSubpixel(player, targetX);
        NativePositionOps.writeYPosPreserveSubpixel(player, spawn.y());
        player.setXSpeed((short) 0);
        player.setYSpeed((short) LAUNCH_Y_SPEED);
        player.setGSpeed((short) LAUNCH_GROUND_SPEED);
        player.setAir(true);
        player.setJumping(false);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        return SIDE_LAUNCH_COOLDOWN;
    }

    private int tryStandingRelease(AbstractPlayableSprite player, int cooldown) {
        if (cooldown > 0 || !insideStandingReleaseWindow(player)) {
            return cooldown;
        }

        int targetX = spawn.x() + (facingLeft() ? -EXIT_X_OFFSET : EXIT_X_OFFSET);
        NativePositionOps.writeXPosPreserveSubpixel(player, targetX);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setAir(true);
        player.setJumping(false);
        player.setOnObject(false);
        player.clearRollingFlagPreserveRadii();
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        return STANDING_RELEASE_COOLDOWN;
    }

    private boolean insideSideOpening(AbstractPlayableSprite player) {
        int playerX = player.getCentreX() & 0xFFFF;
        int objectX = spawn.x() & 0xFFFF;
        int delta = facingLeft() ? objectX - playerX : playerX - objectX;
        return unsigned16(delta + SIDE_WINDOW_OFFSET) < SIDE_WINDOW_WIDTH;
    }

    private boolean insideStandingReleaseWindow(AbstractPlayableSprite player) {
        int playerX = player.getCentreX() & 0xFFFF;
        int objectX = spawn.x() & 0xFFFF;
        int delta = facingLeft() ? objectX - playerX : playerX - objectX;
        return unsigned16(delta) < STANDING_RELEASE_WINDOW;
    }

    private int cooldownFor(AbstractPlayableSprite player) {
        return player.isCpuControlled() ? p2Cooldown : p1Cooldown;
    }

    private void setCooldown(AbstractPlayableSprite player, int value) {
        if (player.isCpuControlled()) {
            p2Cooldown = value;
        } else {
            p1Cooldown = value;
        }
    }

    private boolean facingLeft() {
        return (spawn.renderFlags() & 0x01) != 0;
    }

    private static int unsigned16(int value) {
        return value & 0xFFFF;
    }
}

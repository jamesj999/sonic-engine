package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.events.Sonic3kICZEvents;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * IceCap Act 1 big snow pile used after Sonic crashes into the wall.
 *
 * <p>ROM reference: {@code Obj_ICZ1BigSnowPile} at
 * {@code docs/skdisasm/sonic3k.asm:110433}. The visual snow fall is background
 * event driven; this object supplies the sloped top collision and the jump-out
 * release once the pile reaches its final Y position.
 */
public final class IczBigSnowPileInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, RewindRecreatable {
    public static final int X_POSITION = 0x3880;
    public static final int BASE_Y = 0x05E0;

    private static final int FINAL_Y = 0x070E;
    private static final int HALF_WIDTH = 0x0094;
    private static final int JUMP_Y_SPEED = -0x0600;
    private static final int SNOW_BG_SOURCE_X_DELTA = 0x1D40;
    private static final int SNOW_BG_SOURCE_Y_DELTA = 0x0460;
    private static final int RENDER_HALF_WIDTH = 0x00B0;
    private static final int RENDER_TOP_OFFSET = 0x0090;
    private static final int RENDER_BOTTOM_OFFSET = 0x0040;
    private static final byte[] SLOPE = {
            0x56, 0x56, 0x55, 0x55, 0x54, 0x54, 0x53, 0x53,
            0x52, 0x52, 0x51, 0x51, 0x51, 0x51, 0x50, 0x50,
            0x50, 0x50, 0x4F, 0x4F, 0x4E, 0x4E, 0x4D, 0x4C,
            0x4C, 0x4B, 0x4A, 0x49, 0x49, 0x48, 0x47, 0x47,
            0x46, 0x46, 0x45, 0x45, 0x44, 0x44, 0x43, 0x43,
            0x42, 0x42, 0x41, 0x41, 0x41, 0x41, 0x40, 0x40,
            0x40, 0x40, 0x3F, 0x3F, 0x3E, 0x3E, 0x3D, 0x3C,
            0x3C, 0x3B, 0x3A, 0x39, 0x39, 0x38, 0x37, 0x37,
            0x36, 0x36, 0x35, 0x35, 0x34, 0x34, 0x33, 0x33,
            0x32, 0x32, 0x31, 0x31, 0x31, 0x31, 0x30, 0x30,
            0x30, 0x30, 0x2F, 0x2F, 0x2E, 0x2E, 0x2D, 0x2C,
            0x2C, 0x2B, 0x2A, 0x29, 0x29, 0x28, 0x27, 0x27,
            0x26, 0x26, 0x25, 0x25, 0x24, 0x24, 0x23, 0x23,
            0x22, 0x22, 0x21, 0x21, 0x21, 0x21, 0x20, 0x20,
            0x20, 0x20, 0x1F, 0x1F, 0x1E, 0x1E, 0x1D, 0x1C,
            0x1C, 0x1B, 0x1A, 0x19, 0x19, 0x18, 0x17, 0x17,
            0x16, 0x16, 0x15, 0x15, 0x14, 0x14, 0x13, 0x13,
            0x12, 0x12, 0x11, 0x11, 0x11, 0x11, 0x10, 0x10,
            0x10, 0x10, 0x0F, 0x0F, 0x0E, 0x0E, 0x0D, 0x0C,
            0x0C, 0x0B, 0x0A, 0x09, 0x09, 0x08, 0x07, 0x07,
            0x06, 0x06, 0x05, 0x05, 0x04, 0x04, 0x03, 0x03,
            0x02, 0x02
    };

    private final Sonic3kICZEvents events;
    private final PatternDesc renderDesc = new PatternDesc();
    private int currentY = BASE_Y;
    private int lastRenderedTileCount;

    public IczBigSnowPileInstance(ObjectSpawn spawn, Sonic3kICZEvents events) {
        super(spawn, "ICZ1BigSnowPile");
        this.events = events;
        updateDynamicSpawn(X_POSITION, currentY);
    }

    private IczBigSnowPileInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (!(ctx.objectServices().levelEventProvider() instanceof Sonic3kLevelEventManager manager)) {
            return null;
        }
        Sonic3kICZEvents liveEvents = manager.getIczEvents();
        return liveEvents != null ? new IczBigSnowPileInstance(ctx.spawn(), liveEvents) : null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (events.getIcz1BackgroundRoutine() >= 8) {
            setDestroyed(true);
            return;
        }

        currentY = BASE_Y - events.getIcz1BigSnowOffset();
        updateDynamicSpawn(X_POSITION, currentY);
        AbstractPlayableSprite player = services().camera().getFocusedSprite();
        if (player != null && currentY == FINAL_Y) {
            handleJumpEscape(player);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        lastRenderedTileCount = 0;
    }

    public void renderBackgroundLayer(SpecialRenderEffectContext context) {
        if (isDestroyed()) {
            lastRenderedTileCount = 0;
            return;
        }
        renderBackgroundSnowSource(context.levelManager(), context.graphicsManager());
    }

    public int getLastRenderedTileCountForTests() {
        return lastRenderedTileCount;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, 0, 0);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public byte[] getSlopeData() {
        return SLOPE;
    }

    @Override
    public boolean isSlopeFlipped() {
        return false;
    }

    @Override
    public int getSlopeBaseline() {
        return 0;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        return true;
    }

    @Override
    public boolean providesPreMovementGroundAttachmentSupport() {
        return true;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (currentY != FINAL_Y || !contact.standing() || !(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        handleJumpEscape(player);
    }

    private void renderBackgroundSnowSource(LevelManager levelManager, GraphicsManager graphicsManager) {
        if (levelManager == null || graphicsManager == null) {
            lastRenderedTileCount = 0;
            return;
        }

        int drawn = 0;
        int startX = alignDown(X_POSITION - RENDER_HALF_WIDTH);
        int endX = alignDown(X_POSITION + RENDER_HALF_WIDTH);
        int startY = alignDown(currentY - RENDER_TOP_OFFSET);
        int endY = alignDown(currentY + RENDER_BOTTOM_OFFSET);
        int offset = events.getIcz1BigSnowOffset();

        for (int y = startY; y <= endY; y += Pattern.PATTERN_HEIGHT) {
            int sourceY = y - SNOW_BG_SOURCE_Y_DELTA + offset;
            for (int x = startX; x <= endX; x += Pattern.PATTERN_WIDTH) {
                int sourceX = x - SNOW_BG_SOURCE_X_DELTA;
                int descriptor = levelManager.getBackgroundTileDescriptorAtWorld(sourceX, sourceY);
                if ((descriptor & 0x07FF) == 0) {
                    continue;
                }
                renderDesc.set(descriptor);
                graphicsManager.renderPatternWithId(renderDesc.getPatternIndex(), renderDesc, x, y);
                drawn++;
            }
        }
        lastRenderedTileCount = drawn;
    }

    private static int alignDown(int value) {
        return Math.floorDiv(value, Pattern.PATTERN_WIDTH) * Pattern.PATTERN_WIDTH;
    }

    /**
     * ROM {@code loc_53A4C} (sonic3k.asm:110464-110480): the pile only tests
     * {@code Ctrl_1_locked} — it never sets it. The lock is established by
     * {@code ICZ1SE_Init} after the snowboard wall crash, so a route that
     * reaches a settled pile without that quake (a checkpoint restart) simply
     * never arms the scripted escape.
     */
    private void handleJumpEscape(AbstractPlayableSprite player) {
        if (!player.isControlLocked() || player.getAir() || !player.isJumpJustPressed()) {
            return;
        }
        player.setControlLocked(false);
        int releaseY = player.getCentreY();
        player.setYSpeed((short) JUMP_Y_SPEED);
        player.setAir(true);
        player.setJumping(true);
        player.applyRollingRadii(false);
        player.setRolling(true);
        // ROM writes y_radius/x_radius without changing y_pos.
        NativePositionOps.writeYPosPreserveSubpixel(player, releaseY);
        player.setRollingJump(false);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setOnObject(false);
        services().objectManager().clearRidingObject(player);
        services().audioManager().playSfx(Sonic3kSfx.JUMP.id);
    }
}

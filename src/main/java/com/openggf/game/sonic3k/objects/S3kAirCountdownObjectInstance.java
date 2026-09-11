package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.NullableSpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.DrowningController;

import java.util.List;

/**
 * Visible dynamic child created by S3K's fixed {@code Obj_AirCountdown}
 * controller.
 *
 * <p>ROM refs: visible child init/update and water-surface delete paths at
 * docs/skdisasm/sonic3k.asm:33306-33370. Fixed controllers live outside
 * dynamic SST, but {@code AirCountdown_MakeItem} allocates these children via
 * the normal dynamic {@code AllocateObject} scan (sonic3k.asm:33591-33610).
 */
public final class S3kAirCountdownObjectInstance extends AbstractObjectInstance
        implements NullableSpawnCoordinateZeroScalarArgsRewindRecreatable {
    // AirCountdown_Index (sonic3k.asm:33306-33315).
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_RISE = 0x02;
    private static final int ROUTINE_DISPLAY = 0x06;
    private static final int ROUTINE_DELETE = 0x08;
    private static final int ROUTINE_AIR_LEFT = 0x0C;
    private static final int ROUTINE_DISPLAY_NUMBER = 0x0E;
    private static final int ROUTINE_NUMBER_DELETE = 0x10;

    private static final int ANIM_SURFACE_POP = 0x0D;
    /** ROM {@code addq.b #7,anim(a0)}: step from a growing bubble to its flashing digit. */
    private static final int ANIM_NUMBER_FLASH_STEP = 7;
    /** ROM {@code cmpi.b #12,air_left(a2)}: above this the countdown is over. */
    private static final int DROWNING_AIR_THRESHOLD = 12;
    /** Sprite-table origin: screen-space coordinates are offset by 128. */
    private static final int SPRITE_TABLE_ORIGIN = 128;

    /** Animate_Sprite end marker {@code $FC}: advance {@code routine} by 2. */
    private static final int AF_ROUTINE = 0xFC;

    /**
     * First {@code Map_Bubbler} frame that draws an air-countdown digit rather
     * than a bubble. Frames $09-$12 all point at {@code word_2FD7A}, whose one
     * piece indexes tile {@code $384} relative to {@code ArtTile_Bubbles} —
     * that is {@code ArtTile_DashDust}, the DMA target of
     * {@code AirCountdown_Load_Art} (sonic3k.asm:33489-33516). The provider
     * rebuilds those frames against {@code ArtUnc_AirCountdown} under the
     * {@code AIR_COUNTDOWN_DIGITS} key, so this is the offset between the two
     * sheets' frame numbering.
     */
    private static final int FIRST_NUMBER_FRAME = 0x09;

    /**
     * {@code Ani_AirCountdown} (docs/skdisasm/General/Sprites/Bubbles/Anim -
     * Air Countdown.asm). Entry 0 is the frame duration, then the frame list,
     * terminated by {@code $FC}. Anims $00-$05 are the countdown bubbles that
     * grow and then reveal a digit; anim $06 is the plain breathing bubble;
     * anims $07-$0C flash a digit; $0D is the surface pop and $0E the
     * post-number fade.
     */
    private static final int[][] ANIM_SCRIPTS = {
            {0x05, 0x00, 0x01, 0x02, 0x03, 0x04, 0x09, 0x0D, AF_ROUTINE},
            {0x05, 0x00, 0x01, 0x02, 0x03, 0x04, 0x0C, 0x12, AF_ROUTINE},
            {0x05, 0x00, 0x01, 0x02, 0x03, 0x04, 0x0C, 0x11, AF_ROUTINE},
            {0x05, 0x00, 0x01, 0x02, 0x03, 0x04, 0x0B, 0x10, AF_ROUTINE},
            {0x05, 0x00, 0x01, 0x02, 0x03, 0x04, 0x0C, 0x0F, AF_ROUTINE},
            {0x05, 0x00, 0x01, 0x02, 0x03, 0x04, 0x0A, 0x0E, AF_ROUTINE},
            {0x0E, 0x00, 0x01, 0x02, AF_ROUTINE},
            {0x07, 0x16, 0x0D, 0x16, 0x0D, 0x16, 0x0D, AF_ROUTINE},
            {0x07, 0x16, 0x12, 0x16, 0x12, 0x16, 0x12, AF_ROUTINE},
            {0x07, 0x16, 0x11, 0x16, 0x11, 0x16, 0x11, AF_ROUTINE},
            {0x07, 0x16, 0x10, 0x16, 0x10, 0x16, 0x10, AF_ROUTINE},
            {0x07, 0x16, 0x0F, 0x16, 0x0F, 0x16, 0x0F, AF_ROUTINE},
            {0x07, 0x16, 0x0E, 0x16, 0x0E, 0x16, 0x0E, AF_ROUTINE},
            {0x0E, AF_ROUTINE},
            {0x0E, 0x01, 0x02, 0x03, 0x04, AF_ROUTINE},
    };

    private static final int[] WOBBLE = {
            0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2,
            2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2,
            2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0,
            0, -1, -1, -1, -1, -1, -2, -2, -2, -2, -2, -3, -3, -3, -3, -3,
            -3, -3, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4,
            -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3,
            -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1
    };

    private int routine;
    // Non-final so the generic field capturer reapplies it after generic
    // rewind recreate supplies placeholder constructor values.
    private int subtype;
    private int x;
    private int y;
    private int ySubpixel;
    private int yVel;
    private int renderFlags;
    private int anim;
    private int animFrame;
    private int animTimer;
    private int mappingFrame;
    private int obj34;
    private int obj3c;
    private int angle;
    // Non-final so the generic field capturer reapplies it after a rewind recreate.
    private int initialDisplayTimer;
    /**
     * ROM {@code parent+1(a0)} / the {@code $40} character pointer: which of
     * the two fixed controllers created this child. Non-final for the same
     * rewind-recreate reason as the fields above.
     */
    private boolean primaryPlayer;

    public S3kAirCountdownObjectInstance(int x, int y, int subtype, int angle) {
        this(x, y, subtype, angle, 0, true);
    }

    public S3kAirCountdownObjectInstance(int x, int y, int subtype, int angle, int displayTimer) {
        this(x, y, subtype, angle, displayTimer, true);
    }

    public S3kAirCountdownObjectInstance(int x, int y, int subtype, int angle, int displayTimer,
            boolean primaryPlayer) {
        super(null, "AirCountdown");
        this.x = x & 0xFFFF;
        this.y = y & 0xFFFF;
        this.subtype = subtype & 0xFF;
        this.angle = angle & 0xFF;
        this.initialDisplayTimer = displayTimer & 0xFFFF;
        this.primaryPlayer = primaryPlayer;
    }

    @Override
    public int getX() {
        return x & 0xFFFF;
    }

    @Override
    public int getY() {
        return y & 0xFFFF;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (routine == ROUTINE_DELETE || routine == ROUTINE_NUMBER_DELETE) {
            setDestroyed(true);
            return;
        }
        // Once AirCountdown_ShowNumber has parked the sprite on screen the
        // object leaves the rising/wobbling path entirely.
        if (routine == ROUTINE_AIR_LEFT) {
            airLeft();
            return;
        }
        if (routine == ROUTINE_DISPLAY_NUMBER) {
            displayNumber();
            return;
        }
        if (routine == ROUTINE_DISPLAY) {
            display();
            return;
        }

        if (routine == ROUTINE_INIT) {
            routine = ROUTINE_RISE;
            yVel = 0xFF00;
            renderFlags = 0x84;
            anim = subtype & 0xFF;
            initialiseAnimationFrame();
            obj34 = x & 0xFFFF;
            obj3c = initialDisplayTimer;
        }

        // AirCountdown_Init falls through into AirCountdown_Animate on the
        // same dispatch, so the init frame already runs one Animate_Sprite
        // step and displays the first mapping frame.
        if (routine == ROUTINE_RISE) {
            animate();
        }

        // AirCountdown_ChkWater. The pop hands over to AirCountdown_Display,
        // which for a countdown bubble is its flashing-digit animation.
        if (hasReachedWaterSurface()) {
            routine = ROUTINE_DISPLAY;
            anim = Math.min((anim + ANIM_NUMBER_FLASH_STEP) & 0xFF, ANIM_SURFACE_POP);
            initialiseAnimationFrame();
            display();
            return;
        }

        // AirCountdown_Wobble applies the x offset, then ShowNumber, then
        // MoveSprite2, then tests the render flag left by Render_Sprites.
        applyWobble();
        showNumberIfTimerExpires();
        if (routine == ROUTINE_AIR_LEFT) {
            return;
        }
        moveSprite2();
        if ((renderFlags & 0x80) == 0) {
            setDestroyed(true);
            return;
        }
        renderFlags = isWithinSolidContactBounds()
                ? (renderFlags | 0x80)
                : (renderFlags & 0x7F);
    }

    /** {@code AirCountdown_Display} (sonic3k.asm:33414-33420). */
    private void display() {
        showNumberIfTimerExpires();
        animate();
    }

    /**
     * {@code AirCountdown_AirLeft} (sonic3k.asm:33424-33444): the digit is
     * parked on screen for {@code $F} frames, then the animation switches to
     * the flashing variant unless the owner has already recovered its air.
     */
    private void airLeft() {
        if (ownerAirLeft() > DROWNING_AIR_THRESHOLD) {
            setDestroyed(true);
            return;
        }
        obj3c = (obj3c - 1) & 0xFFFF;
        if (obj3c == 0) {
            routine = ROUTINE_DISPLAY_NUMBER;
            anim = (anim + ANIM_NUMBER_FLASH_STEP) & 0xFF;
            initialiseAnimationFrame();
            display();
            return;
        }
        animate();
    }

    /** {@code AirCountdown_DisplayNumber} (sonic3k.asm:33447-33453). */
    private void displayNumber() {
        if (ownerAirLeft() > DROWNING_AIR_THRESHOLD) {
            setDestroyed(true);
            return;
        }
        showNumberIfTimerExpires();
        animate();
    }

    /**
     * ROM {@code air_left(a2)} through the object's {@code $40} character
     * pointer. The fixed controller creates one child per player, so the owner
     * resolves the same way the controller itself does.
     */
    private int ownerAirLeft() {
        AbstractPlayableSprite owner = ownerSprite();
        if (owner == null) {
            return 0;
        }
        DrowningController drowning = owner.getDrowningController();
        return drowning != null ? drowning.getRemainingAir() : 0;
    }

    private AbstractPlayableSprite ownerSprite() {
        try {
            ObjectPlayerQuery players = ObjectPlayerQuery.from(services());
            PlayableEntity owner = primaryPlayer
                    ? players.mainPlayerOrNull()
                    : players.nativeP2OrNull();
            return owner instanceof AbstractPlayableSprite sprite ? sprite : null;
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private boolean hasReachedWaterSurface() {
        try {
            WaterSystem waterSystem = services().waterSystem();
            int zoneId = services().featureZoneId();
            int actId = services().featureActId();
            if (!waterSystem.hasWater(zoneId, actId)) {
                return false;
            }
            return (short) ((y & 0xFFFF) - waterSystem.getWaterLevelY(zoneId, actId)) <= 0;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private void moveSprite2() {
        int fixedY = ((y & 0xFFFF) << 8) | (ySubpixel & 0xFF);
        fixedY = (fixedY + (short) yVel) & 0xFFFFFF;
        y = (fixedY >> 8) & 0xFFFF;
        ySubpixel = fixedY & 0xFF;
    }

    /**
     * Animate_Sprite's anim-changed branch: {@code anim_frame} and
     * {@code anim_frame_duration} are cleared so the next step immediately
     * displays the script's first frame.
     */
    private void initialiseAnimationFrame() {
        animFrame = 0;
        animTimer = 0;
        mappingFrame = 0;
    }

    /**
     * One {@code Animate_Sprite} step against {@code Ani_AirCountdown}. The
     * {@code $FC} terminator advances {@code routine} by 2 — for the rising
     * bubble that moves AirCountdown_Animate on to AirCountdown_ChkWater.
     */
    private void animate() {
        int[] script = animScript();
        if (script == null) {
            return;
        }
        if (animTimer > 0) {
            animTimer--;
            return;
        }
        animTimer = script[0];
        int index = 1 + animFrame;
        int frame = index < script.length ? script[index] : AF_ROUTINE;
        if (frame == AF_ROUTINE) {
            routine += 2;
            return;
        }
        mappingFrame = frame;
        animFrame++;
    }

    private int[] animScript() {
        int index = anim & 0xFF;
        return index < ANIM_SCRIPTS.length ? ANIM_SCRIPTS[index] : null;
    }

    private void showNumberIfTimerExpires() {
        if (obj3c == 0) {
            return;
        }
        obj3c = (short) ((obj3c - 1) & 0xFFFF);
        // ROM cmpi.b #7,anim(a0): a bubble that already flipped to its
        // flashing-digit animation never re-converts its coordinates.
        if (obj3c != 0 || anim >= ANIM_NUMBER_FLASH_STEP) {
            return;
        }
        // AirCountdown_ShowNumber converts the moving child into the fixed
        // screen-space number display for $0F frames
        // (docs/skdisasm/sonic3k.asm:33410-33432). Clearing render_flags bit 2
        // takes the object out of the camera-relative Render_Sprites path
        // (sonic3k.asm:36374), so x/y become sprite-table coordinates.
        obj3c = 0x0F;
        yVel = 0;
        renderFlags = 0x80;
        try {
            Camera camera = services().camera();
            x = (x - camera.getX() + SPRITE_TABLE_ORIGIN) & 0xFFFF;
            y = (y - camera.getY() + SPRITE_TABLE_ORIGIN) & 0xFFFF;
        } catch (IllegalStateException ex) {
            // No camera (headless construction probes): leave the world
            // position in place rather than parking the digit at an offset.
        }
        routine = ROUTINE_AIR_LEFT;
    }

    /** True once ShowNumber cleared render_flags bit 2 (sonic3k.asm:36374). */
    private boolean isScreenSpace() {
        return (renderFlags & 0x04) == 0;
    }

    private void applyWobble() {
        int offset = WOBBLE[angle & 0x7F];
        angle = (angle + 1) & 0xFF;
        x = (obj34 + offset) & 0xFFFF;
    }

    /**
     * ROM: {@code AirCountdown_Init} points the object at {@code Map_Bubbler}
     * with {@code make_art_tile(ArtTile_Bubbles,0,0)}
     * (sonic3k.asm:33320-33327) — the same mappings and Nemesis art the HCZ
     * bubbler uses, so the shared {@code BUBBLER} art set renders these
     * directly.
     *
     * <p>Digit frames ({@link #FIRST_NUMBER_FRAME} and up) come from the
     * separate {@code AIR_COUNTDOWN_DIGITS} sheet instead, which resolves the
     * {@code ArtUnc_AirCountdown} slice {@code AirCountdown_Load_Art} would
     * have DMA'd for that frame.
     */
    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        int frame = mappingFrame;
        String artKey = Sonic3kObjectArtKeys.BUBBLER;
        if (frame >= FIRST_NUMBER_FRAME) {
            frame -= FIRST_NUMBER_FRAME;
            artKey = Sonic3kObjectArtKeys.AIR_COUNTDOWN_DIGITS;
        }
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) {
            return;
        }

        int renderX = x & 0xFFFF;
        int renderY = y & 0xFFFF;
        if (isScreenSpace()) {
            // Sprite-table coordinates, where (128,128) is the screen origin.
            try {
                Camera camera = services().camera();
                renderX += camera.getX() - SPRITE_TABLE_ORIGIN;
                renderY += camera.getY() - SPRITE_TABLE_ORIGIN;
            } catch (IllegalStateException ex) {
                return;
            }
        }
        renderer.drawFrameIndex(frame, renderX, renderY, false, false);
    }

    /** ROM: {@code move.w #$80,priority(a0)} (sonic3k.asm:33330). */
    @Override
    public int getPriorityBucket() {
        return 1;
    }

    /**
     * {@code Obj_AirCountdown} writes {@code width_pixels=$10} but never
     * initializes {@code height_pixels}; the cleared SST field remains zero
     * (sonic3k.asm:33324-33330). Render_Sprites therefore uses no vertical
     * margin when refreshing this child's on-screen bit, which is also the
     * delete gate in {@code AirCountdown_Wobble} (sonic3k.asm:33400-33408).
     */
    @Override
    public int getOnScreenHalfHeight() {
        return 0;
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    int getMappingFrameForTest() {
        return mappingFrame;
    }

    boolean isScreenSpaceForTest() {
        return isScreenSpace();
    }

    @Override
    public String traceDebugDetails() {
        return String.format("r=%02X sub=%02X yv=%04X rf=%02X anim=%02X af=%02X tm=%02X map=%02X $34=%04X $3C=%04X",
                routine & 0xFF,
                subtype & 0xFF,
                yVel & 0xFFFF,
                renderFlags & 0xFF,
                anim & 0xFF,
                animFrame & 0xFF,
                animTimer & 0xFF,
                mappingFrame & 0xFF,
                obj34 & 0xFFFF,
                obj3c & 0xFFFF);
    }
}

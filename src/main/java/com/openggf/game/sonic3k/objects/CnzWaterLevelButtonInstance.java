package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.events.S3kCnzEventWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * CNZ Act 2 water-level button — the visible gray push button the player stands
 * on to raise the water the rest of the way.
 *
 * <p>ROM reference: {@code Obj_CNZWaterLevelButton} / {@code loc_65D8C}
 * (sonic3k.asm). The button is a {@code SolidObjectFull} ({@code sub_65DEC}:
 * d1=$1B, d2=4, d3=5) sharing the cutscene button's art ({@code ObjDat_CutsceneButton}).
 * When a player stands on it it shows the pressed frame, and — only while the
 * cutscene button has armed the follow-up flag ({@code _unkFAA3}) — it raises
 * {@code Target_water_level} to {@code $A58}, plays {@code sfx_Geyser}, clears
 * the arm flag, and spawns the {@code loc_62480} flash child with its subtype
 * set so the flash restores {@code Pal_CNZ} (the lights come back on).
 *
 * <p>Unlike the cutscene button, this object is not deleted after firing; it
 * stays solid. The one-shot is enforced by clearing the arm flag.
 */
public final class CnzWaterLevelButtonInstance extends AbstractObjectInstance
        implements RewindRecreatable, SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_CNZWaterLevelButton} is installed from the S3K object pointer table at
     * {@code $00065D6E} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:134058).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0006}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0006;
    }


    /** ROM: {@code move.w #$A58,(Target_water_level).w}. */
    private static final int SECOND_WATER_TARGET_Y = 0x0A58;
    /** ROM: {@code addq.w #4,y_pos(a0)}. */
    private static final int INIT_Y_OFFSET = 4;
    /** ROM {@code sub_65DEC}: SolidObjectFull d1=$1B, d2=4, d3=5. */
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x1B, 4, 5);
    private static final int PRIORITY = 4;
    private static final int FRAME_UNPRESSED = 0;
    private static final int FRAME_PRESSED = 1;

    private int x;
    private int y;

    private boolean contactStanding;
    private boolean pressedForTest;
    private int mappingFrame = FRAME_UNPRESSED;

    public CnzWaterLevelButtonInstance(ObjectSpawn spawn) {
        super(spawn, "CNZWaterLevelButton");
        this.x = spawn.x();
        this.y = spawn.y() + INIT_Y_OFFSET;
    }

    @Override
    public CnzWaterLevelButtonInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzWaterLevelButtonInstance(ctx.spawn());
    }

    /**
     * Test seam: simulates a player standing on the button for the next update.
     */
    public void forcePressedForTest() {
        pressedForTest = true;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // ROM loc_65D8C: move.b #0,mapping_frame; then set #1 if standing.
        mappingFrame = FRAME_UNPRESSED;

        boolean standing = contactStanding || pressedForTest;
        contactStanding = false;
        pressedForTest = false;
        if (!standing) {
            return;
        }
        mappingFrame = FRAME_PRESSED;

        // ROM: tst.b (_unkFAA3).w / beq — only fire while the cutscene button armed it.
        if (!S3kCnzEventWriteSupport.isWaterButtonArmed(services())) {
            return;
        }
        // ROM: clr.b (_unkFAA3).w — one-shot.
        S3kCnzEventWriteSupport.setWaterButtonArmed(services(), false);
        S3kCnzEventWriteSupport.setWaterTargetY(services(), SECOND_WATER_TARGET_Y);
        services().playSfx(Sonic3kSfx.GEYSER.id);
        // ROM: spawn loc_62480 with subtype set -> restore Pal_CNZ (lights back on).
        spawnChild(() -> new CnzLightsFlashChildInstance(buildSpawnAt(x, y), true));
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (contact.standing()) {
            contactStanding = true;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM: Obj_CNZWaterLevelButton uses ObjDat_CutsceneButton -> ArtTile_GrayButton.
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.BUTTON);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }
}

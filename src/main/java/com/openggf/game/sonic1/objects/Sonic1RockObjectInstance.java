package com.openggf.game.sonic1.objects;

import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Sonic 1 Purple Rock (GHZ) - Object ID 0x3B.
 * <p>
 * A static solid object found in Green Hill Zone. No subtypes, no movement,
 * no animation - just a solid rock that the player can stand on and collide with.
 * <p>
 * From disassembly: d1 = $1B (halfWidth), d2 = $10 (airHalfHeight),
 * d3 = $10 (groundHalfHeight), calls SolidObject.
 * <p>
 * Reference: docs/s1disasm/_incObj/3B Purple Rock.asm
 */
public class Sonic1RockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.w #$10+sonic_solid_width,d1 = $10 + $B = $1B
    private static final int HALF_WIDTH = 0x1B;

    // From disassembly: move.w #$10,d2 / move.w #$10,d3
    private static final int HALF_HEIGHT = 0x10;

    // From disassembly (Rock_Main): move.b #$13,obActWid(a0). This is the
    // standable top-surface half-width that Solid_Landed re-reads for NEW
    // landings (docs/s1disasm/_incObj/sub SolidObject.asm:270 move.b
    // obActWid(a0),d1). It is authored INDEPENDENTLY of the collision width
    // d1 = $10 + sonic_solid_width ($1B): here obActWid ($13) does NOT equal
    // d1 - sonic_solid_width ($1B - $B = $10), so the generic
    // "obActWid = collisionHalfWidth - $B" derivation would wrongly narrow the
    // landing surface to $10 and reject the GHZ2 top-landing one frame late.
    // Reference: docs/s1disasm/_incObj/3B Purple Rock.asm:20,24-28.
    private static final int ACT_WIDTH = 0x13;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    public Sonic1RockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PurpleRock");
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.ROCK);
        if (renderer == null) return;
        renderer.drawFrameIndex(0, getX(), getY(), false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    /**
     * The rock's ROM {@code obActWid}.
     *
     * <p>{@code Rock_Main} writes {@code move.b #38/2,obActWid(a0)} = $13 = 19 on
     * the shipped branch (docs/s1disasm/_incObj/3B GHZ Purple Rock.asm:20-27).
     * That site sits on a {@code FixBugs} conditional: with {@code FixBugs = 1}
     * the ROM would write {@code #48/2} = 24, the listing's own comment noting
     * that 19 "gets culled too soon". The engine takes the {@code FixBugs = 0}
     * branch (docs/s1disasm/sonic.asm:20) because that is what the shipped ROM
     * does and what every trace records.
     *
     * <p>Supplied here rather than at {@link #getBalanceWidthPixels()} because
     * both ROM consumers of the byte want it. {@code BuildSprites} uses it as
     * the horizontal on-screen cull bound, testing {@code obX - cameraX +/-
     * obActWid} against 0 and 320 (docs/s1disasm/_inc/BuildSprites.asm:49-58),
     * and {@code Sonic_Balance} reads the same byte off the stood-on object
     * (docs/s1disasm/_incObj/01 Sonic.asm:423). This class is full-solid, so
     * {@code getBalanceWidthPixels()} inherits this accessor rather than
     * {@code getSolidParams().halfWidth()}.
     *
     * <p>Neither is the rendered extent -- {@code Map_PRock} owns that -- nor the
     * collision width, which is {@code #32/2+sonic_solid_width} = $1B at
     * {@code :31} and is modelled separately as {@link #HALF_WIDTH}.
     *
     * <p>Without the override the inherited 16 put the balance edges 3px inboard
     * of the ROM's at both ends of a rock the player stands on throughout GHZ
     * (25 placements across ghz1/ghz2/ghz3).
     */
    @Override
    public int getOnScreenHalfWidth() {
        return ACT_WIDTH;
    }

    /**
     * ROM: Solid_Landed re-reads {@code obActWid(a0)} (= $13) as the standable
     * top-surface half-width for NEW landings, which for this object is narrower
     * than the collision half-width ($1B) yet wider than the generic
     * {@code collisionHalfWidth - sonic_solid_width} ($10) fallback. Supplying the
     * real obActWid keeps the GHZ2 air-roll top-landing on the ROM-accurate frame.
     * Reference: docs/s1disasm/_incObj/sub SolidObject.asm:267-277;
     * docs/s1disasm/_incObj/3B Purple Rock.asm:20.
     */
    @Override
    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        return ACT_WIDTH;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special behavior - standard solid collision handled by ObjectManager
    }
}

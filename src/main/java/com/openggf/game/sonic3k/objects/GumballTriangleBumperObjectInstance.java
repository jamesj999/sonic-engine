package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x87 - Gumball Triangle Bumper (Sonic 3 & Knuckles Gumball bonus stage).
 * <p>
 * ROM reference: sonic3k.asm Obj_GumballTriangleBumper (line 127634).
 * <p>
 * A fixed triangular bumper that bounces the player with fixed velocity on contact:
 * <ul>
 *   <li>X velocity: +/-0x300 (direction determined by h-flip render flag)</li>
 *   <li>Y velocity: -0x600 (always upward)</li>
 *   <li>Sets player airborne, clears riding/on-object flags, sets facing direction</li>
 *   <li>Sets player animation to SPRING (0x10), clears jumping flag</li>
 *   <li>0x0F frame cooldown between bounces</li>
 *   <li>Plays sfx_Spring on bounce</li>
 * </ul>
 * <p>
 * ROM attributes (ObjDat3_613A4):
 * <ul>
 *   <li>Mappings: Map_GumballBonus</li>
 *   <li>Art tile: make_art_tile(ArtTile_BonusStage, 1, 1) = palette 1, high priority</li>
 *   <li>Priority: $0100</li>
 *   <li>Sprite width: 4, height: $10</li>
 *   <li>Mapping frame: $12</li>
 * </ul>
 * <p>
 * ROM collision: SolidObjectFull with D1=$D (13), D2=8, D3=$11 (17).
 * On player standing or side push contact, applies bounce and deletes self
 * (the Gumball machine respawns bumpers). For placed-engine operation we keep
 * the bumper inert after a hit instead of deleting the instance.
 * <p>
 * Contact is resolved exclusively through the generic {@link SolidObjectProvider}
 * / {@link SolidObjectListener} pass (matching ROM's single SolidObjectFull call
 * per frame -- sonic3k.asm:127639-127660). A previous revision additionally ran
 * a per-frame proximity-box "fallback" bounce with no ROM analog (a symmetric
 * AABB using the player's full half-width/half-height on every side, rather
 * than SolidObjectFull's actual asymmetric quadrant test), which fired a frame
 * or more before the ROM's own SolidObjectFull would have registered contact --
 * observed live via TestS3kGumballBonusTraceReplay: bumper slot 6 fired the
 * fallback at trace frame 27 (player still 23px/28px away, well outside
 * SolidObjectFull's real overlap) while the recorded ROM trace stays in normal
 * free-fall through at least frame 29. Removing the fallback and relying on
 * {@link #onSolidContact} alone advances the trace frontier from frame 27 to
 * frame 112, matching ROM's own bounce-response frame for that contact.
 */
public class GumballTriangleBumperObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final int BOUNCE_X_SPEED = 0x300;
    private static final int BOUNCE_Y_SPEED = -0x600;
    private static final int MAPPING_FRAME = 0x12;
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(13, 8, 17);
    private boolean consumed;

    public GumballTriangleBumperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "GumballTriangleBumper");
    }

    @Override
    public GumballTriangleBumperObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new GumballTriangleBumperObjectInstance(ctx.spawn());
    }

    /**
     * ROM {@code loc_60F3E} (sonic3k.asm:127644-127647) opens with
     * {@code tst.w ($FF2020).l / bpl.s loc_60F8E}: while the shared
     * triangle-bumper cooldown word is non-negative the bumper branches
     * straight to {@code Draw_Sprite} and never reaches its
     * {@code jsr (SolidObjectFull)} at :127651. The bumper is therefore not
     * merely non-bouncing during the cooldown -- it is entirely intangible, so
     * neither player nor sidekick is pushed out of it.
     * <p>
     * {@code sub_60F94} arms the word with {@code move.w #$F,($FF2020).l}
     * (:127680) on a bounce and {@code loc_61050}'s
     * {@code subq.w #1,($FF2020).l} (:127743) counts it back down, both of
     * which {@link GumballMachineObjectInstance} already models
     * ({@code onBumperHit} / {@code update}).
     */
    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        if (consumed) {
            return false;
        }
        GumballMachineObjectInstance machine = currentMachineForThisContext();
        return machine == null || machine.areBumpersActive();
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    /**
     * ROM {@code SolidObjectFull_1P}'s new-contact path (the branch taken
     * whenever the player isn't already recorded as standing on this bumper)
     * falls through {@code loc_1DF88} directly into the shared
     * {@code SolidObject_cont} X-overlap test (sonic3k.asm:41395-41406), which
     * rejects only when {@code d0 > d3} ({@code bhi}) -- an exact edge touch
     * ({@code d0 == d3}, i.e. {@code relX == halfWidth*2}) still counts as
     * contact. {@code Obj_GumballTriangleBumper} calls {@code SolidObjectFull}
     * directly (sonic3k.asm:127651), so it inherits that inclusive boundary.
     * Without this override the engine's default exclusive right edge drops
     * the bounce for exactly one frame whenever the player's approach lines
     * up flush with the bumper's half-width, matching the frame-112 divergence
     * observed live via TestS3kGumballBonusTraceReplay (x_speed/y_speed still
     * pre-bounce at f112, bounce values only appear at f113).
     */
    @Override
    public boolean usesInclusiveRightEdge() {
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        if (consumed) {
            return;
        }

        GumballMachineObjectInstance machine = currentMachineForThisContext();
        if (machine != null && !machine.areBumpersActive()) {
            return;
        }

        if (!contact.standing() && !contact.touchSide()) {
            return;
        }

        applyBounce(player);
    }

    private void applyBounce(AbstractPlayableSprite player) {
        boolean hFlipped = (spawn.renderFlags() & 0x1) != 0;

        int xSpeed;
        if (hFlipped) {
            xSpeed = -BOUNCE_X_SPEED;
            player.setDirection(Direction.LEFT);
        } else {
            xSpeed = BOUNCE_X_SPEED;
            player.setDirection(Direction.RIGHT);
        }

        player.setXSpeed((short) xSpeed);
        player.setGSpeed((short) xSpeed);
        player.setYSpeed((short) BOUNCE_Y_SPEED);
        player.setAir(true);
        player.setOnObject(false);
        player.setAnimationId(Sonic3kAnimationIds.SPRING);
        player.setJumping(false);

        try {
            services().playSfx(GameSound.SPRING);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic.
        }

        GumballMachineObjectInstance machine = currentMachineForThisContext();
        if (machine != null) {
            machine.onBumperHit(spawn.subtype() & 0xFF);
        }

        consumed = true;
    }

    private GumballMachineObjectInstance currentMachineForThisContext() {
        GumballMachineObjectInstance machine =
                GumballMachineObjectInstance.current(services().objectManager());
        if (machine == null || services().currentLevel() == null) {
            return null;
        }
        return machine;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(2);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (consumed) {
            return;
        }
        if (!GumballMachineObjectInstance.shouldDebugRender(
                getPriorityBucket(), isHighPriority(), GumballMachineObjectInstance.DEBUG_SOURCE_BUMPER)) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.GUMBALL_BONUS);
        if (renderer == null) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(MAPPING_FRAME, spawn.x(), spawn.y(), hFlip, vFlip);
    }
}

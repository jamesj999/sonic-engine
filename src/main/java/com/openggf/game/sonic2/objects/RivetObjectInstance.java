package com.openggf.game.sonic2.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ExplosionObjectInstance;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * WFZ Rivet (Object 0xC2) - Rivet mechanism at the end of Wing Fortress Zone.
 * <p>
 * When the player stands on this rivet while rolling (spin attack), it busts open,
 * revealing a passage into the ship. The rivet transforms into an explosion, the
 * level layout is modified to create the opening, and the camera minimum X boundary
 * is advanced to prevent backtracking.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 80568-80625 (ObjC2)
 * <p>
 * <h3>SubObjData (ObjC2_SubObjData, s2.asm line 80620)</h3>
 * <pre>
 * subObjData ObjC2_MapUnc_3C3C2, make_art_tile(ArtTile_ArtNem_WfzSwitch,1,1),
 *            1&lt;&lt;render_flags.level_fg, 4, $10, 0
 * </pre>
 * <ul>
 *   <li>Mappings: ObjC2_MapUnc_3C3C2 (1 frame, 2 pieces forming a 32x16 rivet)</li>
 *   <li>Art tile: palette 1, priority set</li>
 *   <li>Render flags: level_fg (rendered with foreground)</li>
 *   <li>Priority: 4</li>
 *   <li>Width: $10 (16 pixels half-width)</li>
 *   <li>Collision: 0 (no touch response, uses SolidObject instead)</li>
 * </ul>
 * <p>
 * <h3>Solid Object Parameters (ObjC2_Main, s2.asm line 80590)</h3>
 * <pre>
 * move.w #$1B,d1   ; half-width = 27
 * move.w #8,d2     ; half-height (air) = 8
 * move.w #9,d3     ; half-height (ground) = 9
 * </pre>
 * <p>
 * <h3>Bust Behavior (ObjC2_Bust, s2.asm lines 80600-80617)</h3>
 * When the player is standing on the rivet and their animation is rolling (anim == 2):
 * <ol>
 *   <li>Camera_Min_X_pos set to $2880 (prevents backtracking)</li>
 *   <li>p1_standing_bit cleared on rivet</li>
 *   <li>Object transforms into explosion (ObjID_Explosion)</li>
 *   <li>Player set to in-air, cleared from on-object</li>
 *   <li>Level layout modified at offsets $850 and $950 to open the ship passage</li>
 *   <li>Screen_redraw_flag set to force tilemap rebuild</li>
 * </ol>
 */
public class RivetObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(RivetObjectInstance.class.getName());

    // Solid object parameters from disassembly (s2.asm line 80590)
    // move.w #$1B,d1 ; half-width
    private static final int SOLID_HALF_WIDTH = 0x1B;
    // move.w #8,d2   ; half-height (air)
    private static final int SOLID_HALF_HEIGHT_AIR = 8;
    // move.w #9,d3   ; half-height (ground)
    private static final int SOLID_HALF_HEIGHT_GROUND = 9;

    // Camera boundary set when rivet busts (s2.asm line 80603)
    // move.w #$2880,(Camera_Min_X_pos).w
    private static final int BUST_CAMERA_MIN_X = 0x2880;

    // Level layout modification constants (s2.asm lines 80609-80614)
    // ROM: lea (Level_Layout+$850).w,a1
    // Layout is 2 layers * 128 wide = 256 bytes per row
    // Offset $850 = row 8, layer 0 (FG), x = 80
    private static final int LAYOUT_ROW_0_Y = 8;
    private static final int LAYOUT_ROW_0_X = 80;
    // Offset $950 = row 9, layer 0 (FG), x = 80
    private static final int LAYOUT_ROW_1_Y = 9;
    private static final int LAYOUT_ROW_1_X = 80;

    // Block values written to open the ship passage (s2.asm lines 80610-80614)
    // Row at y=8: move.l #$8A707172,(a1)+ / move.w #$7374,(a1)+
    private static final byte[] LAYOUT_ROW_0_BLOCKS = {
            (byte) 0x8A, (byte) 0x70, (byte) 0x71, (byte) 0x72, (byte) 0x73, (byte) 0x74
    };
    // Row at y=9: move.l #$6E787978,(a1)+ / move.w #$787A,(a1)+
    private static final byte[] LAYOUT_ROW_1_BLOCKS = {
            (byte) 0x6E, (byte) 0x78, (byte) 0x79, (byte) 0x78, (byte) 0x78, (byte) 0x7A
    };

    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(
            SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT_AIR, SOLID_HALF_HEIGHT_GROUND);

    private boolean busted;

    // Tracks the player's animation state each frame (ROM: objoff_30)
    private int cachedMainAnimationId;
    private transient AbstractPlayableSprite lastNativeMainPlayer;

    public RivetObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.busted = false;
    }

    @Override
    public RivetObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new RivetObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (busted) {
            return;
        }

        // ROM: ObjC2_Main (s2.asm line 80588)
        // move.b (MainCharacter+anim).w,objoff_30(a0)
        AbstractPlayableSprite player = nativeMainPlayer(playerEntity);
        lastNativeMainPlayer = player;
        cachedMainAnimationId = player != null ? player.getAnimationId() : 0;
    }

    // ========================================================================
    // SolidObjectProvider
    // ========================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !busted;
    }

    // ========================================================================
    // SolidObjectListener
    // ========================================================================

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (busted || player == null) {
            return;
        }

        // ROM: btst #p1_standing_bit,status(a0)
        // ROM: bne.s ObjC2_Bust
        if (!contact.standing()) {
            return;
        }

        // ROM ObjC2 only checks MainCharacter and has no Sidekick branch.
        PlayableEntity nativeMain = services().playerQuery().mainPlayerOrNull();
        if (nativeMain == null) {
            nativeMain = lastNativeMainPlayer;
        }
        if (nativeMain != player) {
            return;
        }

        // ROM: ObjC2_Bust (s2.asm line 80600)
        // cmpi.b #2,objoff_30(a0) - check if player was rolling (anim == 2)
        // bne.s + (skip if not rolling)
        if (cachedMainAnimationId != Sonic2AnimationIds.ROLL.id()) {
            return;
        }

        bustRivet(player);
    }

    private AbstractPlayableSprite nativeMainPlayer(PlayableEntity updatePlayer) {
        PlayableEntity main = services().playerQuery().mainPlayerOrNull();
        if (main instanceof AbstractPlayableSprite sprite) {
            return sprite;
        }
        return updatePlayer instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    /**
     * Busts the rivet open, modifying the level layout and spawning an explosion.
     * ROM: ObjC2_Bust (s2.asm lines 80600-80617)
     */
    private void bustRivet(AbstractPlayableSprite player) {
        busted = true;

        // ROM: move.w #$2880,(Camera_Min_X_pos).w (s2.asm line 80603)
        services().camera().setMinX((short) BUST_CAMERA_MIN_X);

        // ROM: bclr #p1_standing_bit,status(a0) (s2.asm line 80604)
        // (Handled by engine when we destroy the object)

        // ROM: bset #status.player.in_air,(MainCharacter+status).w (s2.asm line 80607)
        // ROM: bclr #status.player.on_object,(MainCharacter+status).w (s2.asm line 80608)
        // clearRidingObject drops the engine's riding-state link; without it the
        // solid-contact latch re-seats the player as grounded/on-object every frame
        // and he never falls. Mirror the ROM bclr by clearing onObject + air after.
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }
        player.setAir(true);
        player.setOnObject(false);

        // ROM: Modify level layout to open ship passage (s2.asm lines 80609-80614)
        modifyLevelLayout();

        // ROM: _move.b #ObjID_Explosion,id(a0) (s2.asm line 80605)
        // Transform into explosion - spawn an explosion object at our position
        spawnExplosion();

        // Mark this object as destroyed
        setDestroyed(true);

        LOGGER.fine(() -> String.format("Rivet busted at (%d,%d) - ship passage opened",
                spawn.x(), spawn.y()));
    }

    /**
     * Modifies the level layout to create the ship passage opening.
     * ROM: s2.asm lines 80609-80614
     * <pre>
     * lea (Level_Layout+$850).w,a1
     * move.l #$8A707172,(a1)+
     * move.w #$7374,(a1)+
     * lea (Level_Layout+$950).w,a1
     * move.l #$6E787978,(a1)+
     * move.w #$787A,(a1)+
     * </pre>
     */
    private void modifyLevelLayout() {
        // ROM: move.l #$8A707172,(a1)+ / move.w #$7374,(a1)+ (row 0, Level_Layout+$850)
        //      move.l #$6E787978,(a1)+ / move.w #$787A,(a1)+ (row 1, Level_Layout+$950)
        // ROM: move.b #1,(Screen_redraw_flag).w — pipeline publishes redraw effects automatically.
        services().zoneLayoutMutationPipeline().queue(context -> {
            try {
                MutationEffects effects = MutationEffects.NONE;
                // Write row 0 blocks (Level_Layout+$850): 6 blocks starting at FG x=80, y=8
                for (int i = 0; i < LAYOUT_ROW_0_BLOCKS.length; i++) {
                    effects = context.surface().setBlockInMap(0,
                            LAYOUT_ROW_0_X + i, LAYOUT_ROW_0_Y,
                            LAYOUT_ROW_0_BLOCKS[i] & 0xFF);
                }
                // Write row 1 blocks (Level_Layout+$950): 6 blocks starting at FG x=80, y=9
                for (int i = 0; i < LAYOUT_ROW_1_BLOCKS.length; i++) {
                    effects = context.surface().setBlockInMap(0,
                            LAYOUT_ROW_1_X + i, LAYOUT_ROW_1_Y,
                            LAYOUT_ROW_1_BLOCKS[i] & 0xFF);
                }
                return effects;
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Rivet layout modification failed: " + e.getMessage());
                return MutationEffects.NONE;
            }
        });
    }

    /**
     * Spawns an explosion at the rivet's position.
     * ROM: _move.b #ObjID_Explosion,id(a0) / move.b #2,routine(a0)
     * The ROM transforms the object in-place into an explosion (Obj27).
     */
    private void spawnExplosion() {
        ObjectManager objectManager = services().objectManager();
        ObjectRenderManager renderManager = services().renderManager();
        if (objectManager == null || renderManager == null) {
            return;
        }

        // ROM transforms the rivet into Obj27, whose init plays SndID_Explosion
        // ($C1; s2.asm:46728). Pass the SFX so the spawned explosion is not silent.
        spawnFreeChild(() -> new ExplosionObjectInstance(
                Sonic2ObjectIds.EXPLOSION, spawn.x(), spawn.y(), renderManager,
                Sonic2AudioConstants.SFX_EXPLOSION));
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (busted) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_RIVET);
        if (renderer == null) return;

        // ROM: single mapping frame (frame 0), no flip
        renderer.drawFrameIndex(0, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (busted) {
            return;
        }

        // Draw solid collision box (green)
        ctx.drawRect(spawn.x(), spawn.y(), SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT_GROUND,
                0.0f, 1.0f, 0.5f);

        // Draw label
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -12, "Rivet",
                com.openggf.debug.DebugColor.YELLOW);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: SubObjData priority = 4
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzSwitch,1,1)
        return true;
    }
}

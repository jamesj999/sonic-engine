package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.*;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Collapsing Platform (Object 0x1F) - OOZ, MCZ, and ARZ.
 * <p>
 * A platform that collapses into falling fragments when the player stands on it
 * for 7 frames. Each zone has different art, fragment counts, and delay patterns.
 * <p>
 * Based on Obj1F in the Sonic 2 disassembly (s2.asm).
 * <p>
 * Zone-specific behavior:
 * - OOZ: 7 fragments, dedicated Nemesis art at 0x809D0
 * - MCZ: 6 fragments, dedicated Nemesis art at 0xF1ABA
 * - ARZ: 8 fragments, uses level art tiles (0x55, 0x59, 0xA3, 0xA7)
 */
public class CollapsingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(CollapsingPlatformObjectInstance.class.getName());

    /**
     * Zone-specific configuration for collapsing platforms.
     * <p>
     * Fragment visual offsets are stored in the sprite mapping data. Each
     * fragment spawns at the parent's exact x/y position, and the sprite
     * piece's offsets provide the visual displacement.
     */
    private record ZoneConfig(
            int halfWidth,
            int halfHeight,
            int[] delayData,
            String artKey,
            int palette,
            boolean usesLevelArt
    ) {}

    // OOZ: 7 fragments from obj1F_b.asm
    // Delay values: $1A, $12, $0A, $02, $16, $0E, $06
    // Piece offsets from mapping data (for debug rendering)
    private static final ZoneConfig OOZ_CONFIG = new ZoneConfig(
            0x40,  // width_pixels from disassembly (half-width, collision extends ±0x40 from center)
            0x10,  // 16px half-height
            new int[]{0x1A, 0x12, 0x0A, 0x02, 0x16, 0x0E, 0x06},
            Sonic2ObjectArtKeys.OOZ_COLLAPSING_PLATFORM,
            3,
            false
    );

    // MCZ: 6 fragments from obj1F_c.asm
    // Delay values: $1A, $16, $12, $0E, $0A, $02
    private static final ZoneConfig MCZ_CONFIG = new ZoneConfig(
            0x20,  // width_pixels from disassembly (half-width, collision extends ±0x20 from center)
            0x10,  // 16px half-height
            new int[]{0x1A, 0x16, 0x12, 0x0E, 0x0A, 0x02},
            Sonic2ObjectArtKeys.MCZ_COLLAPSING_PLATFORM,
            3,
            false
    );

    // ARZ: 8 fragments from obj1F_d.asm
    // Delay values: $16, $1A, $18, $12, $06, $0E, $0A, $02
    private static final ZoneConfig ARZ_CONFIG = new ZoneConfig(
            0x20,  // width_pixels from disassembly (half-width, collision extends ±0x20 from center)
            0x10,  // 16px half-height
            new int[]{0x16, 0x1A, 0x18, 0x12, 0x06, 0x0E, 0x0A, 0x02},
            null,  // Uses level art
            2,
            true
    );

    // Default config for unknown zones (uses OOZ config)
    private static final ZoneConfig DEFAULT_CONFIG = OOZ_CONFIG;

    // ARZ uses level art tiles at specific indices from obj1F_d.asm
    // Palette line 2 as per disassembly: make_art_tile(ArtTile_ArtKos_LevelArt,2,0)
    private static final int ARZ_PALETTE = 2;

    // ARZ Frame 0 (intact) - 4 pieces from obj1F_d.asm Map_obj1F_d_0004
    // Note: piece palette is 0 so that (0 + ARZ_PALETTE) & 3 = 2 (correct palette)
    private static final SpriteMappingFrame ARZ_FRAME_INTACT = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(-0x20, -0x10, 4, 2, 0x55, false, false, 0),  // Top-left
            new SpriteMappingPiece(0x00, -0x10, 4, 2, 0x55, true, false, 0),   // Top-right (H-flip)
            new SpriteMappingPiece(-0x20, 0x00, 4, 2, 0xA3, false, false, 0),  // Bottom-left
            new SpriteMappingPiece(0x00, 0x00, 4, 2, 0xA3, true, false, 0)     // Bottom-right (H-flip)
    ));

    // ARZ Frame 1 (collapsed) - 8 pieces from obj1F_d.asm Map_obj1F_d_0026
    // Note: piece palette is 0 so that (0 + ARZ_PALETTE) & 3 = 2 (correct palette)
    private static final SpriteMappingFrame ARZ_FRAME_COLLAPSED = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(-0x20, -0x10, 2, 2, 0x55, false, false, 0),  // Piece 0
            new SpriteMappingPiece(-0x10, -0x10, 2, 2, 0x59, false, false, 0),  // Piece 1
            new SpriteMappingPiece(0x00, -0x10, 2, 2, 0x59, true, false, 0),   // Piece 2 (H-flip)
            new SpriteMappingPiece(0x10, -0x10, 2, 2, 0x55, true, false, 0),   // Piece 3 (H-flip)
            new SpriteMappingPiece(-0x20, 0x00, 2, 2, 0xA3, false, false, 0),  // Piece 4
            new SpriteMappingPiece(-0x10, 0x00, 2, 2, 0xA7, false, false, 0),  // Piece 5
            new SpriteMappingPiece(0x00, 0x00, 2, 2, 0xA7, true, false, 0),    // Piece 6 (H-flip)
            new SpriteMappingPiece(0x10, 0x00, 2, 2, 0xA3, true, false, 0)     // Piece 7 (H-flip)
    ));

    // State
    private static final int INITIAL_DELAY = 7;  // 7 frames before collapse starts

    // Gravity from ObjectMoveAndFall (s2.asm line 29950)
    private static final int GRAVITY = 0x38;
    // Obj1F never sets render_flags.explicit_height, so S2 BuildSprites uses
    // its approximate Y culling band instead of y_radius(a0)
    // (docs/s2disasm/s2.asm:30584-30619).
    private static final int APPROX_RENDER_Y_MARGIN = 0x20;

    private ZoneConfig config;
    private int delayCounter = INITIAL_DELAY;
    private boolean stoodOnFlag = false;
    private boolean standingContactLastFrame = false;
    private boolean collapsed = false;
    private boolean inFragmentPhase = false;  // ROM: parent becomes fragment 0, stays solid during delay
    private int fragmentPhaseDelay = 0;       // ROM: delay_counter for the parent-as-fragment-0
    private int mappingFrame = 0;  // 0 = intact, 1 = collapsed appearance

    // Post-fragment parent fall state (ROM: Obj1F_FragmentFall)
    private int parentVelY;
    private int parentY;
    private int parentYFrac;

    // Orientation from spawn render_flags (inherited by fragments per disassembly)
    private boolean hFlip;
    private boolean vFlip;

    public CollapsingPlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        // Extract flip flags from spawn renderFlags (bit 0 = hFlip, bit 1 = vFlip)
        this.hFlip = (spawn.renderFlags() & 1) != 0;
        this.vFlip = (spawn.renderFlags() & 2) != 0;
        initZoneConfig();
    }

    @Override
    public CollapsingPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new CollapsingPlatformObjectInstance(ctx.spawn(), getName()));
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        // ROM Obj1F_FragmentFall moves the parent object itself with
        // ObjectMoveAndFall before checking render_flags.on_screen and
        // DeleteObject (docs/s2disasm/s2.asm:23860-23864). Once detached,
        // expose that falling y_pos so slot/offscreen lifecycle observes the
        // moving parent rather than the original placement y.
        return collapsed ? parentY : spawn.y();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        SolidCheckpointBatch batch = services().solidExecution().resolveSolidNowAll();
        boolean standingThisFrame = hasStandingContact(batch);

        // ROM: Obj1F_FragmentFall - collapsed parent falls with gravity, then
        // deletes when render_flags.on_screen is clear
        // (docs/s2disasm/s2.asm:23860-23864).
        if (collapsed) {
            // ROM Obj1F_FragmentFall tests render_flags.on_screen, which the
            // LAST BuildSprites pass wrote (docs/s2disasm/s2.asm:23860-23864,
            // 30560 for the per-object clear and 30612 for the draw that sets
            // it). BuildSprites runs after RunObjects and after DeformBgLayer
            // publishes Camera_X/Y_pos_copy (s2.asm:5095-5111, 15178-15179), so
            // on this frame the flag describes the object's PREVIOUS-frame
            // position against the camera the engine is still holding. Sample
            // the bounds before ObjectMoveAndFall moves the object, exactly as
            // S1's Obj1A fragment tail already does.
            boolean renderedLastBuildSpritesPass =
                    isPreUpdateWithinRenderSpriteBounds(config.halfWidth(), APPROX_RENDER_Y_MARGIN);
            // ROM ObjectMoveAndFall reads the old y_vel for this frame's
            // position update, then adds gravity for the next frame
            // (docs/s2disasm/s2.asm:29945-29960).
            int oldVelY = (int) (short) parentVelY;
            parentVelY += GRAVITY;
            int y32 = (parentY << 16) | (parentYFrac & 0xFFFF);
            y32 += oldVelY << 8;
            parentY = y32 >> 16;
            parentYFrac = y32 & 0xFFFF;
            if (!renderedLastBuildSpritesPass) {
                setDestroyed(true);
            }
            return;
        }

        // ROM: Obj1F_Fragment phase — parent stays solid at its original position
        // until its fragment delay expires, then detaches the player.
        // In the ROM, the parent object IS fragment 0 (routine changed to Obj1F_Fragment).
        // Only fragment 0 has stood_on_flag=1 so only it calls PlatformObject.
        if (inFragmentPhase) {
            if (fragmentPhaseDelay > 0) {
                fragmentPhaseDelay--;
                // ROM: sub_10B36 (s2.asm:23730-23737) clears only
                // Status_OnObj and Status_Push when the parent-fragment
                // delay expires. Status_InAir is left for normal player
                // movement to set on the next unsupported frame.
                if (fragmentPhaseDelay <= 0) {
                    collapsed = true;
                    parentY = spawn.y();
                    parentYFrac = 0;
                    parentVelY = 0;
                    detachFragmentRiders(batch);
                }
            }
            return;
        }

        // ROM: Obj1F_Main — check stood_on_flag and delay_counter
        if (stoodOnFlag) {
            if (delayCounter <= 0) {
                collapse();
                return;
            }
            delayCounter--;
        }

        // ROM Obj1F_Main reads status(a0) before PlatformObject writes the
        // current frame's standing bits (docs/s2disasm/s2.asm:23815-23827).
        // The manual checkpoint already knows the current contact, so consume
        // the previous-frame contact here to keep the collapse timer aligned.
        if (standingContactLastFrame) {
            stoodOnFlag = true;
        }
        standingContactLastFrame = standingThisFrame;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null || config == null) {
            return;
        }

        if (inFragmentPhase || collapsed) {
            renderParentFragment(renderManager);
            return;
        }

        if (config.usesLevelArt()) {
            // ARZ uses level patterns - render using level tiles
            renderUsingLevelArt(commands);
        } else {
            // OOZ/MCZ use dedicated art
            PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey());
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
        }
    }

    private void renderParentFragment(ObjectRenderManager renderManager) {
        if (config.usesLevelArt()) {
            renderArzPieces(List.of(ARZ_FRAME_COLLAPSED.pieces().get(0)), spawn.x(), getY(), false);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey());
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFramePieceByIndex(mappingFrame, 0, spawn.x(), getY(), hFlip, vFlip);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (config == null) {
            return SolidObjectParams.of(0x40, 0x10, 0x10);
        }
        return SolidObjectParams.of(config.halfWidth(), config.halfHeight(), config.halfHeight());
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive collapsing-platform standing state from update().
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !collapsed && !isDestroyed();
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        // Platform must remain in the active set during the fragment phase so
        // SolidContacts can continue repositioning the player on fragment 0,
        // then render/update the parent fragment fall until offscreen deletion.
        // Matches S1 pattern where markRemembered is deferred to destroy.
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    private void initZoneConfig() {
        final int zoneIndex = (services().currentLevel() != null)
                ? services().currentLevel().getZoneIndex()
                : -1;

        config = switch (zoneIndex) {
            case Sonic2Constants.ZONE_OIL_OCEAN -> OOZ_CONFIG;
            case Sonic2Constants.ZONE_MYSTIC_CAVE -> MCZ_CONFIG;
            case Sonic2Constants.ZONE_ARZ -> ARZ_CONFIG;
            default -> DEFAULT_CONFIG;
        };

        LOGGER.fine(() -> String.format("CollapsingPlatform at (%d,%d) using %s config",
                spawn.x(), spawn.y(),
                zoneIndex == Sonic2Constants.ZONE_OIL_OCEAN ? "OOZ" :
                        zoneIndex == Sonic2Constants.ZONE_MYSTIC_CAVE ? "MCZ" :
                                zoneIndex == Sonic2Constants.ZONE_ARZ ? "ARZ" : "DEFAULT"));
    }

    private boolean isPlayerStanding() {
        if (services().objectManager() == null) {
            return false;
        }
        return services().objectManager().isAnyPlayerRiding(this);
    }

    protected boolean hasStandingContact(SolidCheckpointBatch batch) {
        for (PlayerSolidContactResult result : batch.perPlayer().values()) {
            if (result != null && result.standingNow()) {
                return true;
            }
        }
        return false;
    }

    private void collapse() {
        if (inFragmentPhase || collapsed) {
            return;
        }

        // ROM: Obj1F_CreateFragments — parent becomes fragment 0
        // The parent stays solid at its original position during the fragment delay.
        // Only fragment 0 (the parent) provides collision; other fragments are visual only.
        inFragmentPhase = true;
        fragmentPhaseDelay = config.delayData()[0];  // Parent gets first delay value
        mappingFrame = 1;  // Show collapsed appearance

        // Play collapse sound
        services().playSfx(Sonic2Sfx.SMASH.id);

        // Spawn visual-only fragments (they do NOT provide collision)
        spawnFragments();

        // Mark as remembered to prevent respawn (ROM-accurate behavior)
        ObjectLifetimeOps.markSpawnRemembered(services().objectManager(), spawn);

        LOGGER.fine(() -> String.format("CollapsingPlatform at (%d,%d) collapsed, spawning %d fragments",
                spawn.x(), spawn.y(), config.delayData().length));
    }

    private void detachFragmentRiders(SolidCheckpointBatch batch) {
        ObjectManager objectManager = services().objectManager();
        if (batch == null || objectManager == null) {
            return;
        }
        for (PlayableEntity rider : batch.perPlayer().keySet()) {
            if (rider != null && objectManager.isRidingObject(rider, this)) {
                objectManager.clearRidingObject(rider);
                rider.setOnObject(false);
                rider.setPushing(false);
                rider.forceAnimationRestart();
            }
        }
    }

    private void spawnFragments() {
        ObjectManager objectManager = services().objectManager();
        ObjectRenderManager renderManager = services().renderManager();
        if (objectManager == null) {
            return;
        }

        int[] delayData = config.delayData();

        // ROM Obj1F_CreateFragments turns the parent object into fragment 0;
        // FindFreeObj is only used for the remaining fragments
        // (docs/s2disasm/s2.asm:23752-23815). Allocating a child for index 0
        // consumes an extra SST slot and shifts later objects by one.
        for (int i = 1; i < delayData.length; i++) {
            int delay = delayData[i];

            final int pieceIndex = i;
            spawnFreeChild(() -> new CollapsingPlatformFragmentInstance(
                    spawn.x(), spawn.y(), delay, pieceIndex, config, renderManager, hFlip, vFlip));
        }
    }

    private void renderUsingLevelArt(List<GLCommand> commands) {
        // ARZ uses level art tiles at specific indices from obj1F_d.asm
        // basePatternIndex = 0 because level patterns start at index 0
        SpriteMappingFrame frame = (mappingFrame == 0) ? ARZ_FRAME_INTACT : ARZ_FRAME_COLLAPSED;
        renderArzPieces(frame.pieces(), spawn.x(), spawn.y(), true);
    }

    private void renderArzPieces(List<SpriteMappingPiece> pieces, int originX, int originY, boolean reverse) {
        GraphicsManager graphicsManager = services().graphicsManager();
        // Draw in reverse order (Painter's Algorithm) - first piece in list appears on top
        int start = reverse ? pieces.size() - 1 : 0;
        int endExclusive = reverse ? -1 : pieces.size();
        int step = reverse ? -1 : 1;
        for (int i = start; i != endExclusive; i += step) {
            SpritePieceRenderer.renderPieces(
                    List.of(pieces.get(i)),
                    originX,
                    originY,
                    0,  // Level patterns start at index 0
                    ARZ_PALETTE,
                    hFlip,  // Frame H-flip from spawn render_flags
                    vFlip,  // Frame V-flip from spawn render_flags
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (config == null) {
            return;
        }
        int halfWidth = config.halfWidth();
        int halfHeight = config.halfHeight();
        int x = spawn.x();
        int y = spawn.y();
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        float r = collapsed ? 0.5f : 0.8f;
        float g = 0.4f;
        float b = collapsed ? 0.2f : 0.6f;

        ctx.drawLine(left, top, right, top, r, g, b);
        ctx.drawLine(right, top, right, bottom, r, g, b);
        ctx.drawLine(right, bottom, left, bottom, r, g, b);
        ctx.drawLine(left, bottom, left, top, r, g, b);
    }

    /**
     * Visual-only fragment that falls after the platform collapses.
     * Each fragment has a staggered delay before it starts falling with gravity.
     * <p>
     * In the ROM, only fragment 0 (the parent object itself, reusing its SST) provides
     * collision via PlatformObject. All other fragments have stood_on_flag=0 and never
     * call PlatformObject — they are purely visual. The parent class handles the collision
     * during the fragment phase via {@code inFragmentPhase} / {@code fragmentPhaseDelay}.
     * <p>
     * Each fragment uses static_mappings mode where the mappings pointer points to a
     * single sprite piece. The piece's x/y offsets provide visual displacement.
     */
    public static class CollapsingPlatformFragmentInstance extends AbstractFallingFragment
            implements RewindRecreatable {

        private static final int FRAGMENT_INDEX_MASK = 0x07;
        private static final int CONFIG_SHIFT = 3;
        private static final int CONFIG_MASK = 0x03;
        private static final int CONFIG_OOZ = 0;
        private static final int CONFIG_MCZ = 1;
        private static final int CONFIG_ARZ = 2;
        private static final int RENDER_H_FLIP = 0x01;
        private static final int RENDER_V_FLIP = 0x02;

        private int fragmentIndex;
        private final ZoneConfig config;
        private final ObjectRenderManager renderManager;

        // Inherited from parent (per disassembly: render_flags copied from parent to fragment)
        private boolean hFlip;
        private boolean vFlip;

        public CollapsingPlatformFragmentInstance(int parentX, int parentY, int delay, int fragmentIndex,
                                                   ZoneConfig config, ObjectRenderManager renderManager,
                                                   boolean hFlip, boolean vFlip) {
            this(fragmentSpawn(parentX, parentY, fragmentIndex, config, hFlip, vFlip),
                    delay, fragmentIndex, config, renderManager, hFlip, vFlip);
        }

        public CollapsingPlatformFragmentInstance(ObjectSpawn spawn) {
            this(spawn, 0, decodeFragmentIndex(spawn), decodeConfig(spawn), null,
                    decodeHFlip(spawn), decodeVFlip(spawn));
        }

        private CollapsingPlatformFragmentInstance(ObjectSpawn spawn, int delay, int fragmentIndex,
                                                   ZoneConfig config, ObjectRenderManager renderManager,
                                                   boolean hFlip, boolean vFlip) {
            super(spawn, "CollapsingPlatformFragment", delay, 4);

            this.fragmentIndex = fragmentIndex;
            this.config = config;
            this.renderManager = renderManager;
            this.hFlip = hFlip;
            this.vFlip = vFlip;
        }

        @Override
        public CollapsingPlatformFragmentInstance recreateForRewind(RewindRecreateContext ctx) {
            return new CollapsingPlatformFragmentInstance(ctx.spawn());
        }

        private static ObjectSpawn fragmentSpawn(int parentX, int parentY, int fragmentIndex,
                                                 ZoneConfig config, boolean hFlip, boolean vFlip) {
            int subtype = (fragmentIndex & FRAGMENT_INDEX_MASK)
                    | ((configId(config) & CONFIG_MASK) << CONFIG_SHIFT);
            int renderFlags = (hFlip ? RENDER_H_FLIP : 0) | (vFlip ? RENDER_V_FLIP : 0);
            return new ObjectSpawn(parentX, parentY, 0x1F, subtype, renderFlags, false, 0);
        }

        private static int decodeFragmentIndex(ObjectSpawn spawn) {
            return spawn.subtype() & FRAGMENT_INDEX_MASK;
        }

        private static ZoneConfig decodeConfig(ObjectSpawn spawn) {
            int configId = (spawn.subtype() >> CONFIG_SHIFT) & CONFIG_MASK;
            return switch (configId) {
                case CONFIG_MCZ -> MCZ_CONFIG;
                case CONFIG_ARZ -> ARZ_CONFIG;
                default -> OOZ_CONFIG;
            };
        }

        private static boolean decodeHFlip(ObjectSpawn spawn) {
            return (spawn.renderFlags() & RENDER_H_FLIP) != 0;
        }

        private static boolean decodeVFlip(ObjectSpawn spawn) {
            return (spawn.renderFlags() & RENDER_V_FLIP) != 0;
        }

        private static int configId(ZoneConfig config) {
            if (config == MCZ_CONFIG) {
                return CONFIG_MCZ;
            }
            if (config == ARZ_CONFIG) {
                return CONFIG_ARZ;
            }
            return CONFIG_OOZ;
        }

        @Override
        protected boolean shouldDeleteAfterFall() {
            // ROM Obj1F_CreateFragments copies the parent's x_pos/y_pos into
            // child slots and advances the mappings pointer per fragment; the
            // visual offset is in mappings data
            // (docs/s2disasm/s2.asm:23880-23906).
            //
            // Obj1F_FragmentFall then deletes on render_flags.on_screen
            // (s2.asm:23860-23864), which is written by the LAST BuildSprites
            // pass -- BuildSprites clears the bit per object (s2.asm:30560) and
            // only DrawSprite re-sets it, and it runs after RunObjects and
            // after DeformBgLayer publishes Camera_X/Y_pos_copy
            // (s2.asm:5095-5111, 15178-15179). The deciding position is
            // therefore this frame's PRE-fall one, matching the same tail in
            // S1's Obj1A fragments. Testing the post-fall position instead
            // retired the SST slot up to two frames early, which shifted every
            // later FindFreeObj/AllocateObject result.
            ZoneConfig activeConfig = config == null ? DEFAULT_CONFIG : config;
            return !isPreUpdateWithinRenderSpriteBounds(
                    activeConfig.halfWidth(), APPROX_RENDER_Y_MARGIN);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }

            if (renderManager == null || config == null) {
                appendDebugFragment(commands);
                return;
            }

            if (config.usesLevelArt()) {
                // ARZ fragments use level art
                renderArzFragment();
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey());
            if (renderer == null || !renderer.isReady()) {
                appendDebugFragment(commands);
                return;
            }

            // Obj1F_CreateFragments increments mapping_frame, then gives each
            // fragment a static mapping pointer to one piece from that frame.
            int frameIndex = 1;

            renderer.drawFramePieceByIndex(frameIndex, fragmentIndex, getX(), getY(), hFlip, vFlip);
        }

        /**
         * Render an ARZ fragment using level patterns.
         * Each fragment renders its corresponding piece from ARZ_FRAME_COLLAPSED.
         * ROM keeps every fragment slot at the parent's x_pos/y_pos and lets the
         * static one-piece mapping supply the visual offset.
         */
        private void renderArzFragment() {
            if (fragmentIndex < 0 || fragmentIndex >= ARZ_FRAME_COLLAPSED.pieces().size()) {
                return;
            }

            SpriteMappingPiece piece = ARZ_FRAME_COLLAPSED.pieces().get(fragmentIndex);
            GraphicsManager graphicsManager = services().graphicsManager();

            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    getX(),
                    getY(),
                    0,  // Level patterns start at index 0
                    ARZ_PALETTE,
                    hFlip,  // Frame H-flip inherited from parent
                    vFlip,  // Frame V-flip inherited from parent
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }

        private void appendDebugFragment(List<GLCommand> commands) {
            int renderX = getX();
            int renderY = getY();

            int size = 12;  // Approximate piece size for debug
            int left = renderX - size;
            int right = renderX + size;
            int top = renderY - size;
            int bottom = renderY + size;

            float r = 0.42f;
            float g = 0.21f;
            float b = 0.07f;

            // Draw a small square for the fragment
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, left, top, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, right, top, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, right, top, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, right, bottom, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, right, bottom, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, left, bottom, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, left, bottom, 0, 0));
            commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    r, g, b, left, top, 0, 0));
        }
    }
}

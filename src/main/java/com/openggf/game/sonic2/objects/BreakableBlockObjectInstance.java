package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.BoxObjectInstance;

import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.PlayableEntity;

import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.*;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * CPZ Breakable Block (Object 0x32) - Metal blocks that shatter when Sonic rolls into them.
 *
 * Based on Obj32 in the Sonic 2 disassembly (s2.asm lines 48829-49020).
 *
 * Behavior:
 * - Acts as a solid platform that players can stand on
 * - Only breaks when a player standing on it is rolling (spin attack)
 * - When broken, spawns 4 fragment objects that fly apart
 * - Player bounces upward when block breaks
 * - Plays SLOW_SMASH sound effect (0xCB)
 */
public class BreakableBlockObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(BreakableBlockObjectInstance.class.getName());

    // From disassembly:
    // - CPZ: move.b #$10,width_pixels(a0) (32px wide)
    // - HTZ: move.b #$18,width_pixels(a0) (48px wide)
    private static final int CPZ_HALF_WIDTH = 0x10;  // 16 pixels
    private static final int HTZ_HALF_WIDTH = 0x18;  // 24 pixels
    private static final int HALF_HEIGHT = 0x10;     // 16 pixels

    // From disassembly: move.w #-$300,y_vel(a1) for player bounce
    private static final int PLAYER_BOUNCE_VELOCITY = -0x300;

    // Fragment velocities from Obj32_VelArray2 (CPZ version):
    // -$100, -$200  ; top-left
    //  $100, -$200  ; top-right
    // -$C0,  -$1C0  ; bottom-left
    //  $C0,  -$1C0  ; bottom-right
    private static final int[][] CPZ_FRAGMENT_VELOCITIES = {
            {-0x100, -0x200},  // Fragment 0: top-left
            { 0x100, -0x200},  // Fragment 1: top-right
            {-0x0C0, -0x1C0},  // Fragment 2: bottom-left
            { 0x0C0, -0x1C0}   // Fragment 3: bottom-right
    };

    // Fragment velocities from Obj32_VelArray1 (HTZ version, 6 pieces)
    private static final int[][] HTZ_FRAGMENT_VELOCITIES = {
            {-0x200, -0x200},
            { 0x000, -0x280},
            { 0x200, -0x200},
            {-0x1C0, -0x1C0},
            { 0x000, -0x200},
            { 0x1C0, -0x1C0}
    };

    // Mapping frame indices
    private static final int FRAME_INTACT = 0;
    private static final int FRAME_FRAGMENT_BASE = 1;  // Fragments use frames 1-4 (legacy fallback)

    private int halfWidth;
    private boolean broken;
    private boolean solidForRemainingParticipantsThisFrame;
    private boolean initialized;

    public BreakableBlockObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, resolveHalfWidth(), HALF_HEIGHT, 0.6f, 0.6f, 0.8f, false);
        this.halfWidth = resolveHalfWidth();
        this.broken = false;
    }

    @Override
    public BreakableBlockObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BreakableBlockObjectInstance(ctx.spawn(), getName());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Check persistence: if already broken, stay broken
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null && objectManager.isRemembered(spawn)) {
            this.broken = true;
            setDestroyed(true);
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        // ROM Obj32_Main (s2.asm:48889-48905) runs SolidObject and then checks the
        // BLOCK's own standing_mask (status bits 6/7). No special "rolling player
        // moving up" detection is done here: side/below collisions are handled by
        // SolidObject's ceiling/push paths and never break the block. The per-player
        // break decision is made in onSolidContact below, using the SolidContact's
        // standing flag (set by SolidObject when that player is currently standing
        // on this object).
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: width_pixels = $10 (CPZ) / $18 (HTZ)
        // SolidObject routine uses: halfWidth + 11 for x check, halfHeight for y check
        return SolidObjectParams.of(halfWidth + 11, HALF_HEIGHT, HALF_HEIGHT + 1);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj32 calls the ordinary SolidObject helper. Its X gate branches out
        // only on `bhi`, so relX == 2*d1 remains a zero-distance side contact
        // (docs/s2disasm/s2.asm:35347-35354). HTZ1 reaches that exact right
        // edge on the roll-landing frame; retaining the contact publishes the
        // native Status_Push bit for the following SAnim_Push timer gate.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // Block is not solid once broken. ROM keeps the block fully solid before it
        // breaks (Obj32_Main always invokes SolidObject); a rolling player moving
        // upward hits the underside as a ceiling collision, not a break.
        return !broken || solidForRemainingParticipantsThisFrame;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || player == null) {
            return;
        }

        // ROM Obj32_Main snapshots player anim before SolidObject, then checks
        // standing bits after SolidObject (docs/s2disasm/s2.asm:49295-49350).
        int preContactAnimationId = services().objectManager() != null
                ? services().objectManager().getPreContactAnimationId()
                : player.getAnimationId();
        boolean wasRollAnimating = preContactAnimationId == Sonic2AnimationIds.ROLL.id();
        //   andi.b #standing_mask,d0         ; only break if a player is STANDING on the block
        //   bne.s  Obj32_SupportingSomeone
        //   ; ... checks each standing player's saved anim individually:
        //   ; - MainCharacter standing && saved anim==Roll  -> Obj32_BouncePlayer(MainCharacter)
        //   ; - Sidekick standing      && saved anim==Roll  -> Obj32_BouncePlayer(Sidekick)
        // Side and below contacts NEVER break the block in ROM. Each player's own
        // saved animation determines if THIS player triggers the break (the engine's
        // previous player cache leaked Sonic's roll signal into Tails'
        // onSolidContact call, knocking Tails airborne via the side-touch path).
        if (contact.standing() && wasRollAnimating) {
            breakBlock(player, contact);
        }
    }

    private void breakBlock(AbstractPlayableSprite player, SolidContact contact) {
        if (broken) {
            return;
        }

        broken = true;
        // ROM Obj32_Main runs one SolidObject pass for both players before it
        // branches on the block standing bits and destroys itself. The engine
        // receives per-player callbacks; keep the block solid for later
        // participants in this same callback batch so the first breaker does not
        // erase the other player's pre-destroy support one frame early.
        solidForRemainingParticipantsThisFrame = true;

        // Mark as broken in persistence table (stays broken on respawn/revisit)
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);

        short preservedCentreY = player.getCentreY();
        // Force player into rolling state with proper hitbox (disassembly lines 48916-48919)
        // bset #status.player.rolling,status(a1)
        // move.b #$E,y_radius(a1)
        // move.b #7,x_radius(a1)
        // move.b #AniIDSonAni_Roll,anim(a1)
        // setRolling(true) handles radius change and animation internally, but
        // ROM writes y_radius/x_radius/status without changing y_pos.
        player.setRolling(true);
        player.setCentreYPreserveSubpixel(preservedCentreY);

        // Handle velocity based on contact direction:
        // - Standing on top: bounce upward
        // - Hitting from below: continue through (don't change velocity)
        // - Hitting from side: continue through (don't change velocity)
        if (contact.standing()) {
            // Bounce player upward only when breaking from above
            // From disassembly: move.w #-$300,y_vel(a1)
            player.setYSpeed((short) PLAYER_BOUNCE_VELOCITY);
        }
        // When hitting from below or side, player maintains their momentum

        // Set player state to in-air
        // From disassembly: bset #status.player.in_air, bclr #status.player.on_object
        player.setAir(true);

        // Spawn fragment objects
        spawnFragments();

        // Play slow smash sound effect
        services().playSfx(GameSound.SLOW_SMASH);

        // Award 100 points
        services().gameState().addScore(100);

        // Spawn points display popup
        if (objectManager != null) {
            spawnFreeChild(() -> new PointsObjectInstance(
                    new ObjectSpawn(spawn.x(), spawn.y(), 0x29, 0, 0, false, 0),
                    services(), 100));
        }

        // Mark this object as destroyed so it stops rendering/updating
        setDestroyed(true);

        LOGGER.fine(() -> String.format("Breakable block at (%d,%d) broken by player", spawn.x(), spawn.y()));
    }

    private void spawnFragments() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        ObjectSpriteSheet sheet = renderManager.getSheet(Sonic2ObjectArtKeys.BREAKABLE_BLOCK);
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.BREAKABLE_BLOCK);
        if (sheet == null || renderer == null) {
            return;
        }

        List<SpriteMappingPiece> pieces = List.of();
        if (sheet.getFrameCount() > 0) {
            SpriteMappingFrame frame = sheet.getFrame(0);
            if (frame != null && frame.pieces() != null) {
                pieces = frame.pieces();
            }
        }

        if (!pieces.isEmpty()) {
            int[][] velocities = pieces.size() >= HTZ_FRAGMENT_VELOCITIES.length
                    ? HTZ_FRAGMENT_VELOCITIES
                    : CPZ_FRAGMENT_VELOCITIES;
            int count = Math.min(pieces.size(), velocities.length);

            for (int i = 0; i < count; i++) {
                SpriteMappingPiece piece = pieces.get(i);
                int velX = velocities[i][0];
                int velY = velocities[i][1];
                spawnFreeChild(() -> new BreakableBlockFragmentInstance(
                        spawn.x(),
                        spawn.y(),
                        velX,
                        velY,
                        piece,
                        renderer));
            }
            return;
        }

        // Fallback: spawn 4 simple fragments if mappings are missing
        for (int i = 0; i < 4; i++) {
            int velX = CPZ_FRAGMENT_VELOCITIES[i][0];
            int velY = CPZ_FRAGMENT_VELOCITIES[i][1];
            int frameIndex = FRAME_FRAGMENT_BASE + i;

            spawnFreeChild(() -> new BreakableBlockFragmentInstance(
                    spawn.x(), spawn.y(), velX, velY, frameIndex, renderManager));
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (broken) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.BREAKABLE_BLOCK);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }

        renderer.drawFrameIndex(FRAME_INTACT, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    protected int getHalfWidth() {
        return halfWidth;
    }

    @Override
    protected int getHalfHeight() {
        return HALF_HEIGHT;
    }

    private static int resolveHalfWidth() {
        // Use construction context ThreadLocal since this is called during super() args
        var ctx = constructionContext();
        if (ctx == null || ctx.currentLevel() == null) {
            return CPZ_HALF_WIDTH;
        }
        int zoneId = ctx.currentLevel().getZoneIndex();
        return zoneId == Sonic2Constants.ZONE_HTZ
                ? HTZ_HALF_WIDTH
                : CPZ_HALF_WIDTH;
    }

    /**
     * Inner class for the fragment objects that fly apart when the block breaks.
     * These are simple falling objects with initial velocity that despawn when off-screen.
     */
    public static class BreakableBlockFragmentInstance extends AbstractObjectInstance implements RewindRecreatable {

        private static final int GRAVITY = 0x18;  // From disassembly: addi.w #$18,y_vel(a0)

        private int currentX;
        private int currentY;
        private int subX;  // 8.8 fixed point
        private int subY;  // 8.8 fixed point
        private int velX;  // 8.8 fixed point
        private int velY;  // 8.8 fixed point
        private final SpriteMappingPiece piece;
        private final PatternSpriteRenderer renderer;
        private final List<SpriteMappingPiece> pieceList;
        private int frameIndex;
        private final ObjectRenderManager renderManager;

        public BreakableBlockFragmentInstance(int x, int y, int velX, int velY, SpriteMappingPiece piece,
                                              PatternSpriteRenderer renderer) {
            super(new ObjectSpawn(x, y, 0x32, 0, 0, false, 0), "BlockFragment");
            this.currentX = x;
            this.currentY = y;
            this.subX = x << 8;
            this.subY = y << 8;
            this.velX = velX;
            this.velY = velY;
            this.piece = piece;
            this.renderer = renderer;
            this.pieceList = piece != null ? List.of(piece) : List.of();
            this.frameIndex = -1;
            this.renderManager = null;
        }

        public BreakableBlockFragmentInstance(int x, int y, int velX, int velY, int frameIndex,
                                              ObjectRenderManager renderManager) {
            super(new ObjectSpawn(x, y, 0x32, 0, 0, false, 0), "BlockFragment");
            this.currentX = x;
            this.currentY = y;
            this.subX = x << 8;
            this.subY = y << 8;
            this.velX = velX;
            this.velY = velY;
            this.piece = null;
            this.renderer = null;
            this.pieceList = List.of();
            this.frameIndex = frameIndex;
            this.renderManager = renderManager;
        }

        public BreakableBlockFragmentInstance(int x, int y, int velX, int velY) {
            this(x, y, velX, velY, 0, null);
        }

        @Override
        public BreakableBlockFragmentInstance recreateForRewind(RewindRecreateContext ctx) {
            ObjectRenderManager manager = ctx.objectServices() != null
                    ? ctx.objectServices().renderManager()
                    : null;
            return new BreakableBlockFragmentInstance(ctx.spawn().x(), ctx.spawn().y(), 0, 0, 0, manager);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed()) {
                return;
            }

            // Apply gravity
            velY += GRAVITY;

            // Update position (8.8 fixed point)
            subX += velX;
            subY += velY;
            currentX = subX >> 8;
            currentY = subY >> 8;

            // Check if off-screen (destroy if too far below camera)
            int cameraY = services().camera().getY();
            int screenHeight = 224;  // Standard MD screen height
            if (currentY > cameraY + screenHeight + 32) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }

            if (renderer != null) {
                if (!renderer.isReady() || pieceList.isEmpty()) {
                    return;
                }
                renderer.drawPieces(pieceList, currentX, currentY, false, false);
                return;
            }

            if (renderManager != null) {
                PatternSpriteRenderer fallbackRenderer = renderManager.getRenderer(Sonic2ObjectArtKeys.BREAKABLE_BLOCK);
                if (fallbackRenderer == null || !fallbackRenderer.isReady()) {
                    return;
                }
                fallbackRenderer.drawFrameIndex(frameIndex, currentX, currentY, false, false);
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }
    }
}

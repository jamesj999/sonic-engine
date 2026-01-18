package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
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
        implements SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(BreakableBlockObjectInstance.class.getName());

    // From disassembly: move.b #$10,width_pixels(a0) for CPZ version
    private static final int HALF_WIDTH = 0x10;  // 16 pixels
    private static final int HALF_HEIGHT = 0x10; // 16 pixels

    // From disassembly: move.w #-$300,y_vel(a1) for player bounce
    private static final int PLAYER_BOUNCE_VELOCITY = -0x300;

    // Fragment velocities from Obj32_VelArray2 (CPZ version):
    // -$100, -$200  ; top-left
    //  $100, -$200  ; top-right
    // -$C0,  -$1C0  ; bottom-left
    //  $C0,  -$1C0  ; bottom-right
    private static final int[][] FRAGMENT_VELOCITIES = {
            {-0x100, -0x200},  // Fragment 0: top-left
            { 0x100, -0x200},  // Fragment 1: top-right
            {-0x0C0, -0x1C0},  // Fragment 2: bottom-left
            { 0x0C0, -0x1C0}   // Fragment 3: bottom-right
    };

    // Fragment spawn offsets (relative to block center)
    private static final int[][] FRAGMENT_OFFSETS = {
            {-8, -8},   // Fragment 0: top-left
            { 8, -8},   // Fragment 1: top-right
            {-8,  8},   // Fragment 2: bottom-left
            { 8,  8}    // Fragment 3: bottom-right
    };

    // Mapping frame indices
    private static final int FRAME_INTACT = 0;
    private static final int FRAME_FRAGMENT_BASE = 1;  // Fragments use frames 1-4

    private boolean broken;
    private boolean playerWasRolling;

    public BreakableBlockObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, HALF_WIDTH, HALF_HEIGHT, 0.6f, 0.6f, 0.8f, false);
        this.broken = false;

        // Check persistence: if already broken, stay broken
        if (LevelManager.getInstance().getObjectPlacementManager().isRemembered(spawn)) {
            this.broken = true;
            setDestroyed(true);
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (broken) {
            return;
        }

        // Check if player is standing on us and rolling
        // The original tracks player animation state each frame
        if (player != null) {
            playerWasRolling = player.getRolling();
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: width_pixels = $10 (16 pixels half-width)
        // SolidObject routine uses: halfWidth + 11 for x check, halfHeight for y check
        return new SolidObjectParams(HALF_WIDTH + 11, HALF_HEIGHT, HALF_HEIGHT + 1);
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        // Block is not solid once broken
        return !broken;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (broken || player == null) {
            return;
        }

        // Break the block if player is rolling and either:
        // 1. Standing on top of the block
        // 2. Hitting from below (e.g., exiting a spin tube upward)
        // 3. Hitting from the side while rolling
        boolean isRolling = playerWasRolling || player.getRolling();

        if (isRolling && (contact.standing() || contact.touchBottom() || contact.touchSide())) {
            breakBlock(player, contact);
        }
    }

    private void breakBlock(AbstractPlayableSprite player, SolidContact contact) {
        if (broken) {
            return;
        }

        broken = true;

        // Mark as broken in persistence table (stays broken on respawn/revisit)
        LevelManager.getInstance().getObjectPlacementManager().markRemembered(spawn);

        // Force player into rolling state with proper hitbox (disassembly lines 48916-48919)
        // bset #status.player.rolling,status(a1)
        // move.b #$E,y_radius(a1)
        // move.b #7,x_radius(a1)
        // move.b #AniIDSonAni_Roll,anim(a1)
        // setRolling(true) handles radius change and animation internally
        player.setRolling(true);

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
        AudioManager.getInstance().playSfx(GameSound.SLOW_SMASH);

        // Award points (chain bonus system - simplified to flat 10 points)
        uk.co.jamesj999.sonic.game.GameStateManager.getInstance().addScore(10);

        // Mark this object as destroyed so it stops rendering/updating
        setDestroyed(true);

        LOGGER.fine(() -> String.format("Breakable block at (%d,%d) broken by player", spawn.x(), spawn.y()));
    }

    private void spawnFragments() {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return;
        }

        // Spawn 4 fragment objects
        for (int i = 0; i < 4; i++) {
            int fragX = spawn.x() + FRAGMENT_OFFSETS[i][0];
            int fragY = spawn.y() + FRAGMENT_OFFSETS[i][1];
            int velX = FRAGMENT_VELOCITIES[i][0];
            int velY = FRAGMENT_VELOCITIES[i][1];
            int frameIndex = FRAME_FRAGMENT_BASE + i;

            BreakableBlockFragmentInstance fragment = new BreakableBlockFragmentInstance(
                    fragX, fragY, velX, velY, frameIndex, renderManager);
            objectManager.addDynamicObject(fragment);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (broken) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getBreakableBlockRenderer();
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
        return HALF_WIDTH;
    }

    @Override
    protected int getHalfHeight() {
        return HALF_HEIGHT;
    }

    /**
     * Inner class for the fragment objects that fly apart when the block breaks.
     * These are simple falling objects with initial velocity that despawn when off-screen.
     */
    public static class BreakableBlockFragmentInstance extends AbstractObjectInstance {

        private static final int GRAVITY = 0x18;  // From disassembly: addi.w #$18,y_vel(a0)

        private int currentX;
        private int currentY;
        private int subX;  // 8.8 fixed point
        private int subY;  // 8.8 fixed point
        private int velX;  // 8.8 fixed point
        private int velY;  // 8.8 fixed point
        private final int frameIndex;
        private final ObjectRenderManager renderManager;

        public BreakableBlockFragmentInstance(int x, int y, int velX, int velY, int frameIndex,
                                              ObjectRenderManager renderManager) {
            super(new ObjectSpawn(x, y, 0x32, 0, 0, false, 0), "BlockFragment");
            this.currentX = x;
            this.currentY = y;
            this.subX = x << 8;
            this.subY = y << 8;
            this.velX = velX;
            this.velY = velY;
            this.frameIndex = frameIndex;
            this.renderManager = renderManager;
        }

        @Override
        public void update(int frameCounter, AbstractPlayableSprite player) {
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
            int cameraY = uk.co.jamesj999.sonic.camera.Camera.getInstance().getY();
            int screenHeight = 224;  // Standard MD screen height
            if (currentY > cameraY + screenHeight + 32) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || renderManager == null) {
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getBreakableBlockRenderer();
            if (renderer == null || !renderer.isReady()) {
                return;
            }

            renderer.drawFrameIndex(frameIndex, currentX, currentY, false, false);
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }
    }
}

package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sideways Platform (Object 0x7A) - Horizontal moving platform from CPZ and MCZ.
 * <p>
 * This platform moves horizontally back and forth between two boundary points.
 * Some subtypes create linked pairs of platforms that toggle direction when they touch.
 * <p>
 * Based on Obj7A from the Sonic 2 disassembly.
 * <p>
 * Subtype structure (Obj7A_Properties):
 * - Subtype 0x00: Single platform, 1 platform total
 * - Subtype 0x06: Two linked platforms (40px spacing)
 * - Subtype 0x0C: Two linked platforms (80px spacing), starts moving left
 * - Subtype 0x12: Single platform, offset right
 */
public class SidewaysPformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(SidewaysPformObjectInstance.class.getName());

    // Platform collision dimensions (from disassembly)
    private static final int HALF_WIDTH = 0x18;  // 24 pixels
    private static final int HALF_HEIGHT = 8;

    // MCZ-specific mappings (Obj15_Obj7A_MapUnc_10256, shared with SwingingPlatform)
    // In MCZ, Obj7A uses level art with palette 0 instead of CPZ stair block art.
    // Disasm: cmpi.b #mystic_cave_zone,(Current_Zone).w / bne.s +
    //         move.l #Obj15_Obj7A_MapUnc_10256,mappings(a0)
    //         move.w #make_art_tile(ArtTile_ArtKos_LevelArt,0,0),art_tile(a0)
    private static List<SpriteMappingFrame> mczMappings;
    private static boolean mczMappingsLoadAttempted;

    // Subtype properties from Obj7A_Properties
    // Format: {totalChildren-1, originXOffset, parentXOffset, childXOffset}
    // Subtypes map: 0x00 -> index 0, 0x06 -> index 1, 0x0C -> index 2, 0x12 -> index 3
    private static final int[][] SUBTYPE_PROPERTIES = {
            {0, 0x68, -0x68, 0},    // subtype 0x00: Single platform, centered
            {1, 0xA8, -0xB0, 0x40}, // subtype 0x06: Two linked platforms
            {1, 0xE8, -0x80, 0x80}, // subtype 0x0C: Two linked platforms (wide)
            {0, 0x68, 0x67, 0}      // subtype 0x12: Single platform, offset right
    };

    // Position tracking
    private int x;
    private int y;
    private int minX;        // objoff_32 - Left boundary
    private int maxX;        // objoff_34 - Right boundary
    private int direction;   // objoff_36 - 0=moving right, 1=moving left

    // For linked platforms
    private SidewaysPformObjectInstance linkedPlatform;
    private boolean isChild;

    // Zone-specific rendering: MCZ uses level art, CPZ uses dedicated stair block art
    private boolean isMcz;
    private boolean initialized;

    /**
     * Creates a new SidewaysPform instance.
     *
     * @param spawn the spawn data
     * @param name  the object name
     */
    public SidewaysPformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.isChild = false;
    }

    @Override
    public SidewaysPformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SidewaysPformObjectInstance(ctx.spawn(), getName());
    }

    /**
     * Creates a child platform linked to a parent.
     *
     * @param spawn  the spawn data
     * @param name   the object name
     * @param parent the parent platform to link with
     */
    private SidewaysPformObjectInstance(ObjectSpawn spawn, String name, SidewaysPformObjectInstance parent) {
        super(spawn, name);
        this.isChild = true;
        this.isMcz = parent.isMcz;
        this.linkedPlatform = parent;
        parent.linkedPlatform = this;
        initChild(parent);
        initialized = true;
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
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;  // PlatformObject - only solid from top
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(usesStickyContactBuffer());
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Platform state is driven via ObjectManager standing checks.
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        this.isMcz = services().currentLevel() != null && services().romZoneId() == Sonic2Constants.ZONE_MYSTIC_CAVE;
        init();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        applyMovement();
        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isMcz) {
            appendRenderMcz(commands);
        } else {
            appendRenderCpz(commands);
        }
    }

    /**
     * CPZ rendering: uses dedicated CPZ stair block art via PatternSpriteRenderer.
     * art_tile = make_art_tile(ArtTile_ArtNem_CPZStairBlock, 3, 1)
     */
    private void appendRenderCpz(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = null;

        if (renderManager != null) {
            renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.SIDEWAYS_PFORM);
        }

        if (renderer != null && renderer.isReady()) {
            // Frame 0 is the 48x16 platform
            renderer.drawFrameIndex(0, x, y, false, false);
        }
    }

    /**
     * MCZ rendering: uses level art patterns with Obj15 shared mappings.
     * art_tile = make_art_tile(ArtTile_ArtKos_LevelArt, 0, 0)
     * Mapping pieces have palette 3 embedded, art_tile adds 0.
     */
    private void appendRenderMcz(List<GLCommand> commands) {
        ensureMczMappingsLoaded();

        if (mczMappings == null || mczMappings.isEmpty()) {
            return;
        }

        // Frame 0 is the 48x16 platform
        SpriteMappingFrame frame = mczMappings.get(0);
        if (frame == null || frame.pieces().isEmpty()) {
            return;
        }

        GraphicsManager graphicsManager = services().graphicsManager();
        SpritePieceRenderer.renderPieces(
                frame.pieces(),
                x, y,
                0,  // Base pattern index (level art starts at 0)
                0,  // art_tile palette offset: make_art_tile(ArtTile_ArtKos_LevelArt,0,0)
                false, false,
                (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, px, py) -> {
                    int descIndex = patternIndex & 0x7FF;
                    if (pieceHFlip) {
                        descIndex |= 0x800;
                    }
                    if (pieceVFlip) {
                        descIndex |= 0x1000;
                    }
                    descIndex |= (paletteIndex & 0x3) << 13;
                    graphicsManager.renderPattern(new PatternDesc(descIndex), px, py);
                });
    }

    private static void ensureMczMappingsLoaded() {
        if (mczMappingsLoadAttempted) {
            return;
        }
        mczMappingsLoadAttempted = true;
        try {
            RomByteReader reader = RomByteReader.fromRom(staticRomManager().getRom());
            mczMappings = S2SpriteDataLoader.loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ15_MCZ_ADDR);
            LOGGER.fine("Loaded " + mczMappings.size() + " MCZ SidewaysPform mapping frames");
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load MCZ SidewaysPform mappings: " + e.getMessage());
        }
    }

    private void init() {
        // Get subtype properties index
        int propsIndex = getPropertiesIndex(spawn.subtype());
        int[] props = SUBTYPE_PROPERTIES[propsIndex];

        int totalChildren = props[0];      // Number of additional platforms (0 or 1)
        int originXOffset = props[1];      // Movement range (half of total range)
        int parentXOffset = props[2];      // Offset for parent starting position

        // Calculate movement boundaries (relative to spawn position)
        // Assembly: minX = spawn_x - originXOffset, maxX = spawn_x + originXOffset
        minX = spawn.x() - originXOffset;
        maxX = spawn.x() + originXOffset;

        // Initial position: spawn_x + parentXOffset
        x = spawn.x() + parentXOffset;
        y = spawn.y();

        // Direction: subtype 0x0C starts moving left (direction=1), others start moving right
        direction = (spawn.subtype() == 0x0C) ? 1 : 0;

        updateDynamicSpawn(x, y);

        // Create child platform if needed
        if (totalChildren > 0 && !isChild) {
            createChildPlatform(props[3]);  // childXOffset
        }
    }

    private void initChild(SidewaysPformObjectInstance parent) {
        // Get subtype properties index (same as parent)
        int propsIndex = getPropertiesIndex(spawn.subtype());
        int[] props = SUBTYPE_PROPERTIES[propsIndex];

        int childXOffset = props[3];

        // Child shares same movement boundaries as parent
        minX = parent.minX;
        maxX = parent.maxX;

        x = spawn.x();
        y = parent.y;

        // Child always starts with default direction (0 = moving right)
        // Only the parent of subtype 0x0C gets direction=1; child is fresh allocation
        direction = 0;

        updateDynamicSpawn(x, y);
    }

    private void createChildPlatform(int childXOffset) {
        // Create spawn data for child platform
        ObjectSpawn childSpawn = new ObjectSpawn(
                spawn.x() + childXOffset,
                spawn.y(),
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                false,  // Child is not respawn tracked
                spawn.rawYWord()
        );

        ObjectServices svc = services();
        SidewaysPformObjectInstance child = ObjectConstructionContext.construct(
                svc, () -> new SidewaysPformObjectInstance(childSpawn, name + "_child", this));
        ObjectManager objectManager = svc.objectManager();
        if (objectManager == null) {
            return;
        }
        if (ObjectConstructionContext.isRewindActiveRestore()) {
            objectManager.registerRewindReconstructionChild(child);
        } else {
            // ROM Obj7A_SubObjectLoop uses AllocateObjectAfterCurrent, so the
            // child must occupy the next free slot after the preallocated parent.
            // docs/s2disasm/s2.asm:56192-56196
            objectManager.addDynamicObjectAfterSlot(child, getSlotIndex());
        }
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean checksOutOfRangeAfterRoutine() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        if (isChild) {
            return false;
        }
        // Obj7A parent owns pair lifetime from its min/max range endpoints;
        // the child routine only moves/checks collision/displays.
        // docs/s2disasm/s2.asm:56239-56272
        boolean out = obj7aEndpointOutOfRange(minX, cameraX)
                && obj7aEndpointOutOfRange(maxX, cameraX);
        if (out && linkedPlatform != null && linkedPlatform != this) {
            linkedPlatform.setDestroyedByOffscreen();
        }
        return out;
    }

    private static boolean obj7aEndpointOutOfRange(int endpointX, int cameraX) {
        int dist = ((endpointX & 0xFF80) - S2ObjectWindowing.unloadCoarse(cameraX)) & 0xFFFF;
        return dist > S2ObjectWindowing.UNLOAD_COMPARE;
    }

    /**
     * Applies horizontal movement at 1 pixel/frame.
     * Direction toggles at boundaries.
     * Linked platforms toggle when they touch (their collision boxes overlap).
     */
    private void applyMovement() {
        if (direction == 0) {
            // Moving right
            x++;
            if (x >= maxX) {
                x = maxX;
                direction = 1;
            }
        } else {
            // Moving left
            x--;
            if (x <= minX) {
                x = minX;
                direction = 0;
            }
        }

        // Check for linked platform collision (direction toggle)
        // This check is performed by the CHILD, not the parent (assembly: Obj7A_SubObject).
        if (linkedPlatform != null && isChild) {
            int parentRight = linkedPlatform.x + HALF_WIDTH;
            int childLeft = x - HALF_WIDTH;

            // Obj7A tests exact edge equality before toggling both directions.
            // docs/s2disasm/s2.asm:56307-56317
            if (childLeft == parentRight) {
                direction ^= 1;
                linkedPlatform.direction ^= 1;
            }
        }
    }

    /**
     * Gets the properties table index for a subtype.
     */
    private int getPropertiesIndex(int subtype) {
        // Subtypes map to indices: 0x00->0, 0x06->1, 0x0C->2, 0x12->3
        return switch (subtype) {
            case 0x00 -> 0;
            case 0x06 -> 1;
            case 0x0C -> 2;
            case 0x12 -> 3;
            default -> 0;  // Default to first entry
        };
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int left = x - HALF_WIDTH;
        int right = x + HALF_WIDTH;
        int top = y - HALF_HEIGHT;
        int bottom = y + HALF_HEIGHT;

        ctx.drawLine(left, top, right, top, 0.4f, 0.8f, 0.6f);
        ctx.drawLine(right, top, right, bottom, 0.4f, 0.8f, 0.6f);
        ctx.drawLine(right, bottom, left, bottom, 0.4f, 0.8f, 0.6f);
        ctx.drawLine(left, bottom, left, top, 0.4f, 0.8f, 0.6f);

        // Draw center cross
        ctx.drawLine(x - 4, y, x + 4, y, 0.4f, 0.8f, 0.6f);
        ctx.drawLine(x, y - 4, x, y + 4, 0.4f, 0.8f, 0.6f);
    }

}

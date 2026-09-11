package com.openggf.game.sonic1;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.data.compression.NemesisReader;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages water surface sprite rendering for Sonic 1 Labyrinth Zone.
 * <p>
 * From the S1 disassembly (Object 1B - Water Surface, _incObj/1B Water Surface.asm):
 * <ul>
 *   <li>Art: Nem_Water at ROM 0x302E6, Nemesis compressed, 16 tiles (2 sets of 8).</li>
 *   <li>VRAM: ArtTile_LZ_Water_Surface = $300, palette line 2, priority 1.</li>
 *   <li>Sprite mappings (Map_Surf): 3 normal frames, each with 3 sprite pieces
 *       (4 tiles wide x 2 tiles tall = 32x16 pixels) at x offsets -0x60, -0x20, +0x20.</li>
 *   <li>Animation: 3-frame cycle (0→1→2→0), 8 game frames per animation step.</li>
 *   <li>Frame 0: tiles 0, no flip. Frame 1: tiles 8, no flip. Frame 2: tiles 0, h-flipped.</li>
 *   <li>Even/odd frame alternation: object X shifts by 0x20 (32px) on odd frames,
 *       filling the 32px gaps between sprite pieces to create continuous coverage.</li>
 *   <li>Y position: water surface level (v_waterpos1), Y offset -3.</li>
 * </ul>
 */
public class Sonic1WaterSurfaceManager {
    private static final Logger LOGGER = Logger.getLogger(Sonic1WaterSurfaceManager.class.getName());

    /** Y offset for sprite placement, from Map_Surf: spritePiece x, -3, ... */
    private static final int Y_OFFSET = -3;

    /** Palette line 2, from make_art_tile(ArtTile_LZ_Water_Surface, 2, 1). */
    private static final int PALETTE_LINE = 2;

    /** Animation changes every 8 frames (obTimeFrame resets to 7, counts down). */
    private static final int ANIM_FRAME_DELAY = 8;

    /** 3 animation frames: 0, 1, 2, then wraps to 0. */
    private static final int ANIM_FRAME_COUNT = 3;

    /** Surface object origins from Level_ChkWater in sonic.asm. */
    private static final int SURFACE1_ORIG_X = 0x60;
    private static final int SURFACE2_ORIG_X = 0x120;

    private final int zoneId;
    private final int actId;
    private final ObjectSpriteSheet spriteSheet;
    private final PatternSpriteRenderer renderer;
    private boolean initialized;

    public Sonic1WaterSurfaceManager(Rom rom, int zoneId, int actId) throws IOException {
        this.zoneId = zoneId;
        this.actId = actId;

        // Load water surface art from ROM (Nemesis compressed)
        Pattern[] patterns = loadWaterSurfacePatterns(rom);

        // Create mapping frames directly from Map_Surf in the disassembly.
        List<SpriteMappingFrame> frames = createRomMappingFrames();

        this.spriteSheet = new ObjectSpriteSheet(patterns, frames, PALETTE_LINE, ANIM_FRAME_DELAY);
        this.renderer = new PatternSpriteRenderer(spriteSheet);
        this.initialized = false;

        LOGGER.info(String.format("S1 water surface: loaded %d patterns, %d frames",
                patterns.length, frames.size()));
    }

    /**
     * Load Nem_Water (LZ water surface art) from ROM.
     * 292 bytes compressed at 0x302E6, decompresses to 16 tiles (512 bytes).
     */
    private Pattern[] loadWaterSurfacePatterns(Rom rom) throws IOException {
        byte[] compressed = rom.readBytes(Sonic1Constants.ART_NEM_LZ_WATER_SURFACE_ADDR, 8192);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            byte[] decompressed = NemesisReader.decompress(channel);
            int count = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
            Pattern[] patterns = new Pattern[count];
            for (int i = 0; i < count; i++) {
                patterns[i] = new Pattern();
                byte[] sub = Arrays.copyOfRange(decompressed,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                patterns[i].fromSegaFormat(sub);
            }
            return patterns;
        }
    }

    /**
     * Create animation frames matching Map_Surf_internal from the disassembly.
     * <pre>
     * .normal1: spritePiece -$60/-$20/$20, -3, 4, 2, 0, 0, 0, 0, 0
     * .normal2: spritePiece -$60/-$20/$20, -3, 4, 2, 8, 0, 0, 0, 0
     * .normal3: spritePiece -$60/-$20/$20, -3, 4, 2, 0, 1, 0, 0, 0
     * </pre>
     * Palette bits in spritePiece are 0 in ROM; palette line comes from obGfx.
     */
    static List<SpriteMappingFrame> createRomMappingFrames() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        frames.add(createFrame(0, false));
        frames.add(createFrame(8, false));
        frames.add(createFrame(0, true));

        return frames;
    }

    private static SpriteMappingFrame createFrame(int tileIndex, boolean hFlip) {
        // spritePiece palette bits are 0 in Map_Surf; obGfx provides palette line 2.
        int piecePaletteBits = 0;
        return new SpriteMappingFrame(List.of(
                new SpriteMappingPiece(-0x60, Y_OFFSET, 4, 2, tileIndex, hFlip, false, piecePaletteBits, true),
                new SpriteMappingPiece(-0x20, Y_OFFSET, 4, 2, tileIndex, hFlip, false, piecePaletteBits, true),
                new SpriteMappingPiece(0x20, Y_OFFSET, 4, 2, tileIndex, hFlip, false, piecePaletteBits, true)));
    }

    /**
     * Cache patterns in the graphics manager's pattern atlas.
     *
     * @param graphicsManager The graphics manager
     * @param baseIndex       Starting pattern index for caching
     * @return Next available pattern index after caching
     */
    public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
        renderer.ensurePatternsCached(graphicsManager, baseIndex);
        initialized = true;
        LOGGER.fine(String.format("S1 water surface patterns cached at base=%d, count=%d",
                baseIndex, spriteSheet.getPatterns().length));
        return baseIndex + spriteSheet.getPatterns().length;
    }

    /**
     * Render water surface sprites at the water line.
     * <p>
     * Implements S1 Object 1B rendering logic:
     * <ul>
     *   <li>Animation frame cycles 0→1→2→0 every 8 game frames.</li>
     *   <li>Object X follows {@code (v_screenposx & $FFE0) + surf_origX (+$20 on odd frames)}.</li>
     *   <li>Two surface objects are drawn in LZ at origX values $60 and $120.</li>
     * </ul>
     *
     * @param camera       The camera
     * @param frameCounter Current frame number for animation and alternation
     */
    public void render(Camera camera, int frameCounter) {
        if (!initialized || !renderer.isReady()) {
            return;
        }

        WaterSystem waterSystem = GameServices.water();
        int waterLevelY = waterSystem.getVisualWaterLevelY(zoneId, actId);
        if (waterLevelY == 0) {
            return;
        }

        // Only render if water line is visible on screen
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int waterScreenY = waterLevelY - cameraY;
        if (waterScreenY < -16 || waterScreenY > camera.getHeight()) {
            return;
        }

        // Calculate animation frame: 3-frame cycle, changes every 8 frames.
        // From disassembly: obTimeFrame counts down from 7, increments frame at 0,
        // wraps at frame 3 back to 0.
        int animFrame = (frameCounter / ANIM_FRAME_DELAY) % ANIM_FRAME_COUNT;

        int baseX = computeSurfaceBaseX(cameraX, frameCounter);

        renderer.drawFrameIndex(animFrame, baseX + SURFACE1_ORIG_X, waterLevelY, false, false);
        renderer.drawFrameIndex(animFrame, baseX + SURFACE2_ORIG_X, waterLevelY, false, false);
    }

    static int computeSurfaceBaseX(int cameraX, int frameCounter) {
        int baseX = cameraX & 0xFFE0;
        if ((frameCounter & 1) != 0) {
            baseX += 0x20;
        }
        return baseX;
    }

    public boolean isInitialized() {
        return initialized;
    }
}

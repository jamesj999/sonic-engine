package com.openggf.game.sonic3k;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.data.compression.NemesisReader;

import java.io.IOException;
import com.openggf.sprites.managers.ProcessSpritesEpoch;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages water surface sprite rendering for Sonic 3&amp;K levels.
 * <p>
 * From the S3K disassembly (Obj_HCZWaveSplash, sonic3k.asm:43161):
 * <ul>
 *   <li>Art: ArtNem_HCZWaveSplash at ROM 0x38FBB4, Nemesis compressed, 16 tiles</li>
 *   <li>VRAM: ArtTile_HCZWaveSplash = $042E, palette line 0, priority 1</li>
 *   <li>Mappings: Map_HCZWaveSplash — 7 frames. Frames 0-3 have 3 pieces each (4x2 tiles)
 *       at X offsets -0x60, -0x20, +0x20. Frames 4-6 have 6 pieces (wider coverage during pause).</li>
 *   <li>Animation: frames 1→2→3→1, 10 game frames per step (timer resets to 9)</li>
 *   <li>Main sprite origin: (Camera_X &amp; 0xFFE0) + 0x60, shifted +0x20 on odd frames</li>
 *   <li>Child sprite: at main + 0xC0 (same mapping frame), extending coverage rightward</li>
 *   <li>Only active for HCZ (zone 1) — other water zones have no surface sprites</li>
 * </ul>
 */
public class Sonic3kWaterSurfaceManager {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kWaterSurfaceManager.class.getName());

    // From disassembly: main sprite origin X offset from camera-aligned base
    private static final int MAIN_ORIGIN_OFFSET = 0x60;
    // From disassembly: child sprite X offset from main sprite
    private static final int CHILD_OFFSET = 0xC0;

    // Animation: cycle through mapping frames 1-3, wrap at 4 back to 1
    // Timer resets to 9 (10 game frames per animation step)
    private static final int ANIM_FRAME_DELAY = 10;
    private static final int ANIM_FIRST_FRAME = 1;
    private static final int ANIM_FRAME_COUNT = 3; // frames 1, 2, 3

    private final int zoneId;
    private final int actId;
    private final PatternSpriteRenderer renderer;
    private final int patternCount;
    private boolean initialized;

    public Sonic3kWaterSurfaceManager(Rom rom, RomByteReader reader, int zoneId, int actId) throws IOException {
        this.zoneId = zoneId;
        this.actId = actId;

        // Load Nemesis-compressed art (16 tiles)
        Pattern[] patterns = loadWaveSplashPatterns(rom);

        // Parse mappings from ROM
        List<SpriteMappingFrame> rawFrames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_HCZ_WAVE_SPLASH_ADDR);

        // Apply art_tile priority to all pieces.
        // ROM: make_art_tile(ArtTile_HCZWaveSplash,0,1) — palette 0, priority 1.
        // The VDP ORs art_tile with each mapping piece's tile word, propagating the
        // priority bit. The raw mapping pieces have priority=false in their tile data
        // ($0000, $0008, $0800), so we must apply priority=true post-load.
        List<SpriteMappingFrame> frames = applyArtTilePriority(rawFrames);

        this.patternCount = patterns.length;
        this.renderer = new PatternSpriteRenderer(new ObjectSpriteSheet(patterns, frames, 0, 1));
        this.initialized = false;

        LOGGER.info(String.format("S3K water surface: loaded %d patterns, %d frames",
                patterns.length, frames.size()));
    }

    /**
     * Apply the art_tile priority flag to all mapping pieces.
     * On the VDP, the object's art_tile word is OR'd with each piece's tile word,
     * so priority (bit 15) from art_tile propagates to every piece. The raw ROM
     * mapping data stores piece tile words without the art_tile contribution, so
     * we must reconstruct the OR here.
     */
    private static List<SpriteMappingFrame> applyArtTilePriority(List<SpriteMappingFrame> frames) {
        List<SpriteMappingFrame> result = new ArrayList<>(frames.size());
        for (SpriteMappingFrame frame : frames) {
            List<SpriteMappingPiece> pieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece p : frame.pieces()) {
                pieces.add(new SpriteMappingPiece(
                        p.xOffset(), p.yOffset(), p.widthTiles(), p.heightTiles(),
                        p.tileIndex(), p.hFlip(), p.vFlip(), p.paletteIndex(), true));
            }
            result.add(new SpriteMappingFrame(pieces));
        }
        return result;
    }

    private Pattern[] loadWaveSplashPatterns(Rom rom) throws IOException {
        byte[] data;
        FileChannel channel = rom.getFileChannel();
        synchronized (rom) {
            channel.position(Sonic3kConstants.ART_NEM_HCZ_WAVE_SPLASH_ADDR);
            data = NemesisReader.decompress(channel);
        }
        int count = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
            patterns[i].fromSegaFormat(Arrays.copyOfRange(data,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM));
        }
        return patterns;
    }

    /**
     * Cache patterns in the graphics manager's pattern atlas.
     *
     * @param graphicsManager The graphics manager
     * @param baseIndex       Starting virtual pattern index for caching
     * @return Next available pattern index after caching
     */
    public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
        renderer.ensurePatternsCached(graphicsManager, baseIndex);
        initialized = true;
        return baseIndex + patternCount;
    }

    /**
     * Render water surface sprites at the water line.
     * <p>
     * Implements Obj_HCZWaveSplash rendering logic:
     * <ul>
     *   <li>Base X = (Camera_X &amp; 0xFFE0) + 0x60, shifted +0x20 on odd frames</li>
     *   <li>Two draw calls: main at base, child at base + 0xC0</li>
     *   <li>Animation cycles frames 1-3, 10 game frames per step</li>
     * </ul>
     *
     * @param camera       The camera
     * @param frameCounter Current frame number for animation and alternation
     */
    public void render(Camera camera, int frameCounter) {
        if (!initialized || !renderer.isReady()) {
            return;
        }

        // Use visual water level (matches ROM: move.w (Water_level).w,d1).
        // For HCZ there is no oscillation so visual == gameplay, but this is
        // the semantically correct source for rendering.
        WaterSystem waterSystem = GameServices.water();
        int waterLevelY = waterSystem.getVisualWaterLevelY(zoneId, actId);
        if (waterLevelY == 0) {
            return;
        }

        // Only render if water line is visible on screen
        int cameraY = camera.getY();
        int waterScreenY = waterLevelY - cameraY;
        if (waterScreenY < -16 || waterScreenY > camera.getHeight()) {
            return;
        }

        // Calculate animation frame: cycle 1→2→3→1, 10 frames per step
        // From disassembly: mapping_frame starts at 0 (init), cycles 1-3 in loc_1F2A6
        int animStep = (frameCounter / ANIM_FRAME_DELAY) % ANIM_FRAME_COUNT;
        int mappingFrame = ANIM_FIRST_FRAME + animStep;

        // Calculate base X position (from disassembly loc_1F244):
        // move.w (Camera_X_pos).w,d1
        // andi.w #$FFE0,d1        ; align to 32px boundary
        // addi.w #$60,d1          ; offset
        // btst #0,(Level_frame_counter+1).w  ; even/odd
        // addi.w #$20,d1          ; +0x20 on odd frames
        int cameraX = camera.getX();
        int baseX = (cameraX & 0xFFE0) + MAIN_ORIGIN_OFFSET;
        if ((frameCounter & 1) != 0) {
            baseX += 0x20;
        }

        // Draw main sprite at baseX
        renderer.drawFrameIndex(mappingFrame, baseX, waterLevelY, false, false);
        // Draw child sprite at baseX + 0xC0
        renderer.drawFrameIndex(mappingFrame, baseX + CHILD_OFFSET, waterLevelY, false, false);
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Visits the native fixed wave-splash slot during the setup pass. Rendering
     * derives its mapping directly from the immutable level epoch, so epoch zero
     * intentionally requires no additional mutable cursor.
     */
    void processInitialSstSlot(ProcessSpritesEpoch epoch) {
        if (!initialized) {
            throw new IllegalStateException("wave splash fixed slot is not initialized");
        }
        if (epoch.advanceGameplayCounter()) {
            throw new IllegalArgumentException("initial wave splash dispatch cannot count a frame");
        }
    }
}

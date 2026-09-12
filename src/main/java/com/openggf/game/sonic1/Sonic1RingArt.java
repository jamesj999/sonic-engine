package com.openggf.game.sonic1;

import com.openggf.data.Rom;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.Pattern;
import com.openggf.level.rings.RingFrame;
import com.openggf.level.rings.RingMappingFrames;
import com.openggf.data.RomByteReader;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.util.List;

/**
 * Loads ring art and mapping frames for Sonic 1.
 *
 * <p>Sonic 1 ring mappings from {@code _maps/Rings (REV01).asm}:
 * <ul>
 *   <li>Frames 0-3: spin animation (front, angled, edge, angled-mirrored)</li>
 *   <li>Frames 4-7: sparkle animation (collection effect)</li>
 * </ul>
 *
 * <p>Art is Nemesis-compressed at {@code Nem_Ring} (0x39A0E).
 * Palette index 1 (same as Sonic 2).
 */
public class Sonic1RingArt {

    private static final int RING_PALETTE_INDEX = 1;
    /** SynchroAnimate Sync2 timer: {@code move.b #8-1,(v_ani1_time).w} → 8 VBlanks/frame. */
    private static final int RING_FRAME_DELAY = 8;
    /**
     * Ani_Ring sparkle delay byte = 5 → AnimateSprite displays each frame for 6 VBlanks
     * (timer counts 5→4→3→2→1→0→wrap). Different from the spin rate because sparkle
     * uses per-object AnimateSprite, not the global SynchroAnimate.
     */
    private static final int SPARKLE_FRAME_DELAY = 6;
    private static final int SPIN_FRAME_COUNT = 4;
    private static final int SPARKLE_FRAME_COUNT = 4;

    private final Rom rom;
    private RingSpriteSheet cached;

    public Sonic1RingArt(Rom rom) {
        this.rom = rom;
    }

    public RingSpriteSheet load() throws IOException {
        if (cached != null) {
            return cached;
        }

        Pattern[] patterns = loadRingPatterns();
        List<RingFrame> frames = RingMappingFrames.fromMappings(
                S1SpriteDataLoader.loadMappingFrames(RomByteReader.fromRom(rom),
                        Sonic1Constants.MAP_RING_ADDR));

        cached = new RingSpriteSheet(patterns, frames, RING_PALETTE_INDEX, RING_FRAME_DELAY,
                SPARKLE_FRAME_DELAY, SPIN_FRAME_COUNT, SPARKLE_FRAME_COUNT);
        return cached;
    }

    private Pattern[] loadRingPatterns() throws IOException {
        return PatternDecompressor.nemesis(rom, Sonic1Constants.ART_NEM_RING_ADDR);
    }

}

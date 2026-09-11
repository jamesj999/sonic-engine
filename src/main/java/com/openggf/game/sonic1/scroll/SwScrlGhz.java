package com.openggf.game.sonic1.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

import java.util.logging.Logger;

import static com.openggf.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of REV01 Deform_GHZ (Green Hill Zone scroll routine).
 * Reference: s1disasm/_inc/DeformLayers (JP1).asm - Deform_GHZ
 *
 * GHZ line layout is fixed and always totals 224 lines:
 * <ul>
 *   <li>Upper cloud band: 32-d4 lines (variable with camera Y)</li>
 *   <li>Middle cloud band: 16 lines</li>
 *   <li>Lower cloud band: 16 lines</li>
 *   <li>Distant mountains: 48 lines</li>
 *   <li>Hills/waterfalls: 40 lines</li>
 *   <li>Water deformation: 72+d4 lines (variable with camera Y)</li>
 * </ul>
 *
 * REV01 GHZ uses bg3screenposx (96/256 speed) and bg2screenposx (128/256 speed),
 * plus three auto-scroll cloud counters.
 */
public class SwScrlGhz extends AbstractZoneScrollHandler {

    private static final Logger LOG = Logger.getLogger(SwScrlGhz.class.getName());

    // Persistent BG camera positions (16.16 fixed point, matching original RAM)
    // v_bg2screenposx: accumulated at scrshiftx * 128
    protected long bg2XPos;
    // v_bg3screenposx: accumulated at scrshiftx * 96
    protected long bg3XPos;

    // GHZ cloud auto-scroll accumulators (16.16 fixed point).
    // High word is added to BG3 X to create layered cloud drift at idle.
    //
    // These are pure per-frame accumulators in the ROM (Deform_GHZ adds a fixed
    // increment every frame), so they are re-derived from the frame counter each
    // update() rather than free-accumulated. Free-accumulating on the update
    // call count breaks rewind: rewind restores an earlier frameCounter and
    // recomputes parallax by calling update() again, but a call-count accumulator
    // keeps climbing, leaving the clouds drifting between keyframes and only
    // snapping back at keyframe boundaries. Deriving from the (rewind-restored)
    // frameCounter makes update() idempotent per frame, so the clouds rewind
    // exactly like the camera-linear mountain/hill bands already do.
    protected int cloudLayer1Counter;
    protected int cloudLayer2Counter;
    protected int cloudLayer3Counter;

    // Frame the cloud counters are anchored to. Captured on the first update()
    // after init() so that (frameCounter - cloudBaseFrame) counts frames since
    // the counters were last zeroed — matching the ROM's clear-at-zone-init +
    // add-every-frame behaviour regardless of the frameCounter's absolute value.
    protected int cloudBaseFrame;
    protected boolean cloudBaseFrameSet = false;

    protected int lastCameraX;
    protected boolean initialized = false;
    private boolean firstFrameLogged = false;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    /**
     * Initialize BG camera positions.
     * BgScroll_GHZ (JP1) clears bgscreenposx, bgscreenposy, and cloud counters,
     * but bg2screenposx and bg3screenposx retain their values from BgScrollSpeed
     * (= cameraX).
     */
    public void init(int cameraX) {
        // BgScrollSpeed sets bg2screenposx = bg3screenposx = cameraX.
        // BgScroll_GHZ does NOT clear these, only bgscreenposx.
        bg2XPos = (long) cameraX << 16;
        bg3XPos = (long) cameraX << 16;
        cloudLayer1Counter = 0;
        cloudLayer2Counter = 0;
        cloudLayer3Counter = 0;
        cloudBaseFrameSet = false;
        lastCameraX = cameraX;
        initialized = true;
        LOG.info("SwScrlGhz.init: cameraX=" + cameraX +
                " bg2XPos=" + (bg2XPos >> 16) + " bg3XPos=" + (bg3XPos >> 16));
    }

    @Override
    public void update(int[] horizScrollBuf,
            int cameraX,
            int cameraY,
            int frameCounter,
            int actId) {
        if (!initialized) {
            init(cameraX);
        }

        resetScrollTracking();
        composer.reset();

        // Compute camera movement delta (equivalent to v_scrshiftx)
        int deltaX = cameraX - lastCameraX;
        lastCameraX = cameraX;

        // BGScroll_Block3: bg3screenposx += scrshiftx * 96
        // scrshiftx = deltaX << 8 (8.8 fixed point)
        // d4 = scrshiftx * 96 = deltaX * 96 * 256
        bg3XPos += (long) deltaX * 24576;

        // BGScroll_Block2: bg2screenposx += scrshiftx * 128
        // d0 = scrshiftx << 7 = deltaX * 128 * 256 = deltaX * 32768
        bg2XPos += (long) deltaX * 32768;

        // GHZ auto-scroll clouds (JP1 Deform_GHZ behavior):
        // +0x10000/frame, +0xC000/frame, +0x8000/frame since zone init.
        // Derived from the frame counter (not accumulated on the update call
        // count) so the offsets are a deterministic, rewind-safe function of
        // the current frame. See the field docs above.
        if (!cloudBaseFrameSet) {
            // Anchor so the first update() after init reproduces one frame of
            // drift, exactly as a single "+= increment" would have.
            cloudBaseFrame = frameCounter - 1;
            cloudBaseFrameSet = true;
        }
        int cloudFrames = frameCounter - cloudBaseFrame;
        // int multiply wraps mod 2^32 identically to repeated addition, so the
        // 16.16 sub-pixel accumulation matches the original behaviour bit-for-bit.
        cloudLayer1Counter = cloudFrames * 0x00010000;
        cloudLayer2Counter = cloudFrames * 0x0000C000;
        cloudLayer3Counter = cloudFrames * 0x00008000;

        // Extract integer pixel positions (high word of 16.16)
        int bg3X = (int) (bg3XPos >> 16);
        int bg2X = (int) (bg2XPos >> 16);

        // d4 = max(0, 0x20 - ((screenposy & 0x7FF) >> 5))
        int d4 = 0x20 - ((cameraY & 0x7FF) >> 5);
        if (d4 < 0) {
            d4 = 0;
        }
        composer.setVscrollFactorBG((short) d4);

        // FG scroll = -screenposx (constant for all lines)
        short fgScroll = negWord(cameraX);

        int lineIndex = 0;

        // ==================== Cloud + mountain bands ====================
        short cloud1Offset = (short) (cloudLayer1Counter >> 16);
        short cloud2Offset = (short) (cloudLayer2Counter >> 16);
        short cloud3Offset = (short) (cloudLayer3Counter >> 16);

        // Upper clouds: 32-d4 lines
        lineIndex = fillBand(lineIndex, Math.max(0, 0x20 - d4), fgScroll,
                negWord(bg3X + cloud1Offset));
        // Middle clouds: 16 lines
        lineIndex = fillBand(lineIndex, 16, fgScroll, negWord(bg3X + cloud2Offset));
        // Lower clouds: 16 lines
        lineIndex = fillBand(lineIndex, 16, fgScroll, negWord(bg3X + cloud3Offset));
        // Mountains: 48 lines
        lineIndex = fillBand(lineIndex, 48, fgScroll, negWord(bg3X));

        // ==================== Section 2: BG2 (distant hills) ====================
        // 40 lines (0x28) with BG = -bg2X
        lineIndex = fillBand(lineIndex, 40, fgScroll, negWord(bg2X));

        // ==================== Section 3: Perspective interpolation ====================
        // (0x48 + d4) lines, interpolating from bg2X to cameraX
        // over 0x68 (104) conceptual divisions.
        //
        // From disassembly (REV01):
        //   d2 = screenposx - bg2screenposx (distance to interpolate)
        //   increment = (d2 << 8) / 0x68 << 8 (16.16 fixed point per line)
        //   start = bg2screenposx (16.16)
        int section3Count = 0x48 + d4;
        if (section3Count > 0 && lineIndex < VISIBLE_LINES) {
            // Start value: bg2screenposx (negated for scroll direction)
            int startVal = bg2X;
            // End value: screenposx (REV01)
            int endVal = cameraX;

            // Calculate per-line increment in 16.16 fixed point
            int diff = endVal - startVal;
            long increment16 = 0;
            if (diff != 0) {
                // Matching disasm: (diff << 8) / 0x68, then << 8
                // Combined: (diff << 16) / 0x68
                increment16 = ((long) diff << 16) / 0x68;
            }

            // Start in 16.16 fixed point
            long currentVal = (long) startVal << 16;

            int limit = Math.min(VISIBLE_LINES, lineIndex + section3Count);
            for (; lineIndex < limit; lineIndex++) {
                int bgPixel = (int) (currentVal >> 16);
                short bgScroll = negWord(bgPixel);

                composer.writePackedScrollWord(lineIndex, fgScroll, bgScroll);

                currentVal += increment16;
            }
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();

        // One-time diagnostic dump to verify scroll state against ROM
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            int bg3XInt = (int) (bg3XPos >> 16);
            int bg2XInt = (int) (bg2XPos >> 16);
            LOG.info("SwScrlGhz first frame: cameraX=" + cameraX + " cameraY=" + cameraY +
                    " bg3X=" + bg3XInt + " bg2X=" + bg2XInt +
                    " d4(vscrollFactorBG)=" + vscrollFactorBG +
                    " bands: upperClouds=" + Math.max(0, 0x20 - d4) +
                    " midClouds=16 lowClouds=16 mountains=48 hills=40 water=" + (0x48 + d4));
            // Sample HScroll at key scanlines
            LOG.info("SwScrlGhz HScroll samples: " +
                    "line0=BG:" + (short)(horizScrollBuf[0] & 0xFFFF) +
                    " line80=BG:" + (short)(horizScrollBuf[Math.min(80, 223)] & 0xFFFF) +
                    " line120=BG:" + (short)(horizScrollBuf[Math.min(120, 223)] & 0xFFFF) +
                    " line200=BG:" + (short)(horizScrollBuf[Math.min(200, 223)] & 0xFFFF));
        }
    }

    private int fillBand(int lineIndex, int lineCount, short fgScroll, short bgScroll) {
        if (lineCount <= 0 || lineIndex >= VISIBLE_LINES) {
            return lineIndex;
        }
        int writeCount = Math.min(lineCount, VISIBLE_LINES - lineIndex);
        composer.fillPackedScrollWords(lineIndex, writeCount, fgScroll, bgScroll);
        return lineIndex + writeCount;
    }

    /**
     * Return the BG camera X for nametable tracking.
     * <p>
     * GHZ's BG map is 32 blocks (8192px) wide — far wider than the VDP's 512px
     * nametable. On the real hardware, BGScroll_Block3 sets redraw flags whenever
     * bg3screenposx crosses a 16-pixel boundary, causing the BG draw routines to
     * update the nametable ring buffer with new columns. The mountains are the
     * slowest-scrolling BG band (96/256 of camera speed), so bg3X is the
     * base position the nametable tracks.
     * <p>
     * Returning bg3X here lets LevelManager dynamically rebuild the tilemap FBO
     * around the current mountain scroll position, matching hardware behavior.
     */
    @Override
    public int getBgCameraX() {
        return (int) (bg3XPos >> 16);
    }

    /**
     * GHZ needs a wider BG period to cover the parallax spread.
     * <p>
     * The visible BG range spans from bg3X (mountains, 96/256 of camera speed)
     * to cameraX + 320 (water band, right screen edge). Additionally, the three
     * cloud auto-scroll layers add ever-growing offsets on top of bg3X, pushing
     * the visible cloud band further from the tilemap origin.
     * <p>
     * On the real VDP hardware, the 512px nametable is a ring buffer that's
     * continuously updated column-by-column as bg3X changes. Adjacent nametable
     * columns always contain adjacent BG map columns, so the 512px wrap seam
     * sits between adjacent blocks that tile seamlessly.
     * <p>
     * The engine rebuilds the tilemap from scratch as a static snapshot, so a
     * 512px wrap produces a seam between non-adjacent BG map blocks (2 blocks
     * apart). GHZ uses 7 unique block types in a non-repeating 32-block
     * sequence, and non-adjacent blocks have visibly different cloud art.
     * Including cloud offsets in the spread ensures the tilemap is always wide
     * enough that the wrap seam stays off-screen.
     */
    @Override
    public int getBgPeriodWidth() {
        int bg3X = (int) (bg3XPos >> 16);
        // Camera spread: water band interpolates up to cameraX + 320
        int cameraSpread = lastCameraX + 320 - bg3X;

        // Cloud bands scroll at bg3X + cloudOffset. The fastest layer (cloud1)
        // advances 1 pixel/frame. Include the maximum absolute cloud offset so
        // the tilemap covers the full visible cloud range without a mid-screen
        // wrap seam.
        int c1 = Math.abs((short) (cloudLayer1Counter >> 16));
        int c2 = Math.abs((short) (cloudLayer2Counter >> 16));
        int c3 = Math.abs((short) (cloudLayer3Counter >> 16));
        int cloudSpread = Math.max(c1, Math.max(c2, c3)) + 320;

        int spread = Math.max(cameraSpread, cloudSpread);

        // Round up to next power of 2, minimum 512
        int width = 512;
        while (width < spread) {
            width <<= 1;
        }
        // Cap at full BG map extent (32 blocks × 256px). Beyond this, the seam
        // is at the BG map's natural wrap point — the same boundary the VDP
        // encounters when the nametable cycles through the full map.
        return Math.min(width, 8192);
    }
}

package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.PlayableEntity;
import com.openggf.game.OscillationManager;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x6E - Platform moving in a circle (like at the start of MTZ3).
 * <p>
 * Large rotating platforms in Metropolis Zone that follow circular paths driven by
 * global oscillation data. The position is computed from two oscillators (offsets
 * $20 and $24) which produce sine and cosine components.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 54450-54594 (Obj6E code)
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bits 4-7: Size/frame selection (subtype >> 3) & 0xE gives index into property table</li>
 *   <li>Bit 0: Mirror motion (negate both offsets)</li>
 *   <li>Bit 1: Rotate 90 degrees (negate d1, swap d1/d2)</li>
 * </ul>
 * <p>
 * <b>Subtypes:</b>
 * <ul>
 *   <li>0x00: Edge piece (16x12 collision, frame 0) - solid, full amplitude orbit</li>
 *   <li>0x10: Connector (40x8 collision, frame 1) - solid, full amplitude orbit</li>
 *   <li>0x20: Central platform (96x24 collision, frame 2) - solid, full amplitude orbit</li>
 *   <li>0x30: Indent decoration (12x12, frame 3) - non-solid, half amplitude orbit, uses MtzWheelIndent art</li>
 * </ul>
 */
public class LargeRotPformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(LargeRotPformObjectInstance.class.getName());

    // Property table from byte_283C0: {width_pixels, y_radius}
    // Indexed by (subtype >> 3) & 0xE, then /2 to get 0-3
    private static final int[][] PROPERTIES = {
            {0x10, 0x0C},  // Index 0: edge piece (16px wide, 12px tall)
            {0x28, 0x08},  // Index 1: connector (40px wide, 8px tall)
            {0x60, 0x18},  // Index 2: central platform (96px wide, 24px tall)
            {0x0C, 0x0C},  // Index 3: indent decoration (12px wide, 12px tall)
    };

    // Oscillation offsets used by this object
    // Oscillating_Data+$20 = oscillator 8 value high byte
    // Oscillating_Data+$24 = oscillator 9 value high byte
    private static final int OSC_OFFSET_X = 0x20;
    private static final int OSC_OFFSET_Y = 0x24;

    // Center value subtracted from oscillation for subtypes 0-2 (full amplitude)
    // From disassembly: subi.b #$38,d1
    private static final int OSC_CENTER_FULL = 0x38;

    // Center value subtracted from oscillation for subtype 3 (half amplitude)
    // From disassembly: lsr.b #1,d1 then subi.b #$1C,d1
    private static final int OSC_CENTER_HALF = 0x1C;

    // Static mapping data loaded from ROM
    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

    // Instance state
    private int baseX;       // objoff_34 - original X position
    private int baseY;       // objoff_30 - original Y position
    private int widthPixels;
    private int yRadius;
    private int mappingFrame;
    private boolean isIndent; // Subtype 3 (routine 4) - no solid collision, half amplitude
    private boolean mirrorMotion;  // Bit 0 of subtype
    private boolean rotateMotion;  // Bit 1 of subtype
    private int priority;

    // Current position (updated each frame from oscillation)
    private int x;
    private int y;

    // Collision params (null for indent subtype)
    private final SolidObjectParams solidParams;

    public LargeRotPformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.baseY = spawn.y();

        // Property index from subtype: (subtype >> 3) & 0xE gives byte offset, /2 for array index
        // From disassembly: lsr.w #3,d0; andi.w #$E,d0
        int subtype = spawn.subtype();
        int propsIndex = (subtype >> 4) & 0x07;
        if (propsIndex >= PROPERTIES.length) {
            propsIndex = 0;
        }

        this.widthPixels = PROPERTIES[propsIndex][0];
        this.yRadius = PROPERTIES[propsIndex][1];
        // mapping_frame = propsIndex (from disassembly: lsr.w #1,d0; move.b d0,mapping_frame)
        this.mappingFrame = propsIndex;
        this.isIndent = (propsIndex == 3);

        // Motion modifier bits
        this.mirrorMotion = (subtype & 0x01) != 0;
        this.rotateMotion = (subtype & 0x02) != 0;

        // Priority: 4 for normal, 5 for indent (from disassembly)
        this.priority = isIndent ? 5 : 4;

        // Solid collision params for non-indent subtypes
        // From disassembly: d1 = width_pixels + $0B, d2 = y_radius, d3 = y_radius + 1
        if (!isIndent) {
            int halfWidth = widthPixels + 0x0B;
            solidParams = SolidObjectParams.of(halfWidth, yRadius, yRadius + 1);
        } else {
            solidParams = null;
        }

        // Calculate initial position
        updatePosition();
    }

    @Override
    public LargeRotPformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new LargeRotPformObjectInstance(ctx.spawn(), getName());
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
    public int getOutOfRangeReferenceX() {
        // Obj6E loc_28466/loc_284EA checks objoff_34, not the moving x_pos(a0),
        // before DeleteObject (docs/s2disasm/s2.asm:54526-54543,54572-54589).
        return baseX;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        updatePosition();
        // ROM Obj6E (loc_28466/loc_284EA, s2.asm) marks the object gone via
        // objoff_34; getOutOfRangeReferenceX exposes that anchor to the shared
        // ObjectManager out_of_range path.
    }

    /**
     * Updates position from global oscillation data.
     * <p>
     * Subtypes 0-2 (routine 2, loc_28432): Full amplitude orbit.
     * <pre>
     *   d1 = (Oscillating_Data+$20) - $38  (signed byte)
     *   d2 = (Oscillating_Data+$24) - $38  (signed byte)
     * </pre>
     * <p>
     * Subtype 3 (routine 4, loc_284BC): Half amplitude orbit.
     * <pre>
     *   d1 = ((Oscillating_Data+$20) >> 1) - $1C  (signed byte)
     *   d2 = ((Oscillating_Data+$24) >> 1) - $1C  (signed byte)
     * </pre>
     * <p>
     * Then motion modifiers from subtype bits:
     * <ul>
     *   <li>Bit 0: negate both d1 and d2</li>
     *   <li>Bit 1: negate d1 and swap d1/d2</li>
     * </ul>
     */
    private void updatePosition() {
        int d1, d2;

        if (!isIndent) {
            // Full amplitude: raw oscillator value - center
            d1 = OscillationManager.getByte(OSC_OFFSET_X) - OSC_CENTER_FULL;
            d2 = OscillationManager.getByte(OSC_OFFSET_Y) - OSC_CENTER_FULL;
        } else {
            // Half amplitude: (value >> 1) - half center
            d1 = (OscillationManager.getByte(OSC_OFFSET_X) >> 1) - OSC_CENTER_HALF;
            d2 = (OscillationManager.getByte(OSC_OFFSET_Y) >> 1) - OSC_CENTER_HALF;
        }

        // Sign extend from byte to word (values are treated as signed bytes)
        d1 = (byte) d1;
        d2 = (byte) d2;

        // Subtype bit 0: mirror motion (negate both offsets)
        if (mirrorMotion) {
            d1 = -d1;
            d2 = -d2;
        }

        // Subtype bit 1: rotate 90 degrees (negate d1 and swap)
        if (rotateMotion) {
            d1 = -d1;
            int temp = d1;
            d1 = d2;
            d2 = temp;
        }

        x = baseX + d1;
        y = baseY + d2;

        if (!isIndent) {
            updateDynamicSpawn(x, y);
        }
    }

    // SolidObjectProvider implementation

    @Override
    public SolidObjectParams getSolidParams() {
        return solidParams;
    }

    @Override
    public boolean isTopSolidOnly() {
        // Obj6E uses SolidObject (JmpTo15_SolidObject), not PlatformObject,
        // so it's fully solid from all sides.
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed() && !isIndent;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special handling needed
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isIndent) {
            renderIndent(commands);
        } else {
            renderLevelArt(commands);
        }
    }

    /**
     * Renders subtypes 0-2 using level art tiles.
     * These frames reference patterns from the zone's level art (ArtTile_ArtKos_LevelArt = tile 0).
     */
    private void renderLevelArt(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ6E_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj6E");
        if (mappings.isEmpty() || mappingFrame >= mappings.size()) {
            return;
        }

        SpriteMappingFrame frame = mappings.get(mappingFrame);
        if (frame == null || frame.pieces().isEmpty()) {
            return;
        }

        GraphicsManager graphicsManager = services().graphicsManager();

        SpritePieceRenderer.renderPieces(
                frame.pieces(),
                x, y,
                0,  // Base pattern index (level art starts at tile 0)
                3,  // Palette line 3: make_art_tile(ArtTile_ArtKos_LevelArt,3,0) (s2.asm:54482).
                    // Obj6E pieces declare palette 0; the ROM art_tile forces line 3.
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

    /**
     * Renders subtype 3 (indent) using MtzWheelIndent Nemesis art.
     * Uses art_tile = make_art_tile(ArtTile_ArtNem_MtzWheelIndent, 3, 0) = tile 0x3F0, palette 3.
     */
    private void renderIndent(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_WHEEL_INDENT);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(0, x, y, false, false);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(priority);
    }

    // Debug rendering

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (!isIndent && solidParams != null) {
            int left = x - solidParams.halfWidth();
            int right = x + solidParams.halfWidth();
            int top = y - solidParams.airHalfHeight();
            int bottom = y + solidParams.groundHalfHeight();

            // Green box for collision bounds
            ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
            ctx.drawLine(right, top, right, bottom, 0.3f, 0.7f, 0.3f);
            ctx.drawLine(right, bottom, left, bottom, 0.3f, 0.7f, 0.3f);
            ctx.drawLine(left, bottom, left, top, 0.3f, 0.7f, 0.3f);
        }

        // Center cross (yellow for base position, red for current)
        ctx.drawLine(baseX - 3, baseY, baseX + 3, baseY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(baseX, baseY - 3, baseX, baseY + 3, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(x - 2, y, x + 2, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 2, x, y + 2, 1.0f, 0.0f, 0.0f);
    }

}

package com.openggf.graphics;

import com.openggf.util.ShortIndexedView;
import com.openggf.util.IntIndexedView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.logging.Logger;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.GL_R32F;

/**
 * GPU renderer that draws a tilemap texture into the current framebuffer.
 */
@com.openggf.game.ModApi
public class TilemapGpuRenderer {
    private static final Logger LOGGER = Logger.getLogger(TilemapGpuRenderer.class.getName());

    @com.openggf.game.ModApi
    public enum Layer {
        BACKGROUND,
        FOREGROUND
    }

    private TilemapShaderProgram shader;
    private final TilemapTexture backgroundTexture = new TilemapTexture();
    private final TilemapTexture foregroundTexture = new TilemapTexture();
    private final TilemapTexture foregroundWindowTexture = new TilemapTexture();
    private ForegroundWindow foregroundWindow;
    private int[] uploadedWindowDescriptors;
    private final int[] savedWindowScissor = new int[4];

    public void setForegroundWindow(ForegroundWindow window) {
        foregroundWindow = window;
    }
    private final PatternLookupBuffer patternLookup = new PatternLookupBuffer();
    private final HScrollBuffer foregroundLineScrollBuffer = new HScrollBuffer(true);
    private VScrollBuffer columnVScrollBuffer;
    private final QuadRenderer quadRenderer = new QuadRenderer();

    /**
     * Construct with the configured viewport width.
     * Column count scales as ceil(screenWidth/16): 20 at native 320px.
     * The caller (GraphicsManager) reads the configured width and injects it here
     * so this low-level class stays free of GameServices / SonicConfiguration.
     *
     * @param screenWidth viewport width in pixels (e.g. 320 for native)
     */
    public TilemapGpuRenderer(int screenWidth) {
        columnVScrollBuffer = new VScrollBuffer(columnCount(screenWidth));
    }

    /**
     * No-arg constructor for tests or callers without access to config.
     * Defaults to native 320px (20 vscroll columns).
     */
    public TilemapGpuRenderer() {
        this(320);
    }

    private static int columnCount(int width) {
        return Math.max(1, (Math.max(1, width) + 15) / 16);
    }

    public void applyResolvedDisplayWidth(int screenWidth) {
        int resolvedColumnCount = columnCount(screenWidth);
        if (columnVScrollBuffer.getEntryCount() == resolvedColumnCount) {
            return;
        }

        boolean rendererInitialized = shader != null;
        if (rendererInitialized) {
            columnVScrollBuffer.cleanup();
        }
        columnVScrollBuffer = new VScrollBuffer(resolvedColumnCount);
        if (rendererInitialized) {
            columnVScrollBuffer.init();
        }
        perColumnVScroll = false;
    }

    public int getVScrollColumnCapacity() {
        return columnVScrollBuffer.getEntryCount();
    }

    // Dummy 1x1 textures used as fallback when no real texture is available.
    // This prevents macOS OpenGL driver warnings about unbound samplers.
    private int dummyTextureId = 0;
    private int dummyTexture1dId = 0;

    private byte[] backgroundData;
    private byte[] backgroundColumnUploadData;
    private byte[] backgroundRowUploadData;
    private int backgroundWidthTiles;
    private int backgroundHeightTiles;
    private boolean backgroundDirty = false;
    private boolean backgroundIncrementalDirty = false;
    private int backgroundRingBaseXTiles = 0;
    private int backgroundRingBaseYTiles = 0;
    private int backgroundPendingSourceColumn = 0;
    private int backgroundPendingDestinationColumn = 0;
    private int backgroundPendingColumnCount = 0;
    private int backgroundPendingSourceRow = 0;
    private int backgroundPendingDestinationRow = 0;
    private int backgroundPendingRowCount = 0;
    private int backgroundRenderRingBaseXOverride = -1;
    private int backgroundRenderRingBaseYOverride = -1;
    private int backgroundContentGeneration = 0;
    private int backgroundRenderGenerationOverride = -1;

    private byte[] foregroundData;
    private int foregroundWidthTiles;
    private int foregroundHeightTiles;
    private boolean foregroundDirty = false;

    private byte[] lookupData;
    private int lookupSize;
    private boolean lookupDirty = false;

    // Per-line scroll state (set before render, reset after)
    private boolean perLineScroll = false;
    private int perLineHScrollTextureId = 0;
    private float perLineScreenHeight = 224.0f;
    private float perLineVdpWrapWidth = 0.0f;
    private float perLineNametableBase = 0.0f;
    private float perLineScrollSampleYOffsetPx = 0.0f;
    private float upperBandWrapHeightPx = 0.0f;
    private float upperBandWrapWidthTiles = 0.0f;
    private boolean perColumnVScroll = false;

    private float bgVdpWrapHeight = 0.0f;

    private int shimmerFrameCounter = 0;
    private int shimmerStyle = 0;

    public void init(String shaderPath) throws IOException {
        if (shader == null) {
            shader = new TilemapShaderProgram(shaderPath);
            shader.cacheUniformLocations();

            // Create a dummy 1x1 texture to bind to unused sampler units.
            // This prevents macOS OpenGL driver warnings about unbound samplers
            // when the shader is first validated.
            dummyTextureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, dummyTextureId);
            ByteBuffer pixel = MemoryUtil.memAlloc(4);
            try {
                pixel.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).flip();
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            } finally {
                MemoryUtil.memFree(pixel);
            }
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, 0);

            // Create a dummy 1x1 1D texture for HScrollTexture when per-line scroll
            // is not active. Without this, macOS rejects the draw call due to
            // sampler1D bound to a GL_TEXTURE_2D target on unit 0.
            dummyTexture1dId = glGenTextures();
            glBindTexture(GL_TEXTURE_1D, dummyTexture1dId);
            FloatBuffer pixel1d = MemoryUtil.memAllocFloat(1);
            try {
                pixel1d.put(0.0f).flip();
                glTexImage1D(GL_TEXTURE_1D, 0, GL_R32F, 1, 0, GL_RED, GL_FLOAT, pixel1d);
            } finally {
                MemoryUtil.memFree(pixel1d);
            }
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_1D, 0);

            columnVScrollBuffer.init();
            foregroundLineScrollBuffer.init();

            LOGGER.info("Tilemap GPU renderer initialized.");
        }
        quadRenderer.init();
    }

    public void setTilemapData(Layer layer, byte[] data, int widthTiles, int heightTiles) {
        if (layer == Layer.FOREGROUND) {
            this.foregroundData = data;
            this.foregroundWidthTiles = widthTiles;
            this.foregroundHeightTiles = heightTiles;
            this.foregroundDirty = true;
        } else {
            this.backgroundData = data;
            this.backgroundWidthTiles = widthTiles;
            this.backgroundHeightTiles = heightTiles;
            this.backgroundDirty = true;
            this.backgroundIncrementalDirty = false;
            this.backgroundPendingColumnCount = 0;
            this.backgroundPendingRowCount = 0;
            this.backgroundRingBaseXTiles = 0;
            this.backgroundRingBaseYTiles = 0;
            this.backgroundContentGeneration++;
        }
    }

    /**
     * Publishes a complete physical Plane B texture while retaining its logical
     * X/Y ring origins. Unlike {@link #setTilemapData}, this does not reinterpret
     * physical slot zero as logical slot zero.
     */
    public void setBackgroundTilemapDataPhysical(byte[] data, int widthTiles, int heightTiles,
                                                 int originXTiles, int originYTiles) {
        this.backgroundData = data;
        this.backgroundWidthTiles = widthTiles;
        this.backgroundHeightTiles = heightTiles;
        this.backgroundDirty = true;
        this.backgroundIncrementalDirty = false;
        this.backgroundPendingColumnCount = 0;
        this.backgroundPendingRowCount = 0;
        this.backgroundRingBaseXTiles = widthTiles > 0
                ? Math.floorMod(originXTiles, widthTiles) : 0;
        this.backgroundRingBaseYTiles = heightTiles > 0
                ? Math.floorMod(originYTiles, heightTiles) : 0;
        this.backgroundContentGeneration++;
    }

    /**
     * Registers a logical background-window shift. The CPU array remains in
     * normal left-to-right order; the texture retains old columns in a ring and
     * only receives the newly entering columns.
     */
    public void setBackgroundTilemapDataIncremental(byte[] data, int widthTiles, int heightTiles,
            int shiftTiles) {
        setBackgroundTilemapDataIncremental(data, widthTiles, heightTiles, shiftTiles, 0);
    }

    /**
     * Registers a two-dimensional logical background-window shift. Each axis
     * advances an independent physical texture origin and publishes only its
     * newly entering columns or rows.
     */
    public void setBackgroundTilemapDataIncremental(byte[] data, int widthTiles, int heightTiles,
            int shiftXTiles, int shiftYTiles) {
        int requiredBytes = checkedTilemapByteCount(widthTiles, heightTiles);
        if (data == null || widthTiles <= 0 || heightTiles <= 0
                || (shiftXTiles == 0 && shiftYTiles == 0)
                || shiftXTiles <= -widthTiles || shiftXTiles >= widthTiles
                || shiftYTiles <= -heightTiles || shiftYTiles >= heightTiles
                || requiredBytes < 0 || requiredBytes > data.length
                || backgroundData == null
                || backgroundWidthTiles != widthTiles
                || backgroundHeightTiles != heightTiles
                || backgroundDirty || backgroundIncrementalDirty) {
            setTilemapData(Layer.BACKGROUND, data, widthTiles, heightTiles);
            return;
        }
        backgroundData = data;
        backgroundRingBaseXTiles = (int) Math.floorMod(
                (long) backgroundRingBaseXTiles + shiftXTiles, widthTiles);
        backgroundRingBaseYTiles = (int) Math.floorMod(
                (long) backgroundRingBaseYTiles + shiftYTiles, heightTiles);

        backgroundPendingColumnCount = Math.abs(shiftXTiles);
        if (backgroundPendingColumnCount > 0) {
            backgroundPendingSourceColumn = shiftXTiles > 0
                    ? widthTiles - backgroundPendingColumnCount : 0;
            backgroundPendingDestinationColumn = mapBackgroundLogicalColumn(
                    backgroundPendingSourceColumn);
        }

        backgroundPendingRowCount = Math.abs(shiftYTiles);
        if (backgroundPendingRowCount > 0) {
            backgroundPendingSourceRow = shiftYTiles > 0
                    ? heightTiles - backgroundPendingRowCount : 0;
            backgroundPendingDestinationRow = mapBackgroundLogicalRow(backgroundPendingSourceRow);
        }
        backgroundIncrementalDirty = true;
        backgroundContentGeneration++;
    }

    /**
     * Enable per-scanline horizontal scroll for the next render() call.
     * The tilemap shader will sample hScroll per-scanline instead of using WorldOffsetX.
     * Automatically resets after render().
     */
    public void enablePerLineScroll(int hScrollTextureId, float screenHeight,
            float vdpWrapWidth, float nametableBase, float sampleYOffsetPx) {
        this.perLineScroll = true;
        this.perLineHScrollTextureId = hScrollTextureId;
        this.perLineScreenHeight = screenHeight;
        this.perLineVdpWrapWidth = vdpWrapWidth;
        this.perLineNametableBase = nametableBase;
        this.perLineScrollSampleYOffsetPx = sampleYOffsetPx;
    }

    /**
     * Limits the X wrap width for the upper portion of a BG tilemap.
     * Used by MGZ2 state 8 where the cloud rows only occupy the left portion of
     * the BG layout while lower rows expose the fake-floor strip.
     */
    public void setUpperBandWrap(float heightPx, float widthTiles) {
        this.upperBandWrapHeightPx = heightPx;
        this.upperBandWrapWidthTiles = widthTiles;
    }

    /**
     * Enable per-scanline foreground scroll for the next render() call from packed HScroll data.
     * Extracts FG values (high 16-bit word) and uploads them to a dedicated 1D texture.
     */
    public void enablePerLineForegroundScroll(int[] packedHScroll) {
        if (packedHScroll == null || packedHScroll.length == 0) {
            this.perLineScroll = false;
            return;
        }
        foregroundLineScrollBuffer.upload(packedHScroll);
        enablePerLineScroll(foregroundLineScrollBuffer.getTextureId(), 224.0f, 0.0f, 0.0f, 0.0f);
    }

    public void enablePerLineForegroundScroll(IntIndexedView packedHScroll) {
        if (packedHScroll == null || packedHScroll.size() == 0) {
            this.perLineScroll = false;
            return;
        }
        foregroundLineScrollBuffer.upload(packedHScroll);
        enablePerLineScroll(foregroundLineScrollBuffer.getTextureId(), 224.0f, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Enable per-column vertical scroll for the next render() call.
     * Column count scales with viewport width: ceil(screenWidth/16) — 20 at native 320px.
     * Automatically resets after render().
     */
    public void enablePerColumnVScroll(short[] columnVScroll) {
        if (columnVScroll == null || columnVScroll.length == 0) {
            this.perColumnVScroll = false;
            return;
        }
        columnVScrollBuffer.upload(columnVScroll);
        this.perColumnVScroll = true;
    }

    /** Enables per-column scroll from frame-owned read-only storage. */
    public void enablePerColumnVScroll(ShortIndexedView columnVScroll) {
        if (columnVScroll == null || columnVScroll.size() == 0) {
            this.perColumnVScroll = false;
            return;
        }
        columnVScrollBuffer.upload(columnVScroll);
        this.perColumnVScroll = true;
    }

    /**
     * Set shimmer state for underwater distortion. Called once per frame.
     * The shader gates shimmer on UseUnderwaterPalette, so only water zone
     * renders with underwater palette enabled will apply the distortion.
     */
    public void setShimmerState(int frameCounter, int shimmerStyle) {
        this.shimmerFrameCounter = frameCounter;
        this.shimmerStyle = shimmerStyle;
    }

    private static float resolvePerLineScrollSampleRow(float pixelYFromTop,
            float sampleYOffsetPx, float screenHeight) {
        float maxScanline = screenHeight - 1.0f;
        if (maxScanline <= 0.0f) {
            return 0.0f;
        }
        float scanline = pixelYFromTop - sampleYOffsetPx;
        if (scanline < 0.0f) {
            return 0.0f;
        }
        if (scanline > maxScanline) {
            return maxScanline;
        }
        return scanline;
    }

    public int getShimmerStyle() {
        return shimmerStyle;
    }

    public void setBgVdpWrapHeight(float heightTiles) {
        this.bgVdpWrapHeight = heightTiles;
    }

    public float getBgVdpWrapHeight() {
        return bgVdpWrapHeight;
    }

    public void setPatternLookupData(byte[] data, int size) {
        this.lookupData = data;
        this.lookupSize = size;
        this.lookupDirty = true;
    }

    public void render(
            Layer layer,
            int windowWidth,
            int windowHeight,
            int viewportX,
            int viewportY,
            int viewportWidth,
            int viewportHeight,
            float worldOffsetX,
            float worldOffsetY,
            int atlasWidth,
            int atlasHeight,
            int atlasTextureId,
            int paletteTextureId,
            int underwaterPaletteTextureId,
            int priorityPass,
            boolean wrapY,
            boolean maskOutput,
            boolean useUnderwaterPalette,
            float waterlineScreenY) {
        try {
            renderInternal(layer, windowWidth, windowHeight, viewportX, viewportY,
                    viewportWidth, viewportHeight, worldOffsetX, worldOffsetY,
                    atlasWidth, atlasHeight, atlasTextureId, paletteTextureId,
                    underwaterPaletteTextureId, priorityPass, wrapY, maskOutput,
                    useUnderwaterPalette, waterlineScreenY);
        } finally {
            resetOneShotRenderState();
        }
    }

    private void renderInternal(
            Layer layer,
            int windowWidth,
            int windowHeight,
            int viewportX,
            int viewportY,
            int viewportWidth,
            int viewportHeight,
            float worldOffsetX,
            float worldOffsetY,
            int atlasWidth,
            int atlasHeight,
            int atlasTextureId,
            int paletteTextureId,
            int underwaterPaletteTextureId,
            int priorityPass,
            boolean wrapY,
            boolean maskOutput,
            boolean useUnderwaterPalette,
            float waterlineScreenY) {
        byte[] tilemapData = layer == Layer.FOREGROUND ? foregroundData : backgroundData;
        int tilemapWidthTiles = layer == Layer.FOREGROUND ? foregroundWidthTiles : backgroundWidthTiles;
        int tilemapHeightTiles = layer == Layer.FOREGROUND ? foregroundHeightTiles : backgroundHeightTiles;
        TilemapTexture tilemapTexture = layer == Layer.FOREGROUND ? foregroundTexture : backgroundTexture;
        int renderRingBaseX = layer == Layer.BACKGROUND && backgroundRenderRingBaseXOverride >= 0
                ? backgroundRenderRingBaseXOverride
                : (layer == Layer.BACKGROUND ? backgroundRingBaseXTiles : 0);
        int renderRingBaseY = layer == Layer.BACKGROUND && backgroundRenderRingBaseYOverride >= 0
                ? backgroundRenderRingBaseYOverride
                : (layer == Layer.BACKGROUND ? backgroundRingBaseYTiles : 0);
        // The override is a one-call token. Consume it before any early return or
        // GL operation so a failed/skipped draw cannot leak frame state forward.
        int renderGeneration = backgroundRenderGenerationOverride;
        backgroundRenderRingBaseXOverride = -1;
        backgroundRenderRingBaseYOverride = -1;
        backgroundRenderGenerationOverride = -1;
        if (layer == Layer.BACKGROUND && renderGeneration >= 0
                && renderGeneration != backgroundContentGeneration) {
            // A retained command can become stale when a later registration
            // conservatively coalesces pending partials into a full upload. Skip
            // it without draining the newest upload; its matching command owns
            // that coherent generation.
            return;
        }
        if (layer == Layer.BACKGROUND && backgroundIncrementalDirty
                && !tilemapTexture.hasStorage(tilemapWidthTiles, tilemapHeightTiles)) {
            escalateBackgroundUploadToFull();
            renderRingBaseX = 0;
            renderRingBaseY = 0;
        }

        if (shader == null || tilemapData == null || lookupData == null) {
            return;
        }

        if (layer == Layer.FOREGROUND) {
            if (foregroundDirty) {
                tilemapTexture.upload(tilemapData, tilemapWidthTiles, tilemapHeightTiles);
                foregroundDirty = false;
            }
        } else if (backgroundDirty) {
            tilemapTexture.upload(tilemapData, tilemapWidthTiles, tilemapHeightTiles);
            backgroundDirty = false;
        } else if (backgroundIncrementalDirty) {
            if (!uploadPendingBackgroundSpans(tilemapTexture)) {
                // Context loss or texture recreation invalidated the retained
                // physical ring. Rebuild canonical storage and publish base 0.
                tilemapTexture.upload(tilemapData, tilemapWidthTiles, tilemapHeightTiles);
                escalateBackgroundUploadToFull();
                backgroundDirty = false;
                renderRingBaseX = 0;
                renderRingBaseY = 0;
            }
            backgroundIncrementalDirty = false;
            backgroundPendingColumnCount = 0;
            backgroundPendingRowCount = 0;
        }
        if (lookupDirty) {
            patternLookup.upload(lookupData, lookupSize);
            lookupDirty = false;
        }

        // Bind all sampler1D units before shader.use().
        // macOS may validate samplers at program-use time.
        int patternLookupTextureId = patternLookup.getTextureId();
        int boundPatternLookupTexture = patternLookupTextureId != 0 ? patternLookupTextureId : dummyTexture1dId;
        int boundHScrollTexture = (perLineScroll && perLineHScrollTextureId != 0) ? perLineHScrollTextureId : dummyTexture1dId;
        int columnVScrollTextureId = columnVScrollBuffer.getTextureId();
        int boundColumnVScrollTexture = (perColumnVScroll && columnVScrollTextureId != 0)
                ? columnVScrollTextureId : dummyTexture1dId;
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_1D, boundPatternLookupTexture);
        glActiveTexture(GL_TEXTURE5);
        glBindTexture(GL_TEXTURE_1D, boundHScrollTexture);
        glActiveTexture(GL_TEXTURE6);
        glBindTexture(GL_TEXTURE_1D, boundColumnVScrollTexture);

        shader.use();
        shader.cacheUniformLocations();
        shader.setTotalPaletteLines((float) RenderContext.getTotalPaletteLines());

        shader.setTextureUnits(0, 1, 2, 3, 4);
        shader.setTilemapDimensions(tilemapWidthTiles, tilemapHeightTiles);
        shader.setTilemapRingBase(renderRingBaseX, renderRingBaseY);
        shader.setAtlasDimensions(atlasWidth, atlasHeight);
        shader.setLookupSize(lookupSize);
        shader.setWindowDimensions(windowWidth, windowHeight);
        shader.setViewport(viewportX, viewportY, viewportWidth, viewportHeight);
        shader.setWorldOffset(worldOffsetX, worldOffsetY);
        shader.setWrapY(wrapY);
        shader.setPriorityPass(priorityPass);
        shader.setMaskOutput(maskOutput);
        shader.setWaterSplit(useUnderwaterPalette, waterlineScreenY);
        shader.setPerLineScroll(perLineScroll);
        shader.setVdpWrapWidth(perLineScroll ? perLineVdpWrapWidth : 0.0f);
        shader.setVdpWrapHeight(layer == Layer.BACKGROUND ? bgVdpWrapHeight : 0.0f);
        shader.setNametableBase(perLineScroll ? perLineNametableBase : 0.0f);
        shader.setPerLineScrollSampleYOffsetPx(perLineScroll ? perLineScrollSampleYOffsetPx : 0.0f);
        shader.setUpperBandWrap(layer == Layer.BACKGROUND ? upperBandWrapHeightPx : 0.0f,
                layer == Layer.BACKGROUND ? upperBandWrapWidthTiles : 0.0f);
        // Always assign HScrollTexture to unit 5 to satisfy macOS sampler validation.
        shader.setHScrollTexture(5);
        shader.setVScrollColumnTexture(6);
        shader.setPerColumnVScroll(perColumnVScroll);
        // The column texture is sized from the configured screen width, while
        // windowWidth is the FBO render width for BG passes (e.g. 512 at native
        // 320), so the shader must sample with the texture's own entry count.
        shader.setVScrollColumnCount(columnVScrollBuffer.getEntryCount());
        if (perLineScroll) {
            shader.setScreenHeight(perLineScreenHeight);
        }
        shader.setShimmerParams(shimmerFrameCounter, shimmerStyle);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tilemapTexture.getTextureId());

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_1D, boundPatternLookupTexture);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, atlasTextureId);

        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, paletteTextureId);

        glActiveTexture(GL_TEXTURE4);
        // Use dummy texture when no underwater palette is available to avoid
        // macOS OpenGL driver warnings about unbound samplers.
        glBindTexture(GL_TEXTURE_2D, underwaterPaletteTextureId != 0 ? underwaterPaletteTextureId : dummyTextureId);

        // Always bind HScrollTexture to unit 5 to satisfy macOS sampler validation.
        // Use dummy 1D texture when per-line scroll is not active.
        glActiveTexture(GL_TEXTURE5);
        glBindTexture(GL_TEXTURE_1D, boundHScrollTexture);

        // Bind per-column VScroll texture (or dummy when disabled).
        glActiveTexture(GL_TEXTURE6);
        glBindTexture(GL_TEXTURE_1D, boundColumnVScrollTexture);

        if (layer == Layer.FOREGROUND && foregroundWindow != null) {
            drawWithForegroundWindow(windowWidth, windowHeight, viewportX, viewportY, viewportWidth, viewportHeight);
        } else {
            quadRenderer.draw(0, 0, windowWidth, windowHeight);
        }

        shader.stop();
    }

    private void drawWithForegroundWindow(int width, int height, int viewportX, int viewportY,
                                          int viewportWidth, int viewportHeight) {
        var window = foregroundWindow;
        if (!java.util.Arrays.equals(uploadedWindowDescriptors, window.descriptors())
                || !foregroundWindowTexture.hasStorage(window.widthTiles(), window.heightTiles())) {
            byte[] pixels = packWindowDescriptors(window.descriptors());
            foregroundWindowTexture.upload(pixels, window.widthTiles(), window.heightTiles());
            uploadedWindowDescriptors = window.descriptors().clone();
        }
        boolean scissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
        glGetIntegerv(GL_SCISSOR_BOX, savedWindowScissor);
        glEnable(GL_SCISSOR_TEST);
        int split = Math.min(viewportHeight, Math.max(0, window.top() * viewportHeight / height));
        try {
            setWindowScissor(viewportX, viewportY + viewportHeight - split,
                    viewportWidth, split, scissorEnabled);
            quadRenderer.draw(0, 0, width, height);

            // The window replaces Plane A, including transparent texels. Use the
            // same priority pass/mask output so visible tiles and sprite occlusion agree.
            setWindowScissor(viewportX, viewportY, viewportWidth, viewportHeight - split, scissorEnabled);
            shader.setTilemapDimensions(window.widthTiles(), window.heightTiles());
            shader.setTilemapRingBase(0, 0);
            shader.setWorldOffset(0, 0);
            shader.setPerLineScroll(false);
            shader.setPerColumnVScroll(false);
            shader.setVdpWrapWidth(0);
            shader.setVdpWrapHeight(0);
            shader.setNametableBase(0);
            shader.setWrapY(false);
            shader.setShimmerParams(0, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, foregroundWindowTexture.getTextureId());
            quadRenderer.draw(0, 0, width, height);
        } finally {
            glScissor(savedWindowScissor[0], savedWindowScissor[1], savedWindowScissor[2], savedWindowScissor[3]);
            if (!scissorEnabled) {
                glDisable(GL_SCISSOR_TEST);
            }
        }
    }

    private void setWindowScissor(int x, int y, int width, int height, boolean intersect) {
        if (intersect) {
            int right = Math.min(x + width, savedWindowScissor[0] + savedWindowScissor[2]);
            int top = Math.min(y + height, savedWindowScissor[1] + savedWindowScissor[3]);
            x = Math.max(x, savedWindowScissor[0]);
            y = Math.max(y, savedWindowScissor[1]);
            width = Math.max(0, right - x);
            height = Math.max(0, top - y);
        }
        glScissor(x, y, width, height);
    }

    static byte[] packWindowDescriptors(int[] descriptors) {
        byte[] pixels = new byte[descriptors.length * 4];
        for (int i = 0; i < descriptors.length; i++) {
            int descriptor = descriptors[i];
            pixels[i * 4] = (byte) descriptor;
            pixels[i * 4 + 1] = (byte) (((descriptor >> 8) & 7)
                    | (((descriptor >> 13) & 3) << 3)
                    | ((descriptor & 0x800) != 0 ? 0x20 : 0)
                    | ((descriptor & 0x1000) != 0 ? 0x40 : 0)
                    | ((descriptor & 0x8000) != 0 ? 0x80 : 0));
            pixels[i * 4 + 3] = (byte) 0xFF;
        }
        return pixels;
    }

    private void resetOneShotRenderState() {
        foregroundWindow = null;
        perLineScroll = false;
        perLineScrollSampleYOffsetPx = 0.0f;
        upperBandWrapHeightPx = 0.0f;
        upperBandWrapWidthTiles = 0.0f;
        perColumnVScroll = false;
        backgroundRenderRingBaseXOverride = -1;
        backgroundRenderRingBaseYOverride = -1;
        backgroundRenderGenerationOverride = -1;
    }

    protected boolean hasPendingOneShotRenderState() {
        return foregroundWindow != null || perLineScroll || perColumnVScroll
                || perLineScrollSampleYOffsetPx != 0.0f
                || upperBandWrapHeightPx != 0.0f
                || upperBandWrapWidthTiles != 0.0f
                || backgroundRenderRingBaseXOverride >= 0
                || backgroundRenderRingBaseYOverride >= 0
                || backgroundRenderGenerationOverride >= 0;
    }

    private boolean uploadPendingBackgroundSpans(TilemapTexture texture) {
        if (!texture.hasStorage(backgroundWidthTiles, backgroundHeightTiles)) {
            return false;
        }
        if (backgroundPendingColumnCount > 0 && !uploadPendingBackgroundColumns(texture)) {
            return false;
        }
        return backgroundPendingRowCount <= 0 || uploadPendingBackgroundRows(texture);
    }

    private boolean uploadPendingBackgroundColumns(TilemapTexture texture) {
        byte[] uploadData = prepareBackgroundColumnUploadData();
        int firstCount = Math.min(backgroundPendingColumnCount,
                backgroundWidthTiles - backgroundPendingDestinationColumn);
        if (!texture.uploadColumns(uploadData, backgroundWidthTiles, backgroundHeightTiles,
                backgroundPendingSourceColumn, backgroundPendingDestinationColumn, firstCount)) {
            return false;
        }
        int remaining = backgroundPendingColumnCount - firstCount;
        if (remaining > 0) {
            if (!texture.uploadColumns(uploadData, backgroundWidthTiles, backgroundHeightTiles,
                    backgroundPendingSourceColumn + firstCount, 0, remaining)) {
                return false;
            }
        }
        return true;
    }

    private boolean uploadPendingBackgroundRows(TilemapTexture texture) {
        byte[] uploadData = prepareBackgroundRowUploadData();
        int firstCount = Math.min(backgroundPendingRowCount,
                backgroundHeightTiles - backgroundPendingDestinationRow);
        if (!texture.uploadRows(uploadData, backgroundWidthTiles, backgroundHeightTiles,
                backgroundPendingSourceRow, backgroundPendingDestinationRow, firstCount)) {
            return false;
        }
        int remaining = backgroundPendingRowCount - firstCount;
        return remaining <= 0 || texture.uploadRows(uploadData,
                backgroundWidthTiles, backgroundHeightTiles,
                backgroundPendingSourceRow + firstCount, 0, remaining);
    }

    private byte[] prepareBackgroundColumnUploadData() {
        if (backgroundRingBaseYTiles == 0) {
            return backgroundData;
        }
        int requiredBytes = backgroundWidthTiles * backgroundHeightTiles * 4;
        if (backgroundColumnUploadData == null || backgroundColumnUploadData.length != requiredBytes) {
            backgroundColumnUploadData = new byte[requiredBytes];
        }
        int rowBytes = backgroundWidthTiles * 4;
        for (int physicalRow = 0; physicalRow < backgroundHeightTiles; physicalRow++) {
            int logicalRow = Math.floorMod(physicalRow - backgroundRingBaseYTiles,
                    backgroundHeightTiles);
            System.arraycopy(backgroundData, logicalRow * rowBytes,
                    backgroundColumnUploadData, physicalRow * rowBytes, rowBytes);
        }
        return backgroundColumnUploadData;
    }

    private byte[] prepareBackgroundRowUploadData() {
        if (backgroundRingBaseXTiles == 0) {
            return backgroundData;
        }
        int requiredBytes = backgroundWidthTiles * backgroundHeightTiles * 4;
        if (backgroundRowUploadData == null || backgroundRowUploadData.length != requiredBytes) {
            backgroundRowUploadData = new byte[requiredBytes];
        }
        int rowBytes = backgroundWidthTiles * 4;
        for (int logicalRow = 0; logicalRow < backgroundHeightTiles; logicalRow++) {
            for (int physicalColumn = 0; physicalColumn < backgroundWidthTiles; physicalColumn++) {
                int logicalColumn = Math.floorMod(physicalColumn - backgroundRingBaseXTiles,
                        backgroundWidthTiles);
                System.arraycopy(backgroundData, logicalRow * rowBytes + logicalColumn * 4,
                        backgroundRowUploadData, logicalRow * rowBytes + physicalColumn * 4, 4);
            }
        }
        return backgroundRowUploadData;
    }

    private void escalateBackgroundUploadToFull() {
        backgroundDirty = true;
        backgroundIncrementalDirty = false;
        backgroundPendingColumnCount = 0;
        backgroundPendingRowCount = 0;
        backgroundRingBaseXTiles = 0;
        backgroundRingBaseYTiles = 0;
        backgroundContentGeneration++;
    }

    private int mapBackgroundLogicalColumn(int logicalColumn) {
        return backgroundWidthTiles <= 0 ? logicalColumn
                : (int) Math.floorMod(
                        (long) backgroundRingBaseXTiles + logicalColumn, backgroundWidthTiles);
    }

    private int mapBackgroundLogicalRow(int logicalRow) {
        return backgroundHeightTiles <= 0 ? logicalRow
                : (int) Math.floorMod(
                        (long) backgroundRingBaseYTiles + logicalRow, backgroundHeightTiles);
    }

    /** @deprecated use {@link #getBackgroundRingBaseXTiles()}. */
    @Deprecated
    public int getBackgroundRingBaseTiles() {
        return getBackgroundRingBaseXTiles();
    }

    public int getBackgroundRingBaseXTiles() {
        return backgroundRingBaseXTiles;
    }

    public int getBackgroundRingBaseYTiles() {
        return backgroundRingBaseYTiles;
    }

    /** Uses the frame command's captured ring origin for the next BG draw only. */
    public void setBackgroundRenderRingBaseOverride(int ringBaseTiles, int contentGeneration) {
        setBackgroundRenderRingBaseOverride(ringBaseTiles, 0, contentGeneration);
    }

    /** Uses the frame command's captured ring origins for the next BG draw only. */
    public void setBackgroundRenderRingBaseOverride(int ringBaseXTiles, int ringBaseYTiles,
            int contentGeneration) {
        backgroundRenderRingBaseXOverride = ringBaseXTiles;
        backgroundRenderRingBaseYOverride = ringBaseYTiles;
        backgroundRenderGenerationOverride = contentGeneration;
    }

    /** Immutable render token captured before a retained background command is queued. */
    public BackgroundRenderState captureBackgroundRenderState() {
        return new BackgroundRenderState(
                backgroundRingBaseXTiles, backgroundRingBaseYTiles, backgroundContentGeneration);
    }

    @com.openggf.game.ModApi
    public record BackgroundRenderState(
            int ringBaseXTiles, int ringBaseYTiles, int contentGeneration) { }

    public int getBackgroundContentGeneration() {
        return backgroundContentGeneration;
    }

    public boolean isBackgroundContentGenerationCurrent(int generation) {
        return generation == backgroundContentGeneration;
    }

    public boolean hasBackgroundBaseline(byte[] data, int widthTiles, int heightTiles) {
        return backgroundData == data
                && backgroundWidthTiles == widthTiles
                && backgroundHeightTiles == heightTiles;
    }

    protected int getPendingBackgroundUploadBytes() {
        if (backgroundDirty) {
            return backgroundData == null ? 0 : backgroundData.length;
        }
        if (!backgroundIncrementalDirty) {
            return 0;
        }
        int columnBytes = backgroundPendingColumnCount == 0 ? 0
                : checkedTilemapByteCount(backgroundPendingColumnCount, backgroundHeightTiles);
        int rowBytes = backgroundPendingRowCount == 0 ? 0
                : checkedTilemapByteCount(backgroundWidthTiles, backgroundPendingRowCount);
        if (columnBytes < 0 || rowBytes < 0) {
            return Integer.MAX_VALUE;
        }
        long totalBytes = (long) columnBytes + rowBytes;
        return totalBytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalBytes;
    }

    protected int getPendingBackgroundUploadCallCount() {
        if (backgroundDirty) {
            return 1;
        }
        if (!backgroundIncrementalDirty) {
            return 0;
        }
        int columnCalls = backgroundPendingColumnCount == 0 ? 0
                : (backgroundPendingDestinationColumn + backgroundPendingColumnCount
                        <= backgroundWidthTiles ? 1 : 2);
        int rowCalls = backgroundPendingRowCount == 0 ? 0
                : (backgroundPendingDestinationRow + backgroundPendingRowCount
                        <= backgroundHeightTiles ? 1 : 2);
        return columnCalls + rowCalls;
    }

    protected int getPendingBackgroundColumnCount() {
        return backgroundIncrementalDirty ? backgroundPendingColumnCount : 0;
    }

    protected int getPendingBackgroundRowCount() {
        return backgroundIncrementalDirty ? backgroundPendingRowCount : 0;
    }

    protected int mapBackgroundLogicalColumnForTest(int logicalColumn) {
        return mapBackgroundLogicalColumn(logicalColumn);
    }

    protected void consumePendingBackgroundUploadForTest() {
        backgroundDirty = false;
        backgroundIncrementalDirty = false;
        backgroundPendingColumnCount = 0;
        backgroundPendingRowCount = 0;
    }

    /** Applies the real pending texture-upload plan to a headless recording texture. */
    protected boolean applyPendingBackgroundUploadForTest(TilemapTexture texture) {
        if (texture == null || backgroundData == null) {
            return false;
        }
        if (backgroundDirty) {
            texture.upload(backgroundData, backgroundWidthTiles, backgroundHeightTiles);
            if (!texture.hasStorage(backgroundWidthTiles, backgroundHeightTiles)) {
                return false;
            }
        } else if (backgroundIncrementalDirty && !uploadPendingBackgroundSpans(texture)) {
            return false;
        }
        consumePendingBackgroundUploadForTest();
        return true;
    }

    /** Applies the pending upload plan to a CPU texture image for headless parity tests. */
    protected void applyPendingBackgroundUploadForTest(byte[] physicalTexture) {
        if (physicalTexture == null || backgroundData == null
                || physicalTexture.length != backgroundData.length) {
            throw new IllegalArgumentException("physical texture size must match background data");
        }
        if (backgroundDirty) {
            System.arraycopy(backgroundData, 0, physicalTexture, 0, backgroundData.length);
        } else if (backgroundIncrementalDirty) {
            copyPendingColumnsToPhysicalTexture(physicalTexture);
            copyPendingRowsToPhysicalTexture(physicalTexture);
        }
        consumePendingBackgroundUploadForTest();
    }

    private void copyPendingColumnsToPhysicalTexture(byte[] physicalTexture) {
        for (int logicalRow = 0; logicalRow < backgroundHeightTiles; logicalRow++) {
            int physicalRow = mapBackgroundLogicalRow(logicalRow);
            for (int column = 0; column < backgroundPendingColumnCount; column++) {
                int sourceColumn = backgroundPendingSourceColumn + column;
                int destinationColumn = Math.floorMod(backgroundPendingDestinationColumn + column,
                        backgroundWidthTiles);
                System.arraycopy(backgroundData,
                        (logicalRow * backgroundWidthTiles + sourceColumn) * 4,
                        physicalTexture,
                        (physicalRow * backgroundWidthTiles + destinationColumn) * 4, 4);
            }
        }
    }

    private void copyPendingRowsToPhysicalTexture(byte[] physicalTexture) {
        for (int row = 0; row < backgroundPendingRowCount; row++) {
            int sourceRow = backgroundPendingSourceRow + row;
            int destinationRow = Math.floorMod(backgroundPendingDestinationRow + row,
                    backgroundHeightTiles);
            for (int logicalColumn = 0; logicalColumn < backgroundWidthTiles; logicalColumn++) {
                int physicalColumn = mapBackgroundLogicalColumn(logicalColumn);
                System.arraycopy(backgroundData,
                        (sourceRow * backgroundWidthTiles + logicalColumn) * 4,
                        physicalTexture,
                        (destinationRow * backgroundWidthTiles + physicalColumn) * 4, 4);
            }
        }
    }

    private static int checkedTilemapByteCount(int widthTiles, int heightTiles) {
        if (widthTiles <= 0 || heightTiles <= 0) {
            return -1;
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(widthTiles, heightTiles), 4);
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
        if (dummyTextureId != 0) {
            glDeleteTextures(dummyTextureId);
            dummyTextureId = 0;
        }
        if (dummyTexture1dId != 0) {
            glDeleteTextures(dummyTexture1dId);
            dummyTexture1dId = 0;
        }
        columnVScrollBuffer.cleanup();
        foregroundLineScrollBuffer.cleanup();
        backgroundTexture.cleanup();
        foregroundTexture.cleanup();
        foregroundWindowTexture.cleanup();
        uploadedWindowDescriptors = null;
        foregroundWindow = null;
        patternLookup.cleanup();
        quadRenderer.cleanup();
        backgroundData = null;
        backgroundColumnUploadData = null;
        backgroundRowUploadData = null;
        backgroundWidthTiles = 0;
        backgroundHeightTiles = 0;
        backgroundDirty = false;
        backgroundIncrementalDirty = false;
        backgroundPendingColumnCount = 0;
        backgroundPendingRowCount = 0;
        backgroundRingBaseXTiles = 0;
        backgroundRingBaseYTiles = 0;
        backgroundRenderRingBaseXOverride = -1;
        backgroundRenderRingBaseYOverride = -1;
        backgroundRenderGenerationOverride = -1;
        backgroundContentGeneration++;
        bgVdpWrapHeight = 0.0f;
        lookupData = null;
        lookupSize = 0;
        lookupDirty = false;
    }
}

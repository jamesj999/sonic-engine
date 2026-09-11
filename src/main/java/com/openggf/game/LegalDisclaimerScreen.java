package com.openggf.game;

import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.PlayerInputState;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.PixelFont;
import com.openggf.graphics.PngTextureLoader;
import com.openggf.graphics.TexturedQuadRenderer;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;

/**
 * Legal disclaimer screen shown on engine startup before the master title
 * screen. Renders a white-text-on-black layout, gates dismissal for 5 s,
 * and chains entry/exit fades through {@link FadeManager}.
 */
public class LegalDisclaimerScreen {

    private static final Logger LOGGER = Logger.getLogger(LegalDisclaimerScreen.class.getName());
    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 224;

    /** Side margin in px on each edge. */
    private static final int SIDE_MARGIN = 20;

    /** Maximum width in px for a wrapped body line. */
    static final int BODY_MAX_WIDTH = SCREEN_W - (SIDE_MARGIN * 2);

    /**
     * Body text scale. PixelFont is 9 px/char wide at scale 1.0; at scale
     * 0.75 each char is ~6.75 px wide, giving ~41 chars per 280 px line.
     * The chosen scale keeps the three paragraphs inside Y=48..Y=200 so
     * the dismiss prompt at Y=210 has clearance. Do not raise above 0.85
     * without recomputing the layout (see TestLegalDisclaimerLayoutFits).
     */
    static final float BODY_SCALE = 0.75f;

    /** Pixel advance from one body line to the next. */
    static final int BODY_LINE_HEIGHT = 8;

    /** Pixel gap inserted for each empty paragraph entry. */
    static final int BODY_PARAGRAPH_GAP = 10;

    /** First body line baseline Y. */
    static final int BODY_START_Y = 48;

    /** Dismiss prompt baseline Y. Body must not extend past this. */
    static final int PROMPT_Y = 210;

    /**
     * Frames over which the dismiss prompt ramps from invisible to fully
     * visible after entering {@link LegalDisclaimerState.Phase#DISMISSIBLE},
     * before the pulse cycle drives brightness. 30 frames = 0.5 s at 60 fps.
     */
    static final int PROMPT_FADE_IN_FRAMES = 30;

    /** Pulse cycle period (frames). 90 frames = 1.5 s at 60 fps. */
    static final int PROMPT_PULSE_PERIOD_FRAMES = 90;

    private static final double PROMPT_PULSE_OMEGA =
            2.0 * Math.PI / PROMPT_PULSE_PERIOD_FRAMES;

    private static final String HEADER = "LEGAL NOTICE";

    /**
     * Disclaimer body, one entry per paragraph. Word-wrapped at draw time
     * by {@link #wrapParagraph} so the layout survives font-metric changes.
     * Empty string entries produce a blank line between paragraphs.
     * Package-private so {@link TestLegalDisclaimerTextWrap} can verify
     * the wrapped body fits before the dismiss prompt.
     */
    static final String[] BODY_PARAGRAPHS = {
        "OpenGGF is an independent, open-source reimplementation of the original Sonic the Hedgehog games for the Sega Mega Drive / Genesis. It is developed and verified against community-maintained disassemblies.",
        "",
        "No copyrighted Sega assets are distributed with this engine. ROM data, sprites, music, and all other game assets must be supplied by the user from a legally obtained copy.",
        "",
        "This project is not affiliated with or endorsed by Sega. Sonic the Hedgehog and all related characters, names, and trademarks are the property of Sega Corporation, to which no claim is made."
    };

    private static final String PROMPT = "Press any key to continue";

    private final FadeManager fadeManager;
    private final LegalDisclaimerState state = new LegalDisclaimerState();

    private TexturedQuadRenderer renderer;
    private PixelFont font;
    private int solidWhiteTextureId;
    private List<String> wrappedBodyLines;

    public LegalDisclaimerScreen(FadeManager fadeManager) {
        this.fadeManager = Objects.requireNonNull(fadeManager, "fadeManager");
    }

    public void initialize() {
        try {
            renderer = new TexturedQuadRenderer();
            renderer.init();
            font = new PixelFont();
            font.init("pixel-font.png", renderer);
            solidWhiteTextureId = createSolidWhiteTexture();
            ToIntFunction<String> measure = s -> font.measureWidth(s, BODY_SCALE);
            wrappedBodyLines = new ArrayList<>();
            for (String paragraph : BODY_PARAGRAPHS) {
                if (paragraph.isEmpty()) {
                    wrappedBodyLines.add("");
                } else {
                    wrappedBodyLines.addAll(wrapParagraph(paragraph, measure, BODY_MAX_WIDTH));
                }
            }
            fadeManager.startFadeFromBlack(state::onFadeInComplete);
            LOGGER.info("Legal disclaimer screen initialized");
        } catch (IOException e) {
            LOGGER.severe("Failed to initialize legal disclaimer screen: " + e.getMessage());
            throw new RuntimeException("Failed to initialize legal disclaimer screen", e);
        }
    }

    private static int createSolidWhiteTexture() {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        pixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(pixel);
        return texId;
    }

    public void update(InputHandler inputHandler) {
        PlayerInputState p1 = inputHandler.logical().player1();
        boolean p1LogicalDismiss = (p1.actionPressedMask() & InputActionMasks.ACTION_ALL) != 0
                || p1.startPressed();
        boolean keyPressed = inputHandler.isAnyKeyJustPressed()
                || p1LogicalDismiss;
        boolean enteredExiting = state.tick(keyPressed);
        if (enteredExiting) {
            fadeManager.startFadeToBlack(state::onFadeOutComplete);
        }
    }

    public void draw() {
        if (renderer == null) {
            return;
        }
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Solid black background (separate texture; not batched with text).
        renderer.drawTexture(solidWhiteTextureId, 0, 0, SCREEN_W, SCREEN_H,
                0f, 0f, 0f, 1f);

        // All text (header + body + prompt) shares the font atlas, so we
        // mega-batch into a single GL draw call.
        font.beginMegaBatch();

        // Header (full scale)
        font.drawTextCentered(HEADER, SCREEN_W, 22, 1f, 1f, 1f, 1f);

        // Body — iterate cached wrapped lines (computed once at init).
        int bodyY = BODY_START_Y;
        ToIntFunction<String> measure = s -> font.measureWidth(s, BODY_SCALE);
        for (String line : wrappedBodyLines) {
            if (line.isEmpty()) {
                bodyY += BODY_PARAGRAPH_GAP;
                continue;
            }
            int x = (SCREEN_W - measure.applyAsInt(line)) / 2;
            font.drawText(line, x, bodyY, BODY_SCALE, 0.95f, 0.95f, 0.95f, 1f);
            bodyY += BODY_LINE_HEIGHT;
        }

        // Dismiss prompt — fade-in and pulse share the same dismissible-phase
        // frame counter so they're aligned. Pulse starts at its trough
        // (brightness 0.2) and rises with the fade-in for a clean bloom up
        // to peak, then settles into the 0.2..1.0 cycle.
        if (state.isDismissPromptVisible()) {
            int dismissibleFrames = state.getDismissibleFrameCounter();
            float fadeIn = dismissibleFrames >= PROMPT_FADE_IN_FRAMES
                    ? 1.0f
                    : dismissibleFrames / (float) PROMPT_FADE_IN_FRAMES;
            float pulse = 0.6f - 0.4f * (float) Math.cos(dismissibleFrames * PROMPT_PULSE_OMEGA);
            float brightness = pulse * fadeIn;
            font.drawTextCentered(PROMPT, SCREEN_W, PROMPT_Y, brightness, brightness, brightness, 1f);
        }

        font.endMegaBatch();
    }

    /**
     * Greedy word-wrap. Decoupled from {@link PixelFont} so it can be tested
     * without OpenGL: the caller supplies the per-string width measure.
     * Words longer than {@code maxWidth} get their own line (do not loop or
     * truncate). An empty input returns a single empty line so the caller
     * can preserve paragraph break spacing.
     */
    static List<String> wrapParagraph(String paragraph,
                                      ToIntFunction<String> measure,
                                      int maxWidth) {
        if (paragraph.isEmpty()) {
            return List.of("");
        }
        List<String> out = new ArrayList<>();
        String[] words = paragraph.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
                continue;
            }
            String candidate = current + " " + word;
            if (measure.applyAsInt(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                out.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    public void setProjectionMatrix(float[] projectionMatrix) {
        if (renderer != null && projectionMatrix != null) {
            renderer.setProjectionMatrix(projectionMatrix);
        }
    }

    public boolean isDismissed() {
        return state.isDismissed();
    }

    public void cleanup() {
        if (font != null) {
            font.cleanup();
        }
        PngTextureLoader.deleteTexture(solidWhiteTextureId);
        if (renderer != null) {
            renderer.cleanup();
        }
        LOGGER.info("Legal disclaimer screen cleaned up");
    }
}

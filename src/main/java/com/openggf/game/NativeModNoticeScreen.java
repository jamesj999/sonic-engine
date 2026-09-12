package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.MenuPixelFont;
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
 * Boot screen shown on a native build when enabled code-bearing mods cannot load.
 * The notice fades in, dismisses on input, and fades back to black before boot
 * continues.
 */
public final class NativeModNoticeScreen {
    private static final Logger LOGGER = Logger.getLogger(NativeModNoticeScreen.class.getName());
    public static final int MAX_VISIBLE_MOD_LINES = 12;
    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 224;
    private static final int BODY_MAX_WIDTH = SCREEN_W - 32;
    private static final int MAX_RENDERED_LINES = 12;
    static final float BODY_SCALE = 0.75f;
    private int page;
    private int logicalWidth = SCREEN_W;
    private InputHandler menuInput;

    private final FadeManager fadeManager;
    private final List<String> noticeLines;
    private boolean dismissed;
    private boolean fadingOut;

    private TexturedQuadRenderer renderer;
    private PixelFont font;
    private int solidWhiteTextureId;
    private List<String> wrappedNoticeLines = List.of();

    public NativeModNoticeScreen(FadeManager fadeManager, List<String> noticeLines) {
        this.fadeManager = Objects.requireNonNull(fadeManager, "fadeManager");
        this.noticeLines = List.copyOf(noticeLines);
        wrappedNoticeLines = wrapLines(this.noticeLines,
                line -> line.length() * MenuPixelFont.glyphAdvance(BODY_SCALE),
                BODY_MAX_WIDTH, MAX_RENDERED_LINES);
    }

    public void initialize() {
        try {
            renderer = new TexturedQuadRenderer();
            renderer.init();
            font = new MenuPixelFont();
            font.init("pixel-font.png", renderer);
            wrappedNoticeLines = wrapLines(noticeLines,
                    line -> font.measureWidth(line, BODY_SCALE),
                    BODY_MAX_WIDTH, MAX_RENDERED_LINES);
            solidWhiteTextureId = createSolidWhiteTexture();
            fadeManager.startFadeFromBlack(null);
            LOGGER.info("Native mod notice screen initialized");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize native mod notice screen", e);
        }
    }

    public void update(InputHandler inputHandler) {
        if (dismissed) {
            return;
        }
        menuInput = inputHandler;
        if (fadingOut) return;
        int delta = MenuInput.up(inputHandler) || MenuInput.left(inputHandler) ? -1
                : MenuInput.down(inputHandler) || MenuInput.right(inputHandler) ? 1 : 0;
        page = Math.clamp(page + delta, 0, pageCount() - 1);
        if (MenuInput.accept(inputHandler)) {
            if (page + 1 < pageCount()) page++;
            else {
                fadingOut = true;
                fadeManager.startFadeToBlack(() -> dismissed = true);
            }
        }
    }

    int pageCount() { return Math.max(1, (wrappedNoticeLines.size() + MAX_RENDERED_LINES - 1) / MAX_RENDERED_LINES); }
    int currentPage() { return page; }


    public boolean isDismissed() {
        return dismissed;
    }

    public void draw() {
        if (renderer == null) {
            return;
        }
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        renderer.drawTexture(solidWhiteTextureId, 0, 0, logicalWidth, SCREEN_H,
                0f, 0f, 0f, 1f);
        font.beginMegaBatch();
        int y = 40;
        for (String line : wrappedNoticeLines.subList(page * MAX_RENDERED_LINES,
                Math.min(wrappedNoticeLines.size(), (page + 1) * MAX_RENDERED_LINES))) {
            int x = (logicalWidth - font.measureWidth(line, BODY_SCALE)) / 2;
            font.drawText(line, x, y, BODY_SCALE, 0.95f, 0.95f, 0.95f, 1f);
            y += 12;
        }
        String confirm = menuInput == null ? "Enter" : MenuInput.confirmLabel(menuInput);
        String prompt = confirm + (page + 1 < pageCount() ? " Next page" : " Continue");
        font.drawText(prompt, (logicalWidth - font.measureWidth(prompt, BODY_SCALE)) / 2,
                SCREEN_H - 20, BODY_SCALE, 0.8f, 0.8f, 0.8f, 1f);
        if (pageCount() > 1) {
            String directions = menuInput == null ? "Arrows" : MenuInput.directionLabel(menuInput);
            String pages = "Page " + (page + 1) + "/" + pageCount() + "  " + directions + " Browse";
            font.drawText(pages, (logicalWidth - font.measureWidth(pages, BODY_SCALE)) / 2,
                    188, BODY_SCALE, .8f, .8f, .8f, 1f);
        }
        font.endMegaBatch();
    }

    static List<String> wrapLines(List<String> lines,
                                  ToIntFunction<String> measure,
                                  int maxWidth,
                                  int maxLines) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(measure, "measure");
        if (maxWidth <= 0 || maxLines <= 0) {
            throw new IllegalArgumentException("Wrap dimensions must be positive");
        }

        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrapLine(Objects.requireNonNull(line, "line"), measure, maxWidth));
        }
        // The viewport is paged: keep every wrapped line for later pages.
        return List.copyOf(wrapped);
    }

    private static List<String> wrapLine(String line,
                                         ToIntFunction<String> measure,
                                         int maxWidth) {
        if (line.isBlank()) {
            return List.of("");
        }
        List<String> wrapped = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : line.trim().split("\\s+")) {
            if (measure.applyAsInt(word) > maxWidth) {
                flush(current, wrapped);
                splitOversizedWord(word, measure, maxWidth, wrapped);
                continue;
            }
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (measure.applyAsInt(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                flush(current, wrapped);
                current.append(word);
            }
        }
        flush(current, wrapped);
        return wrapped;
    }

    private static void splitOversizedWord(String word,
                                           ToIntFunction<String> measure,
                                           int maxWidth,
                                           List<String> output) {
        StringBuilder chunk = new StringBuilder();
        for (int index = 0; index < word.length(); index++) {
            char next = word.charAt(index);
            if (!chunk.isEmpty() && measure.applyAsInt(chunk.toString() + next) > maxWidth) {
                output.add(chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(next);
        }
        flush(chunk, output);
    }

    private static void flush(StringBuilder line, List<String> output) {
        if (!line.isEmpty()) {
            output.add(line.toString());
            line.setLength(0);
        }
    }

    public void setViewportWidth(int logicalWidth) { this.logicalWidth = Math.max(SCREEN_W, logicalWidth); }

    public void setProjectionMatrix(float[] projectionMatrix) {
        if (renderer != null && projectionMatrix != null) {
            renderer.setProjectionMatrix(projectionMatrix);
        }
    }

    public void cleanup() {
        if (font != null) {
            font.cleanup();
        }
        PngTextureLoader.deleteTexture(solidWhiteTextureId);
        if (renderer != null) {
            renderer.cleanup();
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
}

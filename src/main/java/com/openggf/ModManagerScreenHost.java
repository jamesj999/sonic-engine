package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.MenuStyle;
import com.openggf.game.MenuFeedback;
import com.openggf.mods.ui.ModManagerScreen;

import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/** Root composition adapter between engine input/rendering and the neutral mod UI. */
public final class ModManagerScreenHost implements MasterTitleScreen.ModManagerView {
    private final ModManagerScreen screen;

    public ModManagerScreenHost(ModManagerScreen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    @Override
    public void update(InputHandler input) {
        screen.update(menuInput(Objects.requireNonNull(input, "input")),
                event -> MenuFeedback.emit(MenuFeedback.Cue.valueOf(event.name())));
    }

    @Override public void render() { screen.render(); }
    @Override public boolean consumeCloseRequested() { return screen.consumeCloseRequested(); }
    @Override public void suppressInputUntilNeutral() { screen.suppressInputUntilNeutral(); }

    static ModManagerScreen.MenuInput menuInput(InputHandler input) {
        var logical = input.logical();
        boolean escape = input.isKeyPressed(GLFW_KEY_ESCAPE);
        // Mod UI retains this object as its previous frame; never close over mutable input.
        boolean up = MenuInput.up(input);
        boolean down = MenuInput.down(input);
        boolean left = MenuInput.left(input);
        boolean right = MenuInput.right(input);
        boolean accept = MenuInput.accept(input);
        boolean back = MenuInput.back(input);
        String confirmLabel = MenuInput.confirmLabel(input);
        String backLabel = MenuInput.backLabel(input);
        String directionLabel = MenuInput.directionLabel(input);

        return new ModManagerScreen.MenuInput() {
            @Override public boolean menuUp() { return up; }
            @Override public boolean menuDown() { return down; }
            @Override public boolean menuLeft() { return left; }
            @Override public boolean menuRight() { return right; }
            @Override public boolean menuAccept() { return accept; }
            @Override public boolean menuBack() { return back; }
            @Override public boolean startHeld() { return logical.player1().startHeld(); }
            @Override public boolean escape() { return escape; }
            @Override public String confirmLabel() { return confirmLabel; }
            @Override public String backLabel() { return backLabel; }
            @Override public String directionLabel() { return directionLabel; }
        };
    }

    public static ModManagerScreen.TextSink textSink(PixelFont font) {
        Objects.requireNonNull(font, "font");
        return new ModManagerScreen.TextSink() {
            @Override public void begin() { font.beginMegaBatch(); }
            @Override public void draw(String text, int x, int y, float scale,
                                       float r, float g, float b, float a) {
                font.drawText(text, x, y, scale, r, g, b, a);
            }
            @Override public void end() { font.endMegaBatch(); }
            @Override public void page(String title, String subtitle) { MenuStyle.page(font, 320, title, subtitle); }
            @Override public void panel(int x, int y, int width, int height) { MenuStyle.panel(font, x, y, width, height); }
            @Override public void focus(int x, int y, int width, int height) { MenuStyle.focus(font, x, y, width, height); }
            @Override public int primaryTextY(int rowTop, int rowHeight) { return MenuStyle.textY(rowTop, rowHeight, 1); }
            @Override public void footer(String first, String second) {
                MenuStyle.fill(font, 0, 198, 320, 26, .012f, .025f, .09f, 1);
                MenuStyle.label(font, first, 9, 200, 302, .5f, .9f, 1);
                MenuStyle.label(font, second, 9, 212, 302, .8f, .86f, 1);
            }
        };
    }
}

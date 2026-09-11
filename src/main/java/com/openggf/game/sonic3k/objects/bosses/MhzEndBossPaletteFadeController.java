package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.util.Arrays;
import java.util.List;

/**
 * ROM {@code loc_85E64} palette fade object as allocated by MHZ end-boss
 * {@code loc_76574}: fade the full normal palette to white, then fade back
 * down to the target palette copied by the weather-machine destruction code.
 */
public final class MhzEndBossPaletteFadeController extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int PALETTE_LINES = 4;
    private static final int COLORS_PER_LINE = 16;
    private static final int DEFAULT_DELAY = 3;
    private static final int FADE_STEPS = 8;

    private final byte[][] targetLines;
    private int delay;
    private boolean fadeBackToTarget;
    private int[][] currentWords;
    private int timer;
    private int step;
    private boolean fadingToTarget;

    private MhzEndBossPaletteFadeController() {
        this(new byte[0][], true, 0);
    }

    public MhzEndBossPaletteFadeController(byte[][] targetLines) {
        this(targetLines, true, DEFAULT_DELAY);
    }

    public MhzEndBossPaletteFadeController(byte[][] targetLines, boolean fadeBackToTarget, int delay) {
        super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "MHZEndBossPaletteFade");
        this.targetLines = cloneLines(targetLines);
        this.fadeBackToTarget = fadeBackToTarget;
        this.delay = delay;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MhzEndBossPaletteFadeController(new byte[0][], true, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }
        setPaletteRotationDisabled(true);
        initializeCurrentWordsIfNeeded();
        timer--;
        if (timer >= 0) {
            return;
        }
        timer = delay;
        if (fadingToTarget) {
            applyTargetStep();
        } else {
            applyWhiteStep();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    private void applyWhiteStep() {
        for (int line = 0; line < PALETTE_LINES; line++) {
            for (int color = 0; color < COLORS_PER_LINE; color++) {
                currentWords[line][color] = stepTowardWhite(currentWords[line][color]);
            }
            applyLine(line);
        }
        step++;
        if (step < FADE_STEPS) {
            return;
        }
        if (!fadeBackToTarget) {
            destroyAndResumePaletteRotation();
            return;
        }
        fadingToTarget = true;
        step = 0;
        timer = delay;
    }

    private void applyTargetStep() {
        for (int line = 0; line < PALETTE_LINES; line++) {
            for (int color = 0; color < COLORS_PER_LINE; color++) {
                currentWords[line][color] = stepDownToward(
                        currentWords[line][color],
                        targetWord(line, color));
            }
            applyLine(line);
        }
        step++;
        if (step >= FADE_STEPS) {
            destroyAndResumePaletteRotation();
        }
    }

    private void destroyAndResumePaletteRotation() {
        setPaletteRotationDisabled(false);
        setDestroyed(true);
    }

    private void setPaletteRotationDisabled(boolean disabled) {
        var services = tryServices();
        if (services != null && services.paletteOwnershipRegistryOrNull() != null) {
            services.paletteOwnershipRegistryOrNull().setPaletteRotationDisabled(disabled);
        }
    }

    private void applyLine(int line) {
        var services = tryServices();
        S3kPaletteWriteSupport.applyLine(
                services != null ? services.paletteOwnershipRegistryOrNull() : null,
                services != null ? services.currentLevel() : null,
                services != null ? services.graphicsManager() : null,
                S3kPaletteOwners.MHZ_END_BOSS_DEFEAT_FADE,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                line,
                encodeLine(currentWords[line]));
    }

    private void initializeCurrentWordsIfNeeded() {
        if (currentWords != null) {
            return;
        }
        currentWords = new int[PALETTE_LINES][COLORS_PER_LINE];
        var services = tryServices();
        Level level = services != null ? services.currentLevel() : null;
        for (int line = 0; line < PALETTE_LINES; line++) {
            Palette palette = level != null && line < level.getPaletteCount()
                    ? level.getPalette(line)
                    : null;
            for (int color = 0; color < COLORS_PER_LINE; color++) {
                currentWords[line][color] = palette != null ? encodeColor(palette.getColor(color)) : 0;
            }
        }
    }

    private int targetWord(int line, int color) {
        if (targetLines == null || line >= targetLines.length || targetLines[line] == null) {
            return 0;
        }
        int offset = color * 2;
        if (offset + 1 >= targetLines[line].length) {
            return 0;
        }
        return ((targetLines[line][offset] & 0xFF) << 8) | (targetLines[line][offset + 1] & 0xFF);
    }

    private static int stepTowardWhite(int word) {
        int red = Math.min(7, red(word) + 1);
        int green = Math.min(7, green(word) + 1);
        int blue = Math.min(7, blue(word) + 1);
        return encodeWord(red, green, blue);
    }

    private static int stepDownToward(int word, int target) {
        int red = Math.max(red(target), red(word) - 1);
        int green = Math.max(green(target), green(word) - 1);
        int blue = Math.max(blue(target), blue(word) - 1);
        return encodeWord(red, green, blue);
    }

    private static int red(int word) {
        return (word >> 1) & 0x07;
    }

    private static int green(int word) {
        return (word >> 5) & 0x07;
    }

    private static int blue(int word) {
        return (word >> 9) & 0x07;
    }

    private static int encodeWord(int red, int green, int blue) {
        return ((blue & 0x07) << 9) | ((green & 0x07) << 5) | ((red & 0x07) << 1);
    }

    private static int encodeColor(Palette.Color color) {
        int red = ((color.r & 0xFF) * 7 + 127) / 255;
        int green = ((color.g & 0xFF) * 7 + 127) / 255;
        int blue = ((color.b & 0xFF) * 7 + 127) / 255;
        return encodeWord(red, green, blue);
    }

    private static byte[] encodeLine(int[] words) {
        byte[] line = new byte[words.length * 2];
        for (int i = 0; i < words.length; i++) {
            line[i * 2] = (byte) ((words[i] >>> 8) & 0xFF);
            line[i * 2 + 1] = (byte) (words[i] & 0xFF);
        }
        return line;
    }

    private static byte[][] cloneLines(byte[][] lines) {
        if (lines == null) {
            return null;
        }
        byte[][] clone = new byte[lines.length][];
        for (int i = 0; i < lines.length; i++) {
            clone[i] = lines[i] != null ? Arrays.copyOf(lines[i], lines[i].length) : null;
        }
        return clone;
    }
}

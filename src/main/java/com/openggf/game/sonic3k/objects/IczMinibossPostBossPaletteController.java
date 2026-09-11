package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Level;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ZeroArgRewindRecreatable;

import java.util.List;

/**
 * ICZ miniboss post-defeat background palette script.
 *
 * <p>ROM anchor: {@code loc_71420} / {@code word_719FA}. After the boss hands
 * off to {@code Obj_EndSignControl}, the ROM allocates a separate object that
 * runs a one-shot {@code Run_PalRotationScript} over
 * {@code Normal_palette_line_4+$02}: 10 colors, 10 rows, 8 frames per row.
 */
public final class IczMinibossPostBossPaletteController extends AbstractObjectInstance
        implements ZeroArgRewindRecreatable {
    private static final int PALETTE_LINE = 3;
    private static final int START_COLOR = 1;
    private static final int FRAME_DELAY = 8;

    private static final int[][] SCRIPT_ROWS = {
            {0x0EEC, 0x0CC6, 0x0C80, 0x0C60, 0x0C40, 0x0A40, 0x0820, 0x0620, 0x0200, 0x0600},
            {0x0EEC, 0x0CC6, 0x0C82, 0x0C80, 0x0C40, 0x0A40, 0x0820, 0x0820, 0x0200, 0x0600},
            {0x0EEC, 0x0CC8, 0x0E82, 0x0C82, 0x0C40, 0x0C40, 0x0A20, 0x0820, 0x0200, 0x0600},
            {0x0EEE, 0x0EC8, 0x0EA4, 0x0C82, 0x0C60, 0x0C40, 0x0A20, 0x0A20, 0x0400, 0x0600},
            {0x0EEE, 0x0EC8, 0x0EA4, 0x0E82, 0x0C60, 0x0C40, 0x0C20, 0x0A20, 0x0400, 0x0600},
            {0x0EEE, 0x0EC8, 0x0EA6, 0x0E82, 0x0C62, 0x0C40, 0x0C20, 0x0C20, 0x0600, 0x0800},
            {0x0EEE, 0x0EE8, 0x0EC6, 0x0EA2, 0x0C80, 0x0C60, 0x0C20, 0x0C20, 0x0600, 0x0A00},
            {0x0EEE, 0x0EEA, 0x0EC6, 0x0EA4, 0x0C82, 0x0C60, 0x0C40, 0x0C20, 0x0800, 0x0C00},
            {0x0EEE, 0x0EEA, 0x0EC8, 0x0EA4, 0x0C82, 0x0C60, 0x0C40, 0x0E20, 0x0A00, 0x0C00},
            {0x0EEE, 0x0EEA, 0x0EC8, 0x0EA4, 0x0C82, 0x0C60, 0x0C40, 0x0E20, 0x0A00, 0x0E00},
    };

    private int rowIndex;
    private int timer;

    public IczMinibossPostBossPaletteController() {
        super(null, "IczMinibossPostBossPalette");
        timer = 0;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        if (rowIndex >= SCRIPT_ROWS.length) {
            setDestroyed(true);
            return;
        }
        applyRow(SCRIPT_ROWS[rowIndex]);
        rowIndex++;
        timer = FRAME_DELAY - 1;
    }

    private void applyRow(int[] segaWords) {
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        Level level = services.currentLevel();
        if (level == null) {
            return;
        }
        byte[] patch = new byte[segaWords.length * 2];
        for (int i = 0; i < segaWords.length; i++) {
            patch[i * 2] = (byte) ((segaWords[i] >>> 8) & 0xFF);
            patch[i * 2 + 1] = (byte) (segaWords[i] & 0xFF);
        }
        S3kPaletteWriteSupport.applyContiguousPatch(
                services.paletteOwnershipRegistryOrNull(),
                level,
                services.graphicsManager(),
                S3kPaletteOwners.ICZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                PALETTE_LINE,
                START_COLOR,
                patch);
        S3kPaletteWriteSupport.resolvePendingWritesNow(
                services.paletteOwnershipRegistryOrNull(),
                level,
                services.graphicsManager());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible palette-script controller.
    }
}

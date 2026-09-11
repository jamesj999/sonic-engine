package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * MGZ2 post-results palette fade to CNZ.
 *
 * <p>ROM: {@code loc_6D104}. Every step copies one 32-byte row from
 * {@code Pal_MGZFadeCNZ} to {@code Normal_palette_line_4}. After the final row
 * it runs {@code StartNewLevel #$300}, entering CNZ Act 1.
 */
public class Mgz2PostBossPaletteFadeController extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(Mgz2PostBossPaletteFadeController.class.getName());
    private static final int PALETTE_LINE_4_INDEX = 3;
    private static final int[] STEP_DELAYS = {
            0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A,
            0x0A, 0x64, 0x05, 0x05, 0x05, 0x05, 0x14, 0x00,
    };

    private int timer;
    private int step;

    public Mgz2PostBossPaletteFadeController() {
        super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "MGZ2PostBossPaletteFade");
    }

    @Override
    public Mgz2PostBossPaletteFadeController recreateForRewind(RewindRecreateContext ctx) {
        return new Mgz2PostBossPaletteFadeController();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }
        timer--;
        if (timer >= 0) {
            return;
        }
        applyFadeStep();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    private void applyFadeStep() {
        if (step >= Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROWS) {
            requestCnzAct1();
            return;
        }

        byte[] row = readFadeRow(step);
        S3kPaletteWriteSupport.applyLine(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.MGZ_POST_BOSS_FADE,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                PALETTE_LINE_4_INDEX,
                row,
                true);
        timer = STEP_DELAYS[Math.min(step, STEP_DELAYS.length - 1)];
        step++;
        if (step >= Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROWS) {
            requestCnzAct1();
        }
    }

    private byte[] readFadeRow(int row) {
        ObjectServices services = services();
        try {
            return services.rom().readBytes(
                    Sonic3kConstants.PAL_MGZ_FADE_CNZ_ADDR
                            + row * Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROW_SIZE,
                    Sonic3kConstants.PAL_MGZ_FADE_CNZ_ROW_SIZE);
        } catch (IOException e) {
            LOG.fine(() -> "Mgz2PostBossPaletteFadeController.readFadeRow: " + e.getMessage());
            return null;
        }
    }

    private void requestCnzAct1() {
        services().requestZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0, true);
        setDestroyed(true);
    }
}

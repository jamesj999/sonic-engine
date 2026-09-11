package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * CNZ Act 2 lights-off / water palette flash effect.
 *
 * <p>ROM reference: {@code loc_62480} (sonic3k.asm), the child object spawned by
 * the CNZ cutscene button ({@code loc_65C78}) and the water-level button
 * ({@code loc_65D8C}).
 *
 * <p>The effect writes {@code Pal_CNZFlash} into {@code Normal_palette_line_3}
 * (engine palette lines 2 and 3) across six flicker steps, alternating between
 * the two 64-byte flash variants with the {@code word_624D0} durations
 * {@code {8,4,3,2,4,8}}. Per ROM, each step writes a full 64-byte chunk (two VDP
 * palette lines).
 *
 * <ul>
 *   <li>When spawned with {@code subtype == 0} (cutscene button), the effect
 *       leaves the final dark variant in palette lines 2/3 — the lights stay
 *       off.</li>
 *   <li>When spawned with {@code subtype} set (water-level button), the final
 *       step restores {@code Pal_CNZ+$20} — the lights come back on.</li>
 * </ul>
 *
 * <p>The writes are submitted at {@link S3kPaletteOwners#PRIORITY_LIGHTS_FLASH}
 * (below the zone cycle) so the always-on CNZ {@code AnPal} color cycling
 * (palette colors 7-11), which ROM applies after object updates, still wins on
 * its colors — the bumpers keep glowing while the rest of the line goes dark.
 */
public final class CnzLightsFlashChildInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(CnzLightsFlashChildInstance.class.getName());

    /** ROM: {@code word_624D0}. Six active steps; the trailing entry is unused. */
    private static final int[] STEP_DURATIONS = {8, 4, 3, 2, 4, 8};
    private static final int STEP_COUNT = 6;

    /** {@code Normal_palette_line_3} -> engine palette index 2. */
    private static final int FLASH_LINE_LOW = 2;
    /** {@code Normal_palette_line_4} -> engine palette index 3. */
    private static final int FLASH_LINE_HIGH = 3;

    private static final int VARIANT_BYTES = 64;
    private static final int LINE_BYTES = 32;
    /** ROM: restore branch copies {@code Pal_CNZ+$20}. */
    private static final int PAL_CNZ_RESTORE_OFFSET = 0x20;

    private boolean restoreAfter;

    private Cnz2CutsceneButtonInstance owner;

    private byte[] flashData;
    private int step;
    private int timer;
    private boolean failed;

    /**
     * @param restoreAfter ROM {@code subtype(a0)} on the flash child: {@code false}
     *                     for the cutscene button (lights stay off), {@code true}
     *                     for the water button (restore Pal_CNZ, lights on).
     */
    public CnzLightsFlashChildInstance(ObjectSpawn spawn, boolean restoreAfter) {
        this(spawn, restoreAfter, null);
    }

    CnzLightsFlashChildInstance(ObjectSpawn spawn, boolean restoreAfter,
            Cnz2CutsceneButtonInstance owner) {
        super(spawn, "CNZLightsFlash");
        this.restoreAfter = restoreAfter;
        this.owner = owner;
    }

    CnzLightsFlashChildInstance(ObjectSpawn spawn) {
        this(spawn, false);
    }

    /** Test seam: whether this flash restores Pal_CNZ (lights on) after flicker. */
    boolean restoresAfterForTest() {
        return restoreAfter;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (failed) {
            setDestroyed(true);
            return;
        }
        if (flashData == null && !loadFlashData()) {
            return;
        }

        // ROM loc_62480: subq.w #1,$2E(a0) / bpl return — wait while timer >= 0.
        timer--;
        if (timer >= 0) {
            return;
        }
        if (step >= STEP_COUNT) {
            finish();
            return;
        }

        int currentStep = step;
        step++;
        timer = STEP_DURATIONS[currentStep];
        // ROM: btst #1,d0 where d0 = step*2, i.e. variant B on odd steps.
        boolean variantB = (currentStep & 1) != 0;
        writeFlashVariant(variantB);
    }

    private boolean loadFlashData() {
        try {
            flashData = services().rom().readBytes(
                    Sonic3kConstants.PAL_CNZ_FLASH_ADDR, Sonic3kConstants.PAL_CNZ_FLASH_SIZE);
        } catch (Exception e) {
            LOG.warning("CNZ: failed to load Pal_CNZFlash: " + e.getMessage());
            failed = true;
            setDestroyed(true);
            return false;
        }
        return true;
    }

    private void writeFlashVariant(boolean variantB) {
        int base = variantB ? VARIANT_BYTES : 0;
        byte[] low = Arrays.copyOfRange(flashData, base, base + LINE_BYTES);
        byte[] high = Arrays.copyOfRange(flashData, base + LINE_BYTES, base + VARIANT_BYTES);
        applyLine(FLASH_LINE_LOW, low);
        applyLine(FLASH_LINE_HIGH, high);
    }

    private void finish() {
        if (restoreAfter) {
            try {
                byte[] restore = services().rom().readBytes(
                        Sonic3kConstants.PAL_CNZ_ADDR + PAL_CNZ_RESTORE_OFFSET, VARIANT_BYTES);
                applyLine(FLASH_LINE_LOW, Arrays.copyOfRange(restore, 0, LINE_BYTES));
                applyLine(FLASH_LINE_HIGH, Arrays.copyOfRange(restore, LINE_BYTES, VARIANT_BYTES));
            } catch (Exception e) {
                LOG.warning("CNZ: failed to restore Pal_CNZ after flash: " + e.getMessage());
            }
        }
        if (owner != null) {
            owner.clearCompletedFlash(this);
            owner = null;
        }
        setDestroyed(true);
    }

    @Override
    protected void afterRewindRestoreSettled() {
        if (owner != null || services().objectManager() == null) {
            return;
        }
        for (var object : services().objectManager().getActiveObjects()) {
            if (object instanceof Cnz2CutsceneButtonInstance button && button.ownsFlash(this)) {
                owner = button;
                return;
            }
        }
    }

    private void applyLine(int paletteIndex, byte[] lineData) {
        S3kPaletteWriteSupport.applyLine(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.CNZ_LIGHTS_FLASH,
                S3kPaletteOwners.PRIORITY_LIGHTS_FLASH,
                paletteIndex,
                lineData);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Palette-only effect; no sprite to draw.
    }
}

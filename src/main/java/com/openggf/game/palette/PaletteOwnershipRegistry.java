package com.openggf.game.palette;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.PaletteOwnershipSnapshot;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@com.openggf.game.ModApi
public final class PaletteOwnershipRegistry implements RewindSnapshottable<PaletteOwnershipSnapshot> {
    private static final String NO_OWNER = "none";

    private final List<PaletteWrite> writes = new ArrayList<>();
    private final String[][][] owners = new String[2][4][16];
    /** ROM Target_palette_* staging surface used by fades; persistent across frame queues. */
    private final byte[][] targetSegaLines = new byte[4][32];
    private final String[][] targetOwners = new String[4][16];
    private final boolean[] normalDirty = new boolean[4];
    private boolean paletteRotationDisabled;
    private boolean resolvedThisFrame;

    public PaletteOwnershipRegistry() {
        resetOwners();
        resetTargetOwners();
    }

    public void beginFrame() {
        writes.clear();
        resetOwners();
        resolvedThisFrame = false;
    }

    public void clear() {
        beginFrame();
        paletteRotationDisabled = false;
        for (byte[] line : targetSegaLines) Arrays.fill(line, (byte) 0);
        resetTargetOwners();
    }

    public void submit(PaletteWrite write) {
        writes.add(write);
    }

    /** Applies an immediate ROM Target_palette write without entering the normal/underwater frame queue. */
    public void applyTargetPatch(String ownerId, int lineIndex, int startColor, byte[] segaData) {
        if (ownerId == null || segaData == null || (segaData.length & 1) != 0
                || lineIndex < 0 || lineIndex >= targetSegaLines.length
                || startColor < 0 || startColor + segaData.length / 2 > 16) {
            throw new IllegalArgumentException("invalid target palette patch");
        }
        System.arraycopy(segaData, 0, targetSegaLines[lineIndex], startColor * 2, segaData.length);
        Arrays.fill(targetOwners[lineIndex], startColor, startColor + segaData.length / 2, ownerId);
    }

    public byte[] targetSegaData(int lineIndex, int startColor, int colorCount) {
        if (lineIndex < 0 || lineIndex >= targetSegaLines.length || startColor < 0
                || colorCount < 0 || startColor + colorCount > 16) {
            throw new IllegalArgumentException("invalid target palette range");
        }
        return Arrays.copyOfRange(targetSegaLines[lineIndex], startColor * 2, (startColor + colorCount) * 2);
    }

    public String targetOwnerAt(int lineIndex, int colorIndex) {
        return targetOwners[lineIndex][colorIndex];
    }

    public void setPaletteRotationDisabled(boolean disabled) {
        paletteRotationDisabled = disabled;
    }

    public boolean isPaletteRotationDisabled() {
        return paletteRotationDisabled;
    }

    public String ownerAt(PaletteSurface surface, int lineIndex, int colorIndex) {
        return owners[surface.ordinal()][lineIndex][colorIndex];
    }

    /**
     * Whether {@link #resolveInto} has applied writes since the last
     * {@link #beginFrame()}. The shared frame fallback uses this so games
     * whose palette cyclers already resolve (S2 cycling zones, S3K) are not
     * resolved a second time.
     */
    public boolean hasResolvedThisFrame() {
        return resolvedThisFrame;
    }

    public void resolveInto(Palette[] normal, Palette[] underwater,
                            GraphicsManager graphics, Palette normalLine0) {
        if (writes.isEmpty()) {
            return;
        }
        resolvedThisFrame = true;

        java.util.Arrays.fill(normalDirty, false);

        writes.sort(java.util.Comparator.comparingInt(PaletteWrite::priority));
        for (PaletteWrite write : writes) {
            applyWrite(surfaceArray(write.surface(), normal, underwater), write, normalDirty);
            applyOwners(write.surface(), write);
            if (write.mirrorToUnderwaterEnabled() && underwater != null) {
                applyWrite(underwater, write, null);
                applyOwners(PaletteSurface.UNDERWATER, write);
            }
        }

        if (graphics != null && graphics.isGlInitialized()) {
            for (int line = 0; line < normalDirty.length; line++) {
                if (normalDirty[line]) {
                    graphics.cachePaletteTexture(normal[line], line);
                }
            }
            if (underwater != null && hasUnderwaterOwner() && normalLine0 != null) {
                graphics.cacheUnderwaterPaletteTexture(underwater, normalLine0);
            }
        }
    }

    private boolean hasUnderwaterOwner() {
        for (int line = 0; line < 4; line++) {
            for (int color = 0; color < 16; color++) {
                if (!NO_OWNER.equals(owners[PaletteSurface.UNDERWATER.ordinal()][line][color])) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyWrite(Palette[] palettes, PaletteWrite write, boolean[] normalDirty) {
        if (palettes == null) {
            return;
        }
        Palette palette = palettes[write.lineIndex()];
        if (palette == null) {
            return;
        }
        write.applyTo(palette);
        if (normalDirty != null) {
            normalDirty[write.lineIndex()] = true;
        }
    }

    private void applyOwners(PaletteSurface surface, PaletteWrite write) {
        for (int i = 0; i < write.colorCount(); i++) {
            owners[surface.ordinal()][write.lineIndex()][write.startColor() + i] = write.ownerId();
        }
    }

    private Palette[] surfaceArray(PaletteSurface surface, Palette[] normal, Palette[] underwater) {
        return surface == PaletteSurface.NORMAL ? normal : underwater;
    }

    private void resetOwners() {
        for (int surface = 0; surface < owners.length; surface++) {
            for (int line = 0; line < owners[surface].length; line++) {
                for (int color = 0; color < owners[surface][line].length; color++) {
                    owners[surface][line][color] = NO_OWNER;
                }
            }
        }
    }

    private void resetTargetOwners() {
        for (String[] line : targetOwners) Arrays.fill(line, NO_OWNER);
    }

    // ── RewindSnapshottable ───────────────────────────────────────────────

    /** Total cells: 2 surfaces × 4 lines × 16 colors = 128. */
    private static final int OWNER_FLAT_SIZE = 2 * 4 * 16;

    @Override
    public String key() {
        return "palette-ownership";
    }

    @Override
    public PaletteOwnershipSnapshot capture() {
        String[] ownersFlat = new String[OWNER_FLAT_SIZE];
        int idx = 0;
        for (int s = 0; s < owners.length; s++) {
            for (int l = 0; l < owners[s].length; l++) {
                for (int c = 0; c < owners[s][l].length; c++) {
                    ownersFlat[idx++] = owners[s][l][c];
                }
            }
        }
        byte[] targetBytes = new byte[4 * 32];
        String[] targetOwnersFlat = new String[4 * 16];
        int byteOffset = 0;
        int targetOwnerOffset = 0;
        for (int line = 0; line < 4; line++) {
            System.arraycopy(targetSegaLines[line], 0, targetBytes, byteOffset, 32);
            System.arraycopy(targetOwners[line], 0, targetOwnersFlat, targetOwnerOffset, 16);
            byteOffset += 32;
            targetOwnerOffset += 16;
        }
        return new PaletteOwnershipSnapshot(ownersFlat, paletteRotationDisabled, targetBytes, targetOwnersFlat);
    }

    @Override
    public void restore(PaletteOwnershipSnapshot snap) {
        writes.clear();
        resolvedThisFrame = false;
        byte[] ownerIds = snap.ownerIds();
        String[] ownerTable = snap.ownerTable();
        paletteRotationDisabled = snap.paletteRotationDisabled();
        int idx = 0;
        for (int s = 0; s < owners.length; s++) {
            for (int l = 0; l < owners[s].length; l++) {
                for (int c = 0; c < owners[s][l].length; c++) {
                    int ownerId = Byte.toUnsignedInt(ownerIds[idx++]);
                    owners[s][l][c] = ownerId == 0 ? NO_OWNER : ownerTable[ownerId - 1];
                }
            }
        }
        byte[] targetBytes = snap.targetSegaData();
        byte[] targetIds = snap.targetOwnerIds();
        String[] targetTable = snap.targetOwnerTable();
        int targetByteOffset = 0;
        int targetOwnerOffset = 0;
        for (int line = 0; line < 4; line++) {
            System.arraycopy(targetBytes, targetByteOffset, targetSegaLines[line], 0, 32);
            for (int color = 0; color < 16; color++) {
                int ownerId = Byte.toUnsignedInt(targetIds[targetOwnerOffset++]);
                targetOwners[line][color] = ownerId == 0 ? NO_OWNER : targetTable[ownerId - 1];
            }
            targetByteOffset += 32;
        }
    }
}

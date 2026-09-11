package com.openggf.game.rewind.snapshot;

/**
 * Snapshot of {@link com.openggf.game.palette.PaletteOwnershipRegistry} state.
 *
 * <p>The registry's {@code beginFrame()} wipes both the {@code writes} queue and
 * the {@code owners} lookup at the start of every frame. Since rewind
 * capture/restore happens at frame boundaries, the {@code writes} list is always
 * empty at capture time and is excluded. The {@code owners} array is captured as
 * a flat {@code String[]} to preserve any ownership that was committed after
 * {@code resolveInto} ran during the preceding frame (relevant when future code
 * queries ownership before the next {@code beginFrame}). Owner strings are
 * stored once in {@code ownerTable}; each palette cell stores a compact id.
 */
public record PaletteOwnershipSnapshot(byte[] ownerIds, String[] ownerTable, boolean paletteRotationDisabled) {
    public PaletteOwnershipSnapshot {
        ownerIds = ownerIds.clone();
        ownerTable = ownerTable.clone();
    }

    public PaletteOwnershipSnapshot(String[] owners) {
        this(owners, false);
    }

    public PaletteOwnershipSnapshot(String[] owners, boolean paletteRotationDisabled) {
        this(packOwnerIds(owners), packOwnerTable(owners), paletteRotationDisabled);
    }

    public String[] owners() {
        String[] owners = new String[ownerIds.length];
        for (int i = 0; i < ownerIds.length; i++) {
            int id = Byte.toUnsignedInt(ownerIds[i]);
            owners[i] = id == 0 ? "none" : ownerTable[id - 1];
        }
        return owners;
    }

    private static byte[] packOwnerIds(String[] owners) {
        java.util.LinkedHashMap<String, Integer> ids = buildOwnerIds(owners);
        byte[] packed = new byte[owners.length];
        for (int i = 0; i < owners.length; i++) {
            String owner = owners[i];
            packed[i] = owner == null || "none".equals(owner)
                    ? 0
                    : (byte) ids.get(owner).intValue();
        }
        return packed;
    }

    private static String[] packOwnerTable(String[] owners) {
        java.util.LinkedHashMap<String, Integer> ids = buildOwnerIds(owners);
        return ids.keySet().toArray(String[]::new);
    }

    private static java.util.LinkedHashMap<String, Integer> buildOwnerIds(String[] owners) {
        java.util.LinkedHashMap<String, Integer> ids = new java.util.LinkedHashMap<>();
        for (String owner : owners) {
            if (owner == null || "none".equals(owner) || ids.containsKey(owner)) {
                continue;
            }
            int id = ids.size() + 1;
            if (id > 255) {
                throw new IllegalArgumentException("too many palette owners in snapshot: " + id);
            }
            ids.put(owner, id);
        }
        return ids;
    }
}

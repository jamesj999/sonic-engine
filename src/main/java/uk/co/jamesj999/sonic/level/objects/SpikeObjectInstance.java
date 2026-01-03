package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class SpikeObjectInstance extends BoxObjectInstance implements SolidObjectProvider, SolidObjectListener {
    private static final int[] WIDTH_PIXELS = {
            0x10, 0x20, 0x30, 0x40,
            0x10, 0x10, 0x10, 0x10
    };
    private static final int[] Y_RADIUS = {
            0x10, 0x10, 0x10, 0x10,
            0x10, 0x20, 0x30, 0x40
    };

    public SpikeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 1.0f, 0.2f, 0.2f, false);
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }
        if (!shouldHurt(contact)) {
            return;
        }
        // Placeholder: minimal knockback. TODO: replace with full hurt/invincibility handling.
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setYSpeed((short) -0x200);
    }

    @Override
    protected int getHalfWidth() {
        return getEntryValue(WIDTH_PIXELS);
    }

    @Override
    protected int getHalfHeight() {
        return getEntryValue(Y_RADIUS);
    }

    private int getEntryValue(int[] table) {
        int entry = (spawn.subtype() >> 4) & 0xF;
        if (entry < 0) {
            entry = 0;
        }
        if (entry >= table.length) {
            entry = table.length - 1;
        }
        return table[entry];
    }

    @Override
    public SolidObjectParams getSolidParams() {
        int widthPixels = getEntryValue(WIDTH_PIXELS);
        int yRadius = getEntryValue(Y_RADIUS);
        int d1 = widthPixels + 0x0B;
        int d2 = yRadius;
        int d3 = yRadius + 1;
        return new SolidObjectParams(d1, d2, d3);
    }

    private boolean shouldHurt(SolidContact contact) {
        if (isSideways()) {
            return contact.touchSide();
        }
        if (isUpsideDown()) {
            return contact.touchBottom();
        }
        return contact.standing();
    }

    private boolean isSideways() {
        int entry = (spawn.subtype() >> 4) & 0xF;
        return entry >= 4;
    }

    private boolean isUpsideDown() {
        return (spawn.renderFlags() & 0x2) != 0;
    }
}

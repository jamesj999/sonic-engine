package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class SpringObjectInstance extends BoxObjectInstance implements SolidObjectProvider, SolidObjectListener {
    private static final int HALF_SIZE_VERTICAL = 0x10;
    private static final int HALF_SIZE_HORIZONTAL = 0x08;

    public SpringObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 8, 8, 1.0f, 0.85f, 0.1f, false);
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }
        if (isHorizontal()) {
            if (!contact.touchSide()) {
                return;
            }
            int strength = getStrength();
            if (isFlippedHorizontal()) {
                strength = -strength;
            }
            player.setAir(true);
            player.setGSpeed((short) 0);
            player.setXSpeed((short) strength);
            return;
        }
        if (isDown()) {
            if (!contact.touchBottom()) {
                return;
            }
            player.setAir(true);
            player.setGSpeed((short) 0);
            player.setYSpeed((short) Math.abs(getStrength()));
            return;
        }
        if (!contact.standing()) {
            return;
        }
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setYSpeed((short) -Math.abs(getStrength()));
    }

    @Override
    protected int getHalfWidth() {
        return isHorizontal() ? HALF_SIZE_HORIZONTAL : HALF_SIZE_VERTICAL;
    }

    @Override
    protected int getHalfHeight() {
        return isHorizontal() ? HALF_SIZE_HORIZONTAL : HALF_SIZE_VERTICAL;
    }

    private boolean isHorizontal() {
        int subtypeGroup = (spawn.subtype() >> 3) & 0xE;
        return subtypeGroup == 0x2;
    }

    private boolean isDown() {
        int subtypeGroup = (spawn.subtype() >> 3) & 0xE;
        return subtypeGroup == 0x4 || subtypeGroup == 0x8;
    }

    private int getStrength() {
        return (spawn.subtype() & 0x02) != 0 ? 0x0A00 : 0x1000;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(0x1B, 0x08, 0x10);
    }
}

package uk.co.jamesj999.sonic.game.sonic2.objects;
import uk.co.jamesj999.sonic.level.objects.*;

public class PlatformObjectInstance extends BoxObjectInstance {
    private static final int[] WIDTH_PIXELS = {
            0x20, 0x20, 0x20, 0x40, 0x30
    };
    private static final int HALF_HEIGHT = 8;

    public PlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 32, 8, 0.35f, 0.7f, 1.0f, false);
    }

    @Override
    protected int getHalfWidth() {
        int index = (spawn.subtype() >> 3) & 0xE;
        index /= 2;
        if (index < 0) {
            index = 0;
        }
        if (index >= WIDTH_PIXELS.length) {
            index = WIDTH_PIXELS.length - 1;
        }
        return WIDTH_PIXELS[index];
    }

    @Override
    protected int getHalfHeight() {
        return HALF_HEIGHT;
    }
}

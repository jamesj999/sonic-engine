package com.openggf.game.sonic2.objects;
import com.openggf.level.objects.BoxObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

public class PlatformObjectInstance extends BoxObjectInstance implements RewindRecreatable {
    private static final int[] WIDTH_PIXELS = {
            0x20, 0x20, 0x20, 0x40, 0x30
    };
    private static final int HALF_HEIGHT = 8;

    public PlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 32, 8, 0.35f, 0.7f, 1.0f, false);
    }

    @Override
    public PlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new PlatformObjectInstance(ctx.spawn(), getName());
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

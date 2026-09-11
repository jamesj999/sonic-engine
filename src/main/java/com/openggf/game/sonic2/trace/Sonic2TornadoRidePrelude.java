package com.openggf.game.sonic2.trace;

import com.openggf.game.sonic2.objects.TornadoObjectInstance;

/**
 * Derives the small native pre-gameplay seeds needed by S2 SCZ/WFZ
 * level-select traces before the first compared Level_MainLoop frame.
 *
 * <p>These values are formulas from the Sonic 2 disassembly, not trace
 * hydration. Player Y subpixel comes from locked-control airborne
 * {@code ObjectMoveAndFall}: it moves by the old {@code y_vel}, then adds
 * gravity {@code $38} (docs/s2disasm/s2.asm:29967-29981). The later platform
 * landing/carry path rewrites integer {@code y_pos} only, preserving
 * {@code y_sub} (s2.asm:35368, 35402-35424).
 *
 * <p>SCZ Tornado subpixel comes from ObjB2's initial {@code ObjB2_Move_vert}
 * bob: {@code y_vel=$200}, then {@code -$20} before each active move
 * (s2.asm:78850-78884). WFZ start has no equivalent vertical seed
 * (s2.asm:78368-78385).
 */
public final class Sonic2TornadoRidePrelude {

    private static final int S2_GRAVITY = 0x38;
    private static final int TORNADO_INITIAL_Y_SPEED = 0x200;
    private static final int TORNADO_Y_SPEED_STEP = 0x20;
    private static final int SCZ_PLAYER_FALL_TICKS = 4;
    private static final int WFZ_PLAYER_FALL_TICKS = 2;
    private static final int SCZ_TORNADO_BOB_TICKS = 11;

    private Sonic2TornadoRidePrelude() {
    }

    public static Seed forTornado(TornadoObjectInstance tornado) {
        if (tornado == null) {
            return new Seed(0, 0);
        }
        if (tornado.isSczRideStartPreludeObject()) {
            return new Seed(
                    playerFallSubpixel(SCZ_PLAYER_FALL_TICKS),
                    tornadoBobSubpixel8(SCZ_TORNADO_BOB_TICKS));
        }
        if (tornado.isWfzStartRideStartPreludeObject()) {
            return new Seed(
                    playerFallSubpixel(WFZ_PLAYER_FALL_TICKS),
                    0);
        }
        return new Seed(0, 0);
    }

    static int playerFallSubpixel(int fallTicks) {
        int yVelocity = 0;
        int ySubpixel = 0;
        for (int i = 0; i < fallTicks; i++) {
            ySubpixel = (ySubpixel + (yVelocity << 8)) & 0xFFFF;
            yVelocity = (yVelocity + S2_GRAVITY) & 0xFFFF;
        }
        return ySubpixel;
    }

    static int tornadoBobSubpixel8(int activeTicks) {
        int yVelocity = TORNADO_INITIAL_Y_SPEED;
        int ySubpixel16 = 0;
        for (int i = 0; i < activeTicks; i++) {
            if (yVelocity > -0x100) {
                yVelocity -= TORNADO_Y_SPEED_STEP;
            }
            ySubpixel16 = (ySubpixel16 + (yVelocity << 8)) & 0xFFFF;
        }
        return ySubpixel16 >>> 8;
    }

    public record Seed(int playerYSubpixel, int tornadoYSubpixel8) {
    }
}

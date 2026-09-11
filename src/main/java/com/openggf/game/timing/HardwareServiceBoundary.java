package com.openggf.game.timing;

/**
 * Ordered production service points at which hardware readiness becomes visible.
 *
 * <p>The order is the ROM's, not the source order of {@code LevelLoop}.
 * {@code Process_Kos_Queue} (docs/skdisasm/sonic3k.asm:7887) is written at the
 * head of the loop body but runs ahead of {@code Wait_VSync} (7888) and the
 * {@code addq.w #1,(Level_frame_counter)} that follows it (7889), so it shares
 * the frame-counter value of the iteration whose {@code Process_Sprites} (7893),
 * {@code ScreenEvents} (7898) and {@code Process_Kos_Module_Queue} (7908)
 * already ran. Both queue services are therefore loop-tail work for that same
 * frame, after its objects -- {@code PRE_MAIN_LOOP} is the last boundary of a
 * frame, not the first.
 */
public enum HardwareServiceBoundary {
    VINT_SERVICE,
    POST_OBJECTS,
    PRE_MAIN_LOOP;

    public static HardwareServiceBoundary fromWireName(String wireName) {
        return switch (wireName) {
            case "vint_service" -> VINT_SERVICE;
            case "pre_main_loop" -> PRE_MAIN_LOOP;
            case "post_objects" -> POST_OBJECTS;
            default -> throw new IllegalArgumentException("unknown boundary: " + wireName);
        };
    }
}

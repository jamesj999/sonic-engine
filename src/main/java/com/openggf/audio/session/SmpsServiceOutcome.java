package com.openggf.audio.session;

public enum SmpsServiceOutcome {
    ORDINARY,
    LOAD_PENDING,
    SERVICE_IN_FLIGHT,
    GLOBAL_STOP_CONSUMED,
    /**
     * The driver's SEGA PCM transport held the bus with interrupts disabled,
     * so this V-int ran no update at all (Sound/Z80 Sound Driver.asm:4372-4424).
     */
    SEGA_PCM_TRANSPORT
}

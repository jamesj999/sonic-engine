package com.openggf.game.timing;

/** Hardware work classes whose final readiness may be recorded for replay. */
public enum HardwareWorkKind {
    KOS_MODULE_QUEUE,
    KOS_DECOMPRESSION_QUEUE,
    /**
     * Sonic 1's {@code RunPLC} arming edge (docs/s1disasm/sonic.asm:1379).
     * The recorded readiness is the moment the ROM accepts the Nemesis PLC
     * FIFO head for decompression, not the delivery of any decoded art --
     * that payload stays owned by the production PLC pipeline.
     */
    NEMESIS_PLC_QUEUE;

    public static HardwareWorkKind fromWireName(String wireName) {
        if ("kos_module_queue".equals(wireName)) {
            return KOS_MODULE_QUEUE;
        }
        if ("kos_decompression_queue".equals(wireName)) {
            return KOS_DECOMPRESSION_QUEUE;
        }
        if ("nemesis_plc_queue".equals(wireName)) {
            return NEMESIS_PLC_QUEUE;
        }
        throw new IllegalArgumentException("unknown kind: " + wireName);
    }
}

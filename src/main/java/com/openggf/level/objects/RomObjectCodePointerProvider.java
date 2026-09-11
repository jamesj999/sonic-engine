package com.openggf.level.objects;

/**
 * Exposes the ROM object-code pointer high word stored in word 0 of an S3K
 * object SST slot. S3K sidekick CPU {@code sub_13EFC} compares and refreshes
 * {@code Tails_CPU_interact} from this word while Tails stands on an object
 * (docs/skdisasm/sonic3k.asm:26816-26843).
 */
public interface RomObjectCodePointerProvider {
    int romObjectCodePointerHighWord();
}

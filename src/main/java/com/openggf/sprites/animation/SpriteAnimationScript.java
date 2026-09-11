package com.openggf.sprites.animation;

import java.util.List;

/**
 * One parsed animation script.
 *
 * <p>{@code trailingBytes} carries the raw ROM bytes that follow this script's
 * frame list, starting at its own terminator byte. The 68000 animation handlers
 * index the script with {@code move.b 1(a1,d1.w),d0} and never bounds-check
 * {@code anim_frame}, so an {@code anim_frame} left behind by a routine with a
 * longer frame table walks straight off the end of the selected script and into
 * whatever the assembler emitted next. {@code trailingBytes} is what makes that
 * flat read modellable without inventing a value; it is empty for scripts that
 * are synthesised in code rather than parsed out of a ROM block.
 */
public record SpriteAnimationScript(
        int delay,
        List<Integer> frames,
        SpriteAnimationEndAction endAction,
        int endParam,
        List<Integer> trailingBytes
) {
    public SpriteAnimationScript(
            int delay,
            List<Integer> frames,
            SpriteAnimationEndAction endAction,
            int endParam
    ) {
        this(delay, frames, endAction, endParam, List.of());
    }

    /**
     * Reads the script byte the ROM handler would fetch for {@code anim_frame}
     * {@code index}, continuing past this script's own frame list into
     * {@link #trailingBytes} exactly as the unchecked {@code 1(a1,d1.w)} read
     * does. Returns -1 when the captured window does not reach that far.
     */
    public int flatByteAt(int index) {
        if (index < 0) {
            return -1;
        }
        if (index < frames.size()) {
            return frames.get(index);
        }
        int tailIndex = index - frames.size();
        if (tailIndex >= trailingBytes.size()) {
            return -1;
        }
        return trailingBytes.get(tailIndex);
    }
}

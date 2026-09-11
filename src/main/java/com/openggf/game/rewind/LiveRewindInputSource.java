package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.PlayerInputState;
import com.openggf.debug.playback.Bk2FrameInput;

import java.util.Arrays;
import java.util.Objects;

/**
 * Growing input source for live gameplay rewind.
 *
 * <p>Frame 0 is a synthetic neutral input row that matches the initial
 * keyframe captured by {@link RewindController}. Each normal level tick appends
 * one row after gameplay has advanced, then {@code recordExternalStep()} moves
 * the rewind cursor onto that row.
 */
public final class LiveRewindInputSource implements InputSource {

    private Bk2FrameInput[] frames = new Bk2FrameInput[128];
    private int head;
    private int size;
    private int baseFrame;

    public LiveRewindInputSource() {
        append(neutralFrameInput(0));
    }

    public void appendFrame(InputHandler input, SonicConfigurationService config) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(config, "config");
        int frameIndex = baseFrame + size;
        PlayerInputState p1 = input.logical().player1();
        PlayerInputState p2 = input.logical().player2();
        append(new Bk2FrameInput(
                frameIndex,
                p1.heldMask(),
                p1.actionHeldMask(),
                p1.startHeld(),
                p2.heldMask(),
                p2.actionHeldMask(),
                p2.startHeld(),
                input.isKeyPressed(config.getInt(SonicConfiguration.DEBUG_MODE_KEY)),
                input.isShiftDown(),
                input.isControlDown(),
                input.isAltDown(),
                input.isSuperDown(),
                "live:" + frameIndex));
    }

    public void discardAfter(int frame) {
        int keepCount = Math.max(1, Math.min(size, frame - baseFrame + 1));
        while (size > keepCount) {
            frames[(head + --size) % frames.length] = null;
        }
    }

    public void discardBefore(int frame) {
        int removeCount = Math.min(Math.max(0, frame - baseFrame), size - 1);
        if (removeCount <= 0) {
            return;
        }
        for (int i = 0; i < removeCount; i++) {
            frames[(head + i) % frames.length] = null;
        }
        head = (head + removeCount) % frames.length;
        size -= removeCount;
        baseFrame += removeCount;
    }

    public void resetToFrameZero() {
        clear();
        baseFrame = 0;
        append(neutralFrameInput(0));
    }

    public void retainOnlyFrame(int frame) {
        if (frame < earliestFrame() || frame >= frameCount()) {
            resetToSingleNeutralFrame(frame);
            return;
        }
        Bk2FrameInput retained = read(frame);
        clear();
        baseFrame = frame;
        append(retained);
    }

    public int earliestFrame() {
        return baseFrame;
    }

    @Override
    public int frameCount() {
        return baseFrame + size;
    }

    @Override
    public Bk2FrameInput read(int frame) {
        int index = frame - baseFrame;
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Live rewind frame " + frame
                    + " outside " + baseFrame + ".." + (frameCount() - 1));
        }
        return frames[(head + index) % frames.length];
    }

    private void resetToSingleNeutralFrame(int frame) {
        clear();
        baseFrame = frame;
        append(neutralFrameInput(frame));
    }

    private void append(Bk2FrameInput frame) {
        if (size == frames.length) {
            Bk2FrameInput[] grown = new Bk2FrameInput[frames.length * 2];
            int tail = Math.min(size, frames.length - head);
            System.arraycopy(frames, head, grown, 0, tail);
            System.arraycopy(frames, 0, grown, tail, size - tail);
            frames = grown;
            head = 0;
        }
        frames[(head + size++) % frames.length] = frame;
    }

    private void clear() {
        Arrays.fill(frames, null);
        head = 0;
        size = 0;
    }

    private static Bk2FrameInput neutralFrameInput(int frame) {
        return new Bk2FrameInput(frame, 0, 0, false, 0, 0, false,
                false, false, false, false, false, "live:" + frame);
    }
}

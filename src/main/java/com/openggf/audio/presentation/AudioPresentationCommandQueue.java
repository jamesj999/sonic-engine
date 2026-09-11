package com.openggf.audio.presentation;

import java.util.Objects;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A fixed-capacity command ledger with structural admission reserve.
 */
public final class AudioPresentationCommandQueue {
    public static final int CAPACITY = 256;
    public static final int STRUCTURAL_RESERVE = 32;
    private static final int NORMAL_CAPACITY = CAPACITY - STRUCTURAL_RESERVE;

    private final AudioPresentationCommand[] entries =
            new AudioPresentationCommand[CAPACITY];
    private final BooleanSupplier renderingActive;
    private int size;
    private PendingBatch activeBatch;

    static final class PendingBatch {
        private final AudioPresentationCommandQueue owner;
        private final int capturedSize;
        private State state = State.OPEN;

        private PendingBatch(
                AudioPresentationCommandQueue owner, int capturedSize) {
            this.owner = owner;
            this.capturedSize = capturedSize;
        }

        private enum State {
            OPEN,
            APPLIED,
            PREPARED,
            COMMITTED,
            ROLLED_BACK
        }
    }

    public AudioPresentationCommandQueue() {
        this(() -> false);
    }

    public AudioPresentationCommandQueue(BooleanSupplier renderingActive) {
        this.renderingActive = Objects.requireNonNull(renderingActive, "renderingActive");
    }

    public void submit(AudioPresentationCommand command,
                       BooleanSupplier ownerThreadBoundary,
                       Consumer<AudioPresentationCommand> synchronousApply) {
        Objects.requireNonNull(synchronousApply, "synchronousApply");
        submitInternal(command, ownerThreadBoundary,
                () -> drainAtOwnerBoundary(
                        ownerThreadBoundary, synchronousApply));
    }

    /**
     * Authoritative submission seam whose pressure fallback asks the
     * presentation owner to drain the complete queue transactionally.
     */
    public void submit(AudioPresentationCommand command,
                       BooleanSupplier ownerThreadBoundary,
                       Runnable synchronousDrain) {
        Objects.requireNonNull(synchronousDrain, "synchronousDrain");
        submitInternal(command, ownerThreadBoundary,
                () -> drainAtOwnerBoundary(
                        ownerThreadBoundary, synchronousDrain));
    }

    private void submitInternal(
            AudioPresentationCommand command,
            BooleanSupplier ownerThreadBoundary,
            Runnable pressureDrain) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(ownerThreadBoundary, "ownerThreadBoundary");
        assertNotRendering();
        assertNoActiveBatch();

        if (coalesce(command)) {
            return;
        }

        if (command.structural()) {
            admitStructural(command, pressureDrain);
            return;
        }

        if (size >= NORMAL_CAPACITY) {
            if (command.droppableSampleStart()) {
                return;
            }
            pressureDrain.run();
        }
        entries[size++] = command;
    }

    public void applyPending(Consumer<AudioPresentationCommand> applier) {
        Objects.requireNonNull(applier, "applier");
        assertNotRendering();
        assertNoActiveBatch();
        drain(applier);
    }

    /**
     * Applies one immutable batch and consumes it only after every command
     * succeeds. The presentation producer pairs this with its composite
     * rollback token, so a failed batch is retryable in full.
     */
    public void applyPendingAtomically(
            Consumer<AudioPresentationCommand> applier) {
        Objects.requireNonNull(applier, "applier");
        PendingBatch batch = capturePendingBatch();
        try {
            applyPendingBatch(batch, applier);
            preparePendingBatchCommit(batch);
            commitPendingBatch(batch);
        } catch (RuntimeException failure) {
            if (activeBatch == batch) {
                rollbackPendingBatch(batch);
            }
            throw failure;
        }
    }

    /** Captures the exact retry prefix without consuming any command. */
    PendingBatch capturePendingBatch() {
        assertNotRendering();
        assertNoActiveBatch();
        PendingBatch batch = new PendingBatch(this, size);
        activeBatch = batch;
        return batch;
    }

    /** Applies the captured prefix while retaining it in the ledger. */
    void applyPendingBatch(
            PendingBatch batch,
            Consumer<AudioPresentationCommand> applier) {
        Objects.requireNonNull(applier, "applier");
        PendingBatch resolved = requireActiveBatch(
                batch, PendingBatch.State.OPEN);
        for (int index = 0; index < resolved.capturedSize; index++) {
            applier.accept(entries[index]);
        }
        resolved.state = PendingBatch.State.APPLIED;
    }

    /** Validates the no-fail queue commit before composite participants commit. */
    void preparePendingBatchCommit(PendingBatch batch) {
        PendingBatch resolved = requireActiveBatch(
                batch, PendingBatch.State.APPLIED);
        if (size != resolved.capturedSize) {
            throw new IllegalStateException(
                    "audio command queue changed during a captured batch");
        }
        resolved.state = PendingBatch.State.PREPARED;
    }

    /** Consumes a previously prepared prefix after the composite commit. */
    void commitPendingBatch(PendingBatch batch) {
        PendingBatch resolved = requireActiveBatch(
                batch, PendingBatch.State.PREPARED);
        java.util.Arrays.fill(entries, 0, resolved.capturedSize, null);
        size = 0;
        resolved.state = PendingBatch.State.COMMITTED;
        activeBatch = null;
    }

    /** Leaves the exact prefix in place for retry and releases the batch. */
    void rollbackPendingBatch(PendingBatch batch) {
        PendingBatch resolved = requireActiveBatch(batch);
        if (resolved.state == PendingBatch.State.COMMITTED
                || resolved.state == PendingBatch.State.ROLLED_BACK) {
            throw new IllegalStateException(
                    "audio command batch is already consumed");
        }
        resolved.state = PendingBatch.State.ROLLED_BACK;
        activeBatch = null;
    }

    public int size() {
        return size;
    }

    List<AudioPresentationCommand> snapshotCommands() {
        return List.of(java.util.Arrays.copyOf(entries, size));
    }

    private void admitStructural(
            AudioPresentationCommand command,
            Runnable pressureDrain) {
        if (size == CAPACITY && !removeOldestDroppableSampleStart()) {
            pressureDrain.run();
        }
        entries[size++] = command;
    }

    private boolean coalesce(AudioPresentationCommand command) {
        Object key = command.coalescingKey();
        if (key == null) {
            return false;
        }
        for (int index = size - 1; index >= 0; index--) {
            AudioPresentationCommand pending = entries[index];
            Object pendingKey = pending.coalescingKey();
            if (pendingKey == null) {
                break;
            }
            if (pending.getClass() == command.getClass()
                    && Objects.equals(pendingKey, key)) {
                entries[index] = command;
                return true;
            }
        }
        return false;
    }

    private boolean removeOldestDroppableSampleStart() {
        for (int index = 0; index < size; index++) {
            if (entries[index].droppableSampleStart()) {
                int remaining = size - index - 1;
                if (remaining > 0) {
                    System.arraycopy(entries, index + 1, entries, index, remaining);
                }
                entries[--size] = null;
                return true;
            }
        }
        return false;
    }

    private void drainAtOwnerBoundary(BooleanSupplier ownerThreadBoundary,
                                      Consumer<AudioPresentationCommand> synchronousApply) {
        if (!ownerThreadBoundary.getAsBoolean()) {
            throw new IllegalStateException(
                    "full audio command queue may drain only at its owner boundary");
        }
        assertNotRendering();
        drain(synchronousApply);
    }

    private void drainAtOwnerBoundary(
            BooleanSupplier ownerThreadBoundary,
            Runnable synchronousDrain) {
        if (!ownerThreadBoundary.getAsBoolean()) {
            throw new IllegalStateException(
                    "full audio command queue may drain only at its owner boundary");
        }
        assertNotRendering();
        int before = size;
        synchronousDrain.run();
        if (before > 0 && size >= before) {
            throw new IllegalStateException(
                    "owner-boundary audio drain made no progress");
        }
    }

    private void drain(Consumer<AudioPresentationCommand> applier) {
        while (size > 0) {
            AudioPresentationCommand command = entries[0];
            // Publish before consuming the ledger entry. Registry appliers
            // resolve/materialize before mutating live state, so a thrown
            // command remains the first retryable entry while every command
            // whose apply returned normally is removed exactly once.
            applier.accept(command);
            int remaining = size - 1;
            if (remaining > 0) {
                System.arraycopy(entries, 1, entries, 0, remaining);
            }
            entries[--size] = null;
        }
    }

    private void assertNotRendering() {
        if (renderingActive.getAsBoolean()) {
            throw new IllegalStateException(
                    "audio commands cannot be submitted or drained during rendering");
        }
    }

    private void assertNoActiveBatch() {
        if (activeBatch != null) {
            throw new IllegalStateException(
                    "an audio command batch is already active");
        }
    }

    private PendingBatch requireActiveBatch(PendingBatch batch) {
        if (batch == null || batch.owner != this || activeBatch != batch) {
            throw new IllegalArgumentException(
                    "audio command batch belongs to another transaction");
        }
        return batch;
    }

    private PendingBatch requireActiveBatch(
            PendingBatch batch, PendingBatch.State expected) {
        PendingBatch resolved = requireActiveBatch(batch);
        if (resolved.state != expected) {
            throw new IllegalStateException(
                    "audio command batch is not " + expected);
        }
        return resolved;
    }
}

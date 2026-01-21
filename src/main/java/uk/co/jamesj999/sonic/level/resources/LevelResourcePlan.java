package uk.co.jamesj999.sonic.level.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines a loading plan for level resources, supporting overlay composition.
 *
 * <p>Most zones use simple single-source loading (one LoadOp per resource type at offset 0).
 * Some zones like Hill Top Zone (HTZ) require compositing multiple sources:
 * <ul>
 *   <li>HTZ patterns (8x8): EHZ_HTZ base (offset 0) + HTZ_Supp overlay (offset 0x3F80)</li>
 *   <li>HTZ chunks (16x16): EHZ base (offset 0) + HTZ overlay (offset 0x0980)</li>
 *   <li>HTZ blocks (128x128): Shared EHZ_HTZ (no overlay)</li>
 * </ul>
 *
 * <p><strong>Terminology Note:</strong> SonLVL uses inverted terminology from this engine:
 * <ul>
 *   <li>SonLVL "blocks" (16x16) = Engine "chunks"</li>
 *   <li>SonLVL "chunks" (128x128) = Engine "blocks"</li>
 * </ul>
 *
 * <p>The overlay system writes data from each LoadOp into the destination buffer
 * at the specified byte offset. Operations are applied in list order, so overlays
 * should come after base loads.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Simple zone (most zones)
 * LevelResourcePlan ehzPlan = LevelResourcePlan.builder()
 *     .addPatternOp(LoadOp.kosinskiBase(0x095C24))
 *     .addChunkOp(LoadOp.kosinskiBase(0x094E74))
 *     .addBlockOp(LoadOp.kosinskiBase(0x099D34))
 *     .setPrimaryCollision(LoadOp.kosinskiBase(0x044E50))
 *     .setSecondaryCollision(LoadOp.kosinskiBase(0x044F40))
 *     .build();
 *
 * // HTZ with overlays
 * LevelResourcePlan htzPlan = LevelResourcePlan.builder()
 *     .addPatternOp(LoadOp.kosinskiBase(0x095C24))             // EHZ_HTZ base patterns
 *     .addPatternOp(LoadOp.kosinskiOverlay(0x098AB4, 0x3F80))  // HTZ_Supp patterns
 *     .addChunkOp(LoadOp.kosinskiBase(0x094E74))               // EHZ base chunks (16x16)
 *     .addChunkOp(LoadOp.kosinskiOverlay(0x0985A4, 0x0980))    // HTZ chunks overlay
 *     .addBlockOp(LoadOp.kosinskiBase(0x099D34))               // Shared EHZ_HTZ blocks (128x128)
 *     .setPrimaryCollision(LoadOp.kosinskiBase(0x044E50))
 *     .setSecondaryCollision(LoadOp.kosinskiBase(0x044F40))
 *     .build();
 * }</pre>
 */
public class LevelResourcePlan {

    private final List<LoadOp> patternOps;
    private final List<LoadOp> blockOps;
    private final List<LoadOp> chunkOps;
    private final LoadOp primaryCollision;
    private final LoadOp secondaryCollision;

    private LevelResourcePlan(Builder builder) {
        this.patternOps = Collections.unmodifiableList(new ArrayList<>(builder.patternOps));
        this.blockOps = Collections.unmodifiableList(new ArrayList<>(builder.blockOps));
        this.chunkOps = Collections.unmodifiableList(new ArrayList<>(builder.chunkOps));
        this.primaryCollision = builder.primaryCollision;
        this.secondaryCollision = builder.secondaryCollision;
    }

    /**
     * Returns the list of pattern loading operations (8×8 tiles).
     * Operations are applied in order; overlay ops should follow base ops.
     */
    public List<LoadOp> getPatternOps() {
        return patternOps;
    }

    /**
     * Returns the list of block loading operations (128×128 tile blocks).
     * Operations are applied in order; overlay ops should follow base ops.
     */
    public List<LoadOp> getBlockOps() {
        return blockOps;
    }

    /**
     * Returns the list of chunk loading operations (16×16 chunks).
     * Most zones have only one chunk source.
     */
    public List<LoadOp> getChunkOps() {
        return chunkOps;
    }

    /**
     * Returns the primary collision index loading operation.
     */
    public LoadOp getPrimaryCollision() {
        return primaryCollision;
    }

    /**
     * Returns the secondary collision index loading operation.
     */
    public LoadOp getSecondaryCollision() {
        return secondaryCollision;
    }

    /**
     * Returns true if this plan uses overlay composition for patterns.
     */
    public boolean hasPatternOverlays() {
        return patternOps.size() > 1;
    }

    /**
     * Returns true if this plan uses overlay composition for blocks (128x128).
     */
    public boolean hasBlockOverlays() {
        return blockOps.size() > 1;
    }

    /**
     * Returns true if this plan uses overlay composition for chunks (16x16).
     */
    public boolean hasChunkOverlays() {
        return chunkOps.size() > 1;
    }

    /**
     * Creates a simple plan with single sources for all resource types.
     * This is a convenience method for zones that don't use overlays.
     */
    public static LevelResourcePlan simple(
            int patternsAddr,
            int blocksAddr,
            int chunksAddr,
            int primaryCollisionAddr,
            int secondaryCollisionAddr) {
        return builder()
                .addPatternOp(LoadOp.kosinskiBase(patternsAddr))
                .addBlockOp(LoadOp.kosinskiBase(blocksAddr))
                .addChunkOp(LoadOp.kosinskiBase(chunksAddr))
                .setPrimaryCollision(LoadOp.kosinskiBase(primaryCollisionAddr))
                .setSecondaryCollision(LoadOp.kosinskiBase(secondaryCollisionAddr))
                .build();
    }

    /**
     * Creates a new builder for constructing a LevelResourcePlan.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for LevelResourcePlan.
     */
    public static class Builder {
        private final List<LoadOp> patternOps = new ArrayList<>();
        private final List<LoadOp> blockOps = new ArrayList<>();
        private final List<LoadOp> chunkOps = new ArrayList<>();
        private LoadOp primaryCollision;
        private LoadOp secondaryCollision;

        private Builder() {
        }

        /**
         * Adds a pattern loading operation.
         */
        public Builder addPatternOp(LoadOp op) {
            patternOps.add(op);
            return this;
        }

        /**
         * Adds a block loading operation.
         */
        public Builder addBlockOp(LoadOp op) {
            blockOps.add(op);
            return this;
        }

        /**
         * Adds a chunk loading operation.
         */
        public Builder addChunkOp(LoadOp op) {
            chunkOps.add(op);
            return this;
        }

        /**
         * Sets the primary collision index loading operation.
         */
        public Builder setPrimaryCollision(LoadOp op) {
            this.primaryCollision = op;
            return this;
        }

        /**
         * Sets the secondary collision index loading operation.
         */
        public Builder setSecondaryCollision(LoadOp op) {
            this.secondaryCollision = op;
            return this;
        }

        /**
         * Builds the LevelResourcePlan.
         */
        public LevelResourcePlan build() {
            if (patternOps.isEmpty()) {
                throw new IllegalStateException("At least one pattern operation is required");
            }
            if (blockOps.isEmpty()) {
                throw new IllegalStateException("At least one block operation is required");
            }
            if (chunkOps.isEmpty()) {
                throw new IllegalStateException("At least one chunk operation is required");
            }
            return new LevelResourcePlan(this);
        }
    }
}

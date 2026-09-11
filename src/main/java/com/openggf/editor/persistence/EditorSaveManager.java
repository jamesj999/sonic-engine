package com.openggf.editor.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.GameId;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.MutableLevel;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Logger;

import static java.security.MessageDigest.getInstance;

public final class EditorSaveManager {
    private static final Logger LOG = Logger.getLogger(EditorSaveManager.class.getName());
    private static final int VERSION = 1;

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EditorSaveReader reader;

    public EditorSaveManager(Path root) {
        this.root = root;
        this.reader = file -> mapper.readValue(file.toFile(), EditorSaveEnvelope.class);
    }

    EditorSaveManager(Path root, EditorSaveReader reader) {
        this.root = root;
        this.reader = reader;
    }

    public SaveResult save(GameId gameId, int zone, int act, MutableLevel level) throws IOException {
        Path file = editPath(gameId, zone, act);
        Files.createDirectories(file.getParent());
        EditorSavePayload payload = buildPayload(level);
        String payloadJson = mapper.writeValueAsString(payload);
        EditorSaveEnvelope envelope = new EditorSaveEnvelope(
                VERSION,
                gameId.code(),
                zone,
                act,
                Instant.now().toString(),
                payload,
                sha256(payloadJson));

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            mapper.writeValue(tmp.toFile(), envelope);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        level.markSaved();
        return new SaveResult(true, file, envelope.hash());
    }

    public ApplyResult tryApplyEdits(GameId gameId, int zone, int act, MutableLevel level) {
        Path file = editPath(gameId, zone, act);
        if (!Files.exists(file)) {
            return ApplyResult.NONE;
        }
        if (!supportsRuntimeEditApply(gameId)) {
            LOG.fine("Skipping persisted " + gameId + " editor edits until runtime overlays support MutableLevel");
            return ApplyResult.UNSUPPORTED;
        }
        try {
            EditorSaveEnvelope envelope = reader.read(file);
            if (envelope.version() != VERSION) {
                quarantine(file, "unsupported editor save version " + envelope.version());
                return ApplyResult.QUARANTINED;
            }
            if (!gameId.code().equals(envelope.gameCode()) || envelope.zone() != zone || envelope.act() != act) {
                return ApplyResult.MISMATCH;
            }
            EditorSavePayload payload = envelope.payload();
            String actual = sha256(mapper.writeValueAsString(payload));
            if (!actual.equals(envelope.hash())) {
                quarantine(file, "hash mismatch");
                return ApplyResult.QUARANTINED;
            }
            applyPayload(payload, level);
            level.markSaved();
            return ApplyResult.APPLIED;
        } catch (JsonProcessingException | RuntimeException ex) {
            try {
                quarantine(file, ex.getMessage());
            } catch (IOException quarantineError) {
                LOG.warning("Failed to quarantine editor save " + file + ": " + quarantineError.getMessage());
            }
            return ApplyResult.QUARANTINED;
        } catch (IOException ex) {
            LOG.warning("Transient I/O while reading editor save " + file + "; leaving it in place: "
                    + ex.getMessage());
            return ApplyResult.TRANSIENT_FAILURE;
        }
    }

    public Path editPath(GameId gameId, int zone, int act) {
        return root.resolve(gameId.code()).resolve("edits").resolve("zone_" + zone + "_act_" + act + ".json");
    }

    public boolean supportsRuntimeEditApply(GameId gameId) {
        return gameId != GameId.S3K;
    }

    private EditorSavePayload buildPayload(MutableLevel level) {
        List<EditorSavePayload.BlockState> blocks = new ArrayList<>();
        BitSet modifiedBlocks = level.modifiedBlocksSinceBaseline();
        for (int index = modifiedBlocks.nextSetBit(0); index >= 0; index = modifiedBlocks.nextSetBit(index + 1)) {
            if (index < level.getBlockCount()) {
                blocks.add(new EditorSavePayload.BlockState(index, level.editorSaveBlockState(index)));
            }
        }

        List<EditorSavePayload.ChunkState> chunks = new ArrayList<>();
        BitSet modifiedChunks = level.modifiedChunksSinceBaseline();
        for (int index = modifiedChunks.nextSetBit(0); index >= 0; index = modifiedChunks.nextSetBit(index + 1)) {
            if (index < level.getChunkCount()) {
                chunks.add(new EditorSavePayload.ChunkState(index, level.editorSaveChunkState(index)));
            }
        }

        List<EditorSavePayload.MapCell> mapCells = new ArrayList<>();
        BitSet modifiedMapCells = level.modifiedMapCellsSinceBaseline();
        for (int index = modifiedMapCells.nextSetBit(0); index >= 0; index = modifiedMapCells.nextSetBit(index + 1)) {
            int[] cell = level.delinearizeMapCell(index);
            int layer = cell[0];
            int x = cell[1];
            int y = cell[2];
            mapCells.add(new EditorSavePayload.MapCell(
                    layer, x, y, level.editorSaveMapCellValue(index)));
        }
        return new EditorSavePayload(blocks, chunks, mapCells);
    }

    private void applyPayload(EditorSavePayload payload, MutableLevel level) {
        if (payload == null) {
            return;
        }
        validatePayload(payload, level);
        for (EditorSavePayload.ChunkState chunkState : payload.chunks()) {
            if (chunkState.index() >= 0 && chunkState.index() < level.getChunkCount()) {
                level.restoreChunkState(chunkState.index(), chunkState.state());
            }
        }
        for (EditorSavePayload.BlockState blockState : payload.blocks()) {
            if (blockState.index() >= 0 && blockState.index() < level.getBlockCount()) {
                level.restoreBlockState(blockState.index(), blockState.state());
            }
        }
        for (EditorSavePayload.MapCell mapCell : payload.mapCells()) {
            if (mapCell.layer() >= 0 && mapCell.layer() < level.getMap().getLayerCount()
                    && mapCell.x() >= 0 && mapCell.x() < level.getMap().getWidth()
                    && mapCell.y() >= 0 && mapCell.y() < level.getMap().getHeight()) {
                if (mapCell.blockIndex() < 0 || mapCell.blockIndex() >= level.getBlockCount()
                        || mapCell.blockIndex() > 0xFF) {
                    throw new IllegalArgumentException("Invalid editor map block index "
                            + mapCell.blockIndex() + " at layer=" + mapCell.layer()
                            + " x=" + mapCell.x() + " y=" + mapCell.y());
                }
                level.setBlockInMap(mapCell.layer(), mapCell.x(), mapCell.y(), mapCell.blockIndex());
            }
        }
    }

    private void validatePayload(EditorSavePayload payload, MutableLevel level) {
        for (EditorSavePayload.ChunkState chunkState : payload.chunks()) {
            if (chunkState.state() == null) {
                throw new IllegalArgumentException("Missing editor chunk state at index " + chunkState.index());
            }
            if (chunkState.index() >= 0 && chunkState.index() < level.getChunkCount()
                    && chunkState.state().length != new Chunk().saveState().length) {
                throw new IllegalArgumentException("Invalid editor chunk state length "
                        + chunkState.state().length + " at index " + chunkState.index());
            }
        }
        for (EditorSavePayload.BlockState blockState : payload.blocks()) {
            if (blockState.state() == null) {
                throw new IllegalArgumentException("Missing editor block state at index " + blockState.index());
            }
            if (blockState.index() >= 0 && blockState.index() < level.getBlockCount()) {
                Block block = level.getBlock(blockState.index());
                if (blockState.state().length != block.saveState().length) {
                    throw new IllegalArgumentException("Invalid editor block state length "
                            + blockState.state().length + " at index " + blockState.index());
                }
            }
        }
        for (EditorSavePayload.MapCell mapCell : payload.mapCells()) {
            if (mapCell.layer() >= 0 && mapCell.layer() < level.getMap().getLayerCount()
                    && mapCell.x() >= 0 && mapCell.x() < level.getMap().getWidth()
                    && mapCell.y() >= 0 && mapCell.y() < level.getMap().getHeight()) {
                if (mapCell.blockIndex() < 0 || mapCell.blockIndex() >= level.getBlockCount()
                        || mapCell.blockIndex() > 0xFF) {
                    throw new IllegalArgumentException("Invalid editor map block index "
                            + mapCell.blockIndex() + " at layer=" + mapCell.layer()
                            + " x=" + mapCell.x() + " y=" + mapCell.y());
                }
            }
        }
    }

    private void quarantine(Path file, String reason) throws IOException {
        LOG.warning("Quarantining corrupt editor save " + file + ": " + reason);
        Files.move(file, uniqueCorruptSibling(file));
    }

    private Path uniqueCorruptSibling(Path file) {
        Path base = file.resolveSibling(file.getFileName() + ".corrupt");
        if (!Files.exists(base)) {
            return base;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            Path candidate = file.resolveSibling(file.getFileName() + ".corrupt." + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No available quarantine filename for " + file);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record SaveResult(boolean ok, Path file, String hash) {
    }

    public enum ApplyResult {
        NONE,
        APPLIED,
        QUARANTINED,
        MISMATCH,
        TRANSIENT_FAILURE,
        UNSUPPORTED
    }
}

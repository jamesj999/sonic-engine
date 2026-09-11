package com.openggf.editor.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.GameId;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Map;
import com.openggf.level.MutableLevel;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.SolidTile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestEditorSaveManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void saveAndApplyRoundTripsModifiedBlockChunkAndMapCell() throws Exception {
        MutableLevel edited = createMutableLevel();
        edited.setChunkInBlock(1, 0, 0, new ChunkDesc(2));
        edited.setBlockInMap(1, 2, 1, 2);
        EditorSaveManager manager = new EditorSaveManager(tempDir);

        EditorSaveManager.SaveResult save = manager.save(GameId.S2, 4, 0, edited);
        MutableLevel fresh = createMutableLevel();
        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 4, 0, fresh);

        assertTrue(save.ok());
        assertEquals(EditorSaveManager.ApplyResult.APPLIED, result);
        assertEquals(2, fresh.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(2, Byte.toUnsignedInt(fresh.getMap().getValue(1, 2, 1)));
        assertTrue(fresh.modifiedBlocksSinceBaseline().get(1));
        assertTrue(fresh.modifiedMapCellsSinceBaseline().get(1 * 12 + 1 * 4 + 2));
        assertFalse(fresh.isModifiedSinceLastSave());
    }

    @Test
    void saveOmitsBlockChunkAndMapCellEditsRevertedToBaseline() throws Exception {
        MutableLevel edited = createMutableLevel();
        edited.setChunkInBlock(1, 0, 0, new ChunkDesc(2));
        edited.setChunkInBlock(1, 0, 0, new ChunkDesc(0));
        edited.setBlockInMap(1, 2, 1, 2);
        edited.setBlockInMap(1, 2, 1, 0);
        edited.setPatternDescInChunk(2, 0, 0, new PatternDesc(7));
        edited.setPatternDescInChunk(2, 0, 0, new PatternDesc(0));
        EditorSaveManager manager = new EditorSaveManager(tempDir);

        EditorSaveManager.SaveResult save = manager.save(GameId.S2, 4, 0, edited);
        EditorSaveEnvelope envelope = MAPPER.readValue(save.file().toFile(), EditorSaveEnvelope.class);

        assertTrue(save.ok());
        assertTrue(envelope.payload().blocks().isEmpty());
        assertTrue(envelope.payload().chunks().isEmpty());
        assertTrue(envelope.payload().mapCells().isEmpty());
        assertFalse(edited.isModifiedSinceLastSave());
    }

    @Test
    void saveOmitsRuntimeTerrainMutationsAppliedThroughMutationSurface() throws Exception {
        MutableLevel edited = createMutableLevel();
        LevelMutationSurface surface = LevelMutationSurface.forLevel(edited);

        surface.setBlockInMap(0, 1, 1, 2);
        surface.restoreBlockState(1, new int[] { 2 });
        surface.restoreChunkState(2, chunkState(7));
        EditorSaveManager manager = new EditorSaveManager(tempDir);

        EditorSaveManager.SaveResult save = manager.save(GameId.S2, 4, 0, edited);
        EditorSaveEnvelope envelope = MAPPER.readValue(save.file().toFile(), EditorSaveEnvelope.class);

        assertEquals(2, Byte.toUnsignedInt(edited.getMap().getValue(0, 1, 1)),
                "runtime mutation must still update live gameplay terrain");
        assertEquals(2, edited.getBlock(1).getChunkDesc(0, 0).getChunkIndex(),
                "runtime block mutation must still update the live block");
        assertEquals(7, edited.getChunk(2).getPatternDesc(0, 0).getPatternIndex(),
                "runtime chunk mutation must still update the live chunk");
        assertTrue(envelope.payload().blocks().isEmpty());
        assertTrue(envelope.payload().chunks().isEmpty());
        assertTrue(envelope.payload().mapCells().isEmpty());
    }

    @Test
    void saveKeepsEditorIntentWhenRuntimeMutationTouchesAlreadyEditedTerrain() throws Exception {
        MutableLevel edited = createMutableLevel();
        edited.setBlockInMap(0, 1, 1, 1);
        edited.setChunkInBlock(1, 0, 0, new ChunkDesc(1));
        edited.setPatternDescInChunk(2, 0, 0, new PatternDesc(7));
        LevelMutationSurface surface = LevelMutationSurface.forLevel(edited);

        surface.setBlockInMap(0, 1, 1, 2);
        surface.restoreBlockState(1, new int[] { 2 });
        surface.restoreChunkState(2, chunkState(8));
        EditorSaveManager manager = new EditorSaveManager(tempDir);

        EditorSaveManager.SaveResult save = manager.save(GameId.S2, 4, 0, edited);
        EditorSaveEnvelope envelope = MAPPER.readValue(save.file().toFile(), EditorSaveEnvelope.class);

        assertEquals(2, Byte.toUnsignedInt(edited.getMap().getValue(0, 1, 1)),
                "runtime mutation must stay present in the live level");
        assertEquals(2, edited.getBlock(1).getChunkDesc(0, 0).getChunkIndex(),
                "runtime block mutation must stay present in the live level");
        assertEquals(8, edited.getChunk(2).getPatternDesc(0, 0).getPatternIndex(),
                "runtime chunk mutation must stay present in the live level");
        assertEquals(List.of(new EditorSavePayload.MapCell(0, 1, 1, 1)), envelope.payload().mapCells());
        assertEquals(1, envelope.payload().blocks().size());
        assertEquals(1, envelope.payload().blocks().get(0).state()[0]);
        assertEquals(1, envelope.payload().chunks().size());
        assertEquals(7, envelope.payload().chunks().get(0).state()[0]);
    }

    @Test
    void missingFileReturnsNone() {
        EditorSaveManager manager = new EditorSaveManager(tempDir);

        assertEquals(EditorSaveManager.ApplyResult.NONE,
                manager.tryApplyEdits(GameId.S2, 1, 0, createMutableLevel()));
    }

    @Test
    void mismatchedZoneReturnsMismatchWithoutQuarantine() throws Exception {
        MutableLevel edited = createMutableLevel();
        edited.setBlockInMap(0, 1, 1, 2);
        EditorSaveManager manager = new EditorSaveManager(tempDir);
        Path file = manager.save(GameId.S2, 1, 0, edited).file();
        Path mismatchedPath = manager.editPath(GameId.S2, 1, 1);
        Files.createDirectories(mismatchedPath.getParent());
        Files.copy(file, mismatchedPath);

        MutableLevel fresh = createMutableLevel();
        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 1, 1, fresh);

        assertEquals(EditorSaveManager.ApplyResult.MISMATCH, result);
        assertTrue(Files.exists(mismatchedPath));
        assertEquals(0, Byte.toUnsignedInt(fresh.getMap().getValue(0, 1, 1)));
    }

    @Test
    void tamperedPayloadIsQuarantined() throws Exception {
        MutableLevel edited = createMutableLevel();
        edited.setBlockInMap(0, 1, 1, 2);
        EditorSaveManager manager = new EditorSaveManager(tempDir);
        Path file = manager.save(GameId.S2, 1, 0, edited).file();
        String json = Files.readString(file);
        Files.writeString(file, json.replace("\"blockIndex\":2", "\"blockIndex\":1"));

        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 1, 0, createMutableLevel());

        assertEquals(EditorSaveManager.ApplyResult.QUARANTINED, result);
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
    }

    @Test
    void quarantinePreservesExistingEditorRecoveryCopies() throws Exception {
        MutableLevel edited = createMutableLevel();
        edited.setBlockInMap(0, 1, 1, 2);
        EditorSaveManager manager = new EditorSaveManager(tempDir);
        Path file = manager.save(GameId.S2, 1, 0, edited).file();
        Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt");
        Path nextCorrupt = file.resolveSibling(file.getFileName() + ".corrupt.1");
        Files.writeString(corrupt, "old");
        Files.writeString(file, "{ not-json");

        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 1, 0, createMutableLevel());

        assertEquals(EditorSaveManager.ApplyResult.QUARANTINED, result);
        assertFalse(Files.exists(file));
        assertEquals("old", Files.readString(corrupt));
        assertEquals("{ not-json", Files.readString(nextCorrupt));
    }

    @Test
    void transientReadIOExceptionLeavesEditorSaveInPlace() throws Exception {
        MutableLevel edited = createMutableLevel();
        edited.setBlockInMap(0, 1, 1, 2);
        EditorSaveManager writer = new EditorSaveManager(tempDir);
        Path file = writer.save(GameId.S2, 1, 0, edited).file();
        String original = Files.readString(file);

        EditorSaveManager reader = new EditorSaveManager(tempDir, path -> {
            throw new java.io.IOException("temporary lock");
        });
        MutableLevel fresh = createMutableLevel();
        EditorSaveManager.ApplyResult result = reader.tryApplyEdits(GameId.S2, 1, 0, fresh);

        assertEquals(EditorSaveManager.ApplyResult.TRANSIENT_FAILURE, result);
        assertTrue(Files.exists(file), "transient I/O must not quarantine the editor save");
        assertEquals(original, Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
        assertEquals(0, Byte.toUnsignedInt(fresh.getMap().getValue(0, 1, 1)));
    }

    @Test
    void saveUsesAtomicMoveFallbackWhenFilesystemDoesNotSupportIt() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/editor/persistence/EditorSaveManager.java"));

        assertTrue(source.contains("catch (AtomicMoveNotSupportedException"),
                "editor saves must retry without ATOMIC_MOVE on filesystems that do not support it");
        assertTrue(source.contains("Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);"),
                "fallback move must still replace the target save file");
    }

    @Test
    void outOfRangePersistedMapBlockIsQuarantined() throws Exception {
        EditorSaveManager manager = new EditorSaveManager(tempDir);
        EditorSavePayload payload = new EditorSavePayload(
                List.of(),
                List.of(),
                List.of(new EditorSavePayload.MapCell(0, 1, 1, 99)));
        EditorSaveEnvelope envelope = new EditorSaveEnvelope(
                1,
                GameId.S2.code(),
                1,
                0,
                "2026-05-09T00:00:00Z",
                payload,
                sha256(MAPPER.writeValueAsString(payload)));
        Path file = manager.editPath(GameId.S2, 1, 0);
        Files.createDirectories(file.getParent());
        MAPPER.writeValue(file.toFile(), envelope);

        MutableLevel level = createMutableLevel();
        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 1, 0, level);

        assertEquals(EditorSaveManager.ApplyResult.QUARANTINED, result);
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)));
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
    }

    @Test
    void invalidLaterPayloadEntryDoesNotPartiallyMutateLevel() throws Exception {
        EditorSaveManager manager = new EditorSaveManager(tempDir);
        EditorSavePayload payload = new EditorSavePayload(
                List.of(),
                List.of(),
                List.of(
                        new EditorSavePayload.MapCell(0, 1, 1, 2),
                        new EditorSavePayload.MapCell(0, 2, 1, 99)));
        EditorSaveEnvelope envelope = new EditorSaveEnvelope(
                1,
                GameId.S2.code(),
                1,
                0,
                "2026-05-09T00:00:00Z",
                payload,
                sha256(MAPPER.writeValueAsString(payload)));
        Path file = manager.editPath(GameId.S2, 1, 0);
        Files.createDirectories(file.getParent());
        MAPPER.writeValue(file.toFile(), envelope);

        MutableLevel level = createMutableLevel();
        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 1, 0, level);

        assertEquals(EditorSaveManager.ApplyResult.QUARANTINED, result);
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)),
                "valid earlier map edit must not be applied before later invalid entry quarantines");
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
    }

    @Test
    void wrongLengthChunkStateQuarantinesWithoutPartialMapApply() throws Exception {
        EditorSaveManager manager = new EditorSaveManager(tempDir);
        EditorSavePayload payload = new EditorSavePayload(
                List.of(),
                List.of(new EditorSavePayload.ChunkState(1, new int[] { 7 })),
                List.of(new EditorSavePayload.MapCell(0, 1, 1, 2)));
        EditorSaveEnvelope envelope = new EditorSaveEnvelope(
                1,
                GameId.S2.code(),
                1,
                0,
                "2026-05-09T00:00:00Z",
                payload,
                sha256(MAPPER.writeValueAsString(payload)));
        Path file = manager.editPath(GameId.S2, 1, 0);
        Files.createDirectories(file.getParent());
        MAPPER.writeValue(file.toFile(), envelope);

        MutableLevel level = createMutableLevel();
        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 1, 0, level);

        assertEquals(EditorSaveManager.ApplyResult.QUARANTINED, result);
        assertEquals(0, level.getChunk(1).getPatternDesc(0, 0).getPatternIndex());
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)),
                "valid map edit must not be applied when a chunk state is invalid");
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
    }

    @Test
    void wrongLengthBlockStateQuarantinesWithoutPartialMapApply() throws Exception {
        EditorSaveManager manager = new EditorSaveManager(tempDir);
        EditorSavePayload payload = new EditorSavePayload(
                List.of(new EditorSavePayload.BlockState(1, new int[] { 2, 3 })),
                List.of(),
                List.of(new EditorSavePayload.MapCell(0, 1, 1, 2)));
        EditorSaveEnvelope envelope = new EditorSaveEnvelope(
                1,
                GameId.S2.code(),
                1,
                0,
                "2026-05-09T00:00:00Z",
                payload,
                sha256(MAPPER.writeValueAsString(payload)));
        Path file = manager.editPath(GameId.S2, 1, 0);
        Files.createDirectories(file.getParent());
        MAPPER.writeValue(file.toFile(), envelope);

        MutableLevel level = createMutableLevel();
        EditorSaveManager.ApplyResult result = manager.tryApplyEdits(GameId.S2, 1, 0, level);

        assertEquals(EditorSaveManager.ApplyResult.QUARANTINED, result);
        assertEquals(0, level.getBlock(1).getChunkDesc(0, 0).getChunkIndex());
        assertEquals(0, Byte.toUnsignedInt(level.getMap().getValue(0, 1, 1)),
                "valid map edit must not be applied when a block state is invalid");
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MutableLevel createMutableLevel() {
        return MutableLevel.snapshot(new SyntheticLevel());
    }

    private static int[] chunkState(int pattern00) {
        int[] state = new Chunk().saveState();
        state[0] = pattern00;
        return state;
    }

    private static final class SyntheticLevel extends AbstractLevel {
        private SyntheticLevel() {
            super(0);
            patternCount = 1;
            patterns = new Pattern[] { new Pattern() };
            chunkCount = 3;
            chunks = new Chunk[] { new Chunk(), new Chunk(), new Chunk() };
            blockCount = 3;
            blocks = new Block[] { new Block(1), new Block(1), new Block(1) };
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(2, 4, 3);
            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 128;
            minY = 0;
            maxY = 96;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 1;
        }

        @Override
        public int getBlockPixelSize() {
            return 32;
        }
    }
}

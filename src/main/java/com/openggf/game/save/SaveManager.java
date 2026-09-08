package com.openggf.game.save;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.dataselect.DataSelectGameProfile;
import com.openggf.util.QuarantineFiles;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.security.MessageDigest.getInstance;

@com.openggf.game.ModApi
public final class SaveManager {

    private static final Logger LOG = Logger.getLogger(SaveManager.class.getName());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SaveFileReader reader;

    public SaveManager(Path root) {
        this.root = root;
        this.reader = file -> mapper.readValue(file.toFile(), MAP_TYPE);
    }

    SaveManager(Path root, SaveFileReader reader) {
        this.root = root;
        this.reader = reader;
    }

    public void writeSlot(String game, int slot, Map<String, Object> payload) throws IOException {
        Path file = slotPath(game, slot);
        byte[] envelope = encodeEnvelope(game, slot, payload);
        writeAtomically(file, envelope);
    }

    /**
     * Frame-thread half of a save: encodes the envelope now, so the file
     * reflects the payload exactly as captured, and hands the disk write to
     * the single save-writer thread. In-game progression saves land on the
     * same frame as an act transition; the temp-file write and atomic rename
     * cost several milliseconds there and have no place in a frame budget.
     *
     * <p>Writes for one manager complete in submission order. {@link #readSlotSummary}
     * and {@link #deleteSlot} flush pending writes first, and
     * {@link #flushPendingWrites()} is available for shutdown.
     */
    public void writeSlotAsync(String game, int slot, Map<String, Object> payload) throws IOException {
        Path file = slotPath(game, slot);
        byte[] envelope = encodeEnvelope(game, slot, payload);
        synchronized (pendingWrites) {
            pendingWrites.add(WRITER.submit(() -> {
                try {
                    writeAtomically(file, envelope);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to write save " + file + ": " + e.getMessage(), e);
                }
                return null;
            }));
        }
    }

    /** Blocks until every write queued through {@link #writeSlotAsync} has finished. */
    public void flushPendingWrites() {
        while (true) {
            Future<?> next;
            synchronized (pendingWrites) {
                next = pendingWrites.poll();
            }
            if (next == null) {
                return;
            }
            try {
                next.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                LOG.log(Level.WARNING, "Save writer task failed: " + e.getCause(), e.getCause());
            }
        }
    }

    private byte[] encodeEnvelope(String game, int slot, Map<String, Object> payload) throws IOException {
        String payloadJson = mapper.writeValueAsString(payload);
        SaveEnvelope env = new SaveEnvelope(1, game, slot, payload, sha256(payloadJson));
        return mapper.writeValueAsBytes(env);
    }

    private static void writeAtomically(Path file, byte[] envelope) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, envelope);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private final Deque<Future<?>> pendingWrites = new ArrayDeque<>();

    /** One writer for every manager: saves are rare and ordering per file matters more than parallelism. */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "save-writer");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // A daemon writer would otherwise be abandoned by an exiting JVM with a
        // save still queued; drain it before the process goes.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WRITER.shutdown();
            try {
                WRITER.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "save-writer-drain"));
    }

    public SaveSlotSummary readSlotSummary(String game, int slot) throws IOException {
        return readSlotSummary(game, slot, null);
    }

    public SaveSlotSummary readSlotSummary(String game, int slot, DataSelectGameProfile profile) throws IOException {
        flushPendingWrites();
        Path file = slotPath(game, slot);
        if (!Files.exists(file)) {
            return SaveSlotSummary.empty(slot);
        }
        try {
            Map<String, Object> raw = reader.read(file);
            if (!game.equals(raw.get("game"))) {
                return quarantineCorruptAndReturnEmpty(file, slot, "wrong game");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) raw.get("payload");
            if (payload == null) {
                return quarantineCorruptAndReturnEmpty(file, slot, "missing payload");
            }
            if (profile != null && !profile.isPayloadValid(payload)) {
                return quarantineCorruptAndReturnEmpty(file, slot, "invalid payload");
            }
            String actual = sha256(mapper.writeValueAsString(payload));
            String expected = String.valueOf(raw.get("hash"));
            if (!actual.equals(expected)) {
                LOG.warning("Hash mismatch while reading save " + file);
            }
            return actual.equals(expected)
                    ? new SaveSlotSummary(slot, SaveSlotState.VALID, payload)
                    : new SaveSlotSummary(slot, SaveSlotState.HASH_WARNING, payload);
        } catch (JsonProcessingException | RuntimeException ex) {
            return quarantineCorruptAndReturnEmpty(file, slot, ex.getMessage());
        } catch (IOException ex) {
            LOG.warning("Transient I/O while reading save " + file + "; leaving it in place: "
                    + ex.getMessage());
            return SaveSlotSummary.unavailable(slot);
        }
    }

    private SaveSlotSummary quarantineCorruptAndReturnEmpty(Path file, int slot, String reason) {
        try {
            quarantine(file, reason);
        } catch (IOException qe) {
            LOG.warning("Failed to quarantine " + file + "; leaving it in place: " + qe.getMessage());
        }
        return SaveSlotSummary.empty(slot);
    }

    private void quarantine(Path file, String reason) throws IOException {
        LOG.warning("Quarantining corrupt save " + file + ": " + reason);
        Files.move(file, QuarantineFiles.uniqueCorruptSibling(file));
    }

    public void deleteSlot(String game, int slot) {
        flushPendingWrites();
        Path file = slotPath(game, slot);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warning("Failed to delete save " + file + ": " + e.getMessage());
        }
    }

    private Path slotPath(String game, int slot) {
        return root.resolve(game).resolve("slot" + slot + ".json");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface SaveFileReader {
        Map<String, Object> read(Path file) throws IOException;
    }
}

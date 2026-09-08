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
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.security.MessageDigest.getInstance;

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
        submitAndWait(() -> {
            writeAtomically(file, envelope);
            return null;
        });
    }

    /**
     * Frame-thread half of a save: encodes the envelope now, so the file
     * reflects the payload exactly as captured, and hands the disk write to
     * the single save-writer thread. In-game progression saves land on the
     * same frame as an act transition; the temp-file write and atomic rename
     * cost several milliseconds there and have no place in a frame budget.
     *
     * <p>Every save operation shares the writer's submission order. Reads,
     * deletes, synchronous writes, and {@link #flushPendingWrites()} are
     * barriers in that same order, even when their callers use different
     * {@code SaveManager} instances for the same root.
     */
    public void writeSlotAsync(String game, int slot, Map<String, Object> payload) throws IOException {
        Path file = slotPath(game, slot);
        byte[] envelope = encodeEnvelope(game, slot, payload);
        try {
            WRITER.execute(() -> {
                try {
                    writeAtomically(file, envelope);
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Failed to write save " + file + ": " + e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new IOException("Save writer is unavailable", e);
        }
    }

    /** Blocks until every save operation queued before this call has finished. */
    public void flushPendingWrites() {
        try {
            submitAndWait(() -> null);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not drain the save writer: " + e.getMessage(), e);
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

    /** One process-wide writer: saves are rare and submission order is part of persistence semantics. */
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
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
        return submitAndWait(() -> readSlotSummaryNow(game, slot, profile));
    }

    private SaveSlotSummary readSlotSummaryNow(String game, int slot, DataSelectGameProfile profile)
            throws IOException {
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
        Path file = slotPath(game, slot);
        try {
            submitAndWait(() -> {
                Files.deleteIfExists(file);
                return null;
            });
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

    private static <T> T submitAndWait(Callable<T> operation) throws IOException {
        Future<T> future;
        try {
            future = WRITER.submit(operation);
        } catch (RejectedExecutionException e) {
            throw new IOException("Save writer is unavailable", e);
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            // Do not fall through to an unprotected filesystem operation: the
            // queued operation is cancelled when it has not started, and the
            // caller keeps the interrupt signal for its own shutdown policy.
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the save writer", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Save writer task failed", cause);
        }
    }

    @FunctionalInterface
    interface SaveFileReader {
        Map<String, Object> read(Path file) throws IOException;
    }
}

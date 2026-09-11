package com.openggf.game.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.control.InputActionMasks;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class UserRecordingWriter {
    public static final String LOG_KEY =
            "#P1 Up|P1 Down|P1 Left|P1 Right|P1 Start|P1 A|P1 B|P1 C|"
                    + "#P2 Up|P2 Down|P2 Left|P2 Right|P2 Start|P2 A|P2 B|P2 C|";

    private static final ObjectMapper SIDECAR_MAPPER = new ObjectMapper();

    private UserRecordingWriter() {
    }

    public static void write(Path bk2Path, UserRecordingManifest manifest, List<RecordedFrameInput> inputs,
            List<DesyncLiteFrame> sidecarFrames) throws IOException {
        Objects.requireNonNull(bk2Path, "bk2Path");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(sidecarFrames, "sidecarFrames");
        validate(manifest, inputs, sidecarFrames);

        Path target = bk2Path.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tmp = temporaryPathFor(target);
        boolean moved = false;
        try {
            writeZip(tmp, manifest, inputs, sidecarFrames);
            moveIntoPlace(tmp, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static void validate(UserRecordingManifest manifest, List<RecordedFrameInput> inputs,
            List<DesyncLiteFrame> sidecarFrames) {
        if (manifest.frameCount() != inputs.size()) {
            throw new IllegalArgumentException(
                    "Manifest frameCount " + manifest.frameCount() + " does not match input count " + inputs.size());
        }
        for (int i = 0; i < inputs.size(); i++) {
            RecordedFrameInput input = Objects.requireNonNull(inputs.get(i), "inputs[" + i + "]");
            if (input.frame() != i) {
                throw new IllegalArgumentException(
                        "Input frame " + input.frame() + " at index " + i + " does not match timeline");
            }
            validateActionMask("p1ActionMask", i, input.p1ActionMask());
            validateActionMask("p2ActionMask", i, input.p2ActionMask());
        }
        if (sidecarFrames.size() != inputs.size()) {
            throw new IllegalArgumentException("Sidecar frame count " + sidecarFrames.size()
                    + " does not match input count " + inputs.size());
        }
        for (int i = 0; i < sidecarFrames.size(); i++) {
            DesyncLiteFrame sidecarFrame = Objects.requireNonNull(sidecarFrames.get(i), "sidecarFrames[" + i + "]");
            if (sidecarFrame.frame() != i) {
                throw new IllegalArgumentException(
                        "Sidecar frame " + sidecarFrame.frame() + " at index " + i + " does not match timeline");
            }
        }
    }

    private static void validateActionMask(String fieldName, int frame, int actionMask) {
        if ((actionMask & ~InputActionMasks.ACTION_ALL) != 0) {
            throw new IllegalArgumentException(fieldName + " at frame " + frame
                    + " has bits outside supported A/B/C action mask 0x"
                    + Integer.toHexString(InputActionMasks.ACTION_ALL));
        }
    }

    private static Path temporaryPathFor(Path target) throws IOException {
        Path fileName = target.getFileName();
        String prefix = fileName == null ? "recording-" : fileName.toString() + "-";
        if (prefix.length() < 3) {
            prefix = "bk2-" + prefix;
        }
        return Files.createTempFile(target.getParent(), prefix, ".tmp");
    }

    private static void writeZip(Path tmp, UserRecordingManifest manifest, List<RecordedFrameInput> inputs,
            List<DesyncLiteFrame> sidecarFrames) throws IOException {
        try (OutputStream out = Files.newOutputStream(tmp);
                ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, "Header.txt", header(manifest));
            writeEntry(zip, "Input Log.txt", inputLog(inputs));
            writeEntry(zip, "OpenGGF/manifest.json", UserRecordingJson.writeManifest(manifest));
            writeEntry(zip, "OpenGGF/desync-lite.jsonl", sidecarJsonl(sidecarFrames));
        }
    }

    private static String header(UserRecordingManifest manifest) {
        String gameId = manifest.launchContext() == null ? "" : manifest.launchContext().gameId();
        return "Author: OpenGGF\n"
                + "GameName: " + gameId + "\n"
                + "Frames: " + manifest.frameCount() + "\n";
    }

    private static String inputLog(List<RecordedFrameInput> inputs) {
        StringBuilder builder = new StringBuilder();
        builder.append("[Input]\n");
        builder.append("LogKey:").append(LOG_KEY).append('\n');
        for (RecordedFrameInput input : inputs) {
            builder.append('|')
                    .append(controllerField(input.p1InputMask(), input.p1ActionMask(), input.p1Start()))
                    .append('|')
                    .append(controllerField(input.p2InputMask(), input.p2ActionMask(), input.p2Start()))
                    .append('|')
                    .append('\n');
        }
        builder.append("[/Input]\n");
        return builder.toString();
    }

    private static String controllerField(int inputMask, int actionMask, boolean start) {
        char[] field = "........".toCharArray();
        if ((inputMask & AbstractPlayableSprite.INPUT_UP) != 0) {
            field[0] = 'U';
        }
        if ((inputMask & AbstractPlayableSprite.INPUT_DOWN) != 0) {
            field[1] = 'D';
        }
        if ((inputMask & AbstractPlayableSprite.INPUT_LEFT) != 0) {
            field[2] = 'L';
        }
        if ((inputMask & AbstractPlayableSprite.INPUT_RIGHT) != 0) {
            field[3] = 'R';
        }
        if (start) {
            field[4] = 'S';
        }
        boolean hasExplicitAction = (actionMask & InputActionMasks.ACTION_ALL) != 0;
        if ((actionMask & InputActionMasks.ACTION_A) != 0
                || (!hasExplicitAction && (inputMask & AbstractPlayableSprite.INPUT_JUMP) != 0)) {
            field[5] = 'A';
        }
        if ((actionMask & InputActionMasks.ACTION_B) != 0) {
            field[6] = 'B';
        }
        if ((actionMask & InputActionMasks.ACTION_C) != 0) {
            field[7] = 'C';
        }
        return new String(field);
    }

    private static String sidecarJsonl(List<DesyncLiteFrame> sidecarFrames) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (DesyncLiteFrame frame : sidecarFrames) {
            builder.append(SIDECAR_MAPPER.writeValueAsString(frame)).append('\n');
        }
        return builder.toString();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void moveIntoPlace(Path tmp, Path bk2Path) throws IOException {
        try {
            Files.move(tmp, bk2Path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, bk2Path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

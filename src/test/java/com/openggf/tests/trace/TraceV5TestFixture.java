package com.openggf.tests.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.tests.TestTempFiles;

import java.io.IOException;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Canonical v5 level rows for test-owned temporary fixtures. */
public final class TraceV5TestFixture {
    public static final String LEVEL_HEADER =
            "frame,input,camera_x,camera_y,rings,gameplay_frame_counter,"
                    + "vblank_counter,lag_counter,player_present,player_x,player_y,"
                    + "player_x_speed,player_y_speed,player_g_speed,player_angle,"
                    + "player_air,player_rolling,player_ground_mode,player_x_sub,"
                    + "player_y_sub,player_routine,player_status_byte,player_stand_on_obj,"
                    + "player_animation_id,player_mapping_frame,sidekick_present,"
                    + "sidekick_x,sidekick_y,sidekick_x_speed,sidekick_y_speed,"
                    + "sidekick_g_speed,sidekick_angle,sidekick_air,sidekick_rolling,"
                    + "sidekick_ground_mode,sidekick_x_sub,sidekick_y_sub,sidekick_routine,"
                    + "sidekick_status_byte,sidekick_stand_on_obj,sidekick_animation_id,"
                    + "sidekick_mapping_frame";

    private TraceV5TestFixture() {
    }

    public static String levelRow(int frame) {
        return levelRow(frame, 0, 0);
    }

    public static String levelRow(int frame, int playerX, int playerY) {
        String[] fields = new String[42];
        Arrays.fill(fields, "0");
        fields[0] = "%04X".formatted(frame);
        fields[8] = "1";
        fields[9] = "%04X".formatted(playerX & 0xFFFF);
        fields[10] = "%04X".formatted(playerY & 0xFFFF);
        fields[20] = "02";
        return String.join(",", fields);
    }

    public static String levelCsv(int... frames) {
        StringBuilder csv = new StringBuilder(LEVEL_HEADER).append('\n');
        for (int frame : frames) {
            csv.append(levelRow(frame)).append('\n');
        }
        return csv.toString();
    }

    /**
     * Materializes a temporary v5 view of an installed fixture for a focused
     * semantic test. The installed predecessor is never modified; metadata
     * migration is limited to removing fields that the strict v5 loader no
     * longer accepts. Payload files are copied byte-for-byte.
     */
    public static Path canonicalizeInstalledTrace(Path source) throws IOException {
        Path target = TestTempFiles.createTempDirectory("trace-v5-installed-");
        ObjectNode metadata = (ObjectNode) new ObjectMapper().readTree(
                Files.readString(source.resolve("metadata.json")));
        for (String removed : new String[]{
                "lua_script_version", "csv_version", "ss_csv_version",
                "hardware_timing_schema", "run_schema"}) {
            metadata.remove(removed);
        }
        metadata.put("trace_schema", 5);
        Files.writeString(target.resolve("metadata.json"),
                new ObjectMapper().writeValueAsString(metadata));

        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals("metadata.json")) {
                    continue;
                }
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
    }
}

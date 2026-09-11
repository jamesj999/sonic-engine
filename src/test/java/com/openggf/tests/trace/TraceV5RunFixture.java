package com.openggf.tests.trace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds disposable strict-v5 run inputs for manifest, catalog, and walker tests. */
public final class TraceV5RunFixture {

    private static final String LEVEL_HEADER = "frame,input,camera_x,camera_y,rings,"
            + "gameplay_frame_counter,vblank_counter,lag_counter,player_present,player_x,"
            + "player_y,player_x_speed,player_y_speed,player_g_speed,player_angle,player_air,"
            + "player_rolling,player_ground_mode,player_x_sub,player_y_sub,player_routine,"
            + "player_status_byte,player_stand_on_obj,player_animation_id,player_mapping_frame,"
            + "sidekick_present,sidekick_x,sidekick_y,sidekick_x_speed,sidekick_y_speed,"
            + "sidekick_g_speed,sidekick_angle,sidekick_air,sidekick_rolling,sidekick_ground_mode,"
            + "sidekick_x_sub,sidekick_y_sub,sidekick_routine,sidekick_status_byte,"
            + "sidekick_stand_on_obj,sidekick_animation_id,sidekick_mapping_frame";
    private static final String LEVEL_ROW = "0000,0000,0000,0390,0037,0000,0300,0000,"
            + "1,0040,0420,0000,0000,0000,00,0,0,0,0000,0000,02,00,00,00,00,"
            + "1,7F00,0000,0000,0000,0000,00,1,0,0,0000,0000,02,02,00,00,00";
    private static final String S2_SPECIAL_STAGE_HEADER = "frame,input,input_p2,lag,speed_factor,"
            + "track_anim,track_anim_frame,track_drawing_index,track_orientation,track_duration_timer,"
            + "current_segment,player_anim_frame_timer,rings_togo_bcd,check_rings_flag,"
            + "tails_control_counter,swap_positions_flag,sonic_present,sonic_ss_x,sonic_ss_x_sub,"
            + "sonic_ss_y,sonic_ss_y_sub,sonic_ss_z,sonic_angle,sonic_routine,"
            + "sonic_routine_secondary,sonic_status,sonic_anim,sonic_anim_frame,sonic_rings_bcd,"
            + "sonic_hurt_timer,sonic_slide_timer,sonic_flip_timer,tails_present,tails_ss_x,"
            + "tails_ss_x_sub,tails_ss_y,tails_ss_y_sub,tails_ss_z,tails_angle,tails_routine,"
            + "tails_routine_secondary,tails_status,tails_anim,tails_anim_frame,tails_rings_bcd,"
            + "tails_hurt_timer,tails_slide_timer,tails_flip_timer";
    private static final String S2_SPECIAL_STAGE_ROW = "0,0,0,0,a,0,0,0,0,0,0,0,0,0,0,0,"
            + "1,100,0,780,0,0,0,0,0,0,0,0,0,0,0,0,1,100,0,780,0,0,0,0,0,0,0,0,0,0,0,0";

    private TraceV5RunFixture() {
    }

    public static Path writeS3kBonusRun(Path root) throws IOException {
        Path run = root.resolve("run_aiz_gumball_3seg");
        writeLevelSegment(run, "seg00_aiz", "s3k", "aiz", "complete_run", 0, 1, 500, 0);
        writeLevelSegment(run, "seg01_gumball", "s3k", "aiz", "s3k_bonus_stage", 19, 1, 1900, 1);
        writeLevelSegment(run, "seg02_aiz", "s3k", "aiz", "complete_run", 0, 1, 2900, 2);
        writeManifest(run, "s3k", "run_aiz_gumball_3seg", """
                {"dir":"seg00_aiz","kind":"level","trace_profile":"complete_run","bk2_frame_offset":500,"trace_frame_count":2,"zone_id":0,"act":1},
                {"dir":"seg01_gumball","kind":"bonus_stage","trace_profile":"s3k_bonus_stage","bk2_frame_offset":1900,"trace_frame_count":2,"zone_id":19,"act":1,"bonus_stage_type":"gumball"},
                {"dir":"seg02_aiz","kind":"level","trace_profile":"complete_run","bk2_frame_offset":2900,"trace_frame_count":2,"zone_id":0,"act":1}
                """, """
                {"from_segment":0,"to_segment":1,"entry_kind":"starpost_bonus","mode_change_bk2_frame":1750},
                {"from_segment":1,"to_segment":2,"entry_kind":"stage_exit","mode_change_bk2_frame":2800}
                """);
        return run;
    }

    public static Path writeS2SpecialStageRun(Path root) throws IOException {
        Path run = root.resolve("run_ehz_ss_3seg");
        writeLevelSegment(run, "seg1_ehz1", "s2", "ehz", "gameplay_unlock", 0, 1, 500, 0);
        writeSpecialStageSegment(run.resolve("ss"));
        writeLevelSegment(run, "seg2_ehz1", "s2", "ehz", "gameplay_unlock", 0, 1, 1200, 2);
        writeManifest(run, "s2", "run_ehz_ss_3seg", """
                {"dir":"seg1_ehz1","kind":"level","trace_profile":"gameplay_unlock","bk2_frame_offset":500,"trace_frame_count":2,"zone_id":0,"act":1},
                {"dir":"ss","kind":"special_stage","trace_profile":"s2_special_stage","bk2_frame_offset":800,"trace_frame_count":2,"zone_id":0,"act":1,"special_stage_index":0},
                {"dir":"seg2_ehz1","kind":"level","trace_profile":"gameplay_unlock","bk2_frame_offset":1200,"trace_frame_count":2,"zone_id":0,"act":1}
                """, """
                {"from_segment":0,"to_segment":1,"entry_kind":"starpost_special","mode_change_bk2_frame":750,"special_bonus_entry_flag":1,"saved_x_pos":256,"saved_y_pos":512,"last_star_post_hit":1,"rings_before":50,"emeralds_before":0},
                {"from_segment":1,"to_segment":2,"entry_kind":"stage_exit","mode_change_bk2_frame":1100,"rings_after":0,"emeralds_after":1}
                """);
        return run;
    }

    /** Writes a minimal BK2 movie that covers every generated manifest offset. */
    public static Path writeMovie(Path movie) throws IOException {
        Files.createDirectories(movie.getParent());
        StringBuilder inputLog = new StringBuilder("[Input]\n")
                .append("LogKey:|P1 Up|P1 Down|P1 Left|P1 Right|P1 B|P1 C|P1 A|P1 Start|\n");
        for (int frame = 0; frame < 3_000; frame++) {
            inputLog.append("|.|.|.|.|.|.|.|.|\n");
        }
        inputLog.append("[/Input]\n");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(movie))) {
            writeZipEntry(zip, "Header.txt", "Author: generated-v5-test\n");
            writeZipEntry(zip, "Input Log.txt", inputLog.toString());
        }
        return movie;
    }

    private static void writeLevelSegment(Path run, String name, String game, String zone,
            String profile, int zoneId, int act, int offset, int segmentIndex) throws IOException {
        Path segment = run.resolve(name);
        Files.createDirectories(segment);
        Files.write(segment.resolve("physics.csv"), List.of(
                LEVEL_HEADER, LEVEL_ROW, advancingLevelRow()));
        Files.write(segment.resolve("aux_state.jsonl"), List.of(
                "{\"frame\":0,\"event\":\"dynamic_art_transfer_state\",\"edges\":[],\"outstanding_transfer_ids\":[]}",
                "{\"frame\":1,\"event\":\"dynamic_art_transfer_state\",\"edges\":[],\"outstanding_transfer_ids\":[]}"));
        Files.writeString(segment.resolve("metadata.json"), """
                {"game":"%s","zone":"%s","zone_id":%d,"act":%d,
                "bk2_frame_offset":%d,"trace_frame_count":2,"trace_schema":5,
                "trace_profile":"%s","source_bk2":"synthetic.bk2",
                "run_id":"%s","segment_index":%d,"start_x":"0400","start_y":"0420",
                "aux_schema_extras":["dynamic_art_transfer_state_per_frame"]}
                """.formatted(game, zone, zoneId, act, offset, profile,
                        run.getFileName(), segmentIndex));
    }

    private static String advancingLevelRow() {
        String[] columns = LEVEL_ROW.split(",", -1);
        columns[0] = "0001";
        columns[5] = "0001";
        columns[6] = "0301";
        return String.join(",", columns);
    }

    private static void writeSpecialStageSegment(Path segment) throws IOException {
        Files.createDirectories(segment);
        Files.write(segment.resolve("physics.csv"), List.of(
                S2_SPECIAL_STAGE_HEADER, S2_SPECIAL_STAGE_ROW,
                S2_SPECIAL_STAGE_ROW.replaceFirst("^0", "1")));
        Files.write(segment.resolve("aux_state.jsonl"), List.of(
                "{\"frame\":0,\"event\":\"dynamic_art_transfer_state\",\"edges\":[],\"outstanding_transfer_ids\":[]}",
                "{\"frame\":1,\"event\":\"dynamic_art_transfer_state\",\"edges\":[],\"outstanding_transfer_ids\":[]}"));
        Files.writeString(segment.resolve("metadata.json"), """
                {"game":"s2","trace_profile":"s2_special_stage","special_stage_index":0,
                "bk2_frame_offset":800,"trace_frame_count":2,"trace_schema":5,
                "source_bk2":"synthetic.bk2","run_id":"run_ehz_ss_3seg","segment_index":1,
                "aux_schema_extras":["dynamic_art_transfer_state_per_frame"]}
                """);
    }

    private static void writeManifest(Path run, String game, String runId,
            String segments, String transitions) throws IOException {
        Files.createDirectories(run);
        Files.writeString(run.resolve("run_manifest.json"), """
                {"trace_schema":5,"game":"%s","run_id":"%s","source_bk2":"synthetic.bk2",
                "rom_checksum":"checksum","recorder":"native-bizhawk-headless","recorder_version":"3.0",
                "segments":[%s],"transitions":[%s],"dynamic_art_gap_transitions":[]}
                """.formatted(game, runId, segments, transitions));
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

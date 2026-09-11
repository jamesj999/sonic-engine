package com.openggf.tests.trace;

import com.openggf.trace.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestTraceEventFormatting {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesObjectNearAndSummarisesWithSlotAndPosition() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":387,"vfc":388,"event":"object_near","slot":44,"type":"0x23","x":"0x024A","y":"0x0386","routine":"0x04","status":"0x00"}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.ObjectNear);
        assertEquals("near s44 0x23 @024A,0386 rtn=04",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void parsesRoutineAndModeChangesIntoCompactSummary() {
        TraceEvent mode = TraceEvent.parseJsonLine(
                """
                {"frame":387,"vfc":388,"event":"mode_change","field":"rolling","from":1,"to":0}
                """.trim(),
                mapper);
        TraceEvent routine = TraceEvent.parseJsonLine(
                """
                {"frame":387,"vfc":388,"event":"routine_change","from":"0x02","to":"0x04","sonic_x":"0x0241","sonic_y":"0x038F"}
                """.trim(),
                mapper);

        assertEquals("mode rolling 1->0 | routine 02->04 @0241,038F",
                TraceEventFormatter.summariseFrameEvents(List.of(mode, routine)));
    }

    @Test
    void parsesCharacterScopedEventsIntoCompactSummary() {
        TraceEvent near = TraceEvent.parseJsonLine(
                """
                {"frame":387,"vfc":388,"event":"object_near","character":"tails","slot":44,"type":"0x23","x":"0x024A","y":"0x0386","routine":"0x04","status":"0x00"}
                """.trim(),
                mapper);
        TraceEvent mode = TraceEvent.parseJsonLine(
                """
                {"frame":387,"vfc":388,"event":"mode_change","character":"tails","field":"rolling","from":1,"to":0}
                """.trim(),
                mapper);
        TraceEvent routine = TraceEvent.parseJsonLine(
                """
                {"frame":387,"vfc":388,"event":"routine_change","character":"tails","from":"0x02","to":"0x04","x":"0x0241","y":"0x038F"}
                """.trim(),
                mapper);

        assertEquals("near tails s44 0x23 @024A,0386 rtn=04 | tails mode rolling 1->0 | tails routine 02->04 @0241,038F",
                TraceEventFormatter.summariseFrameEvents(List.of(near, mode, routine)));
    }

    @Test
    void parsesObjectLifecycleEvents() {
        TraceEvent appeared = TraceEvent.parseJsonLine(
                """
                {"frame":387,"vfc":388,"event":"object_appeared","slot":45,"object_type":"0x37","x":"0x0240","y":"0x0390"}
                """.trim(),
                mapper);
        TraceEvent removed = TraceEvent.parseJsonLine(
                """
                {"frame":390,"vfc":391,"event":"object_removed","slot":44,"object_type":"0x23"}
                """.trim(),
                mapper);

        assertEquals("obj+ s45 0x37 @0240,0390 | obj- s44 0x23",
                TraceEventFormatter.summariseFrameEvents(List.of(appeared, removed)));
    }

    @Test
    void preservesArrayPayloadsForStateSnapshots() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":3101,"vfc":3093,"event":"slot_dump","slots":[[32,"0x46"],[75,"0x55"]]}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.StateSnapshot);
        TraceEvent.StateSnapshot snapshot = (TraceEvent.StateSnapshot) event;
        assertEquals("slot_dump", snapshot.fields().get("event"));
        assertEquals("[[32,\"0x46\"],[75,\"0x55\"]]", snapshot.fields().get("slots"));
    }

    @Test
    void summarisesStateSnapshotCollisionPlaneDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":7065,"vfc":7090,"event":"state_snapshot","character":"sonic","status_byte":"0x03","routine":"0x02","top_solid_bit":"0x0E","lrb_solid_bit":"0x0F","raw_input":"0x04","on_object":false,"pushing":false}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.StateSnapshot);
        assertEquals("sonic state st=0x03 rtn=0x02 top=0x0E lrb=0x0F onObj=false push=false input=0x04",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void summarisesS2TornadoStateDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":911,"vfc":912,"event":"s2_tornado_state","slot":16,"x":"0x04B6","y":"0x008F","y_sub":"0xC000","y_vel":"0x0000","routine":"0x02","routine_secondary":"0x00","status_byte":"0x08","objoff_2e":"0x08","objoff_2f":"0x00","objoff_30":"0x00","objoff_31":"0xFF"}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.S2TornadoState);
        assertEquals("s2Tornado s16 @0x04B6,0x008F sub=0xC000 yv=0x0000 rtn=0x02/0x00 st=0x08 2e=0x08 2f=0x00 30=0x00 31=0xFF",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void parsesCheckpointAndZoneActStateIntoCompactSummary() {
        TraceEvent checkpoint = TraceEvent.parseJsonLine(
                """
                {"frame":1200,"event":"checkpoint","name":"aiz2_main_gameplay","actual_zone_id":0,"actual_act":1,"apparent_act":0,"game_mode":12,"notes":"resume strict replay"}
                """.trim(),
                mapper);
        TraceEvent zoneActState = TraceEvent.parseJsonLine(
                """
                {"frame":1200,"event":"zone_act_state","actual_zone_id":0,"actual_act":1,"apparent_act":0,"game_mode":12}
                """.trim(),
                mapper);

        assertEquals("cp aiz2_main_gameplay z=0 a=1 ap=0 gm=12 | zoneact z=0 a=1 ap=0 gm=12",
                TraceEventFormatter.summariseFrameEvents(List.of(checkpoint, zoneActState)));
    }

    @Test
    void summarisesCnzCageDiagnostics() {
        TraceEvent cageState = TraceEvent.parseJsonLine(
                """
                {"frame":2137,"vfc":2138,"event":"cage_state","slot":4,"x":"0x1300","y":"0x07C0","subtype":"0x28","status":"0x09","p1_phase":"0x80","p1_state":"0x00","p2_phase":"0xC0","p2_state":"0x01"}
                """.trim(),
                mapper);
        TraceEvent cageExecution = TraceEvent.parseJsonLine(
                """
                {"frame":2137,"vfc":2138,"event":"cage_execution","hits":[{"branch":"sub_338C4_entry","pc":"0x338C4","cage_addr":"0xB128","player_addr":"0xB04A","state_addr":"0xB15C","d5":"0x0800","d6":"0x04","state_byte":"0x01","player_status":"0x08","player_obj_ctrl":"0x42","cage_status":"0x09"}]}
                """.trim(),
                mapper);

        assertEquals("cage s4 @1300,07C0 sub=28 st=09 p1=80/00 p2=C0/01 | cageExec sub_338C4_entry@338C4 cage=B128 player=B04A d5=0800 d6=04 state=01 obj=42 cst=09",
                TraceEventFormatter.summariseFrameEvents(List.of(cageState, cageExecution)));
    }

    @Test
    void summarisesS3kTailsCpuNormalStepDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":3905,"vfc":3906,"event":"tails_cpu_normal_step","character":"tails","status":"0x00","object_control":"0x00","ground_vel":"0x000C","x_vel":"0x0000","delayed_stat":"0x08","delayed_input":"0x0800","loc_13dd0_branch":"leader_on_object","ctrl2_logical":"0x0808","ctrl2_held_logical":"0x08","path_pre_ground_vel":"0x000C","path_pre_x_vel":"0x0000","path_pre_status":"0x00","path_post_ground_vel":"0x000C","path_post_x_vel":"0x000C","path_post_status":"0x00"}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.TailsCpuNormalStep);
        assertEquals("tailsCpu status=00 obj=00 gv=000C xv=0000 stat=08 input=0800 branch=leader_on_object ctrl2=0808/08 post=000C,000C,00",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void summarisesS3kSidekickInteractObjectDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":4679,"vfc":4680,"event":"sidekick_interact_object","character":"tails","interact":"0xB128","interact_slot":4,"tails_render_flags":"0x80","tails_object_control":"0x03","tails_invulnerability_timer":"0x2F","tails_width_pixels":"0x14","tails_height_pixels":"0x18","camera_x_copy":"0x1CA1","camera_y_copy":"0x0360","tails_status":"0x08","tails_on_object":true,"object_code":"0x000220C2","object_routine":"0x02","object_status":"0x10","object_x":"0x2D95","object_y":"0x0420","object_subtype":"0x40","object_render_flags":"0x80","object_object_control":"0x00","object_active":true,"object_destroyed":false,"object_p1_standing":false,"object_p2_standing":true}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.SidekickInteractObjectState);
        assertEquals("tailsInteract slot=4 ptr=B128 obj=000220C2 rtn=02 st=10 @2D95,0420 sub=40 tails rf=80 inv=2F wh=14/18 camCopy=1CA1,0360 obj=03 onObj=true objP2=true active=true destroyed=false",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void summarisesS3kAizBoundaryDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":4679,"vfc":4680,"event":"aiz_boundary_state","character":"tails","camera_min_x":"0x2D80","camera_max_x":"0x4000","camera_min_y":"0x0000","camera_max_y":"0x0300","tree_pre_x":"0x2D40","tree_pre_y":"0x0402","tree_pre_x_vel":"0x00F7","tree_pre_y_vel":"0x0198","tree_post_x":"0x2D95","tree_post_y":"0x040F","tree_post_x_vel":"0x0000","tree_post_y_vel":"0x0000","boundary_pre_x":"0x2D95","boundary_pre_y":"0x040F","boundary_pre_x_vel":"0x0000","boundary_pre_y_vel":"0x0000","boundary_post_x":"0x2D95","boundary_post_y":"0x040F","boundary_post_x_vel":"0x0000","boundary_post_y_vel":"0x0000","boundary_action":"none","post_move_x":"0x2D95","post_move_y":"0x040F","post_move_x_vel":"0x0000","post_move_y_vel":"0x0000"}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.AizBoundaryState);
        assertEquals("tailsAizBoundary cam=2D80/4000 y=0000/0300 tree=2D40,0402,00F7,0198->2D95,040F,0000,0000 boundary=none 2D95,040F,0000,0000->2D95,040F,0000,0000 post=2D95,040F,0000,0000",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void summarisesS3kAizTransitionFloorDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":5415,"vfc":1700,"event":"aiz_transition_floor_solid","slot":4,"object_status":"0x90","object_x":"0x2FB0","object_y":"0x03A0","p1_standing":false,"p2_standing":true,"p1_path":"first_reject","p2_path":"standing","p1_d1":"0x00A0","p1_d2":"0x0010","p1_d3":"0x0010","p1_status":"0x00","p1_object_control":"0x00","p1_y_radius":"0x13","p1_x":"0x2FCD","p1_y":"0x0379","p1_y_vel":"0x0000","p1_interact_slot":4,"p2_d1":"0x00A0","p2_d2":"0x0140","p2_d3":"0x0010","p2_status":"0x08","p2_object_control":"0x00","p2_y_radius":"0x10","p2_x":"0x2FB1","p2_y":"0x0380","p2_y_vel":"0x0000","p2_interact_slot":4}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.AizTransitionFloorSolidState);
        assertEquals("aizFloor s4 @2FB0,03A0 st=90 stand=false/true p1=first_reject y=0379 yr=13 st=00 obj=00 d=00A0/0010/0010 p2=standing y=0380 yr=10 st=08 obj=00 d=00A0/0140/0010",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void summarisesS3kAizHandoffTerrainDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":5435,"vfc":1700,"event":"aiz_handoff_terrain_state","events_bg":"0x0010","draw_pos":"0x00A0","draw_rows":"0x0004","kos_modules_left":"0x00","current_zone_act":"0x0000","dynamic_resize":"0x00","object_load":"0x00","rings_manager":"0x00","p1_x":"0x2FCD","p1_y":"0x0379","p1_status":"0x00","p1_y_radius":"0x13","p1_top_solid":"0x0C","sonic_floor_seen":true,"sonic_floor_distance":"0x0000","sonic_floor_angle":"0x00","sonic_floor_probe_x":"0x2FE0","sonic_floor_probe_y":"0x038C","solid_vertical_seen":true,"solid_pre_y":"0x0379","solid_surface_y":"0x0390","solid_delta":"0x0000"}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.AizHandoffTerrainState);
        assertEquals("aizHandoff bg=0010 draw=00A0/0004 kos=00 za=0000 dyn=00 objLoad=00 rings=00 p1=2FCD,0379 st=00 yr=13 top=0C floor=seen d=0000 a=00 probe=2FE0,038C solid=seen preY=0379 surf=0390 d=0000",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void summarisesS3kFixedAirCountdownDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":20358,"vfc":20359,"event":"air_countdown_state","owner":"p2","fixed_slot":95,"object_code":"0x00018164","routine":"0x0A","subtype":"0x81","obj30":"0x0000","obj36":"0x00","obj37":"0x01","obj38":"0xFF","obj3a":"0x0000","obj3c":"0x0032","obj3e":"0x0016","owner_ptr":"0xFFFFB04A","owner_resolved":"p2","owner_air_left":"0x18","owner_status":"0x41","owner_status_secondary":"0x00","owner_facing_left":true,"owner_underwater":true,"rng_seed":"0x89ABCDEF","visible_children":[{"slot":6,"object_code":"0x00018164","routine":"0x02","subtype":"0x06","x":"0x17A2","y":"0x0A94","x_sub":"0x0000","y_sub":"0x0000","y_vel":"0xFF00","render_flags":"0x84","anim":"0x06","mapping_frame":"0x01","anim_frame":"0x02","anim_frame_timer":"0x0E","angle":"0x40","obj34":"0x17A8","obj3c":"0x0000","parent_ptr":"0xFFFFB04A"}]}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.AirCountdownState);
        assertEquals("airCnt p2 fixed=s95 code=00018164 rtn=0A sub=81 30=0000 36=00 37=01 38=FF 3A=0000 3C=0032 3E=0016 owner=p2 ptr=FFFFB04A air=18 st=41/00 face=L water=true rng=89ABCDEF child=s6 @17A2,0A94 sub=06 rtn=02 yv=FF00 rf=84 anim=06 map=01 af=02/0E ang=40 org=17A8 3C=0000 parent=FFFFB04A",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }

    @Test
    void summarisesS3kRngCallDiagnostics() {
        TraceEvent event = TraceEvent.parseJsonLine(
                """
                {"frame":21620,"vfc":21621,"event":"rng_call","hits":[{"pc":"0x01D24","caller_pc":"0x031754","source":"CNZBalloon.init","seed_before":"0x12345678","seed_after":"0x89ABCDEF","result":"0x12340099","result_byte":"0x99","a0_ptr":"0xB2C0","a0_slot":9,"a0_object_code":"0x00031754","a0_routine":"0x00","a0_subtype":"0x02","a0_x":"0x10E8","a0_y":"0x06B0","a1_ptr":"0x0000","a1_slot":-1,"a1_object_code":"0x00000000","a1_routine":"0x00","a1_subtype":"0x00","a1_x":"0x0000","a1_y":"0x0000"}]}
                """.trim(),
                mapper);

        assertTrue(event instanceof TraceEvent.RngCall);
        assertEquals("rng pc=01D24 ret=031754 CNZBalloon.init seed=12345678->89ABCDEF res=12340099/99 a0=s9 00031754 @10E8,06B0 r=00 sub=02",
                TraceEventFormatter.summariseFrameEvents(List.of(event)));
    }
}

package com.openggf.game.rules;

import com.openggf.game.resources.PlcLifecyclePhase;

/**
 * Game-wide policy for claims that represent the player's dynamic-art DMA
 * service boundary.
 */
public enum DynamicArtDmaServiceModel {
    EVERY_CLAIM(true) {
        @Override
        public boolean services(PlcLifecyclePhase phase) {
            return phase != null;
        }
    },
    /**
     * S1 writes Sonic's staged DPLC art from the {@code f_sonframechg}-gated
     * {@code writeVRAM v_sgfx_buffer,ArtTile_Sonic*tile_size} that lives inside
     * each per-mode VBlank handler (docs/s1disasm/sonic.asm:829-833
     * VBlank_Levels, 890-894 VBlank_SpecialStage, 927-931 VBlank_TitleCards,
     * 985-989 VBlank_Paused -- so NORMAL_PAUSE stays a service boundary).
     * VBlank branches to VBlank_Lag before reaching any of them
     * (sonic.asm:652-655) and VBlank_Lag only runs the sound driver
     * (sonic.asm:709-715 -> VBlank_Music, sonic.asm:678-684), so
     * {@code f_sonframechg} stays set across a lag frame and the transfer lands
     * on the next real VBlank.
     */
    SONIC_1_VBLANK_SONIC_GFX(true) {
        @Override
        public boolean services(PlcLifecyclePhase phase) {
            return phase != null && phase != PlcLifecyclePhase.LAG;
        }
    },
    /**
     * S2 retires queued transfers from {@code ProcessDMAQueue}
     * (docs/s2disasm/s2.asm:1770), which is only reached from the real
     * per-mode V-int handlers (s2.asm:781 Vint_Level, 899, 1000, 1046, 1083,
     * 1138). {@code V_Int} branches to {@code Vint_Lag} while
     * {@code Vint_routine} is 0 (s2.asm:483-484), and neither
     * {@code Vint_Lag} (s2.asm:529-584) nor {@code Vint0_noWater}
     * (s2.asm:586-641) calls {@code ProcessDMAQueue} -- they only run the
     * sound driver, water palette, scroll and sprite-table work. A queued
     * transfer therefore survives a lag frame and is retired by the next real
     * V-int, exactly as S1 keeps {@code f_sonframechg} set across
     * {@code VBlank_Lag}.
     */
    SONIC_2_PROCESS_DMA_QUEUE(true) {
        @Override
        public boolean services(PlcLifecyclePhase phase) {
            if (phase == null) {
                return false;
            }
            return switch (phase) {
                // Level_TtlCard's wait loop (s2.asm:4914-4925) and the 25
                // leave-loop iterations that follow InitPlayers
                // (s2.asm:5060-5066) both run with
                // Vint_routine = VintID_TitleCard, so their V-int is
                // Vint_TitleCard (s2.asm:1005), which calls ProcessDMAQueue at
                // s2.asm:1046 -- the same site listed above. Player DPLC work
                // those pre-Level_MainLoop passes queue is therefore drained
                // by the very next V-int and never survives into the level's
                // first main-loop row.
                case CONTINUE_SCREEN, ORDINARY_LEVEL, SPECIAL_STAGE, SPECIAL_STAGE_RESULTS,
                        TWO_PLAYER_RESULTS, CREDITS_TEXT, CREDITS_DEMO,
                        ENDING, POST_CREDITS, NORMAL_PAUSE,
                        SPECIAL_STAGE_PAUSE, LEVEL_TITLE_CARD -> true;
                case TITLE_SCREEN, LEVEL_SELECT,
                        PALETTE_FADE, CREDITS_DEMO_FADE, LAG -> false;
            };
        }
    },
    EVERY_CLAIM_WITHOUT_PLAYER_ART_AUDIT(false) {
        @Override
        public boolean services(PlcLifecyclePhase phase) {
            return phase != null;
        }
    };

    private final boolean supportsPlayerDynamicArtAudit;

    DynamicArtDmaServiceModel(boolean supportsPlayerDynamicArtAudit) {
        this.supportsPlayerDynamicArtAudit = supportsPlayerDynamicArtAudit;
    }

    public abstract boolean services(PlcLifecyclePhase phase);

    public boolean supportsPlayerDynamicArtAudit() {
        return supportsPlayerDynamicArtAudit;
    }
}

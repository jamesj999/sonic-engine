package com.openggf.game.sonic3k;

/**
 * Owner ID constants and priority levels for S3K palette ownership registry.
 *
 * <p>Owner IDs are namespaced strings that identify which subsystem wrote a
 * palette color. Priority determines which write wins when multiple owners
 * target the same color index within a single frame.
 */
public final class S3kPaletteOwners {
    public static final String AIZ_RESIZE_MUTATION = "s3k.aiz.resizeMutation";
    public static final String AIZ_FIRE_TRANSITION = "s3k.aiz.fireTransition";
    public static final String AIZ_BOSS_SMALL = "s3k.aiz.bossSmall";
    public static final String AIZ_MINIBOSS = "s3k.aiz.miniboss";
    public static final String AIZ_MINIBOSS_CUTSCENE = "s3k.aiz.minibossCutscene";
    public static final String AIZ_END_BOSS = "s3k.aiz.endBoss";
    public static final String AIZ_INTRO_SUPER_PALETTE = "s3k.aiz.introSuperPalette";
    public static final String AIZ_INTRO_CUTSCENE_KNUCKLES = "s3k.aiz.introCutsceneKnuckles";
    public static final String AIZ_INTRO_EMERALD_PALETTE = "s3k.aiz.introEmeraldPalette";
    public static final String SUPER_PALETTE = "s3k.super.palette";
    public static final String AIZ1_ANPAL = "s3k.aiz1.anpal";
    public static final String AIZ2_WATER_CYCLE = "s3k.aiz2.waterCycle";
    public static final String AIZ2_TORCH_CYCLE = "s3k.aiz2.torchCycle";
    public static final String ZONE_EVENT_PALETTE_LOAD = "s3k.zoneEvents.paletteLoad";
    public static final String HCZ_EVENT_PALETTE = "s3k.hcz.eventPalette";
    public static final String HCZ_WATER_CYCLE = "s3k.hcz.waterCycle";
    public static final String HCZ_CAVE_LIGHTING = "s3k.hcz.caveLighting";
    public static final String HCZ_MINIBOSS = "s3k.hcz.miniboss";
    public static final String HCZ_END_BOSS = "s3k.hcz.endBoss";
    public static final String MHZ_MINIBOSS = "s3k.mhz.miniboss";
    public static final String MHZ_END_BOSS = "s3k.mhz.endBoss";
    public static final String MHZ_END_BOSS_DEFEAT_FADE = "s3k.mhz.endBossDefeatFade";
    public static final String MGZ_MINIBOSS = "s3k.mgz.miniboss";
    public static final String MGZ_TUNNELBOT = "s3k.mgz.tunnelbot";
    public static final String MGZ_END_BOSS = "s3k.mgz.endBoss";
    public static final String MGZ_POST_BOSS_FADE = "s3k.mgz.postBossFade";
    public static final String MHZ1_CUTSCENE_RESTORE = "s3k.mhz1.cutsceneRestore";
    public static final String LBZ_ZONE_CYCLE = "s3k.lbz.zoneCycle";
    public static final String LBZ_MINIBOSS = "s3k.lbz.miniboss";
    public static final String BPZ_ZONE_CYCLE = "s3k.bpz.zoneCycle";
    public static final String CGZ_ZONE_CYCLE = "s3k.cgz.zoneCycle";
    public static final String EMZ_ZONE_CYCLE = "s3k.emz.zoneCycle";
    public static final String SLOTS_ZONE_CYCLE = "s3k.slots.zoneCycle";
    public static final String PACHINKO_ZONE_CYCLE = "s3k.pachinko.zoneCycle";
    public static final String ICZ_STARTUP_PALETTE = "s3k.icz.startupPalette";
    public static final String ICZ_ZONE_CYCLE = "s3k.icz.zoneCycle";
    public static final String LRZ_ZONE_CYCLE = "s3k.lrz.zoneCycle";
    /**
     * CNZ AnPal palette ownership for the bumper, background, and tertiary
     * animation tables.
     *
     * <p>CNZ's ROM keeps separate normal and water palette tables, but this
     * engine slice routes the normal-table writes through the registry and
     * mirrors them into the underwater surface so both planes stay aligned.
     */
    public static final String CNZ_ANPAL = "s3k.cnz.anpal";
    /**
     * Owner ID for the Act 1 miniboss palette installed by the arena-entry
     * gate.
     *
     * <p>ROM: {@code loc_6D9A8} (sonic3k.asm:144830) loads
     * {@code Pal_CNZMiniboss} into palette line 1 via
     * {@code PalLoad_Line1}. The engine routes the same 32-byte write through
     * the shared palette ownership registry so post-defeat code (and tests)
     * can observe the explicit owner.
     */
    public static final String CNZ_MINIBOSS = "s3k.cnz.miniboss";
    public static final String CNZ_END_BOSS = "s3k.cnz.endBoss";
    public static final String CNZ2_CUTSCENE_RESTORE = "s3k.cnz2.cutsceneRestore";
    public static final String ICZ_MINIBOSS = "s3k.icz.miniboss";
    public static final String ICZ_END_BOSS = "s3k.icz.endBoss";
    /**
     * Reserved owner ID for the Knuckles-route teleporter palette override.
     *
     * <p>Task 5 introduces the owner now so Task 8's teleporter object can
     * claim palette line 2 through the shared registry instead of introducing
     * a CNZ-local side channel.
     */
    public static final String CNZ_TELEPORTER = "s3k.cnz.teleporter";
    /**
     * Owner ID for the CNZ Act 2 lights-off / water flash effect.
     *
     * <p>ROM: {@code loc_62480} writes {@code Pal_CNZFlash} into
     * {@code Normal_palette_line_3} (engine palette lines 2 and 3) across six
     * flicker steps. The cutscene button leaves the dark variant in place
     * (lights off); the water-level button restores {@code Pal_CNZ+$20} (lights
     * on). The write uses {@link #PRIORITY_LIGHTS_FLASH}, below the zone cycle,
     * because ROM runs {@code AnimatePalettes} after object updates, so the CNZ
     * bumper/background color cycling (colors 7-11) still wins over the flash —
     * the bumpers keep glowing while the rest of the line goes dark.
     */
    public static final String CNZ_LIGHTS_FLASH = "s3k.cnz.lightsFlash";

    public static final String HPZ_ZONE_CYCLE = "s3k.hpz.zoneCycle";
    public static final String HPZ_MASTER_EMERALD = "s3k.hpz.masterEmerald";
    public static final String HPZ_PALETTE_CONTROL = "s3k.hpz.paletteControl";

    /**
     * Below {@link #PRIORITY_ZONE_CYCLE} so the CNZ AnPal color cycling (applied
     * after object updates in ROM) still wins on its colors. See
     * {@link #CNZ_LIGHTS_FLASH}.
     */
    public static final int PRIORITY_LIGHTS_FLASH = 90;
    public static final int PRIORITY_ZONE_CYCLE = 100;
    public static final int PRIORITY_ZONE_EVENT = 150;
    public static final int PRIORITY_OBJECT_OVERRIDE = 200;
    public static final int PRIORITY_CUTSCENE_OVERRIDE = 300;

    private S3kPaletteOwners() {
    }
}

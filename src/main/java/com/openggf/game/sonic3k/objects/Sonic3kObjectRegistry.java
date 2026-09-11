package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.objects.badniks.BlastoidBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.BatbotBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.BuggernautBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.CaterkillerJrBodyInstance;
import com.openggf.game.sonic3k.objects.badniks.ButterdroidBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.CaterkillerJrHeadInstance;
import com.openggf.game.sonic3k.objects.badniks.CluckoidBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.CorkeyBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.Flybot767BadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.JawzBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.MantisBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.MonkeyDudeBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.MushmeanieBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.BubblesBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.OrbinautBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.PenguinatorBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.PoindexterBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.RibotBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.TunnelbotBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance;
import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.badniks.BloominatorBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.RhinobotBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.S3kBadnikProjectileInstance;
import com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.SparkleBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance;
import com.openggf.game.sonic3k.objects.badniks.StarPointerBadnikInstance;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss2Instance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossArenaHelperInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossDefeatFragmentChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance;
import com.openggf.level.objects.AbstractObjectRegistry;
import com.openggf.level.objects.FixedSstSlotSink;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;

/**
 * Object registry for Sonic 3 &amp; Knuckles.
 *
 * <p>Route-critical objects, bosses, badniks, and cutscene controllers are
 * registered as concrete factories as they are ported. Remaining unknown or
 * low-priority object IDs still fall back to {@link PlaceholderObjectInstance}
 * for debug rendering. Object names are derived from the SK Set 1 pointer
 * table ({@code Object pointers - SK Set 1.asm}) in the S3K disassembly.
 *
 * <p>S3K uses two zone-set pointer tables (SK Set 1 for S&amp;K zones,
 * S3 Set for S3 zones) which remap some IDs above 110. The names here
 * use SK Set 1 as the canonical source; S3-only remappings share the
 * same underlying object names in most cases.
 */
public class Sonic3kObjectRegistry extends AbstractObjectRegistry {

    @Override
    public ObjectSlotLayout objectSlotLayout() {
        return ObjectSlotLayout.SONIC_3K;
    }

    @Override
    public void installFixedSstObjects(
            int romZoneId,
            int act,
            FixedSstSlotSink slots) {
        if (romZoneId == Sonic3kZoneIds.ZONE_HCZ) {
            // SpawnLevelMainSprites installs Obj_HCZWaterSplash subtype 1 at
            // Dynamic_object_RAM+2 (absolute SST slot 5). The player-facing
            // routine is coordinated by HCZWaterSkimHandler, but the native
            // record must remain allocated before Load_Sprites scans the pool.
            slots.install(5, HCZWaterSkimSlotObjectInstance::new);
        }
        if (romZoneId == Sonic3kZoneIds.ZONE_MHZ) {
            slots.install(4, MhzPollenSpawnerInstance::new);
        }
    }

    @Override
    protected void registerDefaultFactories() {
        factories.put(Sonic3kObjectIds.MONITOR,
                (spawn, registry) -> new Sonic3kMonitorObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.PATH_SWAP, (spawn, registry) -> new Sonic3kPathSwapObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.AIZ_HOLLOW_TREE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzTwistedVineObjectInstance(spawn);
                    }
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizHollowTreeObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.COLLAPSING_PLATFORM,
                (spawn, registry) -> new Sonic3kCollapsingPlatformObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.AIZLRZ_ROCK,
                (spawn, registry) -> new AizLrzRockObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.AIZ_RIDE_VINE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzPulleyLiftObjectInstance(spawn);
                    }
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizRideVineObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.SPRING,
                (spawn, registry) -> new Sonic3kSpringObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.SPIKES,
                (spawn, registry) -> new Sonic3kSpikeObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.AIZ1_TREE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzCurledVineObjectInstance(spawn);
                    }
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new Aiz1TreeObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ1_ZIPLINE_PEG,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzStickyVineObjectInstance(spawn);
                    }
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new Aiz1ZiplinePegObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MHZ_SWING_BAR_HORIZONTAL,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzSwingBarHorizontalObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.AIZ_GIANT_RIDE_VINE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzSwingBarVerticalObjectInstance(spawn);
                    }
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizGiantRideVineObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ_TUBE_ELEVATOR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzSwingVineObjectInstance(spawn);
                    }
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzTubeElevatorInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.BREAKABLE_WALL,
                (spawn, registry) -> new BreakableWallObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.TWISTED_RAMP,
                (spawn, registry) -> new Sonic3kTwistedRampObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.FBZ_DEZ_PLAYER_LAUNCHER,
                (spawn, registry) -> new FbzDezPlayerLauncherInstance(spawn));
        factories.put(Sonic3kObjectIds.COLLAPSING_BRIDGE,
                (spawn, registry) -> new CollapsingBridgeObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.MHZ_MUSHROOM_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzMushroomPlatformObjectInstance(spawn);
                    }
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzMovingPlatformObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.MHZ_MUSHROOM_PARACHUTE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzMushroomParachuteObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.MHZ_MUSHROOM_CATAPULT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzMushroomCatapultObjectInstance(spawn);
                    }
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzExplodingTriggerInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.UPDRAFT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL) {
                        return new UpdraftObjectInstance(spawn);
                    }
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzTriggerBridgeInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_PLAYER_LAUNCHER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzPlayerLauncherInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ_FLAME_THROWER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzFlameThrowerObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ_RIDE_GRAPPLE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzRideGrappleInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ_CUP_ELEVATOR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzCupElevatorInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ_CUP_ELEVATOR_POLE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzCupElevatorPoleInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ_PIPE_PLUG,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzPipePlugObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_SPIN_LAUNCHER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzSpinLauncherObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_LOWERING_GRAPPLE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzLoweringGrappleObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.AUTOMATIC_TUNNEL,
                (spawn, registry) -> new AutomaticTunnelObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.MHZ_MUSHROOM_CAP,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL && currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                        return new MhzMushroomCapObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_ROLLING_DRUM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL) {
                        // Id $31 in the SKL pointer set is Obj_LRZCollapsingBridge
                        // (sonic3k.asm:77383), an unrelated object.
                        return new LrzCollapsingBridgeInstance(spawn);
                    }
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzRollingDrumInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AUTO_SPIN,
                (spawn, registry) -> new AutoSpinObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.CORK_FLOOR,
                (spawn, registry) -> new CorkFloorObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.AIZ_FLIPPING_BRIDGE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizFlippingBridgeObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_DRAW_BRIDGE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizDrawBridgeObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.SINKING_MUD,
                (spawn, registry) -> {
                    // $4F is Obj_SinkingMud only in Sprite_Listing3 (SK Set 1);
                    // Sprite_ListingK (SK Set 2) routes the same id to Obj_DEZStaircase.
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new SinkingMudObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_COLLAPSING_LOG_BRIDGE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizCollapsingLogBridgeObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_FALLING_LOG,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizFallingLogObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_SPIKED_LOG,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizSpikedLogObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_DISAPPEARING_FLOOR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizDisappearingFloorObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.INVISIBLE_BLOCK,
                (spawn, registry) -> new Sonic3kInvisibleBlockObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.INVISIBLE_HURT_BLOCK_H,
                (spawn, registry) -> new Sonic3kInvisibleHurtBlockHObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.INVISIBLE_HURT_BLOCK_V,
                (spawn, registry) -> new Sonic3kInvisibleHurtBlockVObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.FLOATING_PLATFORM,
                (spawn, registry) -> new FloatingPlatformObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.MGZ_SWINGING_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZSwingingPlatformObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZLBZ_SMASHING_PILLAR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZLBZSmashingPillarObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZLBZ_SMASHING_PILLAR_ALT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZLBZSmashingPillarObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ_GATE_LASER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzGateLaserObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_ALARM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new LbzAlarmObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_TWISTING_LOOP,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZTwistingLoopObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_TRIGGER_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZTriggerPlatformObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_HEAD_TRIGGER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZHeadTriggerObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_MOVING_SPIKE_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZMovingSpikePlatformObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_SWINGING_SPIKE_BALL,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZSwingingSpikeBallObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_DASH_TRIGGER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZDashTriggerObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_PULLEY,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZPulleyObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_TOP_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZTopPlatformObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_TOP_LAUNCHER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MGZTopLauncherObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.BUMPER,
                (spawn, registry) -> {
                    if (currentRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE) {
                        return new PachinkoBumperObjectInstance(spawn);
                    }
                    if (currentRomZoneId() == Sonic3kZoneIds.ZONE_CNZ) {
                        return new CnzBumperObjectInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet()));
                });
        factories.put(Sonic3kObjectIds.CNZ_TRIANGLE_BUMPER,
                (spawn, registry) -> currentRomZoneId() == Sonic3kZoneIds.ZONE_CNZ
                        ? new CnzTriangleBumperObjectInstance(spawn)
                        : new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet())));
        factories.put(Sonic3kObjectIds.BUBBLER,
                (spawn, registry) -> new BubblerObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.ICZ_BREAKABLE_WALL,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczBreakableWallObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.BUTTON,
                (spawn, registry) -> new Sonic3kButtonObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.CUTSCENE_BUTTON,
                (spawn, registry) -> {
                    // ROM: Obj_CutsceneButton dispatches on subtype via off_65C40
                    // (sonic3k.asm:133947): $00 -> loc_65C56, $02 -> loc_65C72,
                    // $04 -> loc_65C78 (CNZ water/geyser/flash), $06 -> loc_65CAC
                    // (CNZ vacuum tubes). The CNZ2 layout (Levels/CNZ/Object Pos/2.bin)
                    // places the first-encounter button at X=$1E00 with subtype $04
                    // and the second-encounter (vacuum-tube) button at X=$4780 with
                    // subtype $06. Route both CNZ2 cutscene button variants here.
                    if (currentRomZoneId() == Sonic3kZoneIds.ZONE_CNZ
                            && (spawn.subtype() == 4 || spawn.subtype() == 6)) {
                        return new Cnz2CutsceneButtonInstance(spawn);
                    }
                    if (currentRomZoneId() == Sonic3kZoneIds.ZONE_HCZ) {
                        return new Hcz2CutsceneButtonInstance(spawn);
                    }
                    return new S3kCutsceneButtonObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.STAR_POST,
                (spawn, registry) -> new Sonic3kStarPostObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.HCZ_TWISTING_LOOP,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZTwistingLoopObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_WATER_SPLASH,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZWaterSplashObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_WATER_DROP,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZWaterDropObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.TENSION_BRIDGE,
                (spawn, registry) -> new TensionBridgeObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.HCZ_WATER_RUSH,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZWaterRushObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_CGZ_FAN,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZCGZFanObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_LARGE_FAN,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZLargeFanObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_HAND_LAUNCHER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZHandLauncherObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_CONVEYOR_BELT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZConveyorBeltObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_CONVEYOR_SPIKE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZConveyorSpikeObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_BLOCK,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZBlockObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_BALLOON,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzBalloonInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_CANNON,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzCannonInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_RISING_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzRisingPlatformInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_TRAP_DOOR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzTrapDoorInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_LIGHT_BULB,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzLightBulbInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_HOVER_FAN,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzHoverFanInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_CYLINDER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzCylinderInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_VACUUM_TUBE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzVacuumTubeInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_GIANT_WHEEL,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzGiantWheelInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_SPIRAL_TUBE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzSpiralTubeInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_BARBER_POLE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzBarberPoleObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_WIRE_CAGE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzWireCageObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_SPINNING_COLUMN,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZSpinningColumnObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_SNAKE_BLOCKS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZSnakeBlocksObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_FREEZER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczFreezerObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_PATH_FOLLOW_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczPathFollowPlatformObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_CRUSHING_COLUMN,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczCrushingColumnObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_SEGMENT_COLUMN,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczSegmentColumnObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_SWINGING_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczSwingingPlatformObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_STALAGTITE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczStalagtiteObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_ICE_CUBE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczIceCubeObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_ICE_SPIKES,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczIceSpikesObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_HARMFUL_ICE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczHarmfulIceObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_SNOW_PILE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczSnowPileObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_TENSION_PLATFORM,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczTensionPlatformObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_ICE_BLOCK,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczIceBlockObjectInstance(spawn);
                });
        factories.put(0x3C,
                (spawn, registry) -> new DoorObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.STILL_SPRITE,
                (spawn, registry) -> new StillSpriteInstance(spawn));
        factories.put(Sonic3kObjectIds.ANIMATED_STILL_SPRITE,
                (spawn, registry) -> new AnimatedStillSpriteInstance(spawn));
        factories.put(Sonic3kObjectIds.HCZ_BREAKABLE_BAR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZBreakableBarObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_WATER_WALL,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HCZWaterWallObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_FOREGROUND_PLANT,
                (spawn, registry) -> new AizForegroundPlantInstance(spawn));
        factories.put(Sonic3kObjectIds.HIDDEN_MONITOR,
                (spawn, registry) -> new S3kHiddenMonitorInstance(spawn));
        factories.put(Sonic3kObjectIds.SS_ENTRY_RING,
                (spawn, registry) -> new Sonic3kSSEntryRingObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.GUMBALL_MACHINE,
                (spawn, registry) -> new GumballMachineObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.GUMBALL_TRIANGLE_BUMPER,
                (spawn, registry) -> new GumballTriangleBumperObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR,
                (spawn, registry) -> new CnzWaterLevelCorkFloorInstance(spawn));
        factories.put(Sonic3kObjectIds.CNZ_WATER_LEVEL_BUTTON,
                (spawn, registry) -> new CnzWaterLevelButtonInstance(spawn));
        factories.put(Sonic3kObjectIds.PACHINKO_TRIANGLE_BUMPER,
                (spawn, registry) -> currentRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE
                        ? new PachinkoTriangleBumperObjectInstance(spawn)
                        : new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet())));
        factories.put(Sonic3kObjectIds.PACHINKO_FLIPPER,
                (spawn, registry) -> currentRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE
                        ? new PachinkoFlipperObjectInstance(spawn)
                        : new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet())));
        factories.put(Sonic3kObjectIds.PACHINKO_ENERGY_TRAP,
                (spawn, registry) -> currentRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE
                        ? new PachinkoEnergyTrapObjectInstance(spawn)
                        : new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet())));
        factories.put(Sonic3kObjectIds.PACHINKO_PLATFORM,
                (spawn, registry) -> currentRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE
                        ? new PachinkoPlatformObjectInstance(spawn)
                        : new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet())));
        factories.put(Sonic3kObjectIds.PACHINKO_ITEM_ORB,
                (spawn, registry) -> currentRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE
                        ? new PachinkoItemOrbObjectInstance(spawn)
                        : new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet())));
        factories.put(Sonic3kObjectIds.PACHINKO_MAGNET_ORB,
                (spawn, registry) -> currentRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE
                        ? new PachinkoMagnetOrbObjectInstance(spawn)
                        : new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), getCurrentZoneSet())));
        factories.put(Sonic3kObjectIds.GUMBALL_ITEM,
                (spawn, registry) -> new GumballItemObjectInstance(spawn));
        factories.put(Sonic3kObjectIds.BLOOMINATOR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new MadmoleBadnikInstance(spawn);
                    }
                    return new BloominatorBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.RHINOBOT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new MushmeanieBadnikInstance(spawn);
                    }
                    return new RhinobotBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MONKEY_DUDE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new DragonflyBadnikInstance(spawn);
                    }
                    return new MonkeyDudeBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CATERKILLER_JR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new ButterdroidBadnikInstance(spawn);
                    }
                    return new CaterkillerJrHeadInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.JAWZ,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        if (currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                            return new MhzEndBossInstance(spawn);
                        }
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new JawzBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.BLASTOID,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new BlastoidBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.BUGGERNAUT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new BuggernautBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.TURBO_SPIKER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new TurboSpikerBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MEGA_CHOPPER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MegaChopperBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.POINDEXTER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new PoindexterBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.SPIKER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new SpikerBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.BUBBLES_BADNIK,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new BubblesBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MANTIS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MantisBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.TUNNELBOT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new TunnelbotBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_MINIBOSS_CUTSCENE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new CluckoidBadnikInstance(spawn);
                    }
                    return new AizMinibossCutsceneInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_MINIBOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        if (currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                            return new MhzMinibossTreeInstance(spawn);
                        }
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizMinibossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.AIZ_END_BOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        if (currentRomZoneId() == Sonic3kZoneIds.ZONE_MHZ) {
                            return new MhzMinibossInstance(spawn);
                        }
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new AizEndBossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_MINIBOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HczMinibossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.HCZ_END_BOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new HczEndBossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_MINIBOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzMinibossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CNZ_END_BOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CnzEndBossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CLAMER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new ClamerObjectInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.SPARKLE,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new SparkleBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.BATBOT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new BatbotBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.PENGUINATOR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new PenguinatorBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.STAR_POINTER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new StarPointerBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.SNALE_BLASTER,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new SnaleBlasterBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ORBINAUT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new OrbinautBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.RIBOT,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new RibotBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.CORKEY,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new CorkeyBadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.FLYBOT_767,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new Flybot767BadnikInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_MINIBOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczMinibossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.ICZ_END_BOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new IczEndBossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_MINIBOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MgzMinibossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_END_BOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MgzEndBossInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MGZ_END_BOSS_KNUX,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet != S3kZoneSet.S3KL) {
                        return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                    }
                    return new MgzEndBossKnuxInstance(spawn);
                });
        factories.put(Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL) {
                        return new Mhz1CutsceneKnucklesInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.MHZ1_CUTSCENE_BUTTON,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.SKL) {
                        return new Mhz1CutsceneButtonInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.CUTSCENE_KNUCKLES,
                (spawn, registry) -> {
                    // ROM: Obj_CutsceneKnuckles uses subtype as longword index into
                    // CutsceneKnuckles_Index: 0=AIZ1, 4=AIZ2, 8=HCZ2, 12=CNZ2A, 16=CNZ2B, etc.
                    int subtype = spawn.subtype();
                    if (subtype == 8) {
                        return new CutsceneKnucklesHcz2Instance(spawn);
                    }
                    if (subtype == 12) {
                        return new CutsceneKnucklesCnz2AInstance(spawn);
                    }
                    if (subtype == 16) {
                        return new CutsceneKnucklesCnz2BInstance(spawn);
                    }
                    if (subtype == 0x14) {
                        return new CutsceneKnucklesLbz1Instance(spawn);
                    }
                    if (subtype == 0x18) {
                        return new CutsceneKnucklesLbz2Instance(spawn);
                    }
                    if (subtype == 0x1C) {
                        return new CutsceneKnucklesMhz1Instance(spawn);
                    }
                    if (subtype == 0x20) {
                        return new CutsceneKnucklesMhz2Instance(spawn);
                    }
                    if (subtype == 0x30) {
                        return new CutsceneKnucklesSkIntroInstance(spawn);
                    }
                    // Default: AIZ2 variant (handles subtypes 0 and 4)
                    return new CutsceneKnucklesAiz2Instance(spawn);
                });
        factories.put(Sonic3kObjectIds.LBZ1_ROBOTNIK,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new Lbz1RobotnikEventController(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ2_ROBOTNIK_SHIP,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new Lbz2RobotnikShipInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_KNUX_PILLAR,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzKnuxPillarInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_MINIBOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzMinibossInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_FINAL_BOSS_1,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzFinalBoss1Instance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_END_BOSS,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzEndBossInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_FINAL_BOSS_2,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzFinalBoss2Instance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_FINAL_BOSS_KNUX,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_MINIBOSS_BOX,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzMinibossBoxInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
        factories.put(Sonic3kObjectIds.LBZ_MINIBOSS_BOX_KNUX,
                (spawn, registry) -> {
                    S3kZoneSet zoneSet = getCurrentZoneSet();
                    if (zoneSet == S3kZoneSet.S3KL && currentRomZoneId() == Sonic3kZoneIds.ZONE_LBZ) {
                        return new LbzMinibossBoxKnuxInstance(spawn);
                    }
                    return new PlaceholderObjectInstance(spawn, getPrimaryName(spawn.objectId(), zoneSet));
                });
    }

    private S3kZoneSet getCurrentZoneSet() {
        int romZoneId = currentRomZoneId();
        if (romZoneId < 0) {
            return S3kZoneSet.S3KL;
        }
        return S3kZoneSet.forZone(romZoneId);
    }

    /**
     * Returns the object name for the given zone set.
     * For S3KL (zones 0-6), delegates to {@link #getPrimaryName(int)}.
     * For SKL (zones 7-13), uses the SK Set 2 pointer table names.
     */
    public String getPrimaryName(int objectId, S3kZoneSet zoneSet) {
        if (zoneSet == S3kZoneSet.SKL) {
            return getSklName(objectId);
        }
        return getPrimaryName(objectId);
    }

    /**
     * Returns the object name from the SK Set 1 pointer table (S3KL).
     * Names match the disassembly label with the {@code Obj_} prefix stripped.
     * Used for zones 0-6 (AIZ, HCZ, MGZ, CNZ, FBZ, ICZ, LBZ).
     */
    @Override
    public String getPrimaryName(int objectId) {
        return switch (objectId) {
            case 0x00 -> "Ring";
            case 0x01 -> "Monitor";
            case 0x02 -> "PathSwap";
            case 0x03 -> "AIZHollowTree";
            case 0x04 -> "CollapsingPlatform";
            case 0x05 -> "AIZLRZEMZRock";
            case 0x06 -> "AIZRideVine";
            case 0x07 -> "Spring";
            case 0x08 -> "Spikes";
            case 0x09 -> "AIZ1Tree";
            case 0x0A -> "AIZ1ZiplinePeg";
            case 0x0C -> "AIZGiantRideVine";
            case 0x0D -> "BreakableWall";
            case 0x0E -> "TwistedRamp";
            case 0x0F -> "CollapsingBridge";
            case 0x10 -> "LBZTubeElevator";
            case 0x11 -> "LBZMovingPlatform";
            case 0x12 -> "LBZUnusedElevator";
            case 0x13 -> "LBZExplodingTrigger";
            case 0x14 -> "LBZTriggerBridge";
            case 0x15 -> "LBZPlayerLauncher";
            case 0x16 -> "LBZFlameThrower";
            case 0x17 -> "LBZRideGrapple";
            case 0x18 -> "LBZCupElevator";
            case 0x19 -> "LBZCupElevatorPole";
            case 0x1A -> "LBZUnusedTiltingBridge";
            case 0x1B -> "LBZPipePlug";
            case 0x1D -> "LBZUnusedBarPlatform";
            case 0x1E -> "LBZSpinLauncher";
            case 0x1F -> "LBZLoweringGrapple";
            case 0x20 -> "MGZLBZSmashingPillar";
            case 0x21 -> "LBZGateLaser";
            case 0x22 -> "LBZAlarm";
            case 0x23 -> "LBZUnusedForceFall";
            case 0x24 -> "AutomaticTunnel";
            case 0x26 -> "AutoSpin";
            case 0x27 -> "S2LavaMarker";
            case 0x28 -> "InvisibleBlock";
            case 0x29 -> "AIZDisappearingFloor";
            case 0x2A -> "CorkFloor";
            case 0x2B -> "AIZFlippingBridge";
            case 0x2C -> "AIZCollapsingLogBridge";
            case 0x2D -> "AIZFallingLog";
            case 0x2E -> "AIZSpikedLog";
            case 0x2F -> "StillSprite";
            case 0x30 -> "AnimatedStillSprite";
            case 0x31 -> "LBZRollingDrum";
            case 0x32 -> "AIZDrawBridge";
            case 0x33 -> "Button";
            case 0x34 -> "StarPost";
            case 0x35 -> "AIZForegroundPlant";
            case 0x36 -> "HCZBreakableBar";
            case 0x37 -> "HCZWaterRush";
            case 0x38 -> "HCZCGZFan";
            case 0x39 -> "HCZLargeFan";
            case 0x3A -> "HCZHandLauncher";
            case 0x3B -> "HCZWaterWall";
            case 0x3C -> "Door";
            case 0x3D -> "RetractingSpring";
            case 0x3E -> "HCZConveyorBelt";
            case 0x3F -> "HCZConveyorSpike";
            case 0x40 -> "HCZBlock";
            case 0x41 -> "CNZBalloon";
            case 0x42 -> "CNZCannon";
            case 0x43 -> "CNZRisingPlatform";
            case 0x44 -> "CNZTrapDoor";
            case 0x45 -> "CNZLightBulb";
            case 0x46 -> "CNZHoverFan";
            case 0x47 -> "CNZCylinder";
            case 0x48 -> "CNZVacuumTube";
            case 0x49 -> "CNZGiantWheel";
            case 0x4A -> "Bumper";
            case 0x4B -> "CNZTriangleBumpers";
            case 0x4C -> "CNZSpiralTube";
            case 0x4D -> "CNZBarberPoleSprite";
            case 0x4E -> "CNZWireCage";
            case 0x4F -> "SinkingMud";
            case 0x50 -> "MGZTwistingLoop";
            case 0x51 -> "FloatingPlatform";
            case 0x52 -> "MGZSmashingPillar";
            case 0x53 -> "MGZSwingingPlatform";
            case 0x54 -> "Bubbler";
            case 0x55 -> "MGZHeadTrigger";
            case 0x56 -> "MGZMovingSpikePlatform";
            case 0x57 -> "MGZTriggerPlatform";
            case 0x58 -> "MGZSwingingSpikeBall";
            case 0x59 -> "MGZDashTrigger";
            case 0x5A -> "MGZPulley";
            case 0x5B -> "MGZTopPlatform";
            case 0x5C -> "MGZTopLauncher";
            case 0x5D -> "CGZTriangleBumpers";
            case 0x5E -> "CGZBladePlatform";
            case 0x5F -> "2PRetractingSpring";
            case 0x60 -> "BPZElephantBlock";
            case 0x61 -> "BPZBalloon";
            case 0x62 -> "DPZDisolvingSandBar";
            case 0x63 -> "DPZButton";
            case 0x64 -> "2PItem";
            case 0x65 -> "2PGoalMarker";
            case 0x66 -> "EMZDripper";
            case 0x67 -> "HCZSnakeBlocks";
            case 0x68 -> "HCZSpinningColumn";
            case 0x69 -> "HCZTwistingLoop";
            case 0x6A -> "InvisibleHurtBlockH";
            case 0x6B -> "InvisibleHurtBlockV";
            case 0x6C -> "TensionBridge";
            case 0x6D -> "HCZWaterSplash";
            case 0x6E -> "WaterDrop";
            case 0x6F -> "FBZWireCage";
            case 0x70 -> "FBZWireCageStationary";
            case 0x71 -> "FBZFloatingPlatform";
            case 0x72 -> "FBZChainLink";
            case 0x73 -> "FBZMagneticSpikeBall";
            case 0x74 -> "FBZMagneticPlatform";
            case 0x75 -> "FBZSnakePlatform";
            case 0x76 -> "FBZBentPipe";
            case 0x77 -> "FBZRotatingPlatform";
            case 0x78 -> "FBZDEZPlayerLauncher";
            case 0x79 -> "FBZDisappearingPlatform";
            case 0x7A -> "FBZScrewDoor";
            case 0x7B -> "FBZSpinningPole";
            case 0x7C -> "FBZPropeller";
            case 0x7D -> "FBZPiston";
            case 0x7E -> "FBZPlatformBlocks";
            case 0x7F -> "FBZMissileLauncher";
            case 0x80 -> "HiddenMonitor";
            case 0x81 -> "EggCapsule";
            case 0x82 -> "CutsceneKnuckles";
            case 0x83 -> "CutsceneButton";
            case 0x84 -> "AIZPlaneIntro";
            case 0x85 -> "SSEntryRing";
            case 0x86 -> "GumballMachine";
            case 0x87 -> "GumballTriangleBumper";
            case 0x88 -> "CNZWaterLevelCorkFloor";
            case 0x89 -> "CNZWaterLevelButton";
            case 0x8A -> "FBZExitHall";
            case 0x8B -> "SpriteMask";
            case 0x8C -> "Bloominator";
            case 0x8D -> "Rhinobot";
            case 0x8E -> "MonkeyDude";
            case 0x8F -> "CaterKillerJr";
            case 0x90 -> "AIZMinibossCutscene";
            case 0x91 -> "AIZMiniboss";
            case 0x92 -> "AIZEndBoss";
            case 0x93 -> "Jawz";
            case 0x94 -> "Blastoid";
            case 0x95 -> "Buggernaut";
            case 0x96 -> "TurboSpiker";
            case 0x97 -> "MegaChopper";
            case 0x98 -> "Poindexter";
            case 0x99 -> "HCZMiniboss";
            case 0x9A -> "HCZEndBoss";
            case 0x9B -> "BubblesBadnik";
            case 0x9C -> "Spiker";
            case 0x9D -> "Mantis";
            case 0x9E -> "Tunnelbot";
            case 0x9F -> "MGZMiniboss";
            case 0xA0 -> "MGZ2DrillingRobotnik";
            case 0xA1 -> "MGZEndBoss";
            case 0xA2 -> "MGZEndBossKnux";
            case 0xA3 -> "Clamer";
            case 0xA4 -> "Sparkle";
            case 0xA5 -> "Batbot";
            case 0xA6 -> "CNZMiniboss";
            case 0xA7 -> "CNZEndBoss";
            case 0xA8 -> "Blaster";
            case 0xA9 -> "TechnoSqueek";
            case 0xAA -> "FBZMiniboss";
            case 0xAB -> "FBZ2Subboss";
            case 0xAC -> "FBZEndBoss";
            case 0xAD -> "Penguinator";
            case 0xAE -> "StarPointer";
            case 0xAF -> "ICZCrushingColumn";
            case 0xB0 -> "ICZPathFollowPlatform";
            case 0xB1 -> "ICZBreakableWall";
            case 0xB2 -> "ICZFreezer";
            case 0xB3 -> "ICZSegmentColumn";
            case 0xB4 -> "ICZSwingingPlatform";
            case 0xB5 -> "ICZStalagtite";
            case 0xB6 -> "ICZIceCube";
            case 0xB7 -> "ICZIceSpikes";
            case 0xB8 -> "ICZHarmfulIce";
            case 0xB9 -> "ICZSnowPile";
            case 0xBA -> "ICZTensionPlatform";
            case 0xBB -> "ICZIceBlock";
            case 0xBC -> "ICZMiniboss";
            case 0xBD -> "ICZEndBoss";
            case 0xBE -> "SnaleBlaster";
            case 0xBF -> "Ribot";
            case 0xC0 -> "Orbinaut";
            case 0xC1 -> "Corkey";
            case 0xC2 -> "Flybot767";
            case 0xC3 -> "LBZ1Robotnik";
            case 0xC4 -> "LBZMinibossBox";
            case 0xC5 -> "LBZMinibossBoxKnux";
            case 0xC6 -> "LBZ2RobotnikShip";
            case 0xC8 -> "LBZKnuxPillar";
            case 0xC9 -> "LBZMiniboss";
            case 0xCA -> "LBZFinalBoss1";
            case 0xCB -> "LBZEndBoss";
            case 0xCC -> "LBZFinalBoss2";
            case 0xCD -> "LBZFinalBossKnux";
            case 0xCE -> "FBZExitDoor";
            case 0xCF -> "FBZEggPrison";
            case 0xD0 -> "FBZSpringPlunger";
            case 0xE0 -> "FBZWallMissile";
            case 0xE1 -> "FBZMine";
            case 0xE2 -> "FBZElevator";
            case 0xE3 -> "FBZTrapSpring";
            case 0xE4 -> "FBZFlamethrower";
            case 0xE5 -> "FBZSpiderCrane";
            case 0xE6 -> "PachinkoTriangleBumper";
            case 0xE7 -> "PachinkoFlipper";
            case 0xE8 -> "PachinkoEnergyTrap";
            case 0xE9 -> "PachinkoInvisibleUnknown";
            case 0xEA -> "PachinkoPlatform";
            case 0xEB -> "GumballItem";
            case 0xEC -> "PachinkoMagnetOrb";
            case 0xED -> "PachinkoItemOrb";
            case 0xFF -> "FBZMagneticPendulum";
            default -> String.format("S3K_Obj_%02X", objectId & 0xFF);
        };
    }

    /**
     * Returns the object name from the SK Set 2 pointer table (SKL).
     * Used for zones 7-13 (MHZ, SOZ, LRZ, SSZ, DEZ, DDZ).
     * Shared objects (Ring, Monitor, Spring, etc.) return the same name as S3KL.
     */
    private String getSklName(int objectId) {
        return switch (objectId) {
            case 0x00 -> "Ring";
            case 0x01 -> "Monitor";
            case 0x02 -> "PathSwap";
            case 0x03 -> "MHZTwistedVine";
            case 0x04 -> "CollapsingPlatform";
            case 0x05 -> "AIZLRZEMZRock";
            case 0x06 -> "MHZPulleyLift";
            case 0x07 -> "Spring";
            case 0x08 -> "Spikes";
            case 0x09 -> "MHZCurledVine";
            case 0x0A -> "MHZStickyVine";
            case 0x0B -> "MHZSwingBarHorizontal";
            case 0x0C -> "MHZSwingBarVertical";
            case 0x0D -> "BreakableWall";
            case 0x0E -> "TwistedRamp";
            case 0x0F -> "CollapsingBridge";
            case 0x10 -> "MHZSwingVine";
            case 0x11 -> "MHZMushroomPlatform";
            case 0x12 -> "MHZMushroomParachute";
            case 0x13 -> "MHZMushroomCatapult";
            case 0x14 -> "Updraft";
            case 0x15 -> "LRZCorkscrew";
            case 0x16 -> "LRZWallRide";
            case 0x17 -> "LRZSinkingRock";
            case 0x18 -> "LRZFallingSpike";
            case 0x19 -> "LRZDoor";
            case 0x1A -> "LRZBigDoor";
            case 0x1B -> "LRZFireballLauncher";
            case 0x1C -> "LRZButtonHorizontal";
            case 0x1D -> "LRZShootingTrigger";
            case 0x1E -> "LRZDashElevator";
            case 0x1F -> "LRZLavaFall";
            case 0x20 -> "LRZSwingingSpikeBall";
            case 0x21 -> "LRZSmashingSpikePlatform";
            case 0x22 -> "LRZSpikeBall";
            case 0x23 -> "MHZMushroomCap";
            case 0x24 -> "AutomaticTunnel";
            case 0x25 -> "LRZChainedPlatforms";
            case 0x26 -> "AutoSpin";
            case 0x27 -> "S2LavaMarker";
            case 0x28 -> "InvisibleBlock";
            case 0x29 -> "LRZFlameThrower";
            case 0x2A -> "CorkFloor";
            case 0x2B -> "LRZOrbitingSpikeBallH";
            case 0x2C -> "LRZOrbitingSpikeBallV";
            case 0x2D -> "LRZSolidMovingPlatforms";
            case 0x2E -> "LRZSolidRock";
            case 0x2F -> "StillSprite";
            case 0x30 -> "AnimatedStillSprite";
            case 0x31 -> "LRZCollapsingBridge";
            case 0x32 -> "LRZTurbineSprites";
            case 0x33 -> "Button";
            case 0x34 -> "StarPost";
            case 0x35 -> "AIZForegroundPlant";
            case 0x36 -> "HCZBreakableBar";
            case 0x37 -> "LRZSpikeBallLauncher";
            case 0x38 -> "SOZQuicksand";
            case 0x39 -> "SOZSpawningSandBlocks";
            case 0x3A -> "SOZPathSwap";
            case 0x3B -> "SOZLoopFallthrough";
            case 0x3C -> "Door";
            case 0x3D -> "RetractingSpring";
            case 0x3E -> "SOZPushableRock";
            case 0x3F -> "SOZSpringVine";
            case 0x40 -> "SOZRisingSandWall";
            case 0x41 -> "SOZLightSwitch";
            case 0x42 -> "SOZFloatingPillar";
            case 0x43 -> "SOZSwingingPlatform";
            case 0x44 -> "SOZBreakableSandRock";
            case 0x45 -> "SOZPushSwitch";
            case 0x46 -> "SOZDoor";
            case 0x47 -> "SOZSandCork";
            case 0x48 -> "SOZRapelWire";
            case 0x49 -> "SOZSolidSprites";
            case 0x4A -> "DEZFloatingPlatform";
            case 0x4B -> "DEZTiltingBridge";
            case 0x4C -> "DEZHangCarrier";
            case 0x4D -> "DEZTorpedoLauncher";
            case 0x4E -> "DEZLiftPad";
            case 0x4F -> "DEZStaircase";
            case 0x50 -> "DEZConveyorBelt";
            case 0x51 -> "FloatingPlatform";
            case 0x52 -> "DEZLightning";
            case 0x53 -> "DEZConveyorPad";
            case 0x54 -> "Bubbler";
            case 0x55 -> "DEZEnergyBridge";
            case 0x56 -> "DEZEnergyBridgeCurved";
            case 0x57 -> "DEZTunnelLauncher";
            case 0x58 -> "DEZGravitySwitch";
            case 0x59 -> "DEZTeleporter";
            case 0x5A -> "DEZGravityTube";
            case 0x5B -> "DEZGravitySwap";
            case 0x5C -> "DEZGravityHub";
            case 0x5D -> "DEZRetractingSpring";
            case 0x5E -> "DEZHoverMachine";
            case 0x5F -> "DEZGravityRoom";
            case 0x60 -> "DEZBumperWall";
            case 0x61 -> "DEZGravityPuzzle";
            case 0x6A -> "InvisibleHurtBlockH";
            case 0x6B -> "InvisibleHurtBlockV";
            case 0x6C -> "TensionBridge";
            case 0x6D -> "InvisibleShockBlock";
            case 0x6E -> "InvisibleLavaBlock";
            case 0x74 -> "SSZRetractingSpring";
            case 0x75 -> "SSZSwingingCarrier";
            case 0x76 -> "SSZRotatingPlatform";
            case 0x77 -> "SSZCutsceneBridge";
            case 0x78 -> "FBZDEZPlayerLauncher";
            case 0x79 -> "SSZHPZTeleporter";
            case 0x7A -> "SSZElevatorBar";
            case 0x7B -> "SSZCollapsingBridgeDiagonal";
            case 0x7C -> "SSZCollapsingBridge";
            case 0x7D -> "SSZBouncyCloud";
            case 0x7E -> "SSZCollapsingColumn";
            case 0x7F -> "SSZFloatingPlatform";
            case 0x80 -> "HiddenMonitor";
            case 0x81 -> "EggCapsule";
            case 0x82 -> "CutsceneKnuckles";
            case 0x83 -> "CutsceneButton";
            case 0x84 -> "AIZPlaneIntro";
            case 0x85 -> "SSEntryRing";
            case 0x86 -> "GumballMachine";
            case 0x87 -> "GumballTriangleBumper";
            case 0x88 -> "CNZWaterLevelCorkFloor";
            case 0x89 -> "CNZWaterLevelButton";
            case 0x8A -> "FBZExitHall";
            case 0x8B -> "SpriteMask";
            case 0x8C -> "Madmole";
            case 0x8D -> "Mushmeanie";
            case 0x8E -> "Dragonfly";
            case 0x8F -> "Butterdroid";
            case 0x90 -> "Cluckoid";
            case 0x91 -> "MHZMinibossTree";
            case 0x92 -> "MHZMiniboss";
            case 0x93 -> "MHZEndBoss";
            case 0x94 -> "Skorp";
            case 0x95 -> "Sandworm";
            case 0x96 -> "Rockn";
            case 0x97 -> "SOZMiniboss";
            case 0x98 -> "SOZEndBoss";
            case 0x99 -> "Fireworm";
            case 0x9A -> "Iwamodoki";
            case 0x9B -> "Toxomister";
            case 0x9C -> "LRZRockCrusher";
            case 0x9D -> "LRZMiniboss";
            case 0x9E -> "LRZ3Autoscroll";
            case 0xA0 -> "EggRobo";
            case 0xA1 -> "SSZGHZBoss";
            case 0xA2 -> "SSZMTZBoss";
            case 0xA3 -> "SSZEndBoss";
            case 0xA4 -> "Spikebonker";
            case 0xA5 -> "Chainspike";
            case 0xA6 -> "DEZMiniboss";
            case 0xA7 -> "DEZEndBoss";
            case 0xA8 -> "MHZ1CutsceneKnuckles";
            case 0xA9 -> "MHZ1CutsceneButton";
            case 0xAA -> "Hyudoro";
            case 0xAB -> "SOZCapsuleHyudoro";
            case 0xAC -> "SOZCapsule";
            case 0xAD -> "LRZ3Platform";
            case 0xAE -> "LRZ2CutsceneKnuckles";
            case 0xAF -> "SSZCutsceneButton";
            case 0xB0 -> "HPZMasterEmerald";
            case 0xB1 -> "HPZPaletteControl";
            case 0xB2 -> "KnuxFinalBossCrane";
            case 0xB3 -> "StartNewLevel";
            case 0xB4 -> "HPZSuperEmerald";
            case 0xB5 -> "HPZSSEntryControl";
            case 0xB6 -> "DDZEndBoss";
            case 0xB7 -> "DDZAsteroid";
            case 0xB8 -> "DDZMissile";
            default -> String.format("S3K_Obj_%02X", objectId & 0xFF);
        };
    }
}

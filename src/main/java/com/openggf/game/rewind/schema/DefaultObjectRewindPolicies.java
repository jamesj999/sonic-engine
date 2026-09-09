package com.openggf.game.rewind.schema;

import com.openggf.game.rewind.FieldKey;
import com.openggf.level.objects.ObjectInstance;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

final class DefaultObjectRewindPolicies {
    private static final Set<String> STRUCTURAL_OBJECT_FIELD_NAMES = Set.of(
            "anchor",
            "arm",
            "armChild",
            "backForearm",
            "backLowerLeg",
            "backThigh",
            "ball",
            "barrel",
            "barrierWall",
            "beam",
            "boss",
            "button",
            "buttonObject",
            "chainChild",
            "child",
            "childInstance",
            "container",
            "currentPipe",
            "deathEggRobot",
            "dezWindow",
            "dispenser",
            "emeraldMaster",
            "emeralds",
            "eyes",
            "fanParent",
            "flame",
            "flameChild",
            "frontForearm",
            "frontLowerLeg",
            "frontThigh",
            "glowChild1",
            "glowChild2",
            "head",
            "hurtChild",
            "jet",
            "knuckles",
            "laser",
            "laserShooter",
            "launcher",
            "ledWindow",
            "leftArm",
            "leftLauncher",
            "leftWall",
            "linkedHead",
            "linkedLog",
            "linkedPlatform",
            "linkedSplash",
            "mainBoss",
            "mainWall",
            "makerParent",
            "parent",
            "parentBlock",
            "parentCheckpoint",
            "parentGeyser",
            "parentPipe",
            "parentPlatform",
            "parentRing",
            "parentStarPost",
            "placeholder",
            "planeChild",
            "plasmaLauncher",
            "platformChild",
            "platformParent",
            "platformReleaser",
            "propeller",
            "pump",
            "reflectionChild",
            "rider",
            "rightArm",
            "rightLauncher",
            "rightWall",
            "robotnik",
            "robotnikParent",
            "robotnikPlatform",
            "seesaw",
            "sensorChild",
            "shell",
            "shellChild",
            "ship",
            "shipChild",
            "shoulder",
            "sourceShip",
            "spikeChild",
            "targetingSensor",
            "topSpike",
            "trailEmitter",
            "turbine",
            "upperArm",
            "walkerFire",
            "waterColumn",
            "wreckingBall"
    );

    private static final Map<FieldKey, RewindFieldPolicy> EXACT_FIELD_POLICIES = Map.ofEntries(
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.bosses.Sonic1BossBlockInstance", "grabbingBoss"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.bosses.Sonic1FZBossInstance", "cylinders"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.bosses.Sonic1ScrapEggmanInstance$ScrapEggmanButton", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance", "grabbedBlock"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1EggPrisonButtonObjectInstance", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1EggPrisonObjectInstance", "buttonObject"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1EggPrisonObjectInstance", "lastPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1EndingSonicObjectInstance", "emeraldMaster"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1EndingSonicObjectInstance", "emeralds"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1TryAgainEggmanObjectInstance", "textRenderer"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1GrassFireObjectInstance", "children"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1GrassFireObjectInstance", "parentPlatform"), RewindFieldPolicy.CAPTURED),
            // Grass Fire must remain compact-schema eligible so required parent/list refs are captured.
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1GrassFireObjectInstance", "slopeData"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1GlassBlockObjectInstance", "reflectionChild"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1JunctionObjectInstance", "childInstance"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1LargeGrassyPlatformObjectInstance", "fireChildren"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1LargeGrassyPlatformObjectInstance", "walkerFire"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1LavaGeyserObjectInstance", "makerParent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1LavaGeyserObjectInstance", "parentGeyser"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1LavaWallObjectInstance", "mainWall"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1FloatingBlockObjectInstance", "syz3TunnelRealBlock"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1FloatingBlockObjectInstance", "syz3TunnelProxyBlock"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance$ChainChild", "artKey"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance$ChainChild", "frame"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance$ChainChild", "collisionType"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance$ChainChild", "originX"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1BumperObjectInstance", "pendingTouchedPlayer"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.Sonic1TeleporterObjectInstance", "controlledPlayer"), RewindFieldPolicy.CAPTURED),
            // Caterkiller head/body linkage is structural, rebuilt on restore: each restored
            // body re-registers with the nearest live head (adoptBodySegmentForRewind) and the
            // parent chain + back-reference are rebuilt from restore order (relinkForRewind),
            // mirroring the already-structural 'head' ref (STRUCTURAL_OBJECT_FIELD_NAMES).
            // Capturing these identity references instead would fight the relink rebuild.
            // Covered by TestS1BadnikChildGraphRewind.
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.badniks.Sonic1CaterkillerBadnikInstance", "bodySegments"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.badniks.Sonic1CaterkillerBodyInstance", "parentState"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic1.objects.badniks.Sonic1OrbinautBadnikInstance", "spikes"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.badniks.GrabberBadnikInstance", "grabbedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.badniks.GrabberBadnikInstance", "pendingGrabPlayer"), RewindFieldPolicy.CAPTURED),
            // CPZ spin tube fully object-controls each rider along a per-character path; losing the
            // per-player traversal slot across rewind strands a mid-path player frozen (the tube restarts
            // every character at state 0 and only re-detects players inside the entry box).
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.CPZSpinTubeObjectInstance", "characterStates"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.EggPrisonButtonObjectInstance", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.EggPrisonObjectInstance", "buttonObject"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.EggPrisonObjectInstance", "lastPlayer"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.BreakablePlatingObjectInstance", "lastNativeMainPlayer"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.FallingPillarObjectInstance", "childInstance"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.FlipperObjectInstance", "animationState"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.FlipperObjectInstance", "launchCooldown"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.FlipperObjectInstance", "lockedPlayerPrevSuppressed"), RewindFieldPolicy.CAPTURED),
            // Sibling of lockedPlayerPrevSuppressed: the saved pre-lock pinball mode restored on release.
            // Capturing only the suppressed flag left a rewound release clearing the player's pinball mode.
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.FlipperObjectInstance", "lockedPlayerPrevPinballMode"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.FlipperObjectInstance", "playerFlipperState"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.LauncherBallObjectInstance", "playerCooldowns"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.LauncherBallObjectInstance", "playerStates"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.LauncherBallObjectInstance", "playerVelocities"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.LauncherSpringObjectInstance", "playerStates"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.MCZRotPformsObjectInstance", "children"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.MCZRotPformsObjectInstance", "initialized"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.MCZRotPformsObjectInstance", "moveTable"), RewindFieldPolicy.TRANSIENT),
            // Inverse Obj6A owner linkage is rebuilt from captured children after restore settles.
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.MCZRotPformsObjectInstance", "owner"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.MCZBrickObjectInstance", "displayChild"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.OOZLauncherObjectInstance", "playerStates"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SidewaysPformObjectInstance", "linkedPlatform"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SlidingSpikeObjectInstance", "peer"), RewindFieldPolicy.CAPTURED),
            // Speed launcher catapult: the launch (upward -0x400 pop) is applied on the destination frame to
            // whichever players are in these sets. accelerationRiders is not rebuilt from live contact, and a
            // restore landing on the launch frame with empty sets silently drops/mis-launches the rider.
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SpeedLauncherObjectInstance", "accelerationRiders"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SpeedLauncherObjectInstance", "standingPlayers"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SpiralObjectInstance", "cylinderAngles"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SpiralObjectInstance", "ridingPlayers"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.bosses.ARZBossArrow", "mainBoss"), RewindFieldPolicy.CAPTURED),
            // Obj11 bridge subsprite children (s2.asm:21966-21988) are real SST
            // occupants, but the parent/child links between them are rebuilt by
            // the segment's own recreate path
            // (BridgeSegmentObjectInstance#recreateForRewind ->
            // BridgeObjectInstance#adoptSegmentForRewind), exactly like
            // LbzTubeElevatorInstance#overlayChild and
            // SnaleBlasterBadnikInstance#cover. The links are declared
            // `transient` on the fields themselves; no policy entry belongs here.
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.PointPokeyObjectInstance", "slotMachineManager"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.RivetObjectInstance", "lastNativeMainPlayer"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SeesawBallObjectInstance", "originalSpawn"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SeesawObjectInstance", "standingPlayer1"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SeesawObjectInstance", "standingPlayer2"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SpringboardObjectInstance", "launchPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SuperSonicStarsObjectInstance", "player"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance", "displayChild"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic2.objects.TornadoObjectInstance", "thrusterFollowerChild"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AbstractS3kFloatingEndEggCapsuleInstance", "explosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AbstractS3kUprightEggCapsuleInstance", "explosionController"), RewindFieldPolicy.DEFERRED),
            // AIZ collapsing log bridge: once collapsing it stops being a solid surface (onSolidContact no longer
            // fires), so an empty standingPlayers cannot self-heal and the collapse/final loops never knock the
            // stranded rider off; ejectedPlayers guards a knocked-off rider from re-standing/double-ejecting.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance", "standingPlayers"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance", "ejectedPlayers"), RewindFieldPolicy.CAPTURED),
            // AIZ draw bridge stays a live solid surface for the whole collapse countdown, so onSolidContact
            // refills standingPlayers every frame the player stands — the set is re-derived, not durable state.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizDrawBridgeObjectInstance", "standingPlayers"), RewindFieldPolicy.TRANSIENT),
            // AIZ flipping bridge never terminates; each per-segment solid frame re-adds standing players via
            // onSolidContact, so an empty list self-heals next frame (mirrors SeesawObjectInstance standing refs).
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizFlippingBridgeObjectInstance", "standingPlayers"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizEndBossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            // Hardware queue facades/handles are rebound from their captured ledger ordinals after restore.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizEndBossInstance", "bossArtQueue"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizEndBossInstance", "bossArtHandle"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizPlaneIntroInstance", "introSpriteArtQueue"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizPlaneIntroInstance", "planeArtHandle"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizPlaneIntroInstance", "emeraldArtHandle"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.HCZLargeFanObjectInstance", "artQueue"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.HCZLargeFanObjectInstance", "artHandle"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance", "artQueue"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.HCZWaterWallObjectInstance", "artHandle"), RewindFieldPolicy.TRANSIENT),
            // MHZ1 cutscene button rebinds the module queue from knuxPeerArtOrdinal in
            // retireKnucklesPeerArt() before it can claim, so the facade is re-derived.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance", "knuxPeerArtQueue"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance", "queuedResultsArt"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.HczEndBossGeyserCutscene", "artQueue"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.HczEndBossGeyserCutscene", "artHandle"), RewindFieldPolicy.TRANSIENT),
            // The ship child rebinds to the nearest recreated live ship; its accumulator remains captured.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Lbz2RobotnikShipInstance$GradualCameraMaxXChild", "parent"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizMinibossCutsceneInstance", "explosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizMinibossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizFallingLogObjectInstance$FallingLogChild", "linkedSplash"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizFallingLogObjectInstance$SplashChild", "linkedLog"), RewindFieldPolicy.CAPTURED),
            // Ride-vine link chains carry the rendered swing/deploy state of a ride mechanic.
            // A live rewind hold renders restored state WITHOUT re-running update(), so a
            // dropped chain would show the vine links detached from the captured root/handle
            // mid-ride. The sibling root Segment `first` is already captured (it matches the
            // in-place plain-state-holder gate); capturing the Segment[] keeps the whole vine
            // coherent. CAPTURED is safe: Segment is a codec-backed plain-state-holder and the
            // final array restores in place (fixed length 3 / segmentCount-1 from the spawn
            // subtype, so the recreated length always matches). Covered by TestAizRideVineRewind.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizGiantRideVineObjectInstance", "chain"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizRideVineObjectInstance", "chain"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizSpikedLogObjectInstance$SpikedLogCollisionChild", "parent"), RewindFieldPolicy.CAPTURED),
            // Cross-frame per-player fire-refresh reject counter; without an explicit CAPTURED
            // policy the identity-keyed map drops the class onto the generic scalar path.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizTransitionFloorObjectInstance", "zeroDistanceRejects"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.ClamerObjectInstance", "springChildSlot"), RewindFieldPolicy.CAPTURED),
            // Collapsing bridge wave: riders are seeded once at the collapse trigger and isSolidFor is true only
            // for seeded riders during the wave; an empty set after a mid-wave restore drops every rider early
            // (support lost immediately) and never re-seeds (the trigger will not re-run).
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance", "collapseWaveRiders"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CutsceneKnucklesRockChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CutsceneKnucklesCnz2AInstance", "blockingWall"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CutsceneKnuxCnz2WallInstance", "owner"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesLiftChild", "player"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesRouteSwitchChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance$SideDrillChild", "capturedPlayer"), RewindFieldPolicy.CAPTURED),
            // ROM $44(a0): the arm's player back-reference, set at capture and never
            // cleared, consumed by the loc_8D724 off-camera despawn release.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance$SideDrillChild", "releaseTargetPlayer"), RewindFieldPolicy.CAPTURED),
            // Per-frame TouchResponse scratch, cleared at the start of every update before any snapshot
            // boundary (always null when captured). Central TRANSIENT instead of a per-object annotation.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MadmoleBadnikInstance$SideDrillChild", "pendingCapturePlayer"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance", "capturedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance", "pendingMainPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MegaChopperBadnikInstance", "pendingSidekickPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MantisBadnikInstance", "child"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MantisBadnikInstance$MantisChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MushmeanieBadnikInstance", "shellChild"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.MushmeanieBadnikInstance$ShellChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance$LinkedBodyChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance$LinkedBodyChild", "followAnchor"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance$WingChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.SnaleBlasterBadnikInstance", "cover"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance", "leftLauncher"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance", "pendingLaunchPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance", "rightLauncher"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance", "topSpike"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerSideLauncherChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerTopSpikeChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance", "shellChild"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance", "waterfallOverlayChild"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerShellChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerShellChild", "trailEmitter"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerTrailEmitter", "shell"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerWaterfallOverlayChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MgzEndBossKnuxEggCapsuleInstance", "owner"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance", "endCannon"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance", "magnetChild"), RewindFieldPolicy.CAPTURED),
            // ChildObjDat_6EDE4 encodes the signed field offset in the captured spawn subtype;
            // CnzEndBossFieldChild.recreateForRewind() deterministically reconstructs it.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.CnzEndBossFieldChild", "xOffset"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleInstance", "explosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.HczEndBossInstance", "cameraGate"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.HczEndBossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance", "arenaCameraGate"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance", "effectChildren"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance", "extendedFrostCaptures"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance", "nativeP1FrostCaptureOwner"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance", "nativeP2FrostCaptureOwner"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance", "robotnikExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.IczEndBossInstance", "structuralChildren"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance$MhzEndBossWalkoffPrepChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.MhzEndBossRobotnikShipFlameInstance", "parent"), RewindFieldPolicy.CAPTURED),
            // Per-player pole ride state: latch flag + trackFixed position accumulator (integrates
            // x-speed each frame) + inner-track flag. Cross-frame; identity-keyed map needs an
            // explicit CAPTURED policy or the whole map is silently skipped from the compact set.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzBarberPoleObjectInstance", "riders"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzBumperObjectInstance", "pendingPrimaryTouch"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzBumperObjectInstance", "pendingSidekickTouch"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Cnz2CutsceneButtonInstance", "spawnedFlash"), RewindFieldPolicy.CAPTURED),
            // The button's captured spawnedFlash identity is authoritative; the flash relinks
            // this inverse owner reference in afterRewindRestoreSettled().
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzLightsFlashChildInstance", "owner"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzCannonInstance", "capturedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzCannonInstance", "releasedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzCylinderInstance", "releasedJumpSolidSkipPlayer"), RewindFieldPolicy.CAPTURED),
            // Per-player attach latch that gates the one-time attach setup (animation restart +
            // stick_to_convex); dropping it re-runs the setup and restarts the ride animation
            // one frame after a mid-ride rewind restore.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzGiantWheelInstance", "attachedPlayers"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzTeleporterInstance", "beam"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzMinibossCoilInstance", "boss"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzMinibossSparkInstance", "boss"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzMinibossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            // Per-player lift countdown (subtype-scaled timer decremented each lift frame);
            // dropping it resets or aborts the tube lift on a mid-lift rewind restore.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzVacuumTubeInstance", "activeLiftFrames"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzWaterLevelCorkFloorInstance", "corkFloor"), RewindFieldPolicy.CAPTURED),
            // Per-player cage ride state: latch/phase/rideAngle/cooldown/standingBit. Cross-frame.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CnzWireCageObjectInstance", "riders"), RewindFieldPolicy.CAPTURED),
            // effectiveVelTable is a derived reference to a static constant fragment-velocity
            // table, re-selected identically from subtype/config on recreateForRewind; it has
            // no per-frame state to capture and no int[][] codec, so capturing it would knock
            // CorkFloor onto the generic path and silently drop the CAPTURED rollingBreakPlayer.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CorkFloorObjectInstance", "effectiveVelTable"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CorkFloorObjectInstance", "rollingBreakPlayer"), RewindFieldPolicy.CAPTURED),
            // Immutable glow layout metadata is reconstructed from captured ObjectSpawn.subtype.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizIntroEmeraldGlowChild", "variant"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizIntroEmeraldGlowChild", "xOffset"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.AizIntroEmeraldGlowChild", "yOffset"), RewindFieldPolicy.TRANSIENT),
            // Gumball live child links are rebuilt from the restored child graph in
            // afterRewindRestoreSettled(). Capturing them as object refs can crash
            // after the dispenser/springs have been removed from ObjectManager while
            // the machine still retains stale Java references.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.GumballMachineObjectInstance", "dispenser"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.GumballMachineObjectInstance", "springOriginalPositions"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.GumballMachineObjectInstance", "springs"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.HczMinibossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.HczMinibossInstance", "rocketTouchChildren"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.HczEndBossBladeImpactExplosion", "boss"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleButton", "parent"), RewindFieldPolicy.CAPTURED),
            // Fixed 2-slot rider array whose RiderState holds a live player reference plus the
            // cross-frame twist angle / horizontal-swing distance. A final array of a
            // reference-bearing plain state holder is not auto-captured by the scalar policy
            // (isSupportedValueType rejects it) and, because the class has other scalar fields,
            // it stays compact-eligible while silently dropping the whole rider array without
            // this explicit CAPTURED policy.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.HCZSpinningColumnObjectInstance", "riders"), RewindFieldPolicy.CAPTURED),
            // Test/diagnostic handle only. Capture-cloud behavior is owned by its
            // independent SST; the parent never reads this link during gameplay,
            // and it may outlive the child's registered rewind identity.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczFreezerObjectInstance", "lastCaptureCloud"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$CaptureCloud", "frozenBlock"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczFreezerObjectInstance$FrozenPlayerBlock", "capturedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczMinibossExplosionControllerChild", "parent"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczCrushingColumnObjectInstance$BottomDecoration", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance$SpikeHurtChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$Segment", "mappingFrame"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$Segment", "previous"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$Segment", "root"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.IczMinibossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Lbz2RobotnikShipInstance", "attachedKnuckles"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Lbz2RobotnikShipInstance", "carriedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance", "deferredExplosionControllerChild"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.LbzMinibossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.LbzMinibossInstance", "knucklesFightParent"), RewindFieldPolicy.CAPTURED),
            // The MGZ zoom source is immutable ROM art loaded lazily for an instance-local
            // renderer. Pattern[] and renderer fields already use declared-type transient policy.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance", "airZoomCueSourceRaster"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance", "endBossDefeatExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MgzMinibossInstance", "defeatExplosionController"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MgzMinibossInstance$DrillArmChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MgzMinibossInstance$KnucklesSpikePlatformChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MGZTopLauncherObjectInstance", "child"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance", "playerStates"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MGZPulleyObjectInstance", "grabbedPlayers"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzMushroomParachuteObjectInstance", "grabbedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzMushroomParachuteObjectInstance", "nativeP2GrabbedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1Instance", "parentButton"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz1PeerInstance", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance", "spawnedDoor"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Mhz1CutsceneButtonInstance", "spawnedKnuckles"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Mhz1CutsceneDoorInstance", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Mhz1CutsceneKnucklesInstance$Mhz1CutscenePlayerTwoStopper", "owner"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzStickyVineObjectInstance", "capturedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzMinibossFlameInstance", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzMinibossEscapeShardInstance", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzSwingBarHorizontalObjectInstance", "hangStates"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzSwingBarHorizontalObjectInstance", "hangingPlayers"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.MhzSwingBarVerticalObjectInstance", "playerStates"), RewindFieldPolicy.CAPTURED),
            // Pending-touch latch mirroring CnzBumperObjectInstance: the touch listener records the
            // colliding sprite here and the update loop consumes+clears it one frame later to apply
            // the bounce response outside the touch callback.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoBumperObjectInstance", "pendingPrimaryTouch"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoBumperObjectInstance", "pendingSidekickTouch"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance", "capturedPlayer"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance$EnergyTrapBeamChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance$EnergyTrapColumnChild", "parent"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoFlipperObjectInstance", "lockedPlayer"), RewindFieldPolicy.CAPTURED),
            // Sibling of lockedPlayer: ROM Obj_PachinkoFlipper keeps one standing counter
            // byte per rider ($36(a0) for Player_1, $37(a0) for Player_2,
            // sonic3k.asm:96389-96397), so the engine carries one lock reference each.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoFlipperObjectInstance", "lockedSidekick"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoItemOrbObjectInstance", "rewardItem"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.PachinkoMagnetOrbObjectInstance", "playerStates"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance", "playerRef"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance", "elements"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance", "controller"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.S3kSlotRingRewardObjectInstance", "controller"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.S3kSlotSpikeRewardObjectInstance", "controller"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.SinkingMudObjectInstance", "trackedPlayers"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Sonic3kInvincibilityStarsObjectInstance", "player"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance", "animationState"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance", "monitorContentsSlot"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance", "p1SolidContact"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance", "p2SolidContact"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance", "p2RecentlyClearedSolidContact"), RewindFieldPolicy.DEFERRED),
            // SS-entry flash parent links are structural and can outlive a ring removed from
            // ObjectManager while the flash finishes the transition. Restore relinks by position.
            Map.entry(new FieldKey("com.openggf.game.sonic3k.objects.Sonic3kSSEntryFlashObjectInstance", "parentRing"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.level.objects.AbstractBadnikInstance", "destructionConfig"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.level.objects.AbstractMonitorObjectInstance", "effectTarget"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.level.objects.AbstractObjectInstance", "dynamicSpawn"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.level.objects.AbstractObjectInstance", "spawn"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.level.objects.boss.AbstractBossChild", "dynamicSpawn"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.level.objects.boss.AbstractBossInstance", "dynamicSpawn"), RewindFieldPolicy.DEFERRED),
            // Boss childComponents is an identity-bearing live graph. The compact collection
            // codec retains its exact managed children and their roles for restore/relink.
            Map.entry(new FieldKey("com.openggf.level.objects.boss.AbstractBossInstance", "childComponents"), RewindFieldPolicy.CAPTURED),
            // childSpawnOrdinalCounters is fully derivable from live children and is
            // re-derived post-restore by AbstractBossInstance#afterRewindRestoreSettled()
            // (max(child.getChildOrdinal()) + 1 per class) once every child's own restore
            // has settled -- not merely "doesn't matter", so DEFERRED rather than TRANSIENT.
            Map.entry(new FieldKey("com.openggf.level.objects.boss.AbstractBossInstance", "childSpawnOrdinalCounters"), RewindFieldPolicy.DEFERRED),
            Map.entry(new FieldKey("com.openggf.level.objects.BreathingBubbleInstance", "owner"), RewindFieldPolicy.CAPTURED),
            Map.entry(new FieldKey("com.openggf.level.objects.InvincibilityStarsObjectInstance", "player"), RewindFieldPolicy.TRANSIENT),
            Map.entry(new FieldKey("com.openggf.level.objects.ShieldObjectInstance", "player"), RewindFieldPolicy.TRANSIENT)
    );

    /** Read-only view of the exact-field policy table for guard tests. */
    static Map<FieldKey, RewindFieldPolicy> exactFieldPoliciesForAudit() {
        return EXACT_FIELD_POLICIES;
    }

    static RewindFieldPolicy policyFor(Field field) {
        RewindFieldPolicy exactPolicy = EXACT_FIELD_POLICIES.get(FieldKey.of(field));
        if (exactPolicy != null) {
            return exactPolicy;
        }
        if (ObjectInstance.class.isAssignableFrom(field.getType())
                && STRUCTURAL_OBJECT_FIELD_NAMES.contains(field.getName())) {
            return RewindFieldPolicy.TRANSIENT;
        }
        return null;
    }

    private DefaultObjectRewindPolicies() {
    }
}

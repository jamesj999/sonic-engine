# Sonic 3&K Object Implementation Checklist

Generated: 2026-08-28 21:05:13

> **Coverage meaning:** "Implemented" means the object ID resolves to a
> concrete registry/runtime owner for that zone's zone set (S3KL zones 0-6,
> SKL zones 7-13) in `Sonic3kObjectRegistry`, rather than a
> `PlaceholderObjectInstance`; the totals count id x zone-set rows. It does not
> certify full ROM behavior, rendering, collision, every subtype, boss phase,
> or rewind/trace parity. Dynamically allocated child objects can also remain
> absent even when their registered parent is checked; use the current status
> and known-bugs documents for route-completion claims.

## Summary

- **Total unique objects found:** 303
- **Implemented:** 173 (57.1%)
- **Unimplemented:** 130 (42.9%)

## Implemented Objects

| ID | Name | Total Uses | PLC | Zones |
|----|------|------------|-----|-------|
| 0x01 | Monitor | 494 |  | AIZ1, AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2, SSZ1, DEZ1, DEZ2 |
| 0x02 | PathSwap | 728 |  | AIZ1, AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2, SSZ1, DEZ1 |
| 0x03 | AIZHollowTree | 1 | 0x0B | AIZ1 |
| 0x04 | CollapsingPlatform | 34 | 0x0B | AIZ1, AIZ2, ICZ1, ICZ2 |
| 0x05 | AIZLRZEMZRock | 170 | 0x0B | AIZ1, AIZ2, LRZ1, LRZ2 |
| 0x06 | AIZRideVine | 3 | 0x0B, 0x0C | AIZ1, AIZ2 |
| 0x07 | Spring | 491 |  | AIZ1, AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2, SSZ1, DEZ1, DEZ2 |
| 0x08 | Spikes | 693 |  | AIZ1, AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2, SSZ1, DEZ1, DEZ2 |
| 0x09 | AIZ1Tree | 1 | 0x0B | AIZ1 |
| 0x0A | AIZ1ZiplinePeg | 1 | 0x0B | AIZ1 |
| 0x0C | AIZGiantRideVine | 10 |  | AIZ1, AIZ2 |
| 0x0E | TwistedRamp | 18 |  | AIZ1, AIZ2, MHZ1, MHZ2, SOZ1, LRZ1, LRZ2 |
| 0x28 | InvisibleBlock | 249 |  | AIZ1, AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2, SSZ1, DEZ1, DEZ2 |
| 0x2A | CorkFloor | 59 |  | AIZ1, AIZ2, CNZ1, CNZ2, FBZ2, ICZ2, LBZ1, LBZ2 |
| 0x2D | AIZFallingLog | 2 |  | AIZ1, AIZ2 |
| 0x34 | StarPost | 106 |  | AIZ1, AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2, SSZ1, DEZ1, DEZ2 |
| 0x35 | AIZForegroundPlant | 64 |  | AIZ1 |
| 0x51 | FloatingPlatform | 81 |  | AIZ1, AIZ2, HCZ1, MGZ1, MGZ2 |
| 0x85 | SSEntryRing | 77 |  | AIZ1, AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2 |
| 0x8C | Bloominator | 13 |  | AIZ1, AIZ2 |
| 0x8D | Rhinobot | 12 |  | AIZ1, AIZ2 |
| 0x8E | MonkeyDude | 6 |  | AIZ1, AIZ2 |
| 0x90 | AIZMinibossCutscene | 1 |  | AIZ1 |
| 0x0D | BreakableWall | 170 |  | AIZ2, HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ2 |
| 0x26 | AutoSpin | 50 |  | AIZ2, HCZ1, FBZ1, FBZ2, ICZ2, SOZ1, SOZ2 |
| 0x29 | AIZDisappearingFloor | 6 |  | AIZ2 |
| 0x2B | AIZFlippingBridge | 2 |  | AIZ2 |
| 0x2C | AIZCollapsingLogBridge | 7 |  | AIZ2 |
| 0x2E | AIZSpikedLog | 4 |  | AIZ2 |
| 0x2F | StillSprite | 939 |  | AIZ2, HCZ1, HCZ2, MGZ1, MGZ2, FBZ1, FBZ2, LBZ1, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, DEZ1, DEZ2 |
| 0x30 | AnimatedStillSprite | 415 |  | AIZ2, SOZ1, SOZ2, LRZ1, LRZ2 |
| 0x32 | AIZDrawBridge | 2 |  | AIZ2 |
| 0x33 | Button | 37 |  | AIZ2, HCZ1, FBZ1, FBZ2, ICZ1, ICZ2, LRZ1, LRZ2 |
| 0x80 | HiddenMonitor | 45 |  | AIZ2, HCZ1, MGZ1, CNZ1, CNZ2, FBZ1, ICZ2, LBZ1, MHZ1, SOZ1, LRZ1 |
| 0x83 | CutsceneButton | 3 |  | AIZ2, CNZ2 |
| 0x8F | CaterKillerJr | 14 |  | AIZ2 |
| 0x92 | AIZEndBoss | 2 |  | AIZ2 |
| 0x0F | CollapsingBridge | 121 |  | HCZ1, MGZ1, MGZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ1, LBZ2, SOZ1, SOZ2, LRZ2 |
| 0x36 | HCZBreakableBar | 8 |  | HCZ1, HCZ2 |
| 0x37 | HCZWaterRush | 1 |  | HCZ1 |
| 0x38 | HCZCGZFan | 63 |  | HCZ1, HCZ2 |
| 0x39 | HCZLargeFan | 3 |  | HCZ1, HCZ2 |
| 0x3A | HCZHandLauncher | 12 |  | HCZ1, HCZ2 |
| 0x3B | HCZWaterWall | 3 |  | HCZ1 |
| 0x3E | HCZConveyorBelt | 64 |  | HCZ1 |
| 0x3F | HCZConveyorSpike | 6 |  | HCZ1 |
| 0x40 | HCZBlock | 10 |  | HCZ1, HCZ2 |
| 0x54 | Bubbler | 10 |  | HCZ1, HCZ2 |
| 0x6A | InvisibleHurtBlockH | 59 |  | HCZ1, HCZ2, FBZ1, FBZ2, ICZ1, DEZ2 |
| 0x94 | Blastoid | 7 |  | HCZ1 |
| 0x95 | Buggernaut | 8 |  | HCZ1 |
| 0x96 | TurboSpiker | 23 |  | HCZ1, HCZ2 |
| 0x97 | MegaChopper | 17 |  | HCZ1, HCZ2 |
| 0x98 | Poindexter | 40 |  | HCZ1, HCZ2 |
| 0x99 | HCZMiniboss | 1 |  | HCZ1 |
| 0x3C | Door | 63 |  | HCZ2, CNZ1, CNZ2, DEZ1, DEZ2 |
| 0x67 | HCZSnakeBlocks | 50 |  | HCZ2 |
| 0x68 | HCZSpinningColumn | 27 |  | HCZ2 |
| 0x69 | HCZTwistingLoop | 16 |  | HCZ2 |
| 0x6B | InvisibleHurtBlockV | 227 |  | HCZ2, MGZ1, MGZ2, CNZ1, CNZ2, FBZ1, FBZ2, ICZ1, ICZ2, LBZ2, MHZ1, MHZ2, SOZ1, SOZ2, LRZ1, LRZ2, DEZ2 |
| 0x6C | TensionBridge | 19 |  | HCZ2, ICZ1, ICZ2, LRZ1 |
| 0x6D | HCZWaterSplash | 4 |  | HCZ2 |
| 0x6E | WaterDrop | 8 |  | HCZ2 |
| 0x82 | CutsceneKnuckles | 7 |  | HCZ2, CNZ2, LBZ1, LBZ2, MHZ1, MHZ2 |
| 0x93 | Jawz | 19 |  | HCZ2 |
| 0x9A | HCZEndBoss | 2 |  | HCZ2 |
| 0x4F | SinkingMud | 13 |  | MGZ1, MGZ2 |
| 0x50 | MGZTwistingLoop | 7 |  | MGZ1, MGZ2 |
| 0x52 | MGZSmashingPillar | 16 |  | MGZ1, MGZ2 |
| 0x53 | MGZSwingingPlatform | 75 |  | MGZ1, MGZ2 |
| 0x55 | MGZHeadTrigger | 9 |  | MGZ1, MGZ2 |
| 0x56 | MGZMovingSpikePlatform | 5 |  | MGZ1, MGZ2 |
| 0x57 | MGZTriggerPlatform | 49 |  | MGZ1, MGZ2 |
| 0x58 | MGZSwingingSpikeBall | 30 |  | MGZ1, MGZ2 |
| 0x59 | MGZDashTrigger | 17 |  | MGZ1, MGZ2 |
| 0x5B | MGZTopPlatform | 10 |  | MGZ1, MGZ2 |
| 0x5C | MGZTopLauncher | 6 |  | MGZ1, MGZ2 |
| 0x9B | BubblesBadnik | 41 |  | MGZ1, MGZ2 |
| 0x9C | Spiker | 23 |  | MGZ1, MGZ2 |
| 0x9E | Tunnelbot | 3 |  | MGZ1 |
| 0x9F | MGZMiniboss | 1 |  | MGZ1 |
| 0x5A | MGZPulley | 7 |  | MGZ2 |
| 0x9D | Mantis | 24 |  | MGZ2 |
| 0xA2 | MGZEndBossKnux | 1 |  | MGZ2 |
| 0x41 | CNZBalloon | 105 |  | CNZ1, CNZ2 |
| 0x42 | CNZCannon | 5 |  | CNZ1, CNZ2 |
| 0x43 | CNZRisingPlatform | 8 |  | CNZ1, CNZ2 |
| 0x44 | CNZTrapDoor | 2 |  | CNZ1 |
| 0x46 | CNZHoverFan | 209 |  | CNZ1, CNZ2 |
| 0x47 | CNZCylinder | 75 |  | CNZ1, CNZ2 |
| 0x48 | CNZVacuumTube | 94 |  | CNZ1, CNZ2 |
| 0x49 | CNZGiantWheel | 10 |  | CNZ1, CNZ2 |
| 0x4A | Bumper | 126 |  | CNZ1, CNZ2 |
| 0x4B | CNZTriangleBumpers | 31 |  | CNZ1, CNZ2 |
| 0x4C | CNZSpiralTube | 2 |  | CNZ1 |
| 0x4D | CNZBarberPoleSprite | 35 |  | CNZ1, CNZ2 |
| 0x4E | CNZWireCage | 15 |  | CNZ1, CNZ2 |
| 0xA3 | Clamer | 27 |  | CNZ1, CNZ2 |
| 0xA4 | Sparkle | 18 |  | CNZ1, CNZ2 |
| 0xA5 | Batbot | 28 |  | CNZ1, CNZ2 |
| 0xA6 | CNZMiniboss | 1 |  | CNZ1 |
| 0x45 | CNZLightBulb | 2 |  | CNZ2 |
| 0x88 | CNZWaterLevelCorkFloor | 2 |  | CNZ2 |
| 0x89 | CNZWaterLevelButton | 1 |  | CNZ2 |
| 0xA7 | CNZEndBoss | 1 |  | CNZ2 |
| 0x78 | FBZDEZPlayerLauncher | 21 |  | FBZ1, FBZ2, DEZ1 |
| 0xAD | Penguinator | 44 |  | ICZ1, ICZ2 |
| 0xAE | StarPointer | 20 |  | ICZ1, ICZ2 |
| 0xAF | ICZCrushingColumn | 24 |  | ICZ1, ICZ2 |
| 0xB0 | ICZPathFollowPlatform | 9 |  | ICZ1, ICZ2 |
| 0xB1 | ICZBreakableWall | 12 |  | ICZ1 |
| 0xB2 | ICZFreezer | 22 |  | ICZ1, ICZ2 |
| 0xB3 | ICZSegmentColumn | 4 |  | ICZ1, ICZ2 |
| 0xB4 | ICZSwingingPlatform | 12 |  | ICZ1, ICZ2 |
| 0xB5 | ICZStalagtite | 9 |  | ICZ1 |
| 0xB6 | ICZIceCube | 32 |  | ICZ1, ICZ2 |
| 0xB7 | ICZIceSpikes | 31 |  | ICZ1, ICZ2 |
| 0xB8 | ICZHarmfulIce | 69 |  | ICZ1, ICZ2 |
| 0xB9 | ICZSnowPile | 28 |  | ICZ2 |
| 0xBA | ICZTensionPlatform | 9 |  | ICZ2 |
| 0xBB | ICZIceBlock | 5 |  | ICZ2 |
| 0xBC | ICZMiniboss | 2 |  | ICZ2 |
| 0xBD | ICZEndBoss | 1 |  | ICZ2 |
| 0x10 | LBZTubeElevator | 7 |  | LBZ1 |
| 0x11 | LBZMovingPlatform | 50 |  | LBZ1, LBZ2 |
| 0x13 | LBZExplodingTrigger | 11 |  | LBZ1, LBZ2 |
| 0x14 | LBZTriggerBridge | 11 |  | LBZ1, LBZ2 |
| 0x15 | LBZPlayerLauncher | 24 |  | LBZ1, LBZ2 |
| 0x16 | LBZFlameThrower | 25 |  | LBZ1, LBZ2 |
| 0x17 | LBZRideGrapple | 13 |  | LBZ1, LBZ2 |
| 0x18 | LBZCupElevator | 22 |  | LBZ1, LBZ2 |
| 0x19 | LBZCupElevatorPole | 72 |  | LBZ1, LBZ2 |
| 0x20 | MGZLBZSmashingPillar | 7 |  | LBZ1 |
| 0x22 | LBZAlarm | 9 |  | LBZ1 |
| 0x24 | AutomaticTunnel | 24 |  | LBZ1, LRZ2 |
| 0x31 | LBZRollingDrum | 24 |  | LBZ1, LBZ2 |
| 0xBE | SnaleBlaster | 12 |  | LBZ1, LBZ2 |
| 0xBF | Ribot | 31 |  | LBZ1, LBZ2 |
| 0xC0 | Orbinaut | 21 |  | LBZ1, LBZ2 |
| 0xC1 | Corkey | 13 |  | LBZ1, LBZ2 |
| 0xC3 | LBZ1Robotnik | 1 |  | LBZ1 |
| 0xC4 | LBZMinibossBox | 1 |  | LBZ1 |
| 0xC5 | LBZMinibossBoxKnux | 1 |  | LBZ1 |
| 0x1B | LBZPipePlug | 12 |  | LBZ2 |
| 0x1E | LBZSpinLauncher | 6 |  | LBZ2 |
| 0x1F | LBZLoweringGrapple | 4 |  | LBZ2 |
| 0x21 | LBZGateLaser | 5 |  | LBZ2 |
| 0xC2 | Flybot767 | 12 |  | LBZ2 |
| 0xC6 | LBZ2RobotnikShip | 1 |  | LBZ2 |
| 0xC8 | LBZKnuxPillar | 8 |  | LBZ2 |
| 0xCB | LBZEndBoss | 1 |  | LBZ2 |
| 0x03 | MHZTwistedVine | 11 |  | MHZ1, MHZ2 |
| 0x06 | MHZPulleyLift | 8 |  | MHZ1, MHZ2 |
| 0x09 | MHZCurledVine | 23 |  | MHZ1, MHZ2 |
| 0x0A | MHZStickyVine | 7 |  | MHZ1, MHZ2 |
| 0x0B | MHZSwingBarHorizontal | 21 |  | MHZ1, MHZ2 |
| 0x0C | MHZSwingBarVertical | 5 |  | MHZ1, MHZ2 |
| 0x10 | MHZSwingVine | 7 |  | MHZ1, MHZ2 |
| 0x11 | MHZMushroomPlatform | 15 |  | MHZ1, MHZ2 |
| 0x12 | MHZMushroomParachute | 4 |  | MHZ1, MHZ2 |
| 0x13 | MHZMushroomCatapult | 7 |  | MHZ1, MHZ2 |
| 0x23 | MHZMushroomCap | 211 |  | MHZ1, MHZ2 |
| 0x8C | Madmole | 14 |  | MHZ1, MHZ2 |
| 0x8D | Mushmeanie | 7 |  | MHZ1, MHZ2 |
| 0x8E | Dragonfly | 27 |  | MHZ1, MHZ2 |
| 0x8F | Butterdroid | 25 |  | MHZ1, MHZ2 |
| 0x91 | MHZMinibossTree | 1 |  | MHZ1 |
| 0xA8 | MHZ1CutsceneKnuckles | 1 |  | MHZ1 |
| 0xA9 | MHZ1CutsceneButton | 1 |  | MHZ1 |
| 0x14 | Updraft | 6 |  | MHZ2, SSZ1 |
| 0x90 | Cluckoid | 13 |  | MHZ2 |
| 0x93 | MHZEndBoss | 1 |  | MHZ2 |
| 0x31 | LRZCollapsingBridge | 27 |  | LRZ1 |

## Unimplemented Objects (By Usage)

| ID | Category | Name | Total Uses | PLC | Zones |
|----|----------|------|------------|-----|-------|
| 0xB7 | Object | DDZAsteroid | 426 |  | DDZ1 |
| 0x52 | Object | DEZLightning | 142 |  | DEZ1, DEZ2 |
| 0x73 | Object | FBZMagneticSpikeBall | 114 |  | FBZ1, FBZ2 |
| 0x42 | Object | SOZFloatingPillar | 96 |  | SOZ1, SOZ2 |
| 0x38 | Object | SOZQuicksand | 84 |  | SOZ1, SOZ2 |
| 0x6D | Object | InvisibleShockBlock | 78 |  | DEZ1, DEZ2 |
| 0x4D | Object | DEZTorpedoLauncher | 74 |  | DEZ1, DEZ2 |
| 0x9A | Badnik | Iwamodoki | 66 |  | LRZ1, LRZ2 |
| 0xE1 | Object | FBZMine | 60 |  | FBZ1, FBZ2 |
| 0x29 | Object | LRZFlameThrower | 52 |  | LRZ2 |
| 0x2D | Object | LRZSolidMovingPlatforms | 52 |  | LRZ2 |
| 0xB8 | Object | DDZMissile | 50 |  | DDZ1 |
| 0x5A | Object | DEZGravityTube | 41 |  | DEZ1, DEZ2 |
| 0x2C | Object | LRZOrbitingSpikeBallV | 40 |  | LRZ2 |
| 0xA9 | Badnik | TechnoSqueek | 39 |  | FBZ1, FBZ2 |
| 0x6E | Object | InvisibleLavaBlock | 38 |  | LRZ1, LRZ2 |
| 0x94 | Badnik | Skorp | 35 |  | SOZ1, SOZ2 |
| 0x7B | Object | SSZCollapsingBridgeDiagonal | 35 |  | SSZ1 |
| 0x43 | Object | SOZSwingingPlatform | 33 |  | SOZ1, SOZ2 |
| 0x4F | Object | DEZStaircase | 33 |  | DEZ1, DEZ2 |
| 0x76 | Object | FBZBentPipe | 32 |  | FBZ1 |
| 0x95 | Badnik | Sandworm | 32 |  | SOZ1, SOZ2 |
| 0x9B | Badnik | Toxomister | 31 |  | LRZ1, LRZ2 |
| 0x44 | Object | SOZBreakableSandRock | 30 |  | SOZ1, SOZ2 |
| 0x49 | Object | SOZSolidSprites | 30 |  | SOZ1, SOZ2 |
| 0x99 | Badnik | Fireworm | 29 |  | LRZ1, LRZ2 |
| 0x1B | Object | LRZFireballLauncher | 27 |  | LRZ1 |
| 0x7D | Object | SSZBouncyCloud | 27 |  | SSZ1 |
| 0x19 | Object | LRZDoor | 26 |  | LRZ1, LRZ2 |
| 0xA0 | Boss | EggRobo | 26 |  | SSZ1 |
| 0x41 | Object | SOZLightSwitch | 25 |  | SOZ2 |
| 0x20 | Object | LRZSwingingSpikeBall | 25 |  | LRZ1, LRZ2 |
| 0x7E | Object | SSZCollapsingColumn | 25 |  | SSZ1 |
| 0x55 | Object | DEZEnergyBridge | 25 |  | DEZ1, DEZ2 |
| 0xA8 | Badnik | Blaster | 24 |  | FBZ1, FBZ2 |
| 0x71 | Object | FBZFloatingPlatform | 22 |  | FBZ1, FBZ2 |
| 0x7A | Object | FBZScrewDoor | 22 |  | FBZ1, FBZ2 |
| 0x1C | Object | LRZButtonHorizontal | 21 |  | LRZ1, LRZ2 |
| 0x59 | Object | DEZTeleporter | 21 |  | DEZ2 |
| 0x72 | Object | FBZChainLink | 20 |  | FBZ1, FBZ2 |
| 0x74 | Object | FBZMagneticPlatform | 20 |  | FBZ1, FBZ2 |
| 0xE4 | Object | FBZFlamethrower | 20 |  | FBZ1, FBZ2 |
| 0x46 | Object | SOZDoor | 20 |  | SOZ2 |
| 0x45 | Object | SOZPushSwitch | 19 |  | SOZ2 |
| 0x3A | Object | SOZPathSwap | 18 |  | SOZ1, SOZ2 |
| 0x32 | Object | LRZTurbineSprites | 18 |  | LRZ2 |
| 0xA4 | Badnik | Spikebonker | 18 |  | DEZ1, DEZ2 |
| 0xA5 | Badnik | Chainspike | 18 |  | DEZ1, DEZ2 |
| 0x7E | Object | FBZPlatformBlocks | 17 |  | FBZ1, FBZ2 |
| 0x3F | Object | SOZSpringVine | 17 |  | SOZ1, SOZ2 |
| 0x96 | Badnik | Rockn | 17 |  | SOZ1 |
| 0x18 | Object | LRZFallingSpike | 15 |  | LRZ1 |
| 0x21 | Object | LRZSmashingSpikePlatform | 15 |  | LRZ1 |
| 0x6F | Object | FBZWireCage | 13 |  | FBZ1, FBZ2 |
| 0x50 | Object | DEZConveyorBelt | 13 |  | DEZ1, DEZ2 |
| 0x5D | Object | DEZRetractingSpring | 13 |  | DEZ2 |
| 0xE2 | Object | FBZElevator | 12 |  | FBZ2 |
| 0x48 | Object | SOZRapelWire | 12 |  | SOZ1, SOZ2 |
| 0x2B | Object | LRZOrbitingSpikeBallH | 12 |  | LRZ2 |
| 0x79 | Object | FBZDisappearingPlatform | 11 |  | FBZ1, FBZ2 |
| 0x7C | Object | FBZPropeller | 11 |  | FBZ1 |
| 0x8A | Object | FBZExitHall | 11 |  | FBZ2 |
| 0x17 | Object | LRZSinkingRock | 11 |  | LRZ1 |
| 0x79 | Object | SSZHPZTeleporter | 11 |  | SSZ1, SSZ2 |
| 0x5E | Object | DEZHoverMachine | 11 |  | DEZ1 |
| 0x5B | Object | DEZGravitySwap | 11 |  | DEZ2 |
| 0x7F | Object | FBZMissileLauncher | 10 |  | FBZ1 |
| 0x3E | Object | SOZPushableRock | 10 |  | SOZ1, SOZ2 |
| 0x60 | Object | DEZBumperWall | 10 |  | DEZ1 |
| 0x4A | Object | DEZFloatingPlatform | 10 |  | DEZ2 |
| 0x70 | Object | FBZWireCageStationary | 9 |  | FBZ1 |
| 0x37 | Object | LRZSpikeBallLauncher | 9 |  | LRZ2 |
| 0x53 | Object | DEZConveyorPad | 9 |  | DEZ1, DEZ2 |
| 0x75 | Object | FBZSnakePlatform | 8 |  | FBZ1 |
| 0x7B | Object | FBZSpinningPole | 8 |  | FBZ1 |
| 0x75 | Object | SSZSwingingCarrier | 8 |  | SSZ1 |
| 0x7C | Object | SSZCollapsingBridge | 8 |  | SSZ1 |
| 0x7F | Object | SSZFloatingPlatform | 8 |  | SSZ1 |
| 0xCF | Object | FBZEggPrison | 7 |  | FBZ1, FBZ2 |
| 0xE3 | Object | FBZTrapSpring | 7 |  | FBZ2 |
| 0x1F | Object | LRZLavaFall | 7 |  | LRZ1 |
| 0x76 | Object | SSZRotatingPlatform | 7 |  | SSZ1 |
| 0x4E | Object | DEZLiftPad | 7 |  | DEZ1 |
| 0x57 | Object | DEZTunnelLauncher | 7 |  | DEZ1, DEZ2 |
| 0x77 | Object | FBZRotatingPlatform | 6 |  | FBZ1 |
| 0xFF | Object | FBZMagneticPendulum | 6 |  | FBZ2 |
| 0x40 | Object | SOZRisingSandWall | 6 |  | SOZ1, SOZ2 |
| 0x1E | Object | LRZDashElevator | 6 |  | LRZ1 |
| 0x22 | Object | LRZSpikeBall | 6 |  | LRZ1 |
| 0x00 | Object | Ring | 5 |  | MGZ2, FBZ2, SSZ2 |
| 0xD0 | Object | FBZSpringPlunger | 5 |  | FBZ1 |
| 0x74 | Object | SSZRetractingSpring | 5 |  | SSZ1 |
| 0x7A | Object | SSZElevatorBar | 5 |  | SSZ1 |
| 0x58 | Object | DEZGravitySwitch | 5 |  | DEZ2 |
| 0x47 | Object | SOZSandCork | 4 |  | SOZ2 |
| 0x8B | Object | SpriteMask | 4 |  | SOZ2, LRZ1 |
| 0x4B | Object | DEZTiltingBridge | 4 |  | DEZ1, DEZ2 |
| 0x4C | Object | DEZHangCarrier | 4 |  | DEZ1, DEZ2 |
| 0x39 | Object | SOZSpawningSandBlocks | 3 |  | SOZ1 |
| 0x3B | Object | SOZLoopFallthrough | 3 |  | SOZ2 |
| 0x25 | Object | LRZChainedPlatforms | 3 |  | LRZ2 |
| 0x5C | Object | DEZGravityHub | 3 |  | DEZ2 |
| 0xE0 | Object | FBZWallMissile | 2 |  | FBZ1 |
| 0xE5 | Object | FBZSpiderCrane | 2 |  | FBZ2 |
| 0xAB | Object | SOZCapsuleHyudoro | 2 |  | SOZ2 |
| 0x16 | Object | LRZWallRide | 2 |  | LRZ1, LRZ2 |
| 0x1D | Object | LRZShootingTrigger | 2 |  | LRZ1 |
| 0x9C | Boss | LRZRockCrusher | 2 |  | LRZ1 |
| 0x7D | Object | FBZPiston | 1 |  | FBZ1 |
| 0xAA | Boss | FBZMiniboss | 1 |  | FBZ1 |
| 0x3D | Object | RetractingSpring | 1 |  | FBZ2 |
| 0xAB | Boss | FBZ2Subboss | 1 |  | FBZ2 |
| 0xCE | Object | FBZExitDoor | 1 |  | FBZ2 |
| 0xCD | Boss | LBZFinalBossKnux | 1 |  | LBZ2 |
| 0x98 | Boss | SOZEndBoss | 1 |  | SOZ2 |
| 0xAC | Object | SOZCapsule | 1 |  | SOZ2 |
| 0x15 | Object | LRZCorkscrew | 1 |  | LRZ1 |
| 0x1A | Object | LRZBigDoor | 1 |  | LRZ1 |
| 0x9D | Boss | LRZMiniboss | 1 |  | LRZ1 |
| 0xAE | Object | LRZ2CutsceneKnuckles | 1 |  | LRZ2 |
| 0xB3 | Object | StartNewLevel | 1 |  | LRZ2 |
| 0x77 | Object | SSZCutsceneBridge | 1 |  | SSZ1 |
| 0xAF | Object | SSZCutsceneButton | 1 |  | SSZ1 |
| 0xB2 | Object | KnuxFinalBossCrane | 1 |  | SSZ2 |
| 0x56 | Object | DEZEnergyBridgeCurved | 1 |  | DEZ1 |
| 0x5F | Object | DEZGravityRoom | 1 |  | DEZ1 |
| 0x61 | Object | DEZGravityPuzzle | 1 |  | DEZ1 |
| 0xA6 | Boss | DEZMiniboss | 1 |  | DEZ1 |
| 0xA7 | Boss | DEZEndBoss | 1 |  | DEZ2 |
| 0xB6 | Boss | DDZEndBoss | 1 |  | DDZ1 |

---

## By Zone

### Angel Island Zone

#### Act 1

Total: 153 objects | Implemented: 23 | Unimplemented: 0

**Badniks:**
- [x] 0x8C Bloominator (x2) [0x00]
- [x] 0x8D Rhinobot (x3) [0x00]
- [x] 0x8E MonkeyDude (x5) [0x10]

**Bosses:**
- [x] 0x90 AIZMinibossCutscene (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x11) [6 subtypes]
- [x] 0x02 PathSwap (x13) [5 subtypes]
- [x] 0x03 AIZHollowTree (x1) [0x00] PLC:0x0B
- [x] 0x04 CollapsingPlatform (x4) [0x00] PLC:0x0B
- [x] 0x05 AIZLRZEMZRock (x9) [7 subtypes] PLC:0x0B
- [x] 0x06 AIZRideVine (x1) [0x3B] PLC:0x0B
- [x] 0x07 Spring (x12) [0x10, 0x12, 0x03]
- [x] 0x08 Spikes (x7) [0x00, 0x20, 0x01]
- [x] 0x09 AIZ1Tree (x1) [0x00] PLC:0x0B
- [x] 0x0A AIZ1ZiplinePeg (x1) [0x00] PLC:0x0B
- [x] 0x0C AIZGiantRideVine (x4) [4 subtypes]
- [x] 0x0E TwistedRamp (x1) [0x00]
- [x] 0x28 InvisibleBlock (x6) [0x11, 0x41, 0x71]
- [x] 0x2A CorkFloor (x1) [0x01]
- [x] 0x2D AIZFallingLog (x1) [0x06]
- [x] 0x34 StarPost (x1) [0x02]
- [x] 0x35 AIZForegroundPlant (x64) [8 subtypes]
- [x] 0x51 FloatingPlatform (x3) [0x22, 0x23, 0x24]
- [x] 0x85 SSEntryRing (x1) [0x01]

#### Act 2

Total: 751 objects | Implemented: 32 | Unimplemented: 0

**Badniks:**
- [x] 0x8C Bloominator (x11) [0x00]
- [x] 0x8D Rhinobot (x9) [0x00]
- [x] 0x8E MonkeyDude (x1) [0x10]
- [x] 0x8F CaterKillerJr (x14) [0x00]

**Bosses:**
- [x] 0x92 AIZEndBoss (x2) [0x00]

**Objects:**
- [x] 0x01 Monitor (x22) [6 subtypes]
- [x] 0x02 PathSwap (x29) [11 subtypes]
- [x] 0x04 CollapsingPlatform (x23) [0x00]
- [x] 0x05 AIZLRZEMZRock (x51) [8 subtypes]
- [x] 0x06 AIZRideVine (x2) [0x90, 0x2C] PLC:0x0C
- [x] 0x07 Spring (x33) [8 subtypes]
- [x] 0x08 Spikes (x44) [9 subtypes]
- [x] 0x0C AIZGiantRideVine (x6) [0x05, 0x06, 0x09]
- [x] 0x0D BreakableWall (x10) [0x80, 0x82, 0x84]
- [x] 0x0E TwistedRamp (x2) [0x00]
- [x] 0x26 AutoSpin (x8) [0x80, 0x84]
- [x] 0x28 InvisibleBlock (x16) [7 subtypes]
- [x] 0x29 AIZDisappearingFloor (x6) [4 subtypes]
- [x] 0x2A CorkFloor (x11) [0x00, 0x01]
- [x] 0x2B AIZFlippingBridge (x2) [0x52]
- [x] 0x2C AIZCollapsingLogBridge (x7) [0x08, 0x88]
- [x] 0x2D AIZFallingLog (x1) [0x06]
- [x] 0x2E AIZSpikedLog (x4) [0x00]
- [x] 0x2F StillSprite (x16) [6 subtypes]
- [x] 0x30 AnimatedStillSprite (x373) [0x00, 0x01]
- [x] 0x32 AIZDrawBridge (x2) [0x00]
- [x] 0x33 Button (x2) [0x10]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [x] 0x51 FloatingPlatform (x26) [4 subtypes]
- [x] 0x80 HiddenMonitor (x9) [6 subtypes]
- [x] 0x83 CutsceneButton (x1) [0x00]
- [x] 0x85 SSEntryRing (x3) [0x03, 0x04, 0x05]

### Hydrocity Zone

#### Act 1

Total: 383 objects | Implemented: 30 | Unimplemented: 0

**Badniks:**
- [x] 0x94 Blastoid (x7) [5 subtypes]
- [x] 0x95 Buggernaut (x8) [0x00]
- [x] 0x96 TurboSpiker (x7) [0x20, 0x30]
- [x] 0x97 MegaChopper (x9) [0x00]
- [x] 0x98 Poindexter (x29) [0x20]

**Bosses:**
- [x] 0x99 HCZMiniboss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x22) [5 subtypes]
- [x] 0x02 PathSwap (x55) [14 subtypes]
- [x] 0x07 Spring (x27) [11 subtypes]
- [x] 0x08 Spikes (x34) [7 subtypes]
- [x] 0x0F CollapsingBridge (x7) [7 subtypes]
- [x] 0x26 AutoSpin (x8) [0x00, 0x04]
- [x] 0x28 InvisibleBlock (x4) [4 subtypes]
- [x] 0x2F StillSprite (x11) [5 subtypes]
- [x] 0x33 Button (x2) [0x20]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [x] 0x36 HCZBreakableBar (x6) [0x05, 0x15, 0x25]
- [x] 0x37 HCZWaterRush (x1) [0x00]
- [x] 0x38 HCZCGZFan (x27) [12 subtypes]
- [x] 0x39 HCZLargeFan (x2) [0x00]
- [x] 0x3A HCZHandLauncher (x5) [0x00]
- [x] 0x3B HCZWaterWall (x3) [0x00, 0x01]
- [x] 0x3E HCZConveyorBelt (x64) [32 subtypes]
- [x] 0x3F HCZConveyorSpike (x6) [0x02, 0x08, 0x0A]
- [x] 0x40 HCZBlock (x1) [0x00]
- [x] 0x51 FloatingPlatform (x17) [4 subtypes]
- [x] 0x54 Bubbler (x9) [0x80]
- [x] 0x6A InvisibleHurtBlockH (x2) [0xF1, 0xA1]
- [x] 0x80 HiddenMonitor (x2) [0x03]
- [x] 0x85 SSEntryRing (x2) [0x01, 0x02]

#### Act 2

Total: 510 objects | Implemented: 30 | Unimplemented: 0

**Badniks:**
- [x] 0x93 Jawz (x19) [0x00]
- [x] 0x96 TurboSpiker (x16) [0x20]
- [x] 0x97 MegaChopper (x8) [0x00]
- [x] 0x98 Poindexter (x11) [0x20]

**Bosses:**
- [x] 0x9A HCZEndBoss (x2) [0x00]

**Objects:**
- [x] 0x01 Monitor (x21) [5 subtypes]
- [x] 0x02 PathSwap (x44) [15 subtypes]
- [x] 0x07 Spring (x12) [5 subtypes]
- [x] 0x08 Spikes (x38) [6 subtypes]
- [x] 0x0D BreakableWall (x33) [0x00, 0x02]
- [x] 0x28 InvisibleBlock (x2) [0x11, 0x17]
- [x] 0x2F StillSprite (x61) [5 subtypes]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [x] 0x36 HCZBreakableBar (x2) [0x15]
- [x] 0x38 HCZCGZFan (x36) [12 subtypes]
- [x] 0x39 HCZLargeFan (x1) [0x00]
- [x] 0x3A HCZHandLauncher (x7) [0x00]
- [x] 0x3C Door (x19) [0x00]
- [x] 0x40 HCZBlock (x9) [0x00]
- [x] 0x54 Bubbler (x1) [0x80]
- [x] 0x67 HCZSnakeBlocks (x50) [15 subtypes]
- [x] 0x68 HCZSpinningColumn (x27) [5 subtypes]
- [x] 0x69 HCZTwistingLoop (x16) [16 subtypes]
- [x] 0x6A InvisibleHurtBlockH (x8) [4 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x40) [6 subtypes]
- [x] 0x6C TensionBridge (x7) [0x08, 0x88]
- [x] 0x6D HCZWaterSplash (x4) [0x00]
- [x] 0x6E WaterDrop (x8) [0x40, 0x30]
- [x] 0x82 CutsceneKnuckles (x1) [0x08]
- [x] 0x85 SSEntryRing (x2) [0x03, 0x04]

### Marble Garden Zone

#### Act 1

Total: 455 objects | Implemented: 28 | Unimplemented: 0

**Badniks:**
- [x] 0x9B BubblesBadnik (x24) [0x00]
- [x] 0x9C Spiker (x10) [0x00]
- [x] 0x9E Tunnelbot (x3) [0x00]

**Bosses:**
- [x] 0x9F MGZMiniboss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x30) [7 subtypes]
- [x] 0x02 PathSwap (x13) [10 subtypes]
- [x] 0x07 Spring (x28) [5 subtypes]
- [x] 0x08 Spikes (x77) [7 subtypes]
- [x] 0x0D BreakableWall (x34) [0x00, 0x02]
- [x] 0x0F CollapsingBridge (x16) [0x12, 0x02, 0x22]
- [x] 0x28 InvisibleBlock (x8) [5 subtypes]
- [x] 0x2F StillSprite (x29) [4 subtypes]
- [x] 0x34 StarPost (x3) [0x01, 0x02, 0x03]
- [x] 0x4F SinkingMud (x8) [0x10, 0x08]
- [x] 0x50 MGZTwistingLoop (x2) [0x24, 0x18]
- [x] 0x51 FloatingPlatform (x20) [4 subtypes]
- [x] 0x52 MGZSmashingPillar (x9) [0x0A]
- [x] 0x53 MGZSwingingPlatform (x57) [0x80, 0xD5, 0x2B]
- [x] 0x55 MGZHeadTrigger (x4) [4 subtypes]
- [x] 0x56 MGZMovingSpikePlatform (x1) [0x00]
- [x] 0x57 MGZTriggerPlatform (x23) [17 subtypes]
- [x] 0x58 MGZSwingingSpikeBall (x20) [0x00, 0x01]
- [x] 0x59 MGZDashTrigger (x7) [7 subtypes]
- [x] 0x5B MGZTopPlatform (x5) [0x00]
- [x] 0x5C MGZTopLauncher (x1) [0x00]
- [x] 0x6B InvisibleHurtBlockV (x11) [0x31, 0x51, 0x15]
- [x] 0x80 HiddenMonitor (x3) [0x03, 0x05, 0x06]
- [x] 0x85 SSEntryRing (x8) [8 subtypes]

#### Act 2

Total: 342 objects | Implemented: 28 | Unimplemented: 1

**Badniks:**
- [x] 0x9B BubblesBadnik (x17) [0x00]
- [x] 0x9C Spiker (x13) [0x00]
- [x] 0x9D Mantis (x24) [0x00]

**Bosses:**
- [x] 0xA2 MGZEndBossKnux (x1) [0x00]

**Objects:**
- [ ] 0x00 Ring (x1) [0x00]
- [x] 0x01 Monitor (x19) [7 subtypes]
- [x] 0x02 PathSwap (x18) [6 subtypes]
- [x] 0x07 Spring (x21) [6 subtypes]
- [x] 0x08 Spikes (x19) [5 subtypes]
- [x] 0x0D BreakableWall (x22) [0x00, 0x10, 0x02]
- [x] 0x0F CollapsingBridge (x9) [5 subtypes]
- [x] 0x28 InvisibleBlock (x5) [0x00, 0x02]
- [x] 0x2F StillSprite (x33) [4 subtypes]
- [x] 0x34 StarPost (x8) [8 subtypes]
- [x] 0x4F SinkingMud (x5) [0x10, 0x08]
- [x] 0x50 MGZTwistingLoop (x5) [4 subtypes]
- [x] 0x51 FloatingPlatform (x15) [0x00, 0x02, 0x04]
- [x] 0x52 MGZSmashingPillar (x7) [0x0A]
- [x] 0x53 MGZSwingingPlatform (x18) [0x80, 0xD5, 0x2B]
- [x] 0x55 MGZHeadTrigger (x5) [5 subtypes]
- [x] 0x56 MGZMovingSpikePlatform (x4) [0x00]
- [x] 0x57 MGZTriggerPlatform (x26) [22 subtypes]
- [x] 0x58 MGZSwingingSpikeBall (x10) [0x00, 0x01]
- [x] 0x59 MGZDashTrigger (x10) [10 subtypes]
- [x] 0x5A MGZPulley (x7) [5 subtypes]
- [x] 0x5B MGZTopPlatform (x5) [0x00]
- [x] 0x5C MGZTopLauncher (x5) [0x00]
- [x] 0x6B InvisibleHurtBlockV (x7) [0x40, 0x30]
- [x] 0x85 SSEntryRing (x3) [0x09, 0x0A, 0x0B]

### Carnival Night Zone

#### Act 1

Total: 488 objects | Implemented: 29 | Unimplemented: 0

**Badniks:**
- [x] 0xA3 Clamer (x16) [0x00]
- [x] 0xA4 Sparkle (x5) [0x00]
- [x] 0xA5 Batbot (x16) [0x00]

**Bosses:**
- [x] 0xA6 CNZMiniboss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x42) [7 subtypes]
- [x] 0x02 PathSwap (x19) [10 subtypes]
- [x] 0x07 Spring (x25) [5 subtypes]
- [x] 0x08 Spikes (x39) [10 subtypes]
- [x] 0x0D BreakableWall (x8) [0x00, 0x02]
- [x] 0x28 InvisibleBlock (x11) [4 subtypes]
- [x] 0x2A CorkFloor (x1) [0x01]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [x] 0x3C Door (x15) [0x80, 0x01]
- [x] 0x41 CNZBalloon (x39) [5 subtypes]
- [x] 0x42 CNZCannon (x3) [0x00]
- [x] 0x43 CNZRisingPlatform (x6) [0x00]
- [x] 0x44 CNZTrapDoor (x2) [0x00]
- [x] 0x46 CNZHoverFan (x89) [9 subtypes]
- [x] 0x47 CNZCylinder (x32) [12 subtypes]
- [x] 0x48 CNZVacuumTube (x15) [4 subtypes]
- [x] 0x49 CNZGiantWheel (x7) [0x00]
- [x] 0x4A Bumper (x37) [5 subtypes]
- [x] 0x4B CNZTriangleBumpers (x7) [0x40, 0x80, 0x60]
- [x] 0x4C CNZSpiralTube (x2) [0x00, 0x02]
- [x] 0x4D CNZBarberPoleSprite (x12) [0x00, 0x01]
- [x] 0x4E CNZWireCage (x12) [4 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x13) [8 subtypes]
- [x] 0x80 HiddenMonitor (x3) [0x03, 0x06, 0x07]
- [x] 0x85 SSEntryRing (x6) [6 subtypes]

#### Act 2

Total: 708 objects | Implemented: 32 | Unimplemented: 0

**Badniks:**
- [x] 0xA3 Clamer (x11) [0x00]
- [x] 0xA4 Sparkle (x13) [0x00]
- [x] 0xA5 Batbot (x12) [0x00]

**Bosses:**
- [x] 0xA7 CNZEndBoss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x45) [6 subtypes]
- [x] 0x02 PathSwap (x20) [12 subtypes]
- [x] 0x07 Spring (x17) [4 subtypes]
- [x] 0x08 Spikes (x43) [10 subtypes]
- [x] 0x0D BreakableWall (x11) [0x00, 0x02]
- [x] 0x28 InvisibleBlock (x20) [6 subtypes]
- [x] 0x2A CorkFloor (x4) [0x01]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [x] 0x3C Door (x12) [0x80, 0x01]
- [x] 0x41 CNZBalloon (x66) [9 subtypes]
- [x] 0x42 CNZCannon (x2) [0x00]
- [x] 0x43 CNZRisingPlatform (x2) [0x00]
- [x] 0x45 CNZLightBulb (x2) [0x00]
- [x] 0x46 CNZHoverFan (x120) [13 subtypes]
- [x] 0x47 CNZCylinder (x43) [16 subtypes]
- [x] 0x48 CNZVacuumTube (x79) [0x00, 0x20, 0x10]
- [x] 0x49 CNZGiantWheel (x3) [0x00]
- [x] 0x4A Bumper (x89) [7 subtypes]
- [x] 0x4B CNZTriangleBumpers (x24) [6 subtypes]
- [x] 0x4D CNZBarberPoleSprite (x23) [0x00, 0x01]
- [x] 0x4E CNZWireCage (x3) [0x10, 0x20, 0x18]
- [x] 0x6B InvisibleHurtBlockV (x23) [10 subtypes]
- [x] 0x80 HiddenMonitor (x3) [0x03]
- [x] 0x82 CutsceneKnuckles (x2) [0x10, 0x0C]
- [x] 0x83 CutsceneButton (x2) [0x04, 0x06]
- [x] 0x85 SSEntryRing (x5) [5 subtypes]
- [x] 0x88 CNZWaterLevelCorkFloor (x2) [0x01]
- [x] 0x89 CNZWaterLevelButton (x1) [0x00]

### Flying Battery Zone

#### Act 1

Total: 420 objects | Implemented: 15 | Unimplemented: 24

**Badniks:**
- [ ] 0xA8 Blaster (x10) [0x20, 0x08]
- [ ] 0xA9 TechnoSqueek (x13) [0x00, 0x02, 0x04]

**Bosses:**
- [ ] 0xAA FBZMiniboss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x11) [5 subtypes]
- [x] 0x02 PathSwap (x31) [7 subtypes]
- [x] 0x07 Spring (x15) [4 subtypes]
- [x] 0x08 Spikes (x21) [5 subtypes]
- [x] 0x0F CollapsingBridge (x1) [0x00]
- [x] 0x26 AutoSpin (x2) [0x04]
- [x] 0x28 InvisibleBlock (x20) [4 subtypes]
- [x] 0x2F StillSprite (x5) [0x28, 0x29, 0x2A]
- [x] 0x33 Button (x4) [0x20, 0x21, 0x22]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [x] 0x6A InvisibleHurtBlockH (x1) [0x71]
- [x] 0x6B InvisibleHurtBlockV (x16) [4 subtypes]
- [ ] 0x6F FBZWireCage (x6) [0x10]
- [ ] 0x70 FBZWireCageStationary (x9) [0x00, 0x01, 0x02]
- [ ] 0x71 FBZFloatingPlatform (x20) [10 subtypes]
- [ ] 0x72 FBZChainLink (x10) [9 subtypes]
- [ ] 0x73 FBZMagneticSpikeBall (x52) [4 subtypes]
- [ ] 0x74 FBZMagneticPlatform (x7) [0x0F]
- [ ] 0x75 FBZSnakePlatform (x8) [8 subtypes]
- [ ] 0x76 FBZBentPipe (x32) [0x00, 0x01, 0x02]
- [ ] 0x77 FBZRotatingPlatform (x6) [0x00, 0x0C]
- [x] 0x78 FBZDEZPlayerLauncher (x6) [0x00]
- [ ] 0x79 FBZDisappearingPlatform (x7) [5 subtypes]
- [ ] 0x7A FBZScrewDoor (x4) [4 subtypes]
- [ ] 0x7B FBZSpinningPole (x8) [4 subtypes]
- [ ] 0x7C FBZPropeller (x11) [0x00]
- [ ] 0x7D FBZPiston (x1) [0x28]
- [ ] 0x7E FBZPlatformBlocks (x10) [0x00, 0x02, 0x14]
- [ ] 0x7F FBZMissileLauncher (x10) [0x02, 0x72, 0xF2]
- [x] 0x80 HiddenMonitor (x2) [0x05, 0x06]
- [x] 0x85 SSEntryRing (x2) [0x01, 0x02]
- [ ] 0xCF FBZEggPrison (x6) [0x00, 0x01, 0x02]
- [ ] 0xD0 FBZSpringPlunger (x5) [0x00]
- [ ] 0xE0 FBZWallMissile (x2) [0x10, 0x20]
- [ ] 0xE1 FBZMine (x32) [0x00]
- [ ] 0xE4 FBZFlamethrower (x8) [5 subtypes]

#### Act 2

Total: 440 objects | Implemented: 15 | Unimplemented: 22

**Badniks:**
- [ ] 0xA8 Blaster (x14) [0x20, 0x30]
- [ ] 0xA9 TechnoSqueek (x26) [0x00, 0x02, 0x04]

**Bosses:**
- [ ] 0xAB FBZ2Subboss (x1) [0x00]

**Objects:**
- [ ] 0x00 Ring (x1) [0x00]
- [x] 0x01 Monitor (x7) [5 subtypes]
- [x] 0x02 PathSwap (x15) [9 subtypes]
- [x] 0x07 Spring (x6) [4 subtypes]
- [x] 0x08 Spikes (x89) [4 subtypes]
- [x] 0x0F CollapsingBridge (x2) [0x00]
- [x] 0x26 AutoSpin (x5) [0x80, 0x04]
- [x] 0x28 InvisibleBlock (x6) [0x61, 0x41, 0x17]
- [x] 0x2A CorkFloor (x2) [0x10]
- [x] 0x2F StillSprite (x9) [0x28, 0x29, 0x2C]
- [x] 0x33 Button (x16) [15 subtypes]
- [x] 0x34 StarPost (x6) [6 subtypes]
- [ ] 0x3D RetractingSpring (x1) [0x04]
- [x] 0x6A InvisibleHurtBlockH (x2) [0x71]
- [x] 0x6B InvisibleHurtBlockV (x22) [4 subtypes]
- [ ] 0x6F FBZWireCage (x7) [4 subtypes]
- [ ] 0x71 FBZFloatingPlatform (x2) [0x00, 0x4F]
- [ ] 0x72 FBZChainLink (x10) [6 subtypes]
- [ ] 0x73 FBZMagneticSpikeBall (x62) [0x00, 0x80, 0x81]
- [ ] 0x74 FBZMagneticPlatform (x13) [0x0E, 0x0F]
- [x] 0x78 FBZDEZPlayerLauncher (x5) [0x00]
- [ ] 0x79 FBZDisappearingPlatform (x4) [4 subtypes]
- [ ] 0x7A FBZScrewDoor (x18) [16 subtypes]
- [ ] 0x7E FBZPlatformBlocks (x7) [0x00, 0x14]
- [x] 0x85 SSEntryRing (x2) [0x03, 0x04]
- [ ] 0x8A FBZExitHall (x11) [0x00, 0x04]
- [ ] 0xCE FBZExitDoor (x1) [0x00]
- [ ] 0xCF FBZEggPrison (x1) [0x02]
- [ ] 0xE1 FBZMine (x28) [0x00]
- [ ] 0xE2 FBZElevator (x12) [8 subtypes]
- [ ] 0xE3 FBZTrapSpring (x7) [0x00, 0x02]
- [ ] 0xE4 FBZFlamethrower (x12) [0x80, 0x00, 0x02]
- [ ] 0xE5 FBZSpiderCrane (x2) [0x2C]
- [ ] 0xFF FBZMagneticPendulum (x6) [0x00, 0x80]

### IceCap Zone

#### Act 1

Total: 249 objects | Implemented: 24 | Unimplemented: 0

**Badniks:**
- [x] 0xAD Penguinator (x12) [0x30, 0x20]
- [x] 0xAE StarPointer (x2) [0x00]

**Objects:**
- [x] 0x01 Monitor (x4) [0x03, 0x06, 0x08]
- [x] 0x02 PathSwap (x5) [0x11, 0x02]
- [x] 0x04 CollapsingPlatform (x4) [0x00]
- [x] 0x07 Spring (x14) [4 subtypes]
- [x] 0x0F CollapsingBridge (x4) [4 subtypes]
- [x] 0x28 InvisibleBlock (x1) [0x11]
- [x] 0x33 Button (x4) [4 subtypes]
- [x] 0x34 StarPost (x2) [0x01, 0x02]
- [x] 0x6A InvisibleHurtBlockH (x45) [10 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x14) [5 subtypes]
- [x] 0x6C TensionBridge (x1) [0x0C]
- [x] 0x85 SSEntryRing (x2) [0x01, 0x02]
- [x] 0xAF ICZCrushingColumn (x17) [4 subtypes]
- [x] 0xB0 ICZPathFollowPlatform (x2) [0x00, 0x02]
- [x] 0xB1 ICZBreakableWall (x12) [0x00]
- [x] 0xB2 ICZFreezer (x9) [0x00]
- [x] 0xB3 ICZSegmentColumn (x2) [0x02]
- [x] 0xB4 ICZSwingingPlatform (x9) [0x00, 0x02]
- [x] 0xB5 ICZStalagtite (x9) [0x00]
- [x] 0xB6 ICZIceCube (x11) [0x00]
- [x] 0xB7 ICZIceSpikes (x9) [0x00]
- [x] 0xB8 ICZHarmfulIce (x55) [0x00, 0x02]

#### Act 2

Total: 356 objects | Implemented: 29 | Unimplemented: 0

**Badniks:**
- [x] 0xAD Penguinator (x32) [0x10, 0x20, 0x40]
- [x] 0xAE StarPointer (x18) [0x00, 0x02, 0x04]

**Bosses:**
- [x] 0xBC ICZMiniboss (x2) [0x00, 0x02]
- [x] 0xBD ICZEndBoss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x9) [5 subtypes]
- [x] 0x02 PathSwap (x65) [13 subtypes]
- [x] 0x04 CollapsingPlatform (x3) [0x00]
- [x] 0x07 Spring (x12) [6 subtypes]
- [x] 0x0F CollapsingBridge (x5) [0x81, 0x82, 0x83]
- [x] 0x26 AutoSpin (x9) [0x00, 0x44]
- [x] 0x28 InvisibleBlock (x1) [0x31]
- [x] 0x2A CorkFloor (x35) [5 subtypes]
- [x] 0x33 Button (x5) [0x01, 0x02, 0x03]
- [x] 0x34 StarPost (x4) [4 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x5) [0x41, 0x61, 0x14]
- [x] 0x6C TensionBridge (x10) [0x0C, 0x8C]
- [x] 0x80 HiddenMonitor (x6) [0x03, 0x06]
- [x] 0x85 SSEntryRing (x3) [0x03, 0x04, 0x05]
- [x] 0xAF ICZCrushingColumn (x7) [0x02, 0x03, 0x04]
- [x] 0xB0 ICZPathFollowPlatform (x7) [0x06]
- [x] 0xB2 ICZFreezer (x13) [0x00]
- [x] 0xB3 ICZSegmentColumn (x2) [0x00, 0x02]
- [x] 0xB4 ICZSwingingPlatform (x3) [0x00]
- [x] 0xB6 ICZIceCube (x21) [0x00]
- [x] 0xB7 ICZIceSpikes (x22) [0x00, 0x02]
- [x] 0xB8 ICZHarmfulIce (x14) [0x00]
- [x] 0xB9 ICZSnowPile (x28) [5 subtypes]
- [x] 0xBA ICZTensionPlatform (x9) [0x00]
- [x] 0xBB ICZIceBlock (x5) [0x00]

### Launch Base Zone

#### Act 1

Total: 430 objects | Implemented: 32 | Unimplemented: 0

**Badniks:**
- [x] 0xBE SnaleBlaster (x5) [0x00]
- [x] 0xBF Ribot (x14) [0x00, 0x04]
- [x] 0xC0 Orbinaut (x10) [0x00]
- [x] 0xC1 Corkey (x10) [5 subtypes]

**Bosses:**
- [x] 0xC3 LBZ1Robotnik (x1) [0x00]
- [x] 0xC4 LBZMinibossBox (x1) [0x00]
- [x] 0xC5 LBZMinibossBoxKnux (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x24) [6 subtypes]
- [x] 0x02 PathSwap (x18) [5 subtypes]
- [x] 0x07 Spring (x20) [4 subtypes]
- [x] 0x08 Spikes (x27) [8 subtypes]
- [x] 0x0F CollapsingBridge (x19) [4 subtypes]
- [x] 0x10 LBZTubeElevator (x7) [6 subtypes]
- [x] 0x11 LBZMovingPlatform (x30) [9 subtypes]
- [x] 0x13 LBZExplodingTrigger (x8) [7 subtypes]
- [x] 0x14 LBZTriggerBridge (x7) [7 subtypes]
- [x] 0x15 LBZPlayerLauncher (x6) [0x00]
- [x] 0x16 LBZFlameThrower (x11) [0x20, 0x00]
- [x] 0x17 LBZRideGrapple (x7) [7 subtypes]
- [x] 0x18 LBZCupElevator (x12) [10 subtypes]
- [x] 0x19 LBZCupElevatorPole (x41) [4 subtypes]
- [x] 0x20 MGZLBZSmashingPillar (x7) [5 subtypes]
- [x] 0x22 LBZAlarm (x9) [0x00, 0x01, 0x03]
- [x] 0x24 AutomaticTunnel (x14) [14 subtypes]
- [x] 0x28 InvisibleBlock (x11) [5 subtypes]
- [x] 0x2A CorkFloor (x4) [0x00]
- [x] 0x2F StillSprite (x80) [4 subtypes]
- [x] 0x31 LBZRollingDrum (x9) [0x80, 0x40]
- [x] 0x34 StarPost (x6) [6 subtypes]
- [x] 0x80 HiddenMonitor (x7) [0x01, 0x03, 0x07]
- [x] 0x82 CutsceneKnuckles (x1) [0x14]
- [x] 0x85 SSEntryRing (x3) [0x00, 0x01, 0x02]

#### Act 2

Total: 489 objects | Implemented: 34 | Unimplemented: 1

**Badniks:**
- [x] 0xBE SnaleBlaster (x7) [0x00]
- [x] 0xBF Ribot (x17) [0x00, 0x02, 0x04]
- [x] 0xC0 Orbinaut (x11) [0x00]
- [x] 0xC1 Corkey (x3) [0x20, 0x18]
- [x] 0xC2 Flybot767 (x12) [0x00]

**Bosses:**
- [x] 0xC6 LBZ2RobotnikShip (x1) [0x00]
- [x] 0xCB LBZEndBoss (x1) [0x00]
- [ ] 0xCD LBZFinalBossKnux (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x29) [5 subtypes]
- [x] 0x02 PathSwap (x73) [30 subtypes]
- [x] 0x07 Spring (x37) [5 subtypes]
- [x] 0x08 Spikes (x17) [5 subtypes]
- [x] 0x0D BreakableWall (x2) [0x00]
- [x] 0x0F CollapsingBridge (x13) [4 subtypes]
- [x] 0x11 LBZMovingPlatform (x20) [7 subtypes]
- [x] 0x13 LBZExplodingTrigger (x3) [0x08, 0x09, 0x0A]
- [x] 0x14 LBZTriggerBridge (x4) [4 subtypes]
- [x] 0x15 LBZPlayerLauncher (x18) [0x00]
- [x] 0x16 LBZFlameThrower (x14) [4 subtypes]
- [x] 0x17 LBZRideGrapple (x6) [6 subtypes]
- [x] 0x18 LBZCupElevator (x10) [6 subtypes]
- [x] 0x19 LBZCupElevatorPole (x31) [0x00, 0x01]
- [x] 0x1B LBZPipePlug (x12) [6 subtypes]
- [x] 0x1E LBZSpinLauncher (x6) [0x00]
- [x] 0x1F LBZLoweringGrapple (x4) [0x9A, 0x1A]
- [x] 0x21 LBZGateLaser (x5) [0x88]
- [x] 0x28 InvisibleBlock (x20) [12 subtypes]
- [x] 0x2A CorkFloor (x1) [0x00]
- [x] 0x2F StillSprite (x74) [4 subtypes]
- [x] 0x31 LBZRollingDrum (x15) [0x80, 0x40]
- [x] 0x34 StarPost (x6) [6 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x2) [0x12]
- [x] 0x82 CutsceneKnuckles (x1) [0x18]
- [x] 0x85 SSEntryRing (x5) [5 subtypes]
- [x] 0xC8 LBZKnuxPillar (x8) [0x00]

### Mushroom Hill Zone

#### Act 1

Total: 589 objects | Implemented: 31 | Unimplemented: 0

**Badniks:**
- [x] 0x8C Madmole (x11) [0x00]
- [x] 0x8D Mushmeanie (x4) [0x00]
- [x] 0x8E Dragonfly (x12) [0x00]
- [x] 0x8F Butterdroid (x24) [0x00]

**Bosses:**
- [x] 0x91 MHZMinibossTree (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x20) [8 subtypes]
- [x] 0x02 PathSwap (x105) [32 subtypes]
- [x] 0x03 MHZTwistedVine (x4) [0x00]
- [x] 0x06 MHZPulleyLift (x4) [4 subtypes]
- [x] 0x07 Spring (x37) [7 subtypes]
- [x] 0x08 Spikes (x16) [5 subtypes]
- [x] 0x09 MHZCurledVine (x12) [0x00]
- [x] 0x0A MHZStickyVine (x3) [0x00]
- [x] 0x0B MHZSwingBarHorizontal (x11) [0x00]
- [x] 0x0C MHZSwingBarVertical (x3) [0x00]
- [x] 0x0D BreakableWall (x7) [0x00]
- [x] 0x0E TwistedRamp (x3) [0x00]
- [x] 0x10 MHZSwingVine (x5) [0x00]
- [x] 0x11 MHZMushroomPlatform (x9) [0x01]
- [x] 0x12 MHZMushroomParachute (x1) [0x00]
- [x] 0x13 MHZMushroomCatapult (x2) [0x00]
- [x] 0x23 MHZMushroomCap (x118) [0x00, 0xC1, 0x81]
- [x] 0x28 InvisibleBlock (x6) [5 subtypes]
- [x] 0x2F StillSprite (x153) [7 subtypes]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x1) [0x10]
- [x] 0x80 HiddenMonitor (x4) [0x03, 0x05, 0x06]
- [x] 0x82 CutsceneKnuckles (x1) [0x30]
- [x] 0x85 SSEntryRing (x5) [5 subtypes]
- [x] 0xA8 MHZ1CutsceneKnuckles (x1) [0x00]
- [x] 0xA9 MHZ1CutsceneButton (x1) [0x00]

#### Act 2

Total: 554 objects | Implemented: 30 | Unimplemented: 0

**Badniks:**
- [x] 0x8C Madmole (x3) [0x00]
- [x] 0x8D Mushmeanie (x3) [0x00]
- [x] 0x8E Dragonfly (x15) [0x00]
- [x] 0x8F Butterdroid (x1) [0x00]
- [x] 0x90 Cluckoid (x13) [4 subtypes]

**Bosses:**
- [x] 0x93 MHZEndBoss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x22) [7 subtypes]
- [x] 0x02 PathSwap (x107) [25 subtypes]
- [x] 0x03 MHZTwistedVine (x7) [0x00]
- [x] 0x06 MHZPulleyLift (x4) [4 subtypes]
- [x] 0x07 Spring (x41) [8 subtypes]
- [x] 0x08 Spikes (x30) [7 subtypes]
- [x] 0x09 MHZCurledVine (x11) [0x00]
- [x] 0x0A MHZStickyVine (x4) [0x00]
- [x] 0x0B MHZSwingBarHorizontal (x10) [0x00]
- [x] 0x0C MHZSwingBarVertical (x2) [0x00]
- [x] 0x0D BreakableWall (x12) [0x00]
- [x] 0x0E TwistedRamp (x2) [0x00]
- [x] 0x10 MHZSwingVine (x2) [0x00]
- [x] 0x11 MHZMushroomPlatform (x6) [0x00, 0x01]
- [x] 0x12 MHZMushroomParachute (x3) [0x00]
- [x] 0x13 MHZMushroomCatapult (x5) [0x00]
- [x] 0x14 Updraft (x2) [0x56, 0x36]
- [x] 0x23 MHZMushroomCap (x93) [0x00, 0x81, 0xC1]
- [x] 0x28 InvisibleBlock (x4) [4 subtypes]
- [x] 0x2F StillSprite (x139) [7 subtypes]
- [x] 0x34 StarPost (x4) [4 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x1) [0x70]
- [x] 0x82 CutsceneKnuckles (x1) [0x20]
- [x] 0x85 SSEntryRing (x6) [6 subtypes]

### Sandopolis Zone

#### Act 1

Total: 599 objects | Implemented: 15 | Unimplemented: 14

**Badniks:**
- [ ] 0x94 Skorp (x22) [7 subtypes]
- [ ] 0x95 Sandworm (x24) [0x00]
- [ ] 0x96 Rockn (x17) [17 subtypes]

**Objects:**
- [x] 0x01 Monitor (x34) [6 subtypes]
- [x] 0x02 PathSwap (x32) [15 subtypes]
- [x] 0x07 Spring (x27) [5 subtypes]
- [x] 0x08 Spikes (x38) [7 subtypes]
- [x] 0x0D BreakableWall (x11) [0x00, 0x04]
- [x] 0x0E TwistedRamp (x6) [0x00]
- [x] 0x0F CollapsingBridge (x14) [4 subtypes]
- [x] 0x26 AutoSpin (x10) [0x30]
- [x] 0x28 InvisibleBlock (x10) [4 subtypes]
- [x] 0x2F StillSprite (x80) [0x2E]
- [x] 0x30 AnimatedStillSprite (x21) [4 subtypes]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [ ] 0x38 SOZQuicksand (x61) [21 subtypes]
- [ ] 0x39 SOZSpawningSandBlocks (x3) [0x12, 0x15]
- [ ] 0x3A SOZPathSwap (x10) [0x11, 0x09]
- [ ] 0x3E SOZPushableRock (x7) [7 subtypes]
- [ ] 0x3F SOZSpringVine (x12) [0x00]
- [ ] 0x40 SOZRisingSandWall (x4) [0x60]
- [ ] 0x42 SOZFloatingPillar (x55) [13 subtypes]
- [ ] 0x43 SOZSwingingPlatform (x20) [6 subtypes]
- [ ] 0x44 SOZBreakableSandRock (x17) [0x00]
- [ ] 0x48 SOZRapelWire (x7) [5 subtypes]
- [ ] 0x49 SOZSolidSprites (x16) [0x00, 0x01]
- [x] 0x6B InvisibleHurtBlockV (x26) [8 subtypes]
- [x] 0x80 HiddenMonitor (x3) [0x01, 0x03, 0x05]
- [x] 0x85 SSEntryRing (x7) [7 subtypes]

#### Act 2

Total: 490 objects | Implemented: 13 | Unimplemented: 21

**Badniks:**
- [ ] 0x94 Skorp (x13) [8 subtypes]
- [ ] 0x95 Sandworm (x8) [0x00]

**Bosses:**
- [ ] 0x98 SOZEndBoss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x17) [7 subtypes]
- [x] 0x02 PathSwap (x33) [15 subtypes]
- [x] 0x07 Spring (x24) [6 subtypes]
- [x] 0x08 Spikes (x21) [6 subtypes]
- [x] 0x0D BreakableWall (x16) [0x00, 0x04]
- [x] 0x0F CollapsingBridge (x6) [0x04, 0x06]
- [x] 0x26 AutoSpin (x8) [0x30]
- [x] 0x28 InvisibleBlock (x7) [6 subtypes]
- [x] 0x2F StillSprite (x93) [0x2E, 0x2F]
- [x] 0x30 AnimatedStillSprite (x8) [0x05, 0x06, 0x07]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [ ] 0x38 SOZQuicksand (x23) [16 subtypes]
- [ ] 0x3A SOZPathSwap (x8) [0x11, 0x09]
- [ ] 0x3B SOZLoopFallthrough (x3) [0x21]
- [ ] 0x3E SOZPushableRock (x3) [0x05, 0x87, 0x0A]
- [ ] 0x3F SOZSpringVine (x5) [0x00]
- [ ] 0x40 SOZRisingSandWall (x2) [0x60]
- [ ] 0x41 SOZLightSwitch (x25) [0x04, 0x84]
- [ ] 0x42 SOZFloatingPillar (x41) [14 subtypes]
- [ ] 0x43 SOZSwingingPlatform (x13) [6 subtypes]
- [ ] 0x44 SOZBreakableSandRock (x13) [0x00]
- [ ] 0x45 SOZPushSwitch (x19) [14 subtypes]
- [ ] 0x46 SOZDoor (x20) [15 subtypes]
- [ ] 0x47 SOZSandCork (x4) [4 subtypes]
- [ ] 0x48 SOZRapelWire (x5) [4 subtypes]
- [ ] 0x49 SOZSolidSprites (x14) [0x00, 0x01]
- [x] 0x6B InvisibleHurtBlockV (x24) [12 subtypes]
- [x] 0x85 SSEntryRing (x4) [4 subtypes]
- [ ] 0x8B SpriteMask (x1) [0x40]
- [ ] 0xAB SOZCapsuleHyudoro (x2) [0x00, 0x04]
- [ ] 0xAC SOZCapsule (x1) [0x00]

### Lava Reef Zone

#### Act 1

Total: 609 objects | Implemented: 16 | Unimplemented: 21

**Badniks:**
- [ ] 0x99 Fireworm (x20) [0x00]
- [ ] 0x9A Iwamodoki (x32) [0x00]
- [ ] 0x9B Toxomister (x22) [0x00]

**Bosses:**
- [ ] 0x9C LRZRockCrusher (x2) [0x00, 0x02]
- [ ] 0x9D LRZMiniboss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x31) [6 subtypes]
- [x] 0x02 PathSwap (x12) [5 subtypes]
- [x] 0x05 AIZLRZEMZRock (x89) [4 subtypes]
- [x] 0x07 Spring (x15) [4 subtypes]
- [x] 0x08 Spikes (x17) [5 subtypes]
- [x] 0x0E TwistedRamp (x3) [0x00]
- [ ] 0x15 LRZCorkscrew (x1) [0x00]
- [ ] 0x16 LRZWallRide (x1) [0x00]
- [ ] 0x17 LRZSinkingRock (x11) [0x00]
- [ ] 0x18 LRZFallingSpike (x15) [5 subtypes]
- [ ] 0x19 LRZDoor (x15) [15 subtypes]
- [ ] 0x1A LRZBigDoor (x1) [0x00]
- [ ] 0x1B LRZFireballLauncher (x27) [11 subtypes]
- [ ] 0x1C LRZButtonHorizontal (x10) [10 subtypes]
- [ ] 0x1D LRZShootingTrigger (x2) [0xA0, 0xC2]
- [ ] 0x1E LRZDashElevator (x6) [6 subtypes]
- [ ] 0x1F LRZLavaFall (x7) [0x70, 0x50, 0x60]
- [ ] 0x20 LRZSwingingSpikeBall (x11) [0x02, 0x03, 0x04]
- [ ] 0x21 LRZSmashingSpikePlatform (x15) [10 subtypes]
- [ ] 0x22 LRZSpikeBall (x6) [0x00, 0xC0]
- [x] 0x28 InvisibleBlock (x23) [8 subtypes]
- [x] 0x2F StillSprite (x117) [5 subtypes]
- [x] 0x30 AnimatedStillSprite (x7) [0x02]
- [x] 0x31 LRZCollapsingBridge (x27) [0x00, 0x01, 0x02]
- [x] 0x33 Button (x3) [0x03, 0x0A, 0x0E]
- [x] 0x34 StarPost (x6) [6 subtypes]
- [x] 0x6B InvisibleHurtBlockV (x10) [7 subtypes]
- [x] 0x6C TensionBridge (x1) [0x0E]
- [ ] 0x6E InvisibleLavaBlock (x34) [4 subtypes]
- [x] 0x80 HiddenMonitor (x3) [0x03, 0x05, 0x06]
- [x] 0x85 SSEntryRing (x3) [0x02, 0x03, 0x04]
- [ ] 0x8B SpriteMask (x3) [0xF1, 0x84]

#### Act 2

Total: 455 objects | Implemented: 15 | Unimplemented: 17

**Badniks:**
- [ ] 0x99 Fireworm (x9) [0x00]
- [ ] 0x9A Iwamodoki (x34) [0x00]
- [ ] 0x9B Toxomister (x9) [0x00]

**Objects:**
- [x] 0x01 Monitor (x17) [7 subtypes]
- [x] 0x02 PathSwap (x19) [9 subtypes]
- [x] 0x05 AIZLRZEMZRock (x21) [0xF4]
- [x] 0x07 Spring (x24) [5 subtypes]
- [x] 0x08 Spikes (x14) [5 subtypes]
- [x] 0x0D BreakableWall (x4) [0x00]
- [x] 0x0E TwistedRamp (x1) [0x00]
- [x] 0x0F CollapsingBridge (x25) [0x00, 0x03]
- [ ] 0x16 LRZWallRide (x1) [0x00]
- [ ] 0x19 LRZDoor (x11) [11 subtypes]
- [ ] 0x1C LRZButtonHorizontal (x11) [11 subtypes]
- [ ] 0x20 LRZSwingingSpikeBall (x14) [0x02, 0x03]
- [x] 0x24 AutomaticTunnel (x10) [10 subtypes]
- [ ] 0x25 LRZChainedPlatforms (x3) [0x80, 0x81, 0x82]
- [x] 0x28 InvisibleBlock (x15) [4 subtypes]
- [ ] 0x29 LRZFlameThrower (x52) [11 subtypes]
- [ ] 0x2B LRZOrbitingSpikeBallH (x12) [0x00, 0x80]
- [ ] 0x2C LRZOrbitingSpikeBallV (x40) [16 subtypes]
- [ ] 0x2D LRZSolidMovingPlatforms (x52) [10 subtypes]
- [x] 0x30 AnimatedStillSprite (x6) [0x03]
- [ ] 0x32 LRZTurbineSprites (x18) [0x00, 0x01]
- [x] 0x33 Button (x1) [0x05]
- [x] 0x34 StarPost (x5) [5 subtypes]
- [ ] 0x37 LRZSpikeBallLauncher (x9) [0x50, 0x70, 0x60]
- [x] 0x6B InvisibleHurtBlockV (x7) [5 subtypes]
- [ ] 0x6E InvisibleLavaBlock (x4) [0x71]
- [x] 0x85 SSEntryRing (x5) [5 subtypes]
- [ ] 0xAE LRZ2CutsceneKnuckles (x1) [0x00]
- [ ] 0xB3 StartNewLevel (x1) [0x2D]

### Sky Sanctuary Zone

#### Act 1

Total: 213 objects | Implemented: 7 | Unimplemented: 13

**Bosses:**
- [ ] 0xA0 EggRobo (x26) [23 subtypes]

**Objects:**
- [x] 0x01 Monitor (x16) [6 subtypes]
- [x] 0x02 PathSwap (x1) [0x45]
- [x] 0x07 Spring (x9) [4 subtypes]
- [x] 0x08 Spikes (x11) [0x10, 0x00, 0x11]
- [x] 0x14 Updraft (x4) [0x01]
- [x] 0x28 InvisibleBlock (x3) [0x30, 0x81, 0x11]
- [x] 0x34 StarPost (x3) [0x02, 0x03, 0x04]
- [ ] 0x74 SSZRetractingSpring (x5) [0x00]
- [ ] 0x75 SSZSwingingCarrier (x8) [0x80, 0x00, 0x82]
- [ ] 0x76 SSZRotatingPlatform (x7) [0x00, 0x01]
- [ ] 0x77 SSZCutsceneBridge (x1) [0x00]
- [ ] 0x79 SSZHPZTeleporter (x10) [6 subtypes]
- [ ] 0x7A SSZElevatorBar (x5) [0x00]
- [ ] 0x7B SSZCollapsingBridgeDiagonal (x35) [0x00, 0x80]
- [ ] 0x7C SSZCollapsingBridge (x8) [0x00, 0x80]
- [ ] 0x7D SSZBouncyCloud (x27) [0x00]
- [ ] 0x7E SSZCollapsingColumn (x25) [0x00]
- [ ] 0x7F SSZFloatingPlatform (x8) [0x00]
- [ ] 0xAF SSZCutsceneButton (x1) [0x00]

#### Act 2

Total: 5 objects | Implemented: 0 | Unimplemented: 3

**Objects:**
- [ ] 0x00 Ring (x3) [0x00]
- [ ] 0x79 SSZHPZTeleporter (x1) [0x00]
- [ ] 0xB2 KnuxFinalBossCrane (x1) [0x00]

### Death Egg Zone

#### Act 1

Total: 365 objects | Implemented: 9 | Unimplemented: 20

**Badniks:**
- [ ] 0xA4 Spikebonker (x7) [0x20, 0x40]
- [ ] 0xA5 Chainspike (x6) [0x00]

**Bosses:**
- [ ] 0xA6 DEZMiniboss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x19) [5 subtypes]
- [x] 0x02 PathSwap (x1) [0x21]
- [x] 0x07 Spring (x20) [5 subtypes]
- [x] 0x08 Spikes (x31) [7 subtypes]
- [x] 0x28 InvisibleBlock (x26) [11 subtypes]
- [x] 0x2F StillSprite (x19) [0x30, 0x31, 0x32]
- [x] 0x34 StarPost (x3) [0x01, 0x02, 0x03]
- [x] 0x3C Door (x11) [0x02]
- [ ] 0x4B DEZTiltingBridge (x1) [0x00]
- [ ] 0x4C DEZHangCarrier (x3) [0x34, 0x44, 0x25]
- [ ] 0x4D DEZTorpedoLauncher (x36) [10 subtypes]
- [ ] 0x4E DEZLiftPad (x7) [0x07, 0x27]
- [ ] 0x4F DEZStaircase (x18) [0x00, 0x04]
- [ ] 0x50 DEZConveyorBelt (x8) [5 subtypes]
- [ ] 0x52 DEZLightning (x48) [21 subtypes]
- [ ] 0x53 DEZConveyorPad (x4) [4 subtypes]
- [ ] 0x55 DEZEnergyBridge (x13) [4 subtypes]
- [ ] 0x56 DEZEnergyBridgeCurved (x1) [0x07]
- [ ] 0x57 DEZTunnelLauncher (x3) [0x00, 0x01, 0x06]
- [ ] 0x5A DEZGravityTube (x24) [5 subtypes]
- [ ] 0x5E DEZHoverMachine (x11) [0x00]
- [ ] 0x5F DEZGravityRoom (x1) [0x00]
- [ ] 0x60 DEZBumperWall (x10) [4 subtypes]
- [ ] 0x61 DEZGravityPuzzle (x1) [0x00]
- [ ] 0x6D InvisibleShockBlock (x22) [5 subtypes]
- [x] 0x78 FBZDEZPlayerLauncher (x10) [0x00]

#### Act 2

Total: 494 objects | Implemented: 9 | Unimplemented: 20

**Badniks:**
- [ ] 0xA4 Spikebonker (x11) [0x20, 0x40]
- [ ] 0xA5 Chainspike (x12) [0x00]

**Bosses:**
- [ ] 0xA7 DEZEndBoss (x1) [0x00]

**Objects:**
- [x] 0x01 Monitor (x22) [6 subtypes]
- [x] 0x07 Spring (x15) [6 subtypes]
- [x] 0x08 Spikes (x60) [8 subtypes]
- [x] 0x28 InvisibleBlock (x24) [4 subtypes]
- [x] 0x2F StillSprite (x20) [0x30, 0x32]
- [x] 0x34 StarPost (x4) [4 subtypes]
- [x] 0x3C Door (x6) [0x02]
- [ ] 0x4A DEZFloatingPlatform (x10) [5 subtypes]
- [ ] 0x4B DEZTiltingBridge (x3) [0x00]
- [ ] 0x4C DEZHangCarrier (x1) [0x25]
- [ ] 0x4D DEZTorpedoLauncher (x38) [6 subtypes]
- [ ] 0x4F DEZStaircase (x15) [0x00, 0x04]
- [ ] 0x50 DEZConveyorBelt (x5) [0x10, 0x18]
- [ ] 0x52 DEZLightning (x94) [23 subtypes]
- [ ] 0x53 DEZConveyorPad (x5) [4 subtypes]
- [ ] 0x55 DEZEnergyBridge (x12) [0x01, 0x45, 0x05]
- [ ] 0x57 DEZTunnelLauncher (x4) [4 subtypes]
- [ ] 0x58 DEZGravitySwitch (x5) [0x00]
- [ ] 0x59 DEZTeleporter (x21) [15 subtypes]
- [ ] 0x5A DEZGravityTube (x17) [9 subtypes]
- [ ] 0x5B DEZGravitySwap (x11) [0x00]
- [ ] 0x5C DEZGravityHub (x3) [0x05, 0x06, 0x0F]
- [ ] 0x5D DEZRetractingSpring (x13) [0x02]
- [x] 0x6A InvisibleHurtBlockH (x1) [0xF1]
- [x] 0x6B InvisibleHurtBlockV (x5) [0xF1]
- [ ] 0x6D InvisibleShockBlock (x56) [4 subtypes]

### The Doomsday Zone

#### Act 1

Total: 477 objects | Implemented: 0 | Unimplemented: 3

**Bosses:**
- [ ] 0xB6 DDZEndBoss (x1) [0x00]

**Objects:**
- [ ] 0xB7 DDZAsteroid (x426) [14 subtypes]
- [ ] 0xB8 DDZMissile (x50) [0x00]


# Sonic 2 Object Implementation Checklist

Generated: 2026-01-17 20:04:16

## Summary

- **Total unique objects found:** 120
- **Implemented:** 16 (13.3%)
- **Unimplemented:** 104 (86.7%)

## Implemented Objects

| ID | Name | Total Uses | Zones |
|----|------|------------|-------|
| 0x03 | LayerSwitcher | 299 | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, SCZ1 |
| 0x06 | Spiral | 18 | EHZ1, EHZ2, MTZ1, MTZ2, MTZ3 |
| 0x0D | GoalPlate | 13 | EHZ1, EHZ2, CPZ1, ARZ1, CNZ1, CNZ2, HTZ1, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2 |
| 0x11 | Bridge | 14 | EHZ1, EHZ2 |
| 0x15 | SwingingPlatform | 7 | ARZ2, MCZ1, MCZ2 |
| 0x18 | ARZPlatform | 71 | EHZ1, EHZ2, ARZ1, ARZ2, HTZ1, HTZ2 |
| 0x19 | CPZPlatform | 55 | CPZ1, CPZ2, OOZ1, OOZ2, WFZ1 |
| 0x1C | Scenery | 135 | EHZ1, EHZ2, HTZ1, HTZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3 |
| 0x26 | Monitor | 245 | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3, WFZ1 |
| 0x36 | Spikes | 204 | EHZ1, EHZ2, CPZ2, ARZ1, CNZ1, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3 |
| 0x41 | Spring | 139 | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3 |
| 0x49 | EHZWaterfall | 18 | EHZ1, EHZ2 |
| 0x4B | Buzzer | 23 | EHZ1, EHZ2 |
| 0x5C | Masher | 21 | EHZ1, EHZ2 |
| 0x79 | Checkpoint | 61 | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3, WFZ1 |
| 0x9D | Coconuts | 17 | EHZ1, EHZ2 |

## Unimplemented Objects (By Usage)

| ID | Category | Name | Total Uses | Zones |
|----|----------|------|------------|-------|
| 0x44 | Object | Bumper | 117 | CNZ1, CNZ2 |
| 0x74 | Object | InvisibleBlock | 113 | CPZ1, CPZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, MTZ1, MTZ2, MTZ3, WFZ1 |
| 0xD8 | Object | BonusBlock | 98 | CNZ1, CNZ2 |
| 0xA4 | Badnik | Asteron | 90 | MTZ1, MTZ2, MTZ3 |
| 0x1F | Object | CollapsPform | 84 | ARZ1, ARZ2, MCZ1, MCZ2, OOZ1, OOZ2 |
| 0x65 | Object | MTZLongPlatform | 81 | MTZ1, MTZ2, MTZ3 |
| 0x86 | Object | Flipper | 63 | CNZ1, CNZ2 |
| 0x3F | Object | Fan | 60 | OOZ1, OOZ2 |
| 0x66 | Object | MTZSpringWall | 60 | MTZ1, MTZ2, MTZ3 |
| 0x6B | Object | MTZPlatform | 58 | CPZ1, CPZ2, MTZ1, MTZ2, MTZ3 |
| 0x31 | Object | LavaMarker | 50 | HTZ1, HTZ2, MTZ2, MTZ3 |
| 0x2C | Object | LeavesGenerator | 43 | ARZ1, ARZ2 |
| 0x84 | Object | ForcedSpin | 42 | CNZ1, CNZ2, HTZ1, HTZ2 |
| 0x2F | Object | SmashableGround | 40 | HTZ1, HTZ2 |
| 0x91 | Badnik | ChopChop | 38 | ARZ1, ARZ2 |
| 0x2D | Object | Barrier | 35 | CPZ1, CPZ2, HTZ1, HTZ2, MTZ1, MTZ2, MTZ3, WFZ1, DEZ1 |
| 0x48 | Object | LauncherBall | 32 | OOZ1, OOZ2 |
| 0x40 | Object | Springboard | 31 | CPZ2, ARZ1, ARZ2, MCZ2 |
| 0xD6 | Object | PointPokey | 29 | CNZ1, CNZ2 |
| 0x32 | Object | BreakableBlock | 28 | CPZ1, CPZ2, HTZ1, HTZ2 |
| 0x4A | Badnik | Octus | 28 | OOZ1, OOZ2 |
| 0x69 | Object | Nut | 28 | MTZ1, MTZ2, MTZ3 |
| 0x6E | Object | LargeRotPform | 28 | MTZ3 |
| 0xA3 | Badnik | Flasher | 26 | MCZ1, MCZ2 |
| 0x6D | Object | FloorSpike | 25 | MTZ1, MTZ2, MTZ3 |
| 0x8C | Badnik | Whisp | 25 | ARZ1, ARZ2 |
| 0x22 | Object | ArrowShooter | 24 | ARZ1, ARZ2 |
| 0x80 | Object | MovingVine | 24 | MCZ1, MCZ2, WFZ1 |
| 0xA1 | Badnik | Slicer | 24 | MTZ1, MTZ2, MTZ3 |
| 0x92 | Badnik | Spiker | 23 | HTZ1, HTZ2 |
| 0x99 | Badnik | Nebula | 23 | SCZ1 |
| 0xD4 | Object | CNZBigBlock | 23 | CNZ1, CNZ2 |
| 0x33 | Object | OOZPoppingPform | 22 | OOZ1, OOZ2 |
| 0x68 | Object | SpikyBlock | 22 | MTZ1, MTZ2, MTZ3 |
| 0x1B | Object | SpeedBooster | 20 | CPZ1, CPZ2 |
| 0x24 | Object | Bubbles | 20 | ARZ1, ARZ2 |
| 0x8E | Badnik | GrounderInWall2 | 20 | ARZ1, ARZ2 |
| 0x75 | Object | MCZBrick | 19 | MCZ1, MCZ2 |
| 0xAC | Badnik | Balkiry | 19 | SCZ1 |
| 0x0B | Object | TippingFloor | 18 | CPZ1, CPZ2 |
| 0x42 | Object | SteamSpring | 18 | MTZ1, MTZ2, MTZ3 |
| 0xD7 | Object | HexBumper | 18 | CNZ1, CNZ2 |
| 0x1E | Object | CPZSpinTube | 16 | CPZ1, CPZ2 |
| 0x23 | Object | FallingPillar | 16 | ARZ1, ARZ2 |
| 0x50 | Badnik | Aquis | 16 | OOZ1, OOZ2 |
| 0xD5 | Object | Elevator | 16 | CNZ1, CNZ2 |
| 0x71 | Object | MTZLavaBubble | 15 | MTZ2, MTZ3 |
| 0x72 | Object | CNZConveyorBelt | 15 | CNZ1, CNZ2, MTZ2, MTZ3, WFZ1 |
| 0x82 | Object | SwingingPform | 15 | ARZ1, ARZ2 |
| 0x16 | Object | HTZLift | 14 | HTZ1, HTZ2 |
| 0x47 | Object | Button | 14 | MTZ1, MTZ2, MTZ3 |
| 0x70 | Object | Cog | 14 | MTZ2, MTZ3 |
| 0x76 | Object | SlidingSpikes | 14 | MCZ1, MCZ2 |
| 0x85 | Object | LauncherSpring | 14 | CNZ1, CNZ2 |
| 0x14 | Object | Seesaw | 13 | HTZ1, HTZ2 |
| 0x2A | Object | Stomper | 13 | MCZ1, MCZ2 |
| 0xB4 | Object | VPropeller | 13 | SCZ1, WFZ1 |
| 0x67 | Object | MTZSpinTube | 12 | MTZ1, MTZ2, MTZ3 |
| 0x81 | Object | MCZDrawbridge | 12 | MCZ1, MCZ2 |
| 0x9E | Badnik | Crawlton | 12 | MCZ1, MCZ2 |
| 0x2B | Object | RisingPillar | 11 | ARZ1, ARZ2 |
| 0x7F | Object | VineSwitch | 11 | MCZ1, MCZ2 |
| 0x8B | Object | WFZPalSwitcher | 11 | WFZ1 |
| 0xA5 | Badnik | Spiny | 11 | CPZ1, CPZ2 |
| 0x6C | Object | Conveyor | 10 | MTZ2, MTZ3 |
| 0x9A | Badnik | Turtloid | 10 | SCZ1 |
| 0xAD | Badnik | CluckerBase | 10 | WFZ1 |
| 0xAE | Badnik | Clucker | 10 | WFZ1 |
| 0xB5 | Object | HPropeller | 10 | SCZ1, WFZ1 |
| 0x7A | Object | SidewaysPform | 9 | CPZ2, MCZ1, MCZ2 |
| 0x7B | Object | PipeExitSpring | 9 | CPZ1, CPZ2 |
| 0x9F | Badnik | Shellcracker | 9 | MTZ1, MTZ2, MTZ3 |
| 0x8D | Badnik | GrounderInWall | 8 | ARZ1, ARZ2 |
| 0xC0 | Object | SpeedLauncher | 8 | WFZ1 |
| 0xC8 | Badnik | Crawl | 8 | CNZ1, CNZ2 |
| 0xD2 | Object | CNZRectBlocks | 8 | CNZ1, CNZ2 |
| 0x1D | Object | BlueBalls | 7 | CPZ1 |
| 0x30 | Object | RisingLava | 7 | HTZ1, HTZ2 |
| 0x3E | Object | EggPrison | 7 | EHZ2, CPZ2, ARZ2, CNZ2, HTZ2, MCZ2, MTZ3 |
| 0x6A | Object | MCZRotPforms | 7 | MCZ1, MCZ2, MTZ3 |
| 0xBE | Object | LateralCannon | 7 | WFZ1 |
| 0x3D | Object | OOZLauncher | 6 | OOZ1, OOZ2 |
| 0x64 | Object | MTZTwinStompers | 6 | MTZ1, MTZ2, MTZ3 |
| 0x78 | Object | CPZStaircase | 6 | CPZ1, CPZ2 |
| 0x96 | Badnik | Rexon2 | 6 | HTZ1, HTZ2 |
| 0xB9 | Object | Laser | 6 | WFZ1 |
| 0x77 | Object | MCZBridge | 5 | MCZ1, MCZ2 |
| 0xA7 | Badnik | Grabber | 5 | CPZ1, CPZ2 |
| 0xB6 | Object | TiltingPlatform | 5 | WFZ1 |
| 0xB8 | Object | WallTurret | 5 | WFZ1 |
| 0xBD | Object | SmallMetalPform | 5 | WFZ1 |
| 0x83 | Object | ARZRotPforms | 4 | ARZ1, ARZ2 |
| 0xC1 | Object | BreakablePlating | 4 | WFZ1 |
| 0x95 | Badnik | Sol | 3 | HTZ1, HTZ2 |
| 0xB2 | Object | Tornado | 3 | SCZ1, WFZ1 |
| 0xB3 | Object | Cloud | 3 | SCZ1 |
| 0xD9 | Object | Grab | 3 | WFZ1 |
| 0xA6 | Badnik | SpinyOnWall | 2 | CPZ1, CPZ2 |
| 0xBA | Object | WFZWheel | 2 | WFZ1 |
| 0xBC | Object | WFZShipFire | 2 | WFZ1 |
| 0xC2 | Object | Rivet | 1 | WFZ1 |
| 0xC5 | Boss | WFZBoss | 1 | WFZ1 |
| 0xC6 | Boss | Eggman | 1 | DEZ1 |
| 0xC7 | Boss | Eggrobo | 1 | DEZ1 |

---

## By Zone

### Emerald Hill Zone

#### Act 1

Total: 135 objects | Implemented: 14 | Unimplemented: 0

**Badniks:**
- [x] 0x4B Buzzer (x11) [0x00]
- [x] 0x5C Masher (x13) [0x00]
- [x] 0x9D Coconuts (x8) [0x1E]

**Objects:**
- [x] 0x03 LayerSwitcher (x19) [5 subtypes]
- [x] 0x06 Spiral (x3) [0x00]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x11 Bridge (x7) [4 subtypes]
- [x] 0x18 ARZPlatform (x7) [0x01, 0x02, 0x05]
- [x] 0x1C Scenery (x14) [0x02]
- [x] 0x26 Monitor (x13) [5 subtypes]
- [x] 0x36 Spikes (x12) [0x00, 0x10, 0x01]
- [x] 0x41 Spring (x14) [7 subtypes]
- [x] 0x49 EHZWaterfall (x10) [0x00, 0x02, 0x04]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03]

#### Act 2

Total: 158 objects | Implemented: 14 | Unimplemented: 1

**Badniks:**
- [x] 0x4B Buzzer (x12) [0x00]
- [x] 0x5C Masher (x8) [0x00]
- [x] 0x9D Coconuts (x9) [0x1E]

**Objects:**
- [x] 0x03 LayerSwitcher (x34) [10 subtypes]
- [x] 0x06 Spiral (x4) [0x00]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x11 Bridge (x7) [0x0C]
- [x] 0x18 ARZPlatform (x11) [0x01, 0x02, 0x03]
- [x] 0x1C Scenery (x14) [0x02]
- [x] 0x26 Monitor (x12) [5 subtypes]
- [x] 0x36 Spikes (x20) [0x00, 0x10, 0x01]
- [ ] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x12) [7 subtypes]
- [x] 0x49 EHZWaterfall (x8) [4 subtypes]
- [x] 0x79 Checkpoint (x5) [5 subtypes]

### Chemical Plant Zone

#### Act 1

Total: 153 objects | Implemented: 6 | Unimplemented: 13

**Badniks:**
- [ ] 0xA5 Spiny (x10) [0x32]
- [ ] 0xA6 SpinyOnWall (x1) [0x32]
- [ ] 0xA7 Grabber (x2) [0x36]

**Objects:**
- [x] 0x03 LayerSwitcher (x60) [23 subtypes]
- [ ] 0x0B TippingFloor (x4) [4 subtypes]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x19 CPZPlatform (x7) [4 subtypes]
- [ ] 0x1B SpeedBooster (x8) [0x00]
- [ ] 0x1D BlueBalls (x7) [0x15, 0x05]
- [ ] 0x1E CPZSpinTube (x9) [9 subtypes]
- [x] 0x26 Monitor (x19) [4 subtypes]
- [ ] 0x2D Barrier (x2) [0x00]
- [ ] 0x32 BreakableBlock (x4) [0x00]
- [x] 0x41 Spring (x5) [0x10, 0x02, 0x12]
- [ ] 0x6B MTZPlatform (x2) [0x19]
- [ ] 0x74 InvisibleBlock (x2) [0x17]
- [ ] 0x78 CPZStaircase (x2) [0x00, 0x04]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03]
- [ ] 0x7B PipeExitSpring (x5) [0x02]

#### Act 2

Total: 202 objects | Implemented: 6 | Unimplemented: 15

**Badniks:**
- [ ] 0xA5 Spiny (x1) [0x32]
- [ ] 0xA6 SpinyOnWall (x1) [0x32]
- [ ] 0xA7 Grabber (x3) [0x36]

**Objects:**
- [x] 0x03 LayerSwitcher (x59) [21 subtypes]
- [ ] 0x0B TippingFloor (x14) [6 subtypes]
- [x] 0x19 CPZPlatform (x6) [6 subtypes]
- [ ] 0x1B SpeedBooster (x12) [0x00]
- [ ] 0x1E CPZSpinTube (x7) [7 subtypes]
- [x] 0x26 Monitor (x19) [4 subtypes]
- [ ] 0x2D Barrier (x4) [0x00]
- [ ] 0x32 BreakableBlock (x3) [0x00]
- [x] 0x36 Spikes (x3) [0x30]
- [ ] 0x3E EggPrison (x1) [0x00]
- [ ] 0x40 Springboard (x6) [0x03]
- [x] 0x41 Spring (x3) [0x10, 0x12]
- [ ] 0x6B MTZPlatform (x28) [0x18, 0x19]
- [ ] 0x74 InvisibleBlock (x16) [5 subtypes]
- [ ] 0x78 CPZStaircase (x4) [0x00, 0x04]
- [x] 0x79 Checkpoint (x5) [5 subtypes]
- [ ] 0x7A SidewaysPform (x3) [0x00, 0x06, 0x0C]
- [ ] 0x7B PipeExitSpring (x4) [0x02]

### Aquatic Ruin Zone

#### Act 1

Total: 182 objects | Implemented: 7 | Unimplemented: 13

**Badniks:**
- [ ] 0x8C Whisp (x6) [0x00]
- [ ] 0x8D GrounderInWall (x4) [0x02]
- [ ] 0x8E GrounderInWall2 (x11) [0x02]
- [ ] 0x91 ChopChop (x17) [0x08]

**Objects:**
- [x] 0x03 LayerSwitcher (x30) [18 subtypes]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x18 ARZPlatform (x11) [5 subtypes]
- [ ] 0x1F CollapsPform (x5) [0x00]
- [ ] 0x22 ArrowShooter (x8) [0x00]
- [ ] 0x23 FallingPillar (x5) [0x00]
- [ ] 0x24 Bubbles (x10) [0x80]
- [x] 0x26 Monitor (x13) [5 subtypes]
- [ ] 0x2B RisingPillar (x4) [0x00]
- [ ] 0x2C LeavesGenerator (x34) [0x00, 0x01, 0x02]
- [x] 0x36 Spikes (x1) [0x30]
- [ ] 0x40 Springboard (x6) [0x03]
- [x] 0x41 Spring (x8) [0x10, 0x12]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03]
- [ ] 0x82 SwingingPform (x2) [0x10]
- [ ] 0x83 ARZRotPforms (x3) [0x10]

#### Act 2

Total: 222 objects | Implemented: 6 | Unimplemented: 14

**Badniks:**
- [ ] 0x8C Whisp (x19) [0x00]
- [ ] 0x8D GrounderInWall (x4) [0x02]
- [ ] 0x8E GrounderInWall2 (x9) [0x02]
- [ ] 0x91 ChopChop (x21) [0x08]

**Objects:**
- [x] 0x03 LayerSwitcher (x28) [17 subtypes]
- [x] 0x15 SwingingPlatform (x4) [4 subtypes]
- [x] 0x18 ARZPlatform (x10) [4 subtypes]
- [ ] 0x1F CollapsPform (x10) [0x00]
- [ ] 0x22 ArrowShooter (x16) [0x00]
- [ ] 0x23 FallingPillar (x11) [0x00]
- [ ] 0x24 Bubbles (x10) [0x81]
- [x] 0x26 Monitor (x18) [4 subtypes]
- [ ] 0x2B RisingPillar (x7) [0x00]
- [ ] 0x2C LeavesGenerator (x9) [0x00, 0x01, 0x02]
- [ ] 0x3E EggPrison (x1) [0x00]
- [ ] 0x40 Springboard (x10) [0x03]
- [x] 0x41 Spring (x17) [5 subtypes]
- [x] 0x79 Checkpoint (x4) [4 subtypes]
- [ ] 0x82 SwingingPform (x13) [0x10, 0x11]
- [ ] 0x83 ARZRotPforms (x1) [0x10]

### Casino Night Zone

#### Act 1

Total: 286 objects | Implemented: 6 | Unimplemented: 13

**Badniks:**
- [ ] 0xC8 Crawl (x2) [0xAC]

**Objects:**
- [x] 0x03 LayerSwitcher (x6) [0xD0, 0x52]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x26 Monitor (x13) [5 subtypes]
- [x] 0x36 Spikes (x4) [0x20, 0x10]
- [x] 0x41 Spring (x5) [0x00, 0x02]
- [ ] 0x44 Bumper (x74) [0x00]
- [ ] 0x72 CNZConveyorBelt (x4) [0x04, 0x08, 0x09]
- [ ] 0x74 InvisibleBlock (x17) [0x33, 0x35, 0x17]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02]
- [ ] 0x84 ForcedSpin (x18) [5 subtypes]
- [ ] 0x85 LauncherSpring (x7) [0x00, 0x81]
- [ ] 0x86 Flipper (x38) [0x00, 0x01]
- [ ] 0xD2 CNZRectBlocks (x4) [0x01]
- [ ] 0xD4 CNZBigBlock (x15) [0x00, 0x02]
- [ ] 0xD5 Elevator (x5) [0x48, 0x28, 0x38]
- [ ] 0xD6 PointPokey (x15) [0x00, 0x01]
- [ ] 0xD7 HexBumper (x12) [0x00, 0x01]
- [ ] 0xD8 BonusBlock (x44) [14 subtypes]

#### Act 2

Total: 254 objects | Implemented: 5 | Unimplemented: 14

**Badniks:**
- [ ] 0xC8 Crawl (x6) [0xAC]

**Objects:**
- [x] 0x03 LayerSwitcher (x9) [0x50, 0xD0, 0x52]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x26 Monitor (x16) [5 subtypes]
- [ ] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x9) [4 subtypes]
- [ ] 0x44 Bumper (x43) [0x00]
- [ ] 0x72 CNZConveyorBelt (x4) [0x08, 0x09]
- [ ] 0x74 InvisibleBlock (x18) [5 subtypes]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02]
- [ ] 0x84 ForcedSpin (x16) [5 subtypes]
- [ ] 0x85 LauncherSpring (x7) [0x00, 0x81]
- [ ] 0x86 Flipper (x25) [0x00, 0x01]
- [ ] 0xD2 CNZRectBlocks (x4) [0x01]
- [ ] 0xD4 CNZBigBlock (x8) [0x00, 0x02]
- [ ] 0xD5 Elevator (x11) [7 subtypes]
- [ ] 0xD6 PointPokey (x14) [0x00, 0x01]
- [ ] 0xD7 HexBumper (x6) [0x00, 0x01]
- [ ] 0xD8 BonusBlock (x54) [16 subtypes]

### Hill Top Zone

#### Act 1

Total: 144 objects | Implemented: 8 | Unimplemented: 12

**Badniks:**
- [ ] 0x92 Spiker (x6) [0x0A]
- [ ] 0x95 Sol (x1) [0x00]
- [ ] 0x96 Rexon2 (x2) [0x0E]

**Objects:**
- [x] 0x03 LayerSwitcher (x18) [12 subtypes]
- [x] 0x0D GoalPlate (x1) [0x00]
- [ ] 0x14 Seesaw (x9) [0x00]
- [ ] 0x16 HTZLift (x4) [0x14, 0x1C]
- [x] 0x18 ARZPlatform (x8) [4 subtypes]
- [x] 0x1C Scenery (x16) [4 subtypes]
- [x] 0x26 Monitor (x8) [0x04, 0x06, 0x07]
- [ ] 0x2D Barrier (x5) [0x00]
- [ ] 0x2F SmashableGround (x10) [5 subtypes]
- [ ] 0x30 RisingLava (x3) [0x00, 0x02, 0x04]
- [ ] 0x31 LavaMarker (x9) [0x01, 0x02]
- [ ] 0x32 BreakableBlock (x7) [0x00]
- [x] 0x36 Spikes (x12) [0x00, 0x01]
- [x] 0x41 Spring (x8) [5 subtypes]
- [ ] 0x74 InvisibleBlock (x11) [5 subtypes]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02]
- [ ] 0x84 ForcedSpin (x4) [0x00]

#### Act 2

Total: 259 objects | Implemented: 7 | Unimplemented: 13

**Badniks:**
- [ ] 0x92 Spiker (x17) [0x0A]
- [ ] 0x95 Sol (x2) [0x00]
- [ ] 0x96 Rexon2 (x4) [0x0E]

**Objects:**
- [x] 0x03 LayerSwitcher (x35) [19 subtypes]
- [ ] 0x14 Seesaw (x4) [0x00]
- [ ] 0x16 HTZLift (x10) [6 subtypes]
- [x] 0x18 ARZPlatform (x24) [6 subtypes]
- [x] 0x1C Scenery (x35) [4 subtypes]
- [x] 0x26 Monitor (x14) [4 subtypes]
- [ ] 0x2D Barrier (x10) [0x00]
- [ ] 0x2F SmashableGround (x30) [10 subtypes]
- [ ] 0x30 RisingLava (x4) [0x06, 0x08]
- [ ] 0x31 LavaMarker (x21) [0x00, 0x01, 0x02]
- [ ] 0x32 BreakableBlock (x14) [0x00]
- [x] 0x36 Spikes (x6) [0x00, 0x01]
- [ ] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x12) [6 subtypes]
- [ ] 0x74 InvisibleBlock (x8) [5 subtypes]
- [x] 0x79 Checkpoint (x4) [4 subtypes]
- [ ] 0x84 ForcedSpin (x4) [0x00]

### Mystic Cave Zone

#### Act 1

Total: 130 objects | Implemented: 6 | Unimplemented: 13

**Badniks:**
- [ ] 0x9E Crawlton (x6) [0x22]
- [ ] 0xA3 Flasher (x12) [0x2C]

**Objects:**
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x15 SwingingPlatform (x1) [0x48]
- [ ] 0x1F CollapsPform (x19) [0x00]
- [x] 0x26 Monitor (x12) [0x04, 0x06, 0x07]
- [ ] 0x2A Stomper (x8) [0x00]
- [x] 0x36 Spikes (x13) [5 subtypes]
- [x] 0x41 Spring (x11) [4 subtypes]
- [ ] 0x6A MCZRotPforms (x2) [0x18]
- [ ] 0x74 InvisibleBlock (x2) [0x30, 0xF0]
- [ ] 0x75 MCZBrick (x8) [0x16, 0x0F]
- [ ] 0x76 SlidingSpikes (x11) [0x00]
- [ ] 0x77 MCZBridge (x3) [0x01, 0x02]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03]
- [ ] 0x7A SidewaysPform (x3) [0x00, 0x12]
- [ ] 0x7F VineSwitch (x4) [4 subtypes]
- [ ] 0x80 MovingVine (x6) [4 subtypes]
- [ ] 0x81 MCZDrawbridge (x5) [5 subtypes]

#### Act 2

Total: 148 objects | Implemented: 6 | Unimplemented: 14

**Badniks:**
- [ ] 0x9E Crawlton (x6) [0x22]
- [ ] 0xA3 Flasher (x14) [0x2C]

**Objects:**
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x15 SwingingPlatform (x2) [0x18, 0x38]
- [ ] 0x1F CollapsPform (x16) [0x00]
- [x] 0x26 Monitor (x13) [5 subtypes]
- [ ] 0x2A Stomper (x5) [0x00]
- [x] 0x36 Spikes (x21) [6 subtypes]
- [ ] 0x3E EggPrison (x1) [0x00]
- [ ] 0x40 Springboard (x9) [0x01, 0x03]
- [x] 0x41 Spring (x12) [5 subtypes]
- [ ] 0x6A MCZRotPforms (x3) [0x18]
- [ ] 0x75 MCZBrick (x11) [0x16, 0x17, 0x0F]
- [ ] 0x76 SlidingSpikes (x3) [0x00]
- [ ] 0x77 MCZBridge (x2) [0x03, 0x04]
- [x] 0x79 Checkpoint (x4) [4 subtypes]
- [ ] 0x7A SidewaysPform (x3) [0x00, 0x12]
- [ ] 0x7F VineSwitch (x7) [7 subtypes]
- [ ] 0x80 MovingVine (x8) [4 subtypes]
- [ ] 0x81 MCZDrawbridge (x7) [7 subtypes]

### Oil Ocean Zone

#### Act 1

Total: 189 objects | Implemented: 7 | Unimplemented: 7

**Badniks:**
- [ ] 0x4A Octus (x14) [0x00]
- [ ] 0x50 Aquis (x8) [0x00]

**Objects:**
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x19 CPZPlatform (x13) [0x23]
- [x] 0x1C Scenery (x21) [7 subtypes]
- [ ] 0x1F CollapsPform (x17) [0x00]
- [x] 0x26 Monitor (x11) [4 subtypes]
- [ ] 0x33 OOZPoppingPform (x11) [0x00]
- [x] 0x36 Spikes (x40) [0x00, 0x10, 0x30]
- [ ] 0x3D OOZLauncher (x3) [0x01]
- [ ] 0x3F Fan (x30) [5 subtypes]
- [x] 0x41 Spring (x2) [0x02]
- [ ] 0x48 LauncherBall (x16) [6 subtypes]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02]

#### Act 2

Total: 189 objects | Implemented: 7 | Unimplemented: 7

**Badniks:**
- [ ] 0x4A Octus (x14) [0x00]
- [ ] 0x50 Aquis (x8) [0x00]

**Objects:**
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x19 CPZPlatform (x13) [0x23]
- [x] 0x1C Scenery (x21) [7 subtypes]
- [ ] 0x1F CollapsPform (x17) [0x00]
- [x] 0x26 Monitor (x11) [4 subtypes]
- [ ] 0x33 OOZPoppingPform (x11) [0x00]
- [x] 0x36 Spikes (x40) [0x00, 0x10, 0x30]
- [ ] 0x3D OOZLauncher (x3) [0x01]
- [ ] 0x3F Fan (x30) [5 subtypes]
- [x] 0x41 Spring (x2) [0x02]
- [ ] 0x48 LauncherBall (x16) [6 subtypes]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02]

### Metropolis Zone

#### Act 1

Total: 193 objects | Implemented: 7 | Unimplemented: 15

**Badniks:**
- [ ] 0x9F Shellcracker (x3) [0x24]
- [ ] 0xA1 Slicer (x4) [0x28]
- [ ] 0xA4 Asteron (x27) [0x2E]

**Objects:**
- [x] 0x06 Spiral (x5) [0x80]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x1C Scenery (x1) [0x03]
- [x] 0x26 Monitor (x10) [0x04, 0x06, 0x07]
- [ ] 0x2D Barrier (x3) [0x01]
- [x] 0x36 Spikes (x7) [0x10, 0x00, 0x30]
- [x] 0x41 Spring (x4) [0x20, 0x12, 0x02]
- [ ] 0x42 SteamSpring (x4) [0x01]
- [ ] 0x47 Button (x9) [5 subtypes]
- [ ] 0x64 MTZTwinStompers (x2) [0x01]
- [ ] 0x65 MTZLongPlatform (x32) [11 subtypes]
- [ ] 0x66 MTZSpringWall (x30) [0x01, 0x11]
- [ ] 0x67 MTZSpinTube (x3) [0x00, 0x01, 0x02]
- [ ] 0x68 SpikyBlock (x9) [4 subtypes]
- [ ] 0x69 Nut (x9) [7 subtypes]
- [ ] 0x6B MTZPlatform (x6) [0x02, 0x07]
- [ ] 0x6D FloorSpike (x8) [8 subtypes]
- [ ] 0x74 InvisibleBlock (x11) [0x71, 0x73, 0x13]
- [x] 0x79 Checkpoint (x5) [5 subtypes]

#### Act 2

Total: 220 objects | Implemented: 7 | Unimplemented: 20

**Badniks:**
- [ ] 0x9F Shellcracker (x3) [0x24]
- [ ] 0xA1 Slicer (x7) [0x28]
- [ ] 0xA4 Asteron (x21) [0x2E]

**Objects:**
- [x] 0x06 Spiral (x4) [0x80]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x1C Scenery (x12) [0x00, 0x01, 0x03]
- [x] 0x26 Monitor (x13) [4 subtypes]
- [ ] 0x2D Barrier (x3) [0x01]
- [ ] 0x31 LavaMarker (x12) [0x01, 0x02]
- [x] 0x36 Spikes (x11) [0x00, 0x40]
- [x] 0x41 Spring (x6) [0x00, 0x02]
- [ ] 0x42 SteamSpring (x5) [0x01]
- [ ] 0x47 Button (x4) [0x00, 0x01]
- [ ] 0x64 MTZTwinStompers (x1) [0x01]
- [ ] 0x65 MTZLongPlatform (x10) [0xB0, 0xB1, 0x13]
- [ ] 0x66 MTZSpringWall (x19) [0x01, 0x11]
- [ ] 0x67 MTZSpinTube (x4) [4 subtypes]
- [ ] 0x68 SpikyBlock (x6) [0x00, 0x01, 0x02]
- [ ] 0x69 Nut (x9) [6 subtypes]
- [ ] 0x6B MTZPlatform (x14) [4 subtypes]
- [ ] 0x6C Conveyor (x9) [0x80, 0x81, 0x82]
- [ ] 0x6D FloorSpike (x12) [12 subtypes]
- [ ] 0x70 Cog (x6) [0x00]
- [ ] 0x71 MTZLavaBubble (x11) [0x22]
- [ ] 0x72 CNZConveyorBelt (x2) [0x04, 0x09]
- [ ] 0x74 InvisibleBlock (x12) [0x71, 0x17]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03]

#### Act 3

Total: 270 objects | Implemented: 6 | Unimplemented: 23

**Badniks:**
- [ ] 0x9F Shellcracker (x3) [0x24]
- [ ] 0xA1 Slicer (x13) [0x28]
- [ ] 0xA4 Asteron (x42) [0x2E]

**Objects:**
- [x] 0x06 Spiral (x2) [0x80]
- [x] 0x1C Scenery (x1) [0x03]
- [x] 0x26 Monitor (x11) [4 subtypes]
- [ ] 0x2D Barrier (x4) [0x01]
- [ ] 0x31 LavaMarker (x8) [0x01, 0x02]
- [x] 0x36 Spikes (x14) [5 subtypes]
- [ ] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x9) [0x00, 0x10, 0x02]
- [ ] 0x42 SteamSpring (x9) [0x01]
- [ ] 0x47 Button (x1) [0x05]
- [ ] 0x64 MTZTwinStompers (x3) [0x11, 0x01]
- [ ] 0x65 MTZLongPlatform (x39) [4 subtypes]
- [ ] 0x66 MTZSpringWall (x11) [0x01, 0x11]
- [ ] 0x67 MTZSpinTube (x5) [5 subtypes]
- [ ] 0x68 SpikyBlock (x7) [0x00, 0x02, 0x03]
- [ ] 0x69 Nut (x10) [5 subtypes]
- [ ] 0x6A MCZRotPforms (x2) [0x00]
- [ ] 0x6B MTZPlatform (x8) [0x02, 0x04, 0x07]
- [ ] 0x6C Conveyor (x1) [0x81]
- [ ] 0x6D FloorSpike (x5) [5 subtypes]
- [ ] 0x6E LargeRotPform (x28) [4 subtypes]
- [ ] 0x70 Cog (x8) [0x00]
- [ ] 0x71 MTZLavaBubble (x4) [0x22]
- [ ] 0x72 CNZConveyorBelt (x4) [0x09]
- [ ] 0x74 InvisibleBlock (x11) [0x71]
- [x] 0x79 Checkpoint (x6) [6 subtypes]

### Sky Chase Zone

#### Act 1

Total: 60 objects | Implemented: 1 | Unimplemented: 7

**Badniks:**
- [ ] 0x99 Nebula (x23) [0x12]
- [ ] 0x9A Turtloid (x10) [0x16]
- [ ] 0xAC Balkiry (x19) [0x40]

**Objects:**
- [x] 0x03 LayerSwitcher (x1) [0x7B]
- [ ] 0xB2 Tornado (x1) [0x50]
- [ ] 0xB3 Cloud (x3) [0x60, 0x62, 0x5E]
- [ ] 0xB4 VPropeller (x1) [0x64]
- [ ] 0xB5 HPropeller (x2) [0x68]

### Wing Fortress Zone

#### Act 1

Total: 157 objects | Implemented: 3 | Unimplemented: 22

**Badniks:**
- [ ] 0xAD CluckerBase (x10) [0x42]
- [ ] 0xAE Clucker (x10) [0x44]

**Bosss:**
- [ ] 0xC5 WFZBoss (x1) [0x92]

**Objects:**
- [x] 0x19 CPZPlatform (x16) [10 subtypes]
- [x] 0x26 Monitor (x19) [5 subtypes]
- [ ] 0x2D Barrier (x1) [0x00]
- [ ] 0x72 CNZConveyorBelt (x1) [0x90]
- [ ] 0x74 InvisibleBlock (x5) [0x03, 0x07, 0x18]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03]
- [ ] 0x80 MovingVine (x10) [0x00, 0x10, 0x11]
- [ ] 0x8B WFZPalSwitcher (x11) [4 subtypes]
- [ ] 0xB2 Tornado (x2) [0x52, 0x54]
- [ ] 0xB4 VPropeller (x12) [0x64]
- [ ] 0xB5 HPropeller (x8) [0x66]
- [ ] 0xB6 TiltingPlatform (x5) [5 subtypes]
- [ ] 0xB8 WallTurret (x5) [0x74]
- [ ] 0xB9 Laser (x6) [0x76]
- [ ] 0xBA WFZWheel (x2) [0x78]
- [ ] 0xBC WFZShipFire (x2) [0x7C]
- [ ] 0xBD SmallMetalPform (x5) [0x80, 0x7E]
- [ ] 0xBE LateralCannon (x7) [7 subtypes]
- [ ] 0xC0 SpeedLauncher (x8) [5 subtypes]
- [ ] 0xC1 BreakablePlating (x4) [0x02]
- [ ] 0xC2 Rivet (x1) [0x8A]
- [ ] 0xD9 Grab (x3) [0x00]

### Death Egg Zone

#### Act 1

Total: 5 objects | Implemented: 0 | Unimplemented: 3

**Bosss:**
- [ ] 0xC6 Eggman (x1) [0xA6]
- [ ] 0xC7 Eggrobo (x1) [0x02]

**Objects:**
- [ ] 0x2D Barrier (x3) [0x00]


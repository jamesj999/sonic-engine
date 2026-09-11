# Sonic 2 Object Implementation Checklist

Generated: 2026-08-28 20:53:12

## Summary

- **Total unique objects found:** 122
- **Implemented:** 122 (100.0%)
- **Unimplemented:** 0 (0.0%)

## Implemented Objects

| ID | Name | Total Uses | PLC | Zones |
|----|------|------------|-----|-------|
| 0x03 | LayerSwitcher | 299 |  | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, SCZ1 |
| 0x06 | Spiral | 18 |  | EHZ1, EHZ2, MTZ1, MTZ2, MTZ3 |
| 0x0D | GoalPlate | 12 |  | EHZ1, EHZ2, CPZ1, ARZ1, CNZ1, CNZ2, HTZ1, MCZ1, MCZ2, OOZ1, MTZ1, MTZ2 |
| 0x11 | Bridge | 14 | 0x04 | EHZ1, EHZ2 |
| 0x18 | ARZPlatform | 71 |  | EHZ1, EHZ2, ARZ1, ARZ2, HTZ1, HTZ2 |
| 0x1C | Scenery | 131 |  | EHZ1, EHZ2, HTZ1, HTZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3 |
| 0x26 | Monitor | 250 |  | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3, WFZ1 |
| 0x36 | Spikes | 193 | 0x05, 0x0D, 0x12, 0x19, 0x1B, 0x1D, 0x1F, 0x23 | EHZ1, EHZ2, CPZ2, ARZ1, CNZ1, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3 |
| 0x41 | Spring | 143 | 0x05, 0x0D, 0x12, 0x19, 0x1B, 0x1D, 0x1F, 0x23 | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3 |
| 0x49 | EHZWaterfall | 18 |  | EHZ1, EHZ2 |
| 0x4B | Buzzer | 23 | 0x04 | EHZ1, EHZ2 |
| 0x5C | Masher | 21 | 0x04 | EHZ1, EHZ2 |
| 0x79 | Checkpoint | 62 | 0x01 | EHZ1, EHZ2, CPZ1, CPZ2, ARZ1, ARZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, MCZ2, OOZ1, OOZ2, MTZ1, MTZ2, MTZ3, WFZ1 |
| 0x9D | Coconuts | 17 | 0x04 | EHZ1, EHZ2 |
| 0x3E | EggPrison | 8 |  | EHZ2, CPZ2, ARZ2, CNZ2, HTZ2, MCZ2, OOZ2, MTZ3 |
| 0x0B | TippingFloor | 18 | 0x1E | CPZ1, CPZ2 |
| 0x19 | CPZPlatform | 55 |  | CPZ1, CPZ2, OOZ1, OOZ2, WFZ1 |
| 0x1B | SpeedBooster | 20 | 0x1E | CPZ1, CPZ2 |
| 0x1D | BlueBalls | 7 | 0x1F | CPZ1 |
| 0x1E | CPZSpinTube | 16 |  | CPZ1, CPZ2 |
| 0x2D | Barrier | 35 | 0x1E, 0x20 | CPZ1, CPZ2, HTZ1, HTZ2, MTZ1, MTZ2, MTZ3, WFZ1, DEZ1 |
| 0x32 | BreakableBlock | 28 |  | CPZ1, CPZ2, HTZ1, HTZ2 |
| 0x6B | MTZPlatform | 58 |  | CPZ1, CPZ2, MTZ1, MTZ2, MTZ3 |
| 0x74 | InvisibleBlock | 114 |  | CPZ1, CPZ2, CNZ1, CNZ2, HTZ1, HTZ2, MCZ1, OOZ2, MTZ1, MTZ2, MTZ3, WFZ1 |
| 0x78 | CPZStaircase | 6 | 0x1E | CPZ1, CPZ2 |
| 0x7B | PipeExitSpring | 9 | 0x1E | CPZ1, CPZ2 |
| 0xA5 | Spiny | 11 | 0x1F | CPZ1, CPZ2 |
| 0xA6 | SpinyOnWall | 2 |  | CPZ1, CPZ2 |
| 0xA7 | Grabber | 5 | 0x1F | CPZ1, CPZ2 |
| 0x40 | Springboard | 31 | 0x1B, 0x1F, 0x23 | CPZ2, ARZ1, ARZ2, MCZ2 |
| 0x7A | SidewaysPform | 9 |  | CPZ2, MCZ1, MCZ2 |
| 0x1F | CollapsPform | 77 | 0x19, 0x1A | ARZ1, ARZ2, MCZ1, MCZ2, OOZ1, OOZ2 |
| 0x22 | ArrowShooter | 24 | 0x22 | ARZ1, ARZ2 |
| 0x23 | FallingPillar | 16 |  | ARZ1, ARZ2 |
| 0x24 | Bubbles | 20 | 0x23 | ARZ1, ARZ2 |
| 0x2B | RisingPillar | 11 |  | ARZ1, ARZ2 |
| 0x2C | LeavesGenerator | 43 | 0x22 | ARZ1, ARZ2 |
| 0x82 | SwingingPform | 15 |  | ARZ1, ARZ2 |
| 0x83 | ARZRotPforms | 4 |  | ARZ1, ARZ2 |
| 0x8C | Whisp | 25 | 0x23 | ARZ1, ARZ2 |
| 0x8D | GrounderInWall | 8 | 0x23 | ARZ1, ARZ2 |
| 0x8E | GrounderInWall2 | 20 |  | ARZ1, ARZ2 |
| 0x91 | ChopChop | 38 | 0x23 | ARZ1, ARZ2 |
| 0x15 | SwingingPlatform | 8 |  | ARZ2, MCZ1, MCZ2, OOZ2 |
| 0x44 | Bumper | 117 | 0x1C | CNZ1, CNZ2 |
| 0x72 | CNZConveyorBelt | 15 |  | CNZ1, CNZ2, MTZ2, MTZ3, WFZ1 |
| 0x84 | ForcedSpin | 42 |  | CNZ1, CNZ2, HTZ1, HTZ2 |
| 0x85 | LauncherSpring | 14 | 0x1D | CNZ1, CNZ2 |
| 0x86 | Flipper | 63 | 0x1C | CNZ1, CNZ2 |
| 0xC8 | Crawl | 8 | 0x1C | CNZ1, CNZ2 |
| 0xD2 | CNZRectBlocks | 8 | 0x1C | CNZ1, CNZ2 |
| 0xD4 | CNZBigBlock | 23 | 0x1C | CNZ1, CNZ2 |
| 0xD5 | Elevator | 16 | 0x1C | CNZ1, CNZ2 |
| 0xD6 | PointPokey | 29 | 0x1C | CNZ1, CNZ2 |
| 0xD7 | HexBumper | 18 | 0x1C | CNZ1, CNZ2 |
| 0xD8 | BonusBlock | 98 | 0x1C | CNZ1, CNZ2 |
| 0x14 | Seesaw | 13 | 0x12 | HTZ1, HTZ2 |
| 0x16 | HTZLift | 14 | 0x13 | HTZ1, HTZ2 |
| 0x2F | SmashableGround | 40 |  | HTZ1, HTZ2 |
| 0x30 | RisingLava | 7 |  | HTZ1, HTZ2 |
| 0x31 | LavaMarker | 50 |  | HTZ1, HTZ2, MTZ2, MTZ3 |
| 0x92 | Spiker | 23 | 0x12 | HTZ1, HTZ2 |
| 0x95 | Sol | 3 | 0x12 | HTZ1, HTZ2 |
| 0x96 | Rexon2 | 6 |  | HTZ1, HTZ2 |
| 0x2A | Stomper | 13 |  | MCZ1, MCZ2 |
| 0x6A | MCZRotPforms | 7 | 0x1A | MCZ1, MCZ2, MTZ3 |
| 0x75 | MCZBrick | 19 |  | MCZ1, MCZ2 |
| 0x76 | SlidingSpikes | 14 |  | MCZ1, MCZ2 |
| 0x77 | MCZBridge | 5 |  | MCZ1, MCZ2 |
| 0x7F | VineSwitch | 11 | 0x1A | MCZ1, MCZ2 |
| 0x80 | MovingVine | 24 | 0x11, 0x1A | MCZ1, MCZ2, WFZ1 |
| 0x81 | MCZDrawbridge | 12 | 0x1B | MCZ1, MCZ2 |
| 0x9E | Crawlton | 12 | 0x1A | MCZ1, MCZ2 |
| 0xA3 | Flasher | 26 | 0x1A | MCZ1, MCZ2 |
| 0x33 | OOZPoppingPform | 19 | 0x18 | OOZ1, OOZ2 |
| 0x3D | OOZLauncher | 7 | 0x18, 0x19 | OOZ1, OOZ2 |
| 0x3F | Fan | 48 | 0x19 | OOZ1, OOZ2 |
| 0x48 | LauncherBall | 40 | 0x18 | OOZ1, OOZ2 |
| 0x4A | Octus | 30 | 0x19 | OOZ1, OOZ2 |
| 0x50 | Aquis | 20 | 0x19 | OOZ1, OOZ2 |
| 0x43 | SlidingSpike | 6 | 0x18 | OOZ2 |
| 0x45 | OOZSpring | 5 | 0x19 | OOZ2 |
| 0x42 | SteamSpring | 18 | 0x0C | MTZ1, MTZ2, MTZ3 |
| 0x47 | Button | 14 | 0x0D | MTZ1, MTZ2, MTZ3 |
| 0x64 | MTZTwinStompers | 6 |  | MTZ1, MTZ2, MTZ3 |
| 0x65 | MTZLongPlatform | 81 |  | MTZ1, MTZ2, MTZ3 |
| 0x66 | MTZSpringWall | 60 |  | MTZ1, MTZ2, MTZ3 |
| 0x67 | MTZSpinTube | 12 | 0x0D | MTZ1, MTZ2, MTZ3 |
| 0x68 | SpikyBlock | 22 | 0x0C | MTZ1, MTZ2, MTZ3 |
| 0x69 | Nut | 28 | 0x0D | MTZ1, MTZ2, MTZ3 |
| 0x6D | FloorSpike | 25 | 0x0C | MTZ1, MTZ2, MTZ3 |
| 0x9F | Shellcracker | 9 | 0x0C | MTZ1, MTZ2, MTZ3 |
| 0xA1 | Slicer | 24 | 0x0D | MTZ1, MTZ2, MTZ3 |
| 0xA4 | Asteron | 90 | 0x0C | MTZ1, MTZ2, MTZ3 |
| 0x6C | Conveyor | 10 |  | MTZ2, MTZ3 |
| 0x70 | Cog | 14 | 0x0D | MTZ2, MTZ3 |
| 0x71 | MTZLavaBubble | 15 | 0x0D | MTZ2, MTZ3 |
| 0x6E | LargeRotPform | 28 |  | MTZ3 |
| 0x99 | Nebula | 23 | 0x25 | SCZ1 |
| 0x9A | Turtloid | 10 | 0x25 | SCZ1 |
| 0xAC | Balkiry | 19 | 0x25 | SCZ1 |
| 0xB2 | Tornado | 3 | 0x10, 0x24 | SCZ1, WFZ1 |
| 0xB3 | Cloud | 3 | 0x25 | SCZ1 |
| 0xB4 | VPropeller | 13 | 0x10, 0x11, 0x25 | SCZ1, WFZ1 |
| 0xB5 | HPropeller | 10 | 0x10, 0x11, 0x25 | SCZ1, WFZ1 |
| 0x8B | WFZPalSwitcher | 11 |  | WFZ1 |
| 0xAD | CluckerBase | 10 | 0x10 | WFZ1 |
| 0xAE | Clucker | 10 | 0x10 | WFZ1 |
| 0xB6 | TiltingPlatform | 5 | 0x10 | WFZ1 |
| 0xB8 | WallTurret | 5 | 0x11 | WFZ1 |
| 0xB9 | Laser | 6 | 0x11 | WFZ1 |
| 0xBA | WFZWheel | 2 | 0x11 | WFZ1 |
| 0xBC | WFZShipFire | 2 |  | WFZ1 |
| 0xBD | SmallMetalPform | 5 | 0x11 | WFZ1 |
| 0xBE | LateralCannon | 7 |  | WFZ1 |
| 0xC0 | SpeedLauncher | 8 | 0x11 | WFZ1 |
| 0xC1 | BreakablePlating | 4 | 0x10 | WFZ1 |
| 0xC2 | Rivet | 1 | 0x11 | WFZ1 |
| 0xC5 | WFZBoss | 1 |  | WFZ1 |
| 0xD9 | Grab | 3 |  | WFZ1 |
| 0xC6 | Eggman | 1 |  | DEZ1 |
| 0xC7 | Eggrobo | 1 |  | DEZ1 |

## Unimplemented Objects (By Usage)

| ID | Category | Name | Total Uses | PLC | Zones |
|----|----------|------|------------|-----|-------|

---

## By Zone

### Emerald Hill Zone

#### Act 1

Total: 135 objects | Implemented: 14 | Unimplemented: 0

**Badniks:**
- [x] 0x4B Buzzer (x11) [0x00] PLC:0x04
- [x] 0x5C Masher (x13) [0x00] PLC:0x04
- [x] 0x9D Coconuts (x8) [0x1E] PLC:0x04

**Objects:**
- [x] 0x03 LayerSwitcher (x19) [5 subtypes]
- [x] 0x06 Spiral (x3) [0x00]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x11 Bridge (x7) [4 subtypes] PLC:0x04
- [x] 0x18 ARZPlatform (x7) [0x01, 0x02, 0x05]
- [x] 0x1C Scenery (x14) [0x02]
- [x] 0x26 Monitor (x13) [5 subtypes]
- [x] 0x36 Spikes (x12) [0x00, 0x10, 0x01] PLC:0x05
- [x] 0x41 Spring (x14) [7 subtypes] PLC:0x05
- [x] 0x49 EHZWaterfall (x10) [0x00, 0x02, 0x04]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03] PLC:0x01

#### Act 2

Total: 158 objects | Implemented: 15 | Unimplemented: 0

**Badniks:**
- [x] 0x4B Buzzer (x12) [0x00] PLC:0x04
- [x] 0x5C Masher (x8) [0x00] PLC:0x04
- [x] 0x9D Coconuts (x9) [0x1E] PLC:0x04

**Bosses:**
- [x] 0x56 EHZBoss *(dynamic)* - Drill car boss

**Objects:**
- [x] 0x03 LayerSwitcher (x34) [10 subtypes]
- [x] 0x06 Spiral (x4) [0x00]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x11 Bridge (x7) [0x0C] PLC:0x04
- [x] 0x18 ARZPlatform (x11) [0x01, 0x02, 0x03]
- [x] 0x1C Scenery (x14) [0x02]
- [x] 0x26 Monitor (x12) [5 subtypes]
- [x] 0x36 Spikes (x20) [0x00, 0x10, 0x01] PLC:0x05
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x12) [7 subtypes] PLC:0x05
- [x] 0x49 EHZWaterfall (x8) [4 subtypes]
- [x] 0x79 Checkpoint (x5) [5 subtypes] PLC:0x01

### Chemical Plant Zone

#### Act 1

Total: 153 objects | Implemented: 19 | Unimplemented: 0

**Badniks:**
- [x] 0xA5 Spiny (x10) [0x32] PLC:0x1F
- [x] 0xA6 SpinyOnWall (x1) [0x32]
- [x] 0xA7 Grabber (x2) [0x36] PLC:0x1F

**Objects:**
- [x] 0x03 LayerSwitcher (x60) [23 subtypes]
- [x] 0x0B TippingFloor (x4) [4 subtypes] PLC:0x1E
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x19 CPZPlatform (x7) [4 subtypes]
- [x] 0x1B SpeedBooster (x8) [0x00] PLC:0x1E
- [x] 0x1D BlueBalls (x7) [0x15, 0x05] PLC:0x1F
- [x] 0x1E CPZSpinTube (x9) [9 subtypes]
- [x] 0x26 Monitor (x19) [4 subtypes]
- [x] 0x2D Barrier (x2) [0x00] PLC:0x1E
- [x] 0x32 BreakableBlock (x4) [0x00]
- [x] 0x41 Spring (x5) [0x10, 0x02, 0x12] PLC:0x1F
- [x] 0x6B MTZPlatform (x2) [0x19]
- [x] 0x74 InvisibleBlock (x2) [0x17]
- [x] 0x78 CPZStaircase (x2) [0x00, 0x04] PLC:0x1E
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03] PLC:0x01
- [x] 0x7B PipeExitSpring (x5) [0x02] PLC:0x1E

#### Act 2

Total: 202 objects | Implemented: 21 | Unimplemented: 0

**Badniks:**
- [x] 0xA5 Spiny (x1) [0x32] PLC:0x1F
- [x] 0xA6 SpinyOnWall (x1) [0x32]
- [x] 0xA7 Grabber (x3) [0x36] PLC:0x1F

**Bosses:**
- [x] 0x5D CPZBoss *(dynamic)* - Water dropper boss

**Objects:**
- [x] 0x03 LayerSwitcher (x59) [21 subtypes]
- [x] 0x0B TippingFloor (x14) [6 subtypes] PLC:0x1E
- [x] 0x19 CPZPlatform (x6) [6 subtypes]
- [x] 0x1B SpeedBooster (x12) [0x00] PLC:0x1E
- [x] 0x1E CPZSpinTube (x7) [7 subtypes]
- [x] 0x26 Monitor (x19) [4 subtypes]
- [x] 0x2D Barrier (x4) [0x00] PLC:0x1E
- [x] 0x32 BreakableBlock (x3) [0x00]
- [x] 0x36 Spikes (x3) [0x30] PLC:0x1F
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x40 Springboard (x6) [0x03] PLC:0x1F
- [x] 0x41 Spring (x3) [0x10, 0x12] PLC:0x1F
- [x] 0x6B MTZPlatform (x28) [0x18, 0x19]
- [x] 0x74 InvisibleBlock (x16) [5 subtypes]
- [x] 0x78 CPZStaircase (x4) [0x00, 0x04] PLC:0x1E
- [x] 0x79 Checkpoint (x5) [5 subtypes] PLC:0x01
- [x] 0x7A SidewaysPform (x3) [0x00, 0x06, 0x0C]
- [x] 0x7B PipeExitSpring (x4) [0x02] PLC:0x1E

### Aquatic Ruin Zone

#### Act 1

Total: 182 objects | Implemented: 20 | Unimplemented: 0

**Badniks:**
- [x] 0x8C Whisp (x6) [0x00] PLC:0x23
- [x] 0x8D GrounderInWall (x4) [0x02] PLC:0x23
- [x] 0x8E GrounderInWall2 (x11) [0x02]
- [x] 0x91 ChopChop (x17) [0x08] PLC:0x23

**Objects:**
- [x] 0x03 LayerSwitcher (x30) [18 subtypes]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x18 ARZPlatform (x11) [5 subtypes]
- [x] 0x1F CollapsPform (x5) [0x00]
- [x] 0x22 ArrowShooter (x8) [0x00] PLC:0x22
- [x] 0x23 FallingPillar (x5) [0x00]
- [x] 0x24 Bubbles (x10) [0x80] PLC:0x23
- [x] 0x26 Monitor (x13) [5 subtypes]
- [x] 0x2B RisingPillar (x4) [0x00]
- [x] 0x2C LeavesGenerator (x34) [0x00, 0x01, 0x02] PLC:0x22
- [x] 0x36 Spikes (x1) [0x30] PLC:0x23
- [x] 0x40 Springboard (x6) [0x03] PLC:0x23
- [x] 0x41 Spring (x8) [0x10, 0x12] PLC:0x23
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03] PLC:0x01
- [x] 0x82 SwingingPform (x2) [0x10]
- [x] 0x83 ARZRotPforms (x3) [0x10]

#### Act 2

Total: 222 objects | Implemented: 20 | Unimplemented: 0

**Badniks:**
- [x] 0x8C Whisp (x19) [0x00] PLC:0x23
- [x] 0x8D GrounderInWall (x4) [0x02] PLC:0x23
- [x] 0x8E GrounderInWall2 (x9) [0x02]
- [x] 0x91 ChopChop (x21) [0x08] PLC:0x23

**Bosses:**
- [x] 0x89 ARZBoss *(dynamic)* - Hammer/arrow boss

**Objects:**
- [x] 0x03 LayerSwitcher (x28) [17 subtypes]
- [x] 0x15 SwingingPlatform (x4) [4 subtypes]
- [x] 0x18 ARZPlatform (x10) [4 subtypes]
- [x] 0x1F CollapsPform (x10) [0x00]
- [x] 0x22 ArrowShooter (x16) [0x00] PLC:0x22
- [x] 0x23 FallingPillar (x11) [0x00]
- [x] 0x24 Bubbles (x10) [0x81] PLC:0x23
- [x] 0x26 Monitor (x18) [4 subtypes]
- [x] 0x2B RisingPillar (x7) [0x00]
- [x] 0x2C LeavesGenerator (x9) [0x00, 0x01, 0x02] PLC:0x22
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x40 Springboard (x10) [0x03] PLC:0x23
- [x] 0x41 Spring (x17) [5 subtypes] PLC:0x23
- [x] 0x79 Checkpoint (x4) [4 subtypes] PLC:0x01
- [x] 0x82 SwingingPform (x13) [0x10, 0x11]
- [x] 0x83 ARZRotPforms (x1) [0x10]

### Casino Night Zone

#### Act 1

Total: 286 objects | Implemented: 19 | Unimplemented: 0

**Badniks:**
- [x] 0xC8 Crawl (x2) [0xAC] PLC:0x1C

**Objects:**
- [x] 0x03 LayerSwitcher (x6) [0xD0, 0x52]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x26 Monitor (x13) [5 subtypes]
- [x] 0x36 Spikes (x4) [0x20, 0x10] PLC:0x1D
- [x] 0x41 Spring (x5) [0x00, 0x02] PLC:0x1D
- [x] 0x44 Bumper (x74) [0x00] PLC:0x1C
- [x] 0x72 CNZConveyorBelt (x4) [0x04, 0x08, 0x09]
- [x] 0x74 InvisibleBlock (x17) [0x33, 0x35, 0x17]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02] PLC:0x01
- [x] 0x84 ForcedSpin (x18) [5 subtypes]
- [x] 0x85 LauncherSpring (x7) [0x00, 0x81] PLC:0x1D
- [x] 0x86 Flipper (x38) [0x00, 0x01] PLC:0x1C
- [x] 0xD2 CNZRectBlocks (x4) [0x01] PLC:0x1C
- [x] 0xD4 CNZBigBlock (x15) [0x00, 0x02] PLC:0x1C
- [x] 0xD5 Elevator (x5) [0x48, 0x28, 0x38] PLC:0x1C
- [x] 0xD6 PointPokey (x15) [0x00, 0x01] PLC:0x1C
- [x] 0xD7 HexBumper (x12) [0x00, 0x01] PLC:0x1C
- [x] 0xD8 BonusBlock (x44) [14 subtypes] PLC:0x1C

#### Act 2

Total: 254 objects | Implemented: 19 | Unimplemented: 0

**Badniks:**
- [x] 0xC8 Crawl (x6) [0xAC] PLC:0x1C

**Bosses:**
- [x] 0x51 CNZBoss *(dynamic)* - Catcher boss

**Objects:**
- [x] 0x03 LayerSwitcher (x9) [0x50, 0xD0, 0x52]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x26 Monitor (x16) [5 subtypes]
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x9) [4 subtypes] PLC:0x1D
- [x] 0x44 Bumper (x43) [0x00] PLC:0x1C
- [x] 0x72 CNZConveyorBelt (x4) [0x08, 0x09]
- [x] 0x74 InvisibleBlock (x18) [5 subtypes]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02] PLC:0x01
- [x] 0x84 ForcedSpin (x16) [5 subtypes]
- [x] 0x85 LauncherSpring (x7) [0x00, 0x81] PLC:0x1D
- [x] 0x86 Flipper (x25) [0x00, 0x01] PLC:0x1C
- [x] 0xD2 CNZRectBlocks (x4) [0x01] PLC:0x1C
- [x] 0xD4 CNZBigBlock (x8) [0x00, 0x02] PLC:0x1C
- [x] 0xD5 Elevator (x11) [7 subtypes] PLC:0x1C
- [x] 0xD6 PointPokey (x14) [0x00, 0x01] PLC:0x1C
- [x] 0xD7 HexBumper (x6) [0x00, 0x01] PLC:0x1C
- [x] 0xD8 BonusBlock (x54) [16 subtypes] PLC:0x1C

### Hill Top Zone

#### Act 1

Total: 144 objects | Implemented: 20 | Unimplemented: 0

**Badniks:**
- [x] 0x92 Spiker (x6) [0x0A] PLC:0x12
- [x] 0x95 Sol (x1) [0x00] PLC:0x12
- [x] 0x96 Rexon2 (x2) [0x0E]

**Objects:**
- [x] 0x03 LayerSwitcher (x18) [12 subtypes]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x14 Seesaw (x9) [0x00] PLC:0x12
- [x] 0x16 HTZLift (x4) [0x14, 0x1C] PLC:0x13
- [x] 0x18 ARZPlatform (x8) [4 subtypes]
- [x] 0x1C Scenery (x16) [4 subtypes]
- [x] 0x26 Monitor (x8) [0x04, 0x06, 0x07]
- [x] 0x2D Barrier (x5) [0x00]
- [x] 0x2F SmashableGround (x10) [5 subtypes]
- [x] 0x30 RisingLava (x3) [0x00, 0x02, 0x04]
- [x] 0x31 LavaMarker (x9) [0x01, 0x02]
- [x] 0x32 BreakableBlock (x7) [0x00]
- [x] 0x36 Spikes (x12) [0x00, 0x01] PLC:0x12
- [x] 0x41 Spring (x8) [5 subtypes] PLC:0x12
- [x] 0x74 InvisibleBlock (x11) [5 subtypes]
- [x] 0x79 Checkpoint (x2) [0x01, 0x02] PLC:0x01
- [x] 0x84 ForcedSpin (x4) [0x00]

#### Act 2

Total: 259 objects | Implemented: 20 | Unimplemented: 0

**Badniks:**
- [x] 0x92 Spiker (x17) [0x0A] PLC:0x12
- [x] 0x95 Sol (x2) [0x00] PLC:0x12
- [x] 0x96 Rexon2 (x4) [0x0E]

**Bosses:**
- [x] 0x52 HTZBoss *(dynamic)* - Lava-mobile boss

**Objects:**
- [x] 0x03 LayerSwitcher (x35) [19 subtypes]
- [x] 0x14 Seesaw (x4) [0x00] PLC:0x12
- [x] 0x16 HTZLift (x10) [6 subtypes] PLC:0x13
- [x] 0x18 ARZPlatform (x24) [6 subtypes]
- [x] 0x1C Scenery (x35) [4 subtypes]
- [x] 0x26 Monitor (x14) [4 subtypes]
- [x] 0x2D Barrier (x10) [0x00]
- [x] 0x2F SmashableGround (x30) [10 subtypes]
- [x] 0x30 RisingLava (x4) [0x06, 0x08]
- [x] 0x31 LavaMarker (x21) [0x00, 0x01, 0x02]
- [x] 0x32 BreakableBlock (x14) [0x00]
- [x] 0x36 Spikes (x6) [0x00, 0x01] PLC:0x12
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x12) [6 subtypes] PLC:0x12
- [x] 0x74 InvisibleBlock (x8) [5 subtypes]
- [x] 0x79 Checkpoint (x4) [4 subtypes] PLC:0x01
- [x] 0x84 ForcedSpin (x4) [0x00]

### Mystic Cave Zone

#### Act 1

Total: 130 objects | Implemented: 19 | Unimplemented: 0

**Badniks:**
- [x] 0x9E Crawlton (x6) [0x22] PLC:0x1A
- [x] 0xA3 Flasher (x12) [0x2C] PLC:0x1A

**Objects:**
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x15 SwingingPlatform (x1) [0x48]
- [x] 0x1F CollapsPform (x19) [0x00] PLC:0x1A
- [x] 0x26 Monitor (x12) [0x04, 0x06, 0x07]
- [x] 0x2A Stomper (x8) [0x00]
- [x] 0x36 Spikes (x13) [5 subtypes] PLC:0x1B
- [x] 0x41 Spring (x11) [4 subtypes] PLC:0x1B
- [x] 0x6A MCZRotPforms (x2) [0x18] PLC:0x1A
- [x] 0x74 InvisibleBlock (x2) [0x30, 0xF0]
- [x] 0x75 MCZBrick (x8) [0x16, 0x0F]
- [x] 0x76 SlidingSpikes (x11) [0x00]
- [x] 0x77 MCZBridge (x3) [0x01, 0x02]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03] PLC:0x01
- [x] 0x7A SidewaysPform (x3) [0x00, 0x12]
- [x] 0x7F VineSwitch (x4) [4 subtypes] PLC:0x1A
- [x] 0x80 MovingVine (x6) [4 subtypes] PLC:0x1A
- [x] 0x81 MCZDrawbridge (x5) [5 subtypes] PLC:0x1B

#### Act 2

Total: 148 objects | Implemented: 20 | Unimplemented: 0

**Badniks:**
- [x] 0x9E Crawlton (x6) [0x22] PLC:0x1A
- [x] 0xA3 Flasher (x14) [0x2C] PLC:0x1A

**Bosses:**
- [x] 0x57 MCZBoss *(dynamic)* - Drill boss

**Objects:**
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x15 SwingingPlatform (x2) [0x18, 0x38]
- [x] 0x1F CollapsPform (x16) [0x00] PLC:0x1A
- [x] 0x26 Monitor (x13) [5 subtypes]
- [x] 0x2A Stomper (x5) [0x00]
- [x] 0x36 Spikes (x21) [6 subtypes] PLC:0x1B
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x40 Springboard (x9) [0x01, 0x03] PLC:0x1B
- [x] 0x41 Spring (x12) [5 subtypes] PLC:0x1B
- [x] 0x6A MCZRotPforms (x3) [0x18] PLC:0x1A
- [x] 0x75 MCZBrick (x11) [0x16, 0x17, 0x0F]
- [x] 0x76 SlidingSpikes (x3) [0x00]
- [x] 0x77 MCZBridge (x2) [0x03, 0x04]
- [x] 0x79 Checkpoint (x4) [4 subtypes] PLC:0x01
- [x] 0x7A SidewaysPform (x3) [0x00, 0x12]
- [x] 0x7F VineSwitch (x7) [7 subtypes] PLC:0x1A
- [x] 0x80 MovingVine (x8) [4 subtypes] PLC:0x1A
- [x] 0x81 MCZDrawbridge (x7) [7 subtypes] PLC:0x1B

### Oil Ocean Zone

#### Act 1

Total: 189 objects | Implemented: 14 | Unimplemented: 0

**Badniks:**
- [x] 0x4A Octus (x14) [0x00] PLC:0x19
- [x] 0x50 Aquis (x8) [0x00] PLC:0x19

**Objects:**
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x19 CPZPlatform (x13) [0x23]
- [x] 0x1C Scenery (x21) [7 subtypes]
- [x] 0x1F CollapsPform (x17) [0x00] PLC:0x19
- [x] 0x26 Monitor (x11) [4 subtypes]
- [x] 0x33 OOZPoppingPform (x11) [0x00] PLC:0x18
- [x] 0x36 Spikes (x40) [0x00, 0x10, 0x30] PLC:0x19
- [x] 0x3D OOZLauncher (x3) [0x01] PLC:0x18,0x19
- [x] 0x3F Fan (x30) [5 subtypes] PLC:0x19
- [x] 0x41 Spring (x2) [0x02] PLC:0x19
- [x] 0x48 LauncherBall (x16) [6 subtypes] PLC:0x18
- [x] 0x79 Checkpoint (x2) [0x01, 0x02] PLC:0x01

#### Act 2

Total: 190 objects | Implemented: 18 | Unimplemented: 0

**Badniks:**
- [x] 0x4A Octus (x16) [0x00] PLC:0x19
- [x] 0x50 Aquis (x12) [0x00] PLC:0x19

**Bosses:**
- [ ] 0x55 OOZBoss *(dynamic)* - Laser/spike boss

**Objects:**
- [x] 0x15 SwingingPlatform (x1) [0x88]
- [x] 0x19 CPZPlatform (x13) [0x23]
- [x] 0x1C Scenery (x17) [6 subtypes]
- [x] 0x1F CollapsPform (x10) [0x00] PLC:0x19
- [x] 0x26 Monitor (x16) [5 subtypes]
- [x] 0x33 OOZPoppingPform (x8) [0x00, 0x01] PLC:0x18
- [x] 0x36 Spikes (x29) [0x00, 0x30] PLC:0x19
- [x] 0x3D OOZLauncher (x4) [0x01] PLC:0x18,0x19
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x3F Fan (x18) [4 subtypes] PLC:0x19
- [x] 0x41 Spring (x6) [4 subtypes] PLC:0x19
- [x] 0x43 SlidingSpike (x6) [0x00, 0x06, 0x0C] PLC:0x18
- [x] 0x45 OOZSpring (x5) [0x10, 0x30] PLC:0x19
- [x] 0x48 LauncherBall (x24) [6 subtypes] PLC:0x18
- [x] 0x74 InvisibleBlock (x1) [0x17]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03] PLC:0x01

### Metropolis Zone

#### Act 1

Total: 193 objects | Implemented: 22 | Unimplemented: 0

**Badniks:**
- [x] 0x9F Shellcracker (x3) [0x24] PLC:0x0C
- [x] 0xA1 Slicer (x4) [0x28] PLC:0x0D
- [x] 0xA4 Asteron (x27) [0x2E] PLC:0x0C

**Objects:**
- [x] 0x06 Spiral (x5) [0x80]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x1C Scenery (x1) [0x03]
- [x] 0x26 Monitor (x10) [0x04, 0x06, 0x07]
- [x] 0x2D Barrier (x3) [0x01]
- [x] 0x36 Spikes (x7) [0x10, 0x00, 0x30] PLC:0x0D
- [x] 0x41 Spring (x4) [0x20, 0x12, 0x02] PLC:0x0D
- [x] 0x42 SteamSpring (x4) [0x01] PLC:0x0C
- [x] 0x47 Button (x9) [5 subtypes] PLC:0x0D
- [x] 0x64 MTZTwinStompers (x2) [0x01]
- [x] 0x65 MTZLongPlatform (x32) [11 subtypes]
- [x] 0x66 MTZSpringWall (x30) [0x01, 0x11]
- [x] 0x67 MTZSpinTube (x3) [0x00, 0x01, 0x02] PLC:0x0D
- [x] 0x68 SpikyBlock (x9) [4 subtypes] PLC:0x0C
- [x] 0x69 Nut (x9) [7 subtypes] PLC:0x0D
- [x] 0x6B MTZPlatform (x6) [0x02, 0x07]
- [x] 0x6D FloorSpike (x8) [8 subtypes] PLC:0x0C
- [x] 0x74 InvisibleBlock (x11) [0x71, 0x73, 0x13]
- [x] 0x79 Checkpoint (x5) [5 subtypes] PLC:0x01

#### Act 2

Total: 220 objects | Implemented: 27 | Unimplemented: 0

**Badniks:**
- [x] 0x9F Shellcracker (x3) [0x24] PLC:0x0C
- [x] 0xA1 Slicer (x7) [0x28] PLC:0x0D
- [x] 0xA4 Asteron (x21) [0x2E] PLC:0x0C

**Objects:**
- [x] 0x06 Spiral (x4) [0x80]
- [x] 0x0D GoalPlate (x1) [0x00]
- [x] 0x1C Scenery (x12) [0x00, 0x01, 0x03]
- [x] 0x26 Monitor (x13) [4 subtypes]
- [x] 0x2D Barrier (x3) [0x01]
- [x] 0x31 LavaMarker (x12) [0x01, 0x02]
- [x] 0x36 Spikes (x11) [0x00, 0x40] PLC:0x0D
- [x] 0x41 Spring (x6) [0x00, 0x02] PLC:0x0D
- [x] 0x42 SteamSpring (x5) [0x01] PLC:0x0C
- [x] 0x47 Button (x4) [0x00, 0x01] PLC:0x0D
- [x] 0x64 MTZTwinStompers (x1) [0x01]
- [x] 0x65 MTZLongPlatform (x10) [0xB0, 0xB1, 0x13]
- [x] 0x66 MTZSpringWall (x19) [0x01, 0x11]
- [x] 0x67 MTZSpinTube (x4) [4 subtypes] PLC:0x0D
- [x] 0x68 SpikyBlock (x6) [0x00, 0x01, 0x02] PLC:0x0C
- [x] 0x69 Nut (x9) [6 subtypes] PLC:0x0D
- [x] 0x6B MTZPlatform (x14) [4 subtypes]
- [x] 0x6C Conveyor (x9) [0x80, 0x81, 0x82]
- [x] 0x6D FloorSpike (x12) [12 subtypes] PLC:0x0C
- [x] 0x70 Cog (x6) [0x00] PLC:0x0D
- [x] 0x71 MTZLavaBubble (x11) [0x22] PLC:0x0D
- [x] 0x72 CNZConveyorBelt (x2) [0x04, 0x09]
- [x] 0x74 InvisibleBlock (x12) [0x71, 0x17]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03] PLC:0x01

#### Act 3

Total: 270 objects | Implemented: 29 | Unimplemented: 0

**Badniks:**
- [x] 0x9F Shellcracker (x3) [0x24] PLC:0x0C
- [x] 0xA1 Slicer (x13) [0x28] PLC:0x0D
- [x] 0xA4 Asteron (x42) [0x2E] PLC:0x0C

**Bosses:**
- [x] 0x53 MTZBossOrb *(dynamic)* - Bouncing orb projectiles
- [x] 0x54 MTZBoss *(dynamic)* - Eggman's balloon machine

**Objects:**
- [x] 0x06 Spiral (x2) [0x80]
- [x] 0x1C Scenery (x1) [0x03]
- [x] 0x26 Monitor (x11) [4 subtypes]
- [x] 0x2D Barrier (x4) [0x01]
- [x] 0x31 LavaMarker (x8) [0x01, 0x02]
- [x] 0x36 Spikes (x14) [5 subtypes] PLC:0x0D
- [x] 0x3E EggPrison (x1) [0x00]
- [x] 0x41 Spring (x9) [0x00, 0x10, 0x02] PLC:0x0D
- [x] 0x42 SteamSpring (x9) [0x01] PLC:0x0C
- [x] 0x47 Button (x1) [0x05] PLC:0x0D
- [x] 0x64 MTZTwinStompers (x3) [0x11, 0x01]
- [x] 0x65 MTZLongPlatform (x39) [4 subtypes]
- [x] 0x66 MTZSpringWall (x11) [0x01, 0x11]
- [x] 0x67 MTZSpinTube (x5) [5 subtypes] PLC:0x0D
- [x] 0x68 SpikyBlock (x7) [0x00, 0x02, 0x03] PLC:0x0C
- [x] 0x69 Nut (x10) [5 subtypes] PLC:0x0D
- [x] 0x6A MCZRotPforms (x2) [0x00]
- [x] 0x6B MTZPlatform (x8) [0x02, 0x04, 0x07]
- [x] 0x6C Conveyor (x1) [0x81]
- [x] 0x6D FloorSpike (x5) [5 subtypes] PLC:0x0C
- [x] 0x6E LargeRotPform (x28) [4 subtypes]
- [x] 0x70 Cog (x8) [0x00] PLC:0x0D
- [x] 0x71 MTZLavaBubble (x4) [0x22] PLC:0x0D
- [x] 0x72 CNZConveyorBelt (x4) [0x09]
- [x] 0x74 InvisibleBlock (x11) [0x71]
- [x] 0x79 Checkpoint (x6) [6 subtypes] PLC:0x01

### Sky Chase Zone

#### Act 1

Total: 60 objects | Implemented: 8 | Unimplemented: 0

**Badniks:**
- [x] 0x99 Nebula (x23) [0x12] PLC:0x25
- [x] 0x9A Turtloid (x10) [0x16] PLC:0x25
- [x] 0xAC Balkiry (x19) [0x40] PLC:0x25

**Objects:**
- [x] 0x03 LayerSwitcher (x1) [0x7B]
- [x] 0xB2 Tornado (x1) [0x50] PLC:0x24
- [x] 0xB3 Cloud (x3) [0x60, 0x62, 0x5E] PLC:0x25
- [x] 0xB4 VPropeller (x1) [0x64] PLC:0x25
- [x] 0xB5 HPropeller (x2) [0x68] PLC:0x25

### Wing Fortress Zone

#### Act 1

Total: 157 objects | Implemented: 25 | Unimplemented: 0

**Badniks:**
- [x] 0xAD CluckerBase (x10) [0x42] PLC:0x10
- [x] 0xAE Clucker (x10) [0x44] PLC:0x10

**Bosses:**
- [x] 0xC5 WFZBoss (x1) [0x92]

**Objects:**
- [x] 0x19 CPZPlatform (x16) [10 subtypes]
- [x] 0x26 Monitor (x19) [5 subtypes]
- [x] 0x2D Barrier (x1) [0x00]
- [x] 0x72 CNZConveyorBelt (x1) [0x90]
- [x] 0x74 InvisibleBlock (x5) [0x03, 0x07, 0x18]
- [x] 0x79 Checkpoint (x3) [0x01, 0x02, 0x03] PLC:0x01
- [x] 0x80 MovingVine (x10) [0x00, 0x10, 0x11] PLC:0x11
- [x] 0x8B WFZPalSwitcher (x11) [4 subtypes]
- [x] 0xB2 Tornado (x2) [0x52, 0x54] PLC:0x10
- [x] 0xB4 VPropeller (x12) [0x64] PLC:0x10,0x11
- [x] 0xB5 HPropeller (x8) [0x66] PLC:0x10,0x11
- [x] 0xB6 TiltingPlatform (x5) [5 subtypes] PLC:0x10
- [x] 0xB8 WallTurret (x5) [0x74] PLC:0x11
- [x] 0xB9 Laser (x6) [0x76] PLC:0x11
- [x] 0xBA WFZWheel (x2) [0x78] PLC:0x11
- [x] 0xBC WFZShipFire (x2) [0x7C]
- [x] 0xBD SmallMetalPform (x5) [0x80, 0x7E] PLC:0x11
- [x] 0xBE LateralCannon (x7) [7 subtypes]
- [x] 0xC0 SpeedLauncher (x8) [5 subtypes] PLC:0x11
- [x] 0xC1 BreakablePlating (x4) [0x02] PLC:0x10
- [x] 0xC2 Rivet (x1) [0x8A] PLC:0x11
- [x] 0xD9 Grab (x3) [0x00]

### Death Egg Zone

#### Act 1

Total: 5 objects | Implemented: 3 | Unimplemented: 0

**Bosses:**
- [x] 0xC6 Eggman (x1) [0xA6]
- [x] 0xC7 Eggrobo (x1) [0x02]

**Objects:**
- [x] 0x2D Barrier (x3) [0x00] PLC:0x20


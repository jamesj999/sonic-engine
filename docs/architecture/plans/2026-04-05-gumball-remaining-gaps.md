# S3K Gumball Bonus Stage — Remaining Architectural Gaps

> Plan to cross-validate and implement remaining gaps identified by initial ROM validation.

## Phase 1: Cross-Validation

Dispatch 6 validation subagents (one per group) to independently verify:
1. The bug exists in current code
2. The ROM reference is correct
3. The proposed fix is minimal and ROM-accurate
4. There are no hidden dependencies with other systems

Each agent produces a **validated fix spec** with exact code to write.

### Group A: Machine Y Drift + Slot Tracking
**Scope:** sub_6126C, $FF2000 slot RAM, bumper bit 0 signaling, bumper slot clearing

**Validation targets:**
- ROM lines 127949-127977 (sub_6126C)
- ROM lines 127413-127419 (machine init slot clear)
- ROM lines 127692-127698 (bumper clears slot, signals machine)
- Verify $3A(a0)=initial_Y, $3C(a0)=target_Y semantics
- Verify 14-word slot count vs 36-byte initialization discrepancy

### Group B: Container/Dispenser Chain
**Scope:** Container state machine, dispenser deletion, 16 ejection effects, spring → dispenser signal

**Validation targets:**
- ROM lines 127555-127600 (container loc_60E4C/loc_60E5C/loc_60E8C/loc_60EA2)
- ROM lines 127502-127506 (dispenser deletion loc_60D96)
- ROM lines 127717-127729 (ejection effect loc_6101E)
- ROM byte_61342 ejection offsets
- ROM byte_6145B container animation (containers are parent of balls spawned)

### Group C: Spring Full Implementation
**Scope:** Spring art key, full sub_22F98, parent bit signal

**Validation targets:**
- ROM lines 127509-127552 (loc_60DAC, loc_60E36)
- ROM sub_22F98 implementation
- Spring art loading (Map_Spring from ArtNem_VerticalSpring at 0x35C988)
- ObjDat3_613C8 attributes (palette 0, frame 0)
- Parent bit signaling for dispenser chain

### Group D: Framework Refinements
**Scope:** BonusStageState, fade direction, HUD timer, event routines, entry state

**Validation targets:**
- ROM Save_Level_Data / Save_Level_Data2 (what gets saved)
- ROM Load_Starpost_Settings (what gets restored)
- ROM Pal_FadeToBlack vs Pal_FadeToWhite decision logic
- ROM clr.b (Update_HUD_timer) at line 8340
- Engine BonusStageState field coverage
- Engine GameLoop.enterBonusStage hardcoded fields
- Engine event manager routine restoration per-zone

### Group E: SK_alone_flag
**Scope:** Stage selection divisor 2 case

**Validation targets:**
- ROM lines 61886-61912 (loc_2D47E stage selection)
- How SK_alone_flag is set
- Divisor 2 branch (Pachinko vs Gumball for remainder 2)
- Engine Sonic3kBonusStageCoordinator hardcoding

### Group F: RNG Determinism
**Scope:** Machine RNG seed

**Validation targets:**
- ROM V_int_run_count usage
- Whether this matters for gameplay (RNG affects gumball subtype distribution only)
- Engine's deterministic RNG infrastructure (is there one?)

## Phase 2: Synthesis
Read all 6 validation outputs, identify conflicts/dependencies, produce an ordered implementation list.

## Phase 3: Implementation Delegation
Dispatch one implementation subagent per validated group. Each agent:
- Implements ONLY its group's fixes
- Tests compilation + existing tests
- Commits with descriptive message

## Constraints
- All fixes must preserve existing passing tests
- Each group must be independently committable
- No group should take longer than ~400K tokens to implement

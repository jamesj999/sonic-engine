# ROM Hack & Multi-Version Support Design

**Date:** 2026-02-13
**Status:** Approved

## Problem

The engine relies on hardcoded ROM addresses (`static final` fields in constants classes) tied to specific ROM revisions (REV01). This makes it impossible to load ROM hacks or alternative revisions where pointers are at different offsets.

ROM hacks commonly change: level order/naming, graphics, music/SFX, palettes. Some restructure data tables entirely. The engine needs to handle all of this without recompilation.

## Solution: Two-Pronged Address Resolution

### Prong 1: Runtime Address Resolver (primary)

A layered resolution system that replaces hardcoded constant imports:

**Resolution order:**
1. User override file (`<rom-filename>.profile.json` next to ROM)
2. Known ROM profile (matched by SHA-256 checksum, shipped in `profiles/` resources)
3. Runtime pattern scan (generalized from `Sonic3kRomScanner`)
4. Hardcoded defaults (current constants classes)

### Prong 2: ROM Profile Introspector (standalone tool)

A CLI tool that performs deep ROM analysis by tracing pointer chains (like the original 68000 code does) to *generate* a complete profile JSON. Runs once per ROM, outputs a reusable config file.

Both prongs share a common **ROM Profile** JSON format.

## ROM Profile Format

```json
{
  "profile": {
    "name": "Sonic 2 REV01",
    "game": "sonic2",
    "checksum": "sha256-hash",
    "generatedBy": "introspector|manual|shipped",
    "complete": true
  },
  "addresses": {
    "level": {
      "LEVEL_LAYOUT_INDEX_ADDR": { "value": "0x045A80", "confidence": "verified" }
    },
    "art": { },
    "audio": { },
    "collision": { },
    "palette": { },
    "animation": { },
    "physics": { },
    "objects": { }
  },
  "zones": {
    "0": { "name": "EHZ", "behaviorMapping": "EHZ" },
    "1": { "name": "CPZ", "behaviorMapping": "CPZ" },
    "15": { "name": "Custom Zone", "behaviorMapping": null }
  }
}
```

**Per-address confidence levels:** `verified` (known profile or user-confirmed), `traced` (introspector pointer chain), `scanned` (pattern match), `default` (hardcoded fallback).

**Zone mapping:** `behaviorMapping` links zone slots to known engine behaviors (scroll handler, event manager, object registry). `null` triggers generic fallback.

## Runtime Address Resolver

Singleton loaded at startup. Provides addresses through a unified API:

```java
RomAddressResolver resolver = RomAddressResolver.getInstance();
int addr = resolver.getLevelAddress("LEVEL_LAYOUT_INDEX_ADDR");
String behavior = resolver.getZoneBehavior(zoneId);
```

**Migration shim:** Constants classes become thin wrappers that delegate to the resolver. Existing `import static` call sites keep working. New code uses the resolver directly.

```java
// Sonic2Constants.java - migration shim
public static int LEVEL_LAYOUT_INDEX_ADDR = 0x045A80; // default

public static void initFromResolver(RomAddressResolver resolver) {
    LEVEL_LAYOUT_INDEX_ADDR = resolver.getLevelAddress(
        "LEVEL_LAYOUT_INDEX_ADDR", LEVEL_LAYOUT_INDEX_ADDR);
}
```

## Runtime Pattern Scanner

Generalized from `Sonic3kRomScanner`. Quick byte-pattern searches at startup for addresses missing from profiles.

```java
public record ScanPattern(
    byte[] signature,
    int pointerOffset,
    Validator validator
) {}
```

Each game module registers patterns. S3K patterns migrated from existing scanner. S1/S2 patterns derived from verified address documentation.

Only runs for missing addresses - if a profile covers everything, scanner never fires.

## ROM Profile Introspector Tool

Standalone CLI that traces ROM pointer chains to generate a complete profile:

```bash
mvn exec:java -Dexec.mainClass="uk.co.jamesj999.sonic.tools.RomProfileIntrospector" \
  -Dexec.args="--rom 'My Hack.gen' --output 'My Hack.profile.json'" -q
```

**Tracing strategy per chain:**
1. **Level data:** Level header table -> per-zone art/layout/collision/object pointers
2. **Audio:** Z80 driver load routine -> music/SFX pointer tables -> song headers
3. **Palettes:** Palette loading routine -> palette index table -> palette data
4. **Objects:** Object placement index -> per-act object lists
5. **Physics:** Player initialization -> physics constant tables

Each hop validates: pointer within ROM bounds, data at destination looks correct (compression headers, expected sizes), decompression succeeds if compressed.

Builds on existing `RomOffsetFinder` and `Sonic3kRomScanner` infrastructure.

## Zone Behavior Mapping

Handles ROM hacks that reorder zones. Profile maps zone slot IDs to known behavior sets:

- `behaviorMapping` selects scroll handler, event manager, object registry
- `null` mapping triggers generic fallback: basic parallax, no events, universal objects only
- Introspector can auto-guess zone identity by comparing art/music pointers to known addresses

No new abstraction needed - `ZoneRegistry`, `ScrollHandlerProvider`, `ZoneFeatureProvider` already exist. This adds an indirection layer between zone slot ID and behavior selection.

## Error Handling & Degradation

Subsystems disabled individually when their addresses can't be resolved:

| Subsystem | Required Categories | Degrades To |
|-----------|-------------------|-------------|
| Level rendering | level, art, collision | Fatal - can't load zone |
| Parallax scrolling | level (scroll tables) | Flat single-layer scroll |
| Audio | audio | Silent |
| Palette cycling | palette, animation | Static palettes |
| Object spawning | objects | Universal objects only |
| HUD / title cards | art (HUD patterns) | No HUD display |

Startup summary logged:
```
ROM Profile: 340/350 addresses resolved (profile: 320, scanned: 16, default: 4)
  Missing: audio.SFX_PTR_TABLE_ADDR (10 addresses)
  Disabled: SFX playback
  Tip: Run RomProfileIntrospector to generate a complete profile
```

## Implementation Phases

### Phase 1: Foundation (non-breaking)
- `RomAddressResolver` singleton + JSON profile loader
- Ship profiles for three known ROMs (generated from current constants)
- Constants classes get `initFromResolver()` shim - all existing code unchanged

### Phase 2: Pattern Scanner Framework
- Generalize `Sonic3kRomScanner` into `RomPatternScanner`
- Migrate S3K patterns, add S1/S2 patterns from verified docs
- Wire into resolver as fallback layer

### Phase 3: ROM Profile Introspector Tool
- CLI tool using existing `RomOffsetFinder` primitives
- Level data chain first (highest value), then audio, palette, objects incrementally

### Phase 4: Zone Behavior Mapping
- `zones` section in profiles
- Wire zone registries through indirection layer
- Generic fallback behaviors

### Phase 5: Gradual Call-Site Migration
- Replace `import static` with resolver calls opportunistically
- No deadline - shim means both patterns coexist

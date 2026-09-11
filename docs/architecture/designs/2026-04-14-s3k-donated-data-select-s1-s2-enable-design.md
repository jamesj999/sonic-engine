# S3K-Donated Data Select Enablement For S1 And S2

## Goal

Enable Sonic 1 and Sonic 2 to use the native S3K Data Select screen when cross-game donation resolves to `s3k`, so that:

- `1 PLAYER` from the title screen enters Data Select instead of starting gameplay directly
- the presentation is the real S3K Data Select screen and assets
- slot metadata, restart rules, and save writes remain owned by the host game (`s1` or `s2`)
- Level Select still takes precedence and behaves like `No Save`

This is a routing and provider-resolution feature first. It should not create separate S1/S2 menu frontends.

## Non-Goals

- creating new non-S3K Data Select presentations
- preserving `S1DataSelectManager` or `S2DataSelectManager` as production paths
- widening save behavior beyond the existing active-slot / no-save session model
- redesigning S3K Data Select visuals

## Product Rules

### Startup And Title Routing

Only the `1 PLAYER` title-screen path may route into Data Select.

For `S1` and `S2`, Data Select is available only when all of the following are true:

- title screen is enabled
- level select is disabled
- cross-game features are enabled
- the resolved donor game is `s3k`

If those conditions are not met, `S1` and `S2` follow their existing startup flow and do not enter Data Select.

`2 PLAYER`, `OPTIONS`, and all non-`1 PLAYER` title branches remain unchanged.

### Presentation Ownership

`S3kDataSelectManager` is the only production Data Select presentation manager.

When `S1` or `S2` use donated Data Select:

- presentation manager = `S3kDataSelectManager`
- host profile = `S1DataSelectHostProfile` or `S2DataSelectHostProfile`

When native `S3K` uses Data Select:

- presentation manager = `S3kDataSelectManager`
- host profile = `S3kDataSelectHostProfile`

`S1DataSelectManager` and `S2DataSelectManager` are removed if they are not required by any remaining production path.

### Host Metadata Rendering

The donated screen keeps S3K interaction and structure:

- cursor, slot layout, delete flow, clear-slot arrows, and transitions remain S3K
- host games only supply slot metadata and preview content

For occupied saves:

- unselected slots show static
- the selected slot shows the host-provided preview, exactly the way S3K swaps static for zone art
- the emerald ring remains on the native S3K save-card layout, with host emerald identity and host-adapted colors

Host preview rules:

- `S2`: selected-slot preview uses the scaled S2 level-select art for the current or selected restart destination
- `S1`: selected-slot preview is text-only for now, using the zone name
- `S1` and `S2`: emerald colors are adapted into the S3K save-card palette contract rather than copied slot-for-slot from raw host palette lines

### Save And Session Ownership

The existing save/session model remains shared:

- active slot writes go only to the active slot
- `No Save` remains a no-op session
- save files remain per host game under their current game-specific save directories

The donated screen does not change save ownership:

- `S1` Data Select writes `s1` saves
- `S2` Data Select writes `s2` saves

## Architecture

### Provider Resolution

`GameLoop` and startup routing already reason in terms of title actions and Data Select eligibility. This work adds donor-aware provider resolution.

Each host module exposes:

- a `DataSelectHostProfile`
- a `DataSelectPresentationProvider`

The resolved production provider must become donor-aware:

- native `S3K` returns S3K presentation directly
- `S1` and `S2` return S3K presentation only when donor=`s3k`
- otherwise `S1` and `S2` behave as if no Data Select provider is available

This keeps title routing simple: the route asks whether the resolved presentation is S3K-capable, rather than assuming each host owns its own Data Select frontend.

### Host Profiles

`S1` and `S2` host profiles remain the source of:

- slot summary extraction
- payload validation
- current-zone label formatting
- clear-slot restricted restart lists
- preview metadata for the selected slot

No S1/S2 presentation logic should survive outside those host-profile concerns.

### Save Providers And Trigger Wiring

`S1` and `S2` must keep the same session/save architecture already used by S3K:

- `SaveSessionContext`
- per-game payload validation
- per-game snapshot providers

This pass should make both games functionally correct on the donated screen:

- `NEW` slot start
- existing slot load
- clear restart
- delete
- no-save

Write triggers should use host-game progression / special-stage / completion hooks rather than generic transition writes.

Exact original save-call parity can be tightened per host game in later passes if any remaining gaps are found, but the donated screen must already produce correct save ownership and correct save summaries.

## File Changes

### Expected Production Changes

- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/game/startup/StartupRouteResolver.java`
- `src/main/java/com/openggf/game/CrossGameFeatureProvider.java` or a nearby donor-resolution helper
- `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- `src/main/java/com/openggf/game/sonic1/dataselect/*HostProfile*`
- `src/main/java/com/openggf/game/sonic2/dataselect/*HostProfile*`
- S1 and S2 save snapshot providers and host-game save call sites as needed

### Expected Deletions

- `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectManager.java`
- `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectManager.java`

If either file is still referenced by production code after the provider swap, that is a design failure and should be corrected rather than worked around.

## Testing

### Routing Tests

Add or update tests for:

- `S1` + donor=`s3k` + level select off + `1 PLAYER` -> `DATA_SELECT`
- `S2` + donor=`s3k` + level select off + `1 PLAYER` -> `DATA_SELECT`
- `S1` / `S2` without donor=`s3k` -> normal gameplay route
- `2 PLAYER` and `OPTIONS` remain unchanged

### Provider Tests

Verify:

- native `S3K` resolves to `S3kDataSelectManager`
- donated `S1`/`S2` also resolve to `S3kDataSelectManager`
- donated `S1`/`S2` still keep their own host profiles

### Rendering Tests

Verify:

- `S2` selected occupied save swaps static for the scaled host preview art
- `S1` selected occupied save swaps static for text-only host preview content
- unselected occupied saves remain on static in both games

### Save Tests

Verify:

- `S1` and `S2` donated Data Select runs write only to their own host-game save roots
- `No Save` remains no-op
- clear restart uses host-game restart rules and preserves host-game summary data

## Risks

The main risk is half-implementing donation by allowing routing into Data Select while still instantiating host-local S1/S2 menu managers. That would preserve the wrong architecture and make later parity work harder.

This pass avoids that by enforcing a single production presentation manager: `S3kDataSelectManager`.

## Success Criteria

The feature is complete when all of the following are true:

- `S1` and `S2` enter Data Select from `1 PLAYER` only when donor=`s3k`
- the visible screen is the native S3K Data Select presentation
- S1/S2 slot summaries and clear restart rules come from the host game
- S2 selected saves show host preview art; S1 selected saves show host text preview
- save operations are host-owned and slot-correct
- `S1DataSelectManager` and `S2DataSelectManager` are removed from production code

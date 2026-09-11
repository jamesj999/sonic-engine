# S3K Donated Data Select S1/S2 Enablement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Sonic 1 and Sonic 2 to enter the native S3K Data Select screen when cross-game donation resolves to `s3k`, using host-game save metadata and save writes.

**Architecture:** Keep one production Data Select presentation manager: `S3kDataSelectManager`. Route `S1` and `S2` into that donated presentation only when donor resolution says `s3k`, while keeping host-specific slot metadata, previews, and save writes in `S1`/`S2` host profiles and save providers.

**Tech Stack:** Java 21, JUnit 5, Maven, existing `GameModule`/`CrossGameFeatureProvider`/`DataSelectPresentationProvider` architecture

---

### Task 1: Add failing routing and provider-resolution tests for donated S3K Data Select

**Files:**
- Modify: `src/test/java/com/openggf/game/startup/TestStartupRouteResolver.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Modify: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java`

- [ ] **Step 1: Write the failing routing tests**

Add tests that express the intended routing and provider swap.

```java
@Test
void s1OnePlayerRoutesToDataSelectWhenDonorResolvesToS3k() {
    TitleActionRoute route = resolver.resolveTitleAction(
            new Sonic1GameModule(),
            new DataSelectPresentationResolution(true, GameId.S3K),
            true,
            false,
            TitleScreenAction.ONE_PLAYER);

    assertEquals(TitleActionRoute.DATA_SELECT, route);
}

@Test
void s2OnePlayerRoutesToDataSelectWhenDonorResolvesToS3k() {
    TitleActionRoute route = resolver.resolveTitleAction(
            new Sonic2GameModule(),
            new DataSelectPresentationResolution(true, GameId.S3K),
            true,
            false,
            TitleScreenAction.ONE_PLAYER);

    assertEquals(TitleActionRoute.DATA_SELECT, route);
}

@Test
void s1AndS2DoNotRouteToDataSelectWithoutS3kPresentation() {
    assertEquals(TitleActionRoute.LEVEL,
            resolver.resolveTitleAction(
                    new Sonic1GameModule(),
                    new DataSelectPresentationResolution(false, GameId.S1),
                    true,
                    false,
                    TitleScreenAction.ONE_PLAYER));
    assertEquals(TitleActionRoute.LEVEL,
            resolver.resolveTitleAction(
                    new Sonic2GameModule(),
                    new DataSelectPresentationResolution(false, GameId.S2),
                    true,
                    false,
                    TitleScreenAction.ONE_PLAYER));
}
```

- [ ] **Step 2: Write the failing provider tests**

Add tests that assert donated `S1`/`S2` resolve to S3K presentation rather than their own managers.

```java
@Test
void s1DonatedProviderUsesS3kPresentationManager() {
    Sonic1GameModule module = new Sonic1GameModule();
    DataSelectPresentationProvider provider = module.getDataSelectPresentationProvider();

    assertInstanceOf(S3kDataSelectManager.class, provider.delegate(),
            "S1 donated Data Select should use the S3K presentation manager");
}

@Test
void s2DonatedProviderUsesS3kPresentationManager() {
    Sonic2GameModule module = new Sonic2GameModule();
    DataSelectPresentationProvider provider = module.getDataSelectPresentationProvider();

    assertInstanceOf(S3kDataSelectManager.class, provider.delegate(),
            "S2 donated Data Select should use the S3K presentation manager");
}
```

- [ ] **Step 3: Run the targeted tests to verify they fail**

Run:

```bash
mvn -f .worktrees/s3k-data-select-save/pom.xml -Dmse=off "-Dtest=TestStartupRouteResolver,TestS1DataSelectProfile,TestS2DataSelectProfile,TestGameLoop" test
```

Expected:

- routing tests pass or partially pass if the resolver already behaves correctly
- provider tests fail because `S1DataSelectManager` / `S2DataSelectManager` are still returned today

- [ ] **Step 4: Commit the failing-test checkpoint**

```bash
git -C .worktrees/s3k-data-select-save add src/test/java/com/openggf/game/startup/TestStartupRouteResolver.java src/test/java/com/openggf/TestGameLoop.java src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java
git -C .worktrees/s3k-data-select-save commit -m "Add donated data select routing tests"
```

### Task 2: Implement donor-aware provider resolution and remove S1/S2 presentation managers

**Files:**
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Delete: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectManager.java`
- Delete: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectManager.java`

- [ ] **Step 1: Add a narrow donor-resolution helper**

Expose a single helper that answers whether active donation resolves to S3K.

```java
public boolean isS3kDonorActive() {
    return isActive() && donorGameId == GameId.S3K;
}
```

If a static/helper variant is cleaner near module bootstrap, use that instead, but keep the predicate explicit and single-purpose.

- [ ] **Step 2: Change S1 and S2 to return S3K presentation only when donor=`s3k`**

Shape the module methods like this:

```java
@Override
public DataSelectPresentationProvider getDataSelectPresentationProvider() {
    if (!crossGameFeatureProvider().isS3kDonorActive()) {
        return null;
    }
    if (dataSelectPresentationProvider == null) {
        dataSelectPresentationProvider = new DataSelectPresentationProvider(
                S3kDataSelectManager::new,
                new DataSelectSessionController(dataSelectHostProfile));
    }
    return dataSelectPresentationProvider;
}
```

Do not keep `S1DataSelectManager::new` or `S2DataSelectManager::new` on the production path.

- [ ] **Step 3: Make `getDataSelectProvider()` respect the nullable donated provider**

Use the presentation provider result directly:

```java
@Override
public DataSelectProvider getDataSelectProvider() {
    return getDataSelectPresentationProvider();
}
```

and ensure callers tolerate `null` as “no Data Select available”.

- [ ] **Step 4: Delete the obsolete S1/S2 presentation managers**

Remove:

```text
src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectManager.java
src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectManager.java
```

If either file is still referenced after the provider swap, update the references instead of backing off the deletion.

- [ ] **Step 5: Run targeted tests to verify provider swap passes**

Run:

```bash
mvn -f .worktrees/s3k-data-select-save/pom.xml -Dmse=off "-Dtest=TestStartupRouteResolver,TestS1DataSelectProfile,TestS2DataSelectProfile,TestGameLoop" test
```

Expected:

- the donated routing/provider tests pass
- no remaining production references to `S1DataSelectManager` / `S2DataSelectManager`

- [ ] **Step 6: Commit**

```bash
git -C .worktrees/s3k-data-select-save add src/main/java/com/openggf/game/CrossGameFeatureProvider.java src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java
git -C .worktrees/s3k-data-select-save rm src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectManager.java src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectManager.java
git -C .worktrees/s3k-data-select-save commit -m "Route donated data select through S3K presentation"
```

### Task 3: Enable S1/S2 host preview rendering on the donated S3K screen

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] **Step 1: Write failing rendering tests for host previews**

Add tests that assert the donated S3K presentation uses host preview data only for the selected slot.

```java
@Test
void s2SelectedSlotUsesHostPreviewImageInsteadOfStatic() {
    // Build S3K presentation with S2 host profile and an occupied selected slot.
    // Assert the selected slot render model advertises host preview art rather than static.
}

@Test
void s1SelectedSlotUsesHostTextPreviewInsteadOfStatic() {
    // Build S3K presentation with S1 host profile and an occupied selected slot.
    // Assert the selected slot render model advertises host text preview content.
}

@Test
void unselectedHostSlotsRemainOnStatic() {
    // Assert occupied but unselected slots still stay on static.
}
```

- [ ] **Step 2: Extend S1 host profile to provide selected-slot text preview**

Return explicit preview metadata for the donated presentation:

```java
return new HostSlotPreview(
        HostSlotPreviewType.TEXT_ONLY,
        zoneName,
        null);
```

Use the zone label already derived by the host profile. Do not invent image assets for S1.

- [ ] **Step 3: Extend S2 host profile to provide selected-slot image preview metadata**

Return preview metadata that points at the scaled S2 level-select image for the current zone or clear-restart destination.

```java
return new HostSlotPreview(
        HostSlotPreviewType.IMAGE,
        zoneName,
        scaledPreviewImage);
```

Keep unselected saves on static by leaving the S3K presentation responsible for swapping previews only on the selected slot.

- [ ] **Step 4: Teach S3K presentation/renderer to consume host preview metadata**

In the selected occupied-slot path:

- if host preview type is `IMAGE`, render the host image above static
- if host preview type is `TEXT_ONLY`, suppress static and render host text treatment
- otherwise use native S3K zone-art/static behavior

Keep unselected occupied slots on static.

- [ ] **Step 5: Run the preview tests**

Run:

```bash
mvn -f .worktrees/s3k-data-select-save/pom.xml -Dmse=off "-Dtest=TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectPresentation" test
```

Expected:

- S2 selected-slot image preview passes
- S1 selected-slot text-only preview passes
- unselected slots remain static

- [ ] **Step 6: Commit**

```bash
git -C .worktrees/s3k-data-select-save add src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectProfile.java src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectProfile.java src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
git -C .worktrees/s3k-data-select-save commit -m "Render S1 and S2 host previews on donated S3K data select"
```

### Task 4: Enable host-game save creation, loading, and clear-restart on the donated path

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic1/dataselect/S1SaveSnapshotProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/dataselect/S2SaveSnapshotProvider.java`
- Modify: host-game save trigger files discovered during implementation
- Modify: `src/test/java/com/openggf/TestEngine.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Modify: host-game save provider tests

- [ ] **Step 1: Write failing save/session tests for donated S1/S2**

Add tests that prove:

- `NEW` slot start in donated `S1` writes to `saves/s1/...`
- `NEW` slot start in donated `S2` writes to `saves/s2/...`
- `LOAD_SLOT` and `CLEAR_RESTART` preserve host-game payload data
- `NO_SAVE` remains no-op

Example shape:

```java
@Test
void donatedS1NewSlotWritesIntoS1SaveRoot() {
    // Create S1 session via donated S3K data select action.
    // Launch gameplay and assert slot file appears under saves/s1.
}

@Test
void donatedS2NoSaveDoesNotWriteAnySlotFile() {
    // Launch no-save action and assert no slot file is created.
}
```

- [ ] **Step 2: Ensure S1 and S2 modules expose working save snapshot providers**

Confirm the modules provide their snapshot providers on the donated route and that the providers serialize the fields needed for:

- slot summary
- lives
- emerald state
- clear state
- restart destination display

Keep the existing `version: 1` payload format and host-game validation rules.

- [ ] **Step 3: Wire or tighten host-game save trigger surfaces**

Use the host game’s real save surfaces rather than broad transition saves.

At minimum ensure:

- progression writes occur at real host progression points
- special stage completion/return persists host emerald state
- clear/completion state persists when the host game is completed

Do not add generic save spam merely to make the donated screen appear functional.

- [ ] **Step 4: Run the save/session tests**

Run:

```bash
mvn -f .worktrees/s3k-data-select-save/pom.xml -Dmse=off "-Dtest=TestEngine,TestGameLoop,TestSaveManager,TestS1DataSelectProfile,TestS2DataSelectProfile" test
```

Expected:

- S1/S2 donated runs write only to their own game save roots
- `NO_SAVE` stays no-op
- load and clear-restart keep host summaries intact

- [ ] **Step 5: Commit**

```bash
git -C .worktrees/s3k-data-select-save add src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java src/main/java/com/openggf/game/sonic1/dataselect/S1SaveSnapshotProvider.java src/main/java/com/openggf/game/sonic2/dataselect/S2SaveSnapshotProvider.java src/test/java/com/openggf/TestEngine.java src/test/java/com/openggf/TestGameLoop.java
git -C .worktrees/s3k-data-select-save commit -m "Enable S1 and S2 saves on donated S3K data select"
```

### Task 5: Run final verification and update the branch

**Files:**
- Modify: only if verification exposes gaps

- [ ] **Step 1: Run the donated Data Select regression slice**

Run:

```bash
mvn -f .worktrees/s3k-data-select-save/pom.xml -Dmse=off "-Dtest=TestStartupRouteResolver,TestGameLoop,TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectPresentation,TestEngine,TestSaveManager" test
```

Expected:

- all donated Data Select routing, preview, and save tests pass

- [ ] **Step 2: Run the full worktree suite**

Run:

```bash
mvn -f .worktrees/s3k-data-select-save/pom.xml -Dmse=off test
```

Expected:

- full suite passes, or any remaining unrelated flakes are identified explicitly before merge

- [ ] **Step 3: Push the branch updates**

```bash
git -C .worktrees/s3k-data-select-save push origin feature/ai-s3k-data-select-save
```

- [ ] **Step 4: Summarize remaining parity gaps, if any**

If anything remains out of scope, document it explicitly in the PR discussion or follow-up notes:

- exact S1/S2 save-call parity gaps
- host preview polish gaps
- any residual donated routing edge cases

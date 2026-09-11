# 08 — Clear palette presentation ownership on teardown

Medium conditional rendering issue; medium complexity; estimated 2–4 hours.

## Problem

clearPaletteTextures deletes native resources but retains the active palette fade and cached PaletteView owners. Early visual-trace exit just after S1 title-card release can carry this fade into the next session.

## Owners and evidence

Owners: `graphics/GraphicsManager.java`, `PaletteFadePresentation.java`, teardown in `Engine.resetForGameplayFromMasterTitle`, `GameLoop.resetModuleScopedProviders`, and S1 title-card lifecycle tests.

## Implementation steps

1. Add behavioral regression for active fade + cached palette owners followed by teardown; verify new session palette state is neutral and old owners are dropped.
2. Reset Java palette presentation/cache state as part of complete palette teardown. Avoid clearing via a helper that reuploads or recreates textures while disposing them.
3. Add a production session-boundary test at interrupted S1 title-card release where practical; retain a meaningful pure/headless proof when no GL context is available.
4. Preserve normal title-card fade timing and ordinary palette uploads, underwater state and texture ownership. Check cleanup/reset variants and idempotency.
5. Do not broaden this into unrelated renderer refactoring. State any unperformed live visual validation.

## Acceptance

Complete palette teardown leaves no active fade or references to previous palette owners; later uploads cannot inherit the old fade. Relevant S1 title-card and graphics tests pass.

Follow [shared constraints and delivery](README.md). Update the existing matching `CHANGELOG.0.6.md` theme for runtime fixes; mapped documentation and commit trailers must agree. Report focused regressions, full development-tree suite, guards, exact commit, and any limitations to the coordinator. The coordinator must validate this task before acceptance.

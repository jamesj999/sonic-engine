# Configurable Key Modifiers — Design

Date: 2026-07-25
Branch: `feature/ai-key-modifiers` (worktree
`/home/farrell/code/projects/OpenGGF-key-modifiers`, based on `origin/develop`
at `1474a7a48`)

## Problem

A key binding in `config.yaml` is a bare key code. Any shortcut that wants a
modifier has to hardcode it at its call site, which splits one user-facing
concept across two places and leaves half of it unconfigurable.

`capture.toggleKey` is the worked example. The player can change the key, but
the Shift it must be pressed with is not in the config at all — and it is not in
`Engine` either. `Engine.handleLiveCaptureShortcut:1938` only *forwards* modifier state
(`:1942-1944`):

```java
int key = configService.getInt(SonicConfiguration.CAPTURE_TOGGLE_KEY);
if (!liveCaptureChord.update(inputHandler.isKeyDown(key), inputHandler.isShiftDown(),
        inputHandler.isControlDown(), inputHandler.isAltDown())) {
```

The Shift policy itself is one line inside the edge detector, `LiveCaptureChord:9`:

```java
boolean complete = keyDown && shiftDown && !controlDown && !altDown;
```

That matters for step 4: `LiveCaptureChord.update`'s 4-boolean signature *is* the
chord policy, so "keep the existing edge-triggering" cannot mean "leave `update`
alone".

So the binding documents itself as "the key", the chord is really "Shift + that
key", and there is no way to express `CTRL+SHIFT+O` or to drop the Shift. The
same shape has already recurred eight more times (see the inventory below).

## Goal

Express modifiers in the binding itself:

```yaml
capture:
  toggleKey: "SHIFT+O"
debug:
  keys:
    something: "CTRL+SHIFT+O"
    other: "META+LEFT_BRACKET"
```

Non-goals: rebinding UI, per-context keymaps, chord sequences (`C-x C-s`),
gamepad chords.

## Current state (verified)

| Fact | Location |
|---|---|
| `KEY` bindings resolve **name first, integer second** | `SonicConfigurationService.resolveInt:183-202` |
| Registered *defaults* resolve **integer first, name second** | `SonicConfigurationService.resolveKeyCode:853-869` |
| A **non-empty** unresolvable `KEY` value falls back to the **default** | `SonicConfigurationService.resolveInt:204-217`; `docs/guide/playing/configuration.md:119` |
| An **empty** `KEY` value is unbound — the whole warn-and-default block sits inside `if (!str.isEmpty())`, so no default is consulted | `SonicConfigurationService.resolveInt:204-217` |
| `isKeyDown(-1)` is **not** false — out-of-range codes fall through to `keyCode == inputBindings.rewindKey() && gamepadInputManager.isRewindHeld()` | `InputHandler.isKeyDown:102-106` |
| Every `saveConfig()` rewrites all `KEY` values through a normaliser that emits anything it cannot resolve verbatim | `ConfigYamlWriter.formatKey:116-133` |
| Name↔code tables already exist both ways | `GlfwKeyNameResolver.resolve` / `.nameOf` |
| 53 `KEY`-typed config entries | `ConfigCatalog` |
| Only Shift/Ctrl/Alt are queryable; **no Super/Meta** | `InputHandler:173-192` |
| Key state is cleared **only** on an observed `GLFW_RELEASE` | `InputHandler.handleKeyEvent:68-76`; focus callback `Engine:495-502` does not clear |
| Default is the bare key, in two places | `putDefaultKey(CAPTURE_TOGGLE_KEY, GLFW_KEY_O)` **and** `src/main/resources/config.yaml:172` |

The two resolution orders in rows 1 and 2 are the root of a landed bug — see
"Finding: two key-resolution orders" below. The earlier draft of this spec cited
only `resolveKeyCode` and so mis-stated the live rule, and put the entry count at
51. It is **53**: 52 entries declare `KEY` on the same line as their `put(`, and
`CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY` declares it on the continuation
line `ConfigCatalog:358`. The two `derived(KEY, …)` entries (`JUMP` at `:95`,
`P2_JUMP` at `:106`) are `KEY`-typed and are counted. Anything that has to
enumerate these — step 5's table above all — should be generated from
`ConfigCatalog` entries whose `type() == ConfigType.KEY` rather than transcribed,
so it cannot drift again.

### Inventory: hardcoded chords

`capture.toggleKey` is **not** the only one. Verified call sites:

| Site | Chord | Key source | In scope |
|---|---|---|---|
| `Engine.java:1943` → `capture/LiveCaptureChord.java:9` | Shift + key, Ctrl/Alt suppress | `CAPTURE_TOGGLE_KEY` (config) | **Yes — step 4** |
| `sprites/managers/SpriteManager.java:457-458,965-971` | Shift = debug speed-up, Ctrl = debug slow-down, qualifying the direction keys | `UP`/`DOWN`/`LEFT`/`RIGHT` (config) | No — see the decision in the logical-input finding |
| `game/recording/UserRecordingRuntimeControls.java:33,44` | Shift+key to start; key alone to stop | `RECORDING_RECORD_KEY` (config) | Deferred — step 7, both sites together |
| `game/MasterTitleScreen.java:375` | Shift + key | `RECORDING_RECORD_KEY` (config) | Deferred — step 7, with the above |
| `GameLoop.java:1877-1895` | Shift+B / Ctrl+B / Alt+B, exactly-one-modifier | hardcoded `GLFW_KEY_B` | No — key is not configurable |
| `GameLoop.java:903-905` | Shift+Tab | hardcoded `GLFW_KEY_TAB` | No — key is not configurable |
| `debug/DebugOverlayManager.java:68` | Ctrl+P | hardcoded `GLFW_KEY_P` | No — documented as non-configurable |
| `editor/EditorInputHandler.java:99-101,113-122` | Tab-without-Shift, Ctrl+Z/S/Y | hardcoded, raw `GLFW_KEY_LEFT_CONTROL`/`LEFT_SHIFT` | No — editor bindings are documented as hardcoded |
| `graphics/shaderlib/DisplayShaderPickerController.java:177` | Shift branch | picker-local | No |

`RECORDING_RECORD_KEY` is the obvious second conversion candidate: it is a
configurable `KEY` whose Shift is hardcoded, its `ConfigCatalog:327` description
advertises the Shift in prose exactly the way `CAPTURE_TOGGLE_KEY`'s does, and
it is read from **two** call sites that must be converted together or they will
disagree about what starts a recording.

### Finding: two key-resolution orders

`resolveInt` resolves a `KEY`-typed string through the name table **first** —
there is an explicit branch and comment for it, because `"1"` must mean the
number-row 1 key (49), not raw key code 1. `TestConfigKeyNameResolution.
getInt_digitKeyNameResolvesToNumberRowKeyBeforeRawIntegerParsing` pins that.

`KeyChord.keyCode` (landed in step 0) does `Integer.parseInt` **first**. So:

- `KeyChord.parse("1").keyCode()` is `1`, while `getInt` on the same value is
  `49`. `1` is below the lowest GLFW key code (`SPACE` = 32), so `isBound()`
  returns true and the binding is silently dead rather than reported.
- `format()` does not round-trip for `GLFW_KEY_0`..`GLFW_KEY_9`:
  `nameOf(49)` is `"1"`, and `parse("1")` is `1`.

`TestKeyChord` exercises only `"O"` / `"GLFW_KEY_O"` / `"79"` / `LEFT_BRACKET`,
all unambiguous, so its 13 tests do not catch this. `CONFIGURATION.md:536-541`
documents both numeric strings and single-character names as valid, so digit
bindings are a supported form.

**Decision: `KeyChord` adopts `resolveInt`'s order — name table first, integer
parse second.** Fixed in step 0b.

### Finding: `parse` throws on separator-only input

`text.split("\\+")` drops trailing empty tokens, so `"+"`, `"++"` and `"+++"`
each yield a **zero-length** array and `segments[segments.length - 1]` indexes
`-1`. Verified with a JDK probe: `[+] len=0`, `[++] len=0`, `[+++] len=0`, while
`[CTRL+] len=1` and `[+O] len=2` are safe.

`"+"` is exactly what a player writes to bind the plus key once the docs
advertise `+` as the separator, and after step 4 the parse happens inside
`Engine.handleLiveCaptureShortcut` on the render path — an uncaught per-frame
throw, not a startup warning. Fixed in step 0b.

(The `+`-is-a-safe-separator claim itself holds: every name in the table derives
from a `GLFW_KEY_*` Java identifier, plus the one manual `"#"` alias at
`GlfwKeyNameResolver:67`, and none can contain `+`.)

### Finding: modifier queries are inconsistent about logical input

`isShiftDown()` and `isControlDown()` consult `logicalOverride`
(`debugShiftDown()` / `debugControlDown()`) so trace playback and rewind can
drive them deterministically. **`isAltDown()` does not** — it always reads live
hardware. Any chord using Alt would therefore not be reproducible under trace
playback, unlike the same chord using Ctrl or Shift.

Three qualifications the earlier draft missed:

- Even for Shift/Ctrl, only *live rewind* supplies real values.
  `Bk2MovieLoader:162-166` builds frames with the 8-arg convenience constructor,
  so `debugShiftDown`/`debugControlDown` are hardcoded `false` for every BK2
  frame. A Shift+O capture chord is already untriggerable under BK2 playback.
  That is pre-existing, but it means "deterministic" here means "deterministically
  false".
- Extending the surface is a record change, not an `InputHandler` edit —
  see step 2 for the exact blast radius.
- The one feature actually *named* after those fields does not consult them.
  `Bk2FrameInput:19-20` documents `debugShiftDown`/`debugControlDown` as the
  "debug-movement speed-up modifier" and "slow modifier", but
  `SpriteManager.isDebugSpeedUpModifierDown`/`isDebugSlowDownModifierDown:965-971`
  read `isKeyDown(GLFW_KEY_LEFT_SHIFT)` etc. directly, bypassing
  `logicalOverride` entirely. Only callers that go through
  `isShiftDown()`/`isControlDown()` see the override.

**Decision: extend the logical-override surface to Alt and Meta** (step 2), so
`isSuperDown()` does not inherit the asymmetry, and document that BK2 movies
carry no modifier column so all four read false under BK2 playback.

**Decision: `SpriteManager` stays on raw hardware, out of scope.** Routing it
through `isShiftDown()`/`isControlDown()` would change what debug movement does
under trace playback and live rewind in both directions (false under BK2, real
values under live rewind), which is a physics-adjacent behaviour change that
should not ride along on a config feature. Recorded here so the next reader does
not assume step 2 made debug-movement speed reproducible — it did not.

### Finding: an unbound chord is not inert at the call site

`isBound()` is a query, not a guard, and `InputHandler.isKeyDown:102-106` does
**not** return false for `NO_KEY`:

```java
if (keyCode >= 0 && keyCode < MAX_KEYS && keys[keyCode]) {
    return true;
}
return keyCode == inputBindings.rewindKey() && gamepadInputManager.isRewindHeld();
```

`inputBindings.rewindKey()` is `getInt(LIVE_REWIND_KEY)`
(`InputBindingFactory:51`). Unbind live rewind and that is `-1` too, so
`isKeyDown(-1)` becomes "is the gamepad rewind button held". Pair it with
`matchesModifiers` on an empty modifier set — satisfied whenever no modifier is
held — and an *unbound* chord fires from a gamepad hold. Every `PLAYBACK_*`
binding defaults to `""`, so step 6 would meet this repeatedly.

The precondition is narrow (both the chord and `LIVE_REWIND_KEY` unbound, plus a
pad button held), but it is not visible from reading `isKeyDown`, which is
exactly why it needs to be written down.

**Decision: guard at the call site, do not harden `isKeyDown`.** Every
`getKeyChord` consumer returns early on `!chord.isBound()` before touching
`isKeyDown`. Rejecting `keyCode < 0` inside `isKeyDown` looks tidier but would
disable *gamepad* rewind for any player who unbinds the keyboard rewind key,
which is a live behaviour change to an unrelated feature.

## Design

### `KeyChord` — landed, `9ecb0fb56`

```java
public record KeyChord(int keyCode, Set<Modifier> modifiers) {
    public enum Modifier { CTRL, SHIFT, ALT, META }
    public static KeyChord parse(Object configured);
    public boolean matchesModifiers(boolean shift, boolean ctrl, boolean alt, boolean meta);
    public String format();
    public boolean isBound();
}
```

Decisions taken:

- **Backward compatible for the forms that resolve unambiguously.** An integer,
  `"O"` and `"GLFW_KEY_O"` all parse to that key with an empty modifier set.
  Digits are the exception until step 0b lands; see the first Finding.
- **`matchesModifiers` is exact.** Declared modifiers must be held and the
  others released, so a plain `"O"` does not fire while Ctrl is down and steal a
  chord another binding has claimed. Note this only arbitrates between bindings
  that both route through `KeyChord` — it does nothing about hardcoded keys that
  read `isKeyPressed` directly (see Risks).
- **Aliases follow what players type**: `CONTROL`, `OPTION`, and
  `SUPER`/`CMD`/`COMMAND`/`WIN` for META, because GLFW, macOS and Windows each
  name that key differently.
- **`+` is a safe separator**: no GLFW key name contains one (the plus key is
  `KP_ADD` / `EQUAL`).
- **Unresolvable input yields `NO_KEY` from `parse`.** This does **not** match
  how bindings behaved before, and the earlier draft got the old behaviour wrong
  in both directions: it recorded the divergence as a no-op, then described
  `resolveInt` as always falling back to the default. It does not. The
  warn-and-default block is inside `if (!str.isEmpty())`
  (`resolveInt:204-217`), so `resolveInt` has **two** unresolvable cases:
  - an explicitly empty value → `-1`, no default lookup, no warning. This — not
    an empty *default* — is why the `PLAYBACK_*` keys read as unbound: their
    persisted *value* is `""` (`src/main/resources/config.yaml:203-211`).
    The gate is `isEmpty()`, not `isBlank()`, so a whitespace-only value takes
    the second path; `getKeyChord` must draw the line in the same place rather
    than "improving" on it, or the two accessors disagree again.
  - a non-empty value that resolves as neither name nor integer → warn, then
    `resolveKeyCode(defaults.get(name))`, falling back to `-1` only if that is
    itself unbound.

  **Decision: `getKeyChord` reconciles at the accessor, not in `parse`, and
  reproduces both cases.** A null, empty or blank configured value is unbound
  full stop; a non-empty value that yields `NO_KEY` falls back to
  `KeyChord.parse(defaults.get(name))` with the same warning shape as
  `resolveInt`. Collapsing the two would make `""` mean "the default", so a
  player could no longer unbind a shortcut and the two accessors would disagree
  on the very first form a user reaches for. `parse` stays a pure function with
  no config knowledge.
- **Canonical format order** CTRL, SHIFT, ALT, META, so `format()` round-trips.

## Remaining work

Ordered by dependency. Each step is independently shippable and leaves the
build green.

### 0b. Correct the landed `KeyChord`

Two defects in `9ecb0fb56`, both described under Findings:

- `keyCode(String)`: resolve through `GlfwKeyNameResolver` **first**, fall back
  to `Integer.parseInt`, matching `resolveInt:183-202`.
- `parse`: return `NO_KEY` when `segments.length == 0`, so separator-only input
  cannot throw.

**Done when:** `parse("1")` is `GLFW_KEY_1` (49), `parse("79")` is `GLFW_KEY_O`,
`format()`/`parse` round-trips across `GLFW_KEY_0`..`GLFW_KEY_9` plus a
representative sample of the whole name table, and `"+"` / `"++"` join the
unbound-input cases in `TestKeyChord` instead of throwing.

### 1. Meta/Super support in `InputHandler`

Add `isSuperDown()` over `GLFW_KEY_LEFT_SUPER` / `GLFW_KEY_RIGHT_SUPER`.
Until this exists `"META+..."` parses but can never match.

`isAnyModifierDown()` must account for Super, or it will silently disagree with
chord matching. **That is not free:** it gates `isKeyPressedWithoutModifiers`,
which has ~30 call sites — `PlaybackDebugManager` (9 shortcuts),
`UserRecordingMenuState` (11 menu keys), `TestModeTracePicker` (9 keys) and
`GameLoop.isUnmodifiedDebugKeyPressed:1864`. Every one of those stops responding
while a Super/Cmd key is held, which on macOS is common.

That widens an existing hazard rather than creating one: `handleKeyEvent:68-76`
clears a key only on an observed `GLFW_RELEASE`, and the focus callback at
`Engine:495-502` only pauses/resumes — nothing zeroes `keys[]`. Super is the
standard window-switch modifier on Linux and Windows, so the release is
routinely delivered to another window and the key latches on forever, disabling
every call site above. The same latch already exists for Alt via Alt+Tab.

**Decision: include Super in the aggregates, and pair it with a focus-loss key
state clear** in the same step, so the latch is closed rather than widened.

**Done when:** Super is queryable; `isAnyModifierDown()` and
`isKeyPressedWithoutModifiers()` account for it; losing window focus zeroes
`keys`/`previousKeys`; and a regression test shows a latched modifier is dropped
on focus loss and one menu path (`UserRecordingMenuState` or
`TestModeTracePicker`) still responds afterwards.

### 2. Extend the logical-override surface to Alt and Meta

Its own step because it is a record change, not an `InputHandler` edit.
`debugShiftDown` / `debugControlDown` are components of two records:

| File | Change |
|---|---|
| `control/LogicalInputSnapshot.java` | 2 new components; canonical ctor used 3× in-file (`ofPlayers`, `withMenuPolicy`, `withDebugInput`); `withDebugInput` grows to 5 args |
| `debug/playback/Bk2FrameInput.java` | 2 new components; both convenience ctors absorb them as `false` |
| `debug/playback/RecordedInputSnapshots.java:32-35` | pass the two new fields through |
| `debug/playback/Bk2MovieLoader.java:162-166` | unchanged — uses the 8-arg convenience ctor |
| `game/rewind/LiveRewindInputSource.java:36-47` | supply `isAltDown()` / `isSuperDown()` |
| `control/InputHandler.java:173-192` | `isAltDown()` / `isSuperDown()` consult `logicalOverride` |
| `test/.../TestPlayerInputState.java:111`, `TestInputHandlerLogicalSnapshot.java:66`, `TestLiveRewindInputSource.java` | `withDebugInput` arity |

Six files name those components today; the two record *types* appear in 78
files, but almost all of those read components rather than call the canonical
constructor, so the arity change is contained to the table above. Confirm with a
compile rather than assuming.

BK2 movies have no Alt or Meta column, so the new fields are `false` by
construction under BK2 playback — same as Shift/Ctrl are today. That is the
documented limit, not a defect to fix here.

**Done when:** all four modifier queries consult `logicalOverride` identically,
the two records carry all four, live rewind records all four, and
`CONFIGURATION.md` states that BK2 playback supplies no modifier data so chords
requiring a modifier do not fire under it.

*Alternative if this cost is not worth paying now:* leave the surface at
Shift/Ctrl and document Alt/Meta chords as not playback-reproducible. Criterion
9 is satisfied either way, but the choice must be recorded, not left open.

### 3. `getKeyChord` accessor

```java
public KeyChord getKeyChord(SonicConfiguration binding);
```

on `SonicConfigurationService`. It must:

- read through `getConfigValue` (`:293-305`) so `sessionOverrides` and
  `transientResolved` still win;
- reuse `resolveInt`'s `DERIVED` fallback (`:171-176`) — `JUMP` and `P2_JUMP`
  are `DERIVED` (`ConfigCatalog:95,106`) and fall back to `P1_A`/`P2_A` when
  unset, so a `getKeyChord` built on `getConfigValue` alone returns `NO_KEY` for
  them;
- treat a null, empty or blank configured value as unbound and stop there — no
  default lookup, no warning — matching `resolveInt`'s `!str.isEmpty()` gate, and
  fall back to `KeyChord.parse(defaults.get(name))` only for a **non-empty**
  value that yields `NO_KEY` (see the Design decision above). Put the reasoning
  in a comment at the gate: it is the one place where "unresolvable" and "empty"
  must not be collapsed;
- clear any chord cache everywhere `intCache` is cleared — seven sites:
  `:372, 396, 401, 406, 508, 536, 554`.

Two consumer-side rules travel with the accessor and belong in the same step,
because a caller that follows neither is silently broken rather than loudly:

- **Guard on `isBound()` before `isKeyDown`.** See "Finding: an unbound chord is
  not inert at the call site" — `isKeyDown(-1)` is not false.
- **`ConfigYamlWriter.formatKey:116-133` gains a chord branch.** It normalises
  every `KEY` value on write (`Number` → `nameOf`, resolvable name → canonical
  name, integer string → `nameOf`, everything else *verbatim*), and a chord lands
  in the verbatim branch — so `shift+o`, `Shift + O` and `CMD+O` each persist as
  the player typed them. Nothing is corrupted (a chord is a valid plain scalar
  and `needsKeyQuote` only quotes all-digit values), but the file stops having
  one canonical spelling, and this path runs on the very save step 4's migration
  triggers. Parse and re-`format()` a resolvable chord instead.

`getInt(KEY)` keeps returning the bare key code so the other 52 bindings are
untouched. **That requires two edits, not zero.** Today a chorded string is
neither a name nor an integer, so `resolveInt` warns and returns the *default*'s
key code — and once step 4 makes the default itself `"SHIFT+O"`, the fallback
runs `resolveKeyCode("SHIFT+O")`, which is chord-blind, yields `-1`, and drops
into the "Defaulting to unbound" branch, returning `-1` on every cold cache. So:

- `resolveInt` (`:183-202`) must strip modifier segments before name/integer
  resolution for `ConfigType.KEY` entries;
- `resolveKeyCode` (`:853-869`) must do the same, since it resolves defaults.

**Done when:** both accessors agree on the key code for every form — including
digits, chords, `DERIVED` bindings, values that fall back to their default, and
an explicitly empty value, which both must report as unbound; an unbound chord
cannot fire even while a gamepad rewind button is held; a chord written in a
non-canonical spelling comes back canonical after a `saveConfig()` round trip;
and a converted and unconverted binding can coexist in one `config.yaml`.

### 4. Convert `capture.toggleKey`

Default becomes `"SHIFT+O"`. `Engine.handleLiveCaptureShortcut` reads the chord
via `getKeyChord`, returns early if it is not `isBound()`, and stops computing
the Shift policy itself.

**`LiveCaptureChord` changes too — its signature *is* the Shift policy.** The
earlier draft said "keep the existing `liveCaptureChord.update`", which is not
possible: the hardcoded Shift lives at `LiveCaptureChord:9`
(`keyDown && shiftDown && !controlDown && !altDown`), not in `Engine`, and the
4-boolean signature has no room for the Super state step 1 adds. Only the
rising-edge latch is kept. `update` is respecified to take the chord plus all
four modifier states — `update(KeyChord chord, boolean keyDown, boolean
shiftDown, boolean controlDown, boolean altDown, boolean superDown)` — and
delegates the accept/reject decision to `chord.matchesModifiers`. Passing a
pre-computed `complete` boolean instead is equivalent and also acceptable; what
is not acceptable is leaving a second modifier policy in the detector.

All five tests in `LiveCaptureChordTest` call the 4-arg form and assert
Shift-required / Ctrl-and-Alt-suppress semantics (`:8-43`), so they change with
it; see the test plan.

The default lives in **two** places and both must change, or the behaviour is
unchanged for everyone:

- `putDefaultKey(CAPTURE_TOGGLE_KEY, GLFW_KEY_O)` — the Java default.
- `src/main/resources/config.yaml:172` — `toggleKey: O`. `loadConfig:101-114`
  loads this classpath resource as `config` when no user file exists, and
  `putDefault:719-727` only inserts when the key is *absent*, so on a fresh
  install this explicit `O` wins over the Java default.

`ConfigCatalog:266`'s description string is a third source — it is what
`ConfigYamlWriter` emits as the comment into every saved config, and it still
says "Shift+key toggles…". It must be reworded now that the Shift is in the
value.

**Existing installs need a migration.** `putDefault` never overwrites, and
`ConfigYamlWriter` emits every key in `emitOrder()`, so every user who has ever
launched the engine has a literal `capture.toggleKey: O` persisted. Changing the
default reaches none of them: their bare `"O"` wins, exact matching rejects the
held Shift, Shift+O stops working, and a stray `O` starts a recording. A
changelog note does not mitigate a shortcut silently changing meaning.

Add `ConfigMigrationService.migrateDeprecatedCaptureToggleKey(config)`, modelled
on `migrateDeprecatedDisplayColorProfileToggleKey:179-206`: rewrite to
`"SHIFT+O"` **only** when the persisted value still equals the superseded
default (`"O"` / `"GLFW_KEY_O"` / `"KEY_O"` / `79`). Wire it into the migration
block at `SonicConfigurationService:119-136` with the `configChanged = true`
flag.

**Decision on customised values:** a player who set `toggleKey: P` is left
alone. Their binding becomes a bare `P` chord — Shift is no longer required —
which is the same one-time behaviour change the migration spares default users,
and inferring `SHIFT+P` would silently rewrite a value the player chose. Call it
out in the changelog.

**Decision: the migration is value-based and therefore re-runs, and bare `O`
becomes a reserved value for this binding.** The migration block at
`SonicConfigurationService:119-136` runs unconditionally on every load, before
`applyDefaults`, and `configChanged` forces `saveConfig()` at `:141-143`. There
is no config schema or version marker anywhere in `src/main` to hang a one-shot
guard on. So the sequence for a player who deliberately writes `toggleKey: O` to
drop the Shift is: next launch, the migration matches, rewrites to `"SHIFT+O"`,
and persists it. Bare `O` cannot survive a launch — and neither can `GLFW_KEY_O`,
`KEY_O` or `79`, since the match set covers every spelling.

This is the established shape in this file, not a new hazard:
`migrateDeprecatedDisplayColorProfileToggleKey:179-206` has exactly the same
property for `#`/`WORLD_1`, in both the numeric and the string spelling.
(`migrateDeprecatedS1PreviewCoordLogKey:157-169` is milder — `getIntValue`
returns null for strings, so it only re-rewrites a numeric `WORLD_1`/`F8`.)
Introducing a `configVersion` marker to make this one migration
one-shot would touch `ConfigFlattener`, `ConfigYamlWriter.emitOrder`,
`TestBundledConfigResource` and every other migration — disproportionate to a
config-syntax feature, and better done as its own change if the pattern keeps
biting.

What is **not** acceptable is leaving the earlier draft's done-when clause
`"O" alone toggles without Shift` in place: it is unsatisfiable from
`config.yaml` and promises the opposite of what ships. It is replaced below with
a reachable form, and step 5 must document `O` as reserved for this binding.

**Decision on the `DebugOverlayToggle.OBJECT_DEBUG` collision** (the Risks table
deferred this to step 4; here it is). `DebugOverlayManager.updateInput:58-65`
fires every toggle on a bare `handler.isKeyPressed(toggle.keyCode())` with no
modifier filter, and `OBJECT_DEBUG` is `GLFW_KEY_O` (`DebugOverlayToggle:21`), so
the new `SHIFT+O` default would toggle the object-debug overlay on the same
keystroke that starts a capture. **The dispatch stands a toggle down only for the
frame a chord bound to the same key is satisfied**, rather than moving the capture
default off `O` — moving it would break acceptance criterion 7 (Shift+O keeps
working with no user action) for every existing user.

*Revised after review.* The first form of this decision was
`isKeyPressedWithoutModifiers` for all 16 toggles, i.e. no toggle fires while
*any* modifier is held. That is far wider than the collision it fixes and was
replaced: `DebugOverlayManager.updateInput` now takes the configured
`capture.toggleKey` chord, and a toggle is suppressed only when a modified chord
on the *same* key code matches the modifiers held this frame.

Scope that as its own bullet, because it is not a consequence-free one-liner:

- The collisions that exist are exactly two: `OBJECT_DEBUG` (`O`) versus the
  `SHIFT+O` capture default, and `PERFORMANCE` (`P`) versus the hardcoded Ctrl+P
  stats copy. Nothing else is touched — an F-key toggle keeps working with a
  modifier held, which matters because `P2_A` defaults to `GLFW_KEY_RIGHT_SHIFT`
  and `isShiftDown()` cannot tell left Shift from right: a blanket
  "no modifier held" rule switches all 16 toggles off for as long as player two
  holds jump, and likewise for the Shift held for debug fast movement.
- The suppression follows the *binding*, not a hardcoded Shift. Rebinding
  `capture.toggleKey` to `ALT+F5` moves the reserved keystroke to Alt+F5 and
  hands `SHIFT+O` back to `OBJECT_DEBUG`. A bare (unmodified) capture binding
  reserves nothing, which is why the documented bare-key example must not name a
  key `DebugOverlayToggle` already claims.
- `PERFORMANCE` is dispatched at `:51`, *before* the `debugShortcutsEnabled`
  gate, and must get the same treatment. That incidentally fixes a pre-existing
  double-fire: today Ctrl+P both toggles the performance overlay and copies the
  stats to the clipboard (`:68`).
- Severity without this fix is developer-facing, not player-facing: the bundled
  `config.yaml:180` ships `debugView: false`, so the collision needs debug
  shortcuts turned on. That is why it is a step-4 bullet and not a blocker on
  step 0b.

**Done when (this bullet):** Shift+O starts a capture without toggling
`OBJECT_DEBUG`; a bare F-key toggle still works, including while an unrelated
modifier such as player two's right-Shift jump is held; and a regression test
covers all three.

`CaptureConfigDefaultsTest` **must** be updated as part of this step, contrary to
the earlier draft's test plan:

- `:30` asserts the bundled YAML value is literally `"O"` — becomes `"SHIFT+O"`.
- `:21` asserts `getInt(CAPTURE_TOGGLE_KEY) == GLFW_KEY_O` — this one must keep
  passing **unchanged**, and is the assertion that proves the `resolveInt` edit
  in step 3 landed correctly.

**Done when:** a fresh install and a migrated existing install both toggle on
Shift+O with no user action; a bare-key chord such as `"P"` toggles without Shift
(`"O"` cannot — see the reserved-value decision above); `"CTRL+SHIFT+O"` requires
both; an unbound value (`""`) never toggles; `LiveCaptureChord` holds no modifier
policy of its own; a `TestConfigMigrationService` case covers the rewrite and the
leave-alone; Shift+O does not also toggle `OBJECT_DEBUG`; and the changelog
records the customised-value behaviour change and the reserved `O`.

### 5. Documentation

Four documentation surfaces, not two. The earlier draft scoped only the first
two:

- `CONFIGURATION.md`: chord syntax, the accepted modifier aliases, the exactness
  rule, how to bind the plus key itself (`EQUAL` or `KP_ADD`, since `+` is the
  separator), an empty value meaning unbound, `O` being reserved for
  `capture.toggleKey`, and the BK2-playback limit from step 2.
- Bundled `config.yaml`: syntax note by `capture.toggleKey`.
- `docs/guide/playing/configuration.md:107-121` — the "How do I change controls?"
  section. It is the page that *defines* the accepted syntax ("Key bindings
  accept either GLFW key codes (integers) or human-readable names. The following
  formats all work:") and it is the same page this spec's Current-state table
  cites at `:119` for the fallback rule. After this work both sentences are
  wrong: the format list omits chords, and "Invalid names log a warning and fall
  back to the default binding" does not hold for an empty value.
- `docs/guide/playing/controls.md:3-6` — repeats the same two-format claim as the
  front door to the controls reference.

Those last two make `Guide: updated` mandatory on the documentation commit; a
`Guide: n/a` here would be wrong.

`CONFIGURATION.md` and the bundled `config.yaml` both list every key binding, so
the per-binding support table must be **three-state**, not two:

| State | Meaning | Example |
|---|---|---|
| Chord honoured | read via `getKeyChord`, exact matching | `capture.toggleKey` |
| Modifiers ignored | read via `getInt` + `isKeyDown`/`isKeyPressed`; a chord resolves to its bare key and the modifiers are dropped | most bindings |
| **Chord permanently dead** | read via `isKeyPressedWithoutModifiers`, which is `isKeyPressed(key) && !isAnyModifierDown()` — the modifier must be held to type the chord, and holding it blocks the shortcut | the 9 `PLAYBACK_*` keys and the list below |

The third state is the one a user would file a bug about, and it is invisible
from the config file. `debug.playback.toggleKey: "CTRL+P"` would give `getInt` a
live key code and still never fire.

"`GameLoop`'s debug shortcuts" is too vague to build a per-binding table from,
which is what the done-when demands. The dead set is defined by the
`isKeyPressedWithoutModifiers` and `GameLoop.isUnmodifiedDebugKeyPressed` call
sites, and should be generated from them; at the branch base it is the 9
`PLAYBACK_*` keys plus `SPECIAL_STAGE_KEY`, `SPECIAL_STAGE_COMPLETE_KEY`,
`SPECIAL_STAGE_FAIL_KEY`, `SPECIAL_STAGE_SPRITE_DEBUG_KEY`,
`SPECIAL_STAGE_PLANE_DEBUG_KEY`, `DEBUG_MODE_KEY`, `NEXT_ACT`, `NEXT_ZONE`,
`LEVEL_SELECT_KEY`, `DEBUG_LAST_CHECKPOINT_KEY` — **and `UP`/`DOWN`/`LEFT`/
`RIGHT`**, via the sprite-debug navigation at `GameLoop:1135-1146`.

So a binding can be in two states on different paths, and the table has to say
so: the four movement bindings are "modifiers ignored" on the gameplay path and
"chord permanently dead" on the special-stage sprite-debug path. Step 6's
gameplay rule is written for the first path only.

Also note the hardcoded non-config keys from the inventory, which will swallow a
keystroke regardless of what any binding says.

**Done when:** a reader can tell, per binding *and per path*, which of the three
states it is in, and the dead set was generated from the call sites rather than
transcribed.

### 6. Optional — roll out to remaining bindings

Not mechanical. Per subsystem, and only where a call site does not already
implement its own modifier handling that would then be duplicated. Exclusions
and rules:

- **Bindings whose key *is* a modifier key cannot use exact matching as-is.**
  `putDefaultKey(P2_A, GLFW_KEY_RIGHT_SHIFT)` (`:602`),
  `LIVE_REWIND_HALF_SPEED_KEY = GLFW_KEY_LEFT_CONTROL` (`:650`) and
  `LIVE_REWIND_DOUBLE_SPEED_KEY = GLFW_KEY_LEFT_SHIFT` (`:651`) are all
  defaults today. `isShiftDown()` is true whenever either Shift is down, so a
  chord of `RIGHT_SHIFT` with no declared modifiers can never satisfy
  `matchesModifiers` — pressing player 2's jump makes its own chord fail. Either
  such bindings bypass exact matching, or `KeyChord` must exclude a held
  modifier that is the chord's own key.
- **Gameplay/movement bindings keep permissive matching *on the gameplay path***
  unless deliberately converted. They are read with `isKeyDown`/`isKeyPressed`,
  which do not require modifiers released; adopting exact matching silently kills
  movement while any modifier is held. Note that this is a per-path rule, not a
  per-binding one: `UP`/`DOWN`/`LEFT`/`RIGHT` are *also* read through
  `isUnmodifiedDebugKeyPressed` at `GameLoop:1135-1146`, so the same four
  bindings are already modifier-exclusive on the sprite-debug path. And Shift and
  Ctrl already carry meaning alongside them —
  `SpriteManager.isDebugSpeedUpModifierDown`/`isDebugSlowDownModifierDown` make
  Shift+direction and Ctrl+direction the debug-movement speed controls. Converting
  the movement bindings to exact matching would collide with that, which is a
  further reason not to.
- **Any binding consumed via `isKeyPressedWithoutModifiers`** must be converted
  to `getKeyChord` + `matchesModifiers` at the same time it is advertised as
  chord-capable, or documented as single-key-only. Converting one without the
  other produces the dead-chord state above.
- `JUMP` / `P2_JUMP` need step 3's `DERIVED` fallback.

### 7. Optional — `RECORDING_RECORD_KEY`

The second configurable binding with a hardcoded Shift, at
`UserRecordingRuntimeControls:33,44` and `MasterTitleScreen:375`. Both sites
must be converted together, and `ConfigCatalog:327`'s prose description updated
the same way `CAPTURE_TOGGLE_KEY`'s is in step 4. The stop-on-plain-key rule
(`isKeyPressed(recordKey) && !isShiftDown()`) is a second, derived chord — decide
whether it becomes its own binding or stays "the record chord without its
modifiers".

## Acceptance criteria

1. Every form that parsed before parses to the same key with no modifiers,
   **including the ten number-row digit names**, which requires step 0b.
2. `"CTRL+SHIFT+O"`, `"META+LEFT_BRACKET"`, and case/whitespace/alias variants
   resolve to the same chord as their canonical form.
3. `format()` round-trips through `parse` for every chord, across the whole key
   name table rather than a hand-picked sample.
4. A plain binding does not fire while any modifier is held.
5. A chord fires only with exactly its modifiers.
6. Unresolvable input is unbound, never an exception — including separator-only
   input such as `"+"`.
7. Live recording still toggles on Shift+O with no user action, on a fresh
   install **and** on an existing install carrying a persisted `toggleKey: O`,
   the latter via the step-4 migration.
8. Meta chords match real Super presses.
9. Alt and Meta chords behave under trace playback as decided in step 2, and
   that decision — including the BK2 no-modifier-column limit — is documented.
10. A latched modifier does not survive window focus loss.
11. Full suite green; `capture.toggleKey` conversion carries a changelog entry
    covering both the visible-Shift change and the customised-value case.
12. An explicitly empty value is unbound, and `getKeyChord` and `getInt` agree
    that it is — no default is substituted for either.
13. An unbound chord never fires, including while a gamepad rewind button is held
    and `LIVE_REWIND_KEY` is itself unbound.
14. Shift+O starts a capture without also toggling the `OBJECT_DEBUG` overlay
    when debug shortcuts are enabled, and bare-key debug overlay toggles still
    work — including while an unrelated modifier is held, such as the right
    Shift that is player two's default jump.
15. A chord saved in a non-canonical spelling (`shift+o`, `Shift + O`) is
    rewritten to its canonical form by `saveConfig()`.
16. The guide pages that define binding syntax
    (`docs/guide/playing/configuration.md`, `docs/guide/playing/controls.md`)
    describe chords and the empty-is-unbound rule.

## Risks

| Risk | Mitigation |
|---|---|
| An existing `toggleKey: "O"` starts firing without Shift | Step 4 migration rewrites the untouched default; a customised value is deliberately left alone and called out in the changelog |
| The step-4 migration re-runs every launch, so bare `O` can never be bound to this action | Accepted, not mitigated: `O` is a reserved value for `capture.toggleKey`. Same property as the two existing key migrations. Documented in step 5; a one-shot guard would need a config schema marker that does not exist |
| Plain bindings shadowing chords on the same key | `matchesModifiers` is exact — **but only between bindings that route through `KeyChord`**, which after step 4 is one binding |
| Hardcoded keys shadowing a chord | `DebugOverlayToggle.OBJECT_DEBUG` is `GLFW_KEY_O` and `DebugOverlayManager:58-65` fires it on a bare `isKeyPressed` with no modifier filter. **Decided in step 4, revised after review:** a toggle stands down only for the frame a modified chord bound to the *same* key is satisfied, so the other 14 toggles — and `O` itself under any other modifier combination — are untouched |
| An unbound chord firing anyway | `isKeyDown(-1)` falls through to the gamepad rewind comparison; every `getKeyChord` consumer guards on `isBound()` first (step 3). **Revised after review:** `isKeyPressed(-1)` had the same fall-through against `debugModeKey()`/`frameStepKey()` and no call-site guard, so it is rejected inside `InputHandler` instead |
| Adding Super to `isAnyModifierDown` disables ~30 shortcuts while Cmd is held | Step 1 pairs it with a clear of held key state and a menu regression test. **Revised after review:** the clear runs on window deactivation *and* reactivation, because clearing only on the way out relies on the loss edge firing at all — a minimise that delivers no focus event, or a window manager that grabs a Super combo without moving focus, both swallow the release with the window still active. The 16 debug overlay toggles are **not** in this set — step 4's revised form leaves them on `isKeyPressed` |
| Alt chords not reproducible under trace playback | Step 2 decision; blocking for advertising Alt |
| Debug-movement speed modifiers stay on raw hardware | Out of scope by decision; recorded under the logical-input finding so it is not mistaken for something step 2 fixed |
| Partial rollout confusing users | Step 5's three-state per-binding table |

## Test plan

- `TestKeyChord` — 13 tests landed. Step 0b adds: digit precedence (`"1"`,
  `"CTRL+1"`, `"79"`), a full-table `format()`/`parse` round-trip, and `"+"` /
  `"++"` as unbound rather than throwing.
- New: `getKeyChord`/`getInt` agreement — chorded value, digit value, `DERIVED`
  binding, a value that falls back to its default, and an **explicitly empty**
  value, which both must report as unbound.
- New: `Engine` chord evaluation via the existing non-GL seam pattern —
  default Shift+O, an unmodified bare-key chord, a Ctrl+Shift chord, and an
  unbound chord that must not fire.
- New: `isSuperDown`, the aggregate modifier helpers, and the focus-loss clear.
- New: `TestConfigMigrationService` — `capture.toggleKey` migrated from each
  spelling of the old default, and left alone when customised.
- New: `DebugOverlayManager` — Shift+O does not toggle `OBJECT_DEBUG`, and a bare
  F-key toggle still does.
- **Changed by step 4:** `CaptureConfigDefaultsTest:30` (bundled value becomes
  `"SHIFT+O"`). `:21` must keep passing unchanged and is the evidence that the
  step-3 `resolveInt` edit preserved `getInt`.
- **Changed by step 4:** `LiveCaptureChordTest` — all 5 tests call the 4-arg
  `update(...)` and assert the Shift-required / Ctrl-and-Alt-suppress semantics
  that move into `KeyChord` (`:8-43`), so they are rewritten against the new
  signature. New coverage there: an unbound chord never produces a rising edge.
  The earlier draft listed this file in neither the changed nor the untouched
  set.
- **Changed by step 3:** `TestConfigYamlWriter` gains a case that a chord written
  in a non-canonical spelling is saved in canonical form. Its 4 existing cases
  stay green unchanged.
- Evidence for criterion 1 is `TestKeyboardInputMapper`,
  `TestConfigKeyNameResolution` and `TestBundledConfigResource` staying green
  untouched — not `CaptureConfigDefaultsTest`, which this work must edit, and no
  longer `TestConfigYamlWriter`, which gains a case.

# Configurable Key Modifiers — Handoff

Date: 2026-07-25

## State

- Worktree: `/home/farrell/code/projects/OpenGGF-key-modifiers`
- Branch: `feature/ai-key-modifiers`, based on `origin/develop` at `1474a7a48`
- Head: `2cff2a286`. Base of the work: `823f8c7d6`
- `origin/develop` is green at `116651690` and **does not contain any of this**.
  Nothing here has been pushed.
- Spec: `docs/architecture/designs/2026-07-25-configurable-key-modifiers-design.md`
- Plan: `docs/architecture/plans/2026-07-25-configurable-key-modifiers.md`
- Working record: `.superpowers/sdd/key-modifiers-report.md` (ignored)

Confirm `git status --short --untracked-files=no` is empty before starting.

## What the branch does

Key bindings can carry modifiers: `"CTRL+SHIFT+O"`, `"META+LEFT_BRACKET"`.
Previously a binding was a bare key code and any shortcut wanting a modifier
hardcoded it at its call site — `capture.toggleKey` let you change the key while
the Shift it was pressed with lived in `Engine` and could be neither seen nor
changed.

`KeyChord` (in `com.openggf.configuration`) parses and formats chords. Everything
that parsed before still parses to the same key with no modifiers, so existing
`config.yaml` files keep their meaning.

## Open work — four Important findings

All four were found by review after the fixes for the previous round landed. Full
reviewer output, including detail truncated from the summaries below:

```
/tmp/claude-1000/-home-farrell-code-projects-OpenGGF-live-av-recording/
  6bb02dce-96c8-4f48-a454-decb13c9f219/tasks/wjbd0f1rl.output
```

Read that file. The summaries here are not a substitute for it.

### 1. Ctrl+P overwrites the clipboard on a default install

`DebugOverlayManager.java:88-101`.

To stop `PERFORMANCE` (`GLFW_KEY_P`) toggling on the keystroke Ctrl+P claims, the
clipboard **copy** was promoted above the `debugShortcutsEnabled` gate. On a
shipped install `DEBUG_VIEW_ENABLED` is false and absent from `config.yaml`, so
Ctrl+P now silently overwrites the OS clipboard with a performance dump where it
previously only toggled the overlay.

It compounds: the copy now reads `handler.isControlDown()` (left **or** right
Ctrl) instead of `isKeyDown(GLFW_KEY_LEFT_CONTROL)`, and **right Ctrl is player
two's default Start** (`SonicConfigurationService.java:691`, `config.yaml:44`).
In a two-player session, P2 holding Start while P1 presses P clobbers the system
clipboard.

**Suggested fix.** Put the copy back below the gate and narrow the *suppression*
instead: in `togglePressed`, only let the stats copy claim `GLFW_KEY_P` when
`debugShortcutsEnabled` is true — i.e. only when the action it stands down for
can actually run. That restores default-install behaviour exactly and keeps the
fix when debug shortcuts are on. Restrict the chord to left Ctrl regardless, so
it cannot be satisfied by P2's Start.

This is a judgement call about where a debug-only capability sits relative to its
gate. Decide it deliberately; if you keep the promotion, say so in `CHANGELOG.md`
and in `CONFIGURATION.md`'s hardcoded-shortcut paragraph.

### 2. The unbound-key guard covers only half its problem

`InputHandler.java:102-107` (`isKeyDown`) vs `:126-129` (`isKeyPressed`).

`isKeyPressed` gained `if (keyCode < 0) return false;`. `isKeyDown` did not, and
still falls through to
`return keyCode == inputBindings.rewindKey() && gamepadInputManager.isRewindHeld();`.

An unbound binding resolves to `-1`, which is the documented way to switch a
shortcut off. `rewindHeld` is set unconditionally from the pad's left bumper and
is **not** gated on `LIVE_REWIND_ENABLED`. P1_B, P1_C, P2_B and P2_C ship unbound.

So with `rewind.liveKey: ""` and a pad connected, holding L1 reports both
players' B and C as held for the whole hold — `held` disagreeing with `pressed`,
which is the inconsistency this branch exists to remove.

Worse, a comment in `TestInputHandler` asserts the `isKeyDown` twin "is guarded at
its call site". That is false. Unguarded call sites taking a config-derived code:
`KeyboardInputMapper.java:88`, `LiveRewindManager.java:108` and `:168`,
`TraceSessionLauncher.java:724`, `SpriteManager.java:456`,
`UserRecordingRuntimeControls.java:44`.

**Suggested fix.** Add the identical guard as the first statement of `isKeyDown`.
A negative code is by definition unbound, so no caller can legitimately want the
pad-substitution branch for it. Then delete the now-redundant guard in
`Engine.shouldToggleLiveCapture` (keep the null check) and **delete the false
comment**. Pin it: assert an unbound P1_B is not reported held while the pad's
rewind button is down with `LIVE_REWIND_KEY` unbound.

### 3. A documented claim about separator-only values is wrong

`CONFIGURATION.md:557-558` says a separator-only value (`"+"`, `"++"`) is
unbound. `KeyChord.parse("+")` is indeed unbound, but in a real config it is a
non-empty *unresolvable* value, so it falls back to the binding's registered
default — which is exactly what the next bullet says happens instead. The two
bullets contradict each other.

Decide which behaviour is right, then make the documentation match the code.

### 4. Fourth finding

Truncated from the notification. It is in the output file above; work it from
there rather than from this summary.

## How to work this

Every finding is small and precisely located. Fix each with a RED/GREEN
regression test, and **verify RED by reverting the fix**, not by assuming.

Then, at the true head:

```bash
mvn -Dmse=off -Ds1.rom.path=s1.gen -Ds2.rom.path=s2.gen -Ds3k.rom.path=s3k.gen test
mvn -Dmse=off package -DskipTests
git checkout -- docs/status/rewind-round-trip-gaps.md
```

Confirm the reviewed head and the verified head are the same commit. A previous
round reported a suite run two commits behind, which is how a batch of these
reached review as "done".

ROM symlinks are already present in the worktree.

## The pattern to watch for

Three rounds of fixes have now each introduced a new defect of the same family:

1. Round 1 applied fixes **more broadly** than the problem — all sixteen overlay
   toggles instead of the two keys that actually collided; every `KEY` binding
   instead of the chord-aware ones.
2. Round 2 applied one fix **more narrowly** than its problem (`isKeyPressed` but
   not `isKeyDown`) and another **more broadly** (promoting the clipboard copy
   above its gate).
3. Every round wrote a confident comment or changelog line asserting the new
   behaviour was safe, which the following round disproved.

Before changing a keystroke's behaviour, check what else is bound to the keys and
modifiers involved. `RIGHT_SHIFT` is P2 jump; `RIGHT_CONTROL` is P2 Start. Both
have already caused a finding. And do not write "this is guarded elsewhere"
without opening every call site and listing them.

## Merge

Branch naming and trailers per `CLAUDE.md`. Merging into `develop` requires a
staged `README.md` update summarising the branch in the release-log section.
`develop` has moved during this work — fetch and integrate `origin/develop`
before merging, and verify the tree you push is the tree you tested.

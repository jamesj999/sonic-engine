# Raiscan's Thoughts on 0.6

0.6 is a bit of a beast.

I might have gone too far in a few places.

These are my thoughts on the work behind 0.6, rather than another exhaustive
changelog. For the feature list and current release status, see the
[0.6 changelog](../../CHANGELOG.0.6.md) and [release summary](v0.6-release-summary.md).

## Traces

### The hunt for repeatable, testable improvement

Near the end of 0.5 development, I started dabbling with an idea: what would
happen if you recorded the game inputs from Sonic 1 on an emulator and played
them back in OpenGGF? If we could know when our engine failed to reproduce the
original game, and how, could an LLM work through simple issues in the background?

We began calling these recordings and their comparison data “traces”. The first
setup used BizHawk to play an input recording while a Lua script collected
player positions, actions, and other state into CSV and diagnostic files. The
working loop looked something like this:

1. Create a test that plays the recorded inputs in OpenGGF.
2. Run it headlessly and compare engine state with the ROM recording each frame.
3. Report discrepancies, starting with the first divergence.
4. Have an agent investigate the cause against the disassembly and test a fix.
5. Check that the fix improves the comparison without breaking other behavior.
6. Commit a justified improvement, then repeat.

The loop was crude, slow, and expensive on inference. Some things were fixed
easily; others produced desyncs that looked random until we understood the state
or timing we had failed to model. A report telling you that Sonic is one pixel
too far left is useful, but it does not tell you whether the mistake happened
this frame or several hundred frames ago.

**For 0.6, we upped our game.**

We now have a multi-game capture system using a native headless BizHawk harness.
It records player and sidekick state, animation, object and level diagnostics,
execution timing, and loading/decompression boundaries. The recording and probe
tools now live in [TraceChaser]. OpenGGF retains the replay and comparison side.
This has helped us make hundreds of accuracy fixes across the games.

It also helped us make a few mistakes more efficiently. A test can agree with a
recording for the wrong reason. Some fields were originally just printed in a
report, not checked. Some fixes worked for one movie and failed when the player
took a different route. Improving the measuring equipment became a sizeable
part of the job.

The rule now is that recorded gameplay state is comparison evidence, not a way
to steer the engine into agreement. Recorded hardware timing has a narrow,
separate contract for matching work the engine has already submitted; it cannot
supply player positions, rings, or other gameplay values.

| Trace feature | 0.5 | 0.6 |
| --- | --- | --- |
| Coverage | S1 GHZ1, MZ1, and eight credits-demo replay classes | S1, S2, S3K; levels, character routes, stage detours, segmented runs, and continuous chains |
| Capture | BizHawk Lua scripts and historical retro tooling | Native headless BizHawk capture through TraceChaser |
| Format | Older CSV/metadata contracts | One v5 contract, validated provenance, compressed committed payloads |
| Comparison | Core player movement; richer fields largely diagnostic | Also animation, sidekicks, camera, inventory, selected auxiliary state, boundaries, and hardware timing |
| Lag handling | Inferred from repeated physics state | Explicit execution/timing information and bounded admission to existing ROM-modeled loops |
| Replay | Start a level and compare its recorded interval | Also carry a runtime across acts, zones, and represented stage/ending boundaries |
| Tools | Test reports | Reports, interactive visual replay, rewind, trace selection, and capture |
| Release checks | S1 replay tests excluded from normal runs | Dedicated profiles and a trace no-regression policy |

The important qualification: having a complete-run recording or chain test does
not mean that run passes. Known failures remain, including continuous-run
boundaries. For 0.6, existing green traces must stay green and known failures
must not worsen; clearing every frontier is work for later releases. The
[release trace policy](../status/trace-scope-release-6.md) owns that distinction.

## Physics and collision

The outcome of the trace work, manual testing, and fixing: a lot of fixes.

Many concern when a value is sampled, which object owns it, and when it is
cleared. Examples include:

- Jump presses come from the raw controller-poll edge, avoiding a false jump
  when a control lock releases.
- Airborne sensors reset their orientation instead of retaining a stale wall
  or loop mode.
- Hurt transitions update position, animation, velocity, and blinking at the
  correct points.
- Full-solid and top-solid objects retain their different edge-contact rules.
- Standing and pushing state expires with its object slot.
- Child objects execute and retire in their native slot order.
- Camera following, level events, boundary easing, and water movement follow
  the original ordering more closely.

This is where the apparently tiny differences become interesting. Fixing a
platform by moving Sonic a pixel can hide the real issue: perhaps the platform
ran at the wrong point in the object loop, or a previous object left a flag set.
The better fix models that cause and works wherever the same routine is used.

S1 and S2 now have much broader accuracy evidence, with some recordings matching
exactly. That is still evidence for the tested routes and fields, not a claim
that nearly every possible playthrough is identical. S3K has gained substantial
depth too, but complete campaigns for every character remain unfinished.

## Rewind

One of the things I love most about modern emulators is the ability to rewind.

Sonic's rings already make many mistakes survivable, but some sections are still
rough on newcomers. Being able to go back and try again adds an opt-in way to
adjust the difficulty, practise a tricky section, or try something silly without
having to earn another attempt from the start of the act.

Rewind is a deliberate addition beyond the original games. It grew out of the
trace debugger: if we could replay a deterministic section, could we move back
through it smoothly with bounded memory and little overhead?

That was the goal, rather than a promise of free, unlimited rewind. It turned
out that remembering where Sonic was is the easy part. Remembering which boss
children existed, who was standing on which platform, and what a palette cycle
was doing takes rather more work.

### How it reconstructs the past

During forward play, the engine records inputs and captures periodic checkpoints.
To restore an earlier frame, it loads a preceding checkpoint and simulates
forward with the recorded inputs:

```text
Checkpoint at 120 + inputs for 121 through 127 → restored frame 127
```

Intermediate states are cached, making subsequent backward steps cheaper.
Live play currently captures gameplay checkpoints every 10 ticks and audio
checkpoints every 60. A cold gameplay seek therefore needs at most nine forward
ticks after its nearest checkpoint. Trace playback has its own cadence.

On release, you continue from the restored point and new inputs replace the
abandoned future. In trace playback, the movie supplies the same inputs again,
which makes repeatedly examining a suspicious collision very convenient.

The system restores object relationships, rings, timers, RNG, camera and water,
terrain changes, zone events, and relevant palette/art state as well as the
players. Reverse sound comes from a bounded history of mixed audio; logical
audio state is restored so forward playback can resume. We are not running
the sound chips backward.

Live rewind is off by default. Enable it in the launch profile or configuration,
then hold **R** or the primary gamepad's **L1/LB**. Ctrl and Shift provide half
and double speed. There is also an optional VHS effect, because apparently I
could not leave a perfectly functional rewind button alone.

The default gameplay history is 60 seconds. Act, level, and mode changes reset
the buffer; it cannot rewind an entire campaign across those boundaries. Death
can be undone before the level reload commits. Stage support varies, menus are
outside the system, and audio becomes silent if you rewind beyond its retained
PCM history. The [rewind guide](../guide/contributing/rewind-system.md) explains
the ownership model and limits.

## Audio: another rabbit hole

Audio became one of the biggest parts of the release. It is not enough to play
the right song: the original sound drivers decide which effects get a channel,
what happens to the music underneath, how fades advance, and what comes back
after an extra-life jingle or a special stage.

We now compare driver state and chip writes against emulator captures, alongside
listening and gameplay checks. There was a painful lesson here: an earlier
audio programme produced audible regressions that its tests missed and was
withdrawn. Much of the subsequent work was rebuilt piece by piece against
better evidence. A green driver window is useful; it does not certify the
whole mixed output.

0.6 also gains two FM implementations: the accurate Java Nuked-OPN2 core and a
clean-room fast core. New configurations choose `fast`; `accurate` remains
available, and reference captures retain it. On the measured release-candidate
content, the fast core took roughly 0.17–0.18 ms per audio frame versus
0.94–0.99 ms for accurate. That is an audio measurement on those runs, not a
fivefold speedup for the whole game.

The fast core passes all 178 supported register scripts, but has documented
edge-case limitations. Full-game driver parity and release listening sign-off
remain open. Live mid-track switching between cores is future work.

## Recording and video capture

We support video capture now. **Shift+O** starts a recording and the same chord
stops it. The defaults are lossless FFV1 video and FLAC audio in an MKV file,
configured in `config.yaml`. Capture uses ffmpeg and records the game viewport,
including the sound of rewind, rather than the surrounding desktop.

There are other codec options, including DNxHR SQ with PCM audio for DaVinci
Resolve on Linux. DNxHR SQ is not lossless, so choosing that workflow trades
some video fidelity for editing compatibility. Resizing the captured viewport
stops the current recording and shows a notice.

Input recording is a separate feature. It stores controls and metadata for
playback inside the engine, with a recordings menu and desync reporting. A
video shows what happened; an input recording gives us something we can run
again. Both are useful when a bug report starts with “I did something weird
and then this happened”. See the [capture settings](../../CONFIGURATION.md#capture).

## Saves, controllers, and the bits around the level

It is easy to spend so long on a collision bug that the ordinary experience of
starting and continuing a game gets less attention than it deserves.

0.6 adds persistent saves and S3K-backed data select. S1 and S2 can borrow that
presentation while keeping their own progression. Save writes use temporary
files and atomic replacement where supported, with validation and recovery
handling. Progression writes run off the gameplay thread.

Controller support now feeds the same logical input layer as the keyboard,
recordings, and rewind. Continue screens have their own ROM-backed countdowns
and character sequences: S1/S2 restart from the act start, while S3K retains
its checkpoint. Special- and bonus-stage returns also preserve more of the
state you expect to find waiting when you get back.

## config.yaml, actually

Oh yeah! I moved us to YAML configuration. The filename is `config.yaml`, not
`config.yml`.

Old `config.json` files migrate automatically. The current format stores your
overrides rather than rewriting every default, and launch profiles can apply
game-specific choices without changing the global settings. Hopefully most
people notice the options getting easier to manage rather than the migration.

## Making room for all of this

Rewind, richer audio, and recording all cost time and memory, so performance
became another substantial workstream. We reduced repeated allocations in
audio, collision, rendering, and rewind; reused prepared art; moved screenshot
encoding and save writes off the frame; and prebuilt tilemaps for Angel Island's
terrain swap.

Some improvements were much less glamorous than a faster synthesizer. A
palette-write queue was drained in the windowed path but accumulated during
headless play. Two ROM readers could race over a shared file position during
loading. Those are exactly the kinds of problems a bigger, more heavily used
engine exposes.

We also learned when to undo an experiment. The managed test-session system
grew storage without a useful bound and was removed. Builds use direct Maven
again, with each worktree keeping its own output. Not every ambitious idea
earned a place in the release.

## Where that leaves 0.6

For me, the theme is being able to inspect more of what the engine is doing,
correct it with better evidence, and make the result more pleasant to play.
That includes the less visible work of getting objects, audio, saves, and
transitions to agree about who owns state and when it changes.

There is still plenty unfinished. The editor is experimental, S3K campaign
completion is ahead of us, and modding and multiplayer racing are later-line
work rather than 0.6 features. Even a large release needs somewhere to stop.

Hopefully the next retrospective starts with “this one was a sensible size”.

[TraceChaser]: https://github.com/OpenGGF/TraceChaser

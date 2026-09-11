# 0.6 capture, session, and prepared-load validation

## Scope and source

Source commit: `6f71fddf20f1316cbd37ca6c5c08121054b12abb`, based on
`4a5ae99c425b32024e18d503372549164bf93270`.

The initial base was `36e9d4707`. During validation, upstream added the drowning
countdown ownership/screen-position fix. The local repair was rebased onto that
update without conflicts; `git range-diff` classified the repair patch as
unchanged. Both full suites were then repeated against the updated base.

- Capture queue shutdown measures stalled frame progress. Finalization uses the
  encoder's own timeout, including a blocked buffered-stdin close. Cancellation
  kills ffmpeg before closing its input, and failure reporting waits boundedly
  for worker cleanup.
- Failed replay teardown still clears published session/world references,
  graphics bindings, and the next-run admission policy, preserving the original
  exception.
- Prepared loads require the matching loader, target, and mutation. Ordinary
  loads, reset, and rewind discard them. Workers use captured bootstrap and
  character inputs with their bound ROM. Art and tilemap assembly run on the
  frame thread; ROM decoding remains asynchronous.

The tilemap ordering is validated for the current AIZ fire handoff. A future
transition that changes wrapping or layout during subsequent initialization
must invalidate the prebuilt tilemaps. Existing ffmpeg process timeouts remain;
this repair does not make filesystem operations interruptible.

## Completed ordinary-suite evidence

Java 21.0.11, Maven 3.9.16; ROM SHA-1 identities matched the repository's S1
REV01, S2 REV01, and locked-on S3K references. All commands used `-Dmse=off`.

| Tree | Maven reported tests | Failures/errors | Skips |
| --- | ---: | ---: | ---: |
| Updated base `4a5ae99c4` | 17,093 | 0 / 0 | 45 |
| Candidate `6f71fddf2` | 17,115 | 0 / 0 | 45 |

Comparison of XML testcase identities found 22 additions, no removals, and no
changed outcomes or skip reasons. Maven's console totals include nested-suite
summaries; the corresponding XML suite totals are 16,991 and 17,013. Both
reporting views increase by 22.

The 45 unchanged skips cover unavailable GL, opt-in probes/soaks, missing local
audio references or explicitly configured oracle inputs, and the pre-existing
CPZ spin-tube assumption. No ROM-path skips occurred. These ordinary runs are
not a complete trace-replay fleet or a graphical release smoke test.

The commands below used these common ROM arguments:

```bash
# Resolve this in the main checkout before changing to the candidate worktree.
validation_rom_root=$(git rev-parse --show-toplevel)
rom_args=(
  "-Dsonic1.rom.path=${validation_rom_root}/s1.gen"
  "-Dsonic2.rom.path=${validation_rom_root}/s2.gen"
  "-Ds3k.rom.path=${validation_rom_root}/s3k.gen"
)
# Main checkout at 4a5ae99c4:
LUA_BIN=lua5.4 mvn -Dmse=off "${rom_args[@]}" \
  -Dopenggf.surefire.reports=target/release-lifecycle-updated-baseline test -B
# .worktrees/ai-release-lifecycle-fixes at 6f71fddf2:
LUA_BIN=lua5.4 mvn -Dmse=off "${rom_args[@]}" \
  -Dopenggf.surefire.reports=target/release-lifecycle-updated-candidate test -B
```

## Focused regressions and review

Before fixes, regressions reproduced five failed session-close/mode-switch
paths, two ordinary-load paths consuming a transition build, healthy capture
finalization/draining being cut short, and cancellation blocked on a real
subprocess pipe. Review found a further blocked-EOF-close timeout gap; its
regression also failed before the repair.

The combined focused run passed 180 tests with no skips, covering session and
rewind access, capture control and real ffmpeg media inspection, prepared-load
ownership, AIZ transition byte equivalence, and the four required S3K test pins.
The subsequent EOF-close repair passed all 23 tests in `FfmpegEncoderCommandTest`
and `EncoderSinkTest`; the final ordinary suite includes that repair as well.
Independent source review covered session cleanup, capture lifecycle races,
and prepared-load ownership/worker isolation. `git diff --check` passed.

## Structural guards

Separate JVM runs passed on both updated trees: 613 tests each, no failures,
errors, or skips. Lua 5.4.9 was selected explicitly for the forwarder guard.

```bash
# Main checkout at 4a5ae99c4, using rom_args above:
LUA_BIN=lua5.4 mvn -Dmse=off -Pguards "${rom_args[@]}" \
  -Dopenggf.surefire.reports=target/release-lifecycle-updated-baseline-guards test -B
# Candidate at 6f71fddf2:
LUA_BIN=lua5.4 mvn -Dmse=off -Pguards "${rom_args[@]}" \
  -Dopenggf.surefire.reports=target/release-lifecycle-candidate-guards test -B
```

This record covers pre-integration validation. The repair was integrated at
`d9136a15f`; the [September 10 candidate validation](2026-09-10-release-candidate.md)
records subsequent graphics-enabled, packaged-app, and trace verification on
`d2f1331b3`, including the repair and later gameplay fixes.

# Super Emerald sanctuary routing recovery

## Scope and cause

The work on `feature/ai-s3k-super-emeralds` was already represented in
`develop`: twelve of its thirteen commits have identical patch equivalents;
`30a86fb84b` and `eb13f76d7d` differ only in portable documentation paths.
The runtime symptom was a later destination/profile disconnect, not absent
feature commits.

`SSEntryFlash_GoSS` / `loc_618AC` writes `$1701` and restarts the level.
The engine's legacy sanctuary alias is `$1601`. The resource profile, camera
and palette setup, stock object factories, and scroll provider did not all
recognize the real giant-ring destination. The raw `$1701` load therefore
omitted the sanctuary controller and seven emerald pedestals.

The repair selects the existing sanctuary owners for both identities.
`ScreenEvents` assigns `$1701` to `HPZS_ScreenInit`/`HPZS_BackgroundInit`
and their event routines; the paired `$1700` Death Egg boss act remains
outside the sanctuary profile. No feature-branch gameplay was reapplied over
subsequent development work.

## Reproduction

Worktree: `.worktrees/feature-ai-super-emeralds-develop`.
`OPEN_GGF_ROOT` below denotes the absolute main-checkout directory.
Base: `10f844594b` with `TestS3kHpzSanctuaryHeadless` parameterized over
zones `$16` and `$17`, act index 1.

```sh
mvn -Dmse=off -Dtest=TestS3kHpzSanctuaryHeadless \
  "-Ds3k.rom.path=${OPEN_GGF_ROOT}/Sonic 3 & Knuckles (W) [!].gen" \
  test -B
```

Completed result: 6 tests, 1 failure, 1 error, 0 skips. The `$17` traversal
case expected seven pedestal positions but found an empty list; the `$17`
ceremony case could not find `HPZSSEntryControlObjectInstance`. The `$16`
versions passed. The no-emerald unlock test alone did not distinguish the
broken room from a working room.

After the production repair, the same six lifecycle cases passed, along
with the then-current profile and scroll tests (16 total, no failures,
errors, or skips). The committed regression coverage additionally checks
raw-destination event/profile selection and background dispatch, including
the paired boss-act fallback.

## Verification environment

Java 21.0.10 (SAP), Maven 3.9.16, macOS arm64. All three root ROMs were
verified against the repository CRC32/SHA-1 identities and supplied through
absolute test properties. The first ordinary-suite attempt stalled in
`GlReadPixelsGrabberTest` after macOS rejected the sandboxed display-service
connection. That JVM was terminated; the incomplete run is not suite evidence.
The replacement run uses macOS display access.

This recovery uses ROM-backed lifecycle and scroll assertions. It does not
claim a new pixel comparison against BizHawk or resolve the separately
recorded sanctuary screen-shake / plane-fill limitations.

## Committed verification

Implementation commit: `56583f2dba`.

The ordinary suite uses `mvn -Dmse=off test -B` with all three absolute ROM
properties shown by the repository's root filenames. The guard invocation
uses the same ROM properties plus `-Pguards` and
`LUA_BIN=/private/tmp/openggf-slicer-lua/lua-5.4.8/src/lua` in the separate
`.worktrees/super-emeralds-guards` checkout at the same commit, with its own
build output and a fresh JVM.

Completed guard result: **656 tests, 1 failure, 1 error, 0 skips**. The failure
is the Python release-evidence test's macOS `/var` versus `/private/var`
checkout-path normalization. The error is the PowerShell forwarder test's
missing `pwsh` executable. The remaining 654 guard tests passed.

The focused sanctuary cases also passed in the ordinary run: 6 production
lifecycle cases, 7 resource/event-profile cases, and 5 scroll cases. Required
S3K integration checks passed with no skips: AIZ intro (8), level loading
(34), bootstrap resolver (6), and decoding (3). Sanctuary graph rewind and
palette ownership checks passed too.

Baseline comparisons use `.worktrees/super-emeralds-control` at the original
`10f844594b`, with separate Maven output. Explicit `-Dtest=` selections cover
the failing classes only; these runs are baseline diagnosis, not substitute
full-suite coverage. Completed baseline groups reproduced donation/ROM-path,
GLSL-version, sample-mod packaging, FBZ route/matrix, hook-policy, missing
`/usr/bin/bash`, and audio-reference failures. Failure messages were compared
after normalizing worktree paths, randomized temporary names, and elapsed
build times, rather than comparing only class names or counts.


Completed ordinary-suite result at `56583f2dba`: **20,294 tests, 27 failures,
8 errors, 97 skips**. All 35 failing/erroring cases reproduce at `10f844594b`
with matching normalized diagnostics. No new failure was identified. The two
guard failures also reproduce on that baseline in focused `-Pguards` runs.
Neither broad run is reported as green.

The 97 skips were inspected. They include tests that still resolve configured
`s1.gen`/`s2.gen` or donor paths independently of the explicit ROM properties,
optional generated audio/BizHawk references and captures, opt-in benchmarks,
EGL/visual-environment availability, and a spin-tube capture/release assumption.
The sanctuary and required S3K checks listed above had no skips. These broad
suite limitations are not evidence of full visual or audio parity.

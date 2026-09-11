# ICZ Star Pointer parent touch-phase audit

Date: 2026-07-26
Branch: `bugfix/ai-trace-int-icz-16361`
Baseline: `5222ff8da9b1ffaf3b1f79bdb477deb57b47c6c6`

## Reproduction

The ICZ complete-run replay reproduced the assigned baseline exactly:

```bash
mvn -q '-Dsurefire.argLine=-Xshare:off -Xmx4g' \
  -Ds3k.rom.path=s3k.gen -Dtrace.context.radius=30 \
  -Dtest=com.openggf.tests.trace.s3k.TestS3kIczCompleteRunTraceReplay test
```

Result: 32 errors, 0 warnings; first error f16361 `y_speed`,
expected `0x0093`, actual `-006D`. Position, subpixels, X speed, ground speed,
angle, status, and ride slot otherwise matched.

Temporary diagnostics disproved the collapsing-platform transition hypothesis:
the ridden platform was already fragmented and in its ordinary post-collapse
solid-stay pass. Those diagnostics were removed after triage.

## Native oracle

The committed probe `tools/bizhawk/probes/icz_f16361_yvel_probe.lua` delegates
all lifecycle control to `probe_runtime.lua`. The runtime enables invisible
fast emulation and registers the namespaced hooks only after the semantic gate
matches gameplay mode, ICZ1, and the requested frame window.

```bash
env BIZHAWK_HOME=docs/BizHawk-2.11-linux-x64 \
  OGGF_START=154450 OGGF_STOP=154500 \
  OGGF_OUT=/tmp/icz_f16361_yvel_probe-rerun.txt \
  tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/probes/icz_f16361_yvel_probe.lua \
  src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2 \
  'Sonic and Knuckles & Sonic 3 (W) [!].gen'
```

Probe SHA-256:
`8fb19c277edf4016746a3321b0d97f724da747a466e2ff2c5b7ebe4624220fd8`.
Output SHA-256:
`2165bd8d814a6f22207ed60d557046e10bdbe0e60660d47139747fb6b0202a1a`.
The corrected probe completed with exit 0, produced 151 lines, and reproduced
the earlier corrected-run output byte for byte.

At emulator f154478 / gameplay counter `$3FDA`, corresponding to trace f16361:

```text
kind=yvel-hi pc=0115E6 a0=B000 ... pos=0CF3,0371 vel=0452,FF93
kind=angle   pc=00ED78 a0=B000 ... pos=0CF7,0370 vel=044A,FF93 angle=FC
kind=yvel-hi pc=01020C a0=B000 a1=B172 ... pos=0CF7,0371 vel=044A,FF93
```

The following frame starts at Y velocity `$0093`. PC `$1020C` is
`Touch_EnemyNormal.bounceplayerdown`, whose `addi.w #$100,y_vel(a0)` changes
`$FF93` to `$0093`. A1 `$B172` identifies dynamic SST slot 5. The committed
aux state changes that slot from Star Pointer active code `$0008BEA6` at
X `$0D07`, Y `$0360`, to explosion code `$0001E66E`, confirming the parent
enemy was destroyed by this contact.

## Root cause

`Obj_StarPointer` active routines call `MoveSprite2` and then
`Sprite_CheckDeleteTouch` (`docs/skdisasm/sonic3k.asm:190785-190810`).
The collision list stores an SST pointer. Later, `Touch_Loop` reads the
object's live `x_pos` through that pointer
(`docs/skdisasm/sonic3k.asm:20656-20693`).

At f16361 the ROM's live Star Pointer X is `$0D07`. With collision radius 8,
its left edge is `$0CFF`; Sonic's left touch boundary is `$0CEF`, producing
exactly `$10`, which the ROM accepts because it rejects only values above the
player width. The engine instead used cached pre-update X `$0D08`, producing
`$11` and rejecting the contact by one pixel.

The fix is the existing object semantic contract:
`StarPointerBadnikInstance.usesCurrentTouchResponseState()` returns true.
There is no trace, frame, route, zone, or shared-runtime carve-out.

## Verification

The focused capability-contract test was observed red before the production
change and green afterward. The complete ICZ replay then reported 31 errors,
0 warnings; the frontier advanced from f16361 to f24140 `rings` (expected 3,
actual 2). The remaining ring and animation differences are later independent
frontiers.

Integration verification ran the combined Star Pointer, probe-contract,
rewind coverage, rewind round-trip, and architectural-source command with the
rewind guards' correct packages. Maven exited 0 with all 89 selected tests
passing (14 + 4 + 1 + 4 + 66), with zero failures, errors, or skips:

```bash
mvn -q \
  '-Dtest=com.openggf.game.sonic3k.objects.badniks.TestStarPointerBadnikInstance,com.openggf.tests.TestBizhawkProbeContractGuard,com.openggf.game.rewind.coverage.TestRewindCoverageGuard,com.openggf.game.rewind.coverage.TestRewindRoundTripProbe,com.openggf.tests.TestArchitecturalSourceGuard' \
  test
```

MSE's session aggregate also displayed known-red closure traces from earlier
runs; those were not part of the exact Surefire selector above and do not
change the 89 requested XML results. Running the round-trip probe rewrites
`docs/status/rewind-round-trip-gaps.md`; integration restored that generated
file to `HEAD`, and it is not part of this task's changes.

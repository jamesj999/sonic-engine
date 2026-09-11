# MGZ lost-ring slot frontier audit

Date: 2026-07-26

Base: `3eae9f722e3b368afde0aa762e5df3e0da6df4b5`

Scope: MGZ standalone and complete-run traces only. LBZ was not investigated.

## Result

No safe fix was made. Both first errors are downstream of earlier SST inventory
differences. Altering lost-ring collision, bounce cadence, or phase calculation
would model the trace rather than ROM state.

## Reproduction

Both runs require a larger Surefire heap; the default 1 GiB fork exhausts its
heap and is not a valid trace result.

```text
mvn -Dtest='com.openggf.tests.trace.s3k.TestS3kMgzTraceReplay#replayMatchesTrace' \
  -Dsonic3k.rom.path=s3k.gen -Dsurefire.argLine='-Xshare:off -Xmx6g' test
=> 16 errors; first f23561 rings, ROM 0 / engine 1

mvn -Dtest='com.openggf.tests.trace.s3k.TestS3kMgzCompleteRunTraceReplay#replayMatchesTrace' \
  -Dsonic3k.rom.path=s3k.gen -Dsurefire.argLine='-Xshare:off -Xmx6g' test
=> 27 errors; first f28398 rings, ROM 2 / engine 1
```

Reports were archived outside the worktree at:

- `/tmp/trace-mgz-remaining-3eae9f722/standard`
- `/tmp/trace-mgz-remaining-3eae9f722/complete`

The deterministic archive was built with:

```bash
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -cf /tmp/trace-mgz-remaining-3eae9f722.tar \
  -C /tmp trace-mgz-remaining-3eae9f722
```

It is 61,440 bytes and has SHA-256
`f267d28ac5157867a5045a4023166804220972f024dc0c1ed45aac592b0dcbf1`.
The relied-on files are independently hashed:

| Run | File | SHA-256 |
| --- | --- | --- |
| standard | `s3k_mgz1_report.json` | `dab708c7340da068ff6136ecc2590d65176b2596b09a6670cf22b4b681633393` |
| standard | `s3k_mgz1_context.txt` | `5803c4f78e700f134fda596c994974b50d1a8970a3509db4420a81d809762137` |
| standard | Surefire XML | `34452b539174c9f782cbe4b1031819f35fea5a67d90771890b1dde06bc09a268` |
| standard | Surefire text | `febd1853e7952c4235fb17e773ac32411008aab7e135a541b56dda089c8822cb` |
| complete | `s3k_mgz1_report.json` | `097ad75e142e1c86fb8531f348968afb54549c3e439f2b1f427a407808fd4a22` |
| complete | `s3k_mgz1_context.txt` | `befc677be03c47beb758a0c9a174ce5aa0b73c2eaed330a742256b2e55d6cb17` |
| complete | Surefire XML | `8fc89f1a9cb41d380217d3272a27401a27d2b263eec1278e7dadbb2dd6f8137c` |
| complete | Surefire text | `add2c452a38e35058d314b20d16d955aaf684e9e02fc9fbcd05c211cc039ae9a` |

The ROM SHA-1 was
`cfbf98c36c776677290a872547ac47c53d2761d6`.

## Evidence method

ROM evidence comes from committed fixture files at the audited revision:

| Fixture | SHA-256 |
| --- | --- |
| `traces/s3k/mgz/aux_state.jsonl.gz` | `bf11de118396098311ba6b4371ee11bb516ac05619bc6a8db5740bea20903fdc` |
| `traces/s3k/mgz/physics.csv.gz` | `fef4b20d50e089fc3fa361578cd680046480e146d64c527b6d90676aa3cafb7d` |
| `traces/s3k/mgz_completerun/aux_state.jsonl.gz` | `297d83f3f5dce9604d3aee8bf795e46879eb0c851c4aeda3f30ac9666f7d1dbe` |
| `traces/s3k/mgz_completerun/physics.csv.gz` | `3d4639dfab6970b19069485147c367aa1f252296cdff35232256285bea23c0c9` |

The exact ROM object-state extractions were:

```bash
gzip -cd src/test/resources/traces/s3k/mgz/aux_state.jsonl.gz |
  jq -r 'select(.event=="object_state" and
    ((.frame==23463 and (.slot==15 or .slot==16 or .slot==17 or .slot==18)) or
     ((.frame>=23555 and .frame<=23562) and .slot==16))) |
    [.frame,.vfc,.slot,.object_code,.routine,.x,.y,.status,.subtype] | @tsv'

gzip -cd src/test/resources/traces/s3k/mgz_completerun/aux_state.jsonl.gz |
  jq -r 'select(.event=="object_state" and
    ((.frame==28282 and (.slot==7 or .slot==8 or .slot==11 or .slot==12)) or
     ((.frame>=28396 and .frame<=28398) and .slot==11))) |
    [.frame,.vfc,.slot,.object_code,.routine,.x,.y,.status,.subtype] | @tsv'
```

Engine inventory and nearby-object state were captured by temporarily adding
the following 18-line diagnostic before `binder.compareFrame` in the S3K path
of `AbstractTraceReplayTest`:

```java
String diagnosticFrameProperty = System.getProperty("trace.diag.frame");
if (diagnosticFrameProperty != null
        && comparisonExpected.frame() >= Integer.parseInt(diagnosticFrameProperty)) {
    var objectSnapshot = GameServices.level().getObjectManager().rewindSnapshottable().capture();
    throw new AssertionError("TRACE_DIAG frame=" + comparisonExpected.frame()
            + " index=" + driveTraceIndex
            + " activeSlots=" + objectSnapshot.slots().stream()
                    .map(entry -> entry.slotIndex() + ":"
                            + entry.className().substring(entry.className().lastIndexOf('.') + 1))
                    .toList()
            + " childSlots=" + objectSnapshot.childSpawns().stream()
                    .map(entry -> Integer.toHexString(entry.parentSpawn().x()) + ","
                            + Integer.toHexString(entry.parentSpawn().y()) + "="
                            + java.util.Arrays.toString(entry.reservedSlots()))
                    .toList()
            + " " + engineDiag.format());
}
```

The exact unified patch is `/tmp/mgz-engine-diagnostic.patch`, SHA-256
`b9ee802a55c3a09b15acc3efc6e34700fe828cd27f02b14f966616879a45c816`.
It was applied only over revision `3eae9f722`; the test source after reversion
has SHA-256
`34e0914cc2afa17d8ab09a408960dbdc3f6c6b6a4eeea32aaad8c78785880618`
and `git diff --exit-code -- <test-source>` succeeds.

The engine commands used the same focused test command shown above plus,
respectively, `-Dtrace.diag.frame=23463`, `23555`, `23556`, `23561`, and
`28282`. Their XML/text evidence and patch form deterministic archive
`/tmp/mgz-engine-diagnostics-3eae9f722.tar` (133,120 bytes), SHA-256
`6a4e92ce13094b57259b484c415c63b2f7bbae60ff0b61527402afa34931f264`.
The individual text hashes are:

| Capture | SHA-256 |
| --- | --- |
| standard f23463 | `5d21bbce001dd43d98c19207dc88cec37c699d1d9f6be6c009a5ab81a3250294` |
| standard f23555 | `08b7d8458d52becc823794c95d766c2dcb70c02db98eb09f09fe8a94f8a1521f` |
| standard f23556 | `67e1fac2de5642cc2e6085ccb4612e3b55ab46e8c281c12c7183af88442ebe7e` |
| standard f23561 | `91ab33767277b5378133484c0d912d208219b797bc0bc548f41e846cdca8cc1a` |
| complete f28282 | `e116961fcf473b87fbd331c6ae6ea7c2a146a20c137da6d1289cb86b4e33bb4a` |

## Standalone trace

The corresponding ROM `Obj_Bouncing_Ring` uses slot 16; the engine uses slot
15. Position and integer velocity initially agree. The engine probes the floor
at f23555 and bounces on f23556, while the ROM continues to y=`$078D` on
f23556 and bounces on f23557. The engine therefore reaches the player and
enters collection routine 6 one frame early.

This cadence is slot-owned. `Process_Sprites` initializes `d7` to the last SST
index and decrements it per entry. `Obj_Bouncing_Ring` adds `d7` to
`V_int_run_count+3` before its every-eighth-frame floor test
(`docs/skdisasm/sonic3k.asm:35549-35616,35965-35980`). The engine's equivalent
phase is consequently `$5E` in slot 15 versus the ROM's `$5D` in slot 16.

| Frame | Source | Slot/object | Position or state | Phase |
| --- | --- | --- | --- | --- |
| 23463 | committed ROM aux | 15/17/18 `loc_3406E` | `$1DB8,$06E0` | n/a |
| 23463 | committed ROM aux | 16 `Obj_Bouncing_Ring` | `$1D4A,$0760`, routine 2 | `$5D` from `109 - 16` |
| 23463 | engine diagnostic | 16/17/19 platform reservations | parent spawn `$1DB8,$06E0` | n/a |
| 23463 | engine diagnostic | 15 `LostRingObjectInstance` | `$1D4A,$0760`, velocity `$00C4,$FC2C` | `$5E` reported |
| 23555 | committed ROM aux | 16 ring | y=`$0788`, routine 2 | `$5D` |
| 23555 | engine diagnostic | 15 ring | y=`$0788`, y-velocity=`$04CC` after floor response | `$5E` |
| 23556 | committed ROM aux | 16 ring | y=`$078D`, still descending, routine 2 | `$5D` |
| 23556 | engine diagnostic | 15 ring | y=`$0788`, y-velocity=`$FC55` upward | `$5E` |
| 23557 | committed ROM aux | 16 ring | y=`$0788`, first upward position | `$5D` |
| 23561 | committed ROM aux | 16 ring | y=`$0779`, routine 2/uncollected | `$5D` |
| 23561 | engine diagnostic | 15 ring | y=`$077A`, `col=true`, rings=1 | `$5E` |
| 23562 | committed ROM aux | 16 ring | routine 6/collected | `$5D` |

The slot difference predates the ring spill. At f23463 the ROM has
`loc_3406E` visual helpers in slots 15, 17, and 18. The engine's three
`MGZSwingingPlatform` reservations are in slots 16, 17, and 19. The ROM
platform routine really allocates one independent helper with
`AllocateObjectAfterCurrent` (`docs/skdisasm/sonic3k.asm:70459-70530`);
the engine correctly represents that pressure, but the helper slots were
chosen under an already-different earlier inventory.

## Complete-run trace

The corresponding ROM ring uses slot 11; the engine uses slot 12. At the spill
the engine's slot 11 is occupied by `Sonic3kPathSwapObjectInstance`, so the
second `AllocateObjectAfterCurrent` result differs before any ring physics or
touch response runs. The first ring agrees in slot 7.

The ROM hurt path allocates the owner with `AllocateObject`, then
`Obj_Bouncing_Ring` allocates the remainder after the owner
(`docs/skdisasm/sonic3k.asm:21065-21088,35549-35616`). Engine and ROM therefore
agree on the allocation algorithm; the unresolved datum is the earlier birth
or lifetime edge that leaves the path-swap SST live in engine slot 11 at this
point.

| Frame | Source | Slot | Object/state |
| --- | --- | --- | --- |
| 28282 | committed ROM aux | 7 | first `Obj_Bouncing_Ring`, `$2575,$08A4` |
| 28282 | engine diagnostic | 7 | first `LostRingObjectInstance`, `$2575,$08A4` |
| 28282 | committed ROM aux | 11 | second `Obj_Bouncing_Ring`, `$2576,$08A4` |
| 28282 | engine diagnostic | 11 | `Sonic3kPathSwapObjectInstance` |
| 28282 | engine diagnostic | 12 | second `LostRingObjectInstance`, `$2576,$08A4` |
| 28396 | committed ROM aux | 11 | ring y=`$08E6`, routine 2 |
| 28397 | committed ROM aux | 11 | ring y=`$08E3`, routine 2 |
| 28398 | committed ROM aux | 11 | routine 6/collected |

## Rejected fixes and next evidence

- Do not offset the global lost-ring phase: the two traces have opposite
  allocation shifts, and phase is correctly derived from native SST order.
- Do not enlarge or cache the lost-ring touch box: collection timing is a
  consequence of the differing slot-owned floor probe.
- Do not force ring slots by route, frame, or zone.
- Do not move all swinging-platform reservations by one slot: one of the
  three already agrees, and their differing slots reflect earlier inventory.

The next investigation needs a stage-gated SST lifecycle capture around the
birth of the three standalone swinging platforms and around the complete-run
path-swap birth/unload edge. Any BizHawk capture must use
`tools/bizhawk/diag_template_fast.lua`, enable invisible/turbo emulation, defer
memory hooks until MGZ and the narrow frame window are active, and tear them
down immediately afterward.

# Sonic 3&K pre-consumption request-producer audit

**Date:** 2026-09-02

**Scope:** point-in-time audit of the Sonic 3&K source boundary needed to resolve the
service-128 / source-frame-242 producer-input limitation in the bounded Sonic/Tails audio
oracle.

**Status:** design and implementation prerequisite only; no capture, fixture, producer binding,
or comparison authority was created by this audit.

**Authority result:** `REFERENCE_LIMITATION` / `producer_input` and
`FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE` remain in force.

## Conclusion

The missing request can be observed before the Z80 consumes it. `Play_Music` stops the Z80,
writes D0.b to `zMusicNumber` at Z80 RAM `$1C0A`, and releases the Z80. The installed native
observer's generic ABI 4 service, snapshot, opcode-proof, and action-7 primitives are sufficient
in principle to bracket that write at exact M68K PCs `$1358` through `$1374` and snapshot the
one-byte mailbox while the bus is still held. The source-owned request at the known diagnostic
boundary is `$FE` (`cmd_StopSEGA`), but a production producer must establish that value from the
native snapshot. It may not infer it from source frame 242, service 128, the empty pre-frame
mailbox, the later 84-write stop burst, or the fact that the SEGA PCM loop ends.

No production authority implements that observation. The committed submission manifest,
profile, raw-v2 sink, and tests are deliberately unbound test shapes. They have no production
movie identity, capability, runner, CLI route, installation verification, or fixture publication
path. Moreover, copying their two hooks into the production manifest would be unsafe: the native
hook selector faults whenever it reaches a watched PC but cannot select exactly one hook for the
current active kind. The test manifest covers only the target kind-8-to-kind-13 topology, so an
ordinary `Play_Music` invocation in another active context can fail the observer.

The required authority is a new, distinct Sonic/Tails diagnostic profile for the fixed 5,400-row
prefix. It must not reuse or weaken the Knuckles complete-run profile. It needs a full closed
manifest, capability, runner, raw schema, duplicate-capture extractor, and strict comparison-only
Java consumer and independent OpenGGF producer. Existing installed core bytes can participate
only after exact installation verification and a fresh identity cascade; possessing the old core
hash cannot authorize a new manifest, harness, movie interval, or fixture.

## Audit boundaries

This audit read the pinned source and tooling at OpenGGF commit `fe5d98fb2` and TraceChaser
commit `98b4afaf91bb799a6b79aee22625b14c33113e66`. It did not start Mono, EmuHawk, BizHawk,
GPGX, or any capture process. It did not modify TraceChaser, a raw stream, a fixture, a
capability, an executable, or the audio frontier.

Primary evidence:

- `docs/skdisasm/sonic3k.asm:1493-1497` owns `Play_Music`.
- `docs/skdisasm/sonic3k.macros.asm:93-103` owns the stopped-Z80 bus interval.
- `docs/skdisasm/Sound/Z80 Sound Driver.asm:4370-4400` owns the SEGA-PCM mailbox poll.
- `docs/skdisasm/sonic3k.constants.asm:1455` defines `cmd_StopSEGA = $FE`.
- `docs/architecture/research/audio/2026-08-30-s3k-sound-driver-routine-map.md:59-117`
  maps the mailbox and stop-all state.
- `docs/architecture/designs/audio/2026-08-30-s3k-audio-oracle-design.md:90-124` and
  `:220-225` define the current bounded oracle and its missing producer-side observation.
- `docs/architecture/designs/audio/2026-08-31-sound-driver-production-owned-validation-design.md:381-420`
  separates the Knuckles and Sonic/Tails identities and fences fixture-assisted input.

## Exact `$1358` to `$1374` stopped-bus boundary

The shipped M68K path is:

```asm
Play_Music:
        stopZ80
        move.b  d0,(Z80_RAM+zMusicNumber).l
        startZ80
        rts
```

`stopZ80` writes `$0100` to the Z80 bus-request port and waits until the Z80 acknowledges the
stop. `startZ80` writes zero to release it. The exact native proof points are:

| Field | Begin | End |
|---|---:|---:|
| M68K PC | `$1358` (4952) | `$1374` (4980) |
| Opcode | `33fc010000a11100` | `33fc000000a11100` |
| Meaning | bus-request instruction before acquisition loop | bus-release instruction before execution |
| Target topology | begin child kind 13 under active kind 8 | end active kind 13 |
| Snapshot | none | range `$1C0A..$1C0B`, exactly one byte |

The `$1358` proof begins before the bus-request instruction; the following loop proves the Z80
has stopped before the mailbox store executes. At `$1374`, the mailbox store is complete and the
bus-release instruction has not executed, so the one-byte `$1C0A` snapshot is definitely taken
while the Z80 remains stopped. The request observation is therefore the bounded native ancestry
and ordering:

```text
kind-8 SEGA-PCM iteration active
  -> M68K $1358 exact opcode / kind-13 begin
  -> bus stop acknowledged
  -> move.b D0,$A01C0A
  -> M68K $1374 exact opcode / one-byte snapshot
  -> kind-13 completion
  -> bus release
  -> Z80 resumes SEGA-PCM polling
```

At the diagnostic boundary the snapshot byte is `$FE`. After release, `zPlaySEGAPCM` reads
`zMusicNumber`, compares it with `cmd_StopSEGA`, and exits the PCM path. In the locked-on S&K
path the `$FE` mailbox residue remains for the following update path, which dispatches the
`$E6..$FE` stop-all command to `zStopAllSound`. That later stop burst is corroborating
consequence evidence only. It is not a substitute for the stopped-bus snapshot.

The existing v1 fixture's source frame 242 has no pre-frame mailbox byte but does have the later
84-write stop burst. This is exactly why row sampling and output inference are insufficient: the
request is written and first consumed inside one `Advance` call.

## What ABI 4 already provides

The locked native observer declares ABI 4, 64-byte configuration records, 16-byte kind records,
32-byte hooks, 16-byte ranges, 32-byte events, a 65,536-event capacity, and little-endian
encoding. Its current identities include:

- decompressed core SHA-256
  `f57b7a94237653879fb99af197937500a8b591f801f56284b4d2f53ca7ea6b0c`;
- compressed core SHA-256
  `e65315743a6a122843907a85314e380eee03fdc06bf0885b44c3dbc3bab88c6d`;
- build id `cba4d8c88cf968a9`; and
- observer identity SHA-256
  `815bfde02d78fd6caa1b127ddefe7be28cc84d6fdeef5a75cecc31f186f84d86`.

The generic implementation already supports exact M68K instruction opcode proof,
`PUSH_BEGIN`, `POP_END_AT_PC`, native Z80-RAM snapshot ranges, and action 7's
observation-only marker. A new core or ABI is therefore not inherently required. The production
configuration may add a one-byte range, one kind, and closed hook alternatives if native tests
prove the real cross-CPU scheduling and every reachable active-kind topology.

If the existing exact actions cannot express that topology without ambiguity or a false
lifecycle, implementation must stop. The fallback is a separately reviewed, standalone native
snapshot action with a new ABI/core/artifact identity, not a wildcard hook or a relaxed proof.

## Existing implementation is test-only and unbound

The current surfaces prove a useful managed contract but not a capture authority:

- `tools/tracechaser/bizhawk-headless/fixtures/gpgx-audio-service-manifest-s3k-submission-v2.json`
  is a minimal synthetic manifest. It adds range 2 for `$1C0A`, kind 13
  `MusicMailboxSubmission`, `ALLOW_CHILDREN` on kind 8, token 27 at `$1358`, and token 28 at
  `$1374`. It omits the full production boot/upload/service graph.
- `S3kSubmissionAudioObserverProfile.cs:7-26` explicitly has no BK2 identity, runner, CLI, or
  publication authority. Its authority is `[0,1)`, `productionBound=false`, and
  `includeSubmissions=true` under a provisional complete-run raw-v2 name.
- `S3kCompleteAudioRawSink.cs:230-269` requires a complete, non-cancelled kind-13 service with
  the exact PCs, hook tokens, M68K source, one range-2 byte, and begin-before-completion native
  ordering. This is a good negative-validation seed, not a production profile.
- `S3kSubmissionAudioRawV2Sink` accepts only the singleton unbound authority and rejects a
  production-bound use.
- `S3kSubmissionAudioRawV2Tests` uses a fake `IGpgxAudioTraceApi`. It proves same-`Advance`
  managed retention and no output inference, but does not exercise the installed core, exact
  opcodes, Z80 bus ownership, cross-CPU order, or a real movie.
- `S3kCompleteAudioCaptureRunner` constructs only `S3kAudioObserverProfile` and the production
  v1 sink. It has no submission profile and performs no S3K capability/install verification.
- The complete-audio CLI accepts a capability only for Sonic 2 and explicitly rejects one for
  S3K.
- OpenGGF's production Java raw adapter accepts the Knuckles v1 authority. The submission-v2
  identifier is deliberately absent from production bytecode and guarded from becoming an
  accidental authority.

The capture loop does wrap the whole host `Advance` in one observer capture. A production sink
can therefore retain begin, snapshot, completion, and later Z80 consequences from the same
outer row without synthesizing an inter-frame mailbox value.

## Native unmatched-active-kind fault risk

The native selector in
`native/gpgx-audio-observer/0001-buffer-z80-audio-events.patch:1764-1830` determines the
current active kind, counts every hook at the watched CPU/PC, selects hooks whose
`expected_active_kind` matches, and faults unless exactly one proof-valid hook is selected.
Specifically, `pc_matches > 0` with `matches != 1` is a hook-proof failure.

The test manifest supplies at `$1358` only a kind-13 `PUSH_BEGIN` expecting active kind 8, and
at `$1374` only a kind-13 `POP_END_AT_PC`. Any ordinary `Play_Music` call reaching `$1358`
under root, V-int, update, direct-DPCM, or another declared service kind sees a watched PC but no
matching hook and faults before the diagnostic case. Adding only the target pair to the full
production manifest would therefore regress otherwise valid captures.

The minimum no-new-core manifest design is:

1. clone the exact full production S3K manifest; do not replace it with the test subset;
2. add range 3 as the one-byte `$1C0A` range and add kind 13 (the full manifest already uses
   ranges 1 and 2, so the test manifest's range-2 identifier cannot be copied);
3. add `ALLOW_CHILDREN` only to kind 8, because only the reviewed SEGA-PCM topology creates the
   submission child;
4. retain the exact kind-8 `$1358` begin and kind-13 `$1374` end hooks; and
5. at both watched PCs add exact, non-mutating action-7 alternatives for root and every other
   declared reachable active kind, such that every instruction visit selects exactly one hook.

`GpgxAudioServiceManifest.cs:39` currently parses only actions 1 through 4, so its closed action
inventory must be extended specifically for action 7. The dedicated profile should retain the
reviewed S3K ABI-2 prepublication wrapper only for the exact `SndDrvInit` upload/arming proof,
while requiring the installed core's ABI-4 identity. It must not start a new publication epoch at
row zero or give the new `$1358`/`$1374` hooks pre-arm permission. Native matrix tests must prove
target selection, every non-target alternative, opcode mismatch, wrong parent, cross-CPU
ordering, and the absence of unmatched-kind faults.

## Distinct Sonic/Tails authority

Two S3K identities must remain separate:

| Contract | BK2 and SHA-256 | Movie rows | Publication interval |
|---|---|---:|---:|
| bounded service-128 diagnostic | `s3k-complete-sonic-tails.bk2`, `82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf` | 466,334 | `[0,5400)` |
| production complete run | `s3k-knuckles-complete-superemeralds.bk2`, `aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc` | 434,417 | `[810,434417)` |

The Sonic/Tails BK2 also pins the BizHawk header field named `SHA1` to the 32-hex, MD5-shaped
locked-on ROM token `C5B1C655C19F462ADE0AC4E17A844D10`; it is not the ROM file's SHA-1.
A future profile must validate the full 466,334-row movie identity before it captures the fixed
prefix. The Knuckles complete-run runner observes rows 0 through 809 for carried-in state and
publishes from 810, but changing its interval cannot authorize Sonic/Tails source frame 242.
Character, input, route, row count, interval, and semantic purpose all differ.

The existing `s3k_locked_on_knuckles_superemeralds.v1` profile and its
`[810,434417)` comparison remain intact. The new diagnostic authority must use a different
fixed profile, capability run, raw schema, extractor, fixture identity, and Java adapter. It may
not relabel the old v1 oracle as Knuckles evidence or expand the Knuckles profile to accept two
movies.

Because the diagnostic begins at row zero, its runner must not call
`CaptureBoundaryFrontierAndResetPublication()` there: that method unconditionally starts a new
native publication epoch before the normal upload proof is armed. Instead the sink begins from
`observer.CaptureCutoffFrontier()` before applying row zero, preserves the normal power-on
arming lifecycle, and captures exactly rows `[0,5400)`.

## Minimum production-owned path

### TraceChaser profile, manifest, and runner

The closed producer needs:

1. a fixed Sonic/Tails diagnostic profile pinning the ROM, ROM header, BK2 basename, BK2
   SHA-256, full movie length, prefix `[0,5400)`, state interval, full manifest, capability,
   harness executable, observer/install identity, and exact count/digest/cutoff evidence;
2. the safely extended full production manifest described above;
3. a reviewed capability whose run identity covers this exact movie, interval, manifest,
   harness, observer, source inventory, event counts, occupancy, terminal state, and digests;
4. installation verification equivalent to the Sonic 2 runner's `VerifyInstallation` gate;
5. a dedicated runner that validates the entire BK2, begins before row zero without resetting
   publication, observes exactly 5,400 rows, drains/fails terminal state, and publishes with
   create-new semantics; and
6. a closed CLI/launcher accepting only absolute existing ROM, movie, manifest, and capability
   paths plus an absolute absent output. It must expose no frame count, profile, address, opcode,
   callback, state-range, or request-value selector.

### Raw schema and sink

The diagnostic needs a specifically named schema rather than overloading
`openggf.s3k-complete-run-audio-raw.v2`. Its metadata must pin the complete identity cascade and
its exact inventory. Each request observation must require:

- a parent token which resolves to kind 8 with the exact ancestry/depth relationship;
- child kind 13, M68K source 2, `$1358`/`$1374`, tokens 27/28, and exact opcodes;
- one production range-3 snapshot for exactly `$1C0A..$1C0B` at `$1374`;
- native begin, snapshot, completion, and outer-frame ordinals in strict order; and
- request byte `$FE` for this fixed diagnostic claim.

Unknown fields, extra snapshots, wrong parents, missing alternatives, duplicates, overflow,
cancelled/incomplete services, terminal carry, digest mismatch, or inferred requests must fail
closed. The schema records the source observation; it does not declare that OpenGGF issued the
same request.

### Duplicate extractor and publication

The extractor must take two independently captured, fully attested raw streams. It validates
both exact schemas and identities, requires their normalized metadata, native inventories,
request observations, services, states, writes, terminal frontiers, counts, and digests to
agree, and then compares their shared v1 projection mechanically with the existing bounded
oracle. The only permitted semantic addition is the directly observed request evidence.

It emits a new v2 candidate into an external absent scratch path. Fixture publication remains a
separate explicit review/create-new operation. The extractor must reject a hand-inserted `$FE`,
a value inferred from the stop burst, disagreement between captures, a changed old-v1 shared
projection, or partial publication. It must never overwrite the existing fixture or canonical
bundle.

### Java comparison-only consumer and independent producer

The Java reader must reject duplicate keys and enforce exact top-level and per-row inventories,
types, numeric ranges, identities, row ordering, counts, digests, provenance, request ancestry,
and terminal evidence. A reference request attaches to the service projection only through the
authenticated native ancestry and ordering through PCM resume. It must not key on literal source
frame 242 or service 128.

`S3kOpenGgfAudioCapture` currently dispatches `referenceTick.mailbox()` and copies it back into
the result. That remains a fenced `fixture-assisted projection`: it may not become the
authenticated producer or feed the reference request into gameplay. The comparison-eligible
OpenGGF lane needs its own production BK2 replay and `AudioRequestObserver` evidence. Reference
and OpenGGF request streams are then compared as independently observed comparison data. Neither
stream hydrates engine state, calls a behavior owner, or changes comparator authority by itself.

Until OpenGGF naturally emits and independently observes the equivalent request, the product
reachability gap and request-layer limitation remain. Authenticating the reference side alone
cannot yield `MATCH`.

## Governance gate for another address-filtered observer

TraceChaser's mirrored `AGENTS.md` and `CLAUDE.md` currently permit exactly two
address-filtered hardware-timing observer exceptions and prohibit all other native diagnostic
hooks. This S3K request observer is fixed and comparison-only, but it is not one of those two
documented hardware-timing addresses.

Before implementation, the TraceChaser owner must classify the boundary. If it counts as a
third address-filtered exception rather than an already covered buffered-audio service proof,
explicit approval is required to amend both guidance files and their policy tests together. The
amendment must name only `$1358`/`$1374`, the exact opcode pair, stopped-bus one-byte snapshot,
fixed Sonic/Tails profile, non-mutation/non-inference rules, strong delegate lifetime where
applicable, and deterministic teardown. It must not authorize general M68K callbacks, caller
addresses, diagnostic output, or sync-point selection. This audit records the gate; it does not
amend TraceChaser policy.

## Fresh authority and provenance limitation

The existing installed core can mechanically collect the observation only after the safe
manifest and runner exist, and only if exact installation verification accepts it. Its hash does
not bind the new manifest, movie, interval, capability, harness, schema, event counts, digests,
or duplicate captures. It therefore cannot retroactively authorize the old v1 fixture or a new
v2 candidate.

The minimum authority route preserves the current native patch if native tests prove it, but
still reproduces the locked source/toolchain pair afresh, verifies the exact installed core,
reproduces the managed harness, creates a capability for the new fixed run, performs two serial
captures, and obtains independent review. If the native patch changes, the full ABI/core/artifact
identity and two-fresh-build gate restart.

`docs/architecture/validation/audio/2026-09-01-override-resume-reference-limitation.md`
remains authoritative. Its source-lock, toolchain, `/usr/bin/ar`, capability/source, and fresh
observer-install blockers are not repaired by this design. Both the bounded
`REFERENCE_LIMITATION / producer_input` and the broader
`FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE` must remain visible until every gate
closes. No frontier may move merely because test-only observation code exists.

## Bounded TDD, capture, and review sequence

Implementation should proceed after Task 8A and stop at the first failed gate:

1. Resolve the TraceChaser policy classification. If this is a third exception, obtain approval
   and amend the mirrored policy and policy tests before implementation.
2. Add failing native and pure C# tests for action-7 parsing, exact target selection, all
   non-target active kinds, opcode mismatch, wrong parent, real cross-CPU/bus ordering,
   unmatched-kind faults, overflow, and terminal cleanup.
3. Implement the exact full manifest/profile boundary with the existing ABI 4 core only if those
   tests prove it. Otherwise stop for review of a new native identity family.
4. Add failing profile/capability/install tests for wrong hashes, paths, symlinks, ABI, stale
   executable, wrong movie/header/row count, wrong interval, counts/digests, and overflow.
5. Add failing runner/sink/schema tests for the power-on begin path, exact 5,400-row prefix,
   absence of selectors, same-`Advance` retention, malformed submissions, short/long movies,
   native ordering, and create-new publication.
6. Add failing duplicate-extractor tests for capture disagreement, inferred-`$FE` poison,
   wrong context, changed old-v1 shared projection, provenance mismatch, and partial publication;
   then implement the closed extractor.
7. Add failing Java tests for strict parsing, exact projection, no legacy fallback, no fixture
   fabrication, no hydration, independent OpenGGF observation, and limitation-preserving
   comparison.
8. Restore the locked source/toolchain authority and complete fresh native and managed
   reproduction. Verify the exact install and produce the new capability; do not repin around a
   mismatch.
9. Run two serial, supervised, power-on captures to distinct absent external outputs. Require
   empty process inventories between runs and byte/digest agreement before extraction.
10. Obtain independent review. Only explicit publication approval may create a new fixture
    bundle. Bind consumers and attempt comparison only after publication and independent OpenGGF
    evidence exist.

## Safe process supervision for future execution

No process was launched for this audit. Before and after every future Mono test, harness run, or
capture, require this exact process scan to print nothing:

```bash
ps -eo pid=,comm=,args= | awk \
  '$2=="mono" || $2=="mono-sgen" || $2=="EmuHawk" || \
   $2=="BizHawk.Headless.Gpgx" {print}'
```

The eventual launcher must be a new closed Task-8B entry point; the old diagnostic launcher is
not authority. It must verify BizHawk 2.11, the canonical native core path, exact ROM and movie
hashes, capability, manifest, absent output, and disabled display access, then use `exec mono` so
the supervised process is the real harness. The planned invocation shape is:

```bash
timeout --signal=TERM --kill-after=30s 20m \
  /absolute/TraceChaser/bizhawk-headless/run-s3k-submission-audio.sh \
  --rom /absolute/Sonic3KLockedOn.gen \
  --movie /absolute/s3k-complete-sonic-tails.bk2 \
  --service-manifest /absolute/gpgx-audio-service-manifests-s3k-submission-v1.json \
  --capability /absolute/gpgx-audio-capability-s3k-submission-v1.json \
  --output /absolute/external/absent/s3k-submission-raw-a.jsonl
```

The path above names a planned launcher, not a currently authorized command. Run it twice to two
distinct absent outputs, never a canonical fixture destination. Exit statuses 124, 137, or any
other nonzero status fail the run. The post-run process scan must be empty before inspection,
extraction, or another invocation.

## Decision

The `$1358` to `$1374` stopped-bus mailbox boundary is sufficiently exact to plan a fixed
producer without hydrating engine state or inferring a request from output. The existing ABI 4
core appears to contain the necessary generic mechanics, but the committed implementation is
test-only and its minimal manifest is unsafe for production active-kind coverage. Task 8B must
build the distinct Sonic/Tails authority and pass policy, native, provenance, duplicate-capture,
publication, and independent-OpenGGF gates. Until then, the fixture and comparator remain
unchanged and both authority limitations stay in force.

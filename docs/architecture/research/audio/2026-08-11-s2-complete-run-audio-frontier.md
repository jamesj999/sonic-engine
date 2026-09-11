# Sonic 2 complete-run audio frontier

## Result

Task 1 of the Sonic 2 complete-run audio parity plan is implemented on
`bugfix/ai-s2-complete-audio-frontier`. The closed profile dispatcher can now
resolve `s2_rev01_complete_emeralds.v1`, but both producer bindings deliberately
remain typed as unavailable.

This advances the S2 engine-side frontier from no game profile to a pinned
fixture, native-content identity table, and strict source-state normalizer. It
does not yet produce or compare an OpenGGF trace, so the reference-vs-engine
event frontier remains unmeasured.

## Pinned reference contract

- ROM SHA-1: `8bca5dcef1af3e00098666fd892dc1c2a76333f9`
- ROM CRC32: `7b905383`
- BK2 SHA-256: `e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5`
- manifest SHA-256: `dfb220822eab3c524472aa02d6d78463a9489233b97fdd9ccd9340c9f3a10411`
- comparison interval: `[769,259590)` over 35 segments and seven special
  stages
- proven duplicate native result: 259,590 frames, 169,986,419 events, maximum
  frame occupancy 1,825, digest prefix `c2b2f823`, and an empty cutoff frontier

## Source-derived state and identity model

The model follows the shipped `fixBugs=0` driver:

- `docs/s2disasm/s2.sounddriver.asm:31-227` defines `zComRange`, the 42-byte
  `zTrack`, `zVar`, the ten music tracks, six SFX tracks, and the ten-track
  saved-music payload.
- `docs/s2disasm/s2.sounddriver.asm:1496-1549` defines three-slot request
  priority and the transient jump-SFX priority.
- `docs/s2disasm/s2.sounddriver.asm:1667-1724` defines ordinary-music SFX stop
  behavior and the shipped one-up save order: globals and ten music tracks are
  copied before live priority is cleared.
- `docs/s2disasm/s2.sounddriver.asm:3823-3855` and
  `docs/s2disasm/s2.constants.asm:826-970` define the native playlist, SFX IDs,
  and driver commands.
- `Sonic2SmpsLoader` ROM addresses provide stable music content identities, so
  native driver IDs and the engine's diagnostic API IDs compare by ROM content
  rather than by numeric coincidence.

The normalizer rejects observations made mid-service, validates the exact
source-slot order, expresses live pointers as asset-relative cursors, suppresses
inactive stale bytes, retains the source layers as well as effective hardware
owners, and includes the complete saved one-up payload while it is active.

## RED/GREEN evidence

The focused command was run before production classes existed and failed at
test compilation on the missing `S2CompleteRunAudioProfile`,
`S2NativeSoundResolver`, and `S2CompleteRunStateNormalizer` types. After the
game-local implementation, the same command passed 12 tests after review added
two applicability vectors:

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tools.audio.completerun.s2.TestS2CompleteRunAudioFixture,com.openggf.tools.audio.completerun.s2.TestS2NativeSoundResolver,com.openggf.tools.audio.completerun.s2.TestS2CompleteRunStateNormalizer' \
  test
```

The review-fix final tree reran that focused set together with the shared trace
and authority guards and the ROM-backed Sonic 2 unified-audio integration: 63
tests passed with no failures or errors. This is the same selection previously
reported as the 61-test gate, plus the two new union-byte applicability tests.

A broader 162-test gate covered the shared authority/schema/store/CLI/
comparator surface, the three S2 classes, and the ROM-backed Sonic 2 unified
audio presentation integration. It produced 161 passes and one pre-existing
checkout-mode failure: `TestCompleteRunAudioCli` requires
`tools/audio/run_complete_audio_parity.sh` to be executable, while the pinned
baseline records that file as mode `100644`. The S2 integration test and every
test affected by this task passed; this task does not own the shared script or
its file mode.

## Next boundary

The next ordered plan item is Task 2. Completing it requires the absent native
`S2AudioObserverProfile`, native `S2CompleteAudioCaptureRunner`, their native
tests, and headless `Program.cs` registration. Those files are outside this
worktree's S2 Java-only ownership boundary. The smallest central contract is to
install those native owners and expose their fixed Java reference adapter
binding without changing the shared trace schema. Until that central work is
rolled into the integration baseline, the S2 reference producer must remain
typed unavailable and Task 3 must not leapfrog Task 2.

Fresh CLI JVM bootstrap has a separate shared dependency: static registration
in `S2CompleteRunAudioProfile` does nothing until that class is loaded. The
closed `CompleteRunAudioProducerRegistry` must reserve
`s2_rev01_complete_emeralds.v1` and name the fixed profile class so
`CompleteRunAudioProfiles.require(...)` can load it without an ambient
caller-selected class. Integration checkpoint `ef83b7e6b` already contains
that S2 dispatcher entry; this game-local commit depends on it remaining in the
integration baseline. If a target baseline lacks the entry, adding it is a
central shared-registry change, not an S2 profile workaround.

## Round 2: native observer profile

The first game-owned part of Task 2 is now implemented. The headless
`S2AudioObserverProfile` selects the reviewed S2 portion of the shared native
service manifest, rejects any change to the pinned manifest, movie, observer,
complete-run event, terminal-Z80, or cutoff-frontier identities, and verifies
the exact final observer installation before it can be used. Its configured
graph retains the source-derived `$0038` VInt, `$0110` music, and `$017A` DPCM
iteration boundaries while continuing to exclude the persistent `$0178`
busy-wait.

The installation verified in this round is
`bizhawk-2.11-gpgx-audio-observer-v2` / `gpgx-audio-observer-v2`, ABI 2,
BuildID `b49036a848890682`, observer identity
`1f0147ecc101d4d726ed09536db87c125f305eccdca986c620d735714543c5cc`.
The already-proven complete S2 evidence remains unchanged: 259,590 frames,
169,986,419 events, maximum occupancy 1,825, no open or pending service at
cutoff, and event digest
`c2b2f82374aaa16144b6bf121df051dcd5b4ba095431c16cf6224adc633de41d`.

Focused RED failed at compilation because `S2AudioObserverProfile` did not
exist. Focused GREEN used the final observer installation and passed all three
new profile tests. No new native capture was performed and no ROM, BK2, core,
or captured payload was copied or published.

The next exact boundary remains the S2-owned
`S2CompleteAudioCaptureRunner` and `S2CompleteRunReferenceProducer`. They must
serialize the observer's canonical services into the shared raw staging
contract before the profile may change its reference binding from typed
unavailable. Headless `Program.cs` and `TestMain`/project registration are
shared integration seams; this round changed only the minimal test/project
registration needed to exercise the S2-owned profile and leaves CLI routing to
the conductor.

## Round 3: bounded native capture runner

The next game-owned slice is now implemented as
`S2CompleteAudioCaptureRunner`. It authenticates Sonic 2 World REV01, the
read-only 259,590-row all-emeralds BK2, the reviewed service manifest and
capability, and the final ABI 3 observer installation before opening the host.
It observes and drains from power-on, starts publication at the fixed row-769
comparison boundary, and streams every row in `[769,259590)` to a bounded sink.
The exact-end path rejects both short and overlong movie streams; the bounded
proof path stops without consuming the preserved movie tail.

The shared manifest remains the legacy ABI 1 source description. The S2-owned
profile validates its exact `$EC000`/`$EC036` `SoundDriverLoad` begin/completion
chain, then enables only those two hooks for ABI 2 prepublication. This follows
`docs/s2disasm/s2.asm:91301-91323`: the unique routine owns the full driver
upload and reaches one common release after all 8 KiB are final. The transition
changes publication coordinates only; physical chip latches and native service
lifetime state remain continuous.

A real read-only power-on proof against
`crossing-install-final2-a` reached row 769 with publication armed at epoch 1,
one carried kind-4 DPCM service, no pending services, YM address latches
`$2A/$A1`, and 1,491 raw events in the first published row. The exact ROM and
BK2 identities remained
`8bca5dcef1af3e00098666fd892dc1c2a76333f9` and
`e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5`.
The already-reviewed complete evidence remains 259,590 frames, 169,986,419
events, maximum occupancy 1,825, and an empty cutoff frontier.

This is capture capability only. It does not add CLI routing, a shared raw
staging writer, or the fixed Java reference adapter, and the S2 profile's
reference binding remains typed unavailable. Adding the production C# class
necessarily changed the deterministic harness executable; the provisional
game-branch identity is recorded in the capability fixture while central
integration will perform the single final cross-game deterministic repin.
The clean two-root verifier reproduced production SHA-256
`9d3d9eb25c29fec4436149c802b3851657c74ea20b65df9bf22d60241d574d31`
and test SHA-256
`3a10293d6d41bff0fb637c91953e4f3644e990b49f72b09414437b6b81f1c30b`.

## Round 4: transactional raw reference staging

S2 now owns a lossless native raw sink and strict Java streaming adapter. The
captured state span is the complete `$0000..$1FFF` 8 KiB Z80 RAM image. A
smaller `zVar`-only span would be lossy: the shipped driver holds
future-affecting DAC, channel-pointer, voice, and Saxman decompressor state in
self-modifying instructions throughout the code image
(`docs/s2disasm/s2.sounddriver.asm:516-533,694-722,806-814,2196-2247,`
`2314-2400,4051-4075`). The same image contains the globals at
`$12FE..$1307`, the decompressed song buffer at `zMusicData=$1380`, and the
live/SFX/`fixBugs=0` saved tracks from `zStack=$1B80` through the hard `$2000`
RAM ceiling (`docs/s2disasm/s2.sounddriver.asm:31-227,4087-4096`).

The C# sink writes one strict JSONL envelope with pinned ROM, BK2, manifest,
interval, and state-range identities; lossless ABI-v3 events; typed active and
pending services; chip ownership; snapshots; and ancestry transitions. Output
uses the shared no-replace staging primitive, so a late capture failure removes
the private staging file and an existing target is never replaced. This is raw
staging only: no CLI route, Java producer binding, canonical-store publication,
or trace comparison was enabled.

The Java adapter rejects duplicate or extra fields, non-integral numeric
tokens, noncanonical uint64 payload strings, row gaps, out-of-range ABI-v3
values, malformed immutable/effective ancestry, wrong snapshot widths, and
records after cutoff. It begins its consumer transaction only after validating
metadata and the row-769 baseline, aborts every staged callback on any later
failure, and commits only after a validated cutoff followed by EOF.

The real read-only proof against the action-9 observer installation reproduced
the reviewed boundary exactly: row 769, epoch 1, one open kind-4 DPCM service,
zero pending descendants, YM latches `$2A/$A1`, 1,491 events, and a 16,384-digit
lowercase state snapshot. The pinned ROM and BK2 identities were unchanged and
no ROM, movie, raw payload, or native binary was copied or published.

Follow-up adversarial review hardened the adapter against shapes that strict
JSON typing alone did not exclude: every event kind now obeys its ABI-v3 field
shape and the pinned S2 begin/completion hooks; kinds 10/11 and all ancestry
transitions are rejected because the reviewed S2 manifest has no
marker/promotion hooks; and frontier kind zero is invalid. Snapshot events must
form one contiguous BEGIN/CHUNK/END transaction with stable owner, range,
source, and PC. Reset begin/end events must pair within their frame, name the
exact active-service cancellation count, and preserve the power bit. The
row-769 kind-4 baseline must remain open and noncancelled. A pinned immutable
copy of the S2 hook graph additionally checks each begin against its action,
expected active kind, service kind, parent, and depth. Every normal completion,
reset cancellation, and reset end consumes exactly its manifest-declared
canonical snapshot slice; the active row-769 kind-4 service therefore accepts
range 2 only, never the 8 KiB upload range 1.
Because that manifest contains no promotion actions, every frontier service's
effective ancestry must equal its immutable begin ancestry. Active frontier
services are also one root-first ordered stack; the sole row-769 kind-4 service
is therefore necessarily a depth-zero root rather than an orphaned child.

Focused verification passed 34 S2 Java tests, eight S2 profile tests, eight S2
capture/raw-sink synthetic tests, and the one opt-in real boundary gate. The
round-local pre-integration build produced main executable
`ec8c2b6745faf72dca885d918372a8e3d678235681ab954b948f75a31de4c4c6`
and test executable
`0fde5216cfb819919d29e10287ffd500e4a63b1e72437eb8b0ea78db7a05d3a2`.
The completed combined cross-game cascade supersedes those provisional values:
the integrated main executable is
`a4e2b74cb05db8152e18e6d5c6d8c6c12bb12b470f33a4e83b3b5d9bb7e36965`,
the test executable is
`1c110cf9011af53100a48e9a414d0d06da5510710840db944af8336d1a428bfd`,
the normalized capability is
`a5b5a07529f3e7c908601e7dc1ce552c8fd70390a9619e6847ccb00e16f984d3`,
and the raw capability is
`8a06a63e4a5c8b1d4c9445e4333537caed3c8e67df7df946135e273d911ab0fb`.

## Round 5: source-exact raw-state decoder

S2 now owns the missing strict decoder from the complete 8 KiB raw Z80 image
into `S2CompleteRunStateNormalizer.LiveState`. The layout is taken directly
from `s2.sounddriver.asm:31-227`: `zVar` at `$1B80`, ten live music tracks at
`$1B98`, six live SFX tracks at `$1D3C`, and the `fixBugs=0` saved `zVar` plus
ten music tracks at `$1E38..$1FFF`. Each shipped `zTrack` is exactly `$2A`
bytes. The decoder suppresses every inactive track's stale union, validates
physical-slot voice controls, stack partitions, and every live data,
modulation, voice, TL, and return pointer before normalization.

The accompanying catalog authenticates World REV01 by exact 1 MiB size and
SHA-1 `8bca5dcef1af3e00098666fd892dc1c2a76333f9`. It reads the two shipped music
pointer banks and the 81-entry SFX pointer table from the ROM, derives occupied
asset ends from the next source pointer, and decodes each Saxman-compressed song
to its real `$1380..$1B7F` address extent. This matters because compressed songs
share one mutable buffer: `zMusicData=$1380` and the shipped decompressor in
`s2.sounddriver.asm:3931-4045` overwrite it on each music load. During one-up
playback, saved tracks are therefore attributed only when the saved bank,
pointer extent, and full current decompressed-buffer bytes identify exactly one
source song. Unknown, ambiguous, padding, and out-of-window pointers fail
closed. No asset bytes are loaded from the disassembly at runtime.
The final `Sound70` range ends exclusively at Z80 `$FFEC` (its 10-byte header
plus 14-byte PSG stream); `$FFEC..$FFFF` is rejected bank padding. Saved return
pointers are dereferenced by `cfJumpReturn`, so an address equal to an asset's
exclusive end is likewise rejected rather than attributed to its predecessor.

The shipped one-up union follows `s2.sounddriver.asm:1667-1724` and
`:3087-3155`. In particular, `fixBugs=0` copies the original nonzero SFX
priority before clearing the live priority, and the decoder preserves that
saved value. Inactive saved bytes are ignored unless the live one-up flag says
the saved region owns future behavior.

An opt-in row-769 gate now asks the existing native proof to publish its
transactional raw envelope to a fresh temporary path, then passes both the
baseline and first frame through the strict Java adapter, decoder, normalizer,
and S2 profile validator. Enable it with
`OPENGGF_S2_COMPLETE_AUDIO_DECODE_GATE=1`,
`OPENGGF_S2_COMPLETE_AUDIO_REFERENCE=1`, `S2_ROM_PATH`, `S2_BK2_PATH`, and the
reviewed `BIZHAWK_HOME`. Reference and OpenGGF producer bindings intentionally
remain unavailable; this round does not publish a canonical trace.

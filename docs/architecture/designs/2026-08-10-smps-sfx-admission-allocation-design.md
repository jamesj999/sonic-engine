# SMPS SFX Admission Allocation Design

**Date:** 2026-08-10
**Status:** Implemented on `develop` and locally reconciled on `bugfix/ai-s1-audio-parity-frontier`
**Target branches:** `develop` and `bugfix/ai-s1-audio-parity-frontier`

## Problem

Starting or retriggering an SMPS sound effect currently allocates memory in
proportion to assets and live state that the new sound does not own. Spindash
charge makes the problem conspicuous because every jump-button press while
charging submits another SFX command.

The warmed path still performs all of the following:

1. `AudioPresentationCommandResolver.resolveSmpsSfxCommand` calls
   `warmSmpsSfxAsset` for every trigger.
2. `warmSmpsSfxAsset` replaces the cache entry with `snapshotSource`, which
   deep-copies the SMPS data, the full DAC sample bank, and static sequencer
   configuration.
3. `instantiateCached` calls `freshSource`, which copies the DAC bank and
   static configuration again.
4. `AudioVoiceRegistry` captures a generic live-command rollback token before
   adding the SFX to a music driver. That token snapshots every live sequencer,
   channel lock, latch, driver queue, and synthesizer state even though SFX
   admission mutates a small, bounded subset.

The previous optimization that shares frozen SMPS sequence data during
`freshSource` removed one redundant sequence copy, but did not make registration
idempotent and deliberately retained the two DAC/config copies. The resulting
allocation is mostly short-lived rather than retained, but it creates GC
pressure and burst latency on exactly the frames where sounds start.

## Goals

- Freeze each SMPS SFX asset once per audio-presentation session.
- Share frozen sequence data, DAC samples, and static configuration between all
  sequencers in that session.
- Make a repeated SFX trigger a lookup, never an asset-cache replacement.
- Replace whole-driver rollback capture for SFX admission with a bounded
  prepare/commit protocol.
- Preserve exact sound-driver behavior, including same-ID replacement, channel
  contention, continuous SFX, coordination flags, rewind snapshots, source
  descriptors, donor routing, and failure behavior.
- Preserve the S1 frontier's request/admission/contention observers and their
  transactional guarantees.
- Land and verify the semantic change on both `develop` and the existing
  `bugfix/ai-s1-audio-parity-frontier` worktree.

## Non-goals

- Pooling or recycling `SmpsSequencer` or `Track` instances.
- Removing allocation for genuinely new mutable SFX runtime state.
- Changing gameplay SFX cadence, priority, pitch, or sound selection.
- Changing ordinary rewind snapshot representation or history retention.
- General audio-render-loop optimization; warmed mixing is already covered by
  a zero-allocation budget.
- Treating diagnostics as optional correctness. The frontier observers remain
  inert when disabled and exact when enabled.

## Allocation invariant

After an SFX asset has been registered, triggering it may allocate the new
sequencer's mutable runtime state and bounded admission metadata. Trigger cost
must not scale with:

- total DAC sample bytes;
- frozen SMPS sequence/voice/envelope table size;
- the number or complexity of unrelated live music tracks; or
- the number of prior triggers of the same asset.

The budget is therefore structural rather than a fragile universal byte
constant. Tests will compare two fixtures that trigger the same SFX while
varying DAC-bank size and unrelated live-driver complexity; warmed allocation
must remain within a small fixed tolerance between the fixtures.

## Design

### 1. Session-owned immutable asset catalog

`AudioPresentationSourceFactory` remains the owner of presentation assets. Its
SFX map and per-play music snapshots change into one immutable SMPS session
catalog. The catalog has two levels:

- a game/session dependency entry containing the read-only DAC bank and static
  sequencer configuration; and
- one program entry per generation-bearing SMPS asset identity, covering both
  SFX and music, referring to that shared dependency entry rather than
  embedding another copy of the full DAC bank.

This distinction is required: copying a DAC bank once for every distinct SFX
would trade burst allocation for retained duplication and would still be the
wrong ownership model.

The registration operation will have these semantics:

- On the first game/session dependency key, defensively freeze the DAC bank and
  static sequencer configuration once.
- On the first SFX or music asset identity, defensively freeze its SMPS program
  (including voices and envelope tables) and store a reference to the
  game/session dependency entry.
- On a repeated registration of the same key from an equal logical source,
  return the existing entry without copying.
- On a conflicting registration under the same key, throw a descriptive
  identity-conflict exception. Do not silently retain stale bytes and do not
  replace an asset that may already be referenced by live or rewind-restored
  voices.

The catalog lifetime is the presentation session, but source identity within a
session is generation-aware. `AudioManager` can replace the base ROM/profile or
register/clear donor loaders without first rebuilding an existing shadow
factory. It will therefore own monotonically increasing base and per-donor
dependency generations. Every source-changing API (`setRom`, `setAudioProfile`,
donor registration/replacement, and donor clearing) advances the applicable
generation, and `ShadowSources` exposes that token through an O(1) lookup.

The factory internally keys programs by `(SmpsAssetKey, dependencyGeneration)`;
`SmpsAssetKey` has distinct base/donor music and SFX routes, so an equal numeric
music/SFX id or base/donor id cannot alias.
New commands always resolve against the current generation, so lookup-before-
load cannot return stale bytes after source replacement. Older immutable entries
remain available for already-live voices and rewind snapshots until session
teardown. Their descriptors retain the old generation and therefore cannot
silently bind to new ROM/donor dependencies. Tests pin every source-changing API.

The resolver performs **lookup before load**. If the `SmpsAssetKey` is already
registered, it builds the command from catalog metadata and never calls the
loader or registration path. This applies to repeated base/donor music starts
as well as SFX triggers, so music sequence, voice, PSG-envelope, and modulation-
envelope tables are frozen once per asset generation and shared by every live
or restored playback. It is essential for `Sonic2SmpsLoader` named SFX routes,
which currently create an equal new `AbstractSmpsData` on each `loadSfx(String)`
call. Their second and later triggers must be O(1) catalog lookups, not false
conflicts or repeated decodes.

`AudioManager` has an earlier classification probe at its public submission
boundary: music and SFX entry points call the loader to decide which logical
route to record before the presentation resolver sees the command. Those owner
paths must use the same catalog too. They derive the requested route key and
current generation before probing the loader. A catalog hit records the SMPS
command without loading. On a miss it loads once, registers that exact program
and dependency tuple, and only then records the command; the resolver consumes
the already-registered entry against the same captured source tuple. Named SFX
keep the requested name in their catalog key while policy uses the first
program's resolved asset id. Base loaders returning null retain the existing
music/SFX fallback behavior, and direct donor loaders returning null retain the
existing no-op behavior; a failed GameSound donor probe retains its named
fallback. The resolver keeps its own lookup-before-load miss path for direct
resolver and replay-style callers. Negative-result caching is not required.

The public donor registration overload that supplies loader and DAC but no
sequencer configuration is a supported legacy compatibility route. Its old
backend contract selected the active base backend's configuration and SFX
policy. Registration now captures those two values from one immutable base
source tuple and stores them with the donor loader/DAC generation; an explicit
donor profile remains authoritative when supplied, while an explicit config
without a donor profile retains the legacy default SFX policy rather than
borrowing the base policy. It never combines a later current configuration
with an earlier policy. A config-less
donor registered before a base owner exists remains unmaterialized until a
complete owner is available, matching the legacy backend's inability to create
a sequencer without a profile. Invalid blank names and negative ids are probed
before catalog-key validation; only a valid lookup request or a successfully
loaded program with a valid resolved id may create a catalog key.

The catalog-owned live-backend playback value retains the complete SFX
classification with that tuple: priority, special-SFX, and continuous-SFX.
The manager dispatches an explicit immutable policy value through the narrow
SMPS backend overload. Existing backend overloads remain compatible and may
derive policy from their current profile, but a registered cached hit never
does; reentrant or later profile replacement therefore cannot reclassify the
retained playback.

On a versioned catalog miss, the resolver loads and registers the program. Registration
captures a small provenance descriptor (session/game dependency identity,
dependency generation, asset key, special-SFX flag, and program metadata). If an explicit caller tries
to register another object under an existing key, same-object identity is an
O(1) success; a different object is compared for semantic equality on that
exceptional registration path. Equal reconstructed data reuses the entry and
different data is rejected. The asset-sized comparison is deliberately absent
from the ordinary trigger path. Tests cover the named S2 reconstructed-equal
case directly.

Naming changes from `warmSmpsSfxAsset` to `registerSmpsSfxAsset`, paired with a
non-loading `findRegisteredSmpsSfxAsset`; music receives equivalent
`registerSmpsMusicAsset`/`findRegisteredSmpsMusicAsset` entry points over the
same catalog. This makes the ownership and hot-path contract explicit. Eagerly
decoding every asset is neither required nor desired.

### 2. Share frozen runtime dependencies

`snapshotSource` remains the single defensive-copy boundary. Once copied, the
factory-owned values are immutable session assets:

- SMPS program data will move behind a read-only runtime representation.
  Public array-returning accessors return defensive copies and inherited
  mutators reject writes after construction. `SmpsSequencer` receives a narrow
  internal read view for zero-copy byte, voice, PSG-envelope, and modulation-
  envelope access. The view never exposes its backing arrays.
- `DacData` will become genuinely immutable rather than an unmodifiable map of
  mutable arrays. Construction deep-copies caller input. Public maps/raw arrays
  are replaced with lookup methods and an immutable sample view exposing length
  and indexed byte reads. `Ym2612Chip` may retain the immutable sample view for
  its hot loop, but cannot obtain or mutate its backing array.
- DAC sample bytes and note mappings are frozen once per game/session dependency
  and shared. Tests will mutate constructor inputs, probe every public runtime
  dependency path, and assert that no shared array or setter can mutate live or
  restored voices.
- Static sequencer configuration will be captured once. Per-session dynamic
  services such as the coordination-flag handler are references owned by the
  session, not reasons to clone static arrays and sets for each voice.

`freshSource` and `copyDac` will be removed. `instantiateCached` will pass the
catalog entry's read-only dependencies directly to the new sequencer. If
configuration currently combines static values with a dynamic handler, the
implementation will split or bind that dependency without recopying the static
portion. Architecture tests will scan production writes and exposed APIs so the
ownership contract cannot regress silently.

Source identity is also a registration-time product. The catalog computes the
`SmpsSourceDescriptor` and program fingerprint once while freezing the program
and stores them in the program entry. Music and SFX sequencer construction
accept that precomputed descriptor; neither may call
`SmpsSourceDescriptor.from`, `describeSfx`, `getData()`, or any whole-program
hash/copy on a registered path. Rewind restoration uses the descriptor already
present in the snapshot and resolves the matching versioned catalog entry.
Allocation tests vary program size as a third independent dimension and pin a
zero descriptor/instantiation slope.

Only immutable decoded assets are shared. A music or SFX play still creates its
own mutable sequencer, tracks, channel state, and in-progress envelope state;
the catalog never pools live playback state.

### 3. Pure SFX preparation

All fallible and validating work must occur before the live driver changes.
Preparing an admission will:

1. resolve the registered catalog entry;
2. create the SFX sequencer and its tracks;
3. bind immutable dependencies and session-owned coordination services;
4. validate channel ids, pointers, priority metadata, and continuous-SFX
   metadata;
5. determine same-ID replacement and channel conflicts; and
6. produce a bounded `PreparedSfxAdmission` containing the new sequencer, the
   affected driver references/channels, and ordered conflict actions in the new
   program's native track/header order. Action arrays are sized only from the
   new SFX track count, never the live driver population; exact FM and DAC track
   types may coexist on hardware channel 5 without sharing a conflict slot.

SFX construction must not write to the shared YM2612/PSG state, replace the
driver's DAC source, acquire channel locks, publish observer events, or mutate
coordination runtime state. The frontier already contains construction-purity
work that suppresses the SFX constructor's early DAC-enable write; the port
must preserve and extend that contract rather than regress it.

Coordination-flag `onSfxStart` handling belongs to admission, not asset
construction. It runs before irreversible driver mutation and uses only the
coordination runtime's existing narrow snapshot if restoration is needed.

### 4. Bounded admission commit

`SmpsDriver` will own SFX contention and admission. With diagnostic observers
disabled (the normal runtime path), a prepared admission exposes a commit
operation whose production steps are non-throwing after validation:

- retire/replace the same-ID SFX when required;
- deactivate only conflicting existing SFX tracks;
- release and acquire the affected FM/PSG locks;
- perform the native channel silence/takeover writes;
- add the new sequencer to the driver collections;
- install continuous-SFX bookkeeping; and
- publish the committed admission to the registry.

The generic `mutateVoicesAtomically(... owner)` wrapper will no longer surround
this path, so admission will not call `captureLiveCommandMutation` on the whole
music voice.

The develop branch already has optional YM/PSG `ChipWriteObserver` callbacks,
and Java callbacks can throw after a chip write has mutated state. Until the
frontier's selective journal is ported, an observer-enabled develop commit uses
a transitional driver-local fallback: capture the existing full driver/synth
snapshot immediately before mutation, restore it and release the prepared
commit claim if a chip callback throws, then rethrow. Coordination state remains
owned/restored by the registry's narrow snapshot. This fallback is forbidden
when observers are `NONE`, is excluded from the common-path allocation claim,
and is replaced—not retained—by the channel-selective journal during the
frontier reconciliation. Tests inject failures from real YM and PSG writes and
compare the restored full state exactly.

The implementation must not assume arbitrary frontier observers cannot throw or
move them before mutation. Frontier observers deliberately inspect committed
state; for example, contention callbacks assert that the FM lock already points
at the admitted SFX. Their existing post-mutation timing remains intact.

On the observer-heavy frontier, the transitional develop fallback is replaced
by a bounded `SfxAdmissionMutationJournal` covering exactly the state the
prepared admission can change:

- displaced/replaced sequencer and affected track mutable fields;
- affected FM/PSG locks and latches;
- driver collection membership, removal buffers, continuous-SFX counters, DAC
  source selection, and admission ordinals touched by this admission;
- coordination-flag runtime state; and
- the affected YM2612 channels/operators/key/envelope/register state, PSG
  channels/noise/latch state, and the small set of global chip fields written by
  SFX takeover.

The journal is bounded by hardware channel count plus the new/displaced SFX
track count. It contains no audio asset bytes and no unrelated music-track
snapshots. Chip capture/restore APIs are channel-selective and are tested against
the current full synth snapshot as an oracle.

Commit performs the normal internal mutations and invokes observers at their
existing points. If a callback throws, the journal restores all internal state,
the typed diagnostic exception escapes, and the presentation command remains
queued for retry exactly as today. External observer emissions made before a
later callback throws cannot be retracted; this is already true today, so retry
may repeat an external prefix but never an internal admission. When observers
are all `NONE`, no journal is created: validated internal commit is no-throw and
the common gameplay path pays no rollback allocation. `develop` uses that common
path because it lacks the frontier diagnostics.

If analysis shows a production commit step can still throw after bounds and
invariant validation, that is an
implementation blocker: amend this design and plan, then re-review them. Do not
quietly keep the generic snapshot as a fallback.

### 5. Continuous SFX and standalone drivers

Continuous SFX extension remains a distinct prepared fast path. When the driver
can extend an existing matching continuous SFX, it creates no sequencer and
performs no catalog copy. With throwing frontier observers enabled, its much
smaller journal restores only the continuous counters/flags and observed
admission metadata if a post-mutation callback fails, leaving the extension
safely retryable.

When no music driver exists, the registry may create a standalone driver and
voice. That is genuinely new runtime state and may allocate. It still shares the
catalog assets and uses the same prepared admission rules before publication.
If preparation fails, the unpublished standalone voice is disposed as today.

### 6. Rewind and dependency restoration

Ordinary rewind capture remains unchanged: snapshots must still contain all
mutable driver and sequencer state needed for deterministic restoration.
Catalog sharing changes dependency ownership, not snapshot semantics.

Precomputed, generation-bearing source descriptors remain the stable key used to reconnect snapshot state to
frozen session dependencies. Restoration must share the catalog dependencies,
not copy them again. It must:

- resolve the same frozen catalog entry;
- rebuild mutable sequencer/track state from the snapshot;
- preserve donor/base route identity and coordination handlers; and
- fail explicitly if the required session asset is absent.

Descriptor registration rejects collisions where one descriptor would resolve
to different base/donor programs or dependency entries. Tests cover base/donor
and named/id descriptor separation.

No trace or rewind data may hydrate gameplay or decide which sound is played.

## Error handling and invariants

- Duplicate equal registration: return existing catalog entry.
- Duplicate conflicting registration: reject before command publication.
- A base/donor dependency generation change makes old entries ineligible for
  new command lookup while retaining them for live/rewind dependency resolution.
- Missing catalog entry at owner-boundary application: retain the current
  cache-rejection behavior and warning.
- Invalid SFX data/admission metadata: reject during preparation without live
  mutation.
- Standalone preparation failure: dispose unpublished state and preserve the
  previous registry.
- Observer failure on the frontier: preserve its post-mutation observation
  timing, typed diagnostic exception, and retryable command while restoring all
  affected internal state through the bounded admission journal.
- No production path falls back to copying assets or taking a full driver
  snapshot after a failure.

## Testing strategy

### Asset ownership tests

- Registering one key twice retains the same cached frozen SMPS identity, and
  registering multiple SFX for one game retains the same DAC/static dependency
  identities.
- Repeated Sonic 2 named-route triggers look up before loading and accept
  reconstructed-equal loader data on explicit duplicate registration.
- Mutating loader-owned SMPS/DAC/config inputs after first registration cannot
  alter playback.
- Public APIs on live and restored dependencies cannot expose a mutable backing
  array or mutate frozen program metadata.
- A conflicting registration under the same key is rejected.
- Rebuilding the presentation session permits a new source under the same key
  without cross-session reuse.
- Replacing the base ROM/profile or registering/clearing/replacing a donor in an
  existing session advances dependency generation: new commands use new bytes,
  while live and rewind-restored old-generation voices retain their dependencies.
- Base-game, named, donor, and special-SFX routes remain distinct.

### Admission correctness tests

- Same-ID retrigger replaces the existing SFX exactly once.
- FM and PSG contention deactivate and unlock the same tracks as before.
- Continuous SFX extends without a new sequencer.
- Rejected preparation leaves sequencers, tracks, locks, latches, synth state,
  coordination state, and registry ordering unchanged.
- Injected frontier observer failures at every admission, contention, driver-
  service, and chip-write callback retain the command for retry without internal
  mutation; retry commits exactly once. Cover replacement, contention,
  continuous extension, and standalone admission.
- Each frontier callback still observes the same already-mutated locks, tracks,
  chip state, admission ordinals, and event order as before.
- Selective chip-journal restore is byte-equal to restoration from the existing
  full synth snapshot for every affected-channel combination.
- SFX construction is pure with respect to the live driver/synth.
- The S1 frontier's request, policy, admission, contention, and chip observers
  report the same events and ordinals before and after the optimization.

### Allocation tests

Add a dedicated warmed SFX-trigger allocation budget using
`com.sun.management.ThreadMXBean`, following the existing anti-flake pattern:

- use identical trigger count, SFX track topology, pitch, command route, and
  registry topology in every comparison fixture;
- warm the exact measured method and probe call site through multiple discarded
  runs before reading counters;
- measure at multiple trigger counts and compare per-trigger slopes, not one
  aggregate delta;
- independently vary tiny/large SFX programs, tiny/large DAC banks, and
  simple/complex unrelated music drivers;
- assert no statistically/materially increasing allocation slope attributable
  to either dimension, with a documented tolerance derived from repeated warm
  control runs rather than a hand-waved fixed allowance;
- retain structural identity assertions even when allocation accounting is not
  available; and
- separately bound new-sequencer/track allocation so the test does not demand
  impossible zero allocation for a new mutable voice.

The test must exercise command resolution, catalog lookup, preparation, and
driver admission, not merely the allocation-free mixer loop. Prepared conflict
storage uses channel-bounded arrays/pre-sized structures; it must not allocate a
`HashSet`, stream pipeline, or growing list proportional to unrelated live SFX.
Conflict discovery may scan existing live SFX, but its allocation may not scale
with that scan.

The completed work also requires a historical before/after comparison, not only
an optimized-branch budget. A baseline-compatible benchmark fixture will use
the same public `AudioManager` repeated-music and repeated-SFX entry points,
constant loader/program/DAC/driver topology, trigger counts, warmup protocol,
and JDK/JVM settings on detached updated-`develop` and completed-feature
worktrees. It will report every raw repetition plus median allocated bytes per
operation, control spread, loader calls, program materializations, and warmed
elapsed nanoseconds per operation. The paired counter is implemented entirely
in the byte-identical test fixture: each fresh instrumented
`AbstractSmpsData` returned by the loader increments a primitive
`programMaterializations` counter exactly once on its first program-data/
defensive-copy access. Feature-only catalog-registration identity is asserted
separately and is not presented as a paired baseline metric. Allocation is the
acceptance metric; elapsed time is descriptive because it is more sensitive to
host noise. The
benchmark must keep live voice count constant by replacement/retrigger and must
not invoke a feature-only production API from the measured caller, so the exact
same workload source can be copied into and compiled against the baseline. The
source hash covers the complete benchmark manifest, including every test-local
fixture/helper, not only the main test class.

The paired acceptance rule is fixed before measurement. For every music and SFX
fixture, feature median allocated bytes per operation must not exceed baseline
by more than `max(baselineControlSpread, featureControlSpread) + vmNoiseMargin`,
where the small VM margin is printed by the fixture and documented in the audit.
For each targeted large-program, large-DAC, and unrelated-music comparison, the
feature size slope must be within that zero/control tolerance and materially
below the baseline slope; the feature large-case median must also improve over
baseline by more than the same tolerance when the baseline exhibits the
targeted size-dependent cost. Feature loader calls and program materializations
must equal exactly one per asset key/generation after warmup; catalog identity
tests separately require one registration per key/generation. Completion
requires JDK 21 with supported and enabled thread-allocation accounting. An
unsupported VM may skip ordinary budget assertions, but cannot produce an
acceptable historical comparison.

The final staged performance audit records both commit ids, JDK/Maven/JVM
settings, fixture sizes/topology, warmup and measurement counts, exact commands,
raw repetitions, medians, paired tolerance, pass/fail evaluation, percentage
deltas, and the program/DAC/music-size slopes. A comparison is invalid if the
complete workload manifest, constants, environment, route, allocation-counter
support, or operation counts differ between baseline and optimized runs.

### Behavioral verification

- Focused audio factory/resolver/registry/driver/sequencer tests.
- `TestAudioPresentationAllocationBudget` and new SFX admission budget.
- Audio snapshot/rewind parity and architecture guards.
- Full `mvn test` on the integration baseline, development worktree, merged
  `develop`, and reconciled S1 frontier worktree.
- Representative S1/S2/S3K ROM-backed audio or trace tests where available,
  with exact before/after trajectory and audio-observer results.

## Delivery and dual-branch integration

Development occurs on `bugfix/ai-audio-sfx-allocation`, created from the current
main-workspace `develop` branch. The main workspace remains on `develop`.

The delivery sequence is:

1. Preserve the main workspace's existing user-authored dirty file. Fetch the
   remote and fast-forward the checked-out `develop` branch only if doing so does
   not overwrite that change. If it overlaps, stop at the authority boundary.
2. Record the official updated `develop` full-suite baseline and exact failures.
   The preliminary pre-fetch baseline is diagnostic only.
3. Reconcile the updated `develop` head into the isolated feature worktree,
   implement, and verify the change there.
4. Record the S1 frontier worktree's full-suite baseline and exact failures
   before changing that worktree.
5. Merge the feature branch into `develop`, update `README.md` as required by
   policy, run the full regression comparison, and push `develop`.
6. Bring the exact semantic change into the existing clean worktree at
   `.worktrees/s1-audio-parity-frontier`. Because that branch is 52 audio-heavy
   commits ahead and 160 commits behind `develop`, do not merge all of `develop`
   merely to obtain this fix. Cherry-pick the landed feature commit(s), resolve
   conflicts deliberately against the frontier's newer admission/observer
   contracts, and commit the frontier-specific reconciliation.
7. Run the focused allocation/correctness tests and full suite in the frontier
   worktree. Compare its pre-port and post-port results so unrelated frontier
   failures are not attributed to this work.
8. Leave the frontier worktree and branch intact. It is an active user-requested
   destination, not temporary implementation scaffolding. Push it only if the
   user separately authorizes pushing that branch; otherwise report its local
   commit.
9. After successful `develop` integration/push and frontier reconciliation,
   remove the temporary `audio-sfx-allocation` worktree and delete its fully
   merged local branch per repository policy.

## Expected result

Spindash charge and every other repeated SMPS SFX trigger will stop cloning ROM
audio assets or unrelated live audio state. The remaining allocation represents
only the new SFX's mutable execution state and small bounded contention data,
while audio, rewind, and frontier diagnostic behavior remain exact.

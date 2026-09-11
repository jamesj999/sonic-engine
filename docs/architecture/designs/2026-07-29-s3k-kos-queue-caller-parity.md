# S3K Kosinski queue caller parity design

Date: 2026-07-29

## Goal

Make the implemented HCZ, MGZ, and LBZ seamless transition owners submit the same ordered
Kosinski work as the ROM and advance on the same queue predicate. Preserve a complete
caller audit so later object and zone ports can place queue work at the correct lifecycle
owner.

## Design

Each transition owns one immutable descriptor set:

- HCZ: direct HCZ2 secondary chunks, direct HCZ2 secondary blocks, then HCZ2 secondary
  pattern modules at tile `$11B`.
- MGZ: direct MGZ2 secondary chunks, direct MGZ2 secondary blocks, then MGZ2 secondary
  pattern modules at tile `$252`.
- LBZ: direct LBZ2 chunks, direct LBZ2 secondary blocks, then LBZ2 secondary pattern
  modules at tile `$19D`.

At the ROM event edge, the owner submits the descriptors to the session's
`S3kRuntimeArtCoordinator` in that order. It stores the returned handles and a submitted
flag. The existing event state continues to own all gameplay mutations. The transition
wait state polls `S3kKosModuleQueue.modulesLeft()`, matching the ROM's global
`Kos_modules_left` predicate. Direct and module handles are retained because they own
real FIFO work and must survive rewind/restoration and later claiming; no individual
handle substitutes for the global gate.

HCZ extends its current module-only submission. MGZ and LBZ remove their fixed drain
counters. Descriptor source offsets are ROM-verified constants, discovered from the
locked-on ROM labels. Direct descriptors retain the ROM's canonical 68000 destinations
because those addresses are part of stable timing identity:

- HCZ: `RAM_START + $A00` and `S3kKosRamDestinations.blockTableOffset($558)`;
- MGZ: `RAM_START + $6B00` and `S3kKosRamDestinations.blockTableOffset($C60)`;
- LBZ: `RAM_START` and `S3kKosRamDestinations.blockTableOffset($6B8)`.

Module descriptors retain the ROM VRAM tile destination.

When the global module queue is empty, each owner asserts its module handle and both
preceding direct handles are ready, claims
all three payloads, and then requests the seamless reload. The current level loader
installs the complete target-act resource set synchronously, so HCZ/MGZ/LBZ deliberately
discard the claimed transition payload bytes rather than applying a partial terrain
overlay. Claiming is still required to release timing-ledger payload ownership. If a
claim or transition request fails, the owner throws with zone/descriptor context; it does
not clear ordinals early or silently orphan prepared work.

## Ownership and lifecycle

The queue remains session-owned. Zone event classes submit work through injected runtime
services and never construct a second scheduler. Each event captures three scalar
ordinals (chunk direct, block direct, module) plus its ordinary state. Queue facades and
`HardwareWorkHandle`s are `@RewindTransient`. After the timing ledger restores, the event
rebinds each transient handle with
`HardwareTimingService.pendingHandle(kind, ordinal)`. Discard hooks clear only the
derived facade/handle fields; reset clears ordinals and local state, while coordinator
session reset clears the FIFO.

HCZ extends its existing rebind/discard methods. MGZ and LBZ add equivalent methods and
the level-event manager invokes them in the same restore ordering. Rewind schema guards
must admit only the annotated transient fields, and round-trip tests cover pending and
ready states without duplicate submission.

This design does not introduce a resource handoff ID for these three zones: the existing
reload owns target resources, and the transition queue payloads are claimed/discarded
immediately before requesting it. Runtime bytes come only from the ROM.

## Validation

Tests first establish:

- exact source, destination, kind, and submission ordering for HCZ/MGZ/LBZ;
- the transition remains pending while `modulesLeft()` is true, including after its own
  module handle becomes ready when a later KosM submission remains, and advances only
  when the global module queue is empty and its three handles are ready;
- direct work delays the transition when it blocks a KosM child in the shared FIFO, while
  later direct-only work after global module completion is not itself the ROM predicate;
- rewind round trips do not duplicate submissions or lose ordinal/handle identity;
- the removed MGZ/LBZ fixed-delay fields are absent.

Focused event and runtime-art tests run before the full JDK 21 suite. The disassembly
audit is the durable coverage map; it intentionally does not cause speculative queue
submissions for unimplemented owners.

# Profile adapter compatibility convergence

## Decision

`game.profiles` owns the shared provider-forwarding mechanics and ordinary
provider-to-touch-profile mapping. The existing `level.objects` records remain
as public compatibility identities: their component types, constructors,
accessors, static factories, and returned values are unchanged.

## Evidence and constraints

`level.objects.TouchResponseProfile` and
`game.profiles.touchresponse.TouchResponseProfile` are both present in the
unpublished 0.7 Mod API signature inventory. Removing either record, changing a
component type, or adding a public member to either annotated profile would
change the candidate surface. The candidate has no published baseline, but this
internal normalization has no creator-facing need and therefore does not update
the API descriptor, version, or signature pin.

The two solid adapter records had identical per-call forwards to
`SolidObjectProvider`. `SolidRoutineProviderForwarding` now owns the one
canonical set of static forwarding mechanics. Both records retain their
original declared public forwarding facades and interface hierarchy: public
record callers and declared-method reflection therefore observe the same shape.
Those repetitive facades only pass their existing provider and arguments to the
canonical static method. A solid-contact call still invokes the provider
directly and constructs no bridge adapter or per-frame object.

The ordinary touch mapper is likewise canonical:
`TouchResponseProfileMapper` maps the provider flags and an already-known
multi-region decision to the canonical profile. The canonical public factory
continues to determine region presence by reading `getMultiTouchRegions()` once.
The level compatibility overload supplies its caller-owned boolean and never
reads geometry, as its existing callers require.

## Intentional compatibility exception

The level-owned decode enum has `FORCE_ENEMY`; the canonical enum deliberately
does not. Consequently `TouchResponseProfile.fromProvider(provider, boolean)`
keeps its local force-enemy branch. It preserves both legacy behaviors:

- an enemy override maps to `FORCE_ENEMY`;
- combining that override with any special-property decode mode throws.

All non-force-enemy calls delegate to the canonical mapper. Expanding the
canonical annotated enum merely to erase this compatibility branch would alter
the Mod API candidate without a product requirement.

## Validation

`TestSolidRoutineProfiles` exercises provider forwarding through the retained
level adapter. `TestTouchResponseProfileMapping` covers ordinary canonical
round-tripping, caller-owned multi-region mapping without geometry access, and
the retained force-enemy branch and conflict rejection. Mod API signature and
guard checks remain required before integration.

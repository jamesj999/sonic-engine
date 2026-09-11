# Rewind Compact Record Codecs

## Goal

Capture immutable record values by component instead of storing the record object as an opaque reference.

## Scope

- Add regression coverage proving restored record fields are value-equivalent but not the same captured record instance.
- Support nullable record fields and nullable reference components for wrapper, `String`, and enum component types.
- Keep records with mutable components unsupported until dedicated component codecs exist.
- Leave standalone `String` field behavior unchanged for this slice.

## Steps

- [x] Add focused record codec tests.
- [x] Implement `RecordCodec` in `RewindCodecs`.
- [x] Rename the stale mutable-record rejection test.
- [x] Run focused record tests.
- [x] Run schema rewind regression tests.
- [x] Run all `*Rewind*` tests.

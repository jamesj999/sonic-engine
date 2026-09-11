# Rewind Annotation Density Cleanup

## Goal

Reduce `@RewindTransient` usage where central policy can classify fields safely by type.

## Scope

- Add built-in transient policy for common runtime/rendering infrastructure types.
- Remove redundant annotations from shared base classes only.
- Avoid broad leaf-object churn in this slice.
- Diff object-related files against `origin/develop` before finishing.

## Steps

- [x] Add a default-policy regression test.
- [x] Implement built-in runtime/rendering transient policies.
- [x] Remove redundant annotations from scoped shared classes.
- [x] Run focused policy/capture tests.
- [x] Diff object-related files against `origin/develop`.
- [x] Run all `*Rewind*` tests.

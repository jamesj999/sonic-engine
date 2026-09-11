# Rewind Inventory-Driven Object Rollout

## Goal

Make automatic object rewind coverage visible and auditable without adding per-object annotations or leaf-object churn.

## Checklist

- [x] Move the default object subclass capture decision behind a central rewind eligibility API.
- [x] Extend the inventory tool so it can report object classes currently covered by default subclass capture.
- [x] Keep deferred/transient fields out of unsupported-field inventory.
- [x] Add focused tests for default rollout eligibility and inventory reporting.
- [x] Run focused rewind/object tests and the rewind slice.
- [x] Check object diffs against origin so the rollout does not create unnecessary leaf-object file changes.

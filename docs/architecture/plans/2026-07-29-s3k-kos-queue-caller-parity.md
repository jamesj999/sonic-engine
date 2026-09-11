# S3K Kosinski queue caller parity implementation plan

Date: 2026-07-29

## 1. Lock ROM descriptor evidence

- Resolve all nine HCZ2, MGZ2, and LBZ2 archive sources with `RomOffsetFinder` and record
  the evidence. Use the S&K-half address when both halves match; retain a verified
  S3-half address when the runtime label genuinely has no S&K-half counterpart.
- Use the disassembly destinations exactly:
  `RAM_START+$A00`/`Block_table+$558`/tile `$11B`,
  `RAM_START+$6B00`/`Block_table+$C60`/tile `$252`, and
  `RAM_START`/`Block_table+$6B8`/tile `$19D`.
- Add tests that inspect queued descriptors and prove direct-direct-module FIFO order.

## 2. Add reusable transition submission ownership

- Prefer the smallest shared helper only if HCZ/MGZ/LBZ would otherwise duplicate the
  same submission, readiness, claim, ordinal, rebind, and discard rules.
- Keep state and transition mutations in each zone event owner.
- Represent queue facade/handles as transient derived state and three ordinals as captured
  state.
- If a helper is introduced, test its pending, ready, claim/discard, reset, and rewind
  restoration contract before connecting event transitions; otherwise cover the same
  contract directly in each zone test.

## 3. Correct HCZ

- Extend the existing HCZ submission edge to queue the two direct jobs before the existing
  module job.
- Gate on global `modulesLeft() == false`, assert all three owned jobs ready,
  claim/discard all payloads,
  then request HCZ2.
- Extend HCZ rewind rebind/discard/reset coverage for all three ordinals.
- Update existing HCZ event tests for exact ordering and no early transition.

## 4. Correct MGZ

- Replace `MGZ2_SECONDARY_KOS_DRAIN_FRAMES` and `transitionKosDrainFrames` with the exact
  three-job submission at `Events_fg_5`.
- Gate on global module-queue empty, claim/discard all payloads, then preserve the existing
  seamless transition request.
- Add MGZ rebind/discard hooks to the event manager restore path and test rewind without
  duplicate submission.

## 5. Correct LBZ

- Replace `LBZ2_SECONDARY_KOS_DRAIN_FRAMES` and
  `lbz2TransitionKosDrainFrames` with the exact three-job submission.
- Gate on global module-queue empty, claim/discard all payloads, then preserve LBZ's existing
  seamless reload request and state preservation.
- Add LBZ rebind/discard hooks to the event manager restore path and test rewind without
  duplicate submission.

## 6. Guard and validate

- Extend rewind schema/coverage guards for the new transient fields and manager callbacks.
- Add a shared contract test plus zone wiring assertions proving that:
  - a target module handle may be ready while a later KosM parent keeps
    `modulesLeft()` true, and the transition remains blocked;
  - direct work that blocks a KosM child delays global module completion through the
    shared FIFO; and
  - direct-only work submitted after global module completion is not the final transition
    predicate.
- Run focused runtime-art, HCZ, MGZ, LBZ, seamless transition, and rewind tests.
- Run the full JDK 21 Maven suite and compare with the updated integration baseline.
- Update `CHANGELOG.md` because this is a synchronization fix in `src/main`.
- Commit with required documentation trailers, integrate into the main `develop`
  workspace, repeat full verification, push `develop`, and remove the worktree/branch.

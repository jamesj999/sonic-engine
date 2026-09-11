# Develop Backport Candidates Design

## Goal

Cross-validate every claim in
`next:docs/architecture/audits/testing/develop-backport-candidates.md` against the
current `develop` branch, its history, the ROM disassembly, and executable tests,
then implement only the defects that still exist on `develop`.

## Cross-validation results

| Audit item | Result on current `develop` | Disposition |
|---|---|---|
| 1. Automatic tunnel native position writes | Already fixed by `3988590c9` and subsequent tunnel work. All nine writes use `NativePositionOps`. | No code change. Retain the existing tunnel tests. |
| 2. AWT in `WindowIconLoader` | Already fixed by `866b481b9`; `8710e2539` restored the macOS icon with LWJGL Objective-C bindings without AWT. Existing guards cover both production AWT and this file. | No code change. Run the guards. |
| 3. Rewind capture diagnostic | Still present. A null snapshot reports only the registered key; a bad or mocked adapter is not identifiable when that key is null or misleading. | Add the adapter class name to the exception and assert both key and class in `TestRewindRegistry`. |
| 4. Modifier documentation guard | Still present. `readSitesOf` assigns an entire statement to every inline binding reference, so sibling calls in an OR expression contaminate one another. An arbitrary widest enclosing call is also insufficient because a wrapper can contain both sibling input calls. | Preserve statement-level handling for hoisted locals, but reduce inline reads to the immediate call outside the configuration getter that contains the binding. Add mixed sibling and wrapper-call regression tests. |
| 5. AIZ renderer/executor mismatch | The audit's proposed either/or is obsolete. The current executor deliberately publishes AIZ2 terrain and art only when the ROM-modeled hardware jobs become ready. Restoring eager overlays would violate the hardware timing contract. After item 7 services those boundaries, phase progression becomes green and only the two descriptor-sampling diagnostics remain red. | Do not revert production timing or invent layout writes. Keep the two remaining renderer diagnostic failures recorded as baseline rather than encoding an unverified renderer model in this backport. |
| 6. S3K dynamic slot count | Valid despite the audit's contradictory final bullet. `Dynamic_object_RAM` occupies absolute slots 3-92, but `AllocateObject` seeds its cursor at slot 3, pre-increments, and executes 90 `dbeq` probes from slots 4 through 93 inclusive. `Offset_ObjectsDuringTransition` likewise starts at slot 4 and executes 90 `dbf` iterations through slot 93. Slot 93 is the first, empty `Level_object_RAM` SST, but it is intentionally included in these ROM loops. | Change `dynamicSlotCount` from 89 to 90, pin allocation/reservation and initial dynamic dispatch through absolute slot 93, and start post-dynamic fixed dispatch at slot 94. Treat trace movement as expected evidence to assess, never as a reason to restore the incorrect ROM model. |
| 7. AIZ ROM test hardware servicing | Valid. The test directly calls `events.update`, but AIZ KosM readiness now advances through `VINT_SERVICE`, `PRE_MAIN_LOOP`, and `POST_OBJECTS`. | Add the same hardware-service helper used by `TestSonic3kAIZEvents` and route all act 1/act 2 event updates through it. |

## Considered approaches

### A. Blindly reproduce the `next` merge resolutions

This is low effort but wrong for the current branch. It would duplicate fixes already
landed for items 1 and 2, carry an internally contradictory slot decision, and risk
restoring eager AIZ art publication that the hardware-timing work intentionally removed.

### B. Selective, evidence-backed backport (chosen)

Treat each audit entry as a hypothesis. Implement items 3, 4, 6, and 7; recognize items
1 and 2 as already complete; reject only an
eager-overlay interpretation of item 5. This keeps
the patch narrow, ROM-backed, and compatible with the current timing architecture.

### C. Expand the task into a full AIZ fire-curtain renderer redesign

This could attempt to replace the renderer's level-layout sampling with a VDP-name-table
model. That is not a backport: it requires new production architecture, visual reference
capture, and a dedicated design. It also is not necessary to validate or implement the
straightforward candidates in this audit.

## Implementation boundaries

Production changes are limited to the `RewindRegistry` diagnostic, the ROM-correct
S3K dynamic-slot count, and the matching initial-dispatch ownership boundary. Guard/test
changes are limited to the modifier parser, the rewind diagnostic assertion, slot boundary
and initial-order characterization, and hardware servicing in the AIZ ROM-backed test. No runtime asset is
read from the disassembly; the disassembly is used only as the source of truth for the
slot-window interpretation and event sequencing.

## Testing

Use strict red-green cycles:

1. Add a rewind assertion that fails because the adapter class is absent.
2. Add a mixed inline-call modifier fixture that fails because the unchecked sibling is
   attributed to the no-modifier call.
3. Add slot-allocation boundary coverage that fails at the old limit, then proves
   absolute slots 4-93 are allocatable and slot 94 is not.
4. Confirm the AIZ ROM test's hardware-continuation assertion fails before servicing and
   passes after servicing. Its two pre-existing renderer descriptor diagnostics remain part
   of the recorded baseline unless independent ROM evidence supports a production fix.

Focused verification covers the modified test classes, production AWT guards, automatic
tunnel tests, object manager/slot allocator tests, and the AIZ event suite, followed by
the full Maven suite. Because the slot correction can change object identity, run the
available `*TraceReplay` sweep with the discovered ROM paths and record its baseline;
investigate any changed frontier against ROM state rather than reverting the slot count.

`TestSonic3kAIZEvents` has an independent baseline of three failures and one error on
this `develop` commit (`introObjectIsReadyBeforeFirstAizGameplayFrame`,
`setupProcessSpritesAdvancesIntroScrollExactlyOnceBeforeLevelLoop`,
`introSidekickDormantMarkerBeginsOnFirstOrdinaryPlayer2Dispatch`, and
`eventsFg5TransitionWritesProgressionSaveForActiveSlot`). They reproduce when the class
runs alone and are not changed by this backport.

## Documentation and delivery

Record this design and its implementation plan under `docs/architecture/`. Update
`CHANGELOG.md` for the production S3K slot correction and include the improved rewind
diagnostic context. Integrate through the repository's isolated-worktree workflow,
compare against an updated `develop` baseline, merge to the main workspace, push
`develop`, and remove the implementation worktree and local branch.

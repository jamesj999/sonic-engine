# S3K CNZ Traversal Hotfix Design

**Scope**

This hotfix slice repairs only the traversal objects we already claimed in the prior CNZ object pass:

- `CNZCylinder`
- `CNZHoverFan`
- `CNZBalloon`
- `CNZSpiralTube`

It does not include:

- `CNZGiantWheel`
- `CNZBarberPoleSprite`
- `CNZWireCage`
- broader CNZ miniboss arena completion

Those are separate CNZ content gaps, not regressions in the traversal-first slice.

**Failure Framing**

- `CNZCylinder`
  The current implementation is likely physically inert in gameplay because [CnzCylinderInstance.java](/C:/Users/farre/.t3/worktrees/sonic-engine/t3code-5c28097e/src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java) still extends `AbstractCnzTraversalVisibleStubInstance` and only advances render state after capture. The user report says it does not animate or move as expected and the player falls through it. This hotfix must restore traversal-state entry, physical interaction, and visible progression.

- `CNZHoverFan`
  The current implementation in [CnzHoverFanInstance.java](/C:/Users/farre/.t3/worktrees/sonic-engine/t3code-5c28097e/src/main/java/com/openggf/game/sonic3k/objects/CnzHoverFanInstance.java) reevaluates the lift window every frame with no persistent carry state. The user report says the fan sometimes stops affecting the player mid-sequence. This hotfix must prove or disprove that the player is being dropped because of unstable eligibility math and repair that seam.

- `CNZBalloon`
  The current implementation in [CnzBalloonInstance.java](/C:/Users/farre/.t3/worktrees/sonic-engine/t3code-5c28097e/src/main/java/com/openggf/game/sonic3k/objects/CnzBalloonInstance.java) keeps rendering a popped frame (`+3`) after launch. The user report says balloons freeze on their last frame instead of disappearing, and may have a palette issue. This hotfix must repair the popped-object render/lifecycle seam and only change palette handling if ROM-backed evidence supports it.

- `CNZSpiralTube`
  The current implementation in [CnzSpiralTubeInstance.java](/C:/Users/farre/.t3/worktrees/sonic-engine/t3code-5c28097e/src/main/java/com/openggf/game/sonic3k/objects/CnzSpiralTubeInstance.java) models the sway/descent/route phases, but the user report says the player gets stuck at the end instead of shooting upward through the trap-door path. This hotfix must restore the end-of-route handoff, not just the internal route completion.

**Repair Strategy**

The hotfix is split into three code slices plus validation:

1. `Cylinder`
   Restore physical interaction, traversal-state entry, and visible progression.

2. `HoverFan` and `Balloon`
   Repair unstable carry persistence and popped/disappearance behavior. These two share the local-traversal test surface and can be fixed together without conflicting with directed/tube traversal files.

3. `SpiralTube`
   Repair the end-of-route upward handoff while preserving the already-verified S&K-side route-table and same-frame release semantics wherever they are correct.

4. Validation
   Re-run the focused CNZ traversal regressions and extend the validation artifact only for the repaired seams.

**Implementation Rules**

- Every fix must start from a failing regression test or a deterministic capture seam.
- Javadocs and short code comments must tie repaired behavior to specific ROM routines or justify the engine-side alternative.
- Do not pull `GiantWheel`, `BarberPole`, `WireCage`, or miniboss completion into this slice.
- Prefer repairing existing object classes and tests over inventing new CNZ-local infrastructure.

**Success Criteria**

- `CNZCylinder`
  - player can enter the traversal reliably in gameplay
  - traversal visibly progresses instead of appearing static/inert
  - the player no longer simply falls through during the interaction

- `CNZHoverFan`
  - the player remains affected while still inside the valid force window
  - no random mid-carry dropouts caused by unstable window reevaluation

- `CNZBalloon`
  - popped balloons no longer freeze on a visible terminal frame
  - the object disappears or otherwise matches ROM lifecycle intent after pop
  - palette handling is corrected only if ROM evidence shows the current sheet/palette line is wrong

- `CNZSpiralTube`
  - the route no longer dead-ends
  - the player reaches the expected upward escape handoff instead of remaining trapped at the route endpoint

**Validation**

Behavioral validation is primary:

- `TestS3kCnzDirectedTraversalHeadless`
- `TestS3kCnzLocalTraversalHeadless`
- `TestS3kCnzTubeTraversalHeadless`
- `TestCnzTraversalObjectArt`
- `TestCnzTraversalRegistry`

Visual validation is secondary and should only be added where the repaired seam is difficult to prove numerically:

- `Cylinder`
- `Balloon`
- `SpiralTube`

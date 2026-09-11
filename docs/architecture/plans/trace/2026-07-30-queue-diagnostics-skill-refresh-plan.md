# Queue diagnostics skill refresh plan

1. Inventory trace/PLC skill ownership and current queue guidance.
2. Add one concise shared diagnostic contract to the operational `.agents`
   skills, tailored to capture, replay, fleet, PLC, S3K, and stable-retro use.
3. Add a short queue/`dynamic_art` routing note to every remaining project
   skill so all 24 skills direct the work to the authoritative owner.
4. Mirror all 24 updated files byte-for-byte into `.claude`.
5. Run mirror, frontmatter, terminology, link/path, whitespace, and policy
   checks.
6. Obtain independent skill-content review; correct every valid issue and
   repeat until clean.
7. Add the required `README.md` release-log entry and commit the complete change
   on the feature worktree.
8. Fetch the remote and fast-forward the main-workspace `develop` branch
   without disturbing unrelated user changes.
9. Run and record the full suite on the updated `develop` baseline, then run
   the same suite plus focused skill/policy checks in the feature worktree.
10. Merge the feature branch into main-workspace `develop`, run the full suite
    and focused checks again, and compare the result with the recorded baseline.
11. Push only `develop`, verify the feature worktree is clean and fully merged,
    remove it, delete the merged local feature branch, and prune worktree
    metadata.

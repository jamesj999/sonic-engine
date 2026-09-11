# S3K Knuckles Super-Emerald Trace Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the complete Knuckles super-emerald BK2 as an immutable, manifest-driven S3K trace run with reviewed hardware-timing evidence.

**Architecture:** Use the existing native BizHawk complete-run recorder in `--run-id` mode and install its atomic, compressed output under a new `runs/` identity. Freeze the independently reviewed output in the hardware-timing TSV and extend the existing committed-fixture guard to own two run manifests and two run-prefix inventories.

**Tech Stack:** BizHawk 2.11 / GPGX, Mono native recorder harness, JSON/JSONL, gzip, Java 21, JUnit 5, Maven.

## Global Constraints

- Run id: `s3k-knuckles-complete-superemeralds`.
- Destination: `src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds/`.
- Curated movie: `src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2`.
- Source BK2 SHA-256: `aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc`.
- BK2 header checksum token / ROM MD5: `C5B1C655C19F462ADE0AC4E17A844D10`.
- Locked-on ROM SHA-1: `CFBF98C36C776677290A872547AC47C53D2761D6`; CRC32: `63522553`.
- The source movie and ROM are resolved from the main-workspace root; generated destinations remain in the feature worktree.
- Every segment publishes `metadata.json`, `physics.csv.gz`, `aux_state.jsonl.gz`, and a nonempty `hardware_timing.jsonl`.
- Generated physics, aux, and hardware timing are comparison-only and never hydrate engine state.
- Existing Sonic and `s3-knux-multibonus-ss` fixtures remain byte-unchanged.

---

### Task 1: Capture and audit the new run

**Files:**
- Create: `src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2`
- Create: `src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds/**`
- Create: `docs/architecture/validation/2026-07-30-s3k-knuckles-superemerald-trace-publication.md`

**Interfaces:**
- Consumes: Native `tools/bizhawk-headless/run.sh --run-id` publication contract.
- Produces: An atomic run tree, manifest, and reviewed literal inventory for Task 2.

- [ ] **Step 1: Verify source identities**

Run:

```bash
MAIN_ROOT=/path/to/main/OpenGGF
SOURCE_BK2="$MAIN_ROOT/docs/BizHawk-2.11-linux-x64/Movies/s3k-knuckles-complete-superemeralds.bk2"
find "$MAIN_ROOT" -maxdepth 1 -type f -iname '*.gen' -print
sha256sum "$SOURCE_BK2"
unzip -p "$SOURCE_BK2" Header.txt
unzip -p "$SOURCE_BK2" 'Input Log.txt' | awk '/^\|/{n++} END{print n}'
```

Hash each discovered locked-on candidate rather than assuming a filename:

```bash
md5sum "$S3K_ROM_PATH"
sha1sum "$S3K_ROM_PATH"
python3 -c 'import sys,zlib; print(f"{zlib.crc32(open(sys.argv[1], \"rb\").read()) & 0xffffffff:08X}")' "$S3K_ROM_PATH"
```

Expected: MD5 `c5b1c655c19f462ade0ac4e17a844d10` matches the
BK2 token case-insensitively; the other hashes and header values match Global
Constraints; and the movie has 434,417 input rows. Record the exact commands,
selected path, and output in the validation report.

- [ ] **Step 2: Curate the BK2 byte-for-byte**

```bash
cp "$SOURCE_BK2" \
  src/test/resources/traces/s3k/_movies/
sha256sum src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2
```

Expected: SHA-256 remains `aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc`.

- [ ] **Step 3: Record into a new scratch directory**

```bash
BIZHAWK_HOME="$MAIN_ROOT/docs/BizHawk-2.11-linux-x64" \
tools/bizhawk-headless/run.sh \
  --rom "$S3K_ROM_PATH" \
  --movie "$PWD/src/test/resources/traces/s3k/_movies/s3k-knuckles-complete-superemeralds.bk2" \
  --output "$PWD/tools/bizhawk-headless/.scratch/s3k-knuckles-complete-superemeralds" \
  --mode trace \
  --run-id s3k-knuckles-complete-superemeralds
```

Expected: exit 0; stdout reports all segments/transitions and a run manifest; the output path did not exist before invocation.

- [ ] **Step 4: Freeze and independently review the capture inventory**

Write the validation report with:

- the recorder stdout;
- ordered manifest segments with directory, kind, trace profile, zone/act, BK2 offset, and frame count;
- ordered transitions and sampled boundary fields;
- proof, using recorder movie-length/finalization arithmetic, that the final active segment was finalized through the 434,417-row movie end;
- byte length and SHA-256 of `run_manifest.json` and all four files in every segment;
- per-segment trace/hardware schema, recorder version, event count, boundary counts, first edge, and last edge.

Delegate the report and manifest to a reviewer. Fix or recapture every valid segmentation, truncation, identity, or inventory issue until no blocking issue remains.

- [ ] **Step 5: Install the reviewed atomic output**

```bash
cp -a tools/bizhawk-headless/.scratch/s3k-knuckles-complete-superemeralds \
  src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds
find src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds \
  -type f | sort
```

Expected: exactly one manifest plus the reviewed segment directories and their four-file sets; no plain `physics.csv` or `aux_state.jsonl`.

---

### Task 2: Pin the immutable publication

**Files:**
- Modify: `src/test/java/com/openggf/trace/timing/TestCommittedHardwareTimingFixtures.java`
- Modify: `src/test/resources/traces/s3k/hardware-timing-publication.tsv`

**Interfaces:**
- Consumes: Reviewed literal values from Task 1.
- Produces: Exact ownership, destination, hash, length, edge, and run-manifest regression checks.

- [ ] **Step 1: Extend expected ownership before adding TSV rows**

Add the new run's exact segment destinations to `EXPECTED_DESTINATIONS`. Replace the single hardcoded run-prefix ownership branch with an immutable map from run prefix to `(owner, runId)`, using:

- `runs/s3-knux-multibonus-ss/` → owner `v637-knuckles-b`, run id `s3-knux-multibonus-ss`;
- `runs/s3k-knuckles-complete-superemeralds/` → owner `v638-knuckles-superemeralds`, run id `s3k-knuckles-complete-superemeralds`.

Change run-manifest assertions from one hardcoded manifest to an exact two-entry expected map containing path, owner, segment count, and transition count.

Preserve leaf ownership: each run-prefix rule must still assert that
`source_segment` equals the destination's final path token and that segment
metadata carries the mapped `run_id`.

Classify the new schema-2 segments against the direct-consumer contract and add
every qualifying destination to `SCHEMA_TWO_DIRECT_CONSUMER_FIXTURES` with its
semantic consumer label. Update that test's exact expected key set. The likely
AIZ/HCZ/MGZ/CNZ/ICZ candidates must be decided from captured route evidence,
not included merely because their directory names match a zone.

- [ ] **Step 2: Run the guard and verify it fails red**

```bash
mvn -Dmse=off "-Dtest=TestCommittedHardwareTimingFixtures" test
```

Expected: FAIL because the TSV does not yet contain the new expected destinations/manifests.

- [ ] **Step 3: Add reviewed literal TSV records**

Append one `FIXTURE` row per reviewed segment using the exact Task 1 values, owner `v638-knuckles-superemeralds`, and source segment equal to the manifest directory token. Append one `RUN_MANIFEST` row with its exact path, owner, byte length, SHA-256, segment count, and transition count. Do not derive expected values during the test.

- [ ] **Step 4: Run the publication guard green**

```bash
mvn -Dmse=off "-Dtest=TestCommittedHardwareTimingFixtures" test
```

Expected: PASS with the exact two-run publication fleet.

---

### Task 3: Document and validate the publication

**Files:**
- Modify: `tools/bizhawk/README.md`
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `docs/status/trace-frontier-log.md` only if a run replay/sweep is executed and moves or establishes a frontier

**Interfaces:**
- Consumes: Final reviewed inventory and focused test results.
- Produces: Reproducible capture instructions and repository release/change-log evidence.

- [ ] **Step 1: Document the capture identity**

Add the exact native capture command, source/run IDs, provenance hashes, segment/transition totals, recorder/schema versions, and immutable publication location to `tools/bizhawk/README.md`. Add concise entries to `CHANGELOG.md` and the `README.md` release/change log.

- [ ] **Step 2: Run focused native-recorder checks**

```bash
BIZHAWK_HOME="$MAIN_ROOT/docs/BizHawk-2.11-linux-x64" \
tools/bizhawk-headless/test.sh --filter S3KCompleteRun
BIZHAWK_HOME="$MAIN_ROOT/docs/BizHawk-2.11-linux-x64" \
tools/bizhawk-headless/test.sh --filter S3KRunMode
```

Expected: all selected tests pass.

- [ ] **Step 3: Run focused Java fixture/catalog checks**

```bash
mvn -Dmse=off "-Dtest=TestCommittedHardwareTimingFixtures,TestTraceFixtureCompressionGuard,TestTraceCatalogRunDiscovery,TraceCatalogTest,TraceCatalogSpecialStageTest" test
```

Expected: all tests pass with no uncompressed fixture additions.

The Task 1 ROM-backed full capture plus its independently reviewed terminal
evidence is the exact-new-movie verification. The existing differential filters
remain regression coverage for the recorder's already-pinned identities.

- [ ] **Step 4: Verify repository policy and artifacts**

```bash
git status --short
git diff --check
find src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds \
  -type f \( -name 'physics*.csv' -o -name 'aux_state*.jsonl' \)
```

Expected: design, plan, validation report, documentation, curated BK2, run fixtures, TSV, and guard changes are present; diff check is clean; the final `find` emits nothing.

- [ ] **Step 5: Commit the development branch**

Stage every task artifact and commit with the required trailers. Set
`Changelog: updated`; set `Agent-Docs: n/a` because neither `AGENTS.md` nor
`CLAUDE.md` changes; set other mapped documentation trailers according to the
files actually staged. Never bypass hooks.

---

### Task 4: Integrate and deliver

**Files:**
- Integration target: main-workspace `develop`

**Interfaces:**
- Consumes: Reviewed, committed feature branch.
- Produces: Pushed `develop` with no regression beyond the recorded updated baseline.

- [ ] **Step 1: Update the integration baseline safely**

Fetch and fast-forward pull `develop` in the main workspace without disturbing
its unrelated user changes:

```bash
git fetch origin
git pull --ff-only origin develop
mvn -v
```

Ensure Maven reports JDK 21.

- [ ] **Step 2: Record the full-suite baseline**

Run on updated `develop`, substituting the three discovered absolute paths:

```bash
mvn -Dmse=off \
  "-Dsonic1.rom.path=$S1_ROM_PATH" \
  "-Dsonic2.rom.path=$S2_ROM_PATH" \
  "-Ds3k.rom.path=$S3K_ROM_PATH" test
```

Record exact failures/errors/skips.

- [ ] **Step 3: Verify the development worktree**

Run the same full suite plus the focused native and Java commands from Task 3 in the feature worktree. Compare with baseline; no new or worsened failure is acceptable.

- [ ] **Step 4: Merge and verify**

Merge without switching the main workspace:

```bash
git merge --no-ff feature/ai-s3k-knuckles-superemerald-traces
```

Resolve conflicts carefully, run the exact full-suite command from Step 2
again, and compare against the recorded baseline.

- [ ] **Step 5: Push and clean up**

Push only `develop` with `git push origin develop`. Verify the feature worktree
is clean and fully merged, remove it with `git worktree remove`, delete the
fully merged local feature branch with `git branch -d`, and run
`git worktree prune`.

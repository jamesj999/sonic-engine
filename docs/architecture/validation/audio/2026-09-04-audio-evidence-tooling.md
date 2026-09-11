# Audio evidence tooling validation (2026-09-04)

## Scope

Validation of Task 3's ROM-free FM-core benchmark tooling on
`feature/ai-audio-round-evidence`, based on `4296bc291`. No publishable timing
run was taken; the smoke dimensions intentionally exercise behavior only.

## Commands and outcomes

```bash
tools/testing/install-hooks.sh
tools/audio/fm-core-benchmark/tests/test-tool.sh
```

PASS. The boundary test verified a correct source lock, rejected a changed
source hash and malformed lock row, accepted complete passing result data,
rejected a Java/C checksum mismatch, rejected an inert negative control, and
rejected malformed implementation identifiers, booleans in integer fields,
missing or malformed timing arrays, non-finite/negative timings, and mismatched
measurement dimensions. It also rejected output outside the invoking
worktree's `target/`. Both entry points
also rejected a `target/` symlink escape without creating a directory beyond
the target tree, and expose executable `--help` usage.

```bash
tools/audio/fm-core-benchmark/run.sh \
  --output "$PWD/target/fm-core-benchmark-smoke" \
  --nuked-source "<external-scratch>/audio-bench-20260904/nuked" \
  --ymfm-source "<external-scratch>/audio-bench-20260904/ymfm" \
  --frames 512 --warmups 0 --iterations 1
python3 -m json.tool target/fm-core-benchmark-smoke/result.json >/dev/null
```

PASS. The source verifier checked three pinned Nuked files and ten pinned ymfm
files. Java Nuked and C Nuked produced the same order-, sign-, and
channel-sensitive FNV-1a stream hash (`5049908672440343513`). Snapshot replay
matched sample by sample for Java Nuked, C Nuked, and C++ ymfm; each active
key-off negative control changed its subsequent output. The result is valid
schema-v1 JSON and marks itself non-publishable. Its provenance includes the
three production Java core hashes, both retained harness-source hashes, fixed
native compiler flags, upstream lock hashes, and both Java runtime/compiler
versions; the runner requires both `java` and `javac` to report major version
21.

```bash
bash -n tools/audio/fm-core-benchmark/run.sh \
  tools/audio/fm-core-benchmark/fetch-sources.sh \
  tools/audio/fm-core-benchmark/tests/test-tool.sh
PYTHONPYCACHEPREFIX="$PWD/target/pycache" python3 -m py_compile \
  tools/audio/fm-core-benchmark/verify-source.py \
  tools/audio/fm-core-benchmark/assemble-results.py
git diff --check
```

PASS with no output. A `g++ -MM` dependency inventory over the retained native
harness and all three compiled ymfm translation units named exactly the nine
ymfm code inputs in `ymfm.lock`; the lock additionally pins the upstream
licence. The Nuked build consumes the two code inputs in `nuked.lock`, which
likewise pins the upstream licence.

## Staged deliverables

- `tools/audio/fm-core-benchmark/README.md`
- `tools/audio/fm-core-benchmark/run.sh`
- `tools/audio/fm-core-benchmark/fetch-sources.sh`
- `tools/audio/fm-core-benchmark/verify-source.py`
- `tools/audio/fm-core-benchmark/assemble-results.py`
- `tools/audio/fm-core-benchmark/JavaNukedBenchmark.java`
- `tools/audio/fm-core-benchmark/native_benchmark.cpp`
- `tools/audio/fm-core-benchmark/nuked.lock`
- `tools/audio/fm-core-benchmark/ymfm.lock`
- `tools/audio/fm-core-benchmark/tests/test-tool.sh`
- `docs/architecture/research/audio/2026-09-04-fm-core-performance-exploration.md`
- `docs/architecture/plans/audio/2026-09-04-audio-parity-handover.md`
- `docs/changelog/v0.6-release-summary.md`
- this validation record

## Limitations

This validates tool behavior and one small synthetic execution, not throughput,
audible parity, hardware accuracy, or backend suitability. It uses locally
available pinned upstream checkouts solely as build input; none are repository
deliverables. Generated classes, objects, executables and JSON remain below the
worktree's ignored `target/` tree. The lead owns combined Maven suites and
integration testing. The pinned network fetch was not repeated in this lane.
The already-fetched checkout identities were separately verified as the
documented commits and trees; the smoke runner itself revalidated every
compiled-input content hash, but does not inspect Git identity.

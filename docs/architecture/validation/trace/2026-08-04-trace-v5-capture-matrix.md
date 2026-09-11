# Trace v5 scratch capture matrix

This is the maintained Task 9 Phase A command boundary. It supersedes the
historical [July native-fleet plan](../../plans/trace/2026-07-29-native-trace-fleet-regeneration-plan.md)
without editing that research record. The machine-readable matrix is
[`2026-08-04-trace-v5-capture-matrix.json`](2026-08-04-trace-v5-capture-matrix.json).

The matrix contains exactly 36 serial invocations: the retained S1/S2/S3K
movie rows, the upstream S1 emerald run, the independent 67-segment Knuckles
super-emerald run, and two distinct movie-free S1 credits captures. Each row
freezes its ROM/BK2 identity, selector, scratch output root, and publication
mapping. The first credits capture owns the eight credits destinations; the
second is independent determinism evidence and owns no candidate path.

Validate and expand without launching BizHawk:

```bash
python3 tools/bizhawk-headless/trace_v5_capture_matrix.py validate
python3 tools/bizhawk-headless/trace_v5_capture_matrix.py \
  expand --batch-root tools/bizhawk-headless/.scratch/<absent-batch>
python3 tools/bizhawk-headless/trace_v5_capture_matrix.py \
  preflight --batch-root tools/bizhawk-headless/.scratch/<absent-batch> \
  --candidate-root tools/bizhawk-headless/.scratch/<absent-candidate>
```

`preflight` verifies the reviewed source/diff and native artifacts, the
verified three-ROM and movie hashes, the unchanged 913-file predecessor
inventory, scratch capacity, and absence of every output and raw sidecar. The
`assemble` operation is scratch-only and no-replace: it copies static BK2
inputs, maps captured roots according to the reviewed declarations, and
refuses an existing candidate or destination collision. Neither command
modifies `src/test/resources/traces`; no production capture is part of Phase A.

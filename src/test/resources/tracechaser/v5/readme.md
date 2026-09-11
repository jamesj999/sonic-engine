# Trace-v5 semantic conformance pack

This bounded pack contains synthetic trace-schema-5 documents only. It contains
no ROM bytes, BK2 movie data, or copied canonical OpenGGF traces.

`manifest.json` pins every member's stored SHA-256 and byte length. Deterministic
gzip members additionally pin logical SHA-256 and length. Every case names the
TraceChaser producer entry, the real OpenGGF Java consumer entry for the Task 10
copy, and normalized accepted semantics or structured, source-pinned consumer
diagnostics. Array order in normalized semantics is significant. Neutral JSON
containers carry deliberately malformed or nondeterministic gzip bytes; each
case declares the exact logical parser target that Task 10 must materialize.

Run `python3 traces/validate_v5_conformance.py` from the repository root. The
validator checks identities, rejects unmanifested members, runs every case
through `traces.validate_trace_v5.Validation`, and compares accepted values
instead of treating hashes as semantic proof.

Regenerate into an empty directory with
`python3 traces/generate_v5_conformance.py <directory>` and compare the result
byte-for-byte. Do not hand-edit generated members.

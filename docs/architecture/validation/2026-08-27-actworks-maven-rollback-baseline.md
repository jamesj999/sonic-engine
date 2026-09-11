# Actworks Maven rollback baseline

- OpenGGF baseline commit: `b50a6edc454e7e8fbbcd99e91878133aa7e6b0bc`
- Maven: `3.9.16`
- JVM: `Java 21.0.11` (Arch Linux OpenJDK)
- Raw pre-rollback command: `mvn -Dmse=off test -B`
- Raw pre-rollback result: exit 1 during `openggf-session-validate-guard`;
  `missing session identity properties`; no tests executed.
- Ordinary baseline command: `tools/testing/test-session.sh -- mvn -Dmse=off test -B`
- Ordinary result: exit 1; 15,160 tests, 44 failures, 26 errors, 45 skipped.
- Ordinary red identities: 70 in
  `2026-08-27-actworks-maven-ordinary-red-set.txt`.
- Ordinary run: `20260827T085330Z-p2-b5f21b`.
- Ordinary manifest: `$AGENT_SCRATCH_ROOT/codex/test-sessions/session-20260827T085330Z-ee7fbf4d/20260827T085330Z-p2-b5f21b/manifest.json`.
- Ordinary terminal log: the same run directory's `maven.log.gz`; gzip compaction
  reclaimed 717,901,022 bytes.
- Guards baseline command: `tools/testing/test-session.sh -- mvn -Dmse=off -Pguards test -B`
- Guards result: exit 1; 557 tests, 18 failures, 1 error, 0 skipped.
- Guards red identities: 19 in
  `2026-08-27-actworks-maven-guards-red-set.txt`.
- Guards run: `20260827T091115Z-p2-b58336`.
- Guards manifest: `$AGENT_SCRATCH_ROOT/codex/test-sessions/session-20260827T091115Z-91f29552/20260827T091115Z-p2-b58336/manifest.json`.
- Guards terminal log: the same run directory's `maven.log.gz`; gzip compaction
  reclaimed 715,596,406 bytes.

The existing red-set comparer rejected the ordinary baseline solely because it
treats every skipped test as a contract failure. The failure/error identities
were therefore extracted directly from the preserved Surefire XML; skipped
count remains recorded separately above.

# Trace scope for release 7

The 0.7 campaign roadmap inherits the existing comparison and evidence rules
from [release 6](trace-scope-release-6.md). Historical failures are not reset by
branch promotion, and old measurements are not claimed as new passing results.

- Preserve passing traces and compare known failures by test identity, owning
  report, first divergence, and failure details against a fixed baseline.
- Keep fixture integrity, timing authority, and structural guards enabled.
- Record newly verified frontiers in [the frontier log](trace-frontier-log.md).
- Use the [0.7 campaign roadmap](../project/v0.7-roadmap.md) to expand route and
  terminal-chain coverage. Branch integration alone does not prove campaign
  completion or hardware/audio parity.

The rollover validation record covers ordinary suites, structural guards, and
focused integration checks. It is not a new full trace-parity certification.

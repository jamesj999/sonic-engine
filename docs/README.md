# OpenGGF documentation

OpenGGF's documentation is organized by audience and purpose.

- [Agent workflow](agent-workflow/) contains agent procedures, runbooks, and
  reusable prompts.
- [Architecture](architecture/) contains current architecture references and
  dated engineering artifacts.
- [Assets](assets/) contains presentation and documentation assets.
- [Changelog](changelog/) contains detailed release history.
- [Guide](guide/) contains player, contributor, and ROM cross-referencing
  documentation.
- [Project](project/) contains project history, roadmaps, and process
  direction.
- [Status](status/) contains active bug, discrepancy, trace-frontier, and
  release-state ledgers.

Architecture artifacts are classified by the question they answer:

- [Designs](architecture/designs/) — what should be built?
- [Plans](architecture/plans/) — how will it be built?
- [Research](architecture/research/) — how does the system or source material
  work?
- [Audits](architecture/audits/) — what state is the project currently in?
- [Validation](architecture/validation/) — did an implementation meet its
  contract?

External trace producers and analysis utilities are documented in the pinned
[`OpenGGF/TraceChaser`](https://github.com/OpenGGF/TraceChaser) submodule under
`tools/tracechaser/docs/`. OpenGGF's dated extraction design, plan, inventory,
and validation records remain here as historical engineering evidence.

Domain names such as audio, testing, trace, performance, or S3K zones may
appear as subfolders inside one of those categories. They do not form a
parallel top-level taxonomy.

Audio: the sound-driver reverse-engineering artifacts (driver maps, gap
analysis, behaviour specs, and oracle records) are indexed in
[architecture/designs/audio/2026-08-30-sound-driver-re-index.md](architecture/designs/audio/2026-08-30-sound-driver-re-index.md).

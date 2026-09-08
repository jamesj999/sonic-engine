# Next-line subsystem contracts

Read this reference when changing the next-line Mod API or network contracts.

**Mod API (`com.openggf.game.patch`, `com.openggf.mod*`).** Owner-tagged additive
`GamePatch` decorators sit over a root `GameModule`. The engine-owned
`ModuleResolutionService` resolves enabled built-in/mod owners in dependency order at
gameplay launch choke points; `WorldSession` keeps both root and resolved modules so
repeated resolution never double-wraps. Metadata/prerequisite failures disable the owner
and its dependents while independent owners continue; arbitrary creator `apply` failures
abort launch, because partial input mutation is not rollback-safe. `WorldSession` also
owns the module's `GameDataSource` — ROM or bounded standalone assets.

Creator content targets the unpublished mutable `@ModApi` 0.7.0 candidate surface (no Mod
API baseline has been published) via the two-artifact `ggfmod` toolchain: namespaced
objects and art, complete Sonic 2 zones, ROM-art intake, owner-tagged playable characters
(`CharacterKey` identities over the immutable module registry), no-ROM standalone modules
(`AbstractStandaloneGameModule`, durable `GameDataSource`, game-agnostic baked levels,
namespaced slot-1 saves/audio), playable-subclass rewind capture hooks, the host-adapted
S3K custom-zone/palette bridge, and exclusive game-start selection with destination-scoped
launch teams, deterministic input filters, and row-only HUD profiles. Code-bearing mods
stay namespaced, injected-service-only, rewind-recreatable, transactionally registered,
and owner-fault-bounded. Complete new zones preserve tagged identities, not runtime
indices. Maintained contracts live in [creator handbook](../modding/index.md) and
[compatibility contract](mod-api-compatibility.md);
dated design specs under `docs/architecture/designs/` are historical provenance only.

**Multiplayer time attack.** The direct-connect and master-server core lives under
`com.openggf.net.protocol`, `.hub`, `.host`, `.client`, and `.master`. These packages are
engine-free and may share only the canonical `GhostFrame` / `GhostFrameCodec`;
`TestNetIsolationRules` enforces the boundary. Each `RoomHost` and `GhostHub` is confined
to a single event-loop thread, and the master server reuses those room classes unchanged.
Engine and UI adapters belong in `com.openggf.game.timeattack.mp`. Production masters
require TLS (`plaintextForTest: true` is loopback-test only); the localhost admin HTTP
endpoint requires its bearer token and appends to `admin-audit.jsonl`. Identity age, clean
rounds, sanctions, and trust tiers persist in SQLite. Verified rooms are relay-only and
need a live replay-verifier worker matching the room's determinism fingerprint; ROM bytes
never cross the network and worker verdicts are Ed25519-signed. Operator commands:

```bash
java -cp target/OpenGGF-0.7.prerelease-jar-with-dependencies.jar com.openggf.tools.net.GhostLoadTestTool --n 256 --duration 30 --mix adversarial
java -cp target/OpenGGF-0.7.prerelease-jar-with-dependencies.jar com.openggf.tools.verifier.VerifierMain --master https://host:27900 --registration-token <token> --rom s3k.gen --data ./verifier-data
```

The CI scale gate runs 32 in-JVM bots through `TestGhostLoadTest`; the 128/256-player gate
above measures hub aggregation CPU, not deployed socket throughput.

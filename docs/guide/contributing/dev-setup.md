# Developer Setup

This page gets you from a fresh clone to running tests.

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java (JDK) | 21 or later | [Adoptium](https://adoptium.net/) recommended. Run `java -version` to check. |
| Maven | 3.8+ | Bundled with most IDEs. Run `mvn -version` to check. |
| GPU | OpenGL 4.1+ | Required for the rendering pipeline. Not needed for headless tests. |
| ROM files | See below | Required for ROM-dependent tests and running the engine. |

An IDE is recommended but not required. The project is developed in IntelliJ IDEA and
includes IntelliJ project files. Any IDE with Maven support will work.

## Clone and Build

```bash
git clone https://github.com/OpenGGF/OpenGGF.git
cd OpenGGF
tools/testing/install-hooks.sh
mvn package
```

The pinned Sonic Retro disassemblies are optional development references. The engine,
Maven build, and ordinary tests do not require them. Contributors doing disassembly-backed
parity research can initialize the exact reference revisions after cloning:

```bash
git submodule update --init docs/s1disasm docs/s2disasm docs/skdisasm
```

Trace recording and analysis are also optional. Those workflows use the exact
TraceChaser release pinned by OpenGGF and the verified official BizHawk 2.11
installation documented there:

```bash
git submodule update --init --recursive tools/tracechaser
tools/tracechaser-bootstrap.sh --check
```

The submodule never initializes itself and normal builds do not require it.
Compatibility launchers resolve each command through
`tools/tracechaser-bootstrap.sh --require <relative-path>` before execution.
That resolver derives the expected commit from the gitlink and refuses a dirty
checkout, symbolic-link or non-directory path component, or a target that
resolves outside the pinned checkout. Status 2 means uninitialized, status 3
means the wrong commit, and status 4 means an unsafe or missing path.

The executable OpenGGF JAR with all dependencies is written to the current
worktree's Maven output tree:
```
target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

Maven Silent Extension (MSE) is configured via `.mvn/extensions.xml`. By default, Maven
output is reduced. Use `-Dmse=off` when you need full Maven logs.

## ROM Files

Place ROM files in the project root directory (next to `pom.xml`):

| Game | Filename | Expected revision and hash |
|------|----------|----------------------------|
| Sonic 1 | `s1.gen` | World, REV01; CRC32 `AFE05EEE`; SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| Sonic 2 | `s2.gen` | World, REV01; CRC32 `7B905383`; SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| Sonic 3&K | `s3k.gen` | World, lock-on; CRC32 `63522553`; SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6` |

ROM files are gitignored. Test classes annotated `@RequiresRom` are disabled (reported as
skipped) when their ROM cannot be found, so you can build and run most tests without any
ROMs. Two classes are stricter: `TestS2CompleteRunStateDecoder` and
`TestS3kCompleteRunStateDecoder` throw unless the matching property is set explicitly.

ROM paths are passed per game as system properties -- note the S3K property is
`s3k.rom.path`, not `sonic3k.rom.path`:

```bash
mvn -Dmse=off test -Dsonic1.rom.path=/abs/path/s1.gen \
    -Dsonic2.rom.path=/abs/path/s2.gen -Ds3k.rom.path=/abs/path/s3k.gen
```

Use **absolute** paths. From a git worktree (or any directory that is not the repository
root) a relative path resolves against the Surefire fork's working directory, the ROM is not
found, and every ROM-gated class is silently skipped and reported green.

## Run the Engine

Build and run a distributable jar with the launcher for your platform:

```bash
# Linux
./run.sh

# Windows
run.cmd
```

The normal launchers build into the current worktree's `target/` directory and
launch the exact fat jar produced by that package, even when an older jar is
still present. They resolve their own script directory, so they can be invoked
from outside the worktree; the generated artifact manifest keeps the Maven
`project.build.finalName` setting authoritative. Run Maven from the worktree
root when invoking it directly so its repository-local JVM configuration also
keeps Maven-side temporary files inside `target/`.

For faster iteration, `dev.sh` (Linux) and `dev.cmd` (Windows) compile only
changed sources and run directly from `target/classes`. The first offline
development launch may require `run.sh` or `run.cmd` to populate Maven's local
dependency cache.

Linux developers can launch a RenderDoc capture with `scripts/run_renderdoc.sh` when
`renderdoccmd` is installed and available on `PATH`.

The engine will open a window showing the master title screen. Select a game with the
arrow keys and press Space. If a ROM file is missing, you will see an error message.

## Run Tests

```bash
# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=TestCollisionLogic

# Run a single test method
mvn test -Dtest=TestCollisionLogic#testSlopeAngle
```

Tests run across 4 JVM forks by default (`surefire.forkCount` in `pom.xml`; the CI profile
drops to 1). Override with `-Dsurefire.forkCount=N`. ROM-dependent tests are gated as
described under "ROM files" above.

## Project Structure

```
OpenGGF/
  pom.xml                    -- Maven build file
  config.yaml                -- Runtime configuration (gitignored if custom)
  src/
    main/java/com/openggf/   -- Engine source code
    main/resources/           -- Bundled default config, shaders
    test/java/com/openggf/   -- Test source code
  docs/
    s1disasm/                -- Sonic 1 disassembly (Sonic Retro submodule)
    s2disasm/                -- Sonic 2 disassembly (Sonic Retro submodule)
    skdisasm/                -- Sonic 3&K disassembly (Sonic Retro submodule)
    guide/                   -- This user guide
  tools/                     -- External reference tools
```

For a deeper look at the source layout, see [Architecture](architecture.md).

## GraalVM Native Image (Optional)

The engine supports ahead-of-time compilation via GraalVM native image. This produces
a standalone binary that starts faster and does not require a JVM installation.

To build a native image, you need GraalVM 21 with the `native-image` tool installed
and selected as Maven's JDK. The build is configured in `pom.xml` under the `native`
profile:

```bash
export JAVA_HOME=/path/to/graalvm-21
export PATH="$JAVA_HOME/bin:$PATH"
mvn -Dmse=off -Pnative -DskipTests package -B
```

On macOS this produces `target/OpenGGF.app` as well as the direct
`target/OpenGGF` executable. Put `config.yaml` and the user-supplied ROM files
beside the app before launching it. Native image metadata is maintained in
`src/main/resources/META-INF/native-image/`.

## Next Steps

- [Architecture](architecture.md) -- Understand the codebase design
- [Tutorial: Implement an Object](tutorial-implement-object.md) -- Learn by doing
- [Testing](testing.md) -- Writing and running tests

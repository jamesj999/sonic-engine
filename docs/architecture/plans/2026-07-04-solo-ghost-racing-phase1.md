# Solo Ghost Racing (Time Attack Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Race a translucent ghost of your best run through a signpost-terminated act with a live delta timer — plus the phase-1 security substrate (Ed25519 player identity, spawn-anchored input-only attempt recording).

**Architecture:** New `com.openggf.game.timeattack` (attempt state machine, runtime orchestration, store) and `com.openggf.game.ghost` (frame/file formats, render registry, playback cursor) packages; a hydration-free `GhostRenderer` in `com.openggf.sprites.ghost` reusing the existing isolated-DPLC-bank slot machinery from `GhostTraceRenderer`; wiring into `GameLoop` beside the existing user-recording hooks; a master-title sub-menu modeled on `UserRecordingMenu`. Specs: `docs/architecture/designs/2026-07-04-multiplayer-time-attack-design.md` (§3, §6.1, §7) and `docs/architecture/designs/2026-07-04-time-attack-security-design.md` (§3, §6.2, §11 phase 1).

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), JDK built-in Ed25519 (`KeyPairGenerator.getInstance("Ed25519")`), Maven.

## Global Constraints

- Branch: `feature/ai-time-attack-phase1` based on `develop` (never master). Execution should use an isolated worktree (superpowers:using-git-worktrees).
- JUnit 5 / Jupiter only — no `org.junit.*` (JUnit 4) imports.
- Every commit needs the trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`), each `updated` or `n/a`. A `feat:` commit touching `src/main/` must have `Changelog: updated` (staged CHANGELOG.md) or `Changelog: n/a: <reason>`. Task 1 adds the CHANGELOG entry; later commits use `Changelog: n/a: phase-1 entry added in c1 of this branch`.
- PowerShell: quote Maven props — `mvn "-Dtest=com.openggf.game.ghost.TestGhostFrameCodec" test`.
- Never `git add -A` (shared repo, concurrent sessions). Stage exact paths.
- Ghost/attempt files contain NO ROM asset/content bytes (positions, frames, masks, hashes only).
- New config keys need a `ConfigCatalog` entry (`TestConfigCatalog` fails otherwise) and a CONFIGURATION.md row.
- No `GameServices` from object constructors; managers may use `GameServices`. New registry follows the GameplayModeContext-hosted pattern.
- Frame layout (spec §7, fixed): 7 bytes = x u16 BE, y u16 BE, mappingFrame u8, flags u8 (bit0 hFlip, bit1 vFlip, bit2 finished), layer u8 (bits0-2 priorityBucket, bit3 highPriority, bits4-7 reserved zero).
- Time contract (spec §6.1): frame counting and input recording start at spawn; displayed timer starts at first input; authoritative time = `finishFrame − firstInputFrame`.

---

### Task 0: Branch setup

**Files:** none (git only)

- [ ] **Step 1: Create branch from develop**

```bash
git checkout develop && git pull --ff-only && git checkout -b feature/ai-time-attack-phase1
git config core.hooksPath .githooks
```

Expected: on new branch, clean tree (`git status --porcelain` empty).

---

### Task 1: GhostFrame + 7-byte codec

**Files:**
- Create: `src/main/java/com/openggf/game/ghost/GhostFrame.java`
- Create: `src/main/java/com/openggf/game/ghost/GhostFrameCodec.java`
- Test: `src/test/java/com/openggf/game/ghost/TestGhostFrameCodec.java`
- Modify: `CHANGELOG.md` (add Unreleased entry: "Solo ghost racing (time attack phase 1): ghost recording/rendering, best-run persistence, player identity, attempt input recording.")

**Interfaces:**
- Produces: `GhostFrame(int x, int y, int mappingFrame, boolean hFlip, boolean vFlip, boolean finished, int priorityBucket, boolean highPriority)` (record); `GhostFrameCodec.BYTES == 7`; `static void encode(GhostFrame f, byte[] out, int off)`; `static GhostFrame decode(byte[] in, int off)`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.ghost;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestGhostFrameCodec {
    @Test
    void roundTripsAllFields() {
        GhostFrame f = new GhostFrame(0x1234, 0xFEDC, 0xAB, true, false, true, 5, true);
        byte[] buf = new byte[GhostFrameCodec.BYTES];
        GhostFrameCodec.encode(f, buf, 0);
        assertEquals(f, GhostFrameCodec.decode(buf, 0));
    }

    @Test
    void packsBitsPerSpec() {
        GhostFrame f = new GhostFrame(0x0102, 0x0304, 7, true, true, false, 3, false);
        byte[] buf = new byte[7];
        GhostFrameCodec.encode(f, buf, 0);
        assertEquals(0x01, buf[0] & 0xFF); assertEquals(0x02, buf[1] & 0xFF); // x BE
        assertEquals(0x03, buf[2] & 0xFF); assertEquals(0x04, buf[3] & 0xFF); // y BE
        assertEquals(7, buf[4] & 0xFF);
        assertEquals(0b0000_0011, buf[5] & 0xFF); // hFlip|vFlip, no finished
        assertEquals(0b0000_0011, buf[6] & 0xFF); // bucket=3, high=false
    }

    @Test
    void treatsCoordinatesAsUnsigned16() {
        GhostFrame f = new GhostFrame(0xFFFF, 0x8000, 0, false, false, false, 0, false);
        byte[] buf = new byte[7];
        GhostFrameCodec.encode(f, buf, 0);
        GhostFrame back = GhostFrameCodec.decode(buf, 0);
        assertEquals(0xFFFF, back.x());
        assertEquals(0x8000, back.y());
    }

    @Test
    void reservedLayerBitsDecodeIgnoredAndEncodeZero() {
        byte[] buf = new byte[7];
        GhostFrameCodec.encode(new GhostFrame(1, 1, 1, false, false, false, 7, true), buf, 0);
        assertEquals(0, (buf[6] & 0xF0));
        buf[6] |= (byte) 0xF0; // future extension bits must not break decode
        assertEquals(7, GhostFrameCodec.decode(buf, 0).priorityBucket());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostFrameCodec" test`
Expected: COMPILATION ERROR (GhostFrame/GhostFrameCodec not found).

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.ghost;

/** One resolved render-state ghost frame (spec §7: final render state, never physics). */
public record GhostFrame(int x, int y, int mappingFrame, boolean hFlip, boolean vFlip,
                         boolean finished, int priorityBucket, boolean highPriority) {
}
```

```java
package com.openggf.game.ghost;

/** Fixed 7-byte wire/file layout for {@link GhostFrame} (main spec §7). */
public final class GhostFrameCodec {
    public static final int BYTES = 7;

    private GhostFrameCodec() {
    }

    public static void encode(GhostFrame f, byte[] out, int off) {
        out[off] = (byte) (f.x() >>> 8);
        out[off + 1] = (byte) f.x();
        out[off + 2] = (byte) (f.y() >>> 8);
        out[off + 3] = (byte) f.y();
        out[off + 4] = (byte) f.mappingFrame();
        out[off + 5] = (byte) ((f.hFlip() ? 0x01 : 0) | (f.vFlip() ? 0x02 : 0) | (f.finished() ? 0x04 : 0));
        out[off + 6] = (byte) ((f.priorityBucket() & 0x07) | (f.highPriority() ? 0x08 : 0));
    }

    public static GhostFrame decode(byte[] in, int off) {
        int x = ((in[off] & 0xFF) << 8) | (in[off + 1] & 0xFF);
        int y = ((in[off + 2] & 0xFF) << 8) | (in[off + 3] & 0xFF);
        int mapping = in[off + 4] & 0xFF;
        int flags = in[off + 5] & 0xFF;
        int layer = in[off + 6] & 0xFF;
        return new GhostFrame(x, y, mapping, (flags & 0x01) != 0, (flags & 0x02) != 0,
                (flags & 0x04) != 0, layer & 0x07, (layer & 0x08) != 0);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostFrameCodec" test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/ghost/GhostFrame.java src/main/java/com/openggf/game/ghost/GhostFrameCodec.java src/test/java/com/openggf/game/ghost/TestGhostFrameCodec.java CHANGELOG.md
git commit -m "feat(timeattack): ghost frame record and 7-byte codec

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: Ghost header + .ggfghost file codec

**Files:**
- Create: `src/main/java/com/openggf/game/ghost/GhostHeader.java`
- Create: `src/main/java/com/openggf/game/ghost/GhostRecording.java`
- Create: `src/main/java/com/openggf/game/ghost/GhostFileCodec.java`
- Test: `src/test/java/com/openggf/game/ghost/TestGhostFileCodec.java`

**Interfaces:**
- Consumes: `GhostFrame`, `GhostFrameCodec` (Task 1).
- Produces:
  - `GhostHeader(int formatVersion, String gameId, int zone, int act, String character, String displayName, int firstInputFrame, int finishFrame, int[] splitFrames, byte[] inputRecordingHash)` (record) with `int finalTimeFrames()` = `finishFrame - firstInputFrame`.
  - `GhostRecording` with `GhostHeader header()`, `int frameCount()`, `GhostFrame frameAt(int index)` (clamps: `index >= frameCount()` returns last frame — playback hold), `byte[] frameData()`, constructor `GhostRecording(GhostHeader header, byte[] frameData)`.
  - `GhostFileCodec.write(GhostRecording r, Path path)`, `GhostFileCodec.read(Path path)`; `GhostFileCodec.FORMAT_VERSION == 1`; `GhostFileCodec.MAX_FRAMES == 36_000` (ten minutes at 60fps — the classic Sonic act time-over cap).
  - **Untrusted-input hardening** (imported ghost files are arbitrary user files): `read` throws a friendly `IOException` on bad magic (message contains `"not a .ggfghost"`), on `version != FORMAT_VERSION`, on hash length != 32, and on `frameCount < 1 || frameCount > MAX_FRAMES` — it must never allocate from an unvalidated count (no OutOfMemory/NegativeArraySize from hostile files).
  - **Immutability:** `GhostHeader` clones its `int[]`/`byte[]` in the compact constructor and returns clones from `splitFrames()`/`inputRecordingHash()`; `GhostRecording` clones `frameData` on construction and from `frameData()`, and rejects empty or non-multiple-of-7 frame data (`IllegalArgumentException` — at least one frame required, so `frameAt` clamping can never index negatively).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.ghost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostFileCodec {
    private static GhostRecording sample() {
        byte[] hash = new byte[32];
        for (int i = 0; i < 32; i++) hash[i] = (byte) i;
        GhostHeader h = new GhostHeader(GhostFileCodec.FORMAT_VERSION, "s3k", 0, 0, "sonic",
                "Farrell", 12, 3612, new int[] {900, 2400}, hash);
        byte[] frames = new byte[3 * GhostFrameCodec.BYTES];
        GhostFrameCodec.encode(new GhostFrame(100, 200, 1, false, false, false, 2, false), frames, 0);
        GhostFrameCodec.encode(new GhostFrame(110, 200, 2, true, false, false, 2, false), frames, 7);
        GhostFrameCodec.encode(new GhostFrame(120, 200, 3, true, false, true, 2, true), frames, 14);
        return new GhostRecording(h, frames);
    }

    @Test
    void roundTripsHeaderAndFrames(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("best.ggfghost");
        GhostRecording original = sample();
        GhostFileCodec.write(original, p);
        GhostRecording back = GhostFileCodec.read(p);
        assertEquals(original.header(), back.header());
        assertEquals(3, back.frameCount());
        assertEquals(original.frameAt(2), back.frameAt(2));
        assertEquals(3600, back.header().finalTimeFrames());
    }

    @Test
    void frameAtClampsToLastFrame(@TempDir Path dir) {
        GhostRecording r = sample();
        assertEquals(r.frameAt(2), r.frameAt(99)); // playback holds final pose
    }

    @Test
    void rejectsBadMagic(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("junk.ggfghost");
        Files.write(p, "NOTAGHOSTFILE----".getBytes());
        IOException ex = assertThrows(IOException.class, () -> GhostFileCodec.read(p));
        assertTrue(ex.getMessage().contains("not a .ggfghost"));
    }

    @Test
    void rejectsUnsupportedVersion(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("v99.ggfghost");
        GhostFileCodec.write(sample(), p);
        byte[] bytes = Files.readAllBytes(p);
        bytes[9] = 99; // version u16 low byte sits right after the 8-byte magic
        Files.write(p, bytes);
        IOException ex = assertThrows(IOException.class, () -> GhostFileCodec.read(p));
        assertTrue(ex.getMessage().contains("format version"));
    }

    @Test
    void rejectsHostileFrameCountWithoutAllocating(@TempDir Path dir) throws IOException {
        // Hand-craft a header claiming Integer.MAX_VALUE frames with no frame bytes.
        Path p = dir.resolve("hostile.ggfghost");
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(p))) {
            out.write("GGFGHOST".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.writeShort(GhostFileCodec.FORMAT_VERSION);
            out.writeUTF("s3k"); out.writeByte(0); out.writeByte(0);
            out.writeUTF("sonic"); out.writeUTF("x");
            out.writeInt(0); out.writeInt(1);
            out.writeByte(0);                 // no splits
            out.writeByte(32); out.write(new byte[32]);
            out.writeInt(Integer.MAX_VALUE);  // hostile frame count
        }
        IOException ex = assertThrows(IOException.class, () -> GhostFileCodec.read(p));
        assertTrue(ex.getMessage().contains("frame count"));
    }

    @Test
    void headerAndRecordingAreDefensivelyCopied() {
        int[] splits = {900};
        byte[] hash = new byte[32];
        GhostHeader h = new GhostHeader(1, "s3k", 0, 0, "sonic", "x", 0, 1000, splits, hash);
        splits[0] = 7; hash[0] = 7;                       // mutate sources
        assertEquals(900, h.splitFrames()[0]);
        assertEquals(0, h.inputRecordingHash()[0]);
        h.splitFrames()[0] = 5;                           // mutate returned copy
        assertEquals(900, h.splitFrames()[0]);

        byte[] frames = new byte[GhostFrameCodec.BYTES];
        GhostRecording r = new GhostRecording(h, frames);
        frames[0] = 0x7F;
        assertEquals(0, r.frameAt(0).x());
        r.frameData()[0] = 0x7F;
        assertEquals(0, r.frameAt(0).x());
    }

    @Test
    void writeRejectsOverCapRecording(@TempDir Path dir) {
        byte[] frames = new byte[(GhostFileCodec.MAX_FRAMES + 1) * GhostFrameCodec.BYTES];
        GhostHeader h = new GhostHeader(1, "s3k", 0, 0, "sonic", "x", 0, 1, new int[0], new byte[32]);
        GhostRecording overCap = new GhostRecording(h, frames);
        IOException ex = assertThrows(IOException.class,
                () -> GhostFileCodec.write(overCap, dir.resolve("big.ggfghost")));
        assertTrue(ex.getMessage().contains("MAX_FRAMES"));
    }

    @Test
    void rejectsEmptyFrameData() {
        GhostHeader h = new GhostHeader(1, "s3k", 0, 0, "sonic", "x", 0, 1, new int[0], new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> new GhostRecording(h, new byte[0]));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostFileCodec" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.ghost;

/** .ggfghost header (main spec §3). No ROM asset/content bytes — metadata only. */
public record GhostHeader(int formatVersion, String gameId, int zone, int act, String character,
                          String displayName, int firstInputFrame, int finishFrame,
                          int[] splitFrames, byte[] inputRecordingHash) {
    public GhostHeader {
        splitFrames = splitFrames.clone();           // defensive: header is immutable
        inputRecordingHash = inputRecordingHash.clone();
    }

    @Override
    public int[] splitFrames() {
        return splitFrames.clone();
    }

    @Override
    public byte[] inputRecordingHash() {
        return inputRecordingHash.clone();
    }

    public int finalTimeFrames() {
        return finishFrame - firstInputFrame;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GhostHeader g && formatVersion == g.formatVersion
                && gameId.equals(g.gameId) && zone == g.zone && act == g.act
                && character.equals(g.character) && displayName.equals(g.displayName)
                && firstInputFrame == g.firstInputFrame && finishFrame == g.finishFrame
                && java.util.Arrays.equals(splitFrames, g.splitFrames)
                && java.util.Arrays.equals(inputRecordingHash, g.inputRecordingHash);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(gameId, zone, act, character, finishFrame);
    }
}
```

```java
package com.openggf.game.ghost;

public final class GhostRecording {
    private final GhostHeader header;
    private final byte[] frameData;

    public GhostRecording(GhostHeader header, byte[] frameData) {
        if (frameData.length < GhostFrameCodec.BYTES || frameData.length % GhostFrameCodec.BYTES != 0) {
            throw new IllegalArgumentException(
                    "frameData must be a non-empty multiple of " + GhostFrameCodec.BYTES + " bytes");
        }
        this.header = header;
        this.frameData = frameData.clone();   // defensive: recording is immutable
    }

    public GhostHeader header() { return header; }
    public byte[] frameData() { return frameData.clone(); }
    public int frameCount() { return frameData.length / GhostFrameCodec.BYTES; }

    /** Clamps past-the-end reads to the final frame so playback holds the finish pose. */
    public GhostFrame frameAt(int index) {
        int clamped = Math.min(Math.max(index, 0), frameCount() - 1);
        return GhostFrameCodec.decode(frameData, clamped * GhostFrameCodec.BYTES);
    }
}
```

```java
package com.openggf.game.ghost;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reader/writer for .ggfghost files: header + 7-byte frame stream (main spec §3/§7). */
public final class GhostFileCodec {
    public static final int FORMAT_VERSION = 1;
    /** Ten minutes at 60fps — the ROM act time-over cap; also bounds hostile-file allocations. */
    public static final int MAX_FRAMES = 36_000;
    private static final int HASH_LENGTH = 32;
    private static final byte[] MAGIC = "GGFGHOST".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private GhostFileCodec() {
    }

    public static void write(GhostRecording r, Path path) throws IOException {
        if (r.frameCount() > MAX_FRAMES) {
            throw new IOException("recording has " + r.frameCount()
                    + " frames, exceeding MAX_FRAMES " + MAX_FRAMES);
        }
        Files.createDirectories(path.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.write(MAGIC);
            GhostHeader h = r.header();
            out.writeShort(h.formatVersion());
            out.writeUTF(h.gameId());
            out.writeByte(h.zone());
            out.writeByte(h.act());
            out.writeUTF(h.character());
            out.writeUTF(h.displayName());
            out.writeInt(h.firstInputFrame());
            out.writeInt(h.finishFrame());
            out.writeByte(h.splitFrames().length);
            for (int split : h.splitFrames()) out.writeInt(split);
            out.writeByte(h.inputRecordingHash().length);
            out.write(h.inputRecordingHash());
            out.writeInt(r.frameCount());
            out.write(r.frameData());
        }
    }

    public static GhostRecording read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException(path + " is not a .ggfghost file");
            }
            int version = in.readUnsignedShort();
            if (version != FORMAT_VERSION) {
                throw new IOException(path + " has unsupported .ggfghost format version " + version);
            }
            String gameId = in.readUTF();
            int zone = in.readUnsignedByte();
            int act = in.readUnsignedByte();
            String character = in.readUTF();
            String displayName = in.readUTF();
            int firstInput = in.readInt();
            int finish = in.readInt();
            int[] splits = new int[in.readUnsignedByte()];
            for (int i = 0; i < splits.length; i++) splits[i] = in.readInt();
            int hashLength = in.readUnsignedByte();
            if (hashLength != HASH_LENGTH) {
                throw new IOException(path + " has invalid input-recording hash length " + hashLength);
            }
            byte[] hash = new byte[HASH_LENGTH];
            in.readFully(hash);
            int frameCount = in.readInt();
            if (frameCount < 1 || frameCount > MAX_FRAMES) {
                throw new IOException(path + " has invalid frame count " + frameCount);
            }
            byte[] frames = new byte[frameCount * GhostFrameCodec.BYTES];
            in.readFully(frames);
            return new GhostRecording(new GhostHeader(version, gameId, zone, act, character,
                    displayName, firstInput, finish, splits, hash), frames);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostFileCodec" test`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/ghost/GhostHeader.java src/main/java/com/openggf/game/ghost/GhostRecording.java src/main/java/com/openggf/game/ghost/GhostFileCodec.java src/test/java/com/openggf/game/ghost/TestGhostFileCodec.java
git commit -m "feat(timeattack): .ggfghost header and file codec

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: Player identity (Ed25519 keypair)

**Files:**
- Create: `src/main/java/com/openggf/net/identity/PlayerIdentity.java`
- Test: `src/test/java/com/openggf/net/identity/TestPlayerIdentity.java`

**Interfaces:**
- Produces: `PlayerIdentity.loadOrCreate(Path dir)` (creates/loads `player-identity.key` PKCS#8 + `player-identity.pub` X.509 in `dir`); `String fingerprint()` (lowercase hex SHA-256 of encoded public key); `byte[] sign(byte[] message)`; `static boolean verify(byte[] publicKeyEncoded, byte[] message, byte[] signature)`; `byte[] publicKeyEncoded()`.
- Security spec §3: identity exists from phase 1; nothing here talks to a network.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.net.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestPlayerIdentity {
    @Test
    void createsThenReloadsSameIdentity(@TempDir Path dir) throws Exception {
        PlayerIdentity first = PlayerIdentity.loadOrCreate(dir);
        PlayerIdentity second = PlayerIdentity.loadOrCreate(dir);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(64, first.fingerprint().length()); // sha-256 hex
    }

    @Test
    void signaturesVerifyAndTamperFails(@TempDir Path dir) throws Exception {
        PlayerIdentity id = PlayerIdentity.loadOrCreate(dir);
        byte[] msg = "nonce:serverfp".getBytes(StandardCharsets.UTF_8);
        byte[] sig = id.sign(msg);
        assertTrue(PlayerIdentity.verify(id.publicKeyEncoded(), msg, sig));
        msg[0] ^= 0x01;
        assertFalse(PlayerIdentity.verify(id.publicKeyEncoded(), msg, sig));
    }

    @Test
    void distinctDirsProduceDistinctIdentities(@TempDir Path a, @TempDir Path b) throws Exception {
        assertNotEquals(PlayerIdentity.loadOrCreate(a).fingerprint(),
                PlayerIdentity.loadOrCreate(b).fingerprint());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.net.identity.TestPlayerIdentity" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.net.identity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * Pseudonymous Ed25519 player identity (security spec §3). Phase 1 only
 * generates/persists the keypair and exposes sign/verify + fingerprint.
 */
public final class PlayerIdentity {
    private static final String ALGORITHM = "Ed25519";
    private static final String KEY_FILE = "player-identity.key";
    private static final String PUB_FILE = "player-identity.pub";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String fingerprint;

    private PlayerIdentity(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
    }

    public static PlayerIdentity loadOrCreate(Path dir) throws IOException, GeneralSecurityException {
        Files.createDirectories(dir);
        Path keyPath = dir.resolve(KEY_FILE);
        Path pubPath = dir.resolve(PUB_FILE);
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        if (Files.exists(keyPath) && Files.exists(pubPath)) {
            PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyPath)));
            PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubPath)));
            return new PlayerIdentity(priv, pub);
        }
        KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        Files.write(keyPath, pair.getPrivate().getEncoded());
        Files.write(pubPath, pair.getPublic().getEncoded());
        return new PlayerIdentity(pair.getPrivate(), pair.getPublic());
    }

    public String fingerprint() { return fingerprint; }
    public byte[] publicKeyEncoded() { return publicKey.getEncoded(); }

    public byte[] sign(byte[] message) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    public static boolean verify(byte[] publicKeyEncoded, byte[] message, byte[] sig) {
        try {
            PublicKey pub = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(publicKeyEncoded));
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(pub);
            signature.update(message);
            return signature.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.net.identity.TestPlayerIdentity" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/net/identity/PlayerIdentity.java src/test/java/com/openggf/net/identity/TestPlayerIdentity.java
git commit -m "feat(timeattack): Ed25519 player identity keypair (security phase 1)

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: Determinism fingerprint + spawn-anchored attempt input recording

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/DeterminismFingerprint.java`
- Create: `src/main/java/com/openggf/game/timeattack/AttemptStartDescriptor.java`
- Create: `src/main/java/com/openggf/game/timeattack/AttemptInputRecording.java`
- Test: `src/test/java/com/openggf/game/timeattack/TestAttemptInputRecording.java`

**Interfaces:**
- Produces:
  - `DeterminismFingerprint(String engineVersion, int romChecksum)` (record) with `String asString()` = `engineVersion + ":" + Integer.toHexString(romChecksum)`. (Runtime capture uses `com.openggf.version.AppVersion.get()` + `RomManager.getInstance().getRom().calculateChecksum()` — wired in Task 10, NOT here; this record stays IO-free.)
  - `AttemptStartDescriptor(String gameId, int zone, int act, String character, String fingerprint)` (record).
  - `AttemptInputRecording(AttemptStartDescriptor start)` with `void appendFrame(int heldMask, boolean startHeld)`, `int frameCount()`, `int heldMaskAt(int frame)` (bit 0x20 = startHeld folded in), `byte[] encode()`, `static AttemptInputRecording decode(byte[] data)`, `byte[] sha256()`.
  - Contract (security spec §6.2): frame 0 is the SPAWN frame; idle frames are recorded as zero masks; input-only — never desync-lite/sidecar data.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestAttemptInputRecording {
    private static AttemptStartDescriptor start() {
        return new AttemptStartDescriptor("s3k", 0, 0, "sonic", "0.6:cafe1234");
    }

    @Test
    void recordsIdleFramesFromSpawn() {
        AttemptInputRecording rec = new AttemptInputRecording(start());
        rec.appendFrame(0, false);            // spawn frame, idle
        rec.appendFrame(0, false);
        rec.appendFrame(0x08, false);         // first input (RIGHT) at frame 2
        assertEquals(3, rec.frameCount());
        assertEquals(0, rec.heldMaskAt(0));
        assertEquals(0x08, rec.heldMaskAt(2));
    }

    @Test
    void foldsStartHeldIntoBit5() {
        AttemptInputRecording rec = new AttemptInputRecording(start());
        rec.appendFrame(0x10, true);
        assertEquals(0x30, rec.heldMaskAt(0));
    }

    @Test
    void encodeDecodeRoundTripsAndHashIsStable() {
        AttemptInputRecording rec = new AttemptInputRecording(start());
        rec.appendFrame(0, false);
        rec.appendFrame(0x0C, false);
        byte[] encoded = rec.encode();
        AttemptInputRecording back = AttemptInputRecording.decode(encoded);
        assertEquals(rec.frameCount(), back.frameCount());
        assertEquals(start(), back.start());
        assertArrayEquals(rec.sha256(), back.sha256());
        assertEquals(32, rec.sha256().length);
    }

    @Test
    void hashChangesWhenAnyMaskChanges() {
        AttemptInputRecording a = new AttemptInputRecording(start());
        a.appendFrame(0x08, false);
        AttemptInputRecording b = new AttemptInputRecording(start());
        b.appendFrame(0x04, false);
        assertFalse(java.util.Arrays.equals(a.sha256(), b.sha256()));
    }

    @Test
    void decodeRejectsHostileFrameLength() {
        AttemptInputRecording rec = new AttemptInputRecording(start());
        rec.appendFrame(0x08, false);
        byte[] encoded = rec.encode();
        // The frame-count int is the 4 bytes immediately before the single mask byte.
        int lengthOffset = encoded.length - 1 - 4;
        encoded[lengthOffset] = (byte) 0x7F; // claim ~2 billion frames
        assertThrows(java.io.UncheckedIOException.class, () -> AttemptInputRecording.decode(encoded));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestAttemptInputRecording" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.timeattack;

/** Physics build identity for replay verification routing (security spec §6.2). IO-free. */
public record DeterminismFingerprint(String engineVersion, int romChecksum) {
    public String asString() {
        return engineVersion + ":" + Integer.toHexString(romChecksum);
    }
}
```

```java
package com.openggf.game.timeattack;

/** Canonical start-state descriptor embedded in every attempt recording (security spec §6.2). */
public record AttemptStartDescriptor(String gameId, int zone, int act, String character,
                                     String fingerprint) {
}
```

```java
package com.openggf.game.timeattack;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Input-only attempt recording (security spec §6.2): one byte per frame from the
 * SPAWN frame onward (idle = 0). Bits 0-4 = AbstractPlayableSprite INPUT_* held
 * mask, bit 5 = start held. Deliberately contains NO sidecar/physics data.
 */
public final class AttemptInputRecording {
    public static final int START_HELD_BIT = 0x20;
    /** Ten minutes at 60fps — matches GhostFileCodec.MAX_FRAMES and the ROM time-over cap. */
    public static final int MAX_FRAMES = 36_000;

    private final AttemptStartDescriptor start;
    private final ByteArrayOutputStream masks;

    public AttemptInputRecording(AttemptStartDescriptor start) {
        this(start, new ByteArrayOutputStream());
    }

    private AttemptInputRecording(AttemptStartDescriptor start, ByteArrayOutputStream masks) {
        this.start = start;
        this.masks = masks;
    }

    public void appendFrame(int heldMask, boolean startHeld) {
        masks.write((heldMask & 0x1F) | (startHeld ? START_HELD_BIT : 0));
    }

    public AttemptStartDescriptor start() { return start; }
    public int frameCount() { return masks.size(); }
    public int heldMaskAt(int frame) { return masks.toByteArray()[frame] & 0xFF; }

    public byte[] encode() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(start.gameId());
            out.writeByte(start.zone());
            out.writeByte(start.act());
            out.writeUTF(start.character());
            out.writeUTF(start.fingerprint());
            byte[] data = masks.toByteArray();
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static AttemptInputRecording decode(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            AttemptStartDescriptor start = new AttemptStartDescriptor(in.readUTF(),
                    in.readUnsignedByte(), in.readUnsignedByte(), in.readUTF(), in.readUTF());
            int length = in.readInt();
            if (length < 0 || length > MAX_FRAMES) {
                throw new IOException("invalid attempt recording frame count " + length);
            }
            byte[] data = new byte[length];
            in.readFully(data);
            ByteArrayOutputStream masks = new ByteArrayOutputStream();
            masks.write(data, 0, data.length);
            return new AttemptInputRecording(start, masks);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] sha256() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encode());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestAttemptInputRecording" test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/DeterminismFingerprint.java src/main/java/com/openggf/game/timeattack/AttemptStartDescriptor.java src/main/java/com/openggf/game/timeattack/AttemptInputRecording.java src/test/java/com/openggf/game/timeattack/TestAttemptInputRecording.java
git commit -m "feat(timeattack): determinism fingerprint and input-only attempt recording

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: Ghost capture buffer + playback cursor

**Files:**
- Create: `src/main/java/com/openggf/game/ghost/GhostCaptureBuffer.java`
- Create: `src/main/java/com/openggf/game/ghost/GhostPlaybackCursor.java`
- Test: `src/test/java/com/openggf/game/ghost/TestGhostCaptureAndPlayback.java`

**Interfaces:**
- Consumes: `GhostFrame`, `GhostFrameCodec`, `GhostRecording` (Tasks 1-2).
- Produces:
  - `GhostCaptureBuffer` with `void capture(int centreX, int centreY, int mappingFrame, boolean hFlip, boolean vFlip, int priorityBucket, boolean highPriority, boolean finished)`, `int frameCount()`, `byte[] toFrameData()`, `void reset()`.
  - `GhostPlaybackCursor(GhostRecording recording)` with `GhostFrame frameFor(int attemptFrame)` (spawn-anchored index; clamps via `GhostRecording.frameAt`), `boolean isFinishedAt(int attemptFrame)`.
- Runtime callers (Task 10) feed sprite accessors: `getCentreX()`, `getCentreY()` (AbstractSprite), `getMappingFrame()`, `getRenderHFlip()`, `getRenderVFlip()`, `getPriorityBucket()`, `isHighPriority()` (AbstractPlayableSprite).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostCaptureAndPlayback {
    @Test
    void captureProducesDecodableFrameStream() {
        GhostCaptureBuffer buf = new GhostCaptureBuffer();
        buf.capture(100, 200, 5, true, false, 2, false, false);
        buf.capture(104, 199, 6, true, false, 2, false, true);
        assertEquals(2, buf.frameCount());
        byte[] data = buf.toFrameData();
        assertEquals(2 * GhostFrameCodec.BYTES, data.length);
        GhostFrame second = GhostFrameCodec.decode(data, GhostFrameCodec.BYTES);
        assertEquals(new GhostFrame(104, 199, 6, true, false, true, 2, false), second);
    }

    @Test
    void resetClearsBuffer() {
        GhostCaptureBuffer buf = new GhostCaptureBuffer();
        buf.capture(1, 1, 1, false, false, 0, false, false);
        buf.reset();
        assertEquals(0, buf.frameCount());
    }

    @Test
    void cursorIsSpawnAnchoredAndHoldsFinalPose() {
        GhostCaptureBuffer buf = new GhostCaptureBuffer();
        buf.capture(10, 0, 0, false, false, 0, false, false);
        buf.capture(20, 0, 0, false, false, 0, false, false);
        buf.capture(30, 0, 0, false, false, 0, false, true);
        GhostRecording rec = new GhostRecording(
                new GhostHeader(1, "s3k", 0, 0, "sonic", "x", 0, 2, new int[0], new byte[32]),
                buf.toFrameData());
        GhostPlaybackCursor cursor = new GhostPlaybackCursor(rec);
        assertEquals(10, cursor.frameFor(0).x());
        assertEquals(30, cursor.frameFor(2).x());
        assertEquals(30, cursor.frameFor(500).x()); // hold last pose
        assertFalse(cursor.isFinishedAt(1));
        assertTrue(cursor.isFinishedAt(2));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostCaptureAndPlayback" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.ghost;

import java.io.ByteArrayOutputStream;

/** Per-frame render-state sampler for the local player's run (main spec §3/§7). */
public final class GhostCaptureBuffer {
    private final ByteArrayOutputStream frames = new ByteArrayOutputStream();
    private final byte[] scratch = new byte[GhostFrameCodec.BYTES];
    private int frameCount;

    public void capture(int centreX, int centreY, int mappingFrame, boolean hFlip, boolean vFlip,
                        int priorityBucket, boolean highPriority, boolean finished) {
        GhostFrameCodec.encode(new GhostFrame(centreX & 0xFFFF, centreY & 0xFFFF, mappingFrame,
                hFlip, vFlip, finished, priorityBucket, highPriority), scratch, 0);
        frames.write(scratch, 0, scratch.length);
        frameCount++;
    }

    public int frameCount() { return frameCount; }
    public byte[] toFrameData() { return frames.toByteArray(); }

    public void reset() {
        frames.reset();
        frameCount = 0;
    }
}
```

```java
package com.openggf.game.ghost;

/** Spawn-anchored playback: attempt frame N maps directly to recorded frame N. */
public final class GhostPlaybackCursor {
    private final GhostRecording recording;

    public GhostPlaybackCursor(GhostRecording recording) {
        this.recording = recording;
    }

    public GhostFrame frameFor(int attemptFrame) {
        return recording.frameAt(attemptFrame);
    }

    public boolean isFinishedAt(int attemptFrame) {
        return recording.frameAt(attemptFrame).finished();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostCaptureAndPlayback" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/ghost/GhostCaptureBuffer.java src/main/java/com/openggf/game/ghost/GhostPlaybackCursor.java src/test/java/com/openggf/game/ghost/TestGhostCaptureAndPlayback.java
git commit -m "feat(timeattack): ghost capture buffer and spawn-anchored playback cursor

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: Time attack attempt state machine + split deltas

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackAttempt.java`
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackDeltas.java`
- Test: `src/test/java/com/openggf/game/timeattack/TestTimeAttackAttempt.java`

**Interfaces:**
- Produces:
  - `TimeAttackAttempt` (pure, no engine imports): `enum Phase { ARMED, RUNNING, FINISHED, VOID }`; `void onFrame(int heldMask, boolean endOfLevelActive, int checkpointIndex)` called once per gameplay frame starting at spawn; `Phase phase()`; `int frameCount()` (frames elapsed since spawn); `int firstInputFrame()` (-1 until input); `int finishFrame()` (-1 until finished); `int finalTimeFrames()`; `int elapsedDisplayFrames()` (0 while ARMED, `frameCount - firstInputFrame` while RUNNING, final time when FINISHED); `int[] splitFrames()` (frame at each NEW checkpoint index, ascending); `void voidAttempt()`.
  - `TimeAttackDeltas.deltaAtSplit(int[] attemptSplits, int attemptFirstInputFrame, int[] ghostSplits, int ghostFirstInputFrame, int splitOrdinal)` → **timed** delta: `(attemptSplit − attemptFirstInput) − (ghostSplit − ghostFirstInput)` (positive = behind), returns `Integer.MIN_VALUE` when either side lacks that split. Splits are stored spawn-anchored, but the timer starts at first input — comparing raw split frames would be wrong by the idle-time difference.
- Semantics (spec §6.1): first `onFrame` call IS the spawn frame (frame 0). `endOfLevelActive == true` transitions RUNNING→FINISHED with `finishFrame = frameCount` of that call. Checkpoint index `-1` = none; a split records only when the index EXCEEDS the highest seen.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestTimeAttackAttempt {
    @Test
    void timesFromFirstInputNotSpawn() {
        TimeAttackAttempt a = new TimeAttackAttempt();
        a.onFrame(0, false, -1);          // spawn frame 0, idle
        a.onFrame(0, false, -1);          // frame 1, idle
        a.onFrame(0x08, false, -1);       // frame 2, first input
        a.onFrame(0x08, false, -1);       // frame 3
        assertEquals(TimeAttackAttempt.Phase.RUNNING, a.phase());
        assertEquals(2, a.firstInputFrame());
        assertEquals(1, a.elapsedDisplayFrames()); // frames 2..3 = 1 elapsed
        a.onFrame(0x08, true, -1);        // frame 4, signpost
        assertEquals(TimeAttackAttempt.Phase.FINISHED, a.phase());
        assertEquals(4, a.finishFrame());
        assertEquals(2, a.finalTimeFrames()); // 4 - 2
    }

    @Test
    void recordsSplitsOnNewCheckpointIndexOnly() {
        TimeAttackAttempt a = new TimeAttackAttempt();
        a.onFrame(0x08, false, -1);
        a.onFrame(0x08, false, 1);   // checkpoint 1 at frame 1
        a.onFrame(0x08, false, 1);   // same index — no new split
        a.onFrame(0x08, false, 2);   // checkpoint 2 at frame 3
        assertArrayEquals(new int[] {1, 3}, a.splitFrames());
    }

    @Test
    void staysArmedThroughIdleAndVoidIsTerminal() {
        TimeAttackAttempt a = new TimeAttackAttempt();
        a.onFrame(0, false, -1);
        assertEquals(TimeAttackAttempt.Phase.ARMED, a.phase());
        assertEquals(0, a.elapsedDisplayFrames());
        a.voidAttempt();
        a.onFrame(0x08, true, -1);
        assertEquals(TimeAttackAttempt.Phase.VOID, a.phase());
        assertEquals(-1, a.finishFrame());
    }

    @Test
    void deltasCompareTimedValuesNotSpawnFrames() {
        // Same timed pace, but the attempt idled 60 frames before first input: delta must be 0.
        assertEquals(0, TimeAttackDeltas.deltaAtSplit(new int[] {960}, 60, new int[] {900}, 0, 0));
        assertEquals(60, TimeAttackDeltas.deltaAtSplit(new int[] {900}, 0, new int[] {840}, 0, 0));
        assertEquals(-30, TimeAttackDeltas.deltaAtSplit(new int[] {800, 1700}, 0, new int[] {830, 1730}, 0, 1));
        assertEquals(Integer.MIN_VALUE, TimeAttackDeltas.deltaAtSplit(new int[] {800}, 0, new int[0], 0, 0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackAttempt" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.timeattack;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure attempt state machine (main spec §6.1): frame counting is spawn-anchored,
 * the displayed timer starts at first input, authoritative time =
 * finishFrame - firstInputFrame. One onFrame call per gameplay frame.
 */
public final class TimeAttackAttempt {
    public enum Phase { ARMED, RUNNING, FINISHED, VOID }

    private Phase phase = Phase.ARMED;
    private int frameCount = -1;      // becomes 0 on the spawn frame's onFrame call
    private int firstInputFrame = -1;
    private int finishFrame = -1;
    private int highestCheckpoint = -1;
    private final List<Integer> splits = new ArrayList<>();

    public void onFrame(int heldMask, boolean endOfLevelActive, int checkpointIndex) {
        if (phase == Phase.FINISHED || phase == Phase.VOID) {
            return;
        }
        frameCount++;
        if (phase == Phase.ARMED && heldMask != 0) {
            phase = Phase.RUNNING;
            firstInputFrame = frameCount;
        }
        if (phase == Phase.RUNNING && checkpointIndex > highestCheckpoint && checkpointIndex >= 0) {
            highestCheckpoint = checkpointIndex;
            splits.add(frameCount);
        }
        if (phase == Phase.RUNNING && endOfLevelActive) {
            phase = Phase.FINISHED;
            finishFrame = frameCount;
        }
    }

    public void voidAttempt() { phase = Phase.VOID; }

    public Phase phase() { return phase; }
    public int frameCount() { return Math.max(frameCount, 0); }
    public int firstInputFrame() { return firstInputFrame; }
    public int finishFrame() { return finishFrame; }
    public int finalTimeFrames() { return finishFrame - firstInputFrame; }

    public int elapsedDisplayFrames() {
        if (phase == Phase.RUNNING) return frameCount - firstInputFrame;
        if (phase == Phase.FINISHED) return finalTimeFrames();
        return 0;
    }

    public int[] splitFrames() {
        return splits.stream().mapToInt(Integer::intValue).toArray();
    }
}
```

```java
package com.openggf.game.timeattack;

/**
 * Split-ordinal delta between the live attempt and a ghost's recorded splits.
 * Splits are spawn-anchored frame numbers but the timer starts at first input
 * (spec §6.1), so deltas compare TIMED values — subtracting each side's own
 * firstInputFrame — never raw spawn-frame numbers.
 */
public final class TimeAttackDeltas {
    public static final int NO_DELTA = Integer.MIN_VALUE;

    private TimeAttackDeltas() {
    }

    /** Positive = attempt is behind the ghost at that split (in timed frames). */
    public static int deltaAtSplit(int[] attemptSplits, int attemptFirstInputFrame,
                                   int[] ghostSplits, int ghostFirstInputFrame, int splitOrdinal) {
        if (splitOrdinal < 0 || splitOrdinal >= attemptSplits.length || splitOrdinal >= ghostSplits.length) {
            return NO_DELTA;
        }
        return (attemptSplits[splitOrdinal] - attemptFirstInputFrame)
                - (ghostSplits[splitOrdinal] - ghostFirstInputFrame);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackAttempt" test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/TimeAttackAttempt.java src/main/java/com/openggf/game/timeattack/TimeAttackDeltas.java src/test/java/com/openggf/game/timeattack/TestTimeAttackAttempt.java
git commit -m "feat(timeattack): attempt state machine with spawn-anchored timing and split deltas

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 7: GhostStore — best-run persistence, rotation, imports

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/GhostStore.java`
- Test: `src/test/java/com/openggf/game/timeattack/TestGhostStore.java`

**Interfaces:**
- Consumes: `GhostRecording`, `GhostHeader`, `GhostFileCodec` (Task 2), `AttemptInputRecording` (Task 4).
- Produces: `GhostStore(Path root)` (production root = `Path.of(System.getProperty("user.dir"), "ghosts")` — wired in Task 10, injected for tests):
  - `Optional<GhostRecording> loadBest(String gameId, int zone, int act, String character)`
  - `boolean saveIfBest(GhostRecording candidate, AttemptInputRecording inputs)` — true when new best (no existing best, or `candidate.header().finalTimeFrames()` strictly lower). **Enforces the hash binding at the persistence boundary:** throws `IllegalArgumentException` unless `candidate.header().inputRecordingHash()` equals `inputs.sha256()` — the store is what creates the persisted ghost/inputs pair, so the binding is checked here, not just trusted from the caller. Rotates existing best → `-prev1`, prev1 → `-prev2` (keep last 3, spec §3). Writes inputs beside the ghost as `<stem>.ggfinputs` (raw `encode()` bytes).
  - `List<Path> listImports(String gameId)` — `.ggfghost` files in `<root>/<gameId>/import/`, sorted by filename; empty list when the folder is absent.
  - Layout: `<root>/<gameId>/<zone>-<act>-<character>.ggfghost` (+`-prev1`/`-prev2` stems, `.ggfinputs` sidecars).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack;

import com.openggf.game.ghost.GhostFileCodec;
import com.openggf.game.ghost.GhostHeader;
import com.openggf.game.ghost.GhostRecording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostStore {
    private static AttemptInputRecording inputs() {
        AttemptInputRecording rec = new AttemptInputRecording(
                new AttemptStartDescriptor("s3k", 0, 0, "sonic", "fp"));
        rec.appendFrame(0x08, false);
        return rec;
    }

    private static GhostRecording ghost(int firstInput, int finish) {
        byte[] frames = new byte[7];
        // Header hash must match the inputs the store persists alongside (binding enforced).
        return new GhostRecording(new GhostHeader(1, "s3k", 0, 0, "sonic", "p",
                firstInput, finish, new int[0], inputs().sha256()), frames);
    }

    @Test
    void savesFirstRunAsBestWithInputSidecar(@TempDir Path root) throws IOException {
        GhostStore store = new GhostStore(root);
        assertTrue(store.saveIfBest(ghost(0, 3600), inputs()));
        assertTrue(Files.exists(root.resolve("s3k").resolve("0-0-sonic.ggfghost")));
        assertTrue(Files.exists(root.resolve("s3k").resolve("0-0-sonic.ggfinputs")));
        assertEquals(3600, store.loadBest("s3k", 0, 0, "sonic").orElseThrow()
                .header().finalTimeFrames());
    }

    @Test
    void rejectsSlowerRunKeepsBest(@TempDir Path root) throws IOException {
        GhostStore store = new GhostStore(root);
        store.saveIfBest(ghost(0, 3600), inputs());
        assertFalse(store.saveIfBest(ghost(0, 4000), inputs()));
        assertEquals(3600, store.loadBest("s3k", 0, 0, "sonic").orElseThrow()
                .header().finalTimeFrames());
    }

    @Test
    void rotatesPreviousBestsKeepingThree(@TempDir Path root) throws IOException {
        GhostStore store = new GhostStore(root);
        store.saveIfBest(ghost(0, 4000), inputs());
        store.saveIfBest(ghost(0, 3800), inputs());
        store.saveIfBest(ghost(0, 3600), inputs());
        Path dir = root.resolve("s3k");
        assertEquals(3600, GhostFileCodec.read(dir.resolve("0-0-sonic.ggfghost")).header().finalTimeFrames());
        assertEquals(3800, GhostFileCodec.read(dir.resolve("0-0-sonic-prev1.ggfghost")).header().finalTimeFrames());
        assertEquals(4000, GhostFileCodec.read(dir.resolve("0-0-sonic-prev2.ggfghost")).header().finalTimeFrames());
    }

    @Test
    void rejectsGhostWhoseHashDoesNotMatchInputs(@TempDir Path root) {
        GhostStore store = new GhostStore(root);
        GhostRecording mismatched = new GhostRecording(new GhostHeader(1, "s3k", 0, 0, "sonic", "p",
                0, 3600, new int[0], new byte[32]), new byte[7]); // zero hash != inputs().sha256()
        assertThrows(IllegalArgumentException.class, () -> store.saveIfBest(mismatched, inputs()));
    }

    @Test
    void listsImportsSortedOrEmpty(@TempDir Path root) throws IOException {
        GhostStore store = new GhostStore(root);
        assertTrue(store.listImports("s3k").isEmpty());
        Path importDir = root.resolve("s3k").resolve("import");
        Files.createDirectories(importDir);
        GhostFileCodec.write(ghost(0, 100), importDir.resolve("b.ggfghost"));
        GhostFileCodec.write(ghost(0, 100), importDir.resolve("a.ggfghost"));
        Files.writeString(importDir.resolve("readme.txt"), "ignored");
        var imports = store.listImports("s3k");
        assertEquals(2, imports.size());
        assertTrue(imports.get(0).getFileName().toString().startsWith("a"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestGhostStore" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.game.timeattack;

import com.openggf.game.ghost.GhostFileCodec;
import com.openggf.game.ghost.GhostHeader;
import com.openggf.game.ghost.GhostRecording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Best-run persistence: ghosts/<gameId>/<zone>-<act>-<character>.ggfghost, keep last 3 (spec §3). */
public final class GhostStore {
    private final Path root;

    public GhostStore(Path root) {
        this.root = root;
    }

    public Optional<GhostRecording> loadBest(String gameId, int zone, int act, String character)
            throws IOException {
        Path best = bestPath(gameId, zone, act, character);
        if (!Files.exists(best)) {
            return Optional.empty();
        }
        return Optional.of(GhostFileCodec.read(best));
    }

    public boolean saveIfBest(GhostRecording candidate, AttemptInputRecording inputs) throws IOException {
        GhostHeader h = candidate.header();
        if (!java.util.Arrays.equals(h.inputRecordingHash(), inputs.sha256())) {
            throw new IllegalArgumentException(
                    "ghost header inputRecordingHash does not match the supplied input recording");
        }
        Path best = bestPath(h.gameId(), h.zone(), h.act(), h.character());
        if (Files.exists(best)
                && GhostFileCodec.read(best).header().finalTimeFrames() <= h.finalTimeFrames()) {
            return false;
        }
        rotate(best, "-prev1", "-prev2");
        GhostFileCodec.write(candidate, best);
        Files.write(sibling(best, ".ggfinputs"), inputs.encode());
        return true;
    }

    public List<Path> listImports(String gameId) throws IOException {
        Path dir = root.resolve(gameId).resolve("import");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".ggfghost"))
                    .sorted().toList();
        }
    }

    private Path bestPath(String gameId, int zone, int act, String character) {
        return root.resolve(gameId).resolve(zone + "-" + act + "-" + character + ".ggfghost");
    }

    private void rotate(Path best, String prev1Suffix, String prev2Suffix) throws IOException {
        Path prev1 = stemSuffix(best, prev1Suffix);
        Path prev2 = stemSuffix(best, prev2Suffix);
        if (Files.exists(prev1)) {
            Files.move(prev1, prev2, StandardCopyOption.REPLACE_EXISTING);
            moveIfExists(sibling(prev1, ".ggfinputs"), sibling(prev2, ".ggfinputs"));
        }
        if (Files.exists(best)) {
            Files.move(best, prev1, StandardCopyOption.REPLACE_EXISTING);
            moveIfExists(sibling(best, ".ggfinputs"), sibling(prev1, ".ggfinputs"));
        }
    }

    private static void moveIfExists(Path from, Path to) throws IOException {
        if (Files.exists(from)) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path stemSuffix(Path ghostFile, String suffix) {
        String name = ghostFile.getFileName().toString();
        String stem = name.substring(0, name.length() - ".ggfghost".length());
        return ghostFile.resolveSibling(stem + suffix + ".ggfghost");
    }

    private static Path sibling(Path ghostFile, String extension) {
        String name = ghostFile.getFileName().toString();
        String stem = name.substring(0, name.length() - ".ggfghost".length());
        return ghostFile.resolveSibling(stem + extension);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestGhostStore" test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/GhostStore.java src/test/java/com/openggf/game/timeattack/TestGhostStore.java
git commit -m "feat(timeattack): ghost store with best rotation and import listing

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 8: Gameplay-owned GhostRenderRegistry + LevelRenderer integration

**Files:**
- Create: `src/main/java/com/openggf/game/ghost/GhostRenderRegistry.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java` (host the registry; mirror how it hosts `SpecialRenderEffectRegistry` — field + getter, same lifecycle)
- Modify: `src/main/java/com/openggf/level/LevelRenderer.java` (~lines 968-1007: the sprite pass that currently calls `renderTraceGhostsForLayer`)
- Test: `src/test/java/com/openggf/game/ghost/TestGhostRenderRegistry.java`

**Interfaces:**
- Produces: `GhostRenderRegistry` with nested `@FunctionalInterface interface GhostLayerRenderer { void renderGhostsForLayer(int bucket, boolean highPriority); }` (same shape as `TraceGhostHook.GhostLayerRenderer`), `void register(GhostLayerRenderer r)`, `void unregister(GhostLayerRenderer r)`, `void renderForLayer(int bucket, boolean highPriority)`, `boolean isEmpty()`. Registry is gameplay-scoped state — a fresh instance per `GameplayModeContext` (spec §6.1: gameplay-owned; `TraceGhostHook` stays trace-only and untouched).
- Consumed by: Task 10 (`TimeAttackRuntime` registers its renderer), `LevelRenderer`.

- [ ] **Step 1: Write the failing registry unit test**

```java
package com.openggf.game.ghost;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostRenderRegistry {
    @Test
    void fansOutToAllRegisteredRenderersInOrder() {
        GhostRenderRegistry registry = new GhostRenderRegistry();
        List<String> calls = new ArrayList<>();
        registry.register((bucket, high) -> calls.add("a:" + bucket + ":" + high));
        registry.register((bucket, high) -> calls.add("b:" + bucket + ":" + high));
        registry.renderForLayer(3, true);
        assertEquals(List.of("a:3:true", "b:3:true"), calls);
    }

    @Test
    void unregisterStopsCallsAndEmptyIsCheap() {
        GhostRenderRegistry registry = new GhostRenderRegistry();
        assertTrue(registry.isEmpty());
        List<String> calls = new ArrayList<>();
        GhostRenderRegistry.GhostLayerRenderer r = (bucket, high) -> calls.add("x");
        registry.register(r);
        assertFalse(registry.isEmpty());
        registry.unregister(r);
        registry.renderForLayer(0, false);
        assertTrue(calls.isEmpty());
        assertTrue(registry.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostRenderRegistry" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement registry**

```java
package com.openggf.game.ghost;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gameplay-owned ghost render registry (main spec §6.1). LevelRenderer consults
 * this during the layered sprite pass; the trace-only TraceGhostHook global is
 * a separate, unchanged path. Renderers draw visuals only — never gameplay state.
 */
public final class GhostRenderRegistry {
    @FunctionalInterface
    public interface GhostLayerRenderer {
        void renderGhostsForLayer(int bucket, boolean highPriority);
    }

    private final List<GhostLayerRenderer> renderers = new CopyOnWriteArrayList<>();

    public void register(GhostLayerRenderer renderer) { renderers.add(renderer); }
    public void unregister(GhostLayerRenderer renderer) { renderers.remove(renderer); }
    public boolean isEmpty() { return renderers.isEmpty(); }

    public void renderForLayer(int bucket, boolean highPriority) {
        for (GhostLayerRenderer renderer : renderers) {
            renderer.renderGhostsForLayer(bucket, highPriority);
        }
    }
}
```

- [ ] **Step 4: Run registry test — PASS**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostRenderRegistry" test`
Expected: PASS (2 tests).

- [ ] **Step 5: Host on GameplayModeContext and consult from LevelRenderer**

In `GameplayModeContext.java`: add a `private final GhostRenderRegistry ghostRenderRegistry = new GhostRenderRegistry();` field and `public GhostRenderRegistry getGhostRenderRegistry()` getter, placed adjacent to the existing runtime-shared registry fields/getters (locate the `SpecialRenderEffectRegistry` field and copy its placement/pattern exactly — same construction style, no lazy init).

In `LevelRenderer.java`, extend the existing per-layer ghost callback (the method `renderTraceGhostsForLayer(int bucket, boolean highPriority)` at ~lines 999-1007) so gameplay ghosts render in the same interleave slot:

```java
private void renderTraceGhostsForLayer(int bucket, boolean highPriority) {
    GhostRenderRegistry gameplayGhosts = resolveGameplayGhostRegistry();
    if (gameplayGhosts != null && !gameplayGhosts.isEmpty()) {
        gameplayGhosts.renderForLayer(bucket, highPriority);
    }
    if (!currentTraceVisibility.showGhosts()) {
        return;
    }
    TraceGhostHook.GhostLayerRenderer ghosts = TraceGhostHook.active();
    if (ghosts != null) {
        ghosts.renderGhostsForLayer(bucket, highPriority);
    }
}
```

`resolveGameplayGhostRegistry()`: follow how `LevelRenderer` already reaches gameplay-scoped state — grep the file for its existing acquisition pattern (`GameServices.` calls or fields passed at construction). If `LevelRenderer` holds no gameplay context reference, add: `private GhostRenderRegistry resolveGameplayGhostRegistry() { var gameplay = com.openggf.game.GameServices.gameplayModeOrNull(); return gameplay != null ? gameplay.getGhostRenderRegistry() : null; }` — and if `GameServices` lacks a `gameplayModeOrNull()` accessor, mirror the existing `*OrNull()` accessor pattern in `GameServices.java` to add one (thin delegation, same style as its neighbors). Trace visibility flags must keep gating ONLY the trace hook — gameplay ghosts render regardless.

- [ ] **Step 6: Compile + run both suites**

Run: `mvn "-Dtest=com.openggf.game.ghost.TestGhostRenderRegistry" test`
Expected: PASS; full compile clean (no LevelRenderer errors).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/ghost/GhostRenderRegistry.java src/test/java/com/openggf/game/ghost/TestGhostRenderRegistry.java src/main/java/com/openggf/game/session/GameplayModeContext.java src/main/java/com/openggf/level/LevelRenderer.java
# plus src/main/java/com/openggf/game/GameServices.java if the OrNull accessor was added
git commit -m "feat(timeattack): gameplay-owned ghost render registry consulted by LevelRenderer

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 9: GhostRenderer — hydration-free ghost drawing

**Files:**
- Create: `src/main/java/com/openggf/sprites/ghost/GhostRenderer.java`
- Create: `src/main/java/com/openggf/sprites/ghost/ActiveGhost.java`
- Test: `src/test/java/com/openggf/sprites/ghost/TestGhostRendererLayerSelection.java`

**Interfaces:**
- Consumes: `GhostFrame` (Task 1); existing machinery — `PlayerSpriteRenderer(SpriteArtSet)`, `drawFrame(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip)`, `GhostArtBankAllocator.shiftToGhostBank(SpriteArtSet, int)`, `LevelManager.reserveSidekickPatternBank(int)`, `GraphicsManager.beginGhostRenderEffect(float)/endGhostRenderEffect()/beginPatternBatch()/flushPatternBatch()/setCurrentSpriteHighPriority(boolean)/getCurrentSpriteHighPriority()`, `GhostOpacityCalculator.alphaForDistance(int,int,int)`, `PlayerSpriteArtProvider.loadPlayerSpriteArt(String)`, `GameServices.graphics()/levelOrNull()`.
- Produces:
  - `ActiveGhost(String slotId, String characterCode, GhostFrame frame)` (record) — what to draw this frame; assembled by Task 10.
  - `GhostRenderer` with `void renderForLayer(java.util.List<ActiveGhost> ghosts, int bucket, boolean highPriority, int playerCentreX, int playerCentreY)` and static (package-visible for test) `static boolean layerMatches(GhostFrame frame, int bucket, boolean highPriority)`.
- Design (spec §6.1/§7): NO physics hydration and NO animation manager — layer selection uses the frame's OWN recorded `priorityBucket`/`highPriority` (not `GhostLayerFilter`, which compares against a live sprite). Draw envelope copied from `GhostTraceRenderer.renderCharacter` lines 101-118; art-slot creation copied from `GhostTraceRenderer.slotFor` (isolated bank via `reserveSidekickPatternBank` + `shiftToGhostBank`) but WITHOUT creating a visual sprite — only the `PlayerSpriteRenderer` is needed. DPLC random access: `drawFrame` uploads the requested frame's patterns on demand (verified: `PlayerSpriteRenderer.applyDplc`); empty-DPLC frames reuse the previously loaded tiles, matching ROM DPLC semantics — acceptable for phase 1 since ghosts play forward almost always; if visible corruption appears after retry snaps, call `invalidateDplcCache()` on the slot's renderer whenever the requested frame index moves backwards.

- [ ] **Step 1: Write the failing test (pure layer-selection logic only — GL paths are exercised manually in Task 14)**

```java
package com.openggf.sprites.ghost;

import com.openggf.game.ghost.GhostFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestGhostRendererLayerSelection {
    @Test
    void matchesOnlyOwnRecordedLayer() {
        GhostFrame frame = new GhostFrame(0, 0, 0, false, false, false, 2, true);
        assertTrue(GhostRenderer.layerMatches(frame, 2, true));
        assertFalse(GhostRenderer.layerMatches(frame, 2, false));
        assertFalse(GhostRenderer.layerMatches(frame, 3, true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.sprites.ghost.TestGhostRendererLayerSelection" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement ActiveGhost + GhostRenderer**

```java
package com.openggf.sprites.ghost;

import com.openggf.game.ghost.GhostFrame;

/** One ghost to draw this frame: stable slot id, character art code, resolved frame. */
public record ActiveGhost(String slotId, String characterCode, GhostFrame frame) {
}
```

```java
package com.openggf.sprites.ghost;

import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.game.GameServices;
import com.openggf.game.ghost.GhostFrame;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hydration-free gameplay ghost renderer (main spec §6.1): consumes resolved
 * render frames straight into PlayerSpriteRenderer.drawFrame — no physics
 * state, no animation manager. Art slots reuse the isolated-DPLC-bank pattern
 * from GhostTraceRenderer.slotFor.
 */
public final class GhostRenderer {
    private static final Logger LOGGER = Logger.getLogger(GhostRenderer.class.getName());
    private static final int FULL_OPACITY_DISTANCE = 32;

    private final Map<String, Slot> slots = new HashMap<>();

    static boolean layerMatches(GhostFrame frame, int bucket, boolean highPriority) {
        return frame.priorityBucket() == bucket && frame.highPriority() == highPriority;
    }

    public void renderForLayer(List<ActiveGhost> ghosts, int bucket, boolean highPriority,
                               int playerCentreX, int playerCentreY) {
        for (ActiveGhost ghost : ghosts) {
            GhostFrame frame = ghost.frame();
            if (!layerMatches(frame, bucket, highPriority)) {
                continue;
            }
            float alpha = GhostOpacityCalculator.alphaForDistance(
                    frame.x() - playerCentreX, frame.y() - playerCentreY, FULL_OPACITY_DISTANCE);
            if (alpha <= 0.0f) {
                continue;
            }
            Slot slot = slotFor(ghost.slotId(), ghost.characterCode());
            if (slot == null) {
                continue;
            }
            if (frame.mappingFrame() < slot.lastMappingFrame) {
                slot.renderer.invalidateDplcCache(); // backwards jump (retry snap): force fresh DPLC
            }
            slot.lastMappingFrame = frame.mappingFrame();
            GraphicsManager graphics = GameServices.graphics();
            graphics.flushPatternBatch();
            boolean previousHighPriority = graphics.getCurrentSpriteHighPriority();
            graphics.setCurrentSpriteHighPriority(frame.highPriority());
            graphics.beginGhostRenderEffect(alpha);
            graphics.beginPatternBatch();
            try {
                slot.renderer.drawFrame(frame.mappingFrame(), frame.x(), frame.y(),
                        frame.hFlip(), frame.vFlip());
            } finally {
                graphics.flushPatternBatch();
                graphics.endGhostRenderEffect();
                graphics.setCurrentSpriteHighPriority(previousHighPriority);
            }
        }
    }

    /** Drop cached art slots (call on level unload / time-attack teardown). */
    public void clearSlots() {
        slots.clear();
    }

    private Slot slotFor(String slotId, String characterCode) {
        String code = characterCode == null || characterCode.isBlank()
                ? "sonic" : characterCode.trim().toLowerCase(Locale.ROOT);
        String key = slotId + ":" + code;
        Slot existing = slots.get(key);
        if (existing != null) {
            return existing;
        }
        LevelManager level = GameServices.levelOrNull();
        if (level == null || !(level.getGame() instanceof PlayerSpriteArtProvider artProvider)) {
            return null;
        }
        try {
            SpriteArtSet sourceArt = artProvider.loadPlayerSpriteArt(code);
            if (sourceArt == null || sourceArt.isEmpty() || sourceArt.bankSize() <= 0) {
                return null;
            }
            int bankBase = level.reserveSidekickPatternBank(sourceArt.bankSize());
            SpriteArtSet ghostArt = GhostArtBankAllocator.shiftToGhostBank(sourceArt, bankBase);
            Slot slot = new Slot(new PlayerSpriteRenderer(ghostArt));
            slots.put(key, slot);
            return slot;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to create ghost slot for " + characterCode, e);
            return null;
        }
    }

    private static final class Slot {
        final PlayerSpriteRenderer renderer;
        int lastMappingFrame = -1;

        Slot(PlayerSpriteRenderer renderer) {
            this.renderer = renderer;
        }
    }
}
```

- [ ] **Step 4: Run test — PASS, full compile clean**

Run: `mvn "-Dtest=com.openggf.sprites.ghost.TestGhostRendererLayerSelection" test`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/sprites/ghost/GhostRenderer.java src/main/java/com/openggf/sprites/ghost/ActiveGhost.java src/test/java/com/openggf/sprites/ghost/TestGhostRendererLayerSelection.java
git commit -m "feat(timeattack): hydration-free GhostRenderer with isolated DPLC bank slots

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 10: TimeAttackRuntime + GameLoop wiring

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java`
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackLaunchRequest.java`
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackHudState.java`
- Modify: `src/main/java/com/openggf/GameLoop.java` (frame hooks in `updateLevelMode` beside `userRecordingControls.beforeLevelFrame/afterLevelFrame`; retry-key handling; live-rewind suppression; `onLevelReloaded()` calls after the time-attack retry's `loadZoneAndAct`)
- Test: `src/test/java/com/openggf/game/timeattack/TestTimeAttackRuntime.java`

**Interfaces:**
- Consumes: everything from Tasks 4-9; `PlayerInputState` (`com.openggf.control`, record with `heldMask()`/`startHeld()`); `InputHandler.logical().player1()`; `GameServices.gameState().isEndOfLevelActive()`; checkpoint index; `AbstractPlayableSprite`/`AbstractSprite` accessors (Task 5 list); `AppVersion.get()`; `RomManager.getInstance().getRom().calculateChecksum()`; `GhostRenderRegistry` (Task 8); `LevelManager.getTransitions().requestZoneAndAct(int, int)`.
- Produces (used by Tasks 11-13):
  - `TimeAttackLaunchRequest(String gameId, int zone, int act, String character, java.util.List<java.nio.file.Path> extraGhosts)` (record).
  - `TimeAttackRuntime(GhostStore store, java.nio.file.Path identityDir, java.util.function.BooleanSupplier launchBlocked)` — `launchBlocked` is the trace/test-mode guard, injected so it is unit-testable. Production: `new TimeAttackRuntime(new GhostStore(Path.of("ghosts")), Path.of("identity"), () -> TraceSessionLauncher.active() != null || GameServices.configuration().getBoolean(SonicConfiguration.TEST_MODE_ENABLED) || GameServices.playbackDebug().isDriving(GameMode.LEVEL))` (the `UserRecordingSessionLauncher.LiveTraceModeGuard` predicate), constructed once by `Engine`/`GameLoop`. `armForLaunch` REFUSES (logs a warning, stays inactive) when `launchBlocked` returns true — implemented, not prose. `armForLaunch` lazily calls `PlayerIdentity.loadOrCreate(identityDir)` on first use (log the fingerprint at INFO; on failure log WARNING and continue with `null` identity — solo play must not break); saved ghost headers use `displayName` = first 8 hex chars of the fingerprint (`""` when identity is unavailable), which is what makes phase-1 identity load-bearing rather than dead code. With: `void armForLaunch(TimeAttackLaunchRequest request)`; `boolean isActive()`; `boolean isAttemptRunning()`; `void onLevelReady()` (called when the level for an armed/retried run is loaded — begins the attempt at spawn, loads best + extra ghosts, registers its `GhostRenderRegistry.GhostLayerRenderer`; imported ghosts whose header gameId/zone/act doesn't match the launch are SKIPPED with a warning — wrong-track ghosts must never render — and each import loads in its own try/catch so one corrupt file can't block the rest); `void beforeLevelFrame(com.openggf.control.InputHandler input)`; `void afterLevelFrame()`; `boolean consumeRetryRequested()`; `void requestRetry()`; `void deactivate()` (unregister renderer, clear slots, drop state); `TimeAttackHudState hudState()`.
  - `TimeAttackHudState(boolean active, int elapsedDisplayFrames, int bestTimeFrames, int lastSplitDelta, boolean finished, boolean newBest)` (record; `bestTimeFrames == -1` when none, `lastSplitDelta == Integer.MIN_VALUE` when none).
- Core loop semantics (all pure logic delegated to `TimeAttackAttempt`):
  - `beforeLevelFrame`: snapshot `PlayerInputState p1 = input.logical().player1()`.
  - `afterLevelFrame` (the spawn-anchored per-frame tick): call `attempt.onFrame(p1.heldMask(), GameServices.gameState().isEndOfLevelActive(), currentCheckpointIndex())`; append to `AttemptInputRecording` (`p1.heldMask()`, `p1.startHeld()`); capture ghost frame from the main player sprite; advance ghost playback cursors; on FINISHED transition → build `GhostRecording` (header from attempt + `AttemptStartDescriptor`; `inputRecordingHash = inputRecording.sha256()`) and `store.saveIfBest(...)` unless debug tools were used.
  - `currentCheckpointIndex()`: read the same `CheckpointState` object services expose (`services().checkpointState().getLastCheckpointIndex()` — objects reach it via `DefaultObjectServices`; grep `DefaultObjectServices` for `checkpointState()` to find the owning source and consume it identically, adding a `GameplayModeContext` getter only if none exists).
  - Retry (`requestRetry()`): void attempt, discard capture, then GameLoop clears checkpoint state and calls `levelManager.getTransitions().requestZoneAndAct(zone, act)`; when that load completes GameLoop calls `onLevelReady()` again.
  - Length cap: when `attempt.frameCount()` reaches `GhostFileCodec.MAX_FRAMES` (36,000 = the ROM's 10:00 time-over), void the attempt and stop capture — the ROM's own time-over will end the run anyway; this guarantees no attempt can produce an unsaveable over-cap ghost.
  - Guards (security spec §11 phase 1 + spec §6.1): `armForLaunch` refuses via the injected `launchBlocked` supplier (see constructor above — covered by a unit test, not just this prose); while `isAttemptRunning()`, GameLoop skips `liveRewindManager.handleRealtimeRewindInput(...)` and blocks editor-mode entry; if the debug overlay was toggled on during an attempt, mark the attempt tainted → best not saved.
  - Cap enforcement is two-sided: the runtime voids any attempt whose capture reaches `GhostFileCodec.MAX_FRAMES` (implemented in the tick, tested), AND `GhostFileCodec.write` rejects over-cap recordings with an `IOException` — so an over-cap ghost can neither be produced nor persisted, and `read`'s cap can never reject a file `write` produced.
- Testability: keep ALL decision logic in `TimeAttackAttempt`/pure helpers; `TimeAttackRuntime` methods that touch `GameServices` must null-tolerate (use `*OrNull()` accessors) so the class constructs in plain unit tests. The unit test below covers arm→frame-tick→finish→save via a seam: extract the per-frame sampling into package-visible `void tickForTest(int heldMask, boolean startHeld, boolean endOfLevel, int checkpointIndex, GhostFrame sampledFrame)` that `afterLevelFrame` delegates to after doing the live sampling.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game.timeattack;

import com.openggf.game.ghost.GhostFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestTimeAttackRuntime {
    private static GhostFrame frame(int x) {
        return new GhostFrame(x, 100, 1, false, false, false, 2, false);
    }

    @Test
    void armTickFinishSavesBestGhostAndInputs(@TempDir Path root) throws Exception {
        GhostStore store = new GhostStore(root);
        Path identityDir = root.resolve("identity");
        TimeAttackRuntime runtime = new TimeAttackRuntime(store, identityDir, () -> false);
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic", java.util.List.of()));
        assertTrue(java.nio.file.Files.exists(identityDir.resolve("player-identity.key"))); // identity wired at arm
        runtime.beginAttemptForTest("0.6:cafe");   // package-visible spawn hook used by onLevelReady
        runtime.tickForTest(0, false, false, -1, frame(10));      // spawn idle
        runtime.tickForTest(0x08, false, false, -1, frame(11));   // first input
        runtime.tickForTest(0x08, false, false, 1, frame(12));    // checkpoint 1
        runtime.tickForTest(0x08, false, true, 1, frame(13));     // signpost
        assertTrue(runtime.hudState().finished());
        assertTrue(runtime.hudState().newBest());
        var best = store.loadBest("s3k", 0, 0, "sonic").orElseThrow();
        assertEquals(4, best.frameCount());
        assertEquals(1, best.header().firstInputFrame());
        assertEquals(3, best.header().finishFrame());
        assertEquals(2, best.header().finalTimeFrames());
        assertArrayEquals(new int[] {2}, best.header().splitFrames());
        assertEquals(32, best.header().inputRecordingHash().length);
        assertEquals(8, best.header().displayName().length()); // fingerprint prefix from PlayerIdentity
        assertEquals(com.openggf.net.identity.PlayerIdentity.loadOrCreate(identityDir)
                .fingerprint().substring(0, 8), best.header().displayName());
    }

    @Test
    void taintedAttemptIsNeverSaved(@TempDir Path root) throws Exception {
        GhostStore store = new GhostStore(root);
        TimeAttackRuntime runtime = new TimeAttackRuntime(store, root.resolve("identity"), () -> false);
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic", java.util.List.of()));
        runtime.beginAttemptForTest("0.6:cafe");
        runtime.markTainted();
        runtime.tickForTest(0x08, false, true, -1, frame(10));
        assertTrue(store.loadBest("s3k", 0, 0, "sonic").isEmpty());
        assertFalse(runtime.hudState().newBest());
    }

    @Test
    void incompatibleImportsAreSkipped(@TempDir Path root, @TempDir Path importDir) throws Exception {
        // A ghost recorded for a DIFFERENT track must never race in this one.
        byte[] frames = new byte[com.openggf.game.ghost.GhostFrameCodec.BYTES];
        var wrongTrack = new com.openggf.game.ghost.GhostRecording(
                new com.openggf.game.ghost.GhostHeader(1, "s3k", 1, 0, "sonic", "x", 0, 100,
                        new int[0], new byte[32]), frames);
        var rightTrack = new com.openggf.game.ghost.GhostRecording(
                new com.openggf.game.ghost.GhostHeader(1, "s3k", 0, 0, "tails", "y", 0, 100,
                        new int[0], new byte[32]), frames);
        Path wrong = importDir.resolve("wrong.ggfghost");
        Path right = importDir.resolve("right.ggfghost");
        com.openggf.game.ghost.GhostFileCodec.write(wrongTrack, wrong);
        com.openggf.game.ghost.GhostFileCodec.write(rightTrack, right);

        TimeAttackRuntime runtime = new TimeAttackRuntime(new GhostStore(root), root.resolve("identity"), () -> false);
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic",
                java.util.List.of(wrong, right)));
        runtime.beginAttemptForTest("0.6:cafe");
        assertEquals(1, runtime.opponents().size()); // only the matching-track import raced
    }

    @Test
    void refusesToArmWhenTraceOrTestModeActive(@TempDir Path root) {
        TimeAttackRuntime runtime = new TimeAttackRuntime(new GhostStore(root),
                root.resolve("identity"), () -> true); // guard says blocked
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic", java.util.List.of()));
        assertFalse(runtime.isActive());
    }

    @Test
    void attemptVoidsAtMaxFramesAndNeverSaves(@TempDir Path root) throws Exception {
        GhostStore store = new GhostStore(root);
        TimeAttackRuntime runtime = new TimeAttackRuntime(store, root.resolve("identity"), () -> false);
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic", java.util.List.of()));
        runtime.beginAttemptForTest("0.6:cafe");
        for (int i = 0; i < com.openggf.game.ghost.GhostFileCodec.MAX_FRAMES + 10; i++) {
            runtime.tickForTest(0x08, false, false, -1, frame(10));
        }
        runtime.tickForTest(0x08, false, true, -1, frame(10)); // signpost after cap — ignored
        assertTrue(store.loadBest("s3k", 0, 0, "sonic").isEmpty());
        assertFalse(runtime.hudState().finished());
    }

    @Test
    void retryVoidsAttemptWithoutSaving(@TempDir Path root) throws Exception {
        GhostStore store = new GhostStore(root);
        TimeAttackRuntime runtime = new TimeAttackRuntime(store, root.resolve("identity"), () -> false);
        runtime.armForLaunch(new TimeAttackLaunchRequest("s3k", 0, 0, "sonic", java.util.List.of()));
        runtime.beginAttemptForTest("0.6:cafe");
        runtime.tickForTest(0x08, false, false, -1, frame(10));
        runtime.requestRetry();
        assertTrue(runtime.consumeRetryRequested());
        assertFalse(runtime.consumeRetryRequested());
        runtime.tickForTest(0x08, false, true, -1, frame(11)); // finish after void — ignored
        assertTrue(store.loadBest("s3k", 0, 0, "sonic").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackRuntime" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement TimeAttackRuntime (core shown; live-sampling wrappers thin)**

```java
package com.openggf.game.timeattack;

/** What the menu launches: track + character + optional imported ghosts to race. */
public record TimeAttackLaunchRequest(String gameId, int zone, int act, String character,
                                      java.util.List<java.nio.file.Path> extraGhosts) {
}
```

```java
package com.openggf.game.timeattack;

/** Immutable HUD snapshot consumed by the overlay each frame. */
public record TimeAttackHudState(boolean active, int elapsedDisplayFrames, int bestTimeFrames,
                                 int lastSplitDelta, boolean finished, boolean newBest) {
    public static final TimeAttackHudState INACTIVE =
            new TimeAttackHudState(false, 0, -1, Integer.MIN_VALUE, false, false);
}
```

```java
package com.openggf.game.timeattack;

import com.openggf.game.ghost.GhostCaptureBuffer;
import com.openggf.game.ghost.GhostFrame;
import com.openggf.game.ghost.GhostHeader;
import com.openggf.game.ghost.GhostFileCodec;
import com.openggf.game.ghost.GhostPlaybackCursor;
import com.openggf.game.ghost.GhostRecording;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Solo time-attack orchestrator (main spec §3/§6.1). All timing decisions live
 * in TimeAttackAttempt; this class samples live state, feeds the attempt,
 * captures the ghost, plays back opponents, and persists new bests.
 */
public final class TimeAttackRuntime {
    private static final Logger LOGGER = Logger.getLogger(TimeAttackRuntime.class.getName());

    private final GhostStore store;
    private final java.nio.file.Path identityDir;
    private final java.util.function.BooleanSupplier launchBlocked;
    private com.openggf.net.identity.PlayerIdentity identity;
    private TimeAttackLaunchRequest launch;
    private TimeAttackAttempt attempt;
    private AttemptInputRecording inputRecording;
    private final GhostCaptureBuffer capture = new GhostCaptureBuffer();
    private final List<GhostPlaybackCursor> opponents = new ArrayList<>();
    private GhostRecording bestGhost;
    private boolean tainted;
    private boolean newBest;
    private boolean retryRequested;
    private int pendingHeldMask;
    private boolean pendingStartHeld;

    public TimeAttackRuntime(GhostStore store, java.nio.file.Path identityDir,
                             java.util.function.BooleanSupplier launchBlocked) {
        this.store = store;
        this.identityDir = identityDir;
        this.launchBlocked = launchBlocked;
    }

    public void armForLaunch(TimeAttackLaunchRequest request) {
        if (launchBlocked.getAsBoolean()) {
            LOGGER.warning("Time attack refused: trace/test/playback-debug mode is active");
            return;
        }
        this.launch = request;
        if (identity == null) {
            try {
                identity = com.openggf.net.identity.PlayerIdentity.loadOrCreate(identityDir);
                LOGGER.info("Time attack identity " + identity.fingerprint());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Player identity unavailable; continuing without", e);
            }
        }
    }

    public boolean isActive() { return launch != null; }
    public boolean isAttemptRunning() {
        return attempt != null && attempt.phase() == TimeAttackAttempt.Phase.RUNNING;
    }

    /** Spawn hook: level for an armed run is loaded. Fingerprint captured by caller. */
    void beginAttemptForTest(String fingerprint) {
        attempt = new TimeAttackAttempt();
        inputRecording = new AttemptInputRecording(new AttemptStartDescriptor(
                launch.gameId(), launch.zone(), launch.act(), launch.character(), fingerprint));
        capture.reset();
        tainted = false;
        newBest = false;
        opponents.clear();
        try {
            bestGhost = store.loadBest(launch.gameId(), launch.zone(), launch.act(),
                    launch.character()).orElse(null);
            if (bestGhost != null) {
                opponents.add(new GhostPlaybackCursor(bestGhost));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed loading best ghost", e);
        }
        for (Path extra : launch.extraGhosts()) {
            try {
                GhostRecording imported = GhostFileCodec.read(extra);
                GhostHeader h = imported.header();
                if (!h.gameId().equals(launch.gameId())
                        || h.zone() != launch.zone() || h.act() != launch.act()) {
                    LOGGER.warning("Skipping import " + extra + ": recorded for "
                            + h.gameId() + " " + h.zone() + "-" + h.act()
                            + ", room is " + launch.gameId() + " " + launch.zone() + "-" + launch.act());
                    continue;
                }
                opponents.add(new GhostPlaybackCursor(imported));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Skipping unreadable import " + extra, e);
            }
        }
    }

    public void markTainted() { tainted = true; }
    public void requestRetry() {
        if (attempt != null) attempt.voidAttempt();
        retryRequested = true;
    }
    public boolean consumeRetryRequested() {
        boolean r = retryRequested;
        retryRequested = false;
        return r;
    }

    void tickForTest(int heldMask, boolean startHeld, boolean endOfLevel, int checkpointIndex,
                     GhostFrame sampledFrame) {
        if (attempt == null) return;
        if (capture.frameCount() >= GhostFileCodec.MAX_FRAMES) {
            attempt.voidAttempt(); // ROM 10:00 time-over cap — over-cap ghosts can never exist
            return;
        }
        TimeAttackAttempt.Phase before = attempt.phase();
        attempt.onFrame(heldMask, endOfLevel, checkpointIndex);
        if (attempt.phase() == TimeAttackAttempt.Phase.VOID) return;
        inputRecording.appendFrame(heldMask, startHeld);
        capture.capture(sampledFrame.x(), sampledFrame.y(), sampledFrame.mappingFrame(),
                sampledFrame.hFlip(), sampledFrame.vFlip(), sampledFrame.priorityBucket(),
                sampledFrame.highPriority(), attempt.phase() == TimeAttackAttempt.Phase.FINISHED);
        if (before != TimeAttackAttempt.Phase.FINISHED
                && attempt.phase() == TimeAttackAttempt.Phase.FINISHED && !tainted) {
            persistIfBest();
        }
    }

    private void persistIfBest() {
        String displayName = identity != null ? identity.fingerprint().substring(0, 8) : "";
        GhostHeader header = new GhostHeader(GhostFileCodec.FORMAT_VERSION, launch.gameId(),
                launch.zone(), launch.act(), launch.character(), displayName,
                attempt.firstInputFrame(), attempt.finishFrame(), attempt.splitFrames(),
                inputRecording.sha256());
        try {
            newBest = store.saveIfBest(new GhostRecording(header, capture.toFrameData()),
                    inputRecording);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed saving best ghost", e);
        }
    }

    public TimeAttackHudState hudState() {
        if (attempt == null) return TimeAttackHudState.INACTIVE;
        int best = bestGhost != null ? bestGhost.header().finalTimeFrames() : -1;
        int[] ghostSplits = bestGhost != null ? bestGhost.header().splitFrames() : new int[0];
        int ghostFirstInput = bestGhost != null ? bestGhost.header().firstInputFrame() : 0;
        int[] attemptSplits = attempt.splitFrames();
        int lastDelta = attemptSplits.length == 0 ? Integer.MIN_VALUE
                : TimeAttackDeltas.deltaAtSplit(attemptSplits, attempt.firstInputFrame(),
                        ghostSplits, ghostFirstInput, attemptSplits.length - 1);
        return new TimeAttackHudState(true, attempt.elapsedDisplayFrames(), best, lastDelta,
                attempt.phase() == TimeAttackAttempt.Phase.FINISHED, newBest);
    }

    int attemptFrameCountForPlayback() { return attempt == null ? 0 : attempt.frameCount(); }
    List<GhostPlaybackCursor> opponents() { return opponents; }
    GhostRecording bestGhost() { return bestGhost; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackRuntime" test`
Expected: PASS (6 tests).

- [ ] **Step 5: Wire live sampling + GameLoop hooks**

Add to `TimeAttackRuntime` the live wrappers (thin, no logic):
- `public void onLevelReady()`: capture fingerprint via `new DeterminismFingerprint(com.openggf.version.AppVersion.get(), romChecksumOrZero()).asString()` (private `romChecksumOrZero()` wraps `RomManager.getInstance().getRom().calculateChecksum()` in try/catch → 0), then `beginAttemptForTest(fingerprint)`; register a `GhostRenderRegistry.GhostLayerRenderer` that builds `List<ActiveGhost>` from `opponents()` (slot ids `"ghost0"`, `"ghost1"`, ... character from each ghost's own header) at cursor position `attemptFrameCountForPlayback()` and calls `ghostRenderer.renderForLayer(list, bucket, highPriority, playerCentreX, playerCentreY)` using the main player from `GameServices.sprites()` (same resolution as `RecordingMainPlayerResolver.resolve(GameServices.configuration(), GameServices.sprites())`).
- `public void beforeLevelFrame(InputHandler input)`: store `input.logical().player1()` held/start into `pendingHeldMask`/`pendingStartHeld`.
- `public void afterLevelFrame()`: sample the main player sprite (`getCentreX()`, `getCentreY()`, `getMappingFrame()`, `getRenderHFlip()`, `getRenderVFlip()`, `getPriorityBucket()`, `isHighPriority()`), read `GameServices.gameState().isEndOfLevelActive()` and the checkpoint index (see Interfaces note), then delegate to `tickForTest(pendingHeldMask, pendingStartHeld, endOfLevel, checkpointIndex, sampledFrame)`.
- `public void deactivate()`: unregister renderer, `ghostRenderer.clearSlots()`, `launch = null; attempt = null;`.

In `GameLoop.java` `updateLevelMode(...)`, directly beside the existing `userRecordingControls.beforeLevelFrame(inputHandler)` / `.afterLevelFrame()` pair, add `timeAttackRuntime.beforeLevelFrame(inputHandler)` / `timeAttackRuntime.afterLevelFrame()` guarded by `timeAttackRuntime.isActive()`. Add retry-key handling next to the recording-key check (`SonicConfiguration.RECORDING_RECORD_KEY` precedent; key constant arrives in Task 12): on press when active → `timeAttackRuntime.requestRetry()`. In the per-frame transition dispatch, when `timeAttackRuntime.consumeRetryRequested()` → clear checkpoint state and `levelManager.getTransitions().requestZoneAndAct(launch zone/act)`; at each fade-completion site where that request's `loadZoneAndAct(zone, act)` returns (the `consumeZoneActRequest()` handling path), call `timeAttackRuntime.onLevelReady()` when `timeAttackRuntime.isActive()`. While `timeAttackRuntime.isAttemptRunning()`: skip the `liveRewindManager.handleRealtimeRewindInput(...)` branch and the editor-mode entry toggle; if the debug overlay toggle fires, call `timeAttackRuntime.markTainted()` instead of blocking it.

- [ ] **Step 6: Full compile + targeted suites green**

Run: `mvn "-Dtest=com.openggf.game.timeattack.*" test`
Expected: PASS; project compiles.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/TimeAttackRuntime.java src/main/java/com/openggf/game/timeattack/TimeAttackLaunchRequest.java src/main/java/com/openggf/game/timeattack/TimeAttackHudState.java src/test/java/com/openggf/game/timeattack/TestTimeAttackRuntime.java src/main/java/com/openggf/GameLoop.java
git commit -m "feat(timeattack): runtime orchestrator wired into GameLoop frame hooks

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 11: Delta timer HUD overlay

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackHudOverlay.java`
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackTimeFormat.java`
- Modify: `src/main/java/com/openggf/GameLoop.java` (draw call where the user-recording HUD / `LiveRewindHudOverlay` draws)
- Test: `src/test/java/com/openggf/game/timeattack/TestTimeAttackTimeFormat.java`

**Interfaces:**
- Consumes: `TimeAttackHudState` (Task 10).
- Produces: `TimeAttackTimeFormat.frames(int frames)` → `"m:ss.cc"` (centiseconds = frames%60*100/60, 60fps); `TimeAttackTimeFormat.delta(int deltaFrames)` → `"+s.cc"`/`"-s.cc"`, empty string for `Integer.MIN_VALUE`. `TimeAttackHudOverlay.render(TimeAttackHudState state)` — no-op when `!state.active()`; renders current time, best time, last split delta (green when negative/ahead, red when positive/behind), "NEW RECORD" flash when `finished && newBest`. Model the pixel-font drawing on `com.openggf.game.rewind.LiveRewindHudOverlay` (same draw primitives, same overlay slot).

- [ ] **Step 1: Write the failing format test**

```java
package com.openggf.game.timeattack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestTimeAttackTimeFormat {
    @Test
    void formatsFramesAsMinutesSecondsCentis() {
        assertEquals("0:00.00", TimeAttackTimeFormat.frames(0));
        assertEquals("0:01.00", TimeAttackTimeFormat.frames(60));
        assertEquals("1:00.50", TimeAttackTimeFormat.frames(3630)); // 60s + 30f = 1:00.50
        assertEquals("0:59.98", TimeAttackTimeFormat.frames(3599));
    }

    @Test
    void formatsDeltasSignedInSeconds() {
        assertEquals("+1.00", TimeAttackTimeFormat.delta(60));
        assertEquals("-0.50", TimeAttackTimeFormat.delta(-30));
        assertEquals("", TimeAttackTimeFormat.delta(Integer.MIN_VALUE));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackTimeFormat" test`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement formatter + overlay**

```java
package com.openggf.game.timeattack;

/** 60fps frame counts to display strings. */
public final class TimeAttackTimeFormat {
    private TimeAttackTimeFormat() {
    }

    public static String frames(int frames) {
        int totalSeconds = frames / 60;
        int centis = (frames % 60) * 100 / 60;
        return "%d:%02d.%02d".formatted(totalSeconds / 60, totalSeconds % 60, centis);
    }

    public static String delta(int deltaFrames) {
        if (deltaFrames == Integer.MIN_VALUE) {
            return "";
        }
        int abs = Math.abs(deltaFrames);
        int centis = (abs % 60) * 100 / 60;
        return "%s%d.%02d".formatted(deltaFrames < 0 ? "-" : "+", abs / 60, centis);
    }
}
```

`TimeAttackHudOverlay`: copy the drawing approach of `com.openggf.game.rewind.LiveRewindHudOverlay` (its text/pixel-font primitives and screen anchoring), rendering top-right: line 1 `TimeAttackTimeFormat.frames(state.elapsedDisplayFrames())`, line 2 `BEST <frames(bestTimeFrames)>` when `bestTimeFrames >= 0`, line 3 `delta(...)` of `lastSplitDelta`, line 4 `NEW RECORD` when `finished && newBest`. In `GameLoop`, call `timeAttackHudOverlay.render(timeAttackRuntime.hudState())` at the exact place the live-rewind/user-recording HUD draws (after scene, HUD overlay pass).

- [ ] **Step 4: Run test — PASS; compile clean**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackTimeFormat" test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/TimeAttackHudOverlay.java src/main/java/com/openggf/game/timeattack/TimeAttackTimeFormat.java src/test/java/com/openggf/game/timeattack/TestTimeAttackTimeFormat.java src/main/java/com/openggf/GameLoop.java
git commit -m "feat(timeattack): delta timer HUD overlay

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 12: Config keys + catalog + CONFIGURATION.md

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java` (add `TIME_ATTACK_RETRY_KEY`)
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java` (static-init `put(...)` — normal section, BEFORE the `debug.*` block)
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java` (default `"R"`, GLFW key-name string like `RECORDING_RECORD_KEY`)
- Modify: `CONFIGURATION.md` (document the key)
- Test: existing `src/test/java/com/openggf/configuration/TestConfigCatalog.java` must stay green (it fails on any uncatalogued key)

- [ ] **Step 1: Add enum constant + catalog meta + default**

`SonicConfiguration`: add `TIME_ATTACK_RETRY_KEY` beside `RECORDING_RECORD_KEY` (line ~323). `ConfigCatalog` static block (mirror the `RECORDING_RECORD_KEY` entry's factory + section naming exactly): `put(SonicConfiguration.TIME_ATTACK_RETRY_KEY, ConfigKeyMeta.of("timeAttack", "retryKey", STRING-type-used-by-neighbor, "Key that instantly retries the current time attack from the act start."));` — copy the exact `ConfigKeyMeta` factory and type token the recording key uses. `SonicConfigurationService`: default `"R"` following the recording key's default wiring.

- [ ] **Step 2: Run the catalog + config suites**

Run: `mvn "-Dtest=com.openggf.configuration.TestConfigCatalog" test`
Expected: PASS.

- [ ] **Step 3: Document in CONFIGURATION.md**

Add a row in the appropriate section: `timeAttack.retryKey` — default `R` — instant retry to act start during solo time attack.

- [ ] **Step 4: Wire the key in GameLoop retry handling (placeholder key from Task 10 becomes this constant), recompile, run time attack suites**

Run: `mvn "-Dtest=com.openggf.game.timeattack.*" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/ConfigCatalog.java src/main/java/com/openggf/configuration/SonicConfigurationService.java src/main/java/com/openggf/GameLoop.java CONFIGURATION.md
git commit -m "feat(timeattack): configurable retry key

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
```

---

### Task 13: Track catalog + Time Attack menu + launch path

**Files:**
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackTrackCatalog.java`
- Create: `src/main/java/com/openggf/game/timeattack/TimeAttackMenu.java`
- Modify: `src/main/java/com/openggf/game/MasterTitleScreen.java` (new menu entry + sub-mode, modeled EXACTLY on its existing `UserRecordingMenu` integration)
- Modify: `src/main/java/com/openggf/Engine.java` (launch handler modeled on `launchGameplayFromDataSelect`, Engine.java:1021)
- Test: `src/test/java/com/openggf/game/timeattack/TestTimeAttackTrackCatalog.java`
- Test: `src/test/java/com/openggf/game/timeattack/TestTimeAttackTrackCatalogRomValidation.java` (ROM-gated)

**Interfaces:**
- Produces:
  - `TimeAttackTrackCatalog.Track(String gameId, int zone, int act, String label, java.util.List<String> characters)` (record); `static java.util.List<Track> tracksFor(String gameId)`.
  - v1 curated track lists — signpost-terminated acts ONLY (spec §2: boss/capsule acts excluded; act indexes are ENGINE zone/act ints as taken by `loadZoneAndAct`):
    - `s1`: GHZ1 (0,0) "GREEN HILL 1", GHZ2 (0,1), MZ1 (2,0), MZ2 (2,1), SYZ1 (1,0), SYZ2 (1,1), LZ1 (3,0), LZ2 (3,1), SLZ1 (4,0), SLZ2 (4,1), SBZ1 (5,0) — characters `["sonic"]`.
    - `s2`: EHZ1 (0,0), CPZ1 (13,0), ARZ1 (15,0), CNZ1 (12,0), HTZ1 (7,0), MCZ1 (11,0), OOZ1 (10,0), MTZ1 (4,0), MTZ2 (4,1) — zone ints are ROM zone ids as used by `loadZoneAndAct` for S2 (VERIFY against `Sonic2Constants`/existing `loadZoneAndAct` callers before finalizing; the catalog is data, adjust freely) — characters `["sonic", "tails"]`.
    - `s3k`: AIZ1 (0,0), HCZ1 (1,0), MGZ1 (2,0), CNZ1 (3,0), ICZ1 (5,0), MHZ1 (7,0) — act-1 signpost acts of the stable zones — characters `["sonic", "tails", "knuckles"]`.
  - `TimeAttackMenu`: master-title sub-mode listing (game → track → character → ghosts (best auto + up to 7 imports via `GhostStore.listImports`) → GO). On GO, produces a `TimeAttackLaunchRequest`.
- Launch flow in `Engine` (new method `launchTimeAttack(TimeAttackLaunchRequest request)` — mirror `launchGameplayFromDataSelect` lines 1027-1031):
  1. Resolve `GameModule` for `request.gameId()` via `GameModuleRegistry` (ROM must be detected; menu greys out games whose ROM is absent — same check MasterTitleScreen uses for game entries).
  2. `SaveSessionContext saveContext = SaveSessionContext.noSave(...)` with a `SelectedTeam` for `request.character()` (read the exact `noSave` signature in `SaveSessionContext` and the team-construction used by `Engine.createDataSelectSaveContext` at Engine.java:1055 — construct the same way).
  3. `GameplayModeContext gameplay = SessionManager.openGameplaySession(module, saveContext); initializeGameplayRuntime(gameplay, false); loadLevelFromDataSelect(request.zone(), request.act()); gameLoop.setGameMode(GameMode.LEVEL);`
  4. `timeAttackRuntime.armForLaunch(request); timeAttackRuntime.onLevelReady();`
  5. On exit back to title (existing quit-to-title path): `timeAttackRuntime.deactivate()`.

- [ ] **Step 1: Write the failing catalog test**

```java
package com.openggf.game.timeattack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestTimeAttackTrackCatalog {
    @Test
    void everyGameHasTracksAndAllAreLabelled() {
        for (String game : new String[] {"s1", "s2", "s3k"}) {
            var tracks = TimeAttackTrackCatalog.tracksFor(game);
            assertFalse(tracks.isEmpty(), game);
            for (var t : tracks) {
                assertEquals(game, t.gameId());
                assertFalse(t.label().isBlank());
                assertFalse(t.characters().isEmpty());
            }
        }
    }

    @Test
    void unknownGameYieldsEmptyList() {
        assertTrue(TimeAttackTrackCatalog.tracksFor("nope").isEmpty());
    }

    @Test
    void s3kOffersAllThreeCharacters() {
        assertTrue(TimeAttackTrackCatalog.tracksFor("s3k").get(0).characters()
                .containsAll(java.util.List.of("sonic", "tails", "knuckles")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails; implement catalog (static `Map<String, List<Track>>` with the curated lists above); run test — PASS**

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackTrackCatalog" test`

- [ ] **Step 2b: ROM-gated catalog validation — every curated (zone, act) must actually load**

The label/character checks in Step 1 cannot catch a wrong zone id (the flagged risk for the S2 ROM-zone-id list). Add `TestTimeAttackTrackCatalogRomValidation`: for each game in the catalog, skip via `org.junit.jupiter.api.Assumptions.assumeTrue(...)` when that game's ROM is not present (same ROM-availability check `TestSonic3kLevelLoading` / `TestRomLogic` use), then for each `Track` load the level headless and assert it loads without throwing. Model the per-game level-load harness on `TestSonic3kLevelLoading` (S3K, exists today — reuse its setup verbatim) and on the equivalent existing S1/S2 headless level-loading tests (grep `src/test/java` for tests calling `loadZoneAndAct` or loading `Sonic1Level`/`Sonic2Level` headless; mirror the closest one per game). One `@Test` per game so a missing ROM skips only that game's validation. This test is the gate that makes the catalog's zone ints trustworthy — a wrong S2 zone id must FAIL here, not at menu launch.

Run: `mvn "-Dtest=com.openggf.game.timeattack.TestTimeAttackTrackCatalogRomValidation" test` (with ROMs present per CLAUDE.md, including `-Ds3k.rom.path` for S3K)
Expected: PASS; each catalog entry loads.

- [ ] **Step 3: Build TimeAttackMenu + MasterTitleScreen entry**

Copy the `UserRecordingMenu` integration verbatim as the structural template: how `MasterTitleScreen.update(InputHandler)` enters/leaves the sub-mode, how `draw()` delegates, how selection state is held. Menu columns: game (ROM-present games only) → track (from catalog) → character → ghost count summary (best found? N imports?) → GO/BACK.

- [ ] **Step 4: Engine.launchTimeAttack + quit-path deactivate; manual smoke**

Implement per Interfaces. Manual smoke test: `java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar` → Time Attack → S3K AIZ1 Sonic → run to signpost → verify timer, retry key, then rerun and verify the ghost renders and NEW RECORD only on improvement.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/timeattack/TimeAttackTrackCatalog.java src/main/java/com/openggf/game/timeattack/TimeAttackMenu.java src/test/java/com/openggf/game/timeattack/TestTimeAttackTrackCatalog.java src/test/java/com/openggf/game/timeattack/TestTimeAttackTrackCatalogRomValidation.java src/main/java/com/openggf/game/MasterTitleScreen.java src/main/java/com/openggf/Engine.java
git commit -m "feat(timeattack): track catalog, master-title menu, and launch path

Changelog: n/a: phase-1 entry added in c1 of this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 14: End-to-end verification + docs

**Files:**
- Modify: `CHANGELOG.md` (finalize entry), `README.md` (only at merge time per repo policy)

- [ ] **Step 1: Full targeted suites**

Run: `mvn "-Dtest=com.openggf.game.timeattack.*" test` then `mvn "-Dtest=com.openggf.game.ghost.*" test` then `mvn "-Dtest=com.openggf.net.identity.*" test` then `mvn "-Dtest=com.openggf.configuration.TestConfigCatalog" test`
Expected: ALL PASS.

- [ ] **Step 2: Must-keep-green S3K suites + guards**

Run: `mvn "-Dtest=TestS3kAiz1SkipHeadless" test`, `mvn "-Dtest=TestSonic3kLevelLoading" test`, `mvn "-Dtest=TestNoServicesInObjectConstructors" test`, `mvn "-Dtest=TestObjectServicesMigrationGuard" test`, `mvn "-Dtest=TestNoAssertionFreeDiagnostics" test`
Expected: ALL PASS (ROM-dependent tests need the S3K ROM present per CLAUDE.md).

- [ ] **Step 3: Manual end-to-end (the /verify pass)**

1. Launch jar → Time Attack → S3K → AIZ1 → Sonic → GO. Timer shows 0:00.00 until first input; runs after.
2. Reach a star post → split delta line appears (blank first run — no ghost).
3. Finish at signpost → NEW RECORD shown; `ghosts/s3k/0-0-sonic.ggfghost` + `.ggfinputs` exist.
4. GO again → translucent ghost races; verify it draws behind/in front of loops per its recorded layer; delta at star post shows signed value.
5. Press retry key mid-run → instant reload to spawn, attempt voided (no save), ghost restarts.
6. Beat the best → rotation: `-prev1` file appears.
7. Hold the live-rewind key during an attempt → nothing happens (suppressed).
8. Drop a copied `.ggfghost` into `ghosts/s3k/import/` → menu shows 1 import → race two ghosts at once.
9. Identity: `identity/player-identity.key` + `.pub` created the first time a run is armed (`TimeAttackRuntime.armForLaunch` calls `PlayerIdentity.loadOrCreate(identityDir)`); the INFO log shows the fingerprint, stable across restarts, and the saved best ghost's `displayName` is its first 8 hex chars (visible via the menu's ghost summary).

- [ ] **Step 4: Final commit + branch note**

```bash
git add CHANGELOG.md
git commit -m "docs(timeattack): finalize phase-1 changelog entry

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Merge to develop follows repo policy (README.md release-log update staged in the merge; superpowers:finishing-a-development-branch).

---

## Self-review notes (spec coverage)

- Spec §3 solo entry ✛ Task 13; `.ggfghost` format + input-hash binding ✛ Tasks 2/4/10; star-post splits ✛ Tasks 6/10; multi-ghost + imports ✛ Tasks 7/10/13; improvement flow keep-3 ✛ Task 7; anti-cheat gating (rewind/debug/editor → tainted/blocked) ✛ Task 10.
- Spec §6.1 `TimeAttackController` semantics live in `TimeAttackAttempt` + `TimeAttackRuntime` (naming: runtime = controller + session glue for solo; multiplayer phase 2 splits `RaceSession` out separately per spec).
- Spec §7 frame layout ✛ Task 1 (render-layer byte included); DPLC random access ✛ Task 9 (backwards-jump invalidation); ghost render registry ✛ Task 8 (TraceGhostHook untouched).
- Security §11 phase 1: keypair ✛ Task 3 (+ Task 14 step 3.9 wiring check); input-only spawn-anchored recording ✛ Task 4 (explicitly NO sidecar data); determinism fingerprint ✛ Tasks 4/10.
- Known judgment calls an implementer may adjust with evidence: exact S2 zone ints in the catalog (verify against `loadZoneAndAct` callers), the `GameServices.gameplayModeOrNull()` accessor name (mirror existing style), checkpoint-state acquisition (grep `DefaultObjectServices.checkpointState()` for the owning source).

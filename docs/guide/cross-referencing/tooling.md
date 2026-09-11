# Tooling

The engine includes built-in tools for exploring ROM data and verifying addresses against
the disassemblies. The primary tool is **RomOffsetFinder**.

## RomOffsetFinder

A command-line tool that searches the disassembly, calculates ROM offsets, and verifies
them against the actual ROM binary.

### Base Command

```bash
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="<command>" -q
```

The `-q` flag suppresses Maven output so you only see the tool's results.

### Game Selection

By default, the tool operates on the Sonic 2 disassembly. Use `--game` to select a
different game:

```bash
# Sonic 1
-Dexec.args="--game s1 search Crabmeat"

# Sonic 2 (default)
-Dexec.args="search Buzzer"

# Sonic 3 & Knuckles
-Dexec.args="--game s3k search AIZ"
```

### Commands

#### search -- Find items by name

Search for disassembly labels matching a partial name. This is the most commonly used
command.

```bash
# Find everything with "Buzzer" in the name
-Dexec.args="search Buzzer"

# Find all EHZ resources
-Dexec.args="search EHZ"

# Find palette labels
-Dexec.args="search Pal_"
```

Results show the label name, file path, compression type, and calculated ROM offset.
For S3K, this includes assembly data labels inside `.asm` files reached by `include`
directives, such as mapping tables under `Levels/*/Misc Object Data/`.

**When to use:** You know a label name (or part of one) from the disassembly and want to
find its ROM offset.

#### verify -- Check a calculated offset

Verify that a specific label's calculated offset matches the actual data in the ROM.

```bash
-Dexec.args="verify ArtNem_Buzzer"
```

The tool reads the ROM at the calculated offset and confirms whether the data there
matches the expected compression signature.

**When to use:** You want to confirm that the engine's address for a piece of data is
correct.

#### list -- List items by type

List all items of a given compression type, or all items if no type is specified.

```bash
# All Nemesis-compressed art
-Dexec.args="list nem"

# All Kosinski-compressed data
-Dexec.args="list kos"

# All uncompressed binary data
-Dexec.args="list bin"

# Everything
-Dexec.args="list"
```

Supported types: `nem` (Nemesis), `kos` (Kosinski), `kosm` (KosinskiM), `eni` (Enigma),
`sax` (Saxman), `bin` (uncompressed).

**When to use:** You want to see what resources exist of a certain type.

#### test -- Test decompression at an offset

Attempt to decompress data at a specific ROM offset.

```bash
# Test if offset 0xDD8CE contains Nemesis data
-Dexec.args="test 0xDD8CE nem"

# Auto-detect compression type
-Dexec.args="test 0x3000 auto"
```

**When to use:** You have a ROM offset and want to know if it contains valid compressed
data.

#### search-rom -- Find raw hex patterns in the ROM

Search the ROM binary for a specific byte pattern. Use this for inline data that has no
usable label, or as a byte-level cross-check. Labeled S3K include-file mappings,
DPLCs, and animation scripts should be resolved with `search` or `find` first.

```bash
# Search for a known byte pattern
-Dexec.args="search-rom \"07 72 73 26 15 08 FF 05\""

# Restrict search to a ROM range
-Dexec.args="search-rom \"0002\" 0x28000 0x29000"
```

Spaces in the hex string are optional.

**When to use:** You want to find unlabeled inline data, or you have a known byte pattern
and want to locate every ROM occurrence.

#### plc -- Inspect PLC entries

Show the contents of a Pattern Load Cue definition, listing which art entries it loads.

```bash
# Show ARZ PLC contents
-Dexec.args="plc ARZ"
```

**When to use:** You want to see which sprite art is loaded for a particular zone or event.

#### verify-batch -- Batch verify

Verify all items of a given type (or all items) in one run.

```bash
# Verify all Nemesis items
-Dexec.args="verify-batch nem"

# Verify everything
-Dexec.args="verify-batch"
```

**When to use:** You want a broad confidence check that the engine's addresses are correct.

#### export -- Generate Java constants

Export verified offsets as Java constant definitions.

```bash
-Dexec.args="export nem ART_"
```

This outputs lines like `public static final int ART_BUZZER = 0xDD8CE;` that can be
pasted into a constants file.

**When to use:** You are adding new ROM addresses to the engine and want correctly
formatted constant definitions.

## Disassembly Label Conventions

The tools expect labels that follow the disassembly naming conventions. These differ
between games:

| Concept | S1 label | S2 label | S3K label |
|---------|----------|----------|-----------|
| Nemesis art | `Nem_Crabmeat` | `ArtNem_Buzzer` | `ArtNem_` / `ArtKos_` |
| Kosinski art | `Kos_GHZ` | `ArtKos_EHZ` | `KosM_` |
| Uncompressed art | `Unc_Sonic` | `ArtUnc_Sonic` | `ArtUnc_` |
| Sprite mappings | `Map_Crabmeat` | `MapUnc_Sonic` | `Map_` |
| Palettes | `Pal_GHZ` | `Pal_EHZ` | `Pal_` |

See [Per-Game Notes](per-game-notes.md) for complete details.

## Tips

- **Start with `search`.** It is the fastest way to find anything. Use partial names.
- **Use `verify` when you find an address you want to use.** It catches revision mismatches.
- **Use `search`/`find` for labeled include-file data.** S3K mapping labels such as
  `Map_MHZPollen` resolve directly even when the data lives in an included `.asm` file.
- **Use `search-rom` for unlabeled pointer tables.** Music pointer tables, some scripts,
  and oscillation data may not have useful labels; use byte patterns for those.
- **The `--game` flag matters.** S1 labels (`Nem_`) are different from S2 labels (`ArtNem_`).
  Make sure you are searching the right disassembly.

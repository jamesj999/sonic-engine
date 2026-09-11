# Getting Started

This page gets you from zero to playing in under five minutes.

## What You Need

- **For downloaded releases:** no separate Java install is required; release packages include a
  native OpenGGF executable for your platform.
- **For source builds:** Java 21 or later. Download from [Adoptium](https://adoptium.net/) or
  your preferred distribution. Run `java -version` to check.
- **A GPU that supports OpenGL 4.1.** Any discrete GPU from the last decade will work.
  Integrated graphics (Intel HD 4000+, Apple Silicon) are fine.
- **ROM files** for the games you want to play. The engine does not include any game data.
  You must supply your own legally obtained copies.

### Expected ROM Files

The engine is verified against these specific ROM revisions. Other revisions may produce
incorrect results.

| Game | Expected Filename | Expected revision and hash |
|------|-------------------|----------------------------|
| Sonic 1 | `s1.gen` | World, Revision 01; CRC32 `AFE05EEE`; SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| Sonic 2 | `s2.gen` | World, Revision 01; CRC32 `7B905383`; SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| Sonic 3&K | `s3k.gen` | World lock-on combined ROM; CRC32 `63522553`; SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6` |

ROM filenames can be changed in `config.yaml` if yours differ. See
[Configuration](configuration.md) for details.

## Install and Run

### Option A: Download a Release

1. Download the latest release package for your platform from the Releases page:
   - Windows: `OpenGGF-windows.zip`
   - macOS: `OpenGGF-macos.zip`
   - Linux: `OpenGGF-linux.tar.gz`
2. Extract it to a folder.
3. Place your ROM files next to the editable `config.yaml` included in the package.
4. Start OpenGGF:
   - Windows: double-click `OpenGGF.exe`, or run it from a terminal.
   - macOS: open `OpenGGF.app`.
   - Linux: run `./OpenGGF` from the extracted `OpenGGF` directory.
5. If your ROM filenames differ from the defaults, edit `config.yaml` in the extracted package.

Windows terminal example:
   ```
   .\OpenGGF.exe
   ```

Linux terminal example:
   ```
   cd OpenGGF
   ./OpenGGF
   ```

### Option B: Build from Source

1. Clone the repository:
   ```
   git clone https://github.com/OpenGGF/OpenGGF.git
   cd OpenGGF
   ```
2. Build with Maven:
   ```
   tools/testing/install-hooks.sh
   mvn package
   ```
3. Place your ROM files in the project root directory (next to `pom.xml`).
4. Run:
   ```
   java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
   ```

## First Launch

When the engine starts, you will see:

1. **Master title screen** -- An engine-wide title screen with animated clouds and a game
   selection menu. Use the arrow keys to highlight a game and press Space to select it.
   When audio is enabled, navigation, confirmation, and missing-ROM errors use short
   host-owned cues that do not depend on the selected game's ROM.
2. **Game title screen** -- The selected game's original title screen (e.g., the Sonic 2
   "PRESS START BUTTON" screen).
3. **Gameplay** -- The first zone of the selected game.

If a ROM file is missing for the game you selected, the engine will show an error.

## Quick Configuration

The engine reads settings from `config.yaml` in the working directory. If the file
does not exist, defaults are used. A few settings you might want to change immediately:

| Setting | What it does | Default |
|---------|-------------|---------|
| `roms.default` | Which game boots first (`"s1"`, `"s2"`, or `"s3k"`) | `"s2"` |
| `startup.masterTitleScreen` | Show game picker on launch | `true` |
| `display.windowAutosize` | Derive the window size from the aspect preset | `true` |
| `audio.enabled` | Enable or disable sound | `true` |
| `characters.sidekick` | Add Tails as a CPU sidekick (`"tails"` or `""`) | `"tails"` |
| `debug.flags.editor` | Allow `Shift+Tab` to open the experimental editor overlay | `false` |

Key bindings can be written as names like `"SPACE"` and `"F9"` instead of raw numeric key codes.

For the full list, see [Configuration](configuration.md) or the
[Configuration Reference](../../../CONFIGURATION.md).

## What Next?

- [Controls](controls.md) -- Learn the keyboard layout
- [Game Status](game-status.md) -- See what works in each game
- [Troubleshooting](troubleshooting.md) -- If something went wrong

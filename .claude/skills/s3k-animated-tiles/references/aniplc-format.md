# AniPLC script format

`AniPlcParser.parseScripts()` parses the S2/S3K `zoneanimstart` / `zoneanimdecl`
format. Verify macro expansion against the selected ROM.

- List header: word count minus one; `0xFFFF` means empty.
- Entry long: signed duration byte in bits 24–31, 24-bit source-art ROM address.
- Destination word: VRAM byte address (tile index times 32).
- Two bytes: frame count, tiles copied per frame.
- Nonnegative global duration: one source tile-offset byte per frame.
- Negative duration: pairs of source tile offset and per-frame duration.
- Pad to even alignment before the next entry.

Example: `zoneanimdecl -1, ArtUnc_AniAIZ1_0, $2E6, 9, $C` selects per-frame
durations, nine frames, twelve tiles per copy, destination `$2E6`.
The following byte pairs specify source tile offsets and durations.

Keep this data in the ROM-loading pipeline. Verify the parser's signed duration
handling and frame timer semantics; the format alone does not determine whether
a gate pauses a timer, skips a write, or resets a channel.

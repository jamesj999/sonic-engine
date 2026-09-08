# S3K deformation mechanics

Use this reference for porting `ApplyDeformation` or a zone's arithmetic/fill loop.
Verify the selected routine and table in `docs/skdisasm/sonic3k.asm`.

## Intermediate words and scanlines

`HScroll_table` stores intermediate BG scroll words. `ApplyDeformation` walks
height entries from camera BG Y, skipping portions above the visible screen,
then distributes the remaining spans into the output buffer. Bit 15 of a height
entry means each scanline consumes a separate word; the low 15 bits give the count.
A table entry is not intrinsically a screen scanline.

## Arithmetic order

A ROM `move.w camera,d0; swap d0; clr.w d0; asr.l #1,d0` is represented by:

```java
int fixed = ((short) cameraX) << 16;
fixed >>= 1;
short integerPart = (short) (fixed >> 16);
```

For stepped band speeds, subtract from the fixed-point accumulator before
extracting the next band's word; repeatedly rounding integer speeds loses phase.
Persistent automatic-scroll accumulators retain their ROM width and fractional
bits. Do not translate `add.w` as unrestricted Java integer addition or assume
that every accumulator is 16.16. Use the actual subsequent read/shift sequence.

If the ROM subtracts screen shake before dividing camera Y and adds it back
later, preserve that order; dividing the shaken camera scales the shake incorrectly.

## Scatter-fill

The HCZ2/FBZ-style index table has groups:
`[count-1, byteOffset, byteOffset, ..., count-1, ..., 0xFF]`.
The signed negative marker ends the table; a `dbf` group writes count+1 entries.
Offsets address bytes in `HScroll_table`, so divide by two for a word-array index.
All destinations in a group receive one speed; only then advance the speed accumulator.

## Water and fine deltas

Waterline tables are indexed by the ROM's water/camera relationship; inspect
`HCZ Waterline Scroll Data` / `LBZ Waterline Scroll Data` labels for the source.
Resolve their ROM addresses rather than reading the disassembly `.bin` files.
Fine deformation deltas likewise come through the ROM pipeline. Match table index
wrap, phase clock, sign, and whether the delta applies to FG, BG, or both.

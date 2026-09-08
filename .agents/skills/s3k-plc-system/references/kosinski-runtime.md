# Kosinski module queue and results contracts


`Queue_Kos_Module` / `Process_Kos_Module_Queue` is a separate, gameplay-visible
scheduler. Do not replace `Kos_modules_left` polling with a guessed frame count,
and do not treat already-decompressed Java renderer art as proof that the native
readiness gate has opened.

- The FIFO has four six-byte entries: archive address (long) plus VRAM byte
  destination (word). Only the first, empty-queue enqueue reads and initializes
  its header immediately. Later enqueues must retain only those raw fields: do
  not read, validate, or derive header/module metadata until the entry shifts
  into slot zero. A malformed later header therefore enters the FIFO and fails
  only at that shift-time initialization boundary.
- Init consumes the big-endian size header, maps `$A000` to `$8000`, stores the
  active payload source as `archive+2`, derives `$800`-word modules, and stores
  the exact last-module word count. Reject invalid zero/one-byte headers rather
  than inventing a module.
- A normal module needs a start dispatch and a later DMA-completion dispatch.
  Completing an archive shifts the FIFO and initializes the next header, but
  cannot start that archive in the same call.
- After decompression from source `S` ends at `E`, the next source is
  `E + ((S-E) & $F)`: alignment preserves the payload source's low-nibble
  residue; it is not absolute 16-byte alignment.
- DMA destination advances by `$1000` bytes for a full module and by
  `Kos_last_module_size*2` for the final module. Use the header-derived word
  count (including its odd-byte truncation), not measured Java output length.
- Gameplay rewind must snapshot FIFO order, initialized/uninitialized entries,
  active payload source, evolving destination, modules-left raw phase/bit 7,
  last-module words, and the in-progress decompressor end. Recreated objects
  must use side-effect-free shells so queue restore is not followed by duplicate
  enqueues.

### Results-screen false-green checklist

`Obj_LevelResults` queues General -> `$520`, apparent-act Num1/Num2 -> `$568`,
and the selected character name -> `$578` or `$5A0`. `Obj_LevelResultsCreate`
returns without advancing its 360-frame wait while modules remain. It then
allocates twelve real `ObjArray_LevResults` SSTs: failure of the initial
`AllocateObjectAfterCurrent` retries without publication; failure of a later
`CreateNewSprite4` publishes the allocated prefix but leaves the parent's native
child count at twelve, so Wait2 retains the missing suffix. Do not heal it.
Children retire from the prior `Render_Sprites` bit-7 result using their own
width and native 320-pixel screen-space bounds; crossing an edge deletes on the
next object dispatch. Embedded arrays, fixed 9-frame gates, fixed offscreen
thresholds, and synthetic post-results retirement timers are false greens.

Focused validation:

```bash
mvn "-Dtest=TestKosinskiModuleQueue,TestKosinskiModuleQueueGameplayIntegration" test "-Ds3k.rom.path=$S3K_ROM_PATH"
mvn "-Dtest=TestS3kResultsKosQueueAndChildren,TestS3kResultsElementObjectInstance" test "-Ds3k.rom.path=$S3K_ROM_PATH"
```

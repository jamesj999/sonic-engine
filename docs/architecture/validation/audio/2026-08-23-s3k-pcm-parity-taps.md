# S3K PCM parity tap validation

Task 3 of the S3K SMPS first-slice plan adds diagnostic evidence boundaries; it
does not change normal playback. The Java fast path has a package-confined
disabled tap. The native core uses a separate `DIAGNOSTIC_S3K_PCM_ONLY` ABI
layered after the frozen ordinary observer and Task 2 chip-write ABI.

## Boundaries

- YM2612 stereo is captured after channel panning and discrete-chip mixing,
  immediately before the internal sample enters the GPGX output buffer.
- The held DAC code is captured on that same 1,008-master-cycle ordinal before
  any host interpolation, gain, or filter.
- PSG stereo is captured on the 240-master-cycle native clock after tone/noise,
  attenuation, and chip panning, before Blip resampling.
- Final interleaved signed 16-bit PCM is hashed in canonical little-endian form,
  with independent left/right onset and tail indices.

The first native implementation exported the YM callback but did not call it
from `ym2612.c`. That false-green was caught before staging: the corrected layer
now calls from the real pre-buffer mix boundary and the selftest compiles the
actual pinned YM core, restores one saved state twice, requires identical
absolute-schedule samples, and rejects a one-sample-shift poison.

## Reproducible native artifact

Two independent locked toolchain roots produced byte-identical artifacts:

- patch SHA-256: `d6f9926e8e68755492388929ab46c7ad8ef642dcd676f6231a6580c1ed78a14c`
- raw core: 43,475,744 bytes,
  `0d4689a1726a11e4f3d0f6763bb4c15d9f03abe7cd83004354675edb35d1e9b2`
- compressed core: 426,280 bytes,
  `241a748a5f7545cca82e4f3a34cf7457a7f62796da781acd74e69d24ac6b3809`
- build ID: `2f10a81d9ad1a747`
- selftest log:
  `8c238480238622e1a87ab5fb0a02d11ad566847d6ca26e2427e5ac9a64bf884e`
- ELF proof:
  `97a66dc883f341fb1c833c00552f5161ca38b7296c43e69957cdb8bbbb66b450`

The layered source is byte-equal to the reviewed working source, the patch
reverse-checks cleanly, the invisible section remains `0x3ab1d0` bytes (below
the 4 MiB cap), and all ordinary/Task 2/PCM/replay selftests pass. The complete
lock is `tools/bizhawk-headless/native/gpgx-audio-observer/s3k-pcm-artifact-lock.json`.

## Java verification

JDK 21.0.11:

- focused tap/snapshot/parity/architecture gate: 66 tests, 0 failures, 0 errors,
  1 existing opt-in skip;
- wider driver snapshot/presentation gate: 138 tests, 0 failures, 0 errors,
  0 skips;
- canonical final-PCM test digest:
  `2e0fd7007ede0d8b08b0e3c68d1eba3f589e11264d936b382687f46290202319`.

Task 4 must still bind authenticated ROM/movie state and native/OpenGGF write
schedules for Collapse, Spindash Release, and Invincibility. This infrastructure
does not assign a passing cross-core tolerance or claim those cases fixed.

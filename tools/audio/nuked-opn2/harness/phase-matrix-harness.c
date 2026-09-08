/*
 * Regenerates TestNukedOpn2PhaseMatrix fingerprints from the pinned ym3438.c.
 * Compile with -I pointing to the verified source directory (see ../PIN.md):
 * cc -O2 -I /path/to/pinned-nuked phase-matrix-harness.c -o phase-matrix
 * No reference implementation is reproduced here: call the actual C function.
 */
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include "ym3438.c"

static uint64_t phase_hash(ym3438_t *chip, uint64_t hash, int fnum, int block,
                           int code, int pms, int dt, int lfo, int multi)
{
    chip->cycles = 3;
    chip->channel = 0;
    chip->pg_fnum = fnum;
    chip->pg_block = block;
    chip->pg_kcode = code;
    chip->pms[0] = pms;
    chip->dt[3] = dt;
    chip->lfo_pm = lfo;
    chip->multi[3] = multi;
    OPN2_PhaseCalcIncrement(chip);
    uint32_t value = chip->pg_inc[3];
    for (int shift = 0; shift < 32; shift += 8)
        hash = (hash ^ ((value >> shift) & 255)) * UINT64_C(1099511628211);
    return hash;
}

int main(void)
{
    ym3438_t chip;
    OPN2_Reset(&chip);
    uint64_t zero = UINT64_C(14695981039346656037);
    for (int fnum = 0; fnum < 2048; fnum++)
        for (int lfo = 0; lfo < 32; lfo++)
            zero = phase_hash(&chip, zero, fnum, 7, 31, 0, 7, lfo, 30);
    uint64_t detune = UINT64_C(14695981039346656037);
    for (int dt = 0; dt < 8; dt++)
        for (int code = 0; code < 32; code++)
            detune = phase_hash(&chip, detune, 1024, 3, code, 0, dt, 0, 2);
    const int frequencies[] = {0, 1, 15, 16, 127, 128, 1023, 2047};
    const int blocks[] = {0, 3, 7};
    const int codes[] = {0, 15, 28, 31};
    const int multiples[] = {0, 1, 2, 15, 16, 30};
    uint64_t active = UINT64_C(14695981039346656037);
    for (int pms = 1; pms < 8; pms++)
        for (int lfo = 0; lfo < 32; lfo++)
            for (int f = 0; f < 8; f++)
                for (int b = 0; b < 3; b++)
                    for (int dt = 0; dt < 8; dt++)
                        for (int k = 0; k < 4; k++)
                            for (int m = 0; m < 6; m++)
                                active = phase_hash(&chip, active, frequencies[f], blocks[b],
                                                    codes[k], pms, dt, lfo, multiples[m]);
    printf("zero-pms 65536 %016" PRIx64 "\n", zero);
    printf("signed-detune 256 %016" PRIx64 "\n", detune);
    printf("active-pms 1032192 %016" PRIx64 "\n", active);
    return 0;
}

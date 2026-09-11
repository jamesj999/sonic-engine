/*
 * Reference harness for TestYm2612ChipNukedParity: drives the pinned
 * ym3438.c with exactly the bus pacing and frame summation that
 * com.openggf.audio.synth.Ym2612Chip applies, and prints the native frame
 * stream so the Java facade can be pinned against the C build.
 *
 * Script lines (stdin):
 *   type <flags>            OPN2_SetChipType(flags): 3 = YM2612|readmode, 2 = readmode
 *   write <port> <reg> <val> address strobe, ADDRESS_HOLD clocks, data strobe,
 *                           DATA_HOLD clocks (Ym2612Chip.ADDRESS_SETTLE_CYCLES /
 *                           DATA_SETTLE_CYCLES: 1 and 2 + 32, the busy window)
 *   render <n>              clock until n more frames have been consumed;
 *                           frames completed during write pacing count first,
 *                           exactly as Ym2612Chip.renderStereo(n) dequeues them
 *   status                  prints "STATUS <byte>"
 * Every completed 24-cycle frame prints "<left> <right>" (pin sum << 3);
 * the last line is "FRAMES <count> CHECKSUM <fnv1a64 hex>".
 *
 * Build: cc -O2 -o adapter_parity_harness adapter_parity_harness.c ym3438.c
 */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "ym3438.h"

/* Must equal Ym2612Chip.ADDRESS_SETTLE_CYCLES and DATA_SETTLE_CYCLES. */
#define ADDRESS_HOLD 1
#define DATA_HOLD (2 + 32)

static ym3438_t chip;
static int32_t sum_l, sum_r;
static uint64_t checksum = 0xcbf29ce484222325ULL;
static long frames;
static long consumed;

static void clock_once(void) {
    Bit16s buffer[2];
    OPN2_Clock(&chip, buffer);
    sum_l += buffer[0];
    sum_r += buffer[1];
    if (chip.cycles == 0) {
        int32_t l = sum_l << 3, r = sum_r << 3;
        printf("%d %d\n", l, r);
        checksum ^= (uint32_t) l; checksum *= 0x100000001b3ULL;
        checksum ^= (uint32_t) r; checksum *= 0x100000001b3ULL;
        frames++;
        sum_l = 0; sum_r = 0;
    }
}

int main(void) {
    char line[256];
    OPN2_SetChipType(ym3438_mode_ym2612 | ym3438_mode_readmode);
    OPN2_Reset(&chip);
    while (fgets(line, sizeof line, stdin)) {
        int a, b, c;
        if (sscanf(line, "type %d", &a) == 1) {
            OPN2_SetChipType((Bit32u) a);
        } else if (sscanf(line, "write %d %d %d", &a, &b, &c) == 3) {
            int i;
            OPN2_Write(&chip, (Bit32u) (a * 2), (Bit8u) b);
            for (i = 0; i < ADDRESS_HOLD; i++) clock_once();
            OPN2_Write(&chip, (Bit32u) (a * 2 + 1), (Bit8u) c);
            for (i = 0; i < DATA_HOLD; i++) clock_once();
        } else if (sscanf(line, "render %d", &a) == 1) {
            consumed += a;
            while (frames < consumed) clock_once();
        } else if (strncmp(line, "status", 6) == 0) {
            printf("STATUS %d\n", OPN2_Read(&chip, 0));
        }
    }
    printf("FRAMES %ld CHECKSUM %016llx\n", frames, (unsigned long long) checksum);
    return 0;
}

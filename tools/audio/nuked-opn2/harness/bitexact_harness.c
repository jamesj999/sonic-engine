/*
 * Bit-exactness harness (round 1) for the Nuked-OPN2 Java port.
 *
 * Drives the pinned upstream ym3438.c one internal cycle at a time with
 * OPN2_Clock and writes the raw MOL/MOR pin values of EVERY cycle as
 * little-endian int16 stereo pairs -- the chip's native, un-resampled
 * stream (one pair per OPN2_Clock, 24 per output sample). Nothing is
 * summed, scaled or resampled, so per-cycle equality with the Java twin
 * (NukedOpn2ScriptRunner) is the strongest statement the port can make.
 *
 * Script grammar (one command per line, '#' starts a comment):
 *   type <flags>            OPN2_SetChipType(flags): bit0 = ym2612, bit1 = readmode
 *   pace <a> <d>            cycles clocked after an address strobe / a data strobe
 *                           issued by "reg" lines (default 1 and 13, the pacing the
 *                           engine adapter documents in adapter_parity_harness.c)
 *   write <port> <data>     raw OPN2_Write: port is the 2-bit bus address
 *                           (0 = part I address, 1 = part I data, 2/3 = part II)
 *   reg <part> <reg> <val>  write part*2 reg; clock a; write part*2+1 val; clock d
 *   clock <n>               n OPN2_Clock calls
 *   at <frame>              clock until the running cycle count reaches frame*24
 *                           (frame stamps of a write log captured at the chip's
 *                           output rate of clock/144); no-op when already past
 *   status <port>           OPN2_Read(port) -> "STATUS <cycle> <byte>" in the side log
 *   irq                     OPN2_ReadIRQPin -> "IRQ <cycle> <bit>"
 *   dump                    selected ym3438_t fields -> side log (for bisecting)
 *
 * Usage: bitexact_harness <script> <out.pcm> <side.txt>
 * Prints "CYCLES <n> CHECKSUM <fnv1a64>" on stdout (FNV-1a over mol&0xffff,
 * mor&0xffff per cycle, the same fold TestNukedOpn2PortSmoke uses).
 *
 * Build: cc -O2 -I<pinned dir> bitexact_harness.c <pinned dir>/ym3438.c -o bitexact_harness
 */
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "ym3438.h"

static ym3438_t chip;
static FILE *pcm;
static FILE *side;
static uint64_t checksum = 0xcbf29ce484222325ULL;
static uint64_t cycles;
static int pace_a = 1, pace_d = 13;

static void clock_once(void) {
    Bit16s buffer[2];
    unsigned char bytes[4];
    OPN2_Clock(&chip, buffer);
    bytes[0] = (unsigned char) (buffer[0] & 0xff);
    bytes[1] = (unsigned char) ((buffer[0] >> 8) & 0xff);
    bytes[2] = (unsigned char) (buffer[1] & 0xff);
    bytes[3] = (unsigned char) ((buffer[1] >> 8) & 0xff);
    fwrite(bytes, 1, 4, pcm);
    checksum ^= (uint16_t) buffer[0]; checksum *= 0x100000001b3ULL;
    checksum ^= (uint16_t) buffer[1]; checksum *= 0x100000001b3ULL;
    cycles++;
}

static void clock_n(long n) {
    while (n-- > 0) clock_once();
}

static void dump(void) {
    int i;
    fprintf(side, "DUMP cycle=%llu cycles=%u channel=%u mol=%d mor=%d eg_timer=%u eg_cycle=%u lfo_cnt=%u lfo_am=%u lfo_pm=%u timer_a_cnt=%u timer_b_cnt=%u status=%u busy=%u dacdata=%u\n",
            (unsigned long long) cycles, chip.cycles, chip.channel, chip.mol, chip.mor, chip.eg_timer,
            chip.eg_cycle, chip.lfo_cnt, chip.lfo_am, chip.lfo_pm, chip.timer_a_cnt, chip.timer_b_cnt,
            chip.status, chip.busy, chip.dacdata);
    fprintf(side, "DUMP pg_phase");
    for (i = 0; i < 24; i++) fprintf(side, " %u", chip.pg_phase[i]);
    fprintf(side, "\nDUMP eg_level");
    for (i = 0; i < 24; i++) fprintf(side, " %u", chip.eg_level[i]);
    fprintf(side, "\nDUMP eg_state");
    for (i = 0; i < 24; i++) fprintf(side, " %u", chip.eg_state[i]);
    fprintf(side, "\nDUMP fm_out");
    for (i = 0; i < 24; i++) fprintf(side, " %d", chip.fm_out[i]);
    fprintf(side, "\nDUMP ch_out");
    for (i = 0; i < 6; i++) fprintf(side, " %d", chip.ch_out[i]);
    fprintf(side, "\n");
}

int main(int argc, char **argv) {
    FILE *script;
    char line[512];
    long a, b, c;
    if (argc < 4) {
        fprintf(stderr, "usage: %s <script> <out.pcm> <side.txt>\n", argv[0]);
        return 2;
    }
    script = fopen(argv[1], "r");
    pcm = fopen(argv[2], "wb");
    side = fopen(argv[3], "w");
    if (!script || !pcm || !side) { perror("open"); return 1; }
    OPN2_SetChipType(ym3438_mode_ym2612 | ym3438_mode_readmode);
    OPN2_Reset(&chip);
    while (fgets(line, sizeof line, script)) {
        if (line[0] == '#' || line[0] == '\n' || line[0] == '\r') continue;
        if (sscanf(line, "type %ld", &a) == 1) {
            OPN2_SetChipType((Bit32u) a);
        } else if (sscanf(line, "pace %ld %ld", &a, &b) == 2) {
            pace_a = (int) a; pace_d = (int) b;
        } else if (sscanf(line, "write %ld %ld", &a, &b) == 2) {
            OPN2_Write(&chip, (Bit32u) a, (Bit8u) b);
        } else if (sscanf(line, "reg %ld %ld %ld", &a, &b, &c) == 3) {
            OPN2_Write(&chip, (Bit32u) (a * 2), (Bit8u) b);
            clock_n(pace_a);
            OPN2_Write(&chip, (Bit32u) (a * 2 + 1), (Bit8u) c);
            clock_n(pace_d);
        } else if (sscanf(line, "clock %ld", &a) == 1) {
            clock_n(a);
        } else if (sscanf(line, "at %ld", &a) == 1) {
            uint64_t target = (uint64_t) a * 24;
            while (cycles < target) clock_once();
        } else if (sscanf(line, "status %ld", &a) == 1) {
            fprintf(side, "STATUS %llu %u\n", (unsigned long long) cycles, OPN2_Read(&chip, (Bit32u) a));
        } else if (strncmp(line, "irq", 3) == 0) {
            fprintf(side, "IRQ %llu %u\n", (unsigned long long) cycles, OPN2_ReadIRQPin(&chip));
        } else if (strncmp(line, "dump", 4) == 0) {
            dump();
        } else {
            fprintf(stderr, "unknown script line: %s", line);
            return 3;
        }
    }
    fclose(pcm); fclose(side); fclose(script);
    printf("CYCLES %llu CHECKSUM %016llx\n", (unsigned long long) cycles, (unsigned long long) checksum);
    return 0;
}

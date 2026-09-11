# Sonic 1 FM5 YM busy-write source program

This checked artifact models the relative timing between hardware data writes for
the source-authenticated first FM5 voice attack. It does not model service-entry
time or use a sound/zone/movie runtime carve-out.

The calculation uses seven master cycles per 68000 cycle and the checked
GPGX discrete-YM BUSY rule (master/42, 32 clocks after each data write).
The native instruction stream records cumulative 14-master-cycle refresh
delay at the exact GPGX refresh-add sites. The generator subtracts only that
counter delta; every remaining BUSY loop is exactly 259 master cycles.
Runtime resolves the checked no-refresh source costs dynamically for its
actual service-cursor residue. Captured final write cycles are comparison-only.

| Shape | Writes | Ledger SHA-256 |
|---|---:|---|
| `VOICE_NOTE` | 30 | `85572eb4af5a875469c1cf1152536e7c86fb155e6babf7dfe9771ac8a0c657c0` |
| `VOICE_PAN_NOTE` | 31 | `a6d385bc17a9efb79ee687897c3577fdc9f3225bd8f4212022cc09fe7a5ccf7a` |

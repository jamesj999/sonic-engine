# CNZ water-helper and badnik constant audit

Checklist verified against the locked-on `docs/skdisasm/sonic3k.asm`:

| Routine | Verified constants | Result |
|---|---|---|
| `Obj_CNZWaterLevelCorkFloor` (`134030`) | cork subtype `1`; target `$958`; `_unkFAA2` latch only | fixed: removed invented `_unkFAA3` button arm |
| `Obj_CNZWaterLevelButton` (`134058`) | Y offset `4`; solid extents `$1B,4,5`; target `$A58`; geyser SFX; `loc_62480` child subtype `$FF` | matches |
| `Obj_Clamer` (`185861`) | `$60` target range; spring impulse `$800,-$800`; close/raw scripts; projectile `-$200`; priorities `$280/$200` | matches |
| `Obj_Sparkle` (`186058`) | `$80` target range; wait `4`; fire wait `$20`; Y step `$68`; projectile velocity `$600`; deceleration `$40`; raw scripts | matches |
| `Obj_Batbot` (`186271`) | `$40` target range; initial/chase cap `$200`; chase step `8`; parent/body raw scripts and offsets `$10/3` | matches |

The water-helper mismatch was a flag-ownership error rather than a numeric target error: `_unkFAA3` is set by the earlier CNZ2 cutscene-button path at `loc_65CC2` (`sonic3k.asm:133988`), not by the cork-floor helper.

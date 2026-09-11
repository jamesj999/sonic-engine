# CNZ object priority audit

Verified against the locked-on `docs/skdisasm/sonic3k.asm` object initializers. ROM priority words map to engine buckets by dividing by `$80`. The final column records this audit's changes.

| Object | ROM priority / plane flag | Engine bucket | Changed? |
|---|---:|---:|---|
| Balloon | `$280`, normal | 5 | yes |
| Cannon | `$280`, normal | 5 | no |
| Rising platform | `$280`, normal | 5 | no |
| Trap door | `$080`, normal | 1 | no |
| Light bulb | `$280`, normal | 5 | yes |
| Hover fan | `$280`, normal | 5 | no |
| Cylinder | `$280`, normal | 5 | yes |
| Bumper | `$080`, normal | 1 | no |
| Water/cutscene button | `$200`, normal | 4 | no |
| Teleporter / beam | `$200` / `$180`, normal | 4 / 3 | no |
| Miniboss body / top / sparks | `$280` / `$200` / `$200`, high | 5 / 4 / 4 | no |
| Miniboss bounce / debris | `$100` / `$100`, inherited high art | 2 / 2 | no |
| End boss body / ship / attack children | `$280` (one defeat child `$200`), mixed plane flag | 5 (or 4) | report to boss task; not edited |
| Clamer body / projectile | `$280` / `$200`, high | 5 / 4 | plane flag fixed |
| Sparkle body / warning / projectile | `$280` / `$200` / `$280`, inherited high | 5 / 4 / 5 | plane flag fixed |
| Batbot body / visual children | `$280` / `$200`, inherited high | 5 / 4 | plane flag fixed |
| Barber pole, giant wheel, spiral/vacuum tubes, triangle bumper, wire cage | no sprite attributes (control objects) | 0 | no; engine visuals retain control-object layer |

The barber pole temporarily sets the *player's* `high_priority` art-tile bit while the player passes behind its foreground geometry (`sub_33392` / `sub_335C4`); it does not assign a sprite priority to the pole controller itself. The wire cage likewise has no sprite setup in `Obj_CNZWireCage`.

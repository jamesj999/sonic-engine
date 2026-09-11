# S3K in-level title-card Kosinski readiness audit

## Outcome

The shared in-level title-card path is not ready for a timing fix based only
on fixed dispatch counts. The engine loads Kosinski Moduled title-card art
synchronously in `Sonic3kTitleCardManager.loadKosmArt`, while the ROM uses
the resumable Kosinski module queue. Until that queue has a runtime owner for
its descriptor, bookmark, source/destination registers, and per-VBlank work,
the remaining AIZ and HCZ ring-reset differences cannot be attributed safely
to the title-card object alone.

## Native evidence

A bounded, stage-gated HCZ replay hooked the following locked-on ROM routines:

- `Queue_Kos_Module` (`$1AD2`)
- module initialization and processing (`$1AEA`, `$1B28`)
- `Queue_Kos` and bookmark handling (`$1BD8`, `$1BF0`)
- queue processing, completion, restore, and backup
  (`$1C1A`, `$1C26`, `$1CD2`, `$1CFA`, `$1CFC`, `$1D0C`)
- `Obj_TitleCard` initialization/create/wait (`$2D6A6`, `$2D76A`,
  `$2D804`)

The results object queues three archives. The first decompression establishes
and restores a bookmark over multiple VBlanks; subsequent modules continue
through later queue-service entries. HCZ retains 149 rings through f10428 and
`Obj_TitleCardWait` clears them at f10429. The engine currently clears them at
f10423, six frames early.

Standalone AIZ remains at f8837 with the inverse visible symptom: the ROM has
cleared the result rings while the engine still holds 100. Its earlier
end-sign lifecycle is already documented separately, so applying HCZ's
six-frame difference to AIZ would be a route-specific timing patch rather than
a shared decoder model.

## Required runtime owner

A viable shared implementation needs:

1. a FIFO of ROM-backed Kosinski module requests;
2. resumable decoder state, including descriptor bits, source/destination,
   module count, and bookmark restore state;
3. one explicitly scheduled service point per VBlank with the ROM's work
   budget and completion semantics;
4. title-card creation gated by queue state rather than a countdown;
5. rewind capture for the queue and active decoder state.

Only after that owner exists should AIZ and HCZ be remeasured. The acceptance
criterion is that their title-card/ring-reset timing emerges from the same
queue state without game, zone, route, trace, or frame predicates.

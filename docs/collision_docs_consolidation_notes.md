# Collision docs cross-check and consolidation notes (Rev01) (2026-01-04)

## Status: Consolidated

Action taken on 2026-01-04:

1.  **Resolved `x_radius` / `y_radius` contradiction:**
    *   Verified against `s2.constants.asm` and `TouchResponse` disassembly.
    *   **Result:** `y_radius` is at offset `$16` (used for height). `x_radius` is at offset `$17` (used for width).
    *   **Action:** Updated `sonic2_rev01_player_collision_sensors.md` which had these swapped. `sonic2_rev01_object_collision_boxes.md` was already correct.

2.  **Resolved Spikes Widths:**
    *   Verified `Obj36_InitData` in ROM. Values are `$10, $20...` (16, 32...).
    *   Verified `SolidObject` call adds `$0B` to this value.
    *   **Result:** `sonic2_rev01_object_collision_boxes.md` correctly lists the table values. The other doc was incorrect/confusing.

3.  **Resolved Monitoring Solidity Address:**
    *   Verified `SolidObject_Monitor` is at `$1271C`.
    *   **Result:** `sonic2_rev01_object_collision_boxes.md` was correct.

4.  **Consolidated Content:**
    *   Merged "Diagnostics", "Spring Strength Values", and "Monitor Rolling Check" details from `sonic2_rev01_collision_springs_spikes_monitors.md` into `sonic2_rev01_object_collision_boxes.md`.
    *   **Deleted** `sonic2_rev01_collision_springs_spikes_monitors.md` as it is now redundant.

## Canonical Documents

*   **Object Collision:** `sonic2_rev01_object_collision_boxes.md` (Master source for objects, spikes, springs, monitors, offsets).
*   **Player Terrain Collision:** `sonic2_rev01_player_collision_sensors.md` (Master source for terrain sensors, but radius offsets are now aligned with object doc).

============
sonic-engine
============


Introduction
============
graphics and the final aim is to have an extensible engine where people can write their own characters and perhaps
implement a full level editor. It should also match the original engine pixel for pixel for true authentic gameplay.

Status
======
ALPHA 1
Real level collision/palette/graphics data is loaded in and displayed. A basic version of Sonic physics interacts with it and allows movement through recognisable levels.
Physics are imperfect and bugs are aplenty. Sound is pretty much perfect but not integrated into the engine yet.
No support for objects yet.

Releases
========
V0.01 (Pre-Alpha) (Unreleased) - A moving black box. This version will be complete when we have an unskinned box that can traverse
terrain in the same way Sonic would in the original game.

V0.05 - Little more than a tech demo. Sonic is able to run and jump and collide with terrain in a reasonably correct way. No
graphics have yet been implemented so it's a moving white box on a black background.

V0.1 - Level tile/graphics/collision/startpos etc. data read in from Sonic 2 international ROM. Levels are rendered correctly, no parallax scrolling.
Basic Sonic physics are now in place, including collisions with real data, sensor lines for ground/air and running modes for walls (and control freeze timer).
Audio/SFX engine added emulating original hardware. Some early SFX mapped. Still buggy physics, no object loading, a lot left to do!

Changelog
=========
Changelog will be added when we move out of Pre-Alpha status.

============
sonic-engine
============


Introduction
============
This Java-based Sonic the Hedgehog game engine aims to fully and faithfully recreate the original physics of the Sonic
The Hedgehog games for Sega Mega Drive (Genesis). It will be capable of loading the game data from the original ROMs of
the games and providing a pixel-perfect gameplay experience of the original games. It will also provide more modern features,
such as a level editor, and an open framework allowing for modding and customisation.

This project is a work in progress, for the current state please see the latest version in the Releases section of this
document

Status
======
PRE-ALPHA:
No official releases have yet been made; the engine is still in pre-alpha state. This means that the graphics are an
un-skinned representation of the engine and not all engine features will be complete. It will move into alpha state
when basic physics are implemented correctly.

Releases
========
V0.01 (Pre-Alpha) (Unreleased) - A moving black box. This version will be complete when we have an unskinned box that can traverse
terrain in the same way Sonic would in the original game.

V0.05 - Little more than a tech demo. Sonic is able to run and jump and collide with terrain in a reasonably correct way. No
graphics have yet been implemented so it's a moving white box on a black background.

0.1.20260110 - Now vaguely resembles the actual Sonic 2 game. Real collision and graphics data is loaded from the Sonic 2 ROM and rendered
on screen. The majority of the physics are in place, although it is far from perfect. A system for loading game objects has
been created, along with an implementation for most of the objects and Badniks in Emerald Hill Zone. Rings are implemented, life and score
tracking is implemented. SoundFX and music are implemented. Everything has room for improvement, but this now resembles a playable
game.

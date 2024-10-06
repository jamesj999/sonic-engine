--------------------------------------------------------------------------------------
-- Sonic 2 Collision Display for Physics Guide v1.1
--------------------------------------------------------------------------------------
-- This script reads RAM data from Sonic 2 as you play, reading information about the
-- states of the player, and of all objects currently active. This data is processed
-- and displayed back in an intuitive way to show hitboxes, solid object collision,
-- and other information such as the state of objects, the variables of the player,
-- and more.
--
-- Differences to guide:
-- What you see using this overlay may differ to the handmade visuals on the guide.
-- Such as:
--   Sensors are shown in colours according to their direction, rather than labelling
--   them. So Sonic's A + B sensors will both be one colour, and when he is in wall
--   Mode, they will change to the colour for sensors facing that side.
--
-- The purpose of this script is to illustrate as you play the ideas and information
-- put forth by the guide thus making it easier to understand.

-- Credits:
-- By @LapperDev (Sensors, Hitboxes & Solid Objects) and @MercurySilver (Terrain & Misc Additions)
--------------------------------------------------------------------------------------

-----------------
--- Constants ---
-----------------

	-- General
	HORIZONTAL 				= 0;
	VERTICAL				= 1;

	-- Colours and opacities
	COLOUR_BLACK 			= "black";
	COLOUR_WHITE			= "white";
	COLOUR_GREEN_DARK		= {0, 160, 120};
	COLOUR_NONE				= {0, 0, 0, 0};

	COLOUR_TEXT_DECIMAL		= COLOUR_WHITE;
	COLOUR_TEXT_HEX			= {128, 255, 240};
	COLOUR_TEXT				= COLOUR_TEXT_DECIMAL;

	COLOUR_HITBOX 			= {};
	COLOUR_HITBOX.PLAYER 	= {255, 0, 255, 128};	-- Colour of hitboxes
	COLOUR_HITBOX.BADNIK	= {255, 0, 150, 128};
	COLOUR_HITBOX.HURT		= {255, 0, 0, 128};
	COLOUR_HITBOX.INCREMENT	= {150, 0, 255, 140};
	COLOUR_HITBOX.SPECIAL	= {60, 0, 255, 128};

	COLOUR_SIZE 			= {255, 255, 0, 128};	-- Colour of object sizes
	COLOUR_SOLID			= {0, 255, 0, 128};	-- Colour of solid boxes
	COLOUR_PLATFORM			= {3, 252, 144, 128};	-- Colour of platform surfaces
	COLOUR_PLATFORM_EDGES	= {3, 252, 144, 230};
	COLOUR_TRIGGER			= {0, 255, 255, 100};	-- Colour of object triggers
	COLOUR_LAYERSWITCH		= {255, 128, 0, 64};	-- Colour of layer switchers (area)
	COLOUR_LAYERSWITCHLINE	= {255, 128, 0, 255};	-- Colour of layer switchers (line)

	OPACITY_TILES			= {0.8, 0.65}
	COLOUR_TILES			= {};
	COLOUR_TILES.TOP 		= {0, 113, 235}
	COLOUR_TILES.SIDES		= {0, 255, 168}
	COLOUR_TILES.FULL		= {255, 255, 255};
	COLOUR_TILES.NONE		= {64, 64, 64}
	COLOUR_TILE_FLAG		= {0, 178, 178};

	COLOUR_SENSOR 			= {};
	COLOUR_SENSOR.DOWN 		= {0, 240, 0};
	COLOUR_SENSOR.UP 		= {0, 174, 239};
	COLOUR_SENSOR.LEFT 		= {255, 56, 255};
	COLOUR_SENSOR.RIGHT 	= {255, 84, 84};
	COLOUR_SENSOR.ANCHOR 	= COLOUR_WHITE;

	COLOUR_SENSOR.A			= {0, 240, 0};
	COLOUR_SENSOR.B			= {56, 255, 162};
	COLOUR_SENSOR.C			= {0, 174, 239};
	COLOUR_SENSOR.D			= {255, 242, 56};
	COLOUR_SENSOR.E			= {255, 56, 255};
	COLOUR_SENSOR.F			= {255, 84, 84};

	OPACITY_DARKEN			= {0.25, 0.5, 0.75, 1}

	-- Character Specific
	CHARACTER_NAMES = {[0] = "Sonic", [1] = "Tails"}

		-- Animations
		PLAYER_ANIMATION_NAMES = {[0] = {}, [1] = {}}

		-- Sonic
		PLAYER_ANIMATION_NAMES[0] = {
		[0] = "Walk",
		[1] = "Run",
		[2] = "Roll",
		[3] = "Roll Fast",
		[4] = "Push",
		[5] = "Wait",
		[6] = "Balance",
		[7] = "LookUp",
		[8] = "Duck",
		[9] = "Spindash",
		[10] = "Blink",
		[11] = "GetUp",
		[12] = "Balance2",
		[13] = "Stop",
		[14] = "Float",
		[15] = "Float2",
		[16] = "Spring",
		[17] = "Hang",
		[18] = "Dash2",
		[19] = "Dash3",
		[20] = "Hang2",
		[21] = "Bubble",
		[22] = "DeathBW",
		[23] = "Drown",
		[24] = "Death",
		[25] = "Hurt",
		[26] = "Hurt2",
		[27] = "Slide",
		[28] = "Blank",
		[29] = "Balance3",
		[30] = "Balance4",
		[31] = "Transform",
		[32] = "Lying",
		[33] = "LieDown"};

		-- Tails
		PLAYER_ANIMATION_NAMES[1] = {
		[0] = "Walk",
		[1] = "Run",
		[2] = "Roll",
		[3] = "Roll Fast",
		[4] = "Push",
		[5] = "Wait",
		[6] = "Balance",
		[7] = "LookUp",
		[8] = "Duck",
		[9] = "Spindash",
		[10] = "Dummy1",
		[11] = "Dummy2",
		[12] = "Dummy3",
		[13] = "Stop",
		[14] = "Float",
		[15] = "Float2",
		[16] = "Spring",
		[17] = "Hang",
		[18] = "Blink",
		[19] = "Blink2",
		[20] = "Hang2",
		[21] = "Bubble",
		[22] = "DeathBW",
		[23] = "Drown",
		[24] = "Death",
		[25] = "Hurt",
		[26] = "Hurt2",
		[27] = "Slide",
		[28] = "Blank",
		[29] = "Dummy4",
		[30] = "Dummy5",
		[31] = "Haul",
		[32] = "Fly"};

	-- Angle modes
	MODE_NAMES = {[0] = "Floor", [1] = "Right Wall", [2] = "Celing", [3] = "Left Wall"};

	-- Display text templates
	DISPLAY_TEXT_TEMPLATE = "== POS AND SPEED =="
	.. "\nX: %s"
	.. "\nY: %s"
	.. "\nX Spd: %s"
	.. "\nY Spd: %s"
	.. "\nG Spd: %s"
	--	.. "\n"
	.. "\n  == SENSORS =="
	.. "\n"
	.. "\n"
	.. "\n"
	--	.. "\n"
	.. "\n   == ANGLE =="
	.. "\nAngle: %s (%s')"
	.. "\nMode: %s"
	--	.. "\n"
	.. "\n   == FLAGS =="
	.. "\nGrounded: %s"
	.. "\nOn Object: %s"
	.. "\nFacing: %s"
	.. "\nPushing: %s"
	.. "\nControl Lock: %s"
	.. "\nStick: %s"
	.. "\nLayer: %s"
	.. "\nPriority: %s"
	--	.. "\n"
	.. "\n  == ANIMATION =="
	.. "\nSprite: %s (%s)"
	.. "\nFrame: %s (%s)"
	.. "\nDuration: %s (%s)"
	.. "\nTimer: %s"

	-- Ram positions
	OFF_PAUSED 				= 0xFFFFF63A
	OFF_UPDATE_TIMER		= 0xFFFE1E;
	OFF_GAME_TIMER			= 0xFFFE04;
	OFF_CAMERA 				= 0xFFEE00;
	OFF_PLAYER 				= 0xFFB000;
	OFF_OBJECTS 			= 0xFFB000;
		SIZE_OBJECT 			= 0x40;
	OFF_GAMEMODE      		= 0xFFF600;

	OFF_OSCILLATING_DATA	= 0xFFFFFE60;


	-- References to positions in the actual game code:

	-- VBlank
	EX_VBLANK 				= 0x00000408; -- VBlank:

	-- Collision
	EX_VERTICAL_SENSOR 		= 0x0001E7D0; -- FindFloor:
	EX_HORIZONTAL_SENSOR 	= 0x0001E9B0; -- FindWall:
	EX_SOLID_OBJECT2		= 0x000199F0; -- SolidObject_cont:
	EX_SOLID_OBJECT_EXIT		= 0x0001973E; -- move.w	d1,d2    --(in SolidObject:)
	EX_SOLID_OBJECT_EXIT2		= 0x00019796; -- move.w	d1,d2    --(in SolidObject_Always_SingleCharacter:)

	EX_PLAYER_HITBOX		= 0x0003F59C; -- move.w	#(Dynamic_Object_RAM_End-Dynamic_Object_RAM)/object_size-1,d6  --(in TouchResponse:)
	EX_OBJECT_HITBOX		= 0x0003F5B4; -- @Touch_Width:    --(in TouchResponse:)
	EX_PLAYER_BOSS_HITBOX	= 0x0003F698; -- move.w	#(Dynamic_Object_RAM_End-Dynamic_Object_RAM)/object_size-1,d6 --(in Touch_Boss:)
	EX_BOSS_HITBOX			= 0x0003F6AE; -- loc_3F6AE:  --  --(in Touch_Boss:)
	EX_RING_HITBOX			= 0x00017112;

	EX_PLATFORM				= 0x00019DBA; -- PlatformObject_cont:
	EX_PLATFORM_EXIT		= 0x00019C52; -- add.w	d2,d2  --(in PlatformObject_SingleCharacter)
	EX_PLATFORM_EXIT2		= 0x00019D02; -- add.w	d2,d2  --(in loc_19CF8)

	EX_PLATFORM2 			= 0x00019D3A; -- PlatformObjectD5:
	EX_PLATFORM2_EXIT 		= 0x00019D62; --  move.w	d1,d2--(in loc_19D62:)


	EX_SLOPED_PLATFORM		= 0x00019E90; -- SlopedPlatform_cont:
	EX_SLOPED_PLATFORM_EXIT	= 0x00019C8A; -- SlopeObject2:

	EX_SLOPED_SOLID_OBJECT	= 0x0001992E; -- SlopedSolid_cont:
	EX_SLOPED_SOLID_OBJECT_EXIT	= 0x000197EE; -- move.w	d1,d2 (in SlopedSolid_SingleCharacter):

	-- Player sensors
	EX_PLAYER_SENSOR_A		= 0x0001E306; -- move.w	(sp)+,d0  --(in Sonic_AnglePos:)
	EX_PLAYER_SENSOR_B		= 0x0001E2D8; -- move.w	d1,-(sp)  --(in Sonic_AnglePos:)
	EX_PLAYER_SENSOR_A_L	= 0x0001E516; -- move.w	d1,-(sp)  --(in Sonic_WalkVertL:)
	EX_PLAYER_SENSOR_B_L	= 0x0001E546; -- move.w	(sp)+,d0  --(in Sonic_WalkVertL:)
	EX_PLAYER_SENSOR_A_C	= 0x0001E468; -- move.w	d1,-(sp)  --(in Sonic_WalkCeiling:)
	EX_PLAYER_SENSOR_B_C	= 0x0001E498; -- move.w	(sp)+,d0  --(in Sonic_WalkCeiling:)
	EX_PLAYER_SENSOR_A_R	= 0x0001E3EA; -- move.w	(sp)+,d0  --(in Sonic_WalkVertR:)
	EX_PLAYER_SENSOR_B_R	= 0x0001E3BE; -- move.w	d1,-(sp)  --(in Sonic_WalkVertR:)
	EX_PLAYER_SENSOR_A_AIR	= 0x0001ECC0; -- move.w	(sp)+,d0  --(in Sonic_HitFloor:)
	EX_PLAYER_SENSOR_B_AIR	= 0x0001EC94; -- move.w	d1,-(sp)  --(in Sonic_HitFloor:)
	EX_PLAYER_SENSOR_E		= 0x0001F07E; -- move.b	#$40,d2  --(in Sonic_HitWall:)
	EX_PLAYER_SENSOR_F		= 0x0001EEF8; -- move.b	#-$40,d2  --(in sub_14EB4:)
	EX_PLAYER_SENSOR_C		= 0x0001EF8C; -- move.w	(sp)+,d0  --(in Sonic_DontRunOnWalls:)
	EX_PLAYER_SENSOR_D		= 0x0001EF5C; -- move.w	d1,-(sp)  --(in Sonic_DontRunOnWalls:)

	-- Special
	EX_SWING_PLATFORM		= 0x00019EC8; -- PlatformObject2_cont:
	EX_SWING_PLATFORM_EXIT 	= 0x00019D00; -- move.w	d1,d2  --(in loc_19CF8:)
	EX_MONITOR_SOLID		= 0x0001275C; -- Mon_SolidSides:
	EX_MONITOR_SOLID_EXIT		= 0x00012782; -- move.w	d1,d2    --(in Obj26_ChkOverEdge:)
	EX_SOLID_WALL 			= 0x00009158; -- Obj44_SolidWall2:
	EX_BRIDGE_PLATFORM		= 0x00019D9C;-- PlatformObject11_cont:
	EX_BRIDGE_PLATFORM_EXIT	= 0x000F892; -- moveq	#0,d0 (in loc_F8F0:)

-------------
--- Input ---
-------------

	-- Data
	INPUT_PREVIOUS 			= input.read();
	INPUT 					= input.read();
	INPUT_PRESS 			= input.read();

----------------
--- Controls ---
----------------

	OVERLAY_CONTROLS = {}
	OVERLAY_CONTROL_COUNT = 0;

	--------------------------------------------------------------------------------------
	-- ControlAdd(name, colour, options, selected, shortcut)
	--------------------------------------------------------------------------------------
	-- Adds an overlay control with it's name, colour, available options, selected option and shortcut
	--------------------------------------------------------------------------------------
	function ControlAdd(name, colour, options, selected, shortcut)
		-- Create control
		local control = {name = name, colour = colour, options = options, selected = selected, shortcut = shortcut};
		control.label = control.name .. ": " .. control.shortcut;
		control.width = string.len(control.label) * 4;
		control.height = 7;

		-- Add control to list (reference by position/id)
		OVERLAY_CONTROLS[OVERLAY_CONTROL_COUNT +  1] = control;
		OVERLAY_CONTROL_COUNT = OVERLAY_CONTROL_COUNT + 1;

		return OVERLAY_CONTROL_COUNT;
	end

	--------------------------------------------------------------------------------------
	-- ControlChange(id)
	--------------------------------------------------------------------------------------
	-- Incriment state of a control
	--------------------------------------------------------------------------------------
	function ControlChange(id)
		-- Find control
		local control = OVERLAY_CONTROLS[id];

		-- Increment and wrap
		control.selected = control.selected + 1;
		options = control.options;

		if control.selected > #options then
			control.selected = 1;
		end
	end

	--------------------------------------------------------------------------------------
	-- ControlGetState(id)
	--------------------------------------------------------------------------------------
	-- Gets the state of a control
	--------------------------------------------------------------------------------------
	function ControlGetState(id)
		-- Find control
		local control = OVERLAY_CONTROLS[id];

		--Return selected option
		return control.options[control.selected];
	end

	--------------------------------------------------------------------------------------
	-- ControlGetShortcut(id)
	--------------------------------------------------------------------------------------
	-- Gets the shortcut of a control
	--------------------------------------------------------------------------------------
	function ControlGetShortcut(id)
		-- Find control
		local control = OVERLAY_CONTROLS[id];

		--Return selected option
		return control.shortcut;
	end

	-- General Toggles
	ControlShowOverlay		= ControlAdd("Show/Hide Overlay", "white", {true, false}, 1, "Q"); -- Show overlay visuals
	ControlShowShortcuts	= ControlAdd("Show Shortcuts", "white", {true, false}, 2, "W"); -- Draw shortcuts
	ControlPlayerVariables	= ControlAdd("Player Variables", "white", {true, false}, 2, "E"); -- Draw current player variables
	ControlDarkening		= ControlAdd("Darkening", "white", {0, 25, 50, 75, 100}, 4, "R"); -- How much to darken the game
	ControlCameraBounds		= ControlAdd("Camera Bounds", "white", {true, false}, 2, "T"); -- Draw camera bounds
	ControlHexValues		= ControlAdd("Hex Values", "white", {true, false}, 2, "Y"); -- Are values displayed as hexidecimal

	-- Solid tiles (Terrain)
	ControlTerrain			= ControlAdd("<> Terrain", "white", {"None", "Plain", "Degrees", "Real"}, 2, "U"); -- Draw the solid tiles
	ControlTerrainLayers	= ControlAdd("<> Layer", "white", {"Mixed", "Current", "Both"}, 1, "K"); -- Draw the layers

	-- Objects
	ControlHitboxes			= ControlAdd("Object Hitboxes", COLOUR_HITBOX.BADNIK, {true, false}, 1, "I"); -- Draw hitboxes
	ControlTriggers			= ControlAdd("Object Triggers", COLOUR_TRIGGER, {true, false}, 1, "O");	-- Draw triggers
	ControlSensors			= ControlAdd("Object Sensors", COLOUR_SENSOR.DOWN, {true, false}, 1, "P"); -- Draw sensor representations
	ControlSolidity			= ControlAdd("Object Solidity", COLOUR_SOLID, {true, false}, 1, "F"); -- Draw solid box representations
	ControlSize				= ControlAdd("Object Width/Height", COLOUR_SIZE, {true, false}, 2, "G"); -- Draw object size (Width Radius/Height Radius), this isn't always relevant and not all solid objects use this size (though, it is relevant for all object collision with terrain and player collision with objects)
	ControlInfo				= ControlAdd("Object Info", "white", {true, false}, 2, "H"); -- Draw object names and information like id, sub id, and animation frame
	ControlSmoothing		= ControlAdd("Smoothing (Effect)", "white", {true, false}, 2, "J"); -- Smoothed collision positions (inaccurate).
		-- If disabled, you see collisions as they have truly occurred that frame. Sensors might shake a bit when walking on slopes, solidboxes and hit boxes may appear to lag behind moving objects (except for Sonic, but it will when he is standing on a moving object), this is because these checks occur before the object moves.
		-- If enabled, shows the sensors/boxes relative to the final screen position of the object, even if the collision check happened before the object moved (which is usually the case). Looks nicer, but isn't accurate.

------------------------
--- Global Variables ---
------------------------
	GameTimer = 0;
	GameTimerPrevious = 0;

	GamePaused = 0;

	CurrentZone = memory.readbyte(0xFE10);
	CurrentAct = memory.readbyte(0xFE11);


-----------------------
--- Input Functions ---
-----------------------

	--------------------------------------------------------------------------------------
	-- InputUpdate()
	--------------------------------------------------------------------------------------
	-- Updates input and registers initial presses.
	--------------------------------------------------------------------------------------
	function InputUpdate()
		-- Load inputs
		INPUT_PREVIOUS = copytable(INPUT);
		INPUT = input.read();

		-- Update press events
		for k, v in pairs(INPUT) do
			INPUT_PRESS[k] = nil;

			local v_prev = INPUT_PREVIOUS[k];
			if v_prev == nil and v == true then
				INPUT_PRESS[k] = true;		-- Key has been pressed
			else
				INPUT_PRESS[k] = nil;		-- Key has not been pressed
			end
		end
	end


------------------------------------
--- Sonic 2 Objects ---
------------------------------------
	OBJECT_NAMES =
	{
		[0x01] = "Sonic",					[0x02] = "Tails",					[0x03] = "LayerSwitcher",			[0x04] = "WaterSurface",
		[0x05] = "TailsTails",				[0x06] = "Spiral",					[0x07] = "Oil",						[0x08] = "SpindashDust",
		[0x08] = "Splash",					[0x09] = "SonicSS",					[0x0A] = "SmallBubbles",			[0x0B] = "TippingFloor",
		[0x0D] = "GoalPlate",				[0x0E] = "IntroStars",				[0x0F] = "TitleMenu",				[0x10] = "TailsSS",
		[0x11] = "Bridge",					[0x12] = "HPZEmerald",				[0x13] = "HPZWaterfall",			[0x14] = "Seesaw",
		[0x15] = "SwingingPlatform",		[0x16] = "HTZLift",					[0x18] = "ARZPlatform",				[0x18] = "EHZPlatform",
		[0x19] = "CPZPlatform",				[0x19] = "OOZMovingPform",			[0x19] = "WFZPlatform",				[0x1A] = "HPZCollapsPform",
		[0x1B] = "SpeedBooster",			[0x1C] = "Scenery",					[0x1C] = "BridgeStake",				[0x1C] = "Stump/FallingOil",
		[0x1D] = "BlueBalls",				[0x1E] = "CPZSpinTube",				[0x1F] = "CollapsPform",			[0x20] = "LavaBubble",
		[0x21] = "HUD",						[0x22] = "ArrowShooter",			[0x23] = "FallingPillar",			[0x24] = "Bubbles",
		[0x25] = "Ring",					[0x26] = "Monitor",					[0x27] = "Explosion",				[0x28] = "Animal",
		[0x29] = "Points",					[0x2A] = "Stomper",					[0x2B] = "RisingPillar",			[0x2C] = "LeavesGenerator",
		[0x2D] = "Barrier",					[0x2E] = "MonitorContents",			[0x2F] = "SmashableGround",			[0x30] = "RisingLava",
		[0x31] = "LavaMarker",				[0x32] = "BreakableBlock",			[0x32] = "BreakableRock",			[0x33] = "OOZPoppingPform",
		[0x34] = "TitleCard",				[0x35] = "InvStars",				[0x36] = "Spikes",					[0x37] = "LostRings",
		[0x38] = "Shield",					[0x39] = "GameOver",				[0x39] = "TimeOver",				[0x3A] = "Results",
		[0x3D] = "OOZLauncher",				[0x3E] = "EggPrison",				[0x3F] = "Fan",						[0x40] = "Springboard",
		[0x41] = "Spring",					[0x42] = "SteamSpring",				[0x43] = "SlidingSpike",			[0x44] = "Bumper",
		[0x45] = "OOZSpring",				[0x46] = "OOZBall",					[0x47] = "Button",					[0x48] = "LauncherBall",
		[0x49] = "EHZWaterfall",			[0x4A] = "Octus",					[0x4B] = "Buzzer",					[0x50] = "Aquis",
		[0x51] = "CNZBoss",					[0x52] = "HTZBoss",					[0x53] = "MTZBossOrb",				[0x54] = "MTZBoss",
		[0x55] = "OOZBoss",					[0x56] = "EHZBoss",					[0x57] = "MCZBoss",					[0x58] = "BossExplosion",
		[0x59] = "SSEmerald",				[0x5A] = "SSMessage",				[0x5B] = "SSRingSpill",				[0x5C] = "Masher",
		[0x5D] = "CPZBoss",					[0x5E] = "SSHUD",					[0x5F] = "StartBanner",				[0x5F] = "EndingController",
		[0x60] = "SSRing",					[0x61] = "SSBomb",					[0x63] = "SSShadow",				[0x64] = "MTZTwinStompers",
		[0x65] = "MTZLongPlatform",			[0x66] = "MTZSpringWall",			[0x67] = "MTZSpinTube",				[0x68] = "SpikyBlock",
		[0x69] = "Nut",						[0x6A] = "MCZRotPforms",			[0x6A] = "MTZMovingPforms",			[0x6B] = "MTZPlatform",
		[0x6B] = "CPZSquarePform",			[0x6C] = "Conveyor",				[0x6D] = "FloorSpike",				[0x6E] = "LargeRotPform",
		[0x6F] = "SSResults",				[0x70] = "Cog",						[0x71] = "MTZLavaBubble",			[0x71] = "HPZBridgeStake",
		[0x71] = "PulsingOrb",				[0x72] = "CNZConveyorBelt",			[0x73] = "RotatingRings",			[0x74] = "InvisibleBlock",
		[0x75] = "MCZBrick",				[0x76] = "SlidingSpikes",			[0x77] = "MCZBridge",				[0x78] = "CPZStaircase",
		[0x79] = "Checkpoint",				[0x7A] = "SidewaysPform",			[0x7B] = "PipeExitSpring",			[0x7C] = "CPZPylon",
		[0x7E] = "SuperSonicStars",			[0x7F] = "VineSwitch",				[0x80] = "MovingVine",				[0x81] = "MCZDrawbridge",
		[0x82] = "SwingingPform",			[0x83] = "ARZRotPforms",			[0x84] = "ForcedSpin",				[0x84] = "PinballMode",
		[0x85] = "LauncherSpring",			[0x86] = "Flipper",					[0x87] = "SSNumberOfRings",			[0x88] = "SSTailsTails",
		[0x89] = "ARZBoss",					[0x8B] = "WFZPalSwitcher",			[0x8C] = "Whisp",					[0x8D] = "GrounderInWall",
		[0x8E] = "GrounderInWall2",			[0x8F] = "GrounderWall",			[0x90] = "GrounderRocks",			[0x91] = "ChopChop",
		[0x92] = "Spiker",					[0x93] = "SpikerDrill",				[0x94] = "Rexon",					[0x95] = "Sol",
		[0x96] = "Rexon2",					[0x97] = "RexonHead",				[0x98] = "Projectile",				[0x99] = "Nebula",
		[0x9A] = "Turtloid",				[0x9B] = "TurtloidRider",			[0x9C] = "BalkiryJet",				[0x9D] = "Coconuts",
		[0x9E] = "Crawlton",				[0x9F] = "Shellcracker",			[0xA0] = "ShellcrackerClaw",		[0xA1] = "Slicer",
		[0xA2] = "SlicerPincers",			[0xA3] = "Flasher",					[0xA4] = "Asteron",					[0xA5] = "Spiny",
		[0xA6] = "SpinyOnWall",				[0xA7] = "Grabber",					[0xA8] = "GrabberLegs",				[0xA9] = "GrabberBox",
		[0xAA] = "GrabberString",			[0xAC] = "Balkiry",					[0xAD] = "CluckerBase",				[0xAE] = "Clucker",
		[0xAF] = "MechaSonic",				[0xB0] = "SonicOnSegaScr",			[0xB1] = "SegaHideTM",				[0xB2] = "Tornado",
		[0xB3] = "Cloud",					[0xB4] = "VPropeller",				[0xB5] = "HPropeller",				[0xB6] = "TiltingPlatform",
		[0xB7] = "VerticalLaser",			[0xB8] = "WallTurret",				[0xB9] = "Laser",					[0xBA] = "WFZWheel",
		[0xBC] = "WFZShipFire",				[0xBD] = "SmallMetalPform",			[0xBE] = "LateralCannon",			[0xBF] = "WFZStick",
		[0xC0] = "SpeedLauncher",			[0xC1] = "BreakablePlating",		[0xC2] = "Rivet",					[0xC3] = "TornadoSmoke",
		[0xC4] = "TornadoSmoke2",			[0xC5] = "WFZBoss",					[0xC6] = "Eggman",					[0xC7] = "Eggrobo",
		[0xC8] = "Crawl",					[0xC9] = "TtlScrPalChanger",		[0xCA] = "CutScene",				[0xCB] = "EndingSeqClouds",
		[0xCC] = "EndingSeqTrigger",		[0xCD] = "EndingSeqBird",			[0xCE] = "EndingSeqSonic",			[0xCE] = "EndingSeqTails",
		[0xCF] = "TornadoHelixes",			[0xD2] = "CNZRectBlocks",			[0xD3] = "BombPrize",				[0xD4] = "CNZBigBlock",
		[0xD5] = "Elevator",				[0xD6] = "PointPokey",				[0xD7] = "HexBumper",				[0xD8] = "BonusBlock",
		[0xD9] = "Grab",					[0xDA] = "ContinueText",			[0xDA] = "ContinueIcons",			[0xDB] = "ContinueChars",
		[0xDC] = "RingPrize",
	}


	OBJECT_TRIGGER_FUNCTIONS = {};

		-- Springs
		OBJECT_TRIGGER_FUNCTIONS["Spring"] = function(current_object, object_base)
			if current_object.sub_id == 16 or current_object.sub_id == 18 then
				if current_object.flipped_x == 0 then
					-- Spring facing right
					if Player.xspeed >= 0 then
						current_object.trigger = true;
						current_object.trigger_left = 0;
						current_object.trigger_width = 40;
						current_object.trigger_top = -24;
						current_object.trigger_height = 48;
					end
				else
					-- Spring facing left
					if Player.xspeed <= 0 then
						current_object.trigger = true;
						current_object.trigger_left = -40;
						current_object.trigger_width = 40;
						current_object.trigger_top = -24;
						current_object.trigger_height = 48;
					end
				end
			end
		end

		-- Oil Ocean fans
		OBJECT_TRIGGER_FUNCTIONS["Fan"] = function(current_object, object_base)
			local fan_on = memory.readbyte(object_base + 0x32);
			if fan_on == 0 then
				if current_object.sub_id < 128 then
					if current_object.flipped_x == 1 then
						current_object.trigger = true;
						current_object.trigger_left = -80;
						current_object.trigger_width = 240;
						current_object.trigger_top = -96;
						current_object.trigger_height = 112;
					else
						current_object.trigger = true;
						current_object.trigger_left = 80;
						current_object.trigger_width = -240;
						current_object.trigger_top = -96;
						current_object.trigger_height = 112;
					end
				else
					local oscillating_data =  memory.readbyte(OFF_OSCILLATING_DATA + 0x14)
					current_object.trigger = true;
					current_object.trigger_left = -64;
					current_object.trigger_width = 128;
					current_object.trigger_top = (-96) - oscillating_data;
					current_object.trigger_height = 144;
				end
			end
		end

		-- Checkpoint
		OBJECT_TRIGGER_FUNCTIONS["Checkpoint"] = function(current_object, object_base)
			if current_object.sub_id > 0 then
				current_object.trigger = true;
				current_object.trigger_left = -8;
				current_object.trigger_width = 16;
				current_object.trigger_top = -64;
				current_object.trigger_height = 104;
			end
		end

		--Goal Plate
		OBJECT_TRIGGER_FUNCTIONS["GoalPlate"] = function(current_object, object_base)
			if memory.readbyte(OFF_UPDATE_TIMER) ~= 0 then
				current_object.trigger = true;
				current_object.trigger_left = 0;
				current_object.trigger_width = 32;
				current_object.trigger_top = -9999;
				current_object.trigger_height = 99999;
			end
		end

		-- Breathable Bubbles
		OBJECT_TRIGGER_FUNCTIONS["Bubbles"] = function(current_object, object_base)
			if memory.readbyte(object_base + 0x2E) ~= 0 then
				current_object.trigger = true;
				current_object.trigger_left = -16;
				current_object.trigger_width = 32;
				current_object.trigger_top = 0;
				current_object.trigger_height = 16;
			end
		end

		-- Oil Ocean Launcher Ball
		OBJECT_TRIGGER_FUNCTIONS["LauncherBall"] = function(current_object, object_base)
			current_object.trigger = true;
			current_object.trigger_left = -16;
			current_object.trigger_width = 32;
			current_object.trigger_top = -16;
			current_object.trigger_height = 32;
		end

		-- Hanging vine
		OBJECT_TRIGGER_FUNCTIONS["MovingVine"] = function(current_object, object_base)
			current_object.trigger = true;
			current_object.trigger_left = -16;
			current_object.trigger_width = 32;
			current_object.trigger_top = 136;
			current_object.trigger_height = 24;
		end

		-- Vine Switch
		OBJECT_TRIGGER_FUNCTIONS["VineSwitch"] = function(current_object, object_base)
			current_object.trigger = true;
			current_object.trigger_left = -12;
			current_object.trigger_width = 24;
			current_object.trigger_top = 40;
			current_object.trigger_height = 16;
		end

		-- Grab/hang
		OBJECT_TRIGGER_FUNCTIONS["Grab"] = function(current_object, object_base)
			current_object.trigger = true;
			current_object.trigger_left = -24;
			current_object.trigger_width = 48;
			current_object.trigger_top = 0;
			current_object.trigger_height = 16;
		end

		-- Chemical Plant Speed Booster
		OBJECT_TRIGGER_FUNCTIONS["SpeedBooster"] = function(current_object, object_base)
			current_object.trigger = true;
			current_object.trigger_left = -16;
			current_object.trigger_width = 32;
			current_object.trigger_top = -16;
			current_object.trigger_height = 32;
		end

		-- Metropolis Spin Tube
		OBJECT_TRIGGER_FUNCTIONS["MTZSpinTube"] = function(current_object, object_base)
			current_object.trigger = true;
			if current_object.flipped_x == 0 then
				current_object.trigger_left = -3;
				current_object.trigger_width = 16;
				current_object.trigger_top = -32;
				current_object.trigger_height = 64;
			else
				current_object.trigger_left = -13;
				current_object.trigger_width = 16;
				current_object.trigger_top = -32;
				current_object.trigger_height = 64;
			end
		end

		-- Layer switcher display
		OBJECT_TRIGGER_FUNCTIONS["LayerSwitcher"] = function(current_object, object_base)
			-- Layer switcher proporties
			local subtype = current_object.sub_id
			local bit_orientation = GetBit(current_object.sub_id, 2);
			local bit_path1 = GetBit(current_object.sub_id, 4);
			local bit_path2 = GetBit(current_object.sub_id, 3);
			local bit_grounded = GetBit(current_object.sub_id, 7);
			local bit_priority1 = GetBit(current_object.sub_id, 6);
			local bit_priority2 = GetBit(current_object.sub_id, 5);
			local bit_priority_only = current_object.flipped_x;

			local current_side = memory.readbyte(object_base + 0x34)

			-- Layers
			local first_layer = ProcessBooleanSpecific(bit_path1, "B", "A")
			local second_layer = ProcessBooleanSpecific(bit_path2, "B", "A")

			-- Priorities
			local first_priority = ProcessBooleanSpecific(bit_priority1, "H", "L")
			local second_priority = ProcessBooleanSpecific(bit_priority2, "H", "L")

			-- Grounded
			local grounded_only = ProcessBooleanSpecific(bit_grounded, "G", "");

			-- Visual size
			local visual_size = 999;

			-- Create trigger
			if bit_orientation == 0 then
				-- Vertical Size
				subtype = AND(subtype, 7)
				subtype = AND(subtype, 3)
				local size_index = subtype --+ subtype;
				local address = 0x1FD68 + size_index * 2;
				local size = memory.readword(address) * 2;

				-- Area
				current_object.trigger = true;
				if current_side == 0 then
					current_object.trigger_left = 0;
					current_object.trigger_width = 999;
				else
					current_object.trigger_left = -999;
					current_object.trigger_width = 999;
				end
				current_object.trigger_top = -size / 2;
				current_object.trigger_height = size;

				-- Line
				current_object.trigger_line = true;
				current_object.trigger_line_x1 = 0;
				current_object.trigger_line_y1 = -size / 2;
				current_object.trigger_line_x2 = 0;
				current_object.trigger_line_y2 = size / 2;
				current_object.trigger_line_colour = COLOUR_LAYERSWITCHLINE;

				-- Label
				if bit_priority_only == 1 then
					-- Priority Only
					current_object.trigger_text = first_priority .. "  " .. second_priority .. "\n\n" .. grounded_only;
					current_object.trigger_text_x = -7;
					current_object.trigger_text_y = -3;
				else
					-- Layer And Priority
					current_object.trigger_text = first_layer .. "  " .. second_layer .. "\n\n" .. first_priority .. "  " .. second_priority .. "\n\n" .. grounded_only;
					current_object.trigger_text_x = -7;
					current_object.trigger_text_y = -10;
				end
			else
				-- Horizontal Size
				subtype = AND(subtype, 3)
				local size_index = subtype --+ subtype;
				local address = 0x1FD68 + size_index * 2;
				local size = memory.readword(address) * 2;

				-- Area
				current_object.trigger = true;
				current_object.trigger_left = -size / 2;
				current_object.trigger_width = size;
				if current_side == 0 then
					current_object.trigger_top = 0;
					current_object.trigger_height = 999;
				else
					current_object.trigger_top = -999;
					current_object.trigger_height = 999;
				end

				-- Line
				current_object.trigger_line = true;
				current_object.trigger_line_x1 = -size / 2;
				current_object.trigger_line_y1 = 0;
				current_object.trigger_line_x2 = size / 2;
				current_object.trigger_line_y2 = 0;
				current_object.trigger_line_colour = COLOUR_LAYERSWITCHLINE;

				-- Label
				if bit_priority_only == 1 then
					-- Priority Only
					current_object.trigger_text = first_priority .. "\n\n" .. second_priority .. "\n\n" .. grounded_only;
					current_object.trigger_text_x = -3;
					current_object.trigger_text_y = -11;
				else
					-- Layer And Priority
					current_object.trigger_text = first_layer .. "  " .. first_priority .. "\n\n" .. second_layer .. "  " .. second_priority .. "\n\n" .. grounded_only
					current_object.trigger_text_x = -7;
					current_object.trigger_text_y = -11;
				end
			end

			-- Colour
			current_object.trigger_colour = COLOUR_LAYERSWITCH;
		end

----------------------------------
--- Names Of All Sonic 2 Zones ---
----------------------------------
	ZONE_NAMES = {
		[0x0] = "Emerald Hill Zone",
		[0x1] = "Unused",
		[0x2] = "Wood Zone",
		[0x3] = "Unused",
		[0x4] = "Metropolis Zone acts 1 and 2",
		[0x5] = "Metropolis Zone acts 3 and 4",
		[0x6] = "Wing Fortress Zone",
		[0x7] = "Hill Top Zone",
		[0x8] = "Hidden Palace",
		[0x9] = "Unused",
		[0xA] = "Oil Ocean Zone",
		[0xB] = "Mystic Cave Zone",
		[0xC] = "Casino Night Zone",
		[0xD] = "Chemical Plant Zone",
		[0xE] = "Death Egg Zone ",
		[0xF] = "Aquatic Ruin Zone",
		[0x10] = "Sky Chase Zone"
	};

----------------------
--- Math Functions ---
----------------------

    --------------------------------------------------------------------------------------
    -- GetRegisterByte(reg)
    --------------------------------------------------------------------------------------
    -- Get the value in a register as a byte.
    --------------------------------------------------------------------------------------
    function GetRegisterByte(reg)
        return AND(memory.getregister(reg), 0xFF)
    end


    --------------------------------------------------------------------------------------
    -- GetRegisterWord(reg)
    --------------------------------------------------------------------------------------
    -- Get the value in a register as a word.
    --------------------------------------------------------------------------------------
    function GetRegisterWord(reg)
        return AND(memory.getregister(reg), 0xFFFF)
    end

    --------------------------------------------------------------------------------------
    -- GetRegisterAddress(reg)
    --------------------------------------------------------------------------------------
    -- Get the value in a register as a valid address (0x000000 - 0xFFFFFF).
    --------------------------------------------------------------------------------------
    function GetRegisterAddress(reg)
        return AND(memory.getregister(reg), 0xFFFFFF)
    end

	--------------------------------------------------------------------------------------
	-- GetBit(n, k)
	--------------------------------------------------------------------------------------
	-- Get specific bit from a value.
	--------------------------------------------------------------------------------------
	function GetBit(n, k)
		local mask = bit.lshift(1, k);
		local masked_n = bit.band(n, mask);
		return bit.rshift(masked_n, k);
	end

	--------------------------------------------------------------------------------------
	-- Round(num, numDecimalPlaces)
	--------------------------------------------------------------------------------------
	-- Round a value to an integer.
	--------------------------------------------------------------------------------------
	function Round(num, numDecimalPlaces)
		local mult = 10^(numDecimalPlaces or 0);
		return math.floor(num * mult + 0.5) / mult;
	end


----------------------
----Data Functions----
----------------------

	--------------------------------------------------------------------------------------
	-- TableShallowCopy(num)
	--------------------------------------------------------------------------------------
	-- Copies surface of table or value
	--------------------------------------------------------------------------------------
	function TableShallowCopy(orig)
		local orig_type = type(orig)
		local copy
		if orig_type == 'table' then
			copy = {}
			for orig_key, orig_value in pairs(orig) do
				copy[orig_key] = orig_value
			end
		else -- number, string, boolean, etc
			copy = orig
		end
		return copy
	end

------------------------
----String Functions----
------------------------

	--------------------------------------------------------------------------------------
	-- ProcessPreciseWord(value)
	--------------------------------------------------------------------------------------
	-- Converts precise decimal to hex word and sub pixel component string
	--------------------------------------------------------------------------------------
	function ProcessPreciseWord(value)

		local str = tostring(value)
		if ControlGetState(ControlHexValues) then
			local whole = math.floor(value)

			if whole == value then
				str = "$"..string.format("%04X", AND(whole, 0xFFFF));
			else
				local fractional = math.floor((value - whole) * 256);
				local whole_hex = string.format("%04X", AND(whole, 0xFFFF));
				local fractional_hex = string.format("%02X", fractional);
				str = "$"..whole_hex .. "-" .. fractional_hex
			end
		end
		return str
	end

	--------------------------------------------------------------------------------------
	-- ProcessWord(value)
	--------------------------------------------------------------------------------------
	-- Converts decimal to hex word string
	--------------------------------------------------------------------------------------
	function ProcessWord(value)
		local str = tostring(value)
		if ControlGetState(ControlHexValues) then
			str = "$"..string.format("%04X", AND(math.floor(value), 0xFFFF));
		end
		return str
	end

	--------------------------------------------------------------------------------------
	-- ProcessByte(value)
	--------------------------------------------------------------------------------------
	-- Converts decimal to hex byte string
	--------------------------------------------------------------------------------------
	function ProcessByte(value)
		local str = tostring(value)
		if ControlGetState(ControlHexValues) then
			str = "$"..string.format("%02X", AND(math.floor(value), 0xFF));
		end
		return str
	end

	--------------------------------------------------------------------------------------
	-- ProcessByteConsistent(value)
	--------------------------------------------------------------------------------------
	-- Converts decimal to hex byte string, keeping decimal to 2 digits also
	--------------------------------------------------------------------------------------
	function ProcessByteConsistent(value)
		local str = string.format("%02d", value)
		if ControlGetState(ControlHexValues) then
			str = "$"..string.format("%02X", AND(math.floor(value), 0xFF));
		end
		return str
	end

	--------------------------------------------------------------------------------------
	-- ProcessBooleanSpecific(value, true, false)
	--------------------------------------------------------------------------------------
	-- Converts boolean to specified string
	--------------------------------------------------------------------------------------
	function ProcessBooleanSpecific(value, t, f)
		if value > 0 or value == true then
			return t;
		end
		return f;
	end

	--------------------------------------------------------------------------------------
	-- ProcessBoolean(value)
	--------------------------------------------------------------------------------------
	-- Converts boolean to string "True" or "False"
	--------------------------------------------------------------------------------------
	function ProcessBoolean(value)
		return ProcessBooleanSpecific(value, "True", "False")
	end


	--------------------------------------------------------------------------------------
	-- YWrap(value)
	--------------------------------------------------------------------------------------
	-- Wraps a y position on the Y axis taking the camera view into account
	--------------------------------------------------------------------------------------
	function YWrap(value)
		local stage_height = 2048;
		local camera_height = 224;
		local y_pos = value % stage_height;
		local cam_y = Camera.y % stage_height;

		-- Process further when camera is hovering over the seam of the wrap
		if cam_y > (stage_height - camera_height) then
			-- If the position is supposed to be at the top of the stage, "move" it below the bottom of the seam
			if y_pos < (stage_height / 2) then
				y_pos = y_pos + stage_height;
			end
		end

		return y_pos - cam_y;
	end

----------------------
----Draw Functions----
----------------------

	--------------------------------------------------------------------------------------
	-- GameBox(x, y, w, h, col, outline)
	--------------------------------------------------------------------------------------
	-- Draw a box using width and height radiuses to be accurate to the game.
	--------------------------------------------------------------------------------------
	function GameBox(x, y, w, h, col, outline)
		gui.box(x - w - 1, y - h - 1,
			x + w + 1, y + h + 1, col, outline);
	end

	--------------------------------------------------------------------------------------
	-- DrawObjectPosition(ob_x, ob_y)
	--------------------------------------------------------------------------------------
	-- Draw a stylised position for objects.
	--------------------------------------------------------------------------------------
	function DrawObjectPosition(ob_x, ob_y)
		gui.line(ob_x - 1, ob_y, ob_x + 1, ob_y, COLOUR_WHITE);
		gui.line(ob_x, ob_y - 1, ob_x, ob_y + 1, COLOUR_WHITE);
		gui.pixel(ob_x, ob_y, COLOUR_BLACK);
	end

	--------------------------------------------------------------------------------------
	-- DrawObjectSensor(ob_x, ob_y, sensor, smooth, line)
	--------------------------------------------------------------------------------------
	-- Draw a stylised sensor for objects.
	--------------------------------------------------------------------------------------
	function DrawObjectSensor(ob_x, ob_y, sensor, smooth, line)
		-- Get sensor position based on smoothing
		local sensor_x, sensor_y;
		if smooth then
			sensor_x = ob_x + sensor.x_rel;
			sensor_y = ob_y + sensor.y_rel;
		else
			sensor_x = sensor.x - Camera.x;
			sensor_y = YWrap(sensor.y);
		end

		-- Line from object position outwards
		if line then
			if sensor.orientation == VERTICAL then
				if sensor.flipped == false then
					gui.line(sensor_x, ob_y, sensor_x, sensor_y, COLOUR_SENSOR.DOWN);
				else
					gui.line(sensor_x, ob_y, sensor_x, sensor_y, COLOUR_SENSOR.UP);
				end
			else
				if sensor.flipped == false then
					gui.line(ob_x, sensor_y, sensor_x, sensor_y, COLOUR_SENSOR.RIGHT);
				else
					gui.line(ob_x, sensor_y, sensor_x, sensor_y, COLOUR_SENSOR.LEFT);
				end
			end
		end

		-- Anchor point
		gui.pixel(sensor_x, sensor_y, COLOUR_SENSOR.ANCHOR);
	end

---------------------------
--- Solidity Structures ---
---------------------------
	HitboxesTable = {};
	SensorsTable = {};
	SolidsTable = {};
	WalkingEdgesTable = {};
	SlopesTable = {};

	--RingTable = {};
	--BumperTable = {};

	SENSORBUFFER = {}
	SENSORBUFFER.A = nil;
	SENSORBUFFER.B = nil;
	SENSORBUFFER.C = nil;
	SENSORBUFFER.D = nil;
	SENSORBUFFER.E = nil;
	SENSORBUFFER.F = nil;

--------------------------
--- Solidity Functions ---
--------------------------

	--------------------------------------------------------------------------------------
	-- LoadObjectSensor(orientation)
	--------------------------------------------------------------------------------------
	-- Load sensor information from data register.
	--------------------------------------------------------------------------------------
	function LoadObjectSensor(orientation)
		-- Collect all sensor information
		local sensor_x = GetRegisterWord("d3");
		local sensor_y = GetRegisterWord("d2");
		local sensor_direction = memory.getregister("a3");
		local flipped = sensor_direction < 0;

		-- Collect information about the object casting the sensor
		local object = GetRegisterAddress("a0");
		local object_x = memory.readword(object + 0x08);
		local object_y = memory.readword(object + 0x0c);

		-- Correct flipped sensors
		-- Objects have erratic flipped sensors and you'll still see this, this is accurate and only a fix to the base game would correct this.
		if (flipped) then
			if orientation == HORIZONTAL then
				sensor_x = XOR(sensor_x, 0xF);
			else
				sensor_y = XOR(sensor_y, 0xF);
			end
		end

		-- Collect all information in a table
		local sensor_info = {}
		sensor_info.x = sensor_x;
		sensor_info.y = sensor_y;
		sensor_info.x_rel = sensor_x-object_x;
		sensor_info.y_rel = sensor_y-object_y;
		sensor_info.orientation = orientation;
		sensor_info.flipped = flipped;

		-- Submit
		SubmitObjectSolidity(sensor_info, SensorsTable, object - OFF_OBJECTS);
	end

	--------------------------------------------------------------------------------------
	-- LoadObjectSlope()
	--------------------------------------------------------------------------------------
	-- Load object slope information from data register.
	--------------------------------------------------------------------------------------
	function LoadObjectSlope(push_radius, slope_type)
		-- Collect information about collision
		local slope_array = GetRegisterAddress("a2");
		local box_width =  GetRegisterWord("d1");
		local box_height = GetRegisterWord("d2");

		-- Collect information about the object casting the sensor
		local object = GetRegisterAddress("a0");
		local object_x = memory.readword(object + 0x08);
		local object_y = memory.readword(object + 0x0c);

		-- Collect all information in a table
		local slope_info = {}
		slope_info.x = object_x;
		slope_info.y = object_y;
		slope_info.size = box_width;
		slope_info.height = box_height;
		local array = {};
		for i = 1, slope_info.size, 1 do
			array[i] = memory.readbytesigned(slope_array + (i - 1));
		end
		slope_info.data = array;
		slope_info.offset = push_radius;
		slope_info.type = slope_type;

		-- Submit
		SubmitObjectSolidity(slope_info, SlopesTable, object - OFF_OBJECTS);
	end

	--------------------------------------------------------------------------------------
	-- SubmitObjectSolidity()
	--------------------------------------------------------------------------------------
	-- Submits a specified type of solidity information to a specified object.
	--------------------------------------------------------------------------------------
	function SubmitObjectSolidity(data, solidtype, object)
		-- Assign solid to object
		local current_object = solidtype[object];
		if current_object == nil then
			solidtype[object] = {[1] = data};
		else
			current_object[#current_object + 1] = data;
		end
	end

	--------------------------------------------------------------------------------------
	-- LoadSensorDistance()
	--------------------------------------------------------------------------------------
	-- Load object sensor distance information from data register.
	--------------------------------------------------------------------------------------
	function LoadSensorDistance()
		-- Collect information about sensor distance
		local distance = GetRegisterByte("d1");
		if distance >= 128 then
			distance = distance - 256;
		end
		return distance;
	end

-------------------------------------------------------------
--- Read solidity from data registers as code is executed ---
-------------------------------------------------------------
-- Here, the game code is read as it happens, collecting information about every sensor and solid object execution dynamically.

	-- Record vertical sensor
	memory.registerexec(EX_VERTICAL_SENSOR, function()
		LoadObjectSensor(VERTICAL);
	end);

	-- Record horizontal sensor
	memory.registerexec(EX_HORIZONTAL_SENSOR, function()
		LoadObjectSensor(HORIZONTAL);
	end);

	-- Record player hitbox
	memory.registerexec(EX_PLAYER_HITBOX, function()
		if ControlGetState(ControlHitboxes) then
			-- Collect information about hitbox
			local box_left = GetRegisterWord("d2");
			local box_top = GetRegisterWord("d3");
			local hitbox_width = GetRegisterWord("d4") / 2;
			local hitbox_height = GetRegisterWord("d5") / 2;

			-- Collect information about the object hitbox being checked
			local object = GetRegisterAddress("a0") - OFF_OBJECTS;
			local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
			local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

			-- Collect all information in a table
			local hitbox_info = {}
			hitbox_info.x = box_left + hitbox_width;
			hitbox_info.y = box_top + hitbox_height;
			hitbox_info.x_rel = (box_left + hitbox_width) - object_x;
			hitbox_info.y_rel = (box_top + hitbox_height) - object_y;
			hitbox_info.width = hitbox_width;
			hitbox_info.height = hitbox_height;

			-- Submit
			SubmitObjectSolidity(hitbox_info, HitboxesTable, object);
		end
	end);

	-- Record player boss hitbox
	memory.registerexec(EX_PLAYER_BOSS_HITBOX, function()
		if ControlGetState(ControlHitboxes) then
			-- Collect information about hitbox
			local box_left = GetRegisterWord("d2");
			local box_top = GetRegisterWord("d3");
			local hitbox_width = GetRegisterWord("d4") / 2;
			local hitbox_height = GetRegisterWord("d5") / 2;

			-- Collect information about the object hitbox being checked
			local object = GetRegisterAddress("a0") - OFF_OBJECTS;
			local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
			local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

			-- Collect all information in a table
			local hitbox_info = {}
			hitbox_info.x = box_left + hitbox_width;
			hitbox_info.y = box_top + hitbox_height;
			hitbox_info.x_rel = (box_left + hitbox_width) - object_x;
			hitbox_info.y_rel = (box_top + hitbox_height) - object_y;
			hitbox_info.width = hitbox_width;
			hitbox_info.height = hitbox_height;

			-- Submit
			SubmitObjectSolidity(hitbox_info, HitboxesTable, object);
		end
	end);

	-- Record object hitbox
	memory.registerexec(EX_OBJECT_HITBOX, function()
		if ControlGetState(ControlHitboxes) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a0") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about the object hitbox being checked
				local object = GetRegisterAddress("a1") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Get the hitbox size being used by object
				local hitbox_data = memory.readbyte(OFF_OBJECTS + object + 0x20);
				local touch_index = AND(hitbox_data, 0x3F); -- Get index of hitbox size array
				local touch_response = bit.rshift(AND(hitbox_data, 0xC0), 6); -- Get type of response
				local address = 0x3F600 + (touch_index * 2); -- Get size at index
				local hitbox_width = memory.readbyte(address);
				local hitbox_height = memory.readbyte(address + 1);

				-- Collect all information in a table
				local hitbox_info = {}
				hitbox_info.x = object_x;
				hitbox_info.y = object_y;
				hitbox_info.x_rel = 0;
				hitbox_info.y_rel = 0;
				hitbox_info.width = hitbox_width;
				hitbox_info.height = hitbox_height;
				hitbox_info.response = touch_response;

				-- Submit
				SubmitObjectSolidity(hitbox_info, HitboxesTable, object);
			end
		end
	end);

	-- Record boss hitbox
	memory.registerexec(EX_BOSS_HITBOX, function()
		if ControlGetState(ControlHitboxes) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a0") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about the object hitbox being checked
				local object = GetRegisterAddress("a1") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Get the hitbox size being used by object
				local hitbox_data = memory.readbyte(OFF_OBJECTS + object + 0x20);
				local touch_index = AND(hitbox_data, 0x3F); -- Get index of hitbox size array
				local touch_response = bit.rshift(AND(hitbox_data, 0xC0), 6); -- Get type of response
				local address = 0x3F600 + (touch_index * 2); -- Get size at index
				local hitbox_width = memory.readbyte(address);
				local hitbox_height = memory.readbyte(address + 1);

				-- Collect all information in a table
				local hitbox_info = {}
				hitbox_info.x = object_x;
				hitbox_info.y = object_y;
				hitbox_info.x_rel = 0;
				hitbox_info.y_rel = 0;
				hitbox_info.width = hitbox_width;
				hitbox_info.height = hitbox_height;
				hitbox_info.response = touch_response;

				-- Submit
				SubmitObjectSolidity(hitbox_info, HitboxesTable, object);
			end
		end
	end);


	-- Record solid object boxes
	memory.registerexec(EX_SOLID_OBJECT2, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x = GetRegisterWord("d4");
				local box_width = GetRegisterWord("d1") - 11; -- We subtract 11 here, objects add 11 which is the Player's push radius + 1 to make the code more efficient, but for visual purposes we don't need to see that.
				local box_height = GetRegisterWord("d2"); -- This is the height used for when sonic is not standing on the object, and the object is searching for him. When Sonic is standing on the object, it uses a seperate radius which is usually 1px bigger or the exact same.
				-- This is all because of a bug in the code that keeps sonic standing on objects which DOESN'T add 1 when it's meant to. While the main SolidObject routine does not have 1 subtracted, subtracting 1 here provides the best visual for standing on the object.

				-- Sometimes these sizes can differ from the width and height radius used, so these sizes here will override any other when viewed in this overlay. Non-solid objects like a badnik will show it's Width and Height Radius as normal.

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local box_info = {};
				box_info.x = box_x;
				box_info.y = object_y;
				box_info.x_rel = box_x - object_x;
				box_info.y_rel = 0;
				box_info.width = box_width;
				box_info.height = box_height;

				-- Submit
				SubmitObjectSolidity(box_info, SolidsTable, object);
			end
		end
	end);


	-- Record solid object boxes
	memory.registerexec(EX_SOLID_OBJECT2, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x = GetRegisterWord("d4");
				local box_width = GetRegisterWord("d1") - 11; -- We subtract 11 here, objects add 11 which is the Player's push radius + 1 to make the code more efficient, but for visual purposes we don't need to see that.
				local box_height = GetRegisterWord("d2"); -- This is the height used for when sonic is not standing on the object, and the object is searching for him. When Sonic is standing on the object, it uses a seperate radius which is usually 1px bigger or the exact same.
				-- This is all because of a bug in the code that keeps sonic standing on objects which DOESN'T add 1 when it's meant to. While the main SolidObject routine does not have 1 subtracted, subtracting 1 here provides the best visual for standing on the object.

				-- Sometimes these sizes can differ from the width and height radius used, so these sizes here will override any other when viewed in this overlay. Non-solid objects like a badnik will show it's Width and Height Radius as normal.

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local box_info = {};
				box_info.x = box_x;
				box_info.y = object_y;
				box_info.x_rel = box_x - object_x;
				box_info.y_rel = 0;
				box_info.width = box_width;
				box_info.height = box_height;
				box_info.type = "Solid";

				-- Submit
				SubmitObjectSolidity(box_info, SolidsTable, object);
			end
		end
	end);

	-- Record solid wall boxes
	memory.registerexec(EX_SOLID_WALL, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_width = memory.getregister("d1") - 11;
				local box_height = memory.getregister("d2");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local box_info = {};
				box_info.x = object_x;
				box_info.y = object_y;
				box_info.x_rel = 0;
				box_info.y_rel = 0;
				box_info.width = box_width;
				box_info.height = box_height;
				box_info.type = "Solid";

				-- Submit
				SubmitObjectSolidity(box_info, SolidsTable, object);
			end
		end
	end);

	-- Record platform collision
	memory.registerexec(EX_PLATFORM, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_width = GetRegisterWord("d1");
				local box_y = memory.getregister("d3"); -- Y Offset of platform, Sonic 1 does not have this
				local box_y_offset = -box_y + 8;
				-- If y offset is negative, it shifts the platform down.
				-- Platform y radius remains 8 like in Sonic 1

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				platform_info = {};
				platform_info.x = object_x;
				platform_info.y = object_y + box_y_offset;
				platform_info.x_rel = 0;
				platform_info.y_rel = box_y_offset;
				platform_info.width = box_width;
				platform_info.height = 8;
				platform_info.type = "Platform";

				-- Submit
				SubmitObjectSolidity(platform_info, SolidsTable, object);
			end
		end
	end);

	-- Record size of the area the Player's position can walk on
	memory.registerexec(EX_PLATFORM_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x_off = memory.getregister("d1");
				local box_width = memory.getregister("d2");
				local box_height = memory.getregister("d3");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local edges_info = {}
				edges_info.x = object_x;
				edges_info.y = object_y;
				edges_info.x_offset = box_x_off;
				edges_info.width = box_width;
				edges_info.type = "Platform";

				-- Submit
				SubmitObjectSolidity(edges_info, WalkingEdgesTable, object);
			end
		end
	end);

	-- Record platform collision
	memory.registerexec(EX_PLATFORM2, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_width = GetRegisterWord("d1");
				local box_y = memory.getregister("d3"); -- Y Offset of platform, Sonic 1 does not have this
				local box_y_offset = -box_y + 8;
				-- If y offset is negative, it shifts the platform down.
				-- Platform y radius remains 8 like in Sonic 1

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				platform_info = {};
				platform_info.x = object_x;
				platform_info.y = object_y + box_y_offset;
				platform_info.x_rel = 0;
				platform_info.y_rel = box_y_offset;
				platform_info.width = box_width;
				platform_info.height = box_height;
				platform_info.type = "Platform";

				-- Submit
				SubmitObjectSolidity(platform_info, SolidsTable, object);
			end
		end
	end);

	-- Record size of area the Player's position can walk on
	memory.registerexec(EX_PLATFORM2_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x = memory.getregister("d4");
				local box_width = memory.getregister("d1");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local edges_info = {}
				edges_info.x = box_x;
				edges_info.y = object_y;
				edges_info.x_offset = box_width;
				edges_info.width = box_width;

				-- Submit
				SubmitObjectSolidity(edges_info, WalkingEdgesTable, object);
			end
		end
	end);

	-- Record size of area the Player's position can walk on
	memory.registerexec(EX_SOLID_OBJECT_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x = memory.getregister("d4");
				local box_width = memory.getregister("d1");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local edges_info = {}
				edges_info.x = box_x;
				edges_info.y = object_y;
				edges_info.x_offset = box_width;
				edges_info.width = box_width;

				-- Submit
				SubmitObjectSolidity(edges_info, WalkingEdgesTable, object);
			end
		end
	end);

	-- Record size of area the Player's position can walk on
	memory.registerexec(EX_SOLID_OBJECT_EXIT2, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x = memory.getregister("d4");
				local box_width = memory.getregister("d1");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local edges_info = {}
				edges_info.x = box_x;
				edges_info.y = object_y;
				edges_info.x_offset = box_width;
				edges_info.width = box_width;

				-- Submit
				SubmitObjectSolidity(edges_info, WalkingEdgesTable, object);
			end
		end
	end);

	-- Record item montitor solidity
	--[[memory.registerexec(EX_MONITOR_SOLID, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_width = memory.getregister("d1") - 11;
				local box_height = memory.getregister("d2");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				box_info = {};
				box_info.x = object_x;
				box_info.y = object_y;
				box_info.x_rel = 0;
				box_info.y_rel = 0;
				box_info.width = box_width;
				box_info.height = box_height;
				box_info.type = "Solid";

				-- Submit
				SubmitObjectSolidity(box_info, SolidsTable, object);
			end
		end
	end);]]

	memory.registerexec(EX_MONITOR_SOLID_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x = memory.getregister("d4");
				local box_width = memory.getregister("d1");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local edges_info = {}
				edges_info.x = box_x;
				edges_info.y = object_y;
				edges_info.x_offset = box_width;
				edges_info.width = box_width;

				-- Submit
				SubmitObjectSolidity(edges_info, WalkingEdgesTable, object);
			end
		end
	end);

	-- Record swing platform solidity
	memory.registerexec(EX_SWING_PLATFORM, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_width = memory.getregister("d1");
				local box_height = 8;

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				box_info = {};
				box_info.x = object_x;
				box_info.y = object_y;
				box_info.x_rel = 0;
				box_info.y_rel = 0;
				box_info.width = box_width;
				box_info.height = box_height;
				box_info.type = "Platform";

				-- Submit
				SubmitObjectSolidity(box_info, SolidsTable, object);
			end
		end
	end);

	-- Record size of the area the Player's position can walk on
	memory.registerexec(EX_SWING_PLATFORM_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x = memory.getregister("d4");
				local box_width = memory.getregister("d1");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local edges_info = {}
				edges_info.x = box_x;
				edges_info.y = object_y;
				edges_info.x_offset = box_width;
				edges_info.width = box_width;

				-- Submit
				SubmitObjectSolidity(edges_info, WalkingEdgesTable, object);
			end
		end
	end);

	-- Record bridge collision
	memory.registerexec(EX_BRIDGE_PLATFORM, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_width = GetRegisterWord("d2") / 2;
				local box_height = 8;

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0");
				local object_x = memory.readword(object + 0x08);
				local object_y = memory.readword(object + 0x0c);

				-- Collect all information in a table
				platform_info = {};
				platform_info.x = object_x - 8; --Bridge code does it's own unique x check which adjusts for the offset of the bridge log doing the check
				platform_info.y = object_y;
				platform_info.x_rel = - 8;
				platform_info.y_rel = 0;
				platform_info.width = box_width;
				platform_info.height = box_height;
				platform_info.type = "Platform";

				-- Submit
				SubmitObjectSolidity(platform_info, SolidsTable, object - OFF_OBJECTS);
			end
		end
	end);


	-- Record size of the area the Player's position can walk on
	memory.registerexec(EX_BRIDGE_PLATFORM_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				-- Collect information about collision
				local box_x_off = memory.getregister("d1");
				local box_width = memory.getregister("d2") / 2;
				local box_height = memory.getregister("d3");

				-- Collect information about the object acting solid
				local object = GetRegisterAddress("a0") - OFF_OBJECTS;
				local object_x = memory.readword(OFF_OBJECTS + object + 0x08);
				local object_y = memory.readword(OFF_OBJECTS + object + 0x0c);

				-- Collect all information in a table
				local edges_info = {}
				edges_info.x = object_x;
				edges_info.y = object_y;
				edges_info.x_offset = box_x_off;
				edges_info.width = box_width;
				edges_info.type = "Platform";

				-- Submit
				SubmitObjectSolidity(edges_info, WalkingEdgesTable, object);
			end
		end
	end);



	-- Record object slope
	memory.registerexec(EX_SLOPED_PLATFORM, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				LoadObjectSlope(0, "Platform");
			end
		end
	end);
	memory.registerexec(EX_SLOPED_PLATFORM_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				LoadObjectSlope(0, "Stand");
			end
		end
	end);

	memory.registerexec(EX_SLOPED_SOLID_OBJECT_EXIT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				LoadObjectSlope(0, "Stand");
			end
		end
	end);

	memory.registerexec(EX_SLOPED_SOLID_OBJECT, function()
		if ControlGetState(ControlSolidity) then
			-- Only draw collision for main character
			local context_object = GetRegisterAddress("a1") - OFF_OBJECTS;
			if context_object == 0 then
				LoadObjectSlope(11, "Solid");
			end
		end
	end);

	-- Record player floor sensor distances while grounded
	memory.registerexec(EX_PLAYER_SENSOR_A, function()
		SENSORBUFFER.A = LoadSensorDistance();
	end);
	memory.registerexec(EX_PLAYER_SENSOR_B, function()
		SENSORBUFFER.B = LoadSensorDistance();
	end);

	memory.registerexec(EX_PLAYER_SENSOR_A_R, function()
		SENSORBUFFER.A = LoadSensorDistance();
	end);
	memory.registerexec(EX_PLAYER_SENSOR_B_R, function()
		SENSORBUFFER.B = LoadSensorDistance();
	end);

	memory.registerexec(EX_PLAYER_SENSOR_A_C, function()
		SENSORBUFFER.A = LoadSensorDistance();
	end);
	memory.registerexec(EX_PLAYER_SENSOR_B_C, function()
		SENSORBUFFER.B = LoadSensorDistance();
	end);

	memory.registerexec(EX_PLAYER_SENSOR_A_L, function()
		SENSORBUFFER.A = LoadSensorDistance();
	end);
	memory.registerexec(EX_PLAYER_SENSOR_B_L, function()
		SENSORBUFFER.B = LoadSensorDistance();
	end);

	-- Record player wall sensor distances
	memory.registerexec(EX_PLAYER_SENSOR_E, function()
		SENSORBUFFER.E = LoadSensorDistance();
	end);
	memory.registerexec(EX_PLAYER_SENSOR_F, function()
		SENSORBUFFER.F = LoadSensorDistance();
	end);

	-- Record player floor sensor distances while airborne
	memory.registerexec(EX_PLAYER_SENSOR_A_AIR, function()
		SENSORBUFFER.A = LoadSensorDistance();
	end);
	memory.registerexec(EX_PLAYER_SENSOR_B_AIR, function()
		SENSORBUFFER.B = LoadSensorDistance();
	end);

	-- Record player ceiling sensor distances while airborne
	memory.registerexec(EX_PLAYER_SENSOR_C, function()
		SENSORBUFFER.C = LoadSensorDistance();
	end);

	memory.registerexec(EX_PLAYER_SENSOR_D, function()
		SENSORBUFFER.D = LoadSensorDistance();
	end);


-------------------
--- Camera Data ---
-------------------

	Camera = {}

	--------------------------------------------------------------------------------------
	-- LoadCamera()
	--------------------------------------------------------------------------------------
	-- Load all information about the camera scrolling.
	--------------------------------------------------------------------------------------
	function LoadCamera()
		Camera.x = memory.readword(OFF_CAMERA);
		Camera.y = memory.readword(OFF_CAMERA + 0x04);
	end

	LoadCamera();

-------------------
--- Player Data ---
-------------------

	Player = {}

	--------------------------------------------------------------------------------------
	-- LoadPlayer()
	--------------------------------------------------------------------------------------
	-- Load all information about the player object.
	--------------------------------------------------------------------------------------
	function LoadPlayer()
		-- Position
		Player.x = memory.readword(OFF_PLAYER + 0x08);
		Player.y = memory.readword(OFF_PLAYER + 0x0c);
		Player.x_subpixel = memory.readbyte(OFF_PLAYER + 0x0a);
		Player.y_subpixel = memory.readbyte(OFF_PLAYER + 0x0e);
		Player.x_precise = Player.x + (Player.x_subpixel / 256);
		Player.y_precise = Player.y + (Player.y_subpixel / 256);
		Player.screen_x = Player.x - Camera.x;
		Player.screen_y = YWrap(Player.y);

		-- Angle
		Player.angle_previous = Player.angle;
		if Player.angle_previous == nil then
			Player.angle_previous = 0;
		end
		Player.angle = memory.readbyte(OFF_PLAYER + 0x26);

		-- Sizes
		Player.radius_push = 10;
		Player.radius_x = memory.readbytesigned(OFF_PLAYER + 0x17);
		Player.radius_y = memory.readbytesigned(OFF_PLAYER + 0x16);

		-- X and y speeds
		Player.xspeed = memory.readbytesigned(OFF_PLAYER + 0x10);
		Player.xspeed_subpixel = memory.readbyte(OFF_PLAYER + 0x11);
		Player.xspeed = Player.xspeed + (Player.xspeed_subpixel / 256)
		Player.yspeed = memory.readbytesigned(OFF_PLAYER + 0x12);
		Player.yspeed_subpixel = memory.readbyte(OFF_PLAYER + 0x13);
		Player.yspeed = Player.yspeed + (Player.yspeed_subpixel / 256)

		-- Groundspeed
		Player.groundspeed = memory.readbytesigned(OFF_PLAYER + 0x14);
		Player.groundspeed_subpixel = memory.readbyte(OFF_PLAYER + 0x15);
		Player.groundspeed = Player.groundspeed + (Player.groundspeed_subpixel / 256);

		-- Control Lock
		Player.controllock = memory.readword(OFF_PLAYER + 0x2E);
		Player.sticktoconvex = memory.readbyte(OFF_PLAYER + 0x38);

		-- Status
		Player.status = memory.readbyte(OFF_PLAYER + 0x22);
		Player.facingleft = GetBit(Player.status, 0);
		Player.inair = GetBit(Player.status, 1);
		Player.spinning = GetBit(Player.status, 2);
		Player.onobject = GetBit(Player.status, 3);
		Player.rolljumping = GetBit(Player.status, 4);
		Player.pushing = GetBit(Player.status, 5);
		Player.underwater = GetBit(Player.status, 6);

		-- Layer
		Player.layer = (memory.readbyte(OFF_PLAYER + 0x3E) - 12) / 2; --0x0E0F or 0x0C0D becomes 0 or 1

		-- Layer
		Player.gfx = memory.readword(OFF_PLAYER + 0x02);
		Player.gfx_priority = GetBit(Player.gfx, 15)

		-- Frame
		Player.animation = memory.readbyte(OFF_PLAYER + 0x1C);
		Player.animation_frame_display = memory.readbyte(OFF_PLAYER + 0x1A);
		Player.animation_frame = memory.readbyte(OFF_PLAYER + 0x1B);
		Player.animation_frame_timer_previous = Player.animation_frame_timer;
		Player.animation_frame_timer = memory.readbyte(OFF_PLAYER + 0x1E);
		if Player.animation_frame_duration == nil then Player.animation_frame_duration = 0; end
		if Player.animation_frame_timer_previous ~= nil then
			if Player.animation_frame_timer > Player.animation_frame_timer_previous then
				Player.animation_frame_duration = Player.animation_frame_timer;
			end
		end

		-- Sensors
		Player.sensor_a = SENSORBUFFER.A;
		Player.sensor_b = SENSORBUFFER.B;
		Player.sensor_c = SENSORBUFFER.C;
		Player.sensor_d = SENSORBUFFER.D;
		Player.sensor_e = SENSORBUFFER.E;
		Player.sensor_f = SENSORBUFFER.F;

		-- Get player mode
		-- No mode variable is stored ingame, so we use the same calculation used for floor sensors (slightly different ranges are used when deciding wall sensor mode)
		-- Based on previous frame's angle because that is the angle used for collision this frame, before the angle is changed
		local ang = 255 - Player.angle_previous;
		Player.mode = 0;
		if Player.inair == 0 then
			if ang >= 1 and ang <= 32 then
				Player.mode = 0;
			elseif ang >= 33 and ang <= 95 then
				Player.mode = 1;
			elseif ang >= 96 and ang <= 160 then
				Player.mode = 2;
			elseif ang >= 161 and ang <= 223 then
				Player.mode = 3;
			elseif ang >= 224 and ang <= 255 then
				Player.mode = 0;
			end
		end
	end

	LoadPlayer()

-------------------
--- Object Data ---
-------------------

	Objects = {}

	--------------------------------------------------------------------------------------
	-- LoadObjects()
	--------------------------------------------------------------------------------------
	-- Load all information about all player objects.
	--------------------------------------------------------------------------------------
	function LoadObjects()

		-- Loop through currently available objects
		for i = 1, 128, 1 do
			-- Ram position
			local object_base = OFF_OBJECTS + (SIZE_OBJECT * (i - 1));

			-- Populate object information
			local current_object = {}

			-- Base
			current_object.index = SIZE_OBJECT * (i - 1);
			current_object.base = object_base;

			-- Id
			current_object.id = memory.readbyte(object_base + 0x00);
			current_object.sub_id = memory.readbyte(object_base + 0x28);

			-- Name
			local name = OBJECT_NAMES[current_object.id]
			if name == nil then
				current_object.name = "";
			else
				current_object.name = name;
			end

			-- Position
			current_object.x = memory.readword(object_base + 0x08);
			current_object.y = memory.readword(object_base + 0x0C);
			current_object.screen_x = current_object.x - Camera.x;
			current_object.screen_y = YWrap(current_object.y);

			-- Size
			current_object.width_radius = memory.readbyte(object_base + 0x17);
			current_object.height_radius = memory.readbyte(object_base + 0x16);
			if current_object.width_radius == 0 then
				current_object.width_radius = memory.readbyte(object_base + 0x19); --use secondary width if width radius isnt being used
			end

			-- Triggers
			current_object.trigger = false;
			current_object.trigger_left = 0;
			current_object.trigger_width = 0;
			current_object.trigger_top = 0;
			current_object.trigger_height = 0;
			current_object.trigger_colour = COLOUR_TRIGGER;

			current_object.trigger_text = "";
			current_object.trigger_text_x = 0;
			current_object.trigger_text_y = 0;

			current_object.trigger_line = false;
			current_object.trigger_line_x1 = 0;
			current_object.trigger_line_y1 = 0;
			current_object.trigger_line_x2 = 0;
			current_object.trigger_line_y2 = 0;
			current_object.trigger_line_colour = COLOUR_TRIGGER;

			-- Collision Type
			current_object.collision_type = memory.readbyte(object_base + 0x20);

			-- Graphics
			current_object.gfx = memory.readword(object_base + 0x02);
			current_object.ani_frame = memory.readbyte(object_base + 0x1a);

			current_object.gfx_priority = GetBit(current_object.gfx, 15)

			current_object.render = memory.readbyte(object_base + 0x01);
			current_object.flipped_x = GetBit(current_object.render, 0);
			current_object.flipped_y = GetBit(current_object.render, 1);

			-- Add information which isn't baked into the object proporties or doesnt use established processes (hard coded ranges aka Triggers)
			local trigger_function = OBJECT_TRIGGER_FUNCTIONS[current_object.name]
			if trigger_function ~= null then
				trigger_function(current_object, object_base);
			end

			-- Find hitboxes belonging to this object
			local current_hitboxes = HitboxesTable[current_object.base - OFF_OBJECTS];
			if current_hitboxes ~= nil then
				current_object.hitboxes = current_hitboxes;
			else
				current_object.hitboxes = nil;
			end

			-- Find sensors belonging to this object
			local current_sensors = SensorsTable[current_object.base - OFF_OBJECTS];
			if current_sensors ~= nil then
				current_object.sensors = current_sensors;
			else
				current_object.sensors = nil;
			end

			-- Find solids belonging to this object
			local current_solids = SolidsTable[current_object.base - OFF_OBJECTS];
			if current_solids ~= nil then
				current_object.solids = current_solids;
			else
				current_object.solids = nil;
			end

			-- Find platform exits belonging to this object
			local current_walkedges = WalkingEdgesTable[current_object.base - OFF_OBJECTS];
			if current_walkedges ~= nil then
				current_object.walking_edges = current_walkedges;
			else
				current_object.walking_edges = nil;
			end

			-- Find slopes belonging to this object
			local current_slopes = SlopesTable[current_object.base - OFF_OBJECTS];
			if current_slopes ~= nil then
				current_object.slopes = current_slopes;
			else
				current_object.slopes = nil;
			end

			-- Add to objects
			Objects[i] = current_object;
		end
	end

	LoadObjects();

------------------------------------------
--- Build data for solid tiles display ---
------------------------------------------
	-- author: Mercury

	local solidImages = {
		[0] = { -- no solid
			[0] = {}; -- no flip
			[1] = {}; -- x flip
			[2] = {}; -- y flip
			[3] = {}; -- x and y flip
		};
		[1] = { -- top solid
			[0] = {}; -- no flip
			[1] = {}; -- x flip
			[2] = {}; -- y flip
			[3] = {}; -- x and y flip
		};
		[2] = { -- sides solid
			[0] = {}; -- no flip
			[1] = {}; -- x flip
			[2] = {}; -- y flip
			[3] = {}; -- x and y flip
		};
		[3] = { -- all solid
			[0] = {}; -- no flip
			[1] = {}; -- x flip
			[2] = {}; -- y flip
			[3] = {}; -- x and y flip
		};
	}

	local solidAngles = {}

	local SHOW_LAYER = true

	do -- build solid images
		local header = string.char(0xFF, 0xFE, 0x00, 0x10, 0x00, 0x10, 0x01, 0xFF, 0xFF, 0xFF, 0xFF); -- GD truecolor 16x16 image header
		local filled = string.char(0x00,  COLOUR_TILES.FULL[1], COLOUR_TILES.FULL[2], COLOUR_TILES.FULL[3]) -- GD ARGB
		local empty = string.char(0x7F, 0x00, 0x00, 0x00); -- GD ARGB transparent black

		local function flipSolid(t, flipX, flipY)
			if flipX then
				local flip = { header; }
				for row = 2, 242, 16 do
					for pos = 0, 15 do
						flip[row + 15 - pos] = t[row + pos];
					end
				end
				t = flip;
			end
			if flipY then
				local flip = { header; }
				for row = 2, 242, 16 do
					for pos = 0, 15 do
						flip[pos + 244 - row] = t[pos + row];
					end
				end
				t = flip;
			end
			return t;
		end

		local function shadeSolid(t, col)
			local shade = string.char(0x00, col[1], col[2], col[3])
			local newSolid = { header; }
			for i = 2, #t do
				if t[i] == empty then
					newSolid[i] = empty
				else
					newSolid[i] = shade
				end
			end
			return newSolid
		end

		local address = 0x42E50; -- Solidity\Vertical
		local address_angle = 0x42D50; -- Angles

		-- for number of solids
		for i = 0, 0xFF do
			local t = { header; } -- table to be concatenated into GD string

			for x = 0, 0xF do
				local top = 2 + x;
				local bottom = 242 + x;
				local h = memory.readbytesigned(address);
				address = address + 1;

				if h > 0 then
					local split = bottom - h * 16
					for i = top, bottom, 16 do
						t[i] = i > split and filled or empty;
					end
				elseif h < 0 then
					local split = top - h * 16
					for i = top, bottom, 16 do
						t[i] = i < split and filled or empty;
					end
				else
					for i = top, bottom, 16 do
						t[i] = empty;
					end
				end
			end

			do
				local t_x = flipSolid(t, true, false)
				local t_y = flipSolid(t, false, true)
				local t_xy = flipSolid(t, true, true)

				solidImages[0][0][i] = table.concat(shadeSolid(t, COLOUR_TILES.NONE))
				solidImages[0][1][i] = table.concat(shadeSolid(t_x, COLOUR_TILES.NONE));
				solidImages[0][2][i] = table.concat(shadeSolid(t_y, COLOUR_TILES.NONE));
				solidImages[0][3][i] = table.concat(shadeSolid(t_xy, COLOUR_TILES.NONE));

				solidImages[1][0][i] = table.concat(shadeSolid(t, COLOUR_TILES.TOP))
				solidImages[1][1][i] = table.concat(shadeSolid(t_x, COLOUR_TILES.TOP));
				solidImages[1][2][i] = table.concat(shadeSolid(t_y, COLOUR_TILES.TOP));
				solidImages[1][3][i] = table.concat(shadeSolid(t_xy, COLOUR_TILES.TOP));

				solidImages[2][0][i] = table.concat(shadeSolid(t, COLOUR_TILES.SIDES))
				solidImages[2][1][i] = table.concat(shadeSolid(t_x, COLOUR_TILES.SIDES));
				solidImages[2][2][i] = table.concat(shadeSolid(t_y, COLOUR_TILES.SIDES));
				solidImages[2][3][i] = table.concat(shadeSolid(t_xy, COLOUR_TILES.SIDES));

				solidImages[3][0][i] = table.concat(t)
				solidImages[3][1][i] = table.concat(t_x);
				solidImages[3][2][i] = table.concat(t_y);
				solidImages[3][3][i] = table.concat(t_xy);
			end

			solidAngles[i] = memory.readbyte(address_angle + i);
		end
	end

	-- draws set of all 255 solids on screen for debugging purposes
	local function drawSolidSet()
		local i = 0
		for y = 0, 224, 16 do
			for x = 0, 320, 16 do
				gui.image(x, y, solidImages[0][0][i], 0.5);
				i = i + 1
				if i == 255 then
					return;
				end
			end
		end
	end

	-- draws `chunk` at `x, y` (screen coordinates)
	local function drawChunk(x, y, chunk)
		-- do
			-- gui.box(x, y, x + 0x80, y + 0x80, 0x00000000, 0xFFFFFFFF)
			-- gui.text(x + 2, y + 2, string.format("%02X", chunk), 0xFFFFFFFF, 0x000000FF)
			-- return
		-- end

		local address = 0xFF0000 + chunk * 0x80;
		local collision_index_address = SHOW_LAYER and 0xFFD900 or 0xFFD600;
		local collision_index_address_alt = SHOW_LAYER and 0xFFD600 or 0xFFD900;
		local collision_shift_amount = SHOW_LAYER and 14 or 12;
		local collision_shift_amount_alt = SHOW_LAYER and 12 or 14;

		for o = 0, 7, 1 do
			for i = 0, 7, 1 do
				local tx = x + (i * 16);
				local ty = y + (o * 16);

				if ty > -16 and ty < 224 and tx > -16 and tx < 320 then -- only draw blocks in view
					local block = memory.readword(address);
					if AND(block, 0xF000) > 0 then -- if block is flagged as solid on either layer
						local coll = AND(SHIFT(block, collision_shift_amount), 3);
						local coll_alt = AND(SHIFT(block, collision_shift_amount_alt), 3);
						local flip = SHIFT(AND(block, 0xC00), 10);

						-- Checkerboard opacity
						local opacity = OPACITY_TILES[XOR(i, o) % 2 == 0 and 1 or 2];
						local solid = memory.readbyte(collision_index_address + AND(block, 0x3FF));
						local solid_alt = memory.readbyte(collision_index_address_alt + AND(block, 0x3FF));

						-- Solids on opposite layer
						if ControlGetState(ControlTerrainLayers) ~= "Current" then
							if solid_alt > 0 and (solid_alt ~= solid or coll_alt ~= coll) then
								-- Draw tile on opposite layer
								local opposite_opacity = opacity * 0.5;
								if ControlGetState(ControlTerrainLayers) == "Both" then
									opposite_opacity = opacity;
								end
								gui.image(tx, ty, solidImages[coll_alt][flip][solid_alt], opposite_opacity);
							end
						end

						if coll > 0 and solid > 0 then
							-- Draw tile
							gui.image(tx, ty, solidImages[coll][flip][solid], opacity);
						end

						if ControlGetState(ControlTerrainLayers) == "Both" or (coll > 0 and solid > 0) then
							-- Drawing the angle
							if ControlGetState(ControlTerrain) ~= "None" and ControlGetState(ControlTerrain) ~= "Plain" then
								local display_angle = ""
								local display_col = COLOUR_TEXT;

								local angle = solidAngles[solid]
								if angle ~= 0xFF then
									-- Modify angle based on flipped
									if flip == 1 then
										-- Flipped X
										angle = 256 - angle;
									elseif flip == 2 then
										-- Flipped Y
										angle = (128 + (256 - angle)) % 256;
									elseif flip == 3 then
										-- Flipped Both
										angle = (angle + 128) % 256;
									end

									-- Display angle string
									if ControlGetState(ControlTerrain) == "Real" then
										-- Decimal
										display_angle = ProcessByteConsistent(angle);
									elseif ControlGetState(ControlTerrain) == "Degrees" then
										-- Degrees
										display_angle = tostring(Round((256-angle) * (360 / 256), 0));
									end
								else
									display_angle = "*";
									display_col = COLOUR_TILE_FLAG;
								end

								-- Draw angle text
								local w = math.floor((string.len(display_angle) * 4) /2)
								local h = math.ceil(7 /2)
								gui.text((tx+8)-w, (ty+8)-h, display_angle, display_col, "black")
							end
						end
					end
				end
				address = address + 2;
			end
		end
	end

	-- draws all chunks in view starting from `left, top` (zone coodinates)
	local function drawTerrain(left, top)
		if memory.readbyte(OFF_GAMEMODE) ~= 0x0C then
			return
		end

		SHOW_LAYER = memory.readword(0xFFB03E) ~= 0x0C0D;

		--drawSolidSet()

		local right = left + 320
		local bottom = top + 224

		local size = 0x80 -- size of a chunk (x or y)

		local l = left - (left % size)
		local t = top - (top % size)
		local r = right - (right % size)
		local b = bottom - (bottom % size)

		for y = t, b, size do
			for x = l, r, size do
				local lx = x / size
				local ly = y / size % 16

				local chunk = memory.readbyte(0xFF8000 + lx + ly * 0x100)

				if chunk > 0 then
					drawChunk(x - left, y - top, chunk)
				end
			end
		end
	end

-----------------------
--- Update and Draw ---
-----------------------

	-- We draw upon execution of VBlank
	memory.registerexec(EX_VBLANK, function()

	--------------
	--- Checks ---
	--------------

		-- Are we in a zone?
		if memory.readbyte(OFF_GAMEMODE) ~= 0x0C then
			return
		end

		-- New frame?
		GameTimerPrevious = GameTimer;
		GameTimer = memory.readword(OFF_GAME_TIMER);

	-------------
	--- Input ---
	-------------
		InputUpdate()

	----------------
	--- Controls ---
	----------------

		-- Process Controls
		for i, control in ipairs(OVERLAY_CONTROLS) do
			if INPUT_PRESS[control.shortcut] then
				ControlChange(i)
			end
		end

	------------
	--- Draw ---
	------------
		if ControlGetState(ControlShowOverlay) then

		--------------
		--- Darken ---
		--------------
			if ControlGetState(ControlDarkening) > 0 then
				gui.box(-1, -1, 320, 224, {0, 0, 0, (ControlGetState(ControlDarkening) / 100) * 255}, COLOUR_NONE);
			end

		--------------------------------
		--- Loop through ring layout ---
		--------------------------------

			-- Static rings
			if ControlGetState(ControlHitboxes) then
				for address = 0xFFE800, 0xFFEE00 - 6, 6 do
					local collected = memory.readword(address);
					local ring_x = memory.readword(address + 2) - Camera.x;
					local ring_y = YWrap(memory.readword(address + 4))

					if collected == 0 then
						GameBox(ring_x, ring_y, 6, 6, COLOUR_HITBOX.INCREMENT, COLOUR_NONE)
					end
				end
			end

		--------------------------------------
		--- Loop through CNZ bumper layout ---
		--------------------------------------
			-- CNZ Bumpers
			if ControlGetState(ControlHitboxes) then
				-- Ensure we are in CNZ
				if ZONE_NAMES[CurrentZone] == "Casino Night Zone" then

					-- Current act data offset
					local offset;
					if CurrentAct == 0 then
						offset = 0x1781A
					else
						offset = 0x1795E
					end

					for address = offset, (offset + 324) - 6, 6 do
						local type = memory.readword(address);
						local bumper_x = memory.readword(address + 2) - Camera.x;
						local bumper_y = YWrap(memory.readword(address + 4));

						local size_offset = AND(type, 0xE)
						local size_x =  memory.readbyte(0x17558 + size_offset)
						local size_y =  memory.readbyte(0x17558 + size_offset + 1)

						--corner 64x64
						--0000 (0x0)  bottom right
						--0010 (0x2)  bottom left

						--wide floor/celing 128 wide
						--0100 (0x4)  floor
						--0110 (0x6)  ceiling

						--wide wall one tall
						--1000 (0x8)  right side
						--1010 (0xA (10))  left side

						GameBox(bumper_x, bumper_y, size_x, size_y, COLOUR_NONE, COLOUR_TRIGGER)
					end
				end
			end
		----------------------------
		--- Loop through objects ---
		----------------------------
			for i = 1, 128, 1 do
				local object = Objects[i]

				-- Draw object
				if object.id ~= 0 then

					-- Size
					if ControlGetState(ControlSize) then
						if object.width_radius > 0 and object.height_radius > 0 then
							if DRAW_PLAYER_PUSH_RADIUS and object.id == 1 then
								GameBox(object.screen_x, object.screen_y, 10, object.height_radius, COLOUR_SIZE, COLOUR_NONE);
							else
								GameBox(object.screen_x, object.screen_y, object.width_radius, object.height_radius, COLOUR_SIZE, COLOUR_NONE);
							end
						end
					end

					-- Hitboxes
					if ControlGetState(ControlHitboxes) and object.hitboxes ~= nil  then
						for i, hitbox in ipairs(object.hitboxes) do
							-- Type of hitbox
							local col = COLOUR_HITBOX.PLAYER;
							if hitbox.response == 0 then
								col = COLOUR_HITBOX.BADNIK;
							elseif hitbox.response == 1 then
								col = COLOUR_HITBOX.INCREMENT;
							elseif hitbox.response == 2 then
								col = COLOUR_HITBOX.HURT;
							elseif hitbox.response == 3 then
								col = COLOUR_HITBOX.SPECIAL;
							end

							if ControlGetState(ControlSmoothing) then
								GameBox(object.screen_x + hitbox.x_rel, object.screen_y + hitbox.y_rel, hitbox.width, hitbox.height, col, COLOUR_NONE);
							else
								GameBox(hitbox.x - Camera.x, YWrap(hitbox.y), hitbox.width, hitbox.height, col, COLOUR_NONE);
							end
						end
					end

					-- Trigger
					if ControlGetState(ControlTriggers) and object.trigger then
						gui.box(object.screen_x + object.trigger_left - 1,
							object.screen_y + object.trigger_top - 1,
							object.screen_x + object.trigger_left + object.trigger_width,
							object.screen_y + object.trigger_top + object.trigger_height,
							object.trigger_colour, COLOUR_NONE);

						if object.trigger_line then
							gui.line(object.screen_x + object.trigger_line_x1,
								object.screen_y + object.trigger_line_y1,
								object.screen_x + object.trigger_line_x2,
								object.screen_y + object.trigger_line_y2,
								object.trigger_line_colour)
						end

						if object.screen_x > 0 and object.screen_x < 320 and object.screen_y > 0 and object.screen_y < 224 then
							gui.text(object.screen_x + object.trigger_text_x, object.screen_y + object.trigger_text_y, object.trigger_text)
						end
					end

					-- Sensors
					if ControlGetState(ControlSensors) and object.sensors ~= nil then
						for i, sensor in ipairs(object.sensors) do
							DrawObjectSensor(object.screen_x, object.screen_y, sensor, ControlGetState(ControlSmoothing), 1);
						end
					end

					-- Solids
					if ControlGetState(ControlSolidity) then
						-- Normal Solids
						if object.solids ~= nil then
							for i, solid in ipairs(object.solids) do
								local col = COLOUR_SOLID;
								if solid.type == "Platform" then
									col = COLOUR_PLATFORM;
								end

								if SMOOTHED_MOTION then
									GameBox(object.screen_x + solid.x_rel, object.screen_y + solid.y_rel, solid.width, solid.height, col, COLOUR_NONE);
								else
									GameBox(solid.x - Camera.x, YWrap(solid.y), solid.width, solid.height, col, COLOUR_NONE);
								end
							end
						end


						-- Walking edges
						if object.walking_edges ~= nil then
							for i, solid in ipairs(object.walking_edges) do
								local current_walkedges = object.walking_edges[i];

								local obj_x = current_walkedges.x - Camera.x;
								local obj_y = YWrap(current_walkedges.y);
								if SMOOTHED_MOTION then
									obj_x = object.screen_x;
									obj_y = object.screen_y;
								end

								local x1 = obj_x - current_walkedges.x_offset;
								local x2 = x1 + (current_walkedges.width * 2) - 1;
								gui.line(x1, Player.screen_y, x2, Player.screen_y, COLOUR_PLATFORM_EDGES);
							end
						end

						-- Slopes
						if object.slopes ~= nil then
							for i, current_slope in ipairs(object.slopes) do
								local size = current_slope.size; -- X radius of sloped object, size of slope data by coincidence because slope data compressed

								local obj_x = current_slope.x - Camera.x;
								local obj_y = YWrap(current_slope.y);
								if ControlGetState(ControlSmoothing) then
									obj_x = object.screen_x;
									obj_y = object.screen_y;
								end

								-- What kind of slope to draw
								local col = COLOUR_PLATFORM;
								local thickness = 0;
								local y_offset = -Player.radius_y
								if current_slope.type == "Platform" then
									thickness = 16;
									y_offset = 0;
								elseif current_slope.type == "Solid" then
									thickness = current_slope.height * 2;
									y_offset = 0;
									col = COLOUR_SOLID;
								end
								obj_y = obj_y + y_offset;

								-- Left to right or right to left
								local slope_start = obj_x - size;
								local slope_end = obj_x + size - 2;
								local slope_step = 2
								if object.flipped_x == 1 then
									slope_start = obj_x + size - 2;
									slope_end = obj_x - size;
									slope_step = -2;
								end

								-- Loop through x positions starting from right side of object to left
								local o = 1;
								for x = slope_start, slope_end, slope_step do
									if x - (obj_x - size) >= current_slope.offset
										and (obj_x + size) - x >= current_slope.offset then
										gui.line(x, obj_y - current_slope.data[o], x, obj_y - current_slope.data[o] + thickness, col);
									end
									if (x + 1) - (obj_x - size) >= current_slope.offset
										and (obj_x + size) - (x + 1) >= current_slope.offset then
										gui.line(x + 1, obj_y - current_slope.data[o], x + 1, obj_y - current_slope.data[o] + thickness, col);
									end
									o = o + 1; -- increment slope data position
								end
							end
						end
					end

					-- Details
					if ControlGetState(ControlInfo) then
						if object.screen_x > object.width_radius and object.screen_x - object.width_radius < 320 - string.len(object.name) * 5 then
							-- Get maximal sizes
							local large_width = math.max(8, object.width_radius);
							local large_height = math.max(8, object.height_radius);

							-- Id, sub id, graphics, and animation frame
							local y_offset = 8;
							gui.text(object.screen_x - large_width, object.screen_y - large_height - 8, ProcessByte(object.id) .. ", " .. ProcessByte(object.sub_id) .. ", " .. ProcessByte(object.gfx) .. ", " .. ProcessByte(object.ani_frame));
							y_offset = 16;

							-- Name
							gui.text(object.screen_x - large_width, object.screen_y - large_height - y_offset, object.name);
						end
					end

					-- Position
					DrawObjectPosition(object.screen_x, object.screen_y);
				end
			end

		--------------------
		--- Draw Terrain ---
		--------------------
			if ControlGetState(ControlTerrain) ~= "None" then
				drawTerrain(Camera.x, Camera.y);
			end

		--------------------------
		--- Draw Camera Bounds ---
		--------------------------
			if ControlGetState(ControlCameraBounds) then
				local x_centre = 160;
				local y_centre = 112 - 16;
				local x_min = 160 - 16;

				local draw_bottom = false;
				draw_col = "white";

				local ani = PLAYER_ANIMATION_NAMES[Player.animation]
				if ani == "Roll" or ani == "Roll Fast" then
					draw_bottom = true;
					draw_col = "magenta";
				end

				if draw_bottom then
					gui.line(x_min, y_centre, x_centre, y_centre, draw_col)
					gui.box(x_min, y_centre - 32, x_centre, y_centre + 32, COLOUR_NONE, draw_col)

					gui.line(x_min, y_centre + 5, x_centre, y_centre + 5, "white")
					gui.box(x_min, y_centre + 5 - 32, x_centre, y_centre + 5 + 32, COLOUR_NONE, "white")
				else
					gui.line(x_min, y_centre, x_centre, y_centre, draw_col)
					gui.box(x_min, y_centre - 32, x_centre, y_centre + 32, COLOUR_NONE, draw_col)
				end
			end

		----------------------
		--- Draw Variables ---
		----------------------
			if ControlGetState(ControlPlayerVariables) then
				-- Text display values
				gui.box(5, 2, 90, 224-5, {0, 0, 0, 200}, COLOUR_NONE)

				-- Display string
				local display_text = DISPLAY_TEXT_TEMPLATE:format(
						ProcessPreciseWord(Player.x_precise)
					, ProcessPreciseWord(Player.y_precise)
					, ProcessPreciseWord(Player.xspeed)
					, ProcessPreciseWord(Player.yspeed)
					, ProcessPreciseWord(Player.groundspeed)
					, ProcessByte(Player.angle) , Round((256-Player.angle) * (360 / 256), 2) % 360
					, MODE_NAMES[Player.mode]

					, ProcessBoolean(1 - Player.inair)
					, ProcessBoolean(Player.onobject)
					, ProcessBooleanSpecific(Player.facingleft, "Left", "Right")
					, ProcessBoolean(Player.pushing)
					, ProcessByte(Player.controllock)
					, ProcessBoolean(Player.sticktoconvex)
					, ProcessBooleanSpecific(Player.layer, "B", "A")
					, ProcessBooleanSpecific(Player.gfx_priority, "H", "L")

					, PLAYER_ANIMATION_NAMES[0][Player.animation], ProcessByte(Player.animation)
					, ProcessByte(Player.animation_frame_display), ProcessByte(Player.animation_frame)
					, ProcessByte(Player.animation_frame_duration), ProcessByte(Player.animation_frame_duration + 1) -- Add 1 because the timer includes 0, so the true duration is actually 1 longer than the ingame value
					, ProcessByte(Player.animation_frame_timer)
				)

				gui.text(10, 5, display_text, COLOUR_TEXT, COLOUR_BLACK);

				-- Draw sensors
				local ty = 74 - 8 - 5;
				local spacing = 8;
				local str = "";
				if Player.sensor_a == nil then str = "--" else str = ProcessByteConsistent(Player.sensor_a) end
				gui.text(10, ty, "A: " .. str, COLOUR_SENSOR.A, COLOUR_BLACK)
				if Player.sensor_b == nil then str = "--" else str = ProcessByteConsistent(Player.sensor_b) end
				gui.text(40, ty, "B: " .. str, COLOUR_SENSOR.B, COLOUR_BLACK) ty = ty + spacing;
				if Player.sensor_c == nil then str = "--" else str = ProcessByteConsistent(Player.sensor_c) end
				gui.text(10, ty, "C: " .. str, COLOUR_SENSOR.C, COLOUR_BLACK)
				if Player.sensor_d == nil then str = "--" else str = ProcessByteConsistent(Player.sensor_d) end
				gui.text(40, ty, "D: " .. str, COLOUR_SENSOR.D, COLOUR_BLACK) ty = ty + spacing;
				if Player.sensor_e == nil then str = "--" else str = ProcessByteConsistent(Player.sensor_e) end
				gui.text(10, ty, "E: " .. str, COLOUR_SENSOR.E, COLOUR_BLACK)
				if Player.sensor_f == nil then str = "--" else str = ProcessByteConsistent(Player.sensor_f) end
				gui.text(40, ty, "F: " .. str, COLOUR_SENSOR.F, COLOUR_BLACK) ty = ty + spacing;
			end
		end

	---------------------
	--- Load all data ---
	---------------------
	-- This loads the data collected from code executions into the objects which are then drawn.
	-- We refresh the data AFTER drawing it because the overlay syncs with the game when the overlay is drawn one frame in the past

		if GamePaused == 0 and GameTimer ~= GameTimerPrevious then
			LoadCamera();
			LoadPlayer();
			LoadObjects();
		end
		GamePaused = memory.readword(OFF_PAUSED);

		CurrentZone = memory.readbyte(0xFFFFFE10);
		CurrentAct = memory.readbyte(0xFFFFFE11);

	----------------------
	--- Clear old data ---
	----------------------
		if  GameTimer ~= GameTimerPrevious then
			HitboxesTable = {};
			SensorsTable = {};
			SolidsTable = {};
			WalkingEdgesTable = {};
			SlopesTable = {};

			--RingTable = {};
			--BumperTable = {};

			SENSORBUFFER.A = nil;
			SENSORBUFFER.B = nil;
			SENSORBUFFER.C = nil;
			SENSORBUFFER.D = nil;
			SENSORBUFFER.E = nil;
			SENSORBUFFER.F = nil;
		end

	----------------------------
	--- Draw Control Prompts ---
	----------------------------

		-- Process Controlsw
		if ControlGetState(ControlShowShortcuts) then
			local ty = 0;

			for i, control in ipairs(OVERLAY_CONTROLS) do
				gui.text(320 - control.width - 40, ty, control.label, control.colour, COLOUR_BLACKw);
				gui.text(320, ty, tostring(ControlGetState(i)), control.colour, COLOUR_BLACK);
				ty = ty + control.height;
			end
		else
			gui.text(320, 0, "Press " .. ControlGetShortcut(ControlShowShortcuts) .. " for Shortcuts", COLOUR_WHITE, COLOUR_BLACK);
		end
	end);

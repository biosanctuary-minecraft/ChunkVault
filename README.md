# ChunkVault

**ChunkVault records the world you explore on a multiplayer server into a normal singleplayer save.** Walk around, open chests, track your progress on a live minimap, plan your scan area, search saved storage, and play the result later — with or without the mod installed.

&lt;img width=&quot;1280&quot; height=&quot;720&quot; alt=&quot;photo_2026-11-20_22-16-41&quot; src=&quot;https://www.google.com/search?q=https://github.com/user-attachments/assets/4463d06c-e1ee-4297-8127-b97cf0e865bd&quot; /&gt;

## Features

- **Chunk recording**: every chunk your client loads is written into a vanilla world save, including terrain, blocks, and block entities. Modified chunks are tracked and re-saved on unload so builds and broken blocks are never lost when flying away, and stopping a recording flushes everything asynchronously without freezing your game.
- **Storage contents & Ender Chests**: open any container while recording and its contents are saved, with a chat notification showing what was stored and where. Supported: chests, trapped chests, barrels, shulker boxes, hoppers, droppers & dispensers, furnaces, blast furnaces & smokers, brewing stands, crafters, and **Ender Chests** (both tracked as map/database storage and exported to your singleplayer `EnderItems` when enabled).
- **Latest state wins & auto-cleanup**: re-opening a container updates its saved contents, and broken or replaced containers are automatically removed from the database when their chunk is revisited.
- **Container Database & Slot Inspector**: search all saved containers by item name, ID, or container title — including double chests, Ender Chests, and items nested inside shulker boxes. Filter by dimension, category, and opened/unopened status; sort by newest, oldest, nearest, farthest, fullness, or name; inspect every slot, copy coordinates, or jump straight to the container on the map.
- **Live In-Game Minimap HUD**:
  - Smooth bilinear-filtered terrain and subpixel-smooth container pins, live recording perimeter, and planned area outline.
  - Drag anywhere on screen, resize (`64–260 px`), and adjust zoom (`0.25x–4x`) in the interactive layout editor.
  - Toggleable Info Bar positioned **below** or **above** the minimap with 10 selectable stats: recording status, chunks, coordinates, project name, live project disk size, containers, entities, area progress %, dimension, and biome.
- **Interactive full-screen map**:
  - **Container pins & live filter**: color-coded pins for containers (including all 17 real shulker box colors and teal Ender Chests) and optional markers for unopened containers. Filter pins directly on the map by search query, opened/unopened status, 9 container types, and individual shulker colors (LMB to toggle, RMB to solo a color, plus All/None) — saved between sessions.
  - **Smooth zoom & controls**: animated zoom anchored to cursor, 3-tier region texture LOD, on-screen **`+` / `−`** buttons, **Follow Player** toggle, and RMB recenter.
  - **Live recording zone**: real-time perimeter outline matching the server's effective loaded chunk area.
- **Area Planner & Progress HUD**:
  - Drag on the map or enter exact coordinates / radius (up to ±30,000,000 blocks) to plan a recording zone.
  - **Clip to area** (ignore chunks & containers outside the zone), **Trim** (delete already-recorded chunks and containers outside the zone), **Dim outside**, and **Auto-stop** when 100% complete.
  - **In-game HUD**: shows recorded/remaining chunks, percentage, directional arrow & distance to the nearest missing chunk, plus live **ETA & movement speed**.
- **Mobs and entities**: entities inside recorded chunks are snapshotted every second and merged by UUID across recording sessions so villagers and mobs are preserved without duplicates (toggleable in Settings).
- **Safe Menu Input Guard**: blocks accidental movement or dismounts (such as hitting Shift on a mount) while ChunkVault menus are open, while still letting you walk and look around with the map open.
- **Projects**: create a new project (**New File**), continue an existing one (**Open**), copy finished worlds straight into your `saves` folder, or safely delete old projects with 2-step confirmation.
- **Tabbed Settings & Hotkeys**: organized into **Recording**, **World**, **Minimap**, and **Hotkeys** tabs — per-server auto-record, container filter, player inventory & Ender Chest export, mob spawning toggle, void or seed-based generation, full minimap customization, and 8 rebindable keybindings synced with Minecraft Controls (`C + V` by default to open/close the map).
- **Languages**: English and Russian, selected automatically from your game language.

## How to use

1. Join a server or world.
2. Press `C + V` (or run `/chunkvault map`), click **NEW FILE** to create a project, then click **RECORD** (or run `/chunkvault start <name>`).
3. *(Optional)* Click **AREA** to drag a target zone on the map or enter exact coordinates, and toggle **Clip**, **HUD**, or **Auto-stop**.
4. Explore and open any containers (including your Ender Chest) you want saved.
5. Press **STOP** or run `/chunkvault stop`.
6. Click **OPEN** on the map screen and hit **Copy to saves** (or copy the folder manually from `.minecraft/chunkvault/` to `.minecraft/saves/`).
7. Open the world from your singleplayer list.

## Commands

| Command | What it does |
|---|---|
| `/chunkvault start <name>` | start recording (supports spaces & Unicode names) |
| `/chunkvault stop` | stop recording |
| `/chunkvault status` | show recording progress |
| `/chunkvault map` | open the map |
| `/chunkvault chests` | open the Container Database |
| `/chunkvault settings` | open settings |
| `/chunkvault area set` | open the Area Coordinates dialog |
| `/chunkvault area set <x1> <z1> <x2> <z2>` | set planned area by block corners |
| `/chunkvault area radius <blocks>` | set planned area around your position |
| `/chunkvault area clear` | clear planned area |
| `/chunkvault area hud` | toggle the Area Progress HUD |
| `/chunkvault area autostop` | toggle auto-stop at 100% |
| `/chunkvault area clip` | toggle clipping recording to the planned area |
| `/chunkvault area trim` | remove recorded chunks & containers outside the area |
| `/chunkvault fixworld <name>` | regenerate `world_gen_settings.dat` for a world |

## Good to know

- Only what the server has sent to your client can be saved. Areas you have not visited and containers you have not opened will be empty.
- Furnace smelting progress and remaining fuel time are not saved, only the items.
- Chest minecarts and mob inventories are not saved.
- Many servers forbid copying their worlds. Check the rules of the server and only record where you are allowed to. You are responsible for how you use this mod.

## Requirements

- Minecraft 26.2.x (`~26.2`)
- Fabric Loader 0.19+ and Fabric API
- Java 25
- Client-side only — nothing to install on the server

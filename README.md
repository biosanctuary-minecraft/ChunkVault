# ChunkVault

**ChunkVault records the world you explore on a multiplayer server into a normal singleplayer save.** Walk around, open chests, plan your scan area, search saved storage, and play the result later — with or without the mod installed.

<img width="1024" height="434" alt="ChunkVault Map" src="https://github.com/user-attachments/assets/9138078e-055b-4159-960c-872298b693e9" />

## Features

- **Chunk recording**: every chunk your client loads is written into a vanilla world save, including terrain, blocks, and block entities. Modified chunks are tracked and re-saved on unload so builds and broken blocks are never lost when flying away.
- **Storage contents**: open a container while recording and its contents are saved, with a chat notification showing what was stored and where. Supported: chests, trapped chests, barrels, shulker boxes, hoppers, droppers & dispensers, furnaces, blast furnaces & smokers, brewing stands, and crafters (configurable per type).
- **Latest state wins & auto-cleanup**: re-opening a container updates its saved contents, and broken or replaced containers are automatically removed from the database when their chunk is revisited.
- **Container Database & Slot Inspector**: search all saved containers by item name, ID, or container title — including double chests and items nested inside shulker boxes. Inspect every slot, peek inside nested shulkers, copy coordinates, or jump straight to the container on the map.
- **Interactive in-game map**:
  - **Container pins & live filter**: color-coded pins for containers (including all 17 real shulker box colors) and optional markers for unopened containers. Filter pins directly on the map by search query, opened/unopened status, 8 container types, and individual shulker colors (LMB to toggle, RMB to solo a color, plus All/None) — saved between sessions.
  - **Map controls**: pan, scroll zoom, on-screen **`+` / `−`** zoom buttons (plus `+`/`-` keys), **Follow Player** geolocation toggle, and RMB recenter.
  - **Live recording zone**: real-time perimeter outline matching the server's effective loaded chunk area.
- **Area Planner & Progress HUD**:
  - Drag on the map or enter exact coordinates / radius (up to ±30,000,000 blocks) to plan a recording zone.
  - **Clip to area** (ignore chunks & containers outside the zone), **Trim** (delete already-recorded chunks and containers outside the zone), **Dim outside**, and **Auto-stop** when 100% complete.
  - **In-game HUD**: shows recorded/remaining chunks, percentage, directional arrow & distance to the nearest missing chunk, plus live **ETA & movement speed**.
- **Mobs and items**: entities inside recorded chunks can be saved too (toggleable in Settings).
- **Projects**: create a new project (**New File**) or continue an existing one (**Open**), and copy finished worlds straight into your `saves` folder.
- **Hotkeys**: 7 rebindable keybindings synced with Minecraft Controls (`C + V` by default to open/close the map, plus toggle recording, Container Database, Projects, Area Coordinates, Area HUD, and Settings).
- **Settings**: per-server auto-record, container type filter, mob spawning toggle in exported worlds, and void or seed-based generation outside recorded chunks.
- **Languages**: English and Russian, selected automatically from your game language.

## How to use

1. Join a server or world.
2. Press `C + V` (or run `/chunkvault map`), click **NEW FILE** to create a project, then click **RECORD** (or run `/chunkvault start <name>`).
3. *(Optional)* Click **AREA** to drag a target zone on the map or enter exact coordinates, and toggle **Clip**, **HUD**, or **Auto-stop**.
4. Explore and open any containers you want saved.
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
- Ender chests, chest minecarts, and mob inventories are not saved.
- Many servers forbid copying their worlds. Check the rules of the server and only record where you are allowed to. You are responsible for how you use this mod.

## Requirements

- Minecraft 26.2.x (`~26.2`)
- Fabric Loader 0.19+ and Fabric API
- Java 25
- Client-side only — nothing to install on the server

# ChunkVault

**ChunkVault records the world you explore on a multiplayer server into a normal singleplayer save.** Walk around, open chests, and play the result later, with or without the mod installed.

![Present GUI](https://cdn.modrinth.com/data/cached_images/12654b52d65ab3678883b3db7c977edf5dd269af.jpeg)

## Features

- **Chunk recording**: every chunk your client loads is written into a vanilla world save, including terrain, blocks and block entities.
- **Upgraded in-game map**: press `C + V` (rebindable) or run `/chunkvault map` to view recorded chunks with biome-tinted grass/foliage, depth-shaded transparent water, elevation shading, and live recording bounds.
- **Container database & viewer**: open a container while recording to save its contents (latest state always wins). Browse all saved and discovered unopened containers in a searchable database (`/chunkvault chests`), inspect slots directly on the map, and look inside nested shulker boxes. Supported: chests, trapped chests, barrels, shulker boxes, hoppers, droppers, dispensers, furnaces, blast furnaces, smokers, brewing stands, crafters.
- **Area Planner**: drag a target rectangle on the map (or enter exact block coordinates / radius via GUI or `/chunkvault area`), track completion with an in-game HUD arrow pointing to the nearest missing chunk, optionally restrict recording strictly to the area (**Clip**), **Auto-stop** at 100%, or **Trim** outside chunks from existing saves.
- **Mobs and items**: entities can be saved too (can be turned off).
- **Projects & clean export**: create a new project (**New File**) or continue an existing one (**Open**), and copy finished worlds straight into your `saves` folder — internal ChunkVault metadata is excluded automatically.
- **Settings & Hotkeys**: per-server auto-record, container type filter, unopened-container visibility, mob spawning toggle, void or seed-based generation outside recorded chunks, and an in-game **Hotkeys** menu.
- **Languages**: English and Russian, chosen automatically from your game language.

## How to use

1. Join a server or world.
2. Press `C + V` (or run `/chunkvault map`), click **NEW FILE** to create a project, then click **RECORD** (or run `/chunkvault start <name>`).
3. *(Optional)* Click **AREA** to outline a target region, enable **Clip** to record only inside it, or enable **Auto-stop** to finish automatically at 100%.
4. Explore and open any containers you want saved.
5. Press **STOP** or run `/chunkvault stop`.
6. Click **OPEN** on the map screen and hit **Copy to saves** (or copy the folder manually from `.minecraft/chunkvault/` to `.minecraft/saves/`).
7. Open the world from your singleplayer list.

## Commands

| Command | What it does |
|---|---|
| `/chunkvault start <name>` | start recording |
| `/chunkvault stop` | stop recording |
| `/chunkvault status` | show recording progress |
| `/chunkvault map` | open the map |
| `/chunkvault chests` | open the container database |
| `/chunkvault settings` | open settings |
| `/chunkvault area set` | open the area coordinates dialog |
| `/chunkvault area set <x1> <z1> <x2> <z2>` | set planned area by block corners |
| `/chunkvault area radius <blocks>` | set square planned area around you |
| `/chunkvault area clip` | toggle recording only inside the planned area |
| `/chunkvault area trim` | remove already-saved chunks outside the area |
| `/chunkvault area hud` | toggle the in-game area progress HUD |
| `/chunkvault area autostop` | toggle auto-stop at 100% completion |
| `/chunkvault area clear` | clear the planned area |

## Good to know

- Only what the server has sent to your client can be saved. Areas you have not visited and containers you have not opened will be empty (unopened containers can still be marked on the map, but their slots remain unknown until opened).
- Furnace smelting progress and remaining fuel time are not saved, only the items.
- Ender chests, chest minecarts and mob inventories are not saved.
- Many servers forbid copying their worlds. Check the rules of the server and only record where you are allowed to. You are responsible for how you use this mod.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19+ and Fabric API
- Java 25
- Client-side only, nothing to install on the server

# ChunkVault

**ChunkVault records the world you explore on a multiplayer server into a normal singleplayer save.** Walk around, open chests, and play the result later, with or without the mod installed.

## Features

- **Chunk recording**: every chunk your client loads is written into a vanilla world save, including terrain, blocks and block entities.
- **Storage contents**: open a container while recording and its contents are saved, with a message in chat showing what was stored and where. Supported: chests, trapped chests, barrels, shulker boxes, hoppers, droppers and dispensers, furnaces, blast furnaces and smokers, brewing stands, crafters.
- **Latest state wins**: open a container again later and the save is updated with its new contents.
- **Mobs and items**: entities can be saved too (can be turned off).
- **In-game map**: see everything you have recorded, with your position and the chunk under the cursor.
- **Projects**: create a new project (**New File**) or continue an old one (**Open**), and copy finished worlds straight into your `saves` folder.
- **Settings**: auto-record per server, block mob spawning in the saved world, and choose what is generated outside the recorded area: void or terrain from a seed.
- **Languages**: English and Russian, chosen automatically from your game language.

## How to use

1. Join a server or world.
2. Run `/chunkvault map` and click **NEW FILE** to create a project, then **RECORD**, or run `/chunkvault start <name>`.
3. Explore. Open the containers you want saved.
4. Press **STOP** or run `/chunkvault stop`.
5. Open the world from your singleplayer list.

## Commands

| Command | What it does |
|---|---|
| `/chunkvault start <name>` | start recording |
| `/chunkvault stop` | stop recording |
| `/chunkvault status` | show progress |
| `/chunkvault map` | open the map |
| `/chunkvault settings` | open settings |

## Good to know

- Only what the server has sent to your client can be saved. Areas you have not visited and containers you have not opened will be empty.
- Furnace smelting progress and remaining fuel time are not saved, only the items.
- Ender chests, chest minecarts and mob inventories are not saved.
- Many servers forbid copying their worlds. Check the rules of the server and only record where you are allowed to. You are responsible for how you use this mod.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19+ and Fabric API
- Java 25
- Client-side only, nothing to install on the server

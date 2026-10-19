# Project Gielinor

Project Gielinor brings Old School RuneScape (OSRS) to Project Zomboid, including RuneScape's items, skills, creatures and the world of Gielinor. Play with Zomboid's character system, survival mechanics, UI and zombies.

**[Get the mod on Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3821895054)**

This is an early test build. Expect bugs, and start a new world after each update. We haven't tested compatibility with other mods.

## Requirements

- Project Zomboid Build 42.
- [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853), including its Java component. Follow the installation instructions on its Workshop page.
- An internet connection for the initial setup.

## Getting started

1. Subscribe to Project Gielinor on Steam Workshop and install ZombieBuddy's Java component.
2. Enable Project Gielinor. This also enables ZombieBuddy. When ZombieBuddy asks for permission to run Project Gielinor's Java code, allow it. You only need to do this once.
3. At the main menu, follow the prompt to build the content pack. This downloads about 190 MB of game files from the [OpenRS2 archive](https://archive.openrs2.org) and builds Gielinor's items, characters, creatures and world on your computer.
4. Allow several minutes for the build. Once it finishes, the mods reload with the content pack enabled.
5. Start a new game and choose **Gielinor** at World Select.

The mod does not include Jagex's game files. Build the content pack on your own computer; please don't share or upload the generated pack.

## Servers

Install ZombieBuddy's Java component on the server, then use these settings:

```ini
Mods=ZombieBuddy;ProjectGielinorContent;ProjectGielinor
Map=Gielinor
```

The server builds its content pack on the first start and again after each update. Players need to build their own pack from the main menu before joining.

## Source code

This repository contains the mod and content-builder source code so you can inspect how they work. Use the Steam Workshop version to play.

The builder reads the game cache using RuneLite's open-source cache library and generates the content pack on your computer. Downloaded game files and generated content packs are not part of the source release. Please don't commit or upload them.

## Permissions

Do not reupload or redistribute this mod, including modified versions, without our permission. Do not copy or reuse our code in other projects without asking us first.

This also applies to mod packs that bundle our files. You can include the Workshop link in collections.

These permissions concern our code only. RuneScape content belongs to Jagex.

See [source code permissions](LICENSE.md) for the repository’s usage and redistribution terms.

## Credits

Project Gielinor is a fan project with no affiliation with or endorsement from Jagex or The Indie Stone. Old School RuneScape and RuneScape are trademarks of Jagex Ltd.; Project Zomboid is a trademark of The Indie Stone.

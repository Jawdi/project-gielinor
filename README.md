# Project Gielinor

A Project Zomboid mod that brings the world of Gielinor into Zomboid: its
items, skills, creatures and eventually its map, played with Zomboid's own
character, survival mechanics, UI and zombies.

## How it's put together

The mod ships only its own code. None of Jagex's assets are included or
distributed. Instead, the mod builds them on your machine: the first time
you reach the main menu with it, it offers to build a local content pack
(items, icons, models, zombies, the map and so on) into your Zomboid mods
folder, then switches the pack on and reloads the mods.

The build runs the Project Gielinor Content Builder, which ships inside the
mod and runs with the game's own Java. The mod starts it through
[ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy), which lets mods
carry Java, so the mod requires ZombieBuddy.

The builder always builds from the same version of the game, so every player
and every multiplayer server gets the same items and world. It fetches that
version (the game cache, about 190 MB, and the keys that unlock its map) once
from the [OpenRS2 archive](https://archive.openrs2.org) and keeps it for later
builds. A pack records which builder made it, and the mod offers to build it
again after an update.

## Requirements

- Project Zomboid, Build 42.
- ZombieBuddy, with its Java part installed as its Workshop page describes.
- An internet connection the first time the builder runs.

## Servers

Install ZombieBuddy's Java part on the server too, and list
`Mods=ZombieBuddy;ProjectGielinorContent;ProjectGielinor` with
`Map=Gielinor`. The server builds its own pack on its first start (and after
an update), before it loads its mods. Players build theirs from the main menu
before joining.

## Building

From `builder/`, with Gradle (the builder needs Java 21; the mod's Java,
compiled against the game, needs Java 25):

```
gradle installMod
```

puts the mod, with its Java and the builder, in `Zomboid/mods/ProjectGielinor`,
and `gradle workshop` lays it out in `Zomboid/Workshop/ProjectGielinor`
(with `workshop/workshop.txt` and `preview.png`) for the game's Workshop
upload screen. Gradle finds Project Zomboid in Steam's usual folders; pass
`-Pzomboid=<install folder>` or set `ZOMBOID_HOME` otherwise.

The builder also runs on its own:

```
gradle run
gradle run --args="--cache <cache folder> --zomboid <install folder> --out <pack folder>"
```

It finds Project Zomboid on its own and writes the pack to
`Zomboid/mods/ProjectGielinorContent`. Each build writes `item-report.txt`
next to the pack, listing the items left out and why.
`builder/src/main/resources/item-overrides.txt` keeps or drops items by name.

The builder reads the cache with RuneLite's open-source cache library; the
licences of the libraries it bundles are in its jar's
`META-INF/THIRD-PARTY-NOTICES.txt`.

## What's in it so far

- Items: the game's items the mod has a use for, about 2,300: weapons, tools,
  food, drink, clothing and armour, and the materials Zomboid's crafting or
  the mod's skills use (ore, bars, logs, gems, herbs, seeds, hides, runes).
  Quest and minigame items, burnt food, poisoned copies and kinds with no use
  yet (ammunition, bows, shields, potions) are left out. Each item copies the
  vanilla Zomboid item that plays the same part, so it works where that does:
  axes chop trees (with tree damage scaled from their strength bonus), hammers
  build, needles sew, buckets and jugs hold water, a jug of wine holds wine,
  raw fish needs cooking. Weapons fight with damage scaled from their
  strength bonus.
- Loot: items turn up where Zomboid keeps its own of the same kind (axes with
  tools, armour in wardrobes, ore with metal bars, food in kitchens), cheap
  ones common and valuable ones rare, and zombies carry a few. Switches for
  Gielinor's and Zomboid's items in loot are ready for sandbox options.
- Models: everything keeps the game's own shape and flat colours. People
  (your character, clothing, zombies, monsters) move on Zomboid's skeleton the
  way the game animates them: each part of a model moves with one bone.
- Clothing and armour: every wearable is Zomboid clothing. Armour protects
  against bites and scratches by its defence bonuses (a rune platebody about
  57 out of 100). Helmets hide hair as they do in the game.
- Your character: character creation is the game's character designer. Pick
  body type A or B, a style for each body part (hair, jaw, torso, arms, legs,
  feet) and the hair, clothing, shoe and skin colours from the game's
  palettes. Name, voice, profession and traits stay Zomboid's. As in Zomboid,
  the body can't be taken off and the clothes can: the torso and arms you pick
  are a top, the legs trousers, the feet shoes, all ordinary clothing you can
  take off, drop, swap and loot, with a Gielinor body (in underwear)
  underneath.
- Zombies: every zombie is one of Gielinor's folk, zombified: a random look
  from the character designer, in Gielinor clothes (now and then missing a
  piece) that drop with the corpse.
- Animals: Zomboid's cows, chickens, sheep, pigs, rabbits, rats, mice,
  raccoons and deer take the look of their Gielinor counterparts.
- Map: the world of Gielinor, chosen at World Select (or `Map=Gielinor` on a
  server). The game's ground, its buildings on every floor with their walls,
  doors, windows, stairs and pitched roofs, its fences, trees, rocks and ore,
  and its furniture as Zomboid's own: beds sleep, chairs seat, ranges cook,
  and drawers, wardrobes, shelves, counters and crates hold loot. Rooms are
  named by their furniture, so a kitchen's counters hold kitchen things and a
  smithy's shelves hold metalwork, and rooms short of containers get some.
  What Zomboid has nothing like is drawn from the game's own models as the
  mod's own tiles: fountains and wells (endless water), statues, stalls,
  signs, banners, carts, crops, climbable ladders, and torches, lanterns and
  candles that light up without power. Rooms the game leaves dark get
  lanterns.
- New characters start with a bronze axe, pickaxe and sword, a tinderbox, a
  lobster and a jug of wine.

## Status

Early development. Nothing is released yet.

## Legal

Project Gielinor is a fan project. It isn't affiliated with or endorsed by
Jagex or The Indie Stone. Old School RuneScape and RuneScape are trademarks
of Jagex Ltd.; Project Zomboid is a trademark of The Indie Stone.

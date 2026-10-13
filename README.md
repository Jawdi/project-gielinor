# Project Gielinor

A Project Zomboid mod that brings the world of Gielinor into Zomboid: its
items, skills, creatures and eventually its map, played with Zomboid's own
character, survival mechanics, UI and zombies.

## How it's put together

The mod ships only its own code. None of Jagex's assets are included or
distributed. Instead, you build them yourself from your own Old School
RuneScape installation:

1. **Project Gielinor** (Steam Workshop): the mod's Lua and scripts, the
   mechanics that tie Gielinor's content into Zomboid.
2. **Project Gielinor Content Builder** (a separate download): reads the game
   cache of your installed Old School RuneScape and writes a local content
   pack (item names, icons, models and so on) into your Zomboid mods folder.
   The Workshop mod requires that pack.

## Requirements

- Project Zomboid, Build 42.
- Old School RuneScape installed and run at least once, so its cache is on
  your machine.

## Building the content pack

The builder needs Java 21 and Gradle. From `builder/`:

```
gradle run
```

It finds the game cache of an installed Old School RuneScape (RuneLite's or
the official client's) and Project Zomboid on its own, and writes the pack to
`Zomboid/mods/ProjectGielinorContent`. To point it somewhere else:

```
gradle run --args="--cache <cache folder> --zomboid <install folder> --out <pack folder>"
```

The builder reads the cache with RuneLite's open-source cache library.

## What's in it so far

- Items: every item in the game (bar noted, placeholder and duplicate
  copies), about 12,000, with icons rendered from the game's own models,
  names and weights. Each copies the vanilla Zomboid item that plays the same
  part for its animations, sounds and held model: axes chop trees like
  Zomboid's hatchet, with tree damage scaled from their strength bonus
  (bronze 12, dragon 37, against the hatchet's 15); swords, daggers, maces,
  spears, staves and two-handers fight like their Zomboid counterparts, with
  damage scaled the same way. Food and drink can be eaten. Everything else is
  carried, with no use yet.
- Models: weapons are held as their own models, lined up like the Zomboid
  weapon each copies so they sit in the hand the same way; everything else
  lies on the ground (and shows in hand) as its own model. Faces keep the
  game's flat colours.
- Clothing and armour: every wearable item (helmets, capes, amulets, body
  armour, legs, gloves, boots, rings) is Zomboid clothing, its worn model
  fitted onto Zomboid's skeleton so it moves with the character. Armour
  protects against bites and scratches by its defence bonuses (a rune
  platebody about 57 out of 100).
- Zombies: about four in five ordinary zombies are one of the game's 148
  zombie looks (zombies, zombie monks, zombie pirates and more), each fitted
  onto Zomboid's skeleton as a full-body piece so it moves with Zomboid's
  zombie animations. Special places keep their own zombies.
- New characters start with a bronze axe, pickaxe and sword, a tinderbox, a
  lobster and a jug of wine.

## Status

Early development. Nothing is released yet.

## Legal

Project Gielinor is a fan project. It isn't affiliated with or endorsed by
Jagex or The Indie Stone. Old School RuneScape and RuneScape are trademarks
of Jagex Ltd.; Project Zomboid is a trademark of The Indie Stone.

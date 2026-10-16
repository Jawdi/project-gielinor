-- Every zombie is one of Gielinor's. The content pack turns each of the
-- game's zombie looks into an outfit named GielinorZombie_<id>, and humanoid
-- monsters (goblins, skeletons, guards...) into GielinorMob_<id>, and lists
-- them in GielinorOutfits.
--
-- Zombies come from three places, and each is covered:
-- * Most are picked from the zone tables, which Zomboid reads once, the
--   first time a zombie needs dressing. As this file loads, the default mix
--   becomes Gielinor's alone, and the special places (police stations, shops,
--   weddings and so on) lose their own outfits, so they draw from it too.
-- * Some are dressed directly in a named outfit (a police car's crew, say).
--   Those are dressed again as they're made.
-- * Zomboid gives every zombie random underwear, cut for its own body, which
--   would show through Gielinor's slimmer figures; it's taken off.

require "NPCs/ZombiesZoneDefinition"
require "Gielinor_Outfits"

-- Shares of the mix: zombies roughly seven in eight, monsters the rest.
local ZOMBIE_WEIGHT = 7
local MONSTER_WEIGHT = 1

local function haveOutfits()
    return GielinorOutfits ~= nil and #GielinorOutfits.zombies > 0
end

--- Leaves the default mix to Gielinor's outfits alone.
local function replaceDefaultMix()
    local mix = {}
    for _, name in ipairs(GielinorOutfits.zombies) do
        table.insert(mix, {name = name, chance = ZOMBIE_WEIGHT / #GielinorOutfits.zombies})
    end
    for _, name in ipairs(GielinorOutfits.monsters) do
        table.insert(mix, {name = name, chance = MONSTER_WEIGHT / #GielinorOutfits.monsters})
    end
    ZombiesZoneDefinition.Default = mix
end

--- Empties every special place's own outfits, the ones it must always have
--- included, so its zombies come from the default mix.
local function emptyZones()
    for name, zone in pairs(ZombiesZoneDefinition) do
        if name ~= "Default" and type(zone) == "table" then
            for key, outfit in pairs(zone) do
                if type(outfit) == "table" and outfit.name then
                    zone[key] = nil
                end
            end
        end
    end
end

local function isGielinor(name)
    return name ~= nil and luautils.stringStarts(name, "Gielinor")
end

--- A random Gielinor outfit, zombies and monsters in the mix's shares.
local function randomOutfit()
    local list = GielinorOutfits.zombies
    if #GielinorOutfits.monsters > 0 and ZombRand(ZOMBIE_WEIGHT + MONSTER_WEIGHT) < MONSTER_WEIGHT then
        list = GielinorOutfits.monsters
    end
    return list[ZombRand(#list) + 1]
end

--- Takes off everything a zombie wears that isn't part of its Gielinor look.
local function stripOthers(zombie)
    local visuals = zombie:getItemVisuals()
    for i = visuals:size() - 1, 0, -1 do
        if not isGielinor(visuals:get(i):getClothingItemName()) then
            visuals:remove(i)
        end
    end
end

local function onZombieCreate(zombie)
    if isClient() or zombie:isSkeleton() then
        return
    end
    if not isGielinor(zombie:getOutfitName()) then
        zombie:dressInPersistentOutfit(randomOutfit())
    end
    stripOthers(zombie)
    zombie:resetModelNextFrame()
end

if haveOutfits() then
    replaceDefaultMix()
    emptyZones()
    Events.OnZombieCreate.Add(onZombieCreate)
    print("Project Gielinor: every zombie is one of " .. #GielinorOutfits.zombies .. " zombie looks and " .. #GielinorOutfits.monsters .. " monster looks")
else
    print("Project Gielinor: no zombie outfits found. Build the content pack with the Project Gielinor Content Builder.")
end

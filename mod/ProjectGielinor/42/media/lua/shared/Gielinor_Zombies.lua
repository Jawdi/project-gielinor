-- Every zombie is one of Gielinor's. The content pack turns each of the
-- game's zombie looks into an outfit named GielinorZombie_<id>, and humanoid
-- monsters (goblins, skeletons, guards...) into GielinorMob_<id>, and lists
-- them in GielinorOutfits.
--
-- The outfits hold no clothes: they only pick, and remember through saves,
-- which look a zombie has. The look itself (the model Gielinor_Zombie_<id>)
-- is laid on as a body visual, the way Zomboid lays on a zombie's wounds: it
-- is drawn and stays on the corpse, but unlike clothing it never turns into
-- something to loot. Zomboid's own body beneath is hidden by giving it a
-- fully transparent skin (clothing hides it with masks, which only worn
-- clothing has), and anything else a zombie wears or shows is taken off,
-- its hair and wounds too, so that only the look shows.
--
-- Zombies come from three places, and each is covered:
-- * Most are picked from the zone tables, which Zomboid reads once, the
--   first time a zombie needs dressing. As this file loads, the default mix
--   becomes Gielinor's alone, and the special places (police stations, shops,
--   weddings and so on) lose their own outfits, so they draw from it too.
-- * Some are dressed directly in a named outfit (a police car's crew, say).
--   Those are dressed again.
-- * Zomboid gives every zombie random underwear; it's taken off with the
--   rest.
--
-- Zomboid dresses a zombie after it's made, not as it's made: it marks the
-- zombie to be dressed when first drawn, and the first time an outfit is
-- used it can be dressed again later. So zombies are checked as they
-- update, the modding guides' way, rather than as they're made.

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

--- The look an outfit stands for: GielinorZombie_26 is the model Gielinor_Zombie_26.
local function lookOf(outfit)
    return (outfit:gsub("^Gielinor(%a+)_", "Gielinor_%1_"))
end

-- A fully transparent skin, in the mod's media/textures/Body.
local HIDDEN_SKIN = "Gielinor_Hidden"

--- Takes off everything a zombie wears or shows but `look`: clothes, and
--- the wounds, bandages and stubble Zomboid shows as body visuals. (They're
--- removed by type: Lua's numbers reach Java as objects, so a list's
--- remove(i) would look for the number in the list instead.)
local function strip(zombie, look)
    zombie:getItemVisuals():clear()
    local visual = zombie:getHumanVisual()
    local body = visual:getBodyVisuals()
    local others = {}
    for i = 0, body:size() - 1 do
        if body:get(i):getClothingItemName() ~= look then
            table.insert(others, body:get(i):getItemType())
        end
    end
    for _, itemType in ipairs(others) do
        visual:removeBodyVisualFromItemType(itemType)
    end
end

--- Gives a zombie its outfit's look, in place of anything else it shows.
local function showLook(zombie, look)
    strip(zombie, look)
    local visual = zombie:getHumanVisual()
    visual:addBodyVisual(look)
    visual:setSkinTextureName(HIDDEN_SKIN)
    visual:setHairModel("Bald")
    visual:setBeardModel("")
    zombie:resetModelNextFrame()
end

-- How often (one update in this many) a zombie is checked for clothes or
-- wounds Zomboid has put on it since.
local STRIP_EVERY = 30

local function onZombieUpdate(zombie)
    if isClient() or zombie:isSkeleton() then
        return
    end
    local outfit = zombie:getOutfitName()
    if not isGielinor(outfit) then
        zombie:dressInPersistentOutfit(randomOutfit())
        outfit = zombie:getOutfitName()
        if not isGielinor(outfit) then
            return
        end
    end
    local look = lookOf(outfit)
    if not zombie:getHumanVisual():hasBodyVisualFromItemType("Gielinor." .. look) then
        showLook(zombie, look)
    elseif ZombRand(STRIP_EVERY) == 0
            and (zombie:getItemVisuals():size() > 0 or zombie:getHumanVisual():getBodyVisuals():size() > 1) then
        strip(zombie, look)
        zombie:resetModelNextFrame()
    end
end

if haveOutfits() then
    replaceDefaultMix()
    emptyZones()
    Events.OnZombieUpdate.Add(onZombieUpdate)
    print("Project Gielinor: every zombie is one of " .. #GielinorOutfits.zombies .. " zombie looks and " .. #GielinorOutfits.monsters .. " monster looks")
else
    print("Project Gielinor: no zombie outfits found. Build the content pack with the Project Gielinor Content Builder.")
end

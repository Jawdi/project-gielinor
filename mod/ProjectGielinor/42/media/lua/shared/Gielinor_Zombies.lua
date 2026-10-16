-- Every zombie is one of Gielinor's folk, zombified: a Gielinor body (see
-- Gielinor_Body) of a random look from the game's character designer, in
-- Gielinor clothes that, as on Zomboid's zombies, are ordinary clothing and
-- drop with the corpse. Now and then a piece is missing.
--
-- Zombies come from three places, and each is covered:
-- * Most are picked from the zone tables, which Zomboid reads once, the
--   first time a zombie needs dressing. As this file loads, the default mix
--   becomes the content pack's one zombie outfit, and the special places
--   (police stations, shops, weddings and so on) lose their own outfits, so
--   they draw from it too.
-- * Some are dressed directly in a named outfit (a police car's crew, say).
--   Those are dressed again.
-- * Zomboid gives every zombie random underwear, and wounds and stubble as
--   body visuals; they're taken off, as they'd show through or float off a
--   Gielinor body.
--
-- The outfit is empty: it gives each zombie a number Zomboid keeps through
-- saves (its persistent outfit), and the look is picked from that number,
-- so a zombie looks the same each time it's loaded. Zomboid dresses a
-- zombie after it's made, not as it's made, so zombies are checked as they
-- update, the modding guides' way, rather than as they're made.

require "NPCs/ZombiesZoneDefinition"
require "Gielinor_Outfits"
require "Gielinor_Body"

-- How likely a zombie is to still have each piece of its clothes, in 100.
local KEEPS = {top = 85, legs = 90, feet = 70}

local function haveOutfits()
    return GielinorOutfits ~= nil and #GielinorOutfits.zombies > 0 and GielinorBody.available()
end

--- Leaves the default mix to Gielinor's outfit alone.
local function replaceDefaultMix()
    local mix = {}
    for _, name in ipairs(GielinorOutfits.zombies) do
        table.insert(mix, {name = name, chance = 100 / #GielinorOutfits.zombies})
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

--- Numbers from 1 to n, the same run for the same seed (a Lehmer generator,
--- small enough for Lua's numbers to keep exact).
local function generator(seed)
    local state = math.abs(seed) % 2147483646 + 1
    return function(n)
        state = state * 48271 % 2147483647
        return state % n + 1
    end
end

--- Dresses a zombie as the Gielinor folk its outfit number stands for.
local function makeGielinor(zombie)
    local random = generator(zombie:getPersistentOutfitID())
    local look = GielinorBody.randomLook(zombie:isFemale(), random)
    local visual = zombie:getHumanVisual()
    local itemVisuals = zombie:getItemVisuals()
    itemVisuals:clear()
    local keeps = {}
    for piece, chance in pairs(KEEPS) do
        keeps[piece] = random(100) <= chance
    end
    for _, piece in ipairs(GielinorBody.clothes(look)) do
        local scriptItem = keeps[GielinorBody.clothingKind(piece[1])] and getScriptManager():FindItem(piece[1])
        if scriptItem then
            local added = visual:addClothingItem(itemVisuals, scriptItem)
            if added then
                added:setTextureChoice(piece[2])
            end
        end
    end
    local covered = {}
    for i = 0, itemVisuals:size() - 1 do
        GielinorBody.cover(covered, itemVisuals:get(i):getItemType())
    end
    GielinorBody.layBody(visual, look, covered)
    zombie:resetModelNextFrame()
end

--- Takes off what Zomboid has put on a zombie since: clothes that aren't
--- Gielinor's (its underwear), and wounds and stubble. Returns whether any.
local function stripZomboid(zombie)
    local changed = false
    local itemVisuals = zombie:getItemVisuals()
    local others = {}
    for i = 0, itemVisuals:size() - 1 do
        if not isGielinor(itemVisuals:get(i):getItemType()) then
            table.insert(others, itemVisuals:get(i))
        end
    end
    -- Removed by the object itself: remove(i) from Lua would look for the number.
    for _, other in ipairs(others) do
        itemVisuals:remove(other)
        changed = true
    end
    local visual = zombie:getHumanVisual()
    local body = visual:getBodyVisuals()
    local wounds = {}
    for i = 0, body:size() - 1 do
        local itemType = body:get(i):getItemType()
        if not isGielinor(itemType) then
            table.insert(wounds, itemType)
        end
    end
    for _, itemType in ipairs(wounds) do
        visual:removeBodyVisualFromItemType(itemType)
        changed = true
    end
    return changed
end

-- How often (one update in this many) a zombie is checked for what Zomboid
-- has put on it since.
local STRIP_EVERY = 30

local function onZombieUpdate(zombie)
    if isClient() or zombie:isSkeleton() then
        return
    end
    if not isGielinor(zombie:getOutfitName()) then
        zombie:dressInPersistentOutfit(GielinorOutfits.zombies[ZombRand(#GielinorOutfits.zombies) + 1])
        if not isGielinor(zombie:getOutfitName()) then
            return
        end
    end
    if not GielinorBody.bodyOf(zombie:getHumanVisual()) then
        makeGielinor(zombie)
    elseif ZombRand(STRIP_EVERY) == 0 then
        -- Wounds and underwear come off; clothes torn open show the body.
        local changed = stripZomboid(zombie)
        local worn = {}
        local itemVisuals = zombie:getItemVisuals()
        for i = 0, itemVisuals:size() - 1 do
            table.insert(worn, {itemVisuals:get(i):getItemType(), itemVisuals:get(i)})
        end
        if GielinorBody.fitClothes(zombie:getHumanVisual(), worn) or changed then
            zombie:resetModelNextFrame()
        end
    end
end

--- Names the clothes a zombie drops for their colours, as Zomboid makes them
--- from what the zombie wore when it dies.
local function onZombieDead(zombie)
    local items = zombie:getInventory():getItems()
    for i = 0, items:size() - 1 do
        GielinorBody.nameClothes(items:get(i))
    end
end

if haveOutfits() then
    replaceDefaultMix()
    emptyZones()
    Events.OnZombieUpdate.Add(onZombieUpdate)
    Events.OnZombieDead.Add(onZombieDead)
    print("Project Gielinor: every zombie is zombified Gielinor folk")
else
    print("Project Gielinor: no Gielinor bodies found. Build the content pack with the Project Gielinor Content Builder.")
end

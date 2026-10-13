-- Puts Gielinor's zombies, and some of its monsters, among Zomboid's. The
-- content pack turns each of the game's zombie looks into an outfit named
-- GielinorZombie_<id>, and humanoid monsters (goblins, skeletons, guards,
-- giants...) into GielinorMob_<id>; this adds them to the default zombie mix,
-- weighted so most ordinary zombies are Gielinor ones. Special places
-- (weddings, police stations and so on) keep their own zombies.

require "NPCs/ZombiesZoneDefinition"

-- Shares of the default mix, against the vanilla outfits' weights (about 110
-- in all): zombies make roughly three in four, monsters about one in ten.
local GIELINOR_WEIGHT = 400
local MONSTER_WEIGHT = 60

local added = false

--- The outfits whose names start with `prefix`.
local function outfitsStarting(prefix)
    local names = {}
    local outfits = getAllOutfits(false)
    for i = 0, outfits:size() - 1 do
        local name = outfits:get(i)
        if luautils.stringStarts(name, prefix) then
            table.insert(names, name)
        end
    end
    return names
end

--- Adds outfits to the default mix, sharing `weight` between them.
local function addToMix(names, weight)
    for _, name in ipairs(names) do
        table.insert(ZombiesZoneDefinition.Default, {name = name, chance = weight / #names})
    end
end

local function addZombies()
    if added then return end
    added = true
    local zombies = outfitsStarting("GielinorZombie_")
    local monsters = outfitsStarting("GielinorMob_")
    if #zombies == 0 then
        print("Project Gielinor: no zombie outfits found. Build the content pack with the Project Gielinor Content Builder.")
        return
    end
    addToMix(zombies, GIELINOR_WEIGHT)
    if #monsters > 0 then
        addToMix(monsters, MONSTER_WEIGHT)
    end
    print("Project Gielinor: " .. #zombies .. " zombie looks and " .. #monsters .. " monster looks added to the default mix")
end

Events.OnGameBoot.Add(addZombies)
Events.OnServerStarted.Add(addZombies)

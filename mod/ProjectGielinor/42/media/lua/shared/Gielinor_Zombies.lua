-- Puts Gielinor's zombies among Zomboid's. The content pack turns each of the
-- game's zombie looks into an outfit named GielinorZombie_<id>; this adds
-- them to the default zombie mix, weighted so most ordinary zombies wear one.
-- Special places (weddings, police stations and so on) keep their own zombies.

require "NPCs/ZombiesZoneDefinition"

-- The Gielinor share of the default mix, against the vanilla outfits' weights
-- (about 110 in all): 400 makes roughly four zombies in five Gielinor ones.
local GIELINOR_WEIGHT = 400

local added = false

local function addZombies()
    if added then return end
    added = true
    local names = {}
    local outfits = getAllOutfits(false)
    for i = 0, outfits:size() - 1 do
        local name = outfits:get(i)
        if luautils.stringStarts(name, "GielinorZombie_") then
            table.insert(names, name)
        end
    end
    if #names == 0 then
        print("Project Gielinor: no zombie outfits found. Build the content pack with the Project Gielinor Content Builder.")
        return
    end
    local each = GIELINOR_WEIGHT / #names
    for _, name in ipairs(names) do
        table.insert(ZombiesZoneDefinition.Default, {name = name, chance = each})
    end
    print("Project Gielinor: " .. #names .. " zombie looks added to the default mix")
end

Events.OnGameBoot.Add(addZombies)
Events.OnServerStarted.Add(addZombies)

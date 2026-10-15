-- Puts Gielinor's zombies, and some of its monsters, among Zomboid's. The
-- content pack turns each of the game's zombie looks into an outfit named
-- GielinorZombie_<id>, and humanoid monsters (goblins, skeletons, guards...)
-- into GielinorMob_<id>, and lists them in GielinorOutfits; this adds them to
-- the default zombie mix, weighted so most ordinary zombies are Gielinor ones.
--
-- It runs as the file loads, before Zomboid reads the zombie tables (which it
-- does once, the first time a zombie needs dressing).

require "NPCs/ZombiesZoneDefinition"
require "Gielinor_Outfits"

-- Shares of the default mix, against the vanilla outfits' weights (about 110
-- in all): zombies make roughly three in four, monsters about one in ten.
local GIELINOR_WEIGHT = 400
local MONSTER_WEIGHT = 60

-- Places with zombies of their own (police stations, shops, rich streets and
-- so on) draw from their own lists first and from the default mix only for
-- the share their outfits leave over. Their outfits keep this much of their
-- share, so most of their zombies come from the default mix as well.
local ZONE_SHARE = 0.25

--- Shrinks every zone's share of its own outfits, all but the ones a zone
--- must always have.
local function shrinkZones()
    -- Some zones go by several names; shrink each once.
    local done = {[ZombiesZoneDefinition.Default] = true}
    for _, zone in pairs(ZombiesZoneDefinition) do
        if type(zone) == "table" and not done[zone] then
            done[zone] = true
            for _, outfit in pairs(zone) do
                if type(outfit) == "table" and outfit.chance and not outfit.mandatory then
                    outfit.chance = outfit.chance * ZONE_SHARE
                end
            end
        end
    end
end

--- Adds outfits to the default mix, sharing `weight` between them.
local function addToMix(names, weight)
    for _, name in ipairs(names) do
        table.insert(ZombiesZoneDefinition.Default, {name = name, chance = weight / #names})
    end
end

if not GielinorOutfits or #GielinorOutfits.zombies == 0 then
    print("Project Gielinor: no zombie outfits found. Build the content pack with the Project Gielinor Content Builder.")
else
    shrinkZones()
    addToMix(GielinorOutfits.zombies, GIELINOR_WEIGHT)
    addToMix(GielinorOutfits.monsters, MONSTER_WEIGHT)
    print("Project Gielinor: " .. #GielinorOutfits.zombies .. " zombie looks and " .. #GielinorOutfits.monsters .. " monster looks added to the default mix")
end

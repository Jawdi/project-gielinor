-- Puts Gielinor's items in Zomboid's loot: each kind of item (axes, food,
-- body armour, ore...) where Zomboid keeps its own things of that kind, the
-- cheap ones common and the valuable ones rare, and some on zombies. The
-- content pack lists the items (GielinorLoot: type, kind, value in coins);
-- GielinorLootSettings switches Gielinor's and Zomboid's items on and off.

require "Items/ProceduralDistributions"
require "Items/SuburbsDistributions"
require "Gielinor_Loot"
require "Gielinor_LootSettings"

-- Where each kind goes: Zomboid's container loot lists.
local TOOLS = {"GarageTools", "CrateTools", "CrateToolsOld", "BarnTools", "FarmerTools"}
local WEAPONS = {"GarageTools", "CrateTools", "Antiques", "BedroomSidetableRedneck", "LivingRoomShelfRedneck", "GunStoreKnives"}
local WARDROBES = {"WardrobeGeneric", "WardrobeRedneck", "WardrobeClassy", "BedroomDresser", "DresserGeneric", "CrateClothesRandom"}
local JEWELLERY = {"JewelryOthers", "JewelrySilver", "JewelryGold", "BedroomSidetable", "BedroomSidetableClassy"}
local KEEPSAKES = {"BedroomSidetable", "LivingRoomShelf", "LivingRoomSideTable", "Antiques", "CrateRandomJunk"}

local PLACES = {
    axe = {"GarageTools", "CrateTools", "BarnTools", "FarmerTools", "CampingStoreTools"},
    pickaxe = {"GarageTools", "CrateTools", "CrateMasonry", "ConstructionWorkerTools"},
    slash = WEAPONS, stab = WEAPONS, two_handed = WEAPONS, blunt = WEAPONS, polearm = WEAPONS, staff = WEAPONS,
    tool = {"GarageTools", "CrateTools", "CrateCarpentry", "CrateMetalwork", "KitchenPots", "CrateFishing", "CrateTailoring", "FarmerTools"},
    food = {"KitchenDryFood", "KitchenCannedFood", "FridgeGeneric", "FreezerGeneric", "CrateCannedFood", "GroceryStorageCrate1"},
    drink = {"FridgeGeneric", "KitchenBottles", "CrateWine", "CrateBeer", "BarShelfLiquor", "FridgeBeer"},
    wear_head = WARDROBES, wear_cape = WARDROBES, wear_body = WARDROBES, wear_legs = WARDROBES,
    wear_hands = WARDROBES, wear_feet = WARDROBES,
    wear_neck = JEWELLERY, wear_ring = JEWELLERY,
    ore = {"CrateMetalBars", "CrateStones", "CrateMetalwork", "BlacksmithTools"},
    bar = {"CrateMetalBars", "CrateMetalwork", "BlacksmithTools"},
    gem = {"JewelryGems", "JewelryStorageAll"},
    rune = KEEPSAKES, coins = {"BedroomSidetable", "CrateWallets", "LivingRoomSideTable"},
    seed = {"CrateFarming", "CrateGardening", "FarmerTools"},
    herb = {"CrateGardening", "KitchenDryFood"},
    logs = {"CrateLumber", "CrateCarpentry"},
    potion = {"BathroomCabinet", "BathroomCounter", "FridgeMedical"},
    bones = {"CrateRandomJunk"}, ammo = {"CampingStoreGear"}, ranged = {"CampingStoreGear"},
    shield = {"Antiques"}, misc = KEEPSAKES,
}

-- How much of each kind a list gets: the chances of all a kind's items in
-- one list add up to this (Zomboid's chances are percentages a roll), so a
-- kind with hundreds of items doesn't crowd out the list.
local KIND_SHARE = 30
-- Zombies' pockets: all Gielinor items together.
local ZOMBIE_SHARE = 8

--- An item's share within its kind: the dearer, the rarer (a bronze axe
--- turns up about twenty times as often as a rune one).
local function rarity(value)
    return 1 / math.sqrt(value + 10)
end

--- The Gielinor items of each kind, with their rarity.
local function byKind()
    local kinds = {}
    for _, entry in ipairs(GielinorLoot) do
        local kind = entry[2]
        kinds[kind] = kinds[kind] or {total = 0, items = {}}
        local weight = rarity(entry[3])
        table.insert(kinds[kind].items, {"Gielinor." .. entry[1], weight})
        kinds[kind].total = kinds[kind].total + weight
    end
    return kinds
end

--- Adds items to a loot list's items, `share` split between them by rarity.
local function addTo(list, group, share)
    for _, item in ipairs(group.items) do
        table.insert(list.items, item[1])
        table.insert(list.items, share * item[2] / group.total)
    end
end

local function addGielinorItems()
    local kinds = byKind()
    local amount = GielinorLootSettings.gielinorAmount
    local missing = {}
    for kind, group in pairs(kinds) do
        for _, name in ipairs(PLACES[kind] or PLACES.misc) do
            local list = ProceduralDistributions.list[name]
            if list and list.items then
                addTo(list, group, KIND_SHARE * amount)
            else
                missing[name] = true
            end
        end
    end
    for name in pairs(missing) do
        print("Project Gielinor: no loot list " .. name)
    end
    -- Zombies carry a little of anything.
    local all = {total = 0, items = {}}
    for _, group in pairs(kinds) do
        for _, item in ipairs(group.items) do
            table.insert(all.items, item)
            all.total = all.total + item[2]
        end
    end
    for _, pocket in ipairs({"inventorymale", "inventoryfemale"}) do
        local list = SuburbsDistributions.all and SuburbsDistributions.all[pocket]
        if list and list.items then
            addTo(list, all, ZOMBIE_SHARE * amount)
        end
    end
end

local function isGielinor(item)
    return type(item) == "string" and luautils.stringStarts(item, "Gielinor.")
end

--- Takes every item but Gielinor's out of a loot list's items, wherever it
--- nests (container lists keep their junk in a list of its own).
local function keepOnlyGielinor(list, seen)
    if type(list) ~= "table" or seen[list] then
        return
    end
    seen[list] = true
    if type(list.items) == "table" then
        local kept = {}
        for i = 1, #list.items, 2 do
            if isGielinor(list.items[i]) then
                table.insert(kept, list.items[i])
                table.insert(kept, list.items[i + 1])
            end
        end
        list.items = kept
    end
    for key, child in pairs(list) do
        if key ~= "items" then
            keepOnlyGielinor(child, seen)
        end
    end
end

local function applyRules()
    if not GielinorLoot then
        print("Project Gielinor: no loot list found. Build the content pack with the Project Gielinor Content Builder.")
        return
    end
    if GielinorLootSettings.gielinorItems then
        addGielinorItems()
    end
    if not GielinorLootSettings.zomboidItems then
        local seen = {}
        keepOnlyGielinor(ProceduralDistributions.list, seen)
        keepOnlyGielinor(SuburbsDistributions, seen)
    end
    print("Project Gielinor: loot has " .. #GielinorLoot .. " Gielinor items" ..
        (GielinorLootSettings.gielinorItems and "" or " (switched off)") ..
        (GielinorLootSettings.zomboidItems and "" or ", and no Zomboid items"))
end

Events.OnPostDistributionMerge.Add(applyRules)

-- New characters start with a few tools and some food from Gielinor.
-- Items come from the locally built content pack; any it doesn't have are
-- skipped.

local kit = {
    "Gielinor.BronzeAxe",
    "Gielinor.BronzePickaxe",
    "Gielinor.BronzeSword",
    "Gielinor.Tinderbox",
    "Gielinor.Lobster",
    "Gielinor.JugOfWine",
}

local function giveKit(player)
    local inventory = player:getInventory()
    for _, fullType in ipairs(kit) do
        if getScriptManager():FindItem(fullType) then
            inventory:AddItem(fullType)
        else
            print("Project Gielinor: the content pack has no " .. fullType)
        end
    end
end

Events.OnNewGame.Add(giveKit)

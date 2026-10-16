-- New characters are Gielinor folk, and start with a few tools and some food
-- from Gielinor. Items come from the locally built content pack; any it
-- doesn't have are skipped.

require "Gielinor_Body"

local kit = {
    "Gielinor.BronzeAxe",
    "Gielinor.BronzePickaxe",
    "Gielinor.BronzeSword",
    "Gielinor.Tinderbox",
    "Gielinor.Lobster",
    "Gielinor.JugOfWine",
    -- For now, to compare how a Gielinor weapon sits in the hand with one of Zomboid's.
    "Base.HandAxe",
}

local function giveKit(player)
    -- Characters made in the character designer already have the body and
    -- clothes they chose; any other (made before the mod was on, say) gets
    -- the default.
    if GielinorBody.available() and not GielinorBody.bodyOf(player:getHumanVisual()) then
        GielinorBody.dress(player)
    end
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

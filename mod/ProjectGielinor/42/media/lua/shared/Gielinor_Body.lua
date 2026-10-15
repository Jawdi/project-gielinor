-- Characters are Gielinor folk: each wears a Gielinor body in place of
-- Zomboid's, one piece a part (head, torso, arms, hands, legs, feet) as the
-- game builds its players. The parts go in their own slots, drawn first so
-- every other piece of clothing goes over them, and clothing replaces the
-- parts it covers as the game's equipment does: something worn on the body
-- hides the torso and arms, gloves the hands, trousers the legs, shoes the
-- feet.

require "NPCs/BodyLocations"

local PARTS = {"head", "torso", "arms", "hands", "legs", "feet"}

-- The slots whose clothing hides each part.
local HIDDEN_BY = {
    torso = {"base:jacket"},
    arms = {"base:jacket"},
    hands = {"base:hands"},
    legs = {"base:pants"},
    feet = {"base:shoes"},
}

local function slot(name)
    return ItemBodyLocation.get(ResourceLocation.of(name))
end

local group = BodyLocations.getGroup("Human")
for i, part in ipairs(PARTS) do
    local location = slot("gielinor:" .. part)
    group:getOrCreateLocation(location)
    group:moveLocationToIndex(location, i - 1)
end
for part, covers in pairs(HIDDEN_BY) do
    for _, cover in ipairs(covers) do
        group:setHideModel(slot(cover), slot("gielinor:" .. part))
    end
end

GielinorBody = GielinorBody or {}

--- Takes off everything a character wears, leaving it in their inventory.
--- Zomboid's clothes are cut for Zomboid's body, so over a Gielinor body they
--- poke through in patches.
local function undress(character)
    local worn = character:getWornItems()
    local items = {}
    for i = 0, worn:size() - 1 do
        table.insert(items, worn:get(i):getItem())
    end
    for _, item in ipairs(items) do
        character:removeWornItem(item)
    end
end

--- Puts a Gielinor body on a character, the one matching their sex, in place
--- of the clothes they started in. Its head has its own hair, so Zomboid's
--- hair and beard go.
function GielinorBody.dress(character)
    local prefix = character:isFemale() and "Gielinor.GielinorBodyFemale_" or "Gielinor.GielinorBodyMale_"
    if not getScriptManager():FindItem(prefix .. "torso") then
        print("Project Gielinor: the content pack has no " .. prefix .. "torso")
        return
    end
    undress(character)
    for _, part in ipairs(PARTS) do
        if getScriptManager():FindItem(prefix .. part) then
            local piece = character:getInventory():AddItem(prefix .. part)
            character:setWornItem(piece:getBodyLocation(), piece)
        end
    end
    local visual = character:getHumanVisual()
    visual:setHairModel("Bald")
    visual:setBeardModel("")
    character:resetModel()
end

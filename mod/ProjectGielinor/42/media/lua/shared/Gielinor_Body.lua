-- Characters are Gielinor folk: each wears a Gielinor body, a full-body piece
-- that hides Zomboid's body. It goes in its own slot, drawn first so every
-- other piece of clothing goes over it.

require "NPCs/BodyLocations"

local location = ItemBodyLocation.get(ResourceLocation.of("gielinor:body"))
local group = BodyLocations.getGroup("Human")
group:getOrCreateLocation(location)
group:moveLocationToIndex(location, 0)

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
    local fullType = character:isFemale() and "Gielinor.GielinorBodyFemale" or "Gielinor.GielinorBodyMale"
    if not getScriptManager():FindItem(fullType) then
        print("Project Gielinor: the content pack has no " .. fullType)
        return
    end
    undress(character)
    local body = character:getInventory():AddItem(fullType)
    character:setWornItem(body:getBodyLocation(), body)
    local visual = character:getHumanVisual()
    visual:setHairModel("Bald")
    visual:setBeardModel("")
    character:resetModel()
end

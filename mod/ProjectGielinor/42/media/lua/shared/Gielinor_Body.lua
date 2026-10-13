-- Characters are Gielinor folk: each wears a Gielinor body, a full-body piece
-- that hides Zomboid's body. It goes in its own slot, drawn first so every
-- other piece of clothing goes over it.

require "NPCs/BodyLocations"

local location = ItemBodyLocation.get(ResourceLocation.of("gielinor:body"))
local group = BodyLocations.getGroup("Human")
group:getOrCreateLocation(location)
group:moveLocationToIndex(location, 0)

GielinorBody = GielinorBody or {}

--- Puts a Gielinor body on a character, the one matching their sex. Its head
--- has its own hair, so Zomboid's hair and beard go.
function GielinorBody.dress(character)
    local fullType = character:isFemale() and "Gielinor.GielinorBodyFemale" or "Gielinor.GielinorBodyMale"
    if not getScriptManager():FindItem(fullType) then
        print("Project Gielinor: the content pack has no " .. fullType)
        return
    end
    local body = character:getInventory():AddItem(fullType)
    character:setWornItem(body:getBodyLocation(), body)
    local visual = character:getHumanVisual()
    visual:setHairModel("Bald")
    visual:setBeardModel("")
    character:resetModel()
end

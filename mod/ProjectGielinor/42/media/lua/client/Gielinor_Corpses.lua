-- A Gielinor corpse's body follows its clothes as they're taken from it, as
-- a character's does as they dress: Zomboid drops a looted piece from the
-- corpse's worn items as it moves, and the body beneath it is shown again.

require "TimedActions/ISInventoryTransferAction"
require "Gielinor_Body"

local original = ISInventoryTransferAction.perform

function ISInventoryTransferAction:perform()
    local corpse = self.srcContainer and self.srcContainer:getParent()
    original(self)
    if instanceof(corpse, "IsoDeadBody") and GielinorBody.available() then
        GielinorBody.fitClothes(corpse:getHumanVisual(), GielinorBody.wornBy(corpse))
    end
end

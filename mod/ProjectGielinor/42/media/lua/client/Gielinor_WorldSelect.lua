-- Names Gielinor on the World Select screen. Its towns are map folders
-- beside the world's own, as Kentucky's towns are, so the game counts the
-- world as several maps and would call it "World 2"; this calls it by its
-- map's title instead.

require "OptionScreens/WorldSelect"

local WORLD = "Gielinor"
local fillList = WorldSelect.fillList

function WorldSelect:fillList()
    fillList(self)
    for _, row in ipairs(self.listbox.items) do
        local dirs = row.item and row.item.mapDirs
        if dirs and dirs:contains(WORLD) then
            local info = getMapInfo(WORLD)
            local title = info and info.title or WORLD
            row.text = title
            row.item.name = title
        end
    end
end

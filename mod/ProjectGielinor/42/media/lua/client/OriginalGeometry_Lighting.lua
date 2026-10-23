if not GielinorImportedWorld then return end

-- Native room switches. Initial state is set once; subsequent user toggles persist.
require "OriginalGeometry_LightLocations"
local function initialize(square)
    if not square then return end
    local key=square:getX() .. "," .. square:getY() .. "," .. square:getZ()
    if not OriginalGeometryLightLocations[key] or not square:getRoom() then return end
    local objects=square:getObjects()
    for i=0,objects:size()-1 do
        local object=objects:get(i)
        if instanceof(object,"IsoLightSwitch") then
            local data=object:getModData()
            if not data.OriginalGeometryLightInitialized then
                object:setActive(true)
                data.OriginalGeometryLightInitialized=true
                print("[OriginalGeometryPrototype] room switch at=" .. key .. " active=" .. tostring(object:isActivated()))
            end
        end
    end
end
Events.LoadGridsquare.Add(initialize)
Events.OnGameStart.Add(function()
    for _,position in pairs(OriginalGeometryLightLocations) do
        initialize(getCell():getGridSquare(position[1],position[2],position[3]))
    end
end)

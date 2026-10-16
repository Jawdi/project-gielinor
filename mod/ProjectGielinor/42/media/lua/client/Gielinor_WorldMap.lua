-- How Zomboid's maps (the M key, the spawn select screen, maps found as loot)
-- draw Gielinor's ground. The content pack's map tags its stone, sand and
-- dirt as `gielinor` features; water is Zomboid's own river layer, and grass
-- is the map's plain background.

require "ISUI/Maps/ISMapDefinitions"

local MIN_ZOOM = 0
local MAX_ZOOM = 24

local GROUNDS = {
    {value = "dirt", r = 185, g = 122, b = 87},
    {value = "sand", r = 214, g = 196, b = 148},
    {value = "stone", r = 150, g = 145, b = 138},
}

local original = MapUtils.initDefaultStyleV1

function MapUtils.initDefaultStyleV1(mapUI)
    original(mapUI)
    local styleAPI = mapUI.javaObject:getAPIv1():getStyleAPI()
    for _, ground in ipairs(GROUNDS) do
        local layer = styleAPI:newPolygonLayer("gielinor-" .. ground.value)
        layer:setMinZoom(MIN_ZOOM)
        layer:setFilter("gielinor", ground.value)
        layer:addFill(MIN_ZOOM, ground.r, ground.g, ground.b, 255)
        layer:addFill(MAX_ZOOM, ground.r, ground.g, ground.b, 255)
    end
end

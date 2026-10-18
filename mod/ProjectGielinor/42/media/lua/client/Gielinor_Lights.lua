-- Lights Gielinor's torches, lanterns, candles and fires. The content pack
-- marks the tiles that give light with a GielinorLight property (the light's
-- radius in squares, then its red, green and blue); each square with one
-- gets a light of its own as it loads, which needs no power and never goes
-- out. A light is remembered by its square, so a square loaded again (its
-- chunk left behind and come back to) replaces its light rather than adding
-- a second.

local lights = {}

--- A GielinorLight value's radius and colour.
local function parse(value)
    local radius, r, g, b = value:match("^(%S+)%s+(%S+)%s+(%S+)%s+(%S+)$")
    return tonumber(radius) or 6, tonumber(r) or 1, tonumber(g) or 0.8, tonumber(b) or 0.6
end

--- The GielinorLight value of an object's sprite, or of a sprite drawn over
--- it: Zomboid lays a wall's lantern (a WallOverlay tile) over the wall
--- rather than as an object of its own.
local function lightOf(object)
    local sprite = object:getSprite()
    local value = sprite and sprite:getProperties():get("GielinorLight")
    if value then
        return value
    end
    local overlays = object:getAttachedAnimSprite()
    if overlays then
        for i = 0, overlays:size() - 1 do
            local overlay = overlays:get(i):getParentSprite()
            value = overlay and overlay:getProperties():get("GielinorLight")
            if value then
                return value
            end
        end
    end
    return nil
end

local function lightSquare(square)
    local objects = square:getObjects()
    for i = 0, objects:size() - 1 do
        local value = lightOf(objects:get(i))
        if value then
            local x, y, z = square:getX(), square:getY(), square:getZ()
            local key = x .. "," .. y .. "," .. z
            local cell = getCell()
            if lights[key] then
                cell:removeLamppost(lights[key])
            end
            local radius, r, g, b = parse(value)
            local light = IsoLightSource.new(x, y, z, r, g, b, radius)
            cell:addLamppost(light)
            lights[key] = light
            return
        end
    end
end

Events.LoadGridsquare.Add(lightSquare)

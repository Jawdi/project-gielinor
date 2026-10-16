-- Characters are Gielinor folk: each wears a Gielinor body in place of
-- Zomboid's, made of the body parts the game's character designer offers
-- (hair, jaw, torso, arms, hands, legs, feet), coloured as its owner chose.
-- The parts go in their own slots, drawn first so every other piece of
-- clothing goes over them, and clothing replaces the parts it covers as the
-- game's equipment does: something worn on the body hides the torso and
-- arms, gloves the hands, trousers the legs, shoes the feet.
--
-- A look is { type = "A" or "B", kits = { part = kit id }, colours = {
-- hair, torso, legs, feet, skin = palette index } }.

require "NPCs/BodyLocations"
require "Gielinor_Kits"
require "Gielinor_Palettes"

GielinorBody = GielinorBody or {}

GielinorBody.PARTS = {"hair", "jaw", "torso", "arms", "hands", "legs", "feet"}

-- The palette and colour each part's chosen colour comes from.
GielinorBody.COLOUR_OF = {
    hair = {"hair", "hair"},
    jaw = {"hair", "hair"},
    torso = {"clothing", "torso"},
    arms = {"clothing", "torso"},
    legs = {"clothing", "legs"},
    feet = {"shoes", "feet"},
}

-- The slots whose clothing hides each part.
local HIDDEN_BY = {
    torso = {"base:jacket"},
    arms = {"base:jacket"},
    hands = {"base:hands"},
    legs = {"base:pants"},
    feet = {"base:shoes"},
}

local PIECES = {fixed = "", skin = "_skin", colour = "_colour"}

local function slot(name)
    return ItemBodyLocation.get(ResourceLocation.of(name))
end

local group = BodyLocations.getGroup("Human")
local index = 0
for _, part in ipairs(GielinorBody.PARTS) do
    for _, suffix in pairs(PIECES) do
        local location = slot("gielinor:" .. part .. suffix)
        group:getOrCreateLocation(location)
        group:moveLocationToIndex(location, index)
        index = index + 1
    end
end
for part, covers in pairs(HIDDEN_BY) do
    for _, cover in ipairs(covers) do
        for _, suffix in pairs(PIECES) do
            group:setHideModel(slot(cover), slot("gielinor:" .. part .. suffix))
        end
    end
end

--- Whether the content pack has body parts to dress anyone in.
function GielinorBody.available()
    return GielinorKits ~= nil and GielinorKits.A ~= nil
end

--- A body type's default look.
function GielinorBody.defaultLook(female)
    local type = female and "B" or "A"
    local kits = {}
    for part, kit in pairs(GielinorKits.defaults[type] or {}) do
        kits[part] = kit
    end
    return {type = type, kits = kits, colours = {hair = 1, torso = 1, legs = 1, feet = 1, skin = 1}}
end

--- The kits a body type has for a part, in the designer's order.
function GielinorBody.choices(type, part)
    return GielinorKits[type] and GielinorKits[type][part] or {}
end

local function findKit(type, part, id)
    for _, entry in ipairs(GielinorBody.choices(type, part)) do
        if entry.kit == id then
            return entry
        end
    end
end

local function colourOf(look, part, piece)
    local palette, key
    if piece == "skin" then
        palette, key = "skin", "skin"
    else
        local which = GielinorBody.COLOUR_OF[part]
        if not which then
            return nil
        end
        palette, key = which[1], which[2]
    end
    local colours = GielinorPalettes[palette]
    local value = colours[look.colours[key] or 1] or colours[1]
    local r, g, b = GielinorPalettes.rgb(value)
    return ImmutableColor.new(r, g, b, 1)
end

--- The items a look is made of: each slot's item type, and its tint (or nil).
function GielinorBody.pieces(look)
    local out = {}
    for _, part in ipairs(GielinorBody.PARTS) do
        local entry = look.kits[part] and findKit(look.type, part, look.kits[part])
        for piece, suffix in pairs(PIECES) do
            local fullType = entry and entry[piece]
            table.insert(out, {
                slot = "gielinor:" .. part .. suffix,
                fullType = fullType,
                tint = fullType and piece ~= "fixed" and colourOf(look, part, piece) or nil,
            })
        end
    end
    return out
end

--- Puts a look on anything that wears items (a character, or the
--- description character creation builds one from). `make` makes an item of
--- a type and gives it to the wearer.
local function wear(wearer, look, make)
    for _, p in ipairs(GielinorBody.pieces(look)) do
        local location = slot(p.slot)
        if p.fullType then
            local item = make(p.fullType)
            if item then
                if p.tint and item:getVisual() then
                    item:getVisual():setTint(p.tint)
                end
                wearer:setWornItem(location, item)
            end
        else
            wearer:setWornItem(location, nil)
        end
    end
end

--- Dresses a character description (as character creation edits) in a look.
function GielinorBody.dressDescription(desc, look)
    wear(desc, look, instanceItem)
    local visual = desc:getHumanVisual()
    visual:setHairModel("Bald")
    visual:setBeardModel("")
end

--- Whether a character already wears a Gielinor body.
function GielinorBody.isWearing(character)
    local worn = character:getWornItems()
    for i = 0, worn:size() - 1 do
        local item = worn:get(i):getItem()
        if item and luautils.stringStarts(item:getFullType(), "Gielinor.GielinorKit") then
            return true
        end
    end
    return false
end

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

--- Dresses a character in a look (their body type's default if none), in
--- place of the clothes they started in.
function GielinorBody.dress(character, look)
    if not GielinorBody.available() then
        print("Project Gielinor: the content pack has no body parts")
        return
    end
    look = look or GielinorBody.defaultLook(character:isFemale())
    undress(character)
    local inventory = character:getInventory()
    wear(character, look, function(fullType)
        return inventory:AddItem(fullType)
    end)
    local visual = character:getHumanVisual()
    visual:setHairModel("Bald")
    visual:setBeardModel("")
    character:resetModel()
end

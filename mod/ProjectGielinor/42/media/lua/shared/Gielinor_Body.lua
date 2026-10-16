-- Gielinor folk are made as Zomboid's are: a body that can't be taken off,
-- and clothes over it that can. The body (upper body, hands, legs, feet,
-- with underwear; the head, hair, chin and beard) is laid on as body
-- visuals, as Zomboid lays on wounds and stubble: drawn, kept on the corpse,
-- never an item. The clothes (a top, trousers, shoes) are ordinary clothing
-- items, worn, taken off, dropped and looted like any other. Zomboid's own
-- body underneath is given a transparent skin, so only Gielinor's shows.
--
-- As Zomboid's clothing masks the body it covers, Gielinor clothes take the
-- place of the body regions they cover (a top the torso and arms, a
-- sleeveless one the torso, trousers the legs, shoes the feet): the clothes
-- carry their own bare skin, in the wearer's skin colour, so nothing beneath
-- pokes through.
--
-- A look is what the game's character designer picks:
-- { type = "A" or "B", kits = { hair, jaw, torso, arms, legs, feet = kit id },
--   colours = { hair, torso, legs, feet, skin = choice } }
-- A kit left out is none: no jaw is clean shaven, no torso no top, no arms a
-- sleeveless top, no legs no trousers, no feet no shoes.
-- A body piece's texture choice (counting from 0) is its colour; a clothing
-- piece's is its colour and skin, colour * GielinorKits.skins + skin.

require "Gielinor_Kits"

GielinorBody = GielinorBody or {}

-- The parts the designer offers, in its order.
GielinorBody.PARTS = {"hair", "jaw", "torso", "arms", "legs", "feet"}
-- A fully transparent skin, in the mod's media/textures/Body.
GielinorBody.HIDDEN_SKIN = "Gielinor_Hidden"
-- The body regions, and the clothes (by item type) that cover them.
GielinorBody.REGIONS = {"torso", "arms", "hands", "legs", "feet"}
local COVERED_BY = {
    {prefix = "Gielinor.GielinorTop_", kind = "top", regions = {"torso", "arms"}},
    {prefix = "Gielinor.GielinorTrousers_", kind = "legs", regions = {"legs"}},
    {prefix = "Gielinor.GielinorShoes_", kind = "feet", regions = {"feet"}},
}
-- The parts a look can leave out.
GielinorBody.OPTIONAL = {jaw = true, torso = true, arms = true, legs = true, feet = true}

local PIECE_PREFIX = "Gielinor.Gielinor"
local BODY_PREFIX = "Gielinor.GielinorBody"

--- Whether the content pack has Gielinor bodies to make anyone of.
function GielinorBody.available()
    return GielinorKits ~= nil and GielinorKits.A ~= nil and type(GielinorKits.A.body) == "table"
        and GielinorKits.colours ~= nil and GielinorKits.skins ~= nil
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

--- The kits a body type offers for a part, in the designer's order, each as
--- { kit = id, ... }.
function GielinorBody.choices(type, part)
    local kits = GielinorKits[type]
    if not kits then
        return {}
    end
    if part == "torso" or part == "arms" then
        local out = {}
        for _, id in ipairs(kits[part] or {}) do
            table.insert(out, {kit = id})
        end
        return out
    end
    return kits[part] or {}
end

local function find(type, part, id)
    for _, entry in ipairs(GielinorBody.choices(type, part)) do
        if entry.kit == id then
            return entry
        end
    end
end

--- A colour's two shades as red, green and blue from 0 to 1.
function GielinorBody.rgb(key, choice, second)
    local choices = GielinorKits.colours[key]
    local shades = choices[choice] or choices[1]
    local value = second and shades[2] or shades[1]
    return math.floor(value / 65536) % 256 / 255, math.floor(value / 256) % 256 / 255, value % 256 / 255
end

--- Which Gielinor clothes an item type is ("top", "legs", "feet"), or nil.
function GielinorBody.clothingKind(itemType)
    for _, rule in ipairs(COVERED_BY) do
        if itemType and luautils.stringStarts(itemType, rule.prefix) then
            return rule.kind
        end
    end
end

--- Marks in `covered` the body regions a clothing item type covers. A
--- sleeveless top (its sleeves "_0") leaves the arms bare.
function GielinorBody.cover(covered, itemType)
    for _, rule in ipairs(COVERED_BY) do
        if itemType and luautils.stringStarts(itemType, rule.prefix) then
            for _, region in ipairs(rule.regions) do
                if not (region == "arms" and luautils.stringEnds(itemType, "_0")) then
                    covered[region] = true
                end
            end
        end
    end
    return covered
end

--- The body visuals a look shows, but for the `covered` regions:
--- { item type, texture choice }.
function GielinorBody.bodyPieces(look, covered)
    covered = covered or {}
    local kits = GielinorKits[look.type]
    local skin = (look.colours.skin or 1) - 1
    local hair = (look.colours.hair or 1) - 1
    local out = {}
    for _, region in ipairs(GielinorBody.REGIONS) do
        if kits.body[region] and not covered[region] then
            table.insert(out, {kits.body[region], skin})
        end
    end
    local style = look.kits.hair and find(look.type, "hair", look.kits.hair)
    if style then
        if style.head then table.insert(out, {style.head, skin}) end
        if style.hair then table.insert(out, {style.hair, hair}) end
    end
    local jaw = look.kits.jaw and find(look.type, "jaw", look.kits.jaw)
    if jaw then
        if jaw.chin then table.insert(out, {jaw.chin, skin}) end
        if jaw.beard then table.insert(out, {jaw.beard, hair}) end
    end
    return out
end

--- The clothes a look wears: { item type, texture choice }.
function GielinorBody.clothes(look)
    local kits = GielinorKits[look.type]
    local skin = (look.colours.skin or 1) - 1
    local function choice(key)
        return ((look.colours[key] or 1) - 1) * GielinorKits.skins + skin
    end
    local out = {}
    local top = look.kits.torso and kits.tops[look.kits.torso .. "_" .. (look.kits.arms or 0)]
    if top then
        table.insert(out, {top, choice("torso")})
    end
    local legs = look.kits.legs and find(look.type, "legs", look.kits.legs)
    if legs then
        table.insert(out, {legs.item, choice("legs")})
    end
    local feet = look.kits.feet and find(look.type, "feet", look.kits.feet)
    if feet then
        table.insert(out, {feet.item, choice("feet")})
    end
    return out
end

local function isPiece(itemType)
    return itemType ~= nil and luautils.stringStarts(itemType, PIECE_PREFIX)
end

--- Lays a look's body onto a human visual, but for the `covered` regions,
--- in place of any Gielinor body it had and of Zomboid's (skin, hair, beard).
function GielinorBody.layBody(visual, look, covered)
    local body = visual:getBodyVisuals()
    local old = {}
    for i = 0, body:size() - 1 do
        table.insert(old, body:get(i):getItemType())
    end
    -- Zomboid's own wounds and stubble go too: they'd float off a Gielinor body.
    for _, itemType in ipairs(old) do
        visual:removeBodyVisualFromItemType(itemType)
    end
    for _, piece in ipairs(GielinorBody.bodyPieces(look, covered)) do
        local added = visual:addBodyVisualFromItemType(piece[1])
        if added then
            added:setTextureChoice(piece[2])
        end
    end
    visual:setSkinTextureName(GielinorBody.HIDDEN_SKIN)
    visual:setHairModel("Bald")
    visual:setBeardModel("")
end

--- A human visual's Gielinor body type and skin choice (from 0), or nil if
--- it has no Gielinor body.
function GielinorBody.bodyOf(visual)
    local body = visual:getBodyVisuals()
    for i = 0, body:size() - 1 do
        local itemType = body:get(i):getItemType()
        if itemType and luautils.stringStarts(itemType, BODY_PREFIX) then
            return string.sub(itemType, -1), body:get(i):getTextureChoice()
        end
    end
end

--- Shows or hides a Gielinor body's regions to suit what it wears, and
--- gives the Gielinor clothes it wears its skin. `worn` lists what it wears
--- as { item type, item visual }. Returns whether anything changed.
function GielinorBody.fitClothes(visual, worn)
    local type, skin = GielinorBody.bodyOf(visual)
    if not type then
        return false
    end
    skin = math.max(skin or 0, 0)
    local changed = false
    local covered = {}
    for _, w in ipairs(worn) do
        if GielinorBody.clothingKind(w[1]) then
            GielinorBody.cover(covered, w[1])
            local choice = w[2]:getTextureChoice()
            local fitted = math.floor(math.max(choice, 0) / GielinorKits.skins) * GielinorKits.skins + skin
            if choice ~= fitted then
                w[2]:setTextureChoice(fitted)
                changed = true
            end
        end
    end
    local regions = GielinorKits[type].body
    for _, region in ipairs(GielinorBody.REGIONS) do
        local itemType = regions[region]
        if itemType then
            local shown = visual:hasBodyVisualFromItemType(itemType)
            if covered[region] and shown then
                visual:removeBodyVisualFromItemType(itemType)
                changed = true
            elseif not covered[region] and not shown then
                local added = visual:addBodyVisualFromItemType(itemType)
                if added then
                    added:setTextureChoice(skin)
                end
                changed = true
            end
        end
    end
    return changed
end

--- What a character wears, as fitClothes takes it.
function GielinorBody.wornBy(character)
    local out = {}
    local worn = character:getWornItems()
    for i = 0, worn:size() - 1 do
        local item = worn:get(i):getItem()
        if item and item:getVisual() then
            table.insert(out, {item:getFullType(), item:getVisual()})
        end
    end
    return out
end

--- Dresses anything that wears items in a look's clothes. `make` makes an
--- item of a type.
local function wearClothes(wearer, look, make)
    for _, piece in ipairs(GielinorBody.clothes(look)) do
        local item = make(piece[1])
        if item then
            if item:getVisual() then
                item:getVisual():setTextureChoice(piece[2])
            end
            wearer:setWornItem(item:getBodyLocation(), item)
        end
    end
end

local function coveredByLook(look)
    local covered = {}
    for _, piece in ipairs(GielinorBody.clothes(look)) do
        GielinorBody.cover(covered, piece[1])
    end
    return covered
end

--- Makes a character description (as character creation edits) of a look:
--- its body, and its clothes in place of whatever it wore.
function GielinorBody.dressDescription(desc, look)
    desc:getWornItems():clear()
    wearClothes(desc, look, instanceItem)
    GielinorBody.layBody(desc:getHumanVisual(), look, coveredByLook(look))
end

--- Makes a character of a look (their body type's default if none): its
--- body, and its clothes in place of what they wore, which go to their
--- inventory.
function GielinorBody.dress(character, look)
    if not GielinorBody.available() then
        print("Project Gielinor: the content pack has no Gielinor bodies")
        return
    end
    look = look or GielinorBody.defaultLook(character:isFemale())
    local worn = character:getWornItems()
    local items = {}
    for i = 0, worn:size() - 1 do
        table.insert(items, worn:get(i):getItem())
    end
    for _, item in ipairs(items) do
        character:removeWornItem(item)
    end
    local inventory = character:getInventory()
    wearClothes(character, look, function(fullType)
        return inventory:AddItem(fullType)
    end)
    GielinorBody.layBody(character:getHumanVisual(), look, coveredByLook(look))
    character:resetModel()
end

--- A random look of a body type, from `random(n)` (1 to n): any kits and
--- colours the designer offers.
function GielinorBody.randomLook(female, random)
    local look = GielinorBody.defaultLook(female)
    for _, part in ipairs(GielinorBody.PARTS) do
        local choices = GielinorBody.choices(look.type, part)
        if #choices > 0 and (part ~= "jaw" or random(3) > 1) then
            look.kits[part] = choices[random(#choices)].kit
        elseif part == "jaw" then
            look.kits.jaw = nil
        end
    end
    for key, choices in pairs(GielinorKits.colours) do
        look.colours[key] = random(#choices)
    end
    return look
end

-- Characters' bodies follow their clothes as they change.
local function onClothingUpdated(character)
    if not instanceof(character, "IsoPlayer") or not GielinorBody.available() then
        return
    end
    if GielinorBody.fitClothes(character:getHumanVisual(), GielinorBody.wornBy(character)) then
        character:resetModelNextFrame()
    end
end

Events.OnClothingUpdated.Add(onClothingUpdated)

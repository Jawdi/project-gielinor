-- Character creation as the game's character designer: Zomboid's name,
-- body type (male is the game's body type A, female B), voice, profession
-- and traits stay, while its skin, hair, beard and clothing choices give way
-- to the game's: a style for each body part and its colours. The hair and
-- jaw are the body's; the torso and arms are a top, the legs trousers and
-- the feet shoes, which the character starts in as ordinary clothes.
--
-- Zomboid's own controls are hidden rather than removed, since the rest of
-- its character creation still reaches for them; the designer is a panel of
-- its own in the clothing column, and it keeps the character being made
-- dressed in the chosen look, which the character starts the game wearing.

require "OptionScreens/CharacterCreationMain"
require "Gielinor_Body"

local FONT_HGT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local FONT_HGT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local BUTTON_HGT = FONT_HGT_SMALL + 6
local SPACING = 10

local PART_NAMES = {
    hair = "Hair", jaw = "Jaw", torso = "Torso", arms = "Arms", hands = "Hands", legs = "Legs", feet = "Feet",
}
-- The colours a player picks, by their key in GielinorKits.colours.
local COLOURS = {
    {key = "hair", name = "Hair colour"},
    {key = "torso", name = "Torso colour"},
    {key = "legs", name = "Legs colour"},
    {key = "feet", name = "Feet colour"},
    {key = "skin", name = "Skin colour"},
}

GielinorDesigner = {}

local function desc()
    return MainScreen.instance.desc
end

--- The look being made, starting from the body type's default.
local function lookOf(screen)
    local female = desc():isFemale()
    if not screen.gielinorLook or (screen.gielinorLook.type == "B") ~= female then
        screen.gielinorLook = GielinorBody.defaultLook(female)
    end
    return screen.gielinorLook
end

--- Where a part's chosen kit is in its list (0 for none).
local function position(look, part)
    for i, entry in ipairs(GielinorBody.choices(look.type, part)) do
        if entry.kit == look.kits[part] then
            return i
        end
    end
    return 0
end

--- Dresses the character being made in the look, in place of the Zomboid
--- clothes character creation picks for it, and shows it.
function GielinorDesigner.apply(screen)
    if not GielinorBody.available() or not desc() then
        return
    end
    local look = lookOf(screen)
    desc():getWornItems():clear()
    GielinorBody.dressDescription(desc(), look)
    screen:setAvatarFromUI()
    GielinorDesigner.refresh(screen)
end

--- Updates the designer's labels and swatches to the look.
function GielinorDesigner.refresh(screen)
    local ui = screen.gielinorDesigner
    if not ui then
        return
    end
    local look = lookOf(screen)
    for part, row in pairs(ui.parts) do
        local count = #GielinorBody.choices(look.type, part)
        local at = position(look, part)
        row.value:setName(count == 0 and "-" or at == 0 and "None" or (at .. " / " .. count))
    end
    for _, c in ipairs(COLOURS) do
        local r, g, b = GielinorBody.rgb(c.key, look.colours[c.key] or 1)
        ui.colours[c.key].backgroundColor = {r = r, g = g, b = b, a = 1}
    end
end

--- Steps a part to its next or previous style; parts that may be left out
--- (the jaw, and the clothes) step through "none" too, their first option.
local function step(screen, part, by)
    local look = lookOf(screen)
    local choices = GielinorBody.choices(look.type, part)
    if #choices == 0 then
        return
    end
    local bare = GielinorBody.OPTIONAL[part]
    local first = bare and 0 or 1
    local count = #choices - first + 1
    -- Kahlua's % keeps the sign of the number divided, so wrap by hand.
    local at = position(look, part) + by
    if at < first then
        at = at + count
    elseif at > #choices then
        at = at - count
    end
    look.kits[part] = at > 0 and choices[at].kit or nil
    GielinorDesigner.apply(screen)
end

local function onColourPicked(screen, color, mouseUp, key)
    local look = lookOf(screen)
    look.colours[key] = screen.gielinorDesigner.picker.index
    GielinorDesigner.apply(screen)
end

local function pickColour(screen, button, key)
    local ui = screen.gielinorDesigner
    local colours = {}
    for i = 1, #GielinorKits.colours[key] do
        local r, g, b = GielinorBody.rgb(key, i)
        table.insert(colours, {r = r, g = g, b = b})
    end
    local columns = math.min(#colours, 8)
    ui.picker:setColors(colours, columns, math.ceil(#colours / columns))
    ui.picker:setX(button:getAbsoluteX())
    ui.picker:setY(button:getAbsoluteY() + button:getHeight())
    ui.picker:setPickedFunc(onColourPicked, key)
    local r, g, b = GielinorBody.rgb(key, lookOf(screen).colours[key] or 1)
    ui.picker:setInitialColor(ColorInfo.new(r, g, b, 1))
    screen:showColorPicker(ui.picker)
end

--- Randomises the look, as the game's designer can't but Zomboid's does.
local function randomise(screen)
    local look = lookOf(screen)
    for _, part in ipairs(GielinorBody.PARTS) do
        local choices = GielinorBody.choices(look.type, part)
        if #choices > 0 and (part ~= "jaw" or look.type == "A") then
            look.kits[part] = choices[ZombRand(#choices) + 1].kit
        end
    end
    for _, c in ipairs(COLOURS) do
        look.colours[c.key] = ZombRand(#GielinorKits.colours[c.key]) + 1
    end
    GielinorDesigner.apply(screen)
end

--- Builds the designer panel where Zomboid's clothing choices were.
local function build(screen)
    local ui = {parts = {}, colours = {}}
    local panel = ISPanel:new(screen.clothingPanel:getX(), screen.clothingPanel:getY(),
        screen.clothingPanel:getWidth(), screen.clothingPanel:getHeight())
    panel:initialise()
    panel:noBackground()
    screen:addChild(panel)
    ui.panel = panel

    local y = 0
    local function heading(text)
        local label = ISLabel:new(0, y, FONT_HGT_MEDIUM, text, 1, 1, 1, 1, UIFont.Medium, true)
        label:initialise()
        panel:addChild(label)
        y = y + FONT_HGT_MEDIUM + SPACING
    end
    local labelWidth = 0
    for _, name in pairs(PART_NAMES) do
        labelWidth = math.max(labelWidth, getTextManager():MeasureStringX(UIFont.Small, name))
    end
    for _, c in ipairs(COLOURS) do
        labelWidth = math.max(labelWidth, getTextManager():MeasureStringX(UIFont.Small, c.name))
    end
    labelWidth = labelWidth + SPACING
    local valueWidth = getTextManager():MeasureStringX(UIFont.Small, "000 / 000") + SPACING

    heading("Appearance")
    for _, part in ipairs(GielinorBody.PARTS) do
        local label = ISLabel:new(0, y, BUTTON_HGT, PART_NAMES[part], 1, 1, 1, 1, UIFont.Small, true)
        label:initialise()
        panel:addChild(label)
        local back = ISButton:new(labelWidth, y, BUTTON_HGT, BUTTON_HGT, "<", screen, function(target)
            step(target, part, -1)
        end)
        back:initialise()
        panel:addChild(back)
        local value = ISLabel:new(labelWidth + BUTTON_HGT + SPACING, y, BUTTON_HGT, "", 1, 1, 1, 1, UIFont.Small, true)
        value:initialise()
        panel:addChild(value)
        local forward = ISButton:new(labelWidth + BUTTON_HGT + SPACING * 2 + valueWidth, y, BUTTON_HGT, BUTTON_HGT, ">", screen, function(target)
            step(target, part, 1)
        end)
        forward:initialise()
        panel:addChild(forward)
        ui.parts[part] = {value = value}
        y = y + BUTTON_HGT + SPACING / 2
    end

    y = y + SPACING
    heading("Colours")
    for _, c in ipairs(COLOURS) do
        local label = ISLabel:new(0, y, BUTTON_HGT, c.name, 1, 1, 1, 1, UIFont.Small, true)
        label:initialise()
        panel:addChild(label)
        local swatch = ISButton:new(labelWidth, y, BUTTON_HGT * 2, BUTTON_HGT, "", screen, function(target, button)
            pickColour(target, button, c.key)
        end)
        swatch:initialise()
        panel:addChild(swatch)
        ui.colours[c.key] = swatch
        y = y + BUTTON_HGT + SPACING / 2
    end

    ui.picker = ISColorPicker:new(0, 0, nil)
    ui.picker:initialise()
    ui.picker.keepOnScreen = true
    ui.picker.pickedTarget = screen
    ui.picker.resetFocusTo = panel
    screen.gielinorDesigner = ui
end

--- Zomboid's controls for what the designer replaces.
local function hideZomboidChoices(screen)
    for _, element in ipairs(screen.gielinorHidden or {}) do
        element:setVisible(false)
    end
    if screen.clothingPanel then
        screen.clothingPanel:setVisible(false)
    end
    if screen.presetPanel then
        screen.presetPanel:setVisible(false)
    end
    local ui = screen.gielinorDesigner
    if ui and screen.clothingPanel then
        -- Follow the clothing column as the screen resizes.
        ui.panel:setX(screen.clothingPanel:getX())
        ui.panel:setY(screen.clothingPanel:getY())
        ui.panel:setWidth(screen.clothingPanel:getWidth())
        ui.panel:setHeight(screen.clothingPanel:getHeight())
    end
end

--- Runs one of Zomboid's section builders and hides what it adds, closing
--- the gap it would leave.
local function hideSection(screen, builder)
    local before = {}
    for _, child in pairs(screen.characterPanel.children) do
        before[child] = true
    end
    local y = screen.yOffset
    builder(screen)
    screen.gielinorHidden = screen.gielinorHidden or {}
    for _, child in pairs(screen.characterPanel.children) do
        if not before[child] then
            child:setVisible(false)
            table.insert(screen.gielinorHidden, child)
        end
    end
    screen.yOffset = y
end

local original = {
    create = CharacterCreationMain.create,
    createBodyTypeBtn = CharacterCreationMain.createBodyTypeBtn,
    createHairTypeBtn = CharacterCreationMain.createHairTypeBtn,
    createBeardTypeBtn = CharacterCreationMain.createBeardTypeBtn,
    randomGenericOutfit = CharacterCreationMain.randomGenericOutfit,
    onGenderSelected = CharacterCreationMain.onGenderSelected,
    onRandomCharacter = CharacterCreationMain.onRandomCharacter,
    prerender = CharacterCreationMain.prerender,
}

local function active()
    return GielinorBody.available()
end

function CharacterCreationMain:createBodyTypeBtn()
    if not active() then return original.createBodyTypeBtn(self) end
    hideSection(self, original.createBodyTypeBtn)
end

function CharacterCreationMain:createHairTypeBtn()
    if not active() then return original.createHairTypeBtn(self) end
    hideSection(self, original.createHairTypeBtn)
end

function CharacterCreationMain:createBeardTypeBtn()
    if not active() then return original.createBeardTypeBtn(self) end
    hideSection(self, original.createBeardTypeBtn)
end

function CharacterCreationMain:create()
    original.create(self)
    if active() then
        build(self)
        hideZomboidChoices(self)
    end
end

-- Every time Zomboid would dress the character in clothes of its own (on
-- showing the screen, on a change of body type, for a profession), it wears
-- its look instead.
function CharacterCreationMain:randomGenericOutfit()
    if not active() then return original.randomGenericOutfit(self) end
    GielinorDesigner.apply(self)
end

-- A change of body type also refreshes the preview from Zomboid's hair
-- choice, so the look goes back on after it.
function CharacterCreationMain:onGenderSelected(combo)
    original.onGenderSelected(self, combo)
    if active() then
        GielinorDesigner.apply(self)
    end
end

function CharacterCreationMain:onRandomCharacter()
    original.onRandomCharacter(self)
    if active() then
        randomise(self)
    end
end

-- Zomboid shows its controls again as the body type changes, so they're
-- hidden every frame.
function CharacterCreationMain:prerender()
    original.prerender(self)
    if active() then
        hideZomboidChoices(self)
    end
end

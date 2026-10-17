-- The main menu's build window. Project Gielinor plays with a content pack
-- built on this computer from the game's own files (none of them ship with
-- the mod). The mod's Java, which ZombieBuddy loads, runs the builder: this
-- window offers a build when the pack is missing or was built by an older
-- version of the mod, shows how it's going, and when it's done switches the
-- pack on and reloads the mods, as closing the Mods screen does.

require "ISUI/ISPanel"
require "ISUI/ISButton"

local PACK_ID = "ProjectGielinorContent"
local MOD_ID = "ProjectGielinor"

local FONT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local FONT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local WIDTH = 560
local PADDING = 16
local BUTTON_WIDTH = 120
local BUTTON_HEIGHT = FONT_SMALL + 8
-- How often a running build is asked how it's going (milliseconds).
local POLL = 250

GielinorBuildWindow = ISPanel:derive("GielinorBuildWindow")

--- `text` broken into lines no wider than `width` in `font` (a line break starts a new paragraph).
local function wrap(text, font, width)
    local lines = {}
    for paragraph in string.gmatch(text .. "\n", "(.-)\n") do
        local line = ""
        for word in string.gmatch(paragraph, "%S+") do
            local longer = line == "" and word or line .. " " .. word
            if line ~= "" and getTextManager():MeasureStringX(font, longer) > width then
                table.insert(lines, line)
                line = word
            else
                line = longer
            end
        end
        table.insert(lines, line)
    end
    return lines
end

--- Minutes and seconds, as 4:05.
local function clock(ms)
    local seconds = math.floor(ms / 1000)
    local s = seconds - math.floor(seconds / 60) * 60
    return math.floor(seconds / 60) .. ":" .. (s < 10 and "0" or "") .. s
end

local function packActive()
    return getActivatedMods():contains(PACK_ID)
end

--- Switches the pack on (loading before this mod) and reloads the mods.
local function switchOnAndReload()
    local defaultMods = ActiveMods.getById("default")
    if not defaultMods:isModActive(PACK_ID) then
        local mods = defaultMods:getMods()
        local before = mods:indexOf(MOD_ID)
        if before >= 0 then
            mods:add(before, PACK_ID)
        else
            defaultMods:setModActive(PACK_ID, true)
        end
    end
    saveModsFile()
    ActiveMods.getById("currentGame"):copyFrom(defaultMods)
    if MainScreen.instance and MainScreen.instance.bottomPanel then
        MainScreen.instance.bottomPanel:setVisible(false)
    end
    getCore():ResetLua("default", "modsChanged")
end

function GielinorBuildWindow:new()
    local o = ISPanel.new(self, 0, 0, WIDTH, 200)
    o.backgroundColor = {r = 0, g = 0, b = 0, a = 0.9}
    o.borderColor = {r = 0.4, g = 0.4, b = 0.4, a = 1}
    o.lines = {}
    o.buttons = {}
    o.moveWithMouse = true
    return o
end

--- Shows `text` and a button for each of `choices` ({label, function}), first one on the right.
function GielinorBuildWindow:show(text, choices)
    for _, button in ipairs(self.buttons) do
        self:removeChild(button)
    end
    self.buttons = {}
    self.lines = wrap(text, UIFont.Small, WIDTH - PADDING * 2)
    local height = PADDING + FONT_MEDIUM + PADDING + #self.lines * FONT_SMALL + PADDING
    if self.building then
        height = height + FONT_SMALL * 2 + PADDING
    end
    if #choices > 0 then
        height = height + BUTTON_HEIGHT + PADDING
    end
    self:setHeight(height)
    self:setX((getCore():getScreenWidth() - WIDTH) / 2)
    self:setY((getCore():getScreenHeight() - height) / 2)
    local x = WIDTH - PADDING - BUTTON_WIDTH
    for i, choice in ipairs(choices) do
        local button = ISButton:new(x, height - PADDING - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT, choice[1], self, function()
            choice[2](self)
        end)
        button:initialise()
        button:instantiate()
        if i == 1 then
            button:enableAcceptColor()
        end
        self:addChild(button)
        table.insert(self.buttons, button)
        x = x - BUTTON_WIDTH - PADDING / 2
    end
end

function GielinorBuildWindow:close()
    self:setVisible(false)
    self:removeFromUIManager()
    GielinorBuildWindow.instance = nil
end

function GielinorBuildWindow:startBuild()
    if gielinorBuildContent() or gielinorContentState() == "building" then
        self.started = getTimestampMs()
        self:watch()
    else
        self:offer(gielinorContentState())
    end
end

--- Follows a running build.
function GielinorBuildWindow:watch()
    self.building = true
    self.started = self.started or getTimestampMs()
    self:show("Building the content pack: Gielinor's items, people, creatures and world, from the game's own files. "
        .. "The first build also downloads those files (about 190 MB). It takes several minutes, and the mods reload "
        .. "when it's done.", {})
end

--- Asks to build, or says why it can't, for the pack's state.
function GielinorBuildWindow:offer(state)
    self.building = false
    local build = {"Build now", GielinorBuildWindow.startBuild}
    local later = {"Not now", GielinorBuildWindow.close}
    if state == "missing" then
        self:show("Project Gielinor plays with a content pack built on this computer from the game's own files: "
            .. "Gielinor's items, people, creatures and world. None of it comes with the mod.\n\n"
            .. "Building it downloads the game's files once (about 190 MB) from the OpenRS2 archive and takes "
            .. "several minutes.", {build, later})
    elseif state == "outdated" then
        self:show("Project Gielinor has been updated, so its content pack needs building again. This takes a few "
            .. "minutes. Worlds started with the old pack might not load with the new one.", {build, later})
    elseif state == "failed" then
        self:show("The build didn't finish: " .. gielinorBuildProgress() .. "\n\nEverything it printed is in "
            .. gielinorBuildLog() .. ".", {{"Try again", GielinorBuildWindow.startBuild}, {"Close", GielinorBuildWindow.close}})
    end
end

function GielinorBuildWindow:update()
    ISPanel.update(self)
    if not self.building or getTimestampMs() - (self.polled or 0) < POLL then
        return
    end
    self.polled = getTimestampMs()
    local state = gielinorContentState()
    if state == "ready" then
        self.building = false
        self:close()
        if gielinorMayReload() then
            switchOnAndReload()
        end
    elseif state ~= "building" then
        self:offer(state)
    end
end

function GielinorBuildWindow:prerender()
    ISPanel.prerender(self)
    local y = PADDING
    self:drawText("Project Gielinor", PADDING, y, 1, 1, 1, 1, UIFont.Medium)
    y = y + FONT_MEDIUM + PADDING
    for _, line in ipairs(self.lines) do
        self:drawText(line, PADDING, y, 0.9, 0.9, 0.9, 1, UIFont.Small)
        y = y + FONT_SMALL
    end
    if self.building then
        y = y + PADDING
        self:drawText(gielinorBuildProgress(), PADDING, y, 1, 0.85, 0.4, 1, UIFont.Small)
        self:drawText(clock(getTimestampMs() - self.started), PADDING, y + FONT_SMALL, 0.6, 0.6, 0.6, 1, UIFont.Small)
    end
end

local function open()
    if GielinorBuildWindow.instance then
        GielinorBuildWindow.instance:close()
    end
    local window = GielinorBuildWindow:new()
    window:initialise()
    window:addToUIManager()
    window:setAlwaysOnTop(true)
    GielinorBuildWindow.instance = window
    return window
end

--- Without the mod's Java (ZombieBuddy missing, or told not to load it): why, unless the pack is there anyway.
local function noJava()
    if packActive() then
        return
    end
    local status = ZombieBuddy and ZombieBuddy.getJavaModStatus and ZombieBuddy.getJavaModStatus(MOD_ID)
    local text
    if not ZombieBuddy then
        text = "Project Gielinor builds its content pack with ZombieBuddy, and ZombieBuddy's Java part isn't installed. "
            .. "Install it as ZombieBuddy's Workshop page describes, then restart the game."
    elseif status and not status.loaded then
        text = "ZombieBuddy didn't load Project Gielinor's Java (" .. tostring(status.reason) .. "), which builds the "
            .. "content pack. Restart the game holding Shift to be asked again."
    else
        text = "Project Gielinor's Java, which builds its content pack, didn't load. Check that ZombieBuddy is "
            .. "installed and up to date, then restart the game."
    end
    open():show(text, {{"OK", GielinorBuildWindow.close}})
end

local function onMainMenuEnter()
    if not gielinorContentState then
        noJava()
        return
    end
    local state = gielinorContentState()
    if state == "building" then
        open():watch()
    elseif state == "ready" then
        -- Built earlier but switched off (or never switched on): switch it on.
        if not packActive() and gielinorMayReload() then
            switchOnAndReload()
        end
    else
        open():offer(state)
    end
end

Events.OnMainMenuEnter.Add(onMainMenuEnter)

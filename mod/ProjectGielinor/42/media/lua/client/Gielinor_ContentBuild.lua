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
local WIDTH = 640
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

--- A whole number with thousands separators, as 426,536.
local function commas(n)
    local text = tostring(math.floor(n))
    while true do
        local grouped, count = string.gsub(text, "^(%d+)(%d%d%d)", "%1,%2")
        text = grouped
        if count == 0 then return text end
    end
end

-- The build's stages, in order, as the builder reports them (its step lines,
-- lower-cased, contain one of `steps`). `weight` is the stage's rough share
-- of the whole build, for the overall bar; drawing the world is most of it.
local STAGES = {
    {label = "Download the game's data", note = "first build only, about 190 MB", weight = 4, steps = {"downloading"}},
    {label = "Items, people and creatures", note = "icons, models, clothing and zombies", weight = 14,
        steps = {"zomboid's items", "game's items", "choosing items", "items:", "clothing", "animals"}},
    {label = "Check the world recipe", note = "the map's layout and drawing instructions", weight = 1,
        steps = {"checking the world recipe", "already built", "rebuilding the damaged"}},
    {label = "Draw the world", note = "every wall, roof, tree and floor; the longest step", weight = 66,
        steps = {"drawing"}},
    {label = "Pack the textures", note = "into the game's texture and depth sheets", weight = 10, steps = {"writing the world"}},
    {label = "Lay out the map", note = "map cells, rooms, doors and lights", weight = 2, steps = {"laying out"}},
    {label = "Finish", note = "combine everything and switch it on", weight = 3,
        steps = {"combining", "putting the pack", "done"}},
}
local DRAW = 4
local ROW = FONT_SMALL + 4
local BAR = 12

--- Which stage a step line belongs to, or nil.
local function stageOf(step)
    local lower = string.lower(step or "")
    for i, stage in ipairs(STAGES) do
        for _, part in ipairs(stage.steps) do
            if string.find(lower, part, 1, true) then return i end
        end
    end
    return nil
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
        -- Stage checklist, the overall bar, then the step and times.
        height = height + #STAGES * ROW + PADDING + BAR + PADDING / 2 + FONT_SMALL * 2 + PADDING
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
    self.stage = self.stage or 1
    self:show("Your computer is drawing Gielinor. RuneScape's art belongs to Jagex, so it can't come with the mod; "
        .. "instead the builder draws every building, tree and floor from the official game data, which takes a while. "
        .. "It only happens once, and again only when an update changes the map.\n\n"
        .. "Keep the game open until it finishes. You can use other programs meanwhile; the mods reload by themselves "
        .. "when it's done.", {})
end

--- Follows the builder's latest step: which stage it's on, the drawing count and the download size.
function GielinorBuildWindow:track(step)
    local stage = stageOf(step)
    if stage and stage > self.stage then
        self.stage = stage
    end
    if string.find(step, "already built", 1, true) then
        self.reused = true
    end
    local done, total = string.match(step, "(%d+) / (%d+) sprites")
    if done then
        done, total = tonumber(done), tonumber(total)
        local now = getTimestampMs()
        if not self.drawStart then
            self.drawStart, self.drawFrom = now, done
        end
        self.drawn, self.toDraw = done, total
        -- Time left from the drawing rate so far, plus the packing and map steps after it.
        if done > self.drawFrom and now - self.drawStart > 20000 then
            local rate = (done - self.drawFrom) / (now - self.drawStart)
            self.left = (total - done) / rate * 1.2 + 60000
        end
    end
    local got, size = string.match(step, "(%d+) of (%d+) MB")
    if got then
        self.downloaded, self.downloadSize = tonumber(got), tonumber(size)
    end
end

--- The whole build's progress, 0 to 1: finished stages by weight, plus the share of the current one done.
function GielinorBuildWindow:fraction()
    local all, done = 0, 0
    for i, stage in ipairs(STAGES) do
        all = all + stage.weight
        if i < self.stage then
            done = done + stage.weight
        elseif i == self.stage then
            if i == DRAW and self.toDraw and self.toDraw > 0 then
                done = done + stage.weight * self.drawn / self.toDraw
            elseif i == 1 and self.downloadSize and self.downloadSize > 0 then
                done = done + stage.weight * self.downloaded / self.downloadSize
            end
        end
    end
    return math.min(1, done / all)
end

--- Asks to build, or says why it can't, for the pack's state.
function GielinorBuildWindow:offer(state)
    self.building = false
    local build = {"Build now", GielinorBuildWindow.startBuild}
    local later = {"Not now", GielinorBuildWindow.close}
    if state == "missing" then
        self:show("Before you can play, Project Gielinor needs to build its content: Gielinor's items, people, "
            .. "creatures and the whole world map.\n\n"
            .. "Why it's built here: RuneScape's art belongs to Jagex, so the mod can't include it. Your computer "
            .. "downloads the official game data once (about 190 MB, from the OpenRS2 archive) and draws everything "
            .. "from it, the way the game itself does.\n\n"
            .. "How long: about 20 minutes on a fast PC, longer on a slower one. It only happens once, and again only "
            .. "when an update changes the map. Keep the game open until it finishes; you'll see each step as it goes.",
            {build, later})
    elseif state == "outdated" then
        self:show("Project Gielinor has been updated, so its content needs building again. If the update didn't "
            .. "change the map, the world drawn last time is reused and this is quick; if it did, the world is drawn "
            .. "again (about 20 minutes on a fast PC). Start a new world after updating.", {build, later})
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
    self:track(gielinorBuildProgress())
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
        self:drawProgress(y + PADDING)
    end
end

--- The stage checklist, the overall bar, the current step and the times.
function GielinorBuildWindow:drawProgress(y)
    local width = WIDTH - PADDING * 2
    local mark = FONT_SMALL - 6
    for i, stage in ipairs(STAGES) do
        local done = i < self.stage
        local current = i == self.stage
        local skipped = self.reused and i > 3 and i < 7
        -- A filled square for done, a bright one for the current stage, an outline for those still to come.
        if done or skipped then
            self:drawRect(PADDING, y + 3, mark, mark, 1, 0.35, 0.75, 0.4)
        elseif current then
            self:drawRect(PADDING, y + 3, mark, mark, 1, 1, 0.8, 0.3)
        else
            self:drawRectBorder(PADDING, y + 3, mark, mark, 1, 0.45, 0.45, 0.45)
        end
        local shade = (done or current or skipped) and 1 or 0.55
        local label = stage.label
        if skipped then
            label = label .. " (reused from last time)"
        elseif current and i == DRAW and self.toDraw then
            label = label .. ": " .. commas(self.drawn) .. " of " .. commas(self.toDraw)
        elseif current and i == 1 and self.downloadSize then
            label = label .. ": " .. self.downloaded .. " of " .. self.downloadSize .. " MB"
        end
        self:drawText(label, PADDING + mark + 8, y, shade, shade, shade, 1, UIFont.Small)
        local labelWidth = getTextManager():MeasureStringX(UIFont.Small, label)
        local noteX = PADDING + mark + 16 + labelWidth
        if not skipped and noteX + getTextManager():MeasureStringX(UIFont.Small, stage.note) <= WIDTH - PADDING then
            self:drawText(stage.note, noteX, y, 0.5, 0.5, 0.5, 1, UIFont.Small)
        end
        y = y + ROW
    end
    y = y + PADDING
    local fraction = self:fraction()
    self:drawRect(PADDING, y, width, BAR, 1, 0.15, 0.15, 0.15)
    self:drawRect(PADDING, y, math.max(1, width * fraction), BAR, 1, 0.35, 0.75, 0.4)
    self:drawRectBorder(PADDING, y, width, BAR, 1, 0.4, 0.4, 0.4)
    y = y + BAR + PADDING / 2
    self:drawText(gielinorBuildProgress(), PADDING, y, 1, 0.85, 0.4, 1, UIFont.Small)
    local times = math.floor(fraction * 100) .. "% done, " .. clock(getTimestampMs() - self.started) .. " so far"
    if self.stage > DRAW or self.reused then
        times = times .. ", nearly done"
    elseif self.left then
        times = times .. ", about " .. math.max(1, math.ceil(self.left / 60000)) .. " min left"
    else
        times = times .. ", time left shows once drawing is under way"
    end
    self:drawText(times, PADDING, y + FONT_SMALL, 0.6, 0.6, 0.6, 1, UIFont.Small)
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

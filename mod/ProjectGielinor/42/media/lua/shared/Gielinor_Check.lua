-- Reports at startup how many Gielinor items the game registered, so a
-- missing or broken content pack shows up in the console log.

local function report()
    local all = getScriptManager():getAllItems()
    local count = 0
    for i = 0, all:size() - 1 do
        if all:get(i):getModuleName() == "Gielinor" then
            count = count + 1
        end
    end
    if count == 0 then
        print("Project Gielinor: no items found. Build the content pack from the main menu.")
    else
        print("Project Gielinor: " .. count .. " items registered; e.g. " .. tostring(getItemNameFromFullType("Gielinor.BronzeAxe")))
    end
end

Events.OnGameBoot.Add(report)

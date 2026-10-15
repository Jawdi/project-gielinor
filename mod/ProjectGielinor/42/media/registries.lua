-- Read before item scripts are: the slots a character's Gielinor body is worn
-- in, part by part, beneath all other clothing.
for _, part in ipairs({"head", "torso", "arms", "hands", "legs", "feet"}) do
    ItemBodyLocation.register("gielinor:" .. part)
end

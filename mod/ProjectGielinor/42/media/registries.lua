-- Read before item scripts are: the slots a character's Gielinor body is worn
-- in, beneath all other clothing. Each body part has four: its own colours,
-- its skin, and the colour its owner chose (hair, clothing or shoes) in two
-- shades.
for _, part in ipairs({"hair", "jaw", "torso", "arms", "hands", "legs", "feet"}) do
    ItemBodyLocation.register("gielinor:" .. part)
    ItemBodyLocation.register("gielinor:" .. part .. "_skin")
    ItemBodyLocation.register("gielinor:" .. part .. "_colour")
    ItemBodyLocation.register("gielinor:" .. part .. "_colour2")
end

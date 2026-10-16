-- What turns up in containers and on zombies. These are the switches a
-- sandbox option can set later; the loot rules read nothing else.

GielinorLootSettings = GielinorLootSettings or {
    -- Gielinor's items in Zomboid's loot.
    gielinorItems = true,
    -- Zomboid's own items in its loot. Off, containers and zombies carry
    -- only Gielinor's (anything the switch can't apply to is left alone).
    zomboidItems = true,
    -- Scales how many Gielinor items turn up: 2 is twice as many.
    gielinorAmount = 1.0,
}

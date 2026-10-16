-- Gielinor as a game to start from the New Game screen. The world is a
-- standalone map the content pack builds (media/maps/Gielinor); the game's
-- challenges are how a game is started in a world other than Kentucky, so
-- Gielinor is offered as one. Servers set Map=Gielinor instead.

GielinorWorld = {}

GielinorWorld.id = "Gielinor"
GielinorWorld.gameMode = "Gielinor"
GielinorWorld.world = "Gielinor"
-- Where new characters start until the map has spawn points of its own.
GielinorWorld.x = 128
GielinorWorld.y = 128
GielinorWorld.z = 0
GielinorWorld.hourOfDay = 8

function GielinorWorld.OnInitWorld()
end

function GielinorWorld.Render()
end

function GielinorWorld.onBackButtonWheel(playerNum, dir)
end

function GielinorWorld.AddPlayer(playerNum, playerObj)
end

function GielinorWorld.RemovePlayer(playerObj)
end

Events.OnChallengeQuery.Add(function()
    addChallenge(GielinorWorld)
end)

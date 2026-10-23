if not GielinorImportedWorld then return end

-- A supported endpoint transfer; imported meshes are not continuous native stair ramps.
require "TimedActions/ISBaseTimedAction"
require "TimedActions/ISTimedActionQueue"
require "OriginalGeometry_StairLinks"
OriginalGeometryStairs = {}
local API=OriginalGeometryStairs
OriginalGeometryUseStairs=ISBaseTimedAction:derive("OriginalGeometryUseStairs")
local function supported(square) return square and square:TreatAsSolidFloor() and square:isFree(false) end
local function near(character,stair)
    local x,y=character:getX(),character:getY()
    local dx=math.max(stair.x-x,0,x-(stair.x+stair.width))
    local dy=math.max(stair.y-y,0,y-(stair.y+stair.depth))
    return math.floor(character:getZ())==stair.z and dx*dx+dy*dy<=4
end
local function stairOf(object)
    local square=object and object:getSquare()
    local sprite=object and object:getSprite()
    local props=sprite and sprite:getProperties()
    if not square or not props or not props:get("OriginalStairWay") then return nil end
    local dx,dy,dz=tonumber(props:get("OriginalStairDX")),tonumber(props:get("OriginalStairDY")),tonumber(props:get("OriginalStairDZ"))
    if not dx or not dy or not dz then return nil end
    local stair={square=square,x=square:getX()-dx,y=square:getY()-dy,z=square:getZ()-dz,width=tonumber(props:get("OriginalStairWidth")) or 2,depth=tonumber(props:get("OriginalStairDepth")) or 2}
    stair.links=OriginalGeometryStairLinks[stair.x..","..stair.y..","..stair.z..","..props:get("OriginalStairSource")]
    return stair
end
function OriginalGeometryUseStairs:isValid() return near(self.character,self.stair) and supported(self.target) end
function OriginalGeometryUseStairs:update() self.character:faceLocation(self.stair.square:getX()+.5,self.stair.square:getY()+.5) end
function OriginalGeometryUseStairs:start() end
function OriginalGeometryUseStairs:stop() ISBaseTimedAction.stop(self) end
function OriginalGeometryUseStairs:perform()
    if self:isValid() then self.character:teleportTo(self.target:getX()+.5,self.target:getY()+.5,self.target:getZ()) end
    ISBaseTimedAction.perform(self)
end
function OriginalGeometryUseStairs:complete() return true end
function OriginalGeometryUseStairs:getDuration() return 45 end
function OriginalGeometryUseStairs:new(character,stair,target)
    local o=ISBaseTimedAction.new(self,character)
    o.stair,o.target,o.maxTime=stair,target,45
    o.stopOnWalk,o.stopOnRun=true,true
    return o
end
local function approach(character,stair)
    local best,score=nil,math.huge
    for x=stair.x-1,stair.x+stair.width do for y=stair.y-1,stair.y+stair.depth do
        local outsideX=x<stair.x or x>=stair.x+stair.width
        local outsideY=y<stair.y or y>=stair.y+stair.depth
        if not (outsideX and outsideY) then
            local square=getCell():getGridSquare(x,y,stair.z)
            local edgeX=math.max(stair.x,math.min(x,stair.x+stair.width-1))
            local edgeY=math.max(stair.y,math.min(y,stair.y+stair.depth-1))
            local edge=getCell():getGridSquare(edgeX,edgeY,stair.z)
            if supported(square) and (not edge or square==edge or not square:isWallTo(edge)) then
                local dx,dy=x+.5-character:getX(),y+.5-character:getY()
                local distance=dx*dx+dy*dy
                if distance<score then best,score=square,distance end
            end
        end
    end end
    return best
end
local function use(character,stair,position)
    local target=getCell():getGridSquare(position[1],position[2],position[3])
    if not supported(target) then character:setHaloNote("The stair landing is blocked") return end
    local start=approach(character,stair)
    if start and luautils.walk(character,start,true) then
        ISTimedActionQueue.add(OriginalGeometryUseStairs:new(character,stair,target))
    else character:setHaloNote("Cannot reach the stairs") end
end
API.approach=approach
API.use=use
local movement=setmetatable({},{__mode="k"})
local function footprintDistance(stair,x,y)
    local dx=math.max(stair.x-x,0,x-(stair.x+stair.width))
    local dy=math.max(stair.y-y,0,y-(stair.y+stair.depth))
    return dx*dx+dy*dy
end
function API.onMove(character)
    local x,y,z=character:getX(),character:getY(),math.floor(character:getZ())
    local old=movement[character]
    movement[character]={x=x,y=y,z=z,arrival=old and old.arrival}
    if not old or old.z~=z then return end
    if old.arrival then
        if (x-old.arrival.x)^2+(y-old.arrival.y)^2<2.25 then return end
        movement[character].arrival=nil
    end
    if (x-old.x)^2+(y-old.y)^2<.0001 then return end
    local seen={}
    for sx=math.floor(x)-2,math.floor(x)+2 do for sy=math.floor(y)-2,math.floor(y)+2 do
        local square=getCell():getGridSquare(sx,sy,z)
        local objects=square and square:getObjects()
        if objects then for i=0,objects:size()-1 do
            local stair=stairOf(objects:get(i))
            if stair and stair.z==z and stair.links then
                local key=stair.x..","..stair.y..","..stair.z
                if not seen[key] then
                    seen[key]=true
                    -- Ambiguous multi-direction landings retain explicit menu choices.
                    -- Stepping into the stair's footprint climbs it; walking past or around it doesn't.
                    local position=stair.links.up and not stair.links.down and stair.links.up or stair.links.down and not stair.links.up and stair.links.down
                    if position and footprintDistance(stair,x,y)==0 and footprintDistance(stair,old.x,old.y)>0 then
                        local target=getCell():getGridSquare(position[1],position[2],position[3])
                        if supported(target) then
                            character:teleportTo(target:getX()+.5,target:getY()+.5,target:getZ())
                            movement[character]={x=target:getX()+.5,y=target:getY()+.5,z=target:getZ(),arrival={x=target:getX()+.5,y=target:getY()+.5}}
                            return
                        end
                    end
                end
            end
        end end
    end end
end
Events.OnPlayerMove.Add(API.onMove)
Events.OnFillWorldObjectContextMenu.Add(function(player,context,worldobjects,test)
    local character=getSpecificPlayer(player)
    if not character then return end
    local found
    for _,object in ipairs(worldobjects) do
        local candidate=stairOf(object)
        if candidate and candidate.z==math.floor(character:getZ()) and candidate.links then found=candidate break end
    end
    if not found then
        for _,object in ipairs(worldobjects) do
            local square=object:getSquare()
            if square then
                for dx=-1,1 do for dy=-1,1 do
                    local nearby=getCell():getGridSquare(square:getX()+dx,square:getY()+dy,math.floor(character:getZ()))
                    if nearby then
                        local objects=nearby:getObjects()
                        for i=0,objects:size()-1 do
                            local candidate=stairOf(objects:get(i))
                            if candidate and candidate.z==math.floor(character:getZ()) and candidate.links then found=candidate break end
                        end
                    end
                    if found then break end
                end if found then break end end
            end
            if found then break end
        end
    end
    if not found or not found.links or not (found.links.up or found.links.down) then return end
    if test then return ISWorldObjectContextMenu.setTest() end
    if found.links.up then context:addOption("Go upstairs",character,use,found,found.links.up) end
    if found.links.down then context:addOption("Go downstairs",character,use,found,found.links.down) end
end)

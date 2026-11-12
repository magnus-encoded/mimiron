-- Runs addon/Mimiron/Link.lua under a stub WoW API and prints the 64 cells it draws,
-- as EDN [[r g b] ...] in 0..255. Regenerate the fixtures after a wire format change:
--   lua5.1 test/fixtures/draw-strip.lua addon/Mimiron/Link.lua <session> <combat 0|1> <saved 0|1> <names,...>
--   link-strip.edn                 42 1 0 Bjørn,Ana
--   link-strip-overflow.edn        7 0 0 Aerin,Bjørn,Cassia,Dagny,Eluned,Fenwick,Gorrim,Halvard,Isolde,Jorund,Kestrel,Liora,Mordecai,Nyssa,Orrin,Peregrin,Quillon,Ragna,Sigrun,Thalric
--   link-strip-overflow-saved.edn  8 0 1 (the same 20 names)
-- saved 1 means the names were written last session, so no reload is wanted.
-- The link is off by default, so the harness turns it on as /mimiron link on would.
local linkFile, session, combat, saved, list = arg[1], tonumber(arg[2]), arg[3] == "1", arg[4] == "1", arg[5] or ""
local party = {}
for name in string.gmatch(list, "[^,]+") do party[#party + 1] = name end
local raid = #party > 4

local function stub() return setmetatable({}, { __index = function() return function() end end }) end
local textures, frames = {}, {}
function CreateFrame()
    local f = stub()
    local scripts = {}
    f.CreateTexture = function()
        local t = stub()
        t.SetColorTexture = function(_, r, g, b) t.rgb = { r, g, b } end
        textures[#textures + 1] = t
        return t
    end
    f.SetScript = function(_, k, fn) scripts[k] = fn end
    f.fire = function(_, ...) if scripts.OnEvent then scripts.OnEvent(f, ...) end end
    frames[#frames + 1] = f
    return f
end
function GetPhysicalScreenSize() return 1920, 1080 end
function IsInRaid() return raid end
function GetNumGroupMembers() return #party end
function GetNumSubgroupMembers() return #party end
function UnitName(u)
    if u == "player" then return "Me" end
    return party[tonumber(u:match("%d+"))]
end
function InCombatLockdown() return combat end
function UnitAffectingCombat() return combat end
function UnitCastingInfo() return nil end
function UnitChannelInfo() return nil end
math.random = function() return session end

MimironDB = { link = true }
if saved then
    local names = {}
    for i, n in ipairs(party) do names[i] = n end
    table.sort(names)
    MimironDB.linknames = names
end
dofile(linkFile)
frames[#frames]:fire("PLAYER_LOGIN")

local out = {}
for i, t in ipairs(textures) do
    out[i] = string.format("[%d %d %d]", math.floor(t.rgb[1] * 255 + 0.5),
        math.floor(t.rgb[2] * 255 + 0.5), math.floor(t.rgb[3] * 255 + 0.5))
end
print("[" .. table.concat(out, " ") .. "]")

-- Game link: a strip of coloured cells in the top-left corner that tehuti reads and
-- hands to the Mimiron plugin. State sync only: nothing here acts in the game.
--
-- Wire format (mirrored in src/mimiron/link.clj; change both together):
--   64 cells, each CELL x CELL physical pixels, left to right from the top-left corner.
--   A cell carries 3 nibbles as R, G, B, each drawn as nibble * 17 / 255.
--   Byte i is nibbles 2i (high) and 2i+1 (low); 96 bytes in all.
--   [0] 0x4D 'M'  [1] 0x31 version  [2] flags  [3] session  [4] revision  [5] length
--   [6 .. 6+length) payload, UTF-8 "p=Name,Name"   [6+length] checksum (sum of all before, mod 256)
-- Flags: 1 in combat, 2 chat box open, 4 wants a reload, 8 busy (casting, a trade or shop window,
-- a cinematic).
-- Names that don't fit go to MimironDB.linknames and set "wants a reload"; Mimiron
-- types /reload once it is safe, then reads them from the SavedVariables file.

local CELL, CELLS, BYTES = 4, 64, 96
local MAX_PAYLOAD = BYTES - 7
local FLAG_COMBAT, FLAG_CHAT, FLAG_RELOAD, FLAG_BUSY = 1, 2, 4, 8

local session = math.random(1, 255)
local revision = 0
local payload = ""
local wantsReload = false

local function db()
    if type(MimironDB) ~= "table" then MimironDB = {} end
    return MimironDB
end

-- Off unless the player turns it on with /mimiron link on (a fresh MimironDB means off).
local function linkOn() return db().link == true end

local frame = CreateFrame("Frame", "MimironLinkFrame")
frame:SetFrameStrata("TOOLTIP")
frame:SetPoint("TOPLEFT", nil, "TOPLEFT", 0, 0)
frame:SetSize(CELL * CELLS, CELL)
local cells = {}
for i = 1, CELLS do
    local t = frame:CreateTexture(nil, "OVERLAY")
    pcall(function() t:SetSnapToPixelGrid(false); t:SetTexelSnappingBias(0) end)
    t:SetPoint("TOPLEFT", frame, "TOPLEFT", (i - 1) * CELL, 0)
    t:SetSize(CELL, CELL)
    cells[i] = t
end

-- One UI unit is one physical pixel, whatever the UI scale.
local function fitToPixels()
    local ok, _, height = pcall(GetPhysicalScreenSize)
    if ok and height and height > 0 then frame:SetScale(768 / height) end
end

local function partyNames()
    local names = {}
    pcall(function()
        local raid = IsInRaid and IsInRaid()
        local count = raid and GetNumGroupMembers() or GetNumSubgroupMembers()
        for i = 1, count do
            local name = UnitName((raid and "raid" or "party") .. i)
            -- "Unknown" is WoW's placeholder until the name loads; UNIT_NAME_UPDATE brings it.
            if name and name ~= UnitName("player") and name ~= (UNKNOWNOBJECT or "Unknown") then
                names[#names + 1] = name
            end
        end
    end)
    table.sort(names)
    return names
end

local function sameList(a, b)
    if type(a) ~= "table" or type(b) ~= "table" or #a ~= #b then return false end
    for i = 1, #a do if a[i] ~= b[i] then return false end end
    return true
end

local function shown(f) return f and f.IsShown and f:IsShown() end

local function flags()
    local value = 0
    if InCombatLockdown() or UnitAffectingCombat("player") then value = value + FLAG_COMBAT end
    local chat = false
    pcall(function()
        local box = ChatEdit_GetActiveWindow and ChatEdit_GetActiveWindow()
        chat = box ~= nil and box:IsShown()
    end)
    if chat then value = value + FLAG_CHAT end
    if wantsReload then value = value + FLAG_RELOAD end
    local busy = false
    pcall(function()
        busy = UnitCastingInfo("player") ~= nil or UnitChannelInfo("player") ~= nil
            or shown(CinematicFrame) or shown(MovieFrame) or shown(TradeFrame)
            or shown(MailFrame) or shown(MerchantFrame) or shown(BankFrame)
            or shown(AuctionFrame) or shown(AuctionHouseFrame) or shown(StaticPopup1)
    end)
    if busy then value = value + FLAG_BUSY end
    return value
end

local function encode()
    local bytes = { 0x4D, 0x31, flags(), session, revision % 256, #payload }
    for i = 1, #payload do bytes[#bytes + 1] = string.byte(payload, i) end
    local sum = 0
    for i = 1, #bytes do sum = sum + bytes[i] end
    bytes[#bytes + 1] = sum % 256
    while #bytes < BYTES do bytes[#bytes + 1] = 0 end
    return bytes
end

local function draw()
    if not linkOn() then frame:Hide() return end
    frame:Show()
    local bytes = encode()
    local nibbles = {}
    for i = 1, BYTES do
        nibbles[#nibbles + 1] = math.floor(bytes[i] / 16)
        nibbles[#nibbles + 1] = bytes[i] % 16
    end
    for c = 1, CELLS do
        local n = (c - 1) * 3
        cells[c]:SetColorTexture(nibbles[n + 1] * 17 / 255, nibbles[n + 2] * 17 / 255,
            nibbles[n + 3] * 17 / 255, 1)
    end
end

-- Recomputes the payload; names that don't fit wait in SavedVariables for a reload.
local function refresh()
    local names = partyNames()
    local text = "p=" .. table.concat(names, ",")
    local store = db()
    -- Link off: nothing drawn, nothing saved for the plugin, no reload asked for.
    if not linkOn() then
        store.linknames = nil
        wantsReload = false
        payload = ""
        draw()
        return
    end
    if #text <= MAX_PAYLOAD then
        store.linknames = nil
        wantsReload = false
    else
        if not sameList(store.linknames, names) then revision = revision + 1 end
        -- Already on disk from the last session: nothing to write.
        wantsReload = not sameList(store.savedlinknames, names)
        store.linknames = names
        text = "p=+"
    end
    if text ~= payload then payload = text; revision = revision + 1 end
    draw()
end

local events = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "GROUP_ROSTER_UPDATE",
    "UNIT_NAME_UPDATE", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED", "DISPLAY_SIZE_CHANGED", "UI_SCALE_CHANGED" }) do
    pcall(function() events:RegisterEvent(e) end)
end
events:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" then
        -- What was saved last session is on disk now.
        db().savedlinknames = db().linknames
    end
    fitToPixels()
    refresh()
end)

-- Flags (combat, chat box, busy) change without events we can rely on; redraw a few times a second.
local elapsed = 0
frame:SetScript("OnUpdate", function(_, dt)
    elapsed = elapsed + dt
    if elapsed >= 0.25 then elapsed = 0; draw() end
end)

MimironLink = {
    set = function(on)
        db().link = on and true or false
        refresh()
    end,
    on = linkOn,
}

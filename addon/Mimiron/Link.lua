-- Game link: a strip of coloured cells in the top-left corner that tehuti reads and
-- hands to the Mimiron plugin. State sync only: nothing here acts in the game.
--
-- Wire format (mirrored in src/mimiron/link.clj; change both together):
--   64 cells, each CELL x CELL physical pixels, left to right from the top-left corner.
--   A cell carries 3 nibbles as R, G, B, each drawn as nibble * 17 / 255.
--   Byte i is nibbles 2i (high) and 2i+1 (low); 96 bytes in all.
--   [0] 0x4D 'M'  [1] 0x31 version  [2] flags  [3] session  [4] revision  [5] length
--   [6 .. 6+length) payload, UTF-8 "p=Name,Name", or "k=KEY;p=Name,Name" while voice chat is on
--   [6+length] checksum (sum of all before, mod 256)
-- Flags: 1 in combat, 2 chat box open, 4 wants a reload, 8 busy (casting, a trade or shop window,
-- a cinematic), 16 voice chat on (an active channel in push-to-talk mode), 32 voice goes through
-- Discord (the voiceProvider CVar), 64 the player keeps push-to-talk for dictation (/mimiron ptt).
-- KEY is WoW's push-to-talk binding, e.g. "SHIFT-V". Voice state only: never members, names
-- of channels, who is speaking, or audio.
-- Names that don't fit go to MimironDB.linknames and set "wants a reload"; Mimiron
-- types /reload once it is safe, then reads them from the SavedVariables file.

local CELL, CELLS, BYTES = 4, 64, 96
local MAX_PAYLOAD = BYTES - 7
local FLAG_COMBAT, FLAG_CHAT, FLAG_RELOAD, FLAG_BUSY = 1, 2, 4, 8
local FLAG_VOICE, FLAG_DISCORD, FLAG_KEEP_DICTATION = 16, 32, 64

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

-- Voice chat is on when a channel is active and the player talks with push-to-talk;
-- with an open mic the button has nothing to do in voice chat.
local function voiceOn()
    local on = false
    pcall(function()
        local mode = C_VoiceChat.GetCommunicationMode()
        on = C_VoiceChat.GetActiveChannelID() ~= nil
            and mode == (Enum and Enum.CommunicationMode and Enum.CommunicationMode.PushToTalk or 0)
    end)
    return on
end

-- Assumed from Config.wtf on WoW Forever: voiceProvider "1" is the Discord integration.
local function voiceViaDiscord()
    local ok, value = pcall(GetCVar, "voiceProvider")
    return ok and value == "1"
end

local function keepDictation() return db().ptt == "dictation" end

-- WoW's push-to-talk binding as one key name, "SHIFT-V"; nil when unbound.
local SIDES = { LSHIFT = "SHIFT", RSHIFT = "SHIFT", LCTRL = "CTRL", RCTRL = "CTRL", LALT = "ALT", RALT = "ALT" }
local function pttKey()
    local key
    pcall(function()
        local keys = C_VoiceChat.GetPushToTalkBinding()
        if type(keys) == "table" and #keys > 0 then
            local parts = {}
            for i, k in ipairs(keys) do parts[i] = SIDES[k] or k end
            key = table.concat(parts, "-")
        end
    end)
    if key and key ~= "" and not string.find(key, "[;,]") then return key end
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
    if voiceOn() then value = value + FLAG_VOICE end
    if voiceViaDiscord() then value = value + FLAG_DISCORD end
    if keepDictation() then value = value + FLAG_KEEP_DICTATION end
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
    local key = voiceOn() and pttKey()
    local prefix = key and ("k=" .. key .. ";") or ""
    local text = prefix .. "p=" .. table.concat(names, ",")
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
        text = prefix .. "p=+"
    end
    if text ~= payload then payload = text; revision = revision + 1 end
    draw()
end

local events = CreateFrame("Frame")
for _, e in ipairs({ "PLAYER_LOGIN", "PLAYER_ENTERING_WORLD", "GROUP_ROSTER_UPDATE",
    "UNIT_NAME_UPDATE", "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED", "DISPLAY_SIZE_CHANGED", "UI_SCALE_CHANGED",
    "VOICE_CHAT_CHANNEL_ACTIVATED", "VOICE_CHAT_CHANNEL_DEACTIVATED", "VOICE_CHAT_CHANNEL_REMOVED",
    "VOICE_CHAT_COMMUNICATION_MODE_CHANGED", "VOICE_CHAT_LOGIN", "VOICE_CHAT_LOGOUT", "UPDATE_BINDINGS" }) do
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
    -- "voice" (default) lets tehuti give push-to-talk to voice chat; "dictation" keeps it.
    setPtt = function(mode)
        db().ptt = mode == "dictation" and "dictation" or nil
        refresh()
    end,
    ptt = function() return keepDictation() and "dictation" or "voice" end,
}

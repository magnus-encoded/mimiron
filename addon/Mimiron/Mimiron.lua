local UNKNOWN = "UNKNOWN"

local function plain(value)
    if value == nil then return UNKNOWN end
    if type(value) == "boolean" then return value end
    if type(value) == "string" then return value end
    if type(value) == "number" then return tostring(value) end
    return tostring(value)
end

local function check(results, name, fn)
    local ok, value = pcall(fn)
    results[name] = ok and plain(value) or UNKNOWN
end

local function presence(results, name, fn)
    check(results, name, function() return fn() ~= nil end)
end

local function frameName(frame)
    if not frame then return UNKNOWN end
    local ok, name = pcall(function() return frame:GetName() end)
    if ok and name then return name end
    return UNKNOWN
end

local function fieldsMatching(frame)
    if not frame then return UNKNOWN end
    local fields = {}
    for key, value in pairs(frame) do
        local lower = type(key) == "string" and string.lower(key)
        if lower and (string.find(lower, "pad", 1, true)
            or string.find(lower, "focus", 1, true)
            or string.find(lower, "nav", 1, true)) then
            fields[#fields + 1] = key .. ":" .. type(value)
        end
    end
    table.sort(fields)
    return #fields > 0 and table.concat(fields, ", ") or UNKNOWN
end

local function runProbe()
    if type(MimironDB) ~= "table" then MimironDB = {} end
    if type(MimironDB.probe) ~= "table" then MimironDB.probe = {} end

    local open = false
    local ok, shown = pcall(function()
        return (AuctionFrame and AuctionFrame:IsShown())
            or (AuctionHouseFrame and AuctionHouseFrame:IsShown())
    end)
    if ok and shown then open = true end
    local state = open and "ah_open" or "closed"
    local stamp = UNKNOWN
    ok, shown = pcall(function() return date("%Y%m%d_%H%M%S") end)
    if ok then stamp = plain(shown) end
    local key = state .. "_" .. stamp
    local index = 2
    while MimironDB.probe[key] do
        key = state .. "_" .. stamp .. "_" .. index
        index = index + 1
    end

    local results = {}
    MimironDB.probe[key] = results
    check(results, "state", function() return state end)
    check(results, "timestamp", function() return stamp end)
    check(results, "GetBuildInfo.version", function() return select(1, GetBuildInfo()) end)
    check(results, "GetBuildInfo.build", function() return select(2, GetBuildInfo()) end)
    check(results, "GetBuildInfo.date", function() return select(3, GetBuildInfo()) end)
    check(results, "GetBuildInfo.toc", function() return select(4, GetBuildInfo()) end)
    check(results, "WOW_PROJECT_ID", function() return WOW_PROJECT_ID end)

    local legacy = {"PostAuction", "StartAuction", "CancelAuction", "PlaceAuctionBid",
        "GetAuctionItemInfo", "GetNumAuctionItems", "QueryAuctionItems"}
    for _, name in ipairs(legacy) do
        presence(results, name, function() return _G[name] end)
    end
    presence(results, "C_AuctionHouse", function() return C_AuctionHouse end)
    presence(results, "C_AuctionHouse.PostItem", function() return C_AuctionHouse and C_AuctionHouse.PostItem end)
    presence(results, "C_AuctionHouse.PlaceBid", function() return C_AuctionHouse and C_AuctionHouse.PlaceBid end)
    presence(results, "C_Container.GetContainerItemInfo", function() return C_Container and C_Container.GetContainerItemInfo end)
    presence(results, "GetContainerItemInfo", function() return GetContainerItemInfo end)

    presence(results, "AuctionFrame", function() return AuctionFrame end)
    presence(results, "AuctionHouseFrame", function() return AuctionHouseFrame end)
    check(results, "AuctionFrame.pad_focus_nav_fields", function() return fieldsMatching(AuctionFrame) end)
    check(results, "AuctionHouseFrame.pad_focus_nav_fields", function() return fieldsMatching(AuctionHouseFrame) end)
    check(results, "AuctionFrame.numTabs", function() return AuctionFrame and AuctionFrame.numTabs end)
    check(results, "AuctionHouseFrame.Tabs.count", function()
        return AuctionHouseFrame and AuctionHouseFrame.Tabs and #AuctionHouseFrame.Tabs
    end)
    for i = 1, 3 do
        local name = "AuctionFrameTab" .. i
        presence(results, name, function() return _G[name] end)
        check(results, name .. ".name", function() return frameName(_G[name]) end)
        check(results, name .. ".text", function()
            local frame = _G[name]
            return frame and frame.GetText and frame:GetText()
        end)
        check(results, name .. ".pad_focus_nav_fields", function() return fieldsMatching(_G[name]) end)
    end
    check(results, "AuctionHouseFrame.Tabs.names", function()
        local tabs = AuctionHouseFrame and AuctionHouseFrame.Tabs
        if not tabs then return UNKNOWN end
        local names = {}
        for i, tab in ipairs(tabs) do names[#names + 1] = i .. ":" .. frameName(tab) end
        return #names > 0 and table.concat(names, ", ") or UNKNOWN
    end)
    check(results, "AuctionHouseFrame.Tabs.pad_focus_nav_fields", function()
        local tabs = AuctionHouseFrame and AuctionHouseFrame.Tabs
        if not tabs then return UNKNOWN end
        local found = {}
        for i, tab in ipairs(tabs) do found[#found + 1] = i .. ":" .. fieldsMatching(tab) end
        return #found > 0 and table.concat(found, ", ") or UNKNOWN
    end)

    presence(results, "C_GamePad", function() return C_GamePad end)
    presence(results, "GamePadFocus", function() return GamePadFocus end)
    presence(results, "GamePadNavigation", function() return GamePadNavigation end)
    check(results, "C_GamePad.IsEnabled", function() return C_GamePad and C_GamePad.IsEnabled and C_GamePad.IsEnabled() end)
    check(results, "GamePad_globals", function()
        local found = {}
        for name, value in pairs(_G) do
            if type(name) == "string" and string.find(string.lower(name), "gamepad", 1, true) then
                found[#found + 1] = name .. ":" .. type(value)
            end
        end
        table.sort(found)
        return #found > 0 and table.concat(found, ", ") or UNKNOWN
    end)
    check(results, "GetMouseFoci", function()
        if not GetMouseFoci then return UNKNOWN end
        local frames = GetMouseFoci()
        if type(frames) ~= "table" then return frameName(frames) end
        local names = {}
        for i, frame in ipairs(frames) do names[#names + 1] = i .. ":" .. frameName(frame) end
        return #names > 0 and table.concat(names, ", ") or UNKNOWN
    end)
    check(results, "GetMouseFocus", function()
        return GetMouseFocus and frameName(GetMouseFocus()) or UNKNOWN
    end)

    -- Party name APIs: WoW Forever characters have two names; find which call carries both.
    for i = 1, 4 do
        local unit = "party" .. i
        local function all(fn) return function()
            local r = { fn() }
            for j = 1, #r do r[j] = tostring(r[j]) end
            return #r > 0 and table.concat(r, " | ") or UNKNOWN
        end end
        check(results, unit .. ".UnitName", all(function() return UnitName(unit) end))
        check(results, unit .. ".UnitFullName", all(function() return UnitFullName(unit) end))
        check(results, unit .. ".GetUnitName", all(function() return GetUnitName(unit, true) end))
        check(results, unit .. ".UnitPVPName", all(function() return UnitPVPName(unit) end))
        check(results, unit .. ".frameText", function()
            local f = _G["PartyMemberFrame" .. i .. "Name"] or (PartyFrame and PartyFrame["MemberFrame" .. i]
                and PartyFrame["MemberFrame" .. i].Name)
            return f and f:GetText()
        end)
    end

    DEFAULT_CHAT_FRAME:AddMessage("Mimiron: probe saved as " .. key .. "; /reload to write it to disk.")
    DEFAULT_CHAT_FRAME:AddMessage("Mimiron: to identify a control, use /mimiron focus <label>, then move the cursor onto it.")
end

-- Delayed focus capture: records the frame under the gamepad cursor 6s after the command, so the owner
-- can put the cursor on the close button, the last sort control, the Sell tab, and so on.
local function runFocus(label)
    if type(MimironDB) ~= "table" then MimironDB = {} end
    if type(MimironDB.focus) ~= "table" then MimironDB.focus = {} end
    local function capture()
        local entry = { label = label, time = UNKNOWN, frames = UNKNOWN, parents = UNKNOWN, pad_fields = UNKNOWN }
        pcall(function() entry.time = date("%Y%m%d_%H%M%S") end)
        pcall(function()
            local f = GetMouseFoci and GetMouseFoci() or (GetMouseFocus and { GetMouseFocus() }) or nil
            if type(f) ~= "table" then f = { f } end
            local names, parents, pads = {}, {}, {}
            for i, frame in ipairs(f) do
                names[#names + 1] = i .. ":" .. frameName(frame)
                local chain, p, n = {}, frame, 0
                while p and n < 6 do
                    chain[#chain + 1] = frameName(p)
                    p = p.GetParent and p:GetParent() or nil
                    n = n + 1
                end
                parents[#parents + 1] = table.concat(chain, " < ")
                pads[#pads + 1] = i .. ":" .. fieldsMatching(frame)
            end
            entry.frames = table.concat(names, ", ")
            entry.parents = table.concat(parents, " | ")
            entry.pad_fields = table.concat(pads, " | ")
        end)
        MimironDB.focus[#MimironDB.focus + 1] = entry
        DEFAULT_CHAT_FRAME:AddMessage("Mimiron: focus '" .. label .. "' captured: " .. tostring(entry.frames))
    end
    if C_Timer and C_Timer.After then
        DEFAULT_CHAT_FRAME:AddMessage("Mimiron: capturing '" .. label .. "' in 6s; move the cursor now.")
        C_Timer.After(6, capture)
    else
        capture()
    end
end

-- Opt-in guild names: only while /mimiron guildnames on. Guild member names (no realm, no
-- notes, no chat) go to MimironDB.roster so the plugin can prime dictation. Off clears them.
local function say(text) DEFAULT_CHAT_FRAME:AddMessage("Mimiron: " .. text) end

local function guildNamesOn()
    return type(MimironDB) == "table" and MimironDB.guildnames == true
end

local function collectRoster()
    if type(MimironDB) ~= "table" then MimironDB = {} end
    if not guildNamesOn() then MimironDB.roster = nil return end
    local names, seen = {}, {}
    pcall(function()
        for i = 1, GetNumGuildMembers() do
            local full = GetGuildRosterInfo(i)
            local name = full and string.match(full, "^([^-]+)")
            if name and not seen[name] then seen[name] = true; names[#names + 1] = name end
        end
    end)
    table.sort(names)
    MimironDB.roster = names
end

local function setGuildNames(on)
    if type(MimironDB) ~= "table" then MimironDB = {} end
    MimironDB.guildnames = on
    if on then
        pcall(function() (C_GuildInfo and C_GuildInfo.GuildRoster or GuildRoster)() end)
        collectRoster()
        say("guildnames on. Guild member names are saved to your Mimiron settings file; /reload writes it.")
    else
        MimironDB.roster = nil
        say("guildnames off. Stored guild names cleared.")
    end
end

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_LOGIN")
events:RegisterEvent("GUILD_ROSTER_UPDATE")
events:SetScript("OnEvent", function() collectRoster() end)

SLASH_MIMIRON1 = "/mimiron"
SLASH_MIMIRON2 = "/mim"
SlashCmdList.MIMIRON = function(message)
    message = type(message) == "string" and message or ""
    local label = string.match(message, "^%s*focus%s+(%S.-)%s*$")
    local toggle = string.match(message, "^%s*guildnames%s+(%a+)%s*$")
    local link = string.match(message, "^%s*link%s+(%a+)%s*$")
    if link == "on" or link == "off" then
        MimironLink.set(link == "on")
        say("link " .. link .. ". The corner strip that tells the Mimiron plugin your party and combat state is " .. (link == "on" and "shown." or "hidden."))
    elseif toggle == "on" or toggle == "off" then
        setGuildNames(toggle == "on")
    elseif string.match(message, "^%s*guildnames%s*$") or string.match(message, "^%s*status%s*$") then
        say("guildnames " .. (guildNamesOn() and "on" or "off") .. " (default off). Use /mimiron guildnames on|off")
        say("link " .. (MimironLink.on() and "on" or "off") .. " (default off). Use /mimiron link on|off")
    elseif string.match(message, "^%s*probe%s*$") then
        runProbe()
    elseif label then
        runFocus(label)
    else
        DEFAULT_CHAT_FRAME:AddMessage("Mimiron: use /mimiron guildnames on|off, /mimiron link on|off, /mimiron status, /mimiron probe, or /mimiron focus <label>")
    end
end

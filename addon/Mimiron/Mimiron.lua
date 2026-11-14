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
events:SetScript("OnEvent", function(_, event)
    -- The removed debug commands (issue #14) left their saves here; drop them.
    if event == "PLAYER_LOGIN" and type(MimironDB) == "table" then
        MimironDB.probe = nil
        MimironDB.focus = nil
    end
    collectRoster()
end)

SLASH_MIMIRON1 = "/mimiron"
SLASH_MIMIRON2 = "/mim"
SlashCmdList.MIMIRON = function(message)
    message = type(message) == "string" and message or ""
    local toggle = string.match(message, "^%s*guildnames%s+(%a+)%s*$")
    local link = string.match(message, "^%s*link%s+(%a+)%s*$")
    local ptt = string.match(message, "^%s*ptt%s+(%a+)%s*$")
    if ptt == "voice" or ptt == "dictation" then
        MimironLink.setPtt(ptt)
        say("ptt " .. ptt .. ". While voice chat is on, the push-to-talk button " .. (ptt == "voice" and "talks in voice chat." or "keeps dictating chat."))
    elseif link == "on" or link == "off" then
        MimironLink.set(link == "on")
        say("link " .. link .. ". The corner strip that tells the Mimiron plugin your party and combat state is " .. (link == "on" and "shown." or "hidden."))
    elseif toggle == "on" or toggle == "off" then
        setGuildNames(toggle == "on")
    elseif string.match(message, "^%s*guildnames%s*$") or string.match(message, "^%s*status%s*$") then
        say("guildnames " .. (guildNamesOn() and "on" or "off") .. " (default off). Use /mimiron guildnames on|off")
        say("link " .. (MimironLink.on() and "on" or "off") .. " (default off). Use /mimiron link on|off")
        say("ptt " .. MimironLink.ptt() .. " (default voice). Use /mimiron ptt voice|dictation")
    else
        say("use /mimiron guildnames on|off, /mimiron link on|off, /mimiron ptt voice|dictation, or /mimiron status")
    end
end

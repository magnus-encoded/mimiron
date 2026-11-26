-- One A-confirmed /mimiron fill writes one field. No submit, click or content logging.
-- Track boxes and focus times only, never contents. Chat owns focus at fill time.
local pending, lostAt, target, targetLostAt
local watched = setmetatable({}, { __mode = "k" })

local function isChat(box)
    local name = box.GetName and box:GetName()
    return (name and string.match(name, "^ChatFrame%d+EditBox$"))
        or (ChatEdit_GetActiveWindow and box == ChatEdit_GetActiveWindow())
end

local function watch(box)
    if not (box and box.IsObjectType and box:IsObjectType("EditBox")) or watched[box] then return end
    watched[box] = true
    box:HookScript("OnEditFocusGained", function(self)
        if isChat(self) then
            target, targetLostAt = pending, lostAt
            pending, lostAt = nil, nil
        else
            target, targetLostAt, pending, lostAt = nil, nil, nil, nil
        end
    end)
    box:HookScript("OnEditFocusLost", function(self)
        if not isChat(self) then pending, lostAt = self, GetTime() end
    end)
end

local function discover()
    local frame = EnumerateFrames()
    while frame do
        watch(frame)
        frame = EnumerateFrames(frame)
    end
end

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_LOGIN")
events:RegisterEvent("ADDON_LOADED")
events:SetScript("OnEvent", discover)
-- Never replace Blizzard globals. Hook shared EditBox methods to discover boxes
-- lazily, including anonymous load-on-demand boxes, without a creation-time walk.
-- An unparented EditBox is shown and autofocuses by default, swallowing
-- keyboard input (including Enter). Keep the introspection probe inert even
-- during creation, before SetAutoFocus can run.
local probeParent = CreateFrame("Frame")
probeParent:Hide()
local probe = CreateFrame("EditBox", nil, probeParent)
probe:SetAutoFocus(false)
probe:Hide()
probe:ClearFocus()
local mt = getmetatable(probe)
local methods = mt and mt.__index
if type(methods) == "table" and hooksecurefunc then
    for _, method in ipairs({ "SetScript", "HookScript", "SetFocus", "ClearFocus" }) do
        if type(methods[method]) == "function" then
            hooksecurefunc(methods, method, function(box)
                local known = watched[box]
                watch(box)
                -- A first SetFocus may precede discovery. Subsequent transitions
                -- are handled by the scripts, preserving the original loss time.
                if not known and method == "SetFocus" and box.HasFocus and box:HasFocus() then
                    target, targetLostAt, pending, lostAt = nil, nil, nil, nil
                end
            end)
        end
    end
end
-- ChatEdit also exposes the boxes cheaply on clients without shared methods.
if hooksecurefunc and ChatEdit_SetLastActiveWindow then
    hooksecurefunc("ChatEdit_SetLastActiveWindow", watch)
end
discover()

function MimironFill.focused(words)
    local box = target
    local age = targetLostAt and (GetTime() - targetLostAt)
    if not (box and age and age >= 0 and age <= 30 and not isChat(box)
            and box:IsShown() and box.SetText)
        or type(words) ~= "string" or words == "" or string.find(words, "[\r\n]") then
        DEFAULT_CHAT_FRAME:AddMessage("Mimiron: no focused text box here.")
        return false
    end
    local gold, silver, copper = string.match(words, "^(%d+) gold (%d+) silver (%d+) copper$")
    if gold then
        local parent = box.GetParent and box:GetParent()
        local name = parent and parent.GetName and parent:GetName()
        local g = parent and (parent.gold or (name and _G[name .. "Gold"]))
        local s = parent and (parent.silver or (name and _G[name .. "Silver"]))
        local c = parent and (parent.copper or (name and _G[name .. "Copper"]))
        -- Only the three parts of the focused money field may be filled.
        if not (g and s and c and (box == g or box == s or box == c)
                and g.SetText and s.SetText and c.SetText) then
            DEFAULT_CHAT_FRAME:AddMessage("Mimiron: no focused money box here.")
            return false
        end
        g:SetText(gold)
        s:SetText(silver)
        c:SetText(copper)
    else
        box:SetText(words)
    end
    DEFAULT_CHAT_FRAME:AddMessage("Mimiron: text box filled.")
    return true
end

-- Run only on the test machine.
local frames, hooks, messages = {}, {}, {}
local enumerations, now = 0, 100
local focused
function GetTime() return now end
local function focus(box)
    local old = focused
    focused = box
    if old and old.scripts.OnEditFocusLost then old.scripts.OnEditFocusLost(old) end
    if box and box.scripts.OnEditFocusGained then box.scripts.OnEditFocusGained(box) end
end
MimironFill = {}
DEFAULT_CHAT_FRAME = { AddMessage = function(_, text) messages[#messages + 1] = text end }
function EnumerateFrames(previous)
    enumerations = enumerations + 1
    if not previous then return frames[1] end
    for i, frame in ipairs(frames) do if frame == previous then return frames[i + 1] end end
end
function hooksecurefunc(object, method, fn)
    if type(object) == "string" then hooks[object] = method; return end
    local original = object[method]
    object[method] = function(self, ...)
        local result = original(self, ...)
        fn(self, ...)
        return result
    end
end
local methods = {}
function methods:IsObjectType(t) return self.kind == t end
function methods:GetName() return self.name end
function methods:HookScript(event, fn)
    local old = self.scripts[event]
    self.scripts[event] = function(...)
        if old then old(...) end
        fn(...)
    end
end
function methods:HasFocus() return focused == self end
function methods:IsShown()
    return self.shown and (not self.parent or self.parent:IsShown())
end
function methods:SetAutoFocus(value) self.autoFocus = value end
function methods:Hide()
    self.shown = false
    if focused == self then focus(nil) end
end
function methods:Show()
    self.shown = true
    if self.kind == "EditBox" and self.autoFocus and self:IsShown() then focus(self) end
end
function methods:SetText(text) self.text = text; self.writes = self.writes + 1 end
function methods:RegisterEvent() end
function methods:SetScript(event, fn) self.scripts[event] = fn end
function methods:SetFocus() focus(self) end
function methods:ClearFocus() if focused == self then focus(nil) end end
function CreateFrame(kind, name, parent)
    local box = setmetatable({ kind = kind, name = name, scripts = {}, shown = true, writes = 0,
                              parent = parent, autoFocus = true },
        { __index = methods })
    frames[#frames + 1] = box
    if kind == "EditBox" and box:IsShown() and box.autoFocus then focus(box) end
    return box
end
local originalCreateFrame = CreateFrame
local subject = CreateFrame("EditBox", "SendMailSubjectEditBox")
local chat = CreateFrame("EditBox", "ChatFrame1EditBox")
focus(nil)
dofile("addon/Mimiron/Fill.lua")
local probe = frames[#frames]
assert(probe.kind == "EditBox" and not probe:IsShown() and not probe.autoFocus,
    "discovery probe must be hidden with autofocus disabled")
assert(focused == nil, "loading Fill.lua must not steal keyboard focus")
-- Model Enter's open-chat binding: a focused EditBox consumes it instead.
local function enter()
    if not focused then chat:SetFocus() end
end
enter()
assert(focused == chat, "Enter must reach the game's open-chat binding after loading")
focus(nil)
probe:Show()
assert(focused == nil and not probe:IsShown(), "hidden parent keeps probe inert")
probe:Hide()
assert(CreateFrame == originalCreateFrame, "Blizzard globals must retain identity")
assert(not MimironFill.focused("secret"))
focus(chat)
assert(not MimironFill.focused("chat only"))
focus(subject)
assert(not MimironFill.focused("before chat"))
focus(chat)
assert(not subject:HasFocus())
assert(MimironFill.focused("hello") and subject.text == "hello")
assert(chat.writes == 0)
now = 130
assert(MimironFill.focused("at boundary"))
now = 130.01
assert(not MimironFill.focused("expired"))
assert(subject.text == "at boundary")
focus(subject)
focus(chat)
subject.shown = false
assert(not MimironFill.focused("hidden"))
subject.shown = true
focus(subject)
focus(nil)
now = now + 31
focus(chat)
assert(not MimironFill.focused("late chat"))
local beforeCreation = enumerations
local dynamic = CreateFrame("EditBox")
assert(enumerations == beforeCreation, "creation must not enumerate")
dynamic:SetFocus()
focus(chat)
assert(MimironFill.focused("private words") and dynamic.text == "private words")
assert(not MimironFill.focused("hello\n/run nope"))
local money = CreateFrame("Frame", "MoneyInput")
money.gold = CreateFrame("EditBox", "MoneyInputGold")
money.silver = CreateFrame("EditBox", "MoneyInputSilver")
money.copper = CreateFrame("EditBox", "MoneyInputCopper")
for _, box in ipairs({money.gold, money.silver, money.copper}) do
    function box:GetParent() return money end
    box:SetFocus()
    focus(chat)
    assert(MimironFill.focused("25 gold 30 silver 0 copper"))
    assert(money.gold.text == "25" and money.silver.text == "30" and money.copper.text == "0")
end
focus(subject)
focus(chat)
assert(not MimironFill.focused("25 gold 30 silver 0 copper"))
for _, message in ipairs(messages) do
    assert(message == "Mimiron: text box filled." or message == "Mimiron: no focused text box here."
        or message == "Mimiron: no focused money box here.")
end
print("ok focused fill")

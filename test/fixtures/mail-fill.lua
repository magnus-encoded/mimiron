-- Run on the test machine: lua5.1 test/fixtures/mail-fill.lua
local messages, writes = {}, 0
DEFAULT_CHAT_FRAME = { AddMessage = function(_, text) messages[#messages + 1] = text end }
local function box()
    return { shown = true, IsShown = function(self) return self.shown end,
        SetText = function(self, value) self.value = value; writes = writes + 1 end }
end
MailFrame, SendMailFrame = box(), box()
SendMailNameEditBox, SendMailSubjectEditBox = box(), box()
SendMailBodyEditBox, SendMailMoneyGold = box(), box()
SendMailMoneySilver, SendMailMoneyCopper = box(), box()
function SendMail() error("must never send mail") end
dofile("addon/Mimiron/Mail.lua")
for _, item in ipairs({ { "recipient", SendMailNameEditBox, "Thrall-Realm" },
        { "subject", SendMailSubjectEditBox, "Two gold for you!" },
        { "body", SendMailBodyEditBox, "One more thing: thank you." },
        { "gold", SendMailMoneyGold, "25" } }) do
    local before = writes
    assert(MimironMail.fill(item[1] .. " " .. item[3]))
    assert(writes == before + 1 and item[2].value == item[3])
end
assert(SendMailMoneySilver.value == nil and SendMailMoneyCopper.value == nil)
assert(MimironMail.fill("gold 0"))
assert(MimironMail.fill("gold 214748"))
local before = writes
for _, command in ipairs({ "gold -1", "gold 214749", "gold 1.5", "gold 9999999999999999999",
        "gold 25 silver", "recipient", "body hi\n/run bad", "send now", "subject " }) do
    assert(not MimironMail.fill(command))
end
MailFrame.shown = false
assert(not MimironMail.fill("body private"))
MailFrame.shown = true
SendMailFrame.shown = false
assert(not MimironMail.fill("subject private"))
SendMailFrame.shown = true
SendMailNameEditBox.shown = false
assert(not MimironMail.fill("recipient private"))
SendMailBodyEditBox = nil
assert(not MimironMail.fill("body private"))
assert(writes == before)
for _, message in ipairs(messages) do
    assert(message == "Mimiron: no mail field here." or message == "Mimiron: mail gold refused."
        or message == "Mimiron: mail recipient filled." or message == "Mimiron: mail subject filled."
        or message == "Mimiron: mail body filled." or message == "Mimiron: mail gold filled.")
end
print("ok mail fill")

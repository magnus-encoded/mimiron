-- One confirmed command fills one mail field. Sending remains on WoW's button.
MimironMail = {}

function MimironMail.fill(command)
    local field, words = string.match(command or "", "^(%a+)%s+(.+)$")
    local boxes = {
        recipient = SendMailNameEditBox,
        subject = SendMailSubjectEditBox,
        body = SendMailBodyEditBox,
        gold = SendMailMoneyGold,
    }
    local box = boxes[field]
    if not (MailFrame and MailFrame:IsShown() and SendMailFrame and SendMailFrame:IsShown()
            and box and box:IsShown() and box.SetText and words)
        or string.find(words, "[\r\n]") then
        DEFAULT_CHAT_FRAME:AddMessage("Mimiron: no mail field here.")
        return false
    end
    if field == "gold" and (not string.match(words, "^%d+$")
            or #words > 6 or tonumber(words) > 214748) then
        DEFAULT_CHAT_FRAME:AddMessage("Mimiron: mail gold refused.")
        return false
    end
    box:SetText(words)
    DEFAULT_CHAT_FRAME:AddMessage("Mimiron: mail " .. field .. " filled.")
    return true
end

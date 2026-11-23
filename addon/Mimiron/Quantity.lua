-- One A-confirmed draft fills one field; never invoke a transaction callback.
MimironQuantity = {}

local function shown(frame)
    return frame and frame.IsShown and frame:IsShown()
end

local function ownerKind(owner)
    while owner do
        if owner == MerchantFrame then return "vendor" end
        local name = owner.GetName and owner:GetName()
        if name and string.match(name, "^ContainerFrame%d+") then return "stack" end
        owner = owner.GetParent and owner:GetParent()
    end
end

local function enabled(button, on)
    if not button then return end
    if on and button.Enable then button:Enable()
    elseif not on and button.Disable then button:Disable() end
end

function MimironQuantity.fill(command)
    local field, words = string.match(command or "", "^(%a+) (%d+)$")
    local n = words and #words <= 10 and tonumber(words)
    local ok = false
    if n and field == "gold" and n <= 214748 then
        local box = TradePlayerInputMoneyFrameGold
        if shown(TradeFrame) and shown(box) and box.SetText then
            box:SetText(words)
            ok = true
        end
    elseif n and n >= 1 and n <= 2147483647 and (field == "stack" or field == "vendor") then
        local frame = StackSplitFrame
        local label = frame and (frame.StackSplitText or StackSplitText)
        if shown(frame) and ownerKind(frame.owner) == field
                and (field ~= "vendor" or shown(MerchantFrame))
                and type(frame.maxStack) == "number" and n <= frame.maxStack
                and label and label.SetText then
            -- These are the quantity dialog's typed value and display, not its
            -- owner's SplitStack callback (which can buy or move an item).
            frame.split = n
            frame.typing = 0
            label:SetText(words)
            local left = frame.LeftButton or StackSplitLeftButton
            local right = frame.RightButton or StackSplitRightButton
            enabled(left, n > 1)
            enabled(right, n < frame.maxStack)
            ok = true
        end
    end
    DEFAULT_CHAT_FRAME:AddMessage(ok and "Mimiron: quantity field filled."
        or "Mimiron: quantity fill refused.")
    return ok
end

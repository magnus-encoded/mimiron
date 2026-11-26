-- Run on the test machine: lua5.1 test/fixtures/quantity-fill.lua
local writes, messages = 0, {}
DEFAULT_CHAT_FRAME = { AddMessage = function(_, text) messages[#messages + 1] = text end }
local function box(name, parent)
    return { shown = true, GetName = function() return name end,
        GetParent = function() return parent end,
        IsShown = function(self) return self.shown end,
        SetText = function(self, value) self.value = value; writes = writes + 1 end,
        Enable = function(self) self.enabled = true end,
        Disable = function(self) self.enabled = false end }
end
local function forbidden() error("transaction must require its own button") end
AcceptTrade, BuyMerchantItem, SplitContainerItem = forbidden, forbidden, forbidden
StackSplitFrameOkay_Click = forbidden
TradeFrame, MerchantFrame = box(), box()
TradePlayerInputMoneyFrameGold = box()
TradePlayerInputMoneyFrameSilver, TradePlayerInputMoneyFrameCopper = box(), box()
local bag = box("ContainerFrame1")
local bagItem, merchantItem = box("ContainerFrame1Item1", bag), box("MerchantItem1ItemButton", MerchantFrame)
bagItem.SplitStack, merchantItem.SplitStack = forbidden, forbidden
StackSplitFrame = box()
StackSplitFrame.owner, StackSplitFrame.maxStack = bagItem, 20
StackSplitText, StackSplitLeftButton, StackSplitRightButton = box(), box(), box()
dofile("addon/Mimiron/Quantity.lua")
assert(MimironQuantity.fill("gold 25"))
assert(TradePlayerInputMoneyFrameGold.value == "25")
assert(not TradePlayerInputMoneyFrameSilver.value and not TradePlayerInputMoneyFrameCopper.value)
assert(MimironQuantity.fill("gold 0"))
assert(MimironQuantity.fill("gold 214748"))
assert(MimironQuantity.fill("stack 20"))
assert(StackSplitFrame.split == 20 and StackSplitText.value == "20" and StackSplitFrame.shown)
assert(StackSplitLeftButton.enabled and not StackSplitRightButton.enabled)
assert(MimironQuantity.fill("stack 1"))
assert(not StackSplitLeftButton.enabled and StackSplitRightButton.enabled)
StackSplitFrame.owner = merchantItem
assert(MimironQuantity.fill("vendor 10"))
assert(StackSplitFrame.split == 10 and StackSplitText.value == "10" and StackSplitFrame.shown)
local before = writes
for _, command in ipairs({ "gold -1", "gold 214749", "gold 1.5", "gold 25\n/run bad",
        "stack 2", "vendor 0", "vendor 21", "vendor 999999999999999999", "buy 10", "accept 1" }) do
    assert(not MimironQuantity.fill(command), command)
end
MerchantFrame.shown = false
assert(not MimironQuantity.fill("vendor 10"))
MerchantFrame.shown = true
StackSplitFrame.shown = false
assert(not MimironQuantity.fill("vendor 10"))
StackSplitFrame.shown = true
StackSplitFrame.owner = box("CraftQuantity")
assert(not MimironQuantity.fill("stack 10"))
StackSplitFrame.owner = nil
assert(not MimironQuantity.fill("vendor 10"))
TradeFrame.shown = false
assert(not MimironQuantity.fill("gold 25"))
TradeFrame.shown = true
TradePlayerInputMoneyFrameGold.shown = false
assert(not MimironQuantity.fill("gold 25"))
TradePlayerInputMoneyFrameGold = nil
assert(not MimironQuantity.fill("gold 25"))
assert(writes == before)
for _, message in ipairs(messages) do
    assert(message == "Mimiron: quantity field filled." or message == "Mimiron: quantity fill refused.")
end
print("ok quantity fill")

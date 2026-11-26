-- /mimiron ah <verb> ...: fills one auction house field (issue #6), fair-play exception 3
-- (docs/notes/fair-play.md). The plugin drafts the line from one spoken phrase and only the
-- player's A sends it. Nothing here buys, bids or posts: those stay on the game's own buttons.
-- Feedback names the field, never what was filled, and nothing is logged.

-- Reusable fills for any Blizzard input (issue #20 generalises the path beyond the house).
MimironFill = {}

local function shown(frame)
    return frame ~= nil and frame.IsShown ~= nil and frame:IsShown()
end

-- A quantity-style input: sets the number, then runs the callback the UI runs only on typed
-- input, so totals and the post state update as if the player had typed it.
function MimironFill.quantity(input, n)
    if not (input and input.SetQuantity) then return false end
    input:SetQuantity(n)
    local box = input.InputBox
    local changed = box and box.GetInputChangedCallback and box:GetInputChangedCallback()
    if changed then changed() end
    return true
end

local function house()
    local frame = AuctionHouseFrame
    if shown(frame) then return frame end
end

local function sellFrame(frame)
    if shown(frame.ItemSellFrame) then return frame.ItemSellFrame end
    if shown(frame.CommoditiesSellFrame) then return frame.CommoditiesSellFrame end
end

-- The list on screen and the frame that sorts it, sell lists first: they share the screen
-- with the sell frame.
local function currentList(frame)
    local pairsInOrder = {
        { frame.ItemSellList, frame.ItemSellFrame },
        { frame.CommoditiesSellList, frame.CommoditiesSellFrame },
        { frame.BrowseResultsFrame and frame.BrowseResultsFrame.ItemList, frame.BrowseResultsFrame },
        { frame.ItemBuyFrame and frame.ItemBuyFrame.ItemList, frame.ItemBuyFrame },
        { frame.CommoditiesBuyFrame and frame.CommoditiesBuyFrame.ItemList, frame.CommoditiesBuyFrame },
    }
    for _, p in ipairs(pairsInOrder) do
        if shown(p[1]) then return p[1], p[2] end
    end
end

local columns = {
    price = "Price", name = "Name", level = "Level", bid = "Bid", buyout = "Buyout",
    time = "TimeRemaining",
}

local verbs = {}

function verbs.search(frame, rest)
    if rest == "" then return "say what to search for." end
    if not frame:SetSearchText(rest) then return "switch to the Buy tab to search." end
    frame.SearchBar:StartSearch()
    return "search filled and started."
end

-- At most one sort query per phrase: the game toggles a column that is already first.
function verbs.sort(frame, rest)
    local word, reversed = string.match(rest, "^(%a+)(.*)$")
    reversed = reversed ~= nil and string.match(reversed, "^%s*reversed$") ~= nil
    local order = word and columns[word] and Enum.AuctionHouseSortOrder[columns[word]]
    if not order then return "say sort by price, name, level, bid, buyout or time." end
    local _, owner = currentList(frame)
    if not (owner and owner.SetSortOrder and owner.GetSortOrderState) then
        return "no list to sort here."
    end
    local state = owner:GetSortOrderState(order)
    local want = reversed and AuctionHouseSortOrderState.PrimaryReversed or AuctionHouseSortOrderState.PrimarySorted
    if state == want then return "already sorted that way." end
    owner:SetSortOrder(order)
    if reversed and state ~= AuctionHouseSortOrderState.PrimarySorted then
        return "sorted. Say it again to reverse."
    end
    return "sorted."
end

-- n counts the rows on screen from the top, as the player sees them.
function verbs.pick(frame, rest)
    local n = tonumber(string.match(rest, "^(%d+)$"))
    local list = currentList(frame)
    if not (n and list and list.getEntry and list.getNumEntries and list.SetSelectedEntry) then
        return "no list to pick from."
    end
    local first = math.max(list.GetScrollBoxDataIndexBegin and list:GetScrollBoxDataIndexBegin() or 1, 1)
    local index = first + n - 1
    if index > list.getNumEntries() then return "no such row on screen." end
    list:SetSelectedEntry(list.getEntry(index))
    return "row picked."
end

function verbs.quantity(frame, rest)
    local n = tonumber(string.match(rest, "^(%d+)$"))
    if not n then return "say a quantity." end
    local sell = sellFrame(frame)
    local buy = frame.CommoditiesBuyFrame
    local input = sell and sell.QuantityInput
        or (shown(buy) and buy.BuyDisplay and buy.BuyDisplay.QuantityInput)
    if not (shown(input) and MimironFill.quantity(input, n)) then return "no quantity box here." end
    return "quantity filled."
end

function verbs.max(frame)
    local sell = sellFrame(frame)
    if not (sell and sell.SetToMaxQuantity) then return "max quantity is for selling." end
    sell:SetToMaxQuantity()
    return "quantity set to the most you can post."
end

function verbs.price(frame, rest)
    local copper = tonumber(string.match(rest, "^(%d+)$"))
    if not copper then return "say a price." end
    local sell = sellFrame(frame)
    if not (sell and sell.PriceInput) then return "the price is for selling." end
    sell.PriceInput:SetAmount(copper)
    if sell.UpdatePostState then sell:UpdatePostState() end
    return "price filled."
end

local durations = { ["12"] = 1, ["24"] = 2, ["48"] = 3 }

function verbs.duration(frame, rest)
    local index = durations[rest]
    if not index then return "say 12, 24 or 48 hours." end
    local sell = sellFrame(frame)
    local duration = sell and sell.Duration
    if not (duration and duration.SetDuration) then return "the listing time is for selling." end
    duration:SetDuration(index)
    if duration.Dropdown and duration.Dropdown.GenerateMenu then duration.Dropdown:GenerateMenu() end
    return "listing time set."
end

local usage = "use /mimiron ah search <words>|sort <column> [reversed]|pick <n>|quantity <n>|max|price <copper>|duration 12|24|48"

MimironAuction = {}

-- Runs one verb and returns the line it said, for the test harness.
function MimironAuction.run(message)
    local verb, rest = string.match(message or "", "^%s*(%a+)%s*(.-)%s*$")
    local fn = verb and verbs[string.lower(verb)]
    local text
    if not fn then
        text = usage
    elseif not house() then
        text = "open the auction house first."
    else
        local ok, result = pcall(fn, house(), rest)
        text = ok and result or "the auction house did not take that."
    end
    DEFAULT_CHAT_FRAME:AddMessage("Mimiron: " .. text)
    return text
end

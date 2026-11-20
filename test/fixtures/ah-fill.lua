-- Runs addon/Mimiron/Auction.lua under a stub auction house and checks each /mimiron ah
-- verb fills its field and nothing else. Prints "ok <n>" and exits 0, or the failures and 1.
--   lua5.1 test/fixtures/ah-fill.lua addon/Mimiron/Auction.lua
local file = arg[1]
local said, calls, failures, checks = {}, {}, {}, 0

local function check(ok, what)
    checks = checks + 1
    if not ok then failures[#failures + 1] = what end
end

DEFAULT_CHAT_FRAME = { AddMessage = function(_, text) said[#said + 1] = text end }
Enum = { AuctionHouseSortOrder = { Price = 0, Name = 1, Level = 2, Bid = 3, Buyout = 4, TimeRemaining = 5 } }
AuctionHouseSortOrderState = { None = 1, PrimarySorted = 2, PrimaryReversed = 3, Sorted = 4, Reversed = 5 }

local function frame(t)
    t = t or {}
    t.visible = t.visible ~= false
    t.IsShown = function(self) return self.visible end
    return t
end

local function record(name) return function(_, ...) calls[#calls + 1] = { name, ... } end end

local function list(rows, begin)
    return frame({
        getEntry = function(i) return rows[i] end,
        getNumEntries = function() return #rows end,
        GetScrollBoxDataIndexBegin = function() return begin end,
        SetSelectedEntry = record("select"),
    })
end

local function sorter(state)
    return frame({
        GetSortOrderState = function() return state end,
        SetSortOrder = record("sort"),
    })
end

local typedCallback = 0
local function quantityInput()
    return frame({
        SetQuantity = record("quantity"),
        InputBox = { GetInputChangedCallback = function() return function() typedCallback = typedCallback + 1 end end },
    })
end

local function buyHouse(mode)
    return frame({
        mode = mode,
        SetSearchText = function(self, text) calls[#calls + 1] = { "searchText", text } return self.mode == "buy" end,
        SearchBar = { StartSearch = record("startSearch") },
        BrowseResultsFrame = sorter(AuctionHouseSortOrderState.None),
        ItemSellFrame = frame({ visible = false }),
        CommoditiesSellFrame = frame({ visible = false }),
        CommoditiesBuyFrame = frame({ visible = false }),
    })
end

local function sellHouse()
    local sell = sorter(AuctionHouseSortOrderState.None)
    sell.QuantityInput = quantityInput()
    sell.PriceInput = { SetAmount = record("price") }
    sell.UpdatePostState = record("postState")
    sell.SetToMaxQuantity = record("max")
    sell.Duration = { SetDuration = record("duration"), Dropdown = { GenerateMenu = record("menu") } }
    return frame({
        SetSearchText = function() return false end,
        CommoditiesSellFrame = sell,
        CommoditiesSellList = list({ "r1", "r2", "r3" }, 1),
        ItemSellFrame = frame({ visible = false }),
    })
end

dofile(file)

local function run(house, message)
    AuctionHouseFrame = house
    calls, said = {}, {}
    return MimironAuction.run(message)
end

local function only(name, ...)
    local want = { name, ... }
    if #calls ~= 1 then return false end
    for i = 1, math.max(#want, #calls[1]) do
        if calls[1][i] ~= want[i] then return false end
    end
    return true
end

local function names()
    local out = {}
    for _, c in ipairs(calls) do out[#out + 1] = c[1] end
    return table.concat(out, ",")
end

-- Search fills the box and runs that search, on a Buy tab only.
local h = buyHouse("buy")
h.BrowseResultsFrame.ItemList = list({ "a", "b", "c", "d" }, 2)
local text = run(h, "search linen cloth")
check(names() == "searchText,startSearch" and calls[1][2] == "linen cloth", "search fills and starts")
check(not string.find(text, "linen"), "search never echoes the words")
run(buyHouse("sell"), "search linen cloth")
check(names() == "searchText", "search off the Buy tab starts nothing")

-- Pick counts the rows on screen.
run(h, "pick 2")
check(only("select", "c"), "pick 2 from row 2 on screen is entry 3")
text = run(h, "pick 4")
check(#calls == 0 and text == "no such row on screen.", "pick past the end does nothing")

-- Sort: at most one query per phrase.
run(h, "sort price")
check(only("sort", 0), "sort asks once")
h.BrowseResultsFrame = sorter(AuctionHouseSortOrderState.PrimarySorted)
h.BrowseResultsFrame.ItemList = list({ "a" }, 1)
text = run(h, "sort price")
check(#calls == 0, "already sorted asks nothing")
text = run(h, "sort price reversed")
check(only("sort", 0) and text == "sorted.", "a sorted first column reverses in one query")
h.BrowseResultsFrame = sorter(AuctionHouseSortOrderState.None)
h.BrowseResultsFrame.ItemList = list({ "a" }, 1)
text = run(h, "sort time reversed")
check(only("sort", 5) and string.find(text, "again"), "an unsorted column sorts once and asks again")
run(h, "sort colour")
check(#calls == 0, "an unknown column sorts nothing")

-- Selling fields.
local s = sellHouse()
typedCallback = 0
text = run(s, "quantity 20")
check(only("quantity", 20) and typedCallback == 1, "quantity fills and runs the typed-input callback")
check(not string.find(text, "20"), "quantity never echoes the number")
run(s, "max")
check(only("max"), "max")
text = run(s, "price 125000")
check(names() == "price,postState" and calls[1][2] == 125000, "price fills in copper")
check(not string.find(text, "125"), "price never echoes the amount")
run(s, "duration 48")
check(names() == "duration,menu" and calls[1][2] == 3, "48 hours is the third duration")
run(s, "duration 36")
check(#calls == 0, "no 36 hour listing")
run(s, "pick 3")
check(only("select", "r3"), "pick in a sell list")
run(buyHouse("buy"), "price 100")
check(#calls == 0, "no price box on the Buy tab")

-- Commodity buy quantity.
local c = buyHouse("buy")
c.CommoditiesBuyFrame = frame({ BuyDisplay = { QuantityInput = quantityInput() } })
run(c, "quantity 5")
check(only("quantity", 5), "commodity buy quantity")

-- No house, unknown verbs, broken frames.
local closed = buyHouse("buy")
closed.visible = false
text = run(closed, "quantity 5")
check(#calls == 0 and text == "open the auction house first.", "a closed house does nothing")
text = run(h, "buy")
check(#calls == 0 and string.find(text, "^use /mimiron ah"), "buy is no verb")
text = run(h, "post")
check(#calls == 0 and string.find(text, "^use /mimiron ah"), "post is no verb")
local broken = buyHouse("buy")
broken.SetSearchText = function() error("secret linen") end
text = run(broken, "search linen")
check(text == "the auction house did not take that.", "an error is caught and not echoed")
check(#said == 1 and string.find(said[1], "^Mimiron: "), "one chat line per verb")

if #failures > 0 then
    for _, f in ipairs(failures) do print("FAIL " .. f) end
    os.exit(1)
end
print("ok " .. checks)

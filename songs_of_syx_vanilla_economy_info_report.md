# Economy Information Available to the Player in Songs of Syx (V71)

*Scope: an overview of what the vanilla game already surfaces about resources — production,
consumption, storage, map totals, and distribution — and how it is presented. Sourced by
reading the game's own `SongsOfSyx-sources.jar`.*

## 1. The data model underneath

Three live data structures feed essentially every economy display. Knowing these explains
what is *possible* to show:

- **`StockpileTally`** (`SETT.ROOMS().STOCKPILE.tally()`) — current totals across all
  warehouses: stored amount, capacity, crates, reserved space/amount per resource, plus a
  **daily history** of stored amounts (kept for `STATS.DAYS_SAVED` days).
- **`FResources`** (`FACTIONS.player().res()`) — faction-wide flow accounting. Every
  gain/loss of a resource is bucketed into one of **13 flow categories** and recorded with
  full history: `PRODUCED, CONSUMED, TRADE, TAX, CONSTRUCTION, FURNISH (furniture wear),
  EQUIPPED, MAINTENANCE, SPOILAGE, ARMY_SUPPLY, SPOILS (battle), DIPLOMACY, THEFT`. It
  exposes `in(type)`, `out(type)`, and a combined `total()` net series.
- **`RoomProduction`** (`SETT.ROOMS().PROD`) — live per-day estimates: `produced(res)`,
  `consumed(res)`, and crucially `producers(res)` / `consumers(res)` as lists of **Source**
  objects (each with an amount/day, icon, and name) — i.e. the breakdown of *which
  rooms/activities* produce or consume a good.

Two more, more specialized: scattered-on-map counts via
`SETT.PATH().finders.resource.scattered.has(res)`, and world-region distribution via
`RD.OUTPUT().get(tradable).getDelivery(region)`.

## 2. Where the player sees it

**A. Right-side mini resource panel** (`view/sett/ui/right/UIMiniResources`) — always visible
in the settlement view.
Per resource: icon + **current stored amount** + a **fill meter** (stored / capacity),
turning red-purple above 90% full. Resources are grouped by category, with a compact and an
expanded mode. The stored number renders in error color when it hits 0 (unless some is
scattered on the map). Left-click opens the Goods detail; right-click cycles the camera to a
warehouse holding that good.

**B. Resource hover tooltip** (`RESOURCE.hover` / `hoverDetailed`) — the single richest
readout, shown on hover almost anywhere a resource icon appears.
- Basic: name, description, **decay/spoilage rate** per year (and when stored),
  edible/drinkable flags.
- Detailed adds: **buy & sell price** with trade-desirability chevrons; a **"Produced per day
  (estimation)"** section listing every producer *and* consumer source with its per-day
  amount; a **Net/day**; a **Stored** breakdown split across building types (Stockpile,
  Hauler, Import, Export, Station — each as amount/capacity); and the **scattered-on-map
  count** ("Resources exist scattered on the map that are not yet stored and counted: X").

**C. Goods overview screen** (`view/ui/goods/UIGoods`, the crate icon) — full-screen, all
resources, searchable, two columns by category.
Each row shows the icon plus two history bar-charts: a **Storage diagram** (fill ratio over
time, labeled with current stored/capacity) and a **Production diagram** (net per day over
time, green positive / red negative). Hovering any history bar gives a per-day snapshot:
timestamp, **Stored**, the full **per-category in/out** breakdown, **Net**, buy/sell/average
**price**, and **Earnings** (export value − import value). A production button per row opens
the regional distribution popup (below).

**D. Per-room industry panel** (`view/sett/ui/room/ModuleIndustry`) — when a workshop/industry
room is selected.
Production estimate/day and consumption estimate/day (explicitly flagged as estimates that
"can vary greatly"), plus actuals: **produced today / yesterday / this year / estimated this
year / last year**, and the same four for consumption. Recipe selection, and warnings like
"proximity to raw materials is poor" or "internal storage is full and production is stalled."

**E. Distribution / location** (`view/ui/goods/Pop`, world region view).
The Goods production button opens a popup listing each **world region** and the **delivery
amount** of that good there; clicking a region jumps to it in the world map. Import/Export
rooms track per-resource amounts and capacities. Within the settlement, the
right-click-to-warehouse cycling is the only "where is it stored" locator — there's no
stored-goods heatmap.

**F. Treasury / finances** (`view/ui/economy/*` — `UITreasury`, `MainDetails`,
`YearlyFinansials`, `Factions`, `MainChart`). A complementary, **gold-centric** view:
income/expenses, yearly financials, and prices across factions — money rather than physical
goods.

## 3. Coverage of the specific metrics

| Metric | Available? | Where / how presented |
|---|---|---|
| **Rate of production** | Full | `RoomProduction.produced()`; tooltip "produced/day" by source; per-room actuals (today/yesterday/year); Goods net-production history chart; `FResources` PRODUCED in-flow history |
| **Rate of usage / consumption** | Full | `RoomProduction.consumed()`; tooltip consumer breakdown; per-room consumption; **plus 11 other loss categories** tracked separately (maintenance, spoilage, construction, equipping, army supply, theft…) |
| **Net rate** | Full | Tooltip Net; Goods net diagram (green/red); `FResources.total()` |
| **Total in storage** | Full | `StockpileTally.amountTotal()`; mini panel; tooltip (split by building type); Goods storage chart with capacity |
| **Total on map** | Partial | Only a single aggregate "scattered, not yet counted" number in the detailed tooltip — not located or broken down |
| **Distribution / location** | Partial | World-**region** granularity (delivery per region, jump-to-region); within settlement only "which warehouse" (right-click) + per-source rooms. No in-map stored-goods view |

## 4. Implication for the Economy Overview mod

Almost everything the mod plan targets — stored amount, produced/day, consumed/day, net/day,
even per-resource usage — **already exists** in vanilla, but it is fragmented: one resource at
a time in the hover tooltip, or split across two history diagrams plus a hover snapshot in the
Goods screen. **There is no single flat, all-resources-at-once table with Stored / Produced /
Consumed / Net as sortable side-by-side columns.** That consolidated, scannable, sortable view
is the mod's genuine value-add rather than exposing data the player can't otherwise get.

Two practical notes for later milestones:

- **Usage descriptions (Milestone 7)** need not be hardcoded — `RoomProduction.consumers(res)`
  already yields the rooms/industries consuming each good, so usage can be derived dynamically.
- The mod's numbers should match vanilla by reading the *same* sources (`SETT.ROOMS().PROD`
  and `STOCKPILE.tally()`), which the tooltip and Goods screen both use — avoiding a second,
  divergent calculation.

---

## Appendix: source references

All paths are within `SongsOfSyx-sources.jar` (installed game source, V71):

| Concern | Class |
|---|---|
| Storage totals / capacity / daily history | `settlement/room/infra/stockpile/StockpileTally` |
| Faction flow accounting (13 categories, history) | `game/faction/FResources` |
| Live production/consumption estimates + per-source breakdown | `settlement/room/industry/module/RoomProduction` |
| Per-resource tooltip (prices, production, stored, scattered) | `init/resources/RESOURCE` (`hover` / `hoverDetailed`) |
| Always-on mini resource panel | `view/sett/ui/right/UIMiniResources` |
| Full goods overview screen + history charts | `view/ui/goods/UIGoods`, `view/ui/goods/Row` |
| Regional distribution popup | `view/ui/goods/Pop` |
| Per-room industry panel | `view/sett/ui/room/ModuleIndustry` |
| Treasury / financial views | `view/ui/economy/*` |

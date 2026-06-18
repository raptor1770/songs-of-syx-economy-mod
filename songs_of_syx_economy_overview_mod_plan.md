# Songs of Syx — Economy Overview Mod Plan

A **Java/code mod (V71)** that adds an `ECON` button to the settlement UI, opening a single
**spreadsheet-style menu listing every resource** with consolidated, sortable, computed
columns the vanilla UI cannot show.

---

## 1. Goal & Value Proposition

Vanilla already exposes almost all raw economy data — stored amounts, per-day
production/consumption, net, prices, history, per-source breakdowns — but it is **fragmented**:
one resource at a time in hover tooltips, or split across two history diagrams in the Goods
screen. There is **no single flat grid where all resources sit side by side and can be sorted
and compared**.

That grid — plus a handful of **derived columns vanilla never computes** (runway, status,
loss breakdown, stockpile value) — is this mod's reason to exist.

> **Design principle:** Do not re-show what a tooltip already shows for one resource. Only add
> what makes sense when *all* resources sit in one sortable table.

Full audit of what vanilla provides: see
[songs_of_syx_vanilla_economy_info_report.md](songs_of_syx_vanilla_economy_info_report.md).

---

## 2. Target User Experience

A small `ECON` button in the settlement top bar opens a full-screen table:

```text
Economy Overview                                  [search…]  [filter ▾]  [×]

Resource     Stored  Fill%  Prod/d  Cons/d   Net/d  Runway  Spoil/d   Value   Δ7d  ⚑
─────────────────────────────────────────────────────────────────────────────────
Grain         2,400   61%    +310    -180    +130    —       -22      4,800   ▲    ·
Wood            840   78%    +120     -95     +25    —         0       2,520   ▲    ·
Stone           500   40%     +40     -60     -20    25d       0       1,500   ▼    !
Tools           110   22%     +12      -9      +3    —         0       3,300   ▲    ·
─────────────────────────────────────────────────────────────────────────────────
TOTALS                                                       Value 64,200  Food 31d
```

- Columns are **sortable** (click header) and **filterable** (negative-net, food-only, search).
- A **status flag** (⚑) marks deficits, low runway, near-overflow, out-of-stock, and a
  **trade opportunity** (a non-partner would pay materially more, and a deal is attainable).
- Money columns show the price you can **realize today**; hovering a price cell lists each
  faction's price and whether you have an agreement, and clicking opens vanilla's trade screen.
- A **totals row** summarizes inventory value, net gold flow, and days of food.
- Read-only — the first release never writes game or save state.

---

## 3. Scope

**In scope (through v1.0):** the ECON button; the all-resources table; the Foundation, Tier 1,
and Tier 2 columns (section 4); sorting/filtering; a totals row; **agreement-aware trade pricing
with an opportunity flag**; read-only rendering.

**Explicitly out of scope — vanilla does these better, do not rebuild:**

- Price/storage **history charts** (the Goods screen's diagrams are the right tool).
- The **trade/diplomacy UI itself** — we *surface* per-faction prices and agreement status, but
  the full partner list and deal-making link out to vanilla (`UIGoodsTraders`, the trade screen).
- **Per-region distribution** detail (vanilla's `view/ui/goods/Pop` popup covers it; at most
  link out to it).
- In-map **stored-goods heatmap** (right-click-to-warehouse already exists).
- Any **editing** of recipes, imports, exports, or save data.
- Replacing or subclassing vanilla UI classes.

---

## 4. Column Reference (the centerpiece)

Each column is one definition: header, how it's computed, and the game data source. Tiers map
to milestones/versions.

| Column | Tier | Meaning | Source / formula |
|---|---|---|---|
| Icon + Name | F | Resource identity | `RESOURCES.ALL()` → `res.icon()`, `res.name` |
| Stored | F | Total held in warehouses | `SETT.ROOMS().STOCKPILE.tally().amountTotal(res)` |
| Fill % | F | Storage utilization | `tally().load(res)` (= amount ÷ `space.total(res)`) |
| Prod/day | F | Live production estimate | `SETT.ROOMS().PROD.produced(res)` |
| Cons/day | F | Live consumption estimate | `SETT.ROOMS().PROD.consumed(res)` |
| Net/day | F | Surplus / deficit | `produced(res) − consumed(res)` |
| Runway | T1 | Days until depletion | `net < 0 ? stored ÷ −net : —` (pessimistic variant: `stored ÷ consumed`) |
| Status ⚑ | T1 | Health flag | Derived: deficit (net<0) · low runway · near-overflow (fill>90% ⇒ production stalls) · zero-stock-and-none-scattered · **(T2) trade-opportunity** (a better price sits behind an attainable agreement) |
| Spoil/day | T2 | Lost to decay | `FACTIONS.player().res().out(RTYPE.SPOILAGE).history(res.tr()).get(1)` |
| Maint/day | T2 | Consumed by upkeep | `…res().out(RTYPE.MAINTENANCE).history(res.tr()).get(1)` |
| Import/Export per day | T2 | Trade flow | `res().in/out(RTYPE.TRADE)` *(confirm direction vs. `GAME.player().trade.inExported/outImported` during impl)* |
| Sell @ now | T2 | Best price realizable **today** (current partners) | best `f.res(res).priceBuyP()` over `f ∈ neighs` where `DIP.get(f).trades`, or effective `FACTIONS.player().trade.pricesSell.get(res.tr())` |
| Best @ avail | T2 | Best price **any reachable** faction offers, tagged *trading / needs-deal / locked* | best `f.res(res).priceBuyP()` over all `f ∈ RD.DIST().neighs()`; tag via `DIP.get(f).trades` and `ROPINION.get(f) ≥ DIP.TRADE().opinionNeeded` |
| Value | T2 | Inventory worth **at current trade prices** | `stored × Sell@now` |
| Net gold/day | T2 | Economic drain/gain at realizable prices | `net × Sell@now` |
| Δ7d (trend) | T2 | 7-day stock direction | `amountsDay().history(res).get(1) − .get(7)` |
| Net % | T3 | Surplus margin | `net ÷ consumed` |
| Scattered | T3 (opt) | Uncounted on map | `SETT.PATH().finders.resource.scattered.has(res)` |

**TOTALS row (T2):** Σ Value, Σ Net gold/day, and **days of food** = (Σ stored over
`RESOURCES.EDI()`) ÷ daily food intake.

---

## 5. Data Sources & Caveats

Group every read through one service (`EconomyData`) so all game-API contact is in one place.

| Concern | Game API |
|---|---|
| Storage totals, capacity, daily history | `SETT.ROOMS().STOCKPILE.tally()` → `amountTotal`, `space.total`, `load`, `amountsDay().history(res)` |
| Live production / consumption + per-source breakdown | `SETT.ROOMS().PROD` → `produced(res)`, `consumed(res)`, `producers(res)`, `consumers(res)` (each `Source.am()/name()/icon()`) |
| Faction flow accounting (13 categories, with history) | `FACTIONS.player().res()` → `in(RTYPE)`, `out(RTYPE)`, `total()` |
| Effective (realized) prices & earnings | `FACTIONS.player().trade.pricesSell/pricesBuy` (agreement-aware aggregate), `GAME.player().trade.inExported/outImported` |
| Per-faction prices & agreements | `f.res(res).priceBuyP()` / `priceSellP()` for `f ∈ RD.DIST().neighs()`; agreement = `DIP.get(f).trades`; attainable = `ROPINION.get(f) ≥ DIP.TRADE().opinionNeeded` |
| Food / drink sets, decay | `RESOURCES.EDI()`, `RESOURCES.DRINKS()`, `res.degradeSpeed()` |

**Caveats (correctness traps):**

- **PROD vs. FResources are different scopes.** `PROD.produced/consumed` is settlement
  room-based (industries, eating, livestock, construction) and matches the vanilla resource
  tooltip and `ModuleIndustry`. `FResources` is faction-wide and separates trade, tax,
  spoilage, maintenance, theft, etc. Use **PROD for the core Prod/Cons/Net columns** (so
  numbers match vanilla), and **FResources for the Tier-2 breakdown columns** (spoilage,
  maintenance, trade).
- **History index:** `get(0)` is the in-progress day; use **`get(1)`** for the last completed
  day (the convention vanilla's `view/ui/goods/Row` uses).
- **Zero capacity** ⇒ treat fill as full (1.0), matching `StockpileTally.load`.
- **Infinite/non-negative runway** ⇒ render as `—`, not a huge number.

### Trade pricing is per-faction and gated by agreements

Prices are **not global**. Each neighbouring faction (`RD.DIST().neighs()`) has its own buy/sell
price for every resource, and the player can only *realize* a price with a faction it has an
active **trade agreement** with. So a current partner may buy your clay at 20 while a
non-partner would pay 100. The table must never present a single context-free price.

- **Realizable now** — the best price among factions where `DIP.get(f).trades` is true (or the
  effective `player().trade.pricesSell/pricesBuy` aggregate). **`Value` and `Net gold/day` are
  computed from this**, so the money columns stay honest about what the player can actually get.
- **Best available** — the best price across *all* neighbours, each tagged by attainability:
  *trading* (`DIP.get(f).trades`), *needs-deal* (`ROPINION.get(f) ≥ DIP.TRADE().opinionNeeded`),
  or *opinion-locked*.
- **Opportunity flag** — fires when *best available − realizable* is materially positive **and**
  the better partner is at least *needs-deal* (attainable): "you're leaving money on the table; a
  trade agreement is available."

Do **not** rebuild the trade UI. The price-cell **hover** mirrors `view/ui/goods/UIGoodsTraders`
(faction banner + its price + "you have/don't have a trade agreement", sorted best-first), and a
**click** deep-links to the vanilla screen (`VIEW.world().UI.factions.openTrade(f)` when
attainable, else `openDip(f)`). The same realizable-vs-available split applies symmetrically to
imports (a non-partner may *sell* a raw material cheaper) via `priceSellP()`.

---

## 6. Architecture

Package: `your.mod` (existing). Build on vanilla widgets — `GTableBuilder`, `GScrollRows`,
`GStat`, `GButt`, `GBox`, `GFORMAT` — and model the panel on `view/ui/goods/UIGoods`
(`IFullView` + `GTableBuilder`) for free scrolling/layout and a native look.

| Class | Status | Purpose |
|---|---|---|
| `MainScript` (`SCRIPT`) | ✅ exists | Mod entry point / registration |
| `InstanceScript` (`SCRIPT_INSTANCE`) | ✅ exists | `createInstance()` performs UI injection (see lifecycle in CLAUDE.md) |
| `GameUiApi` | ✅ exists | `injectIntoSettlementUITopPanel(RENDEROBJ)` |
| `ReflectionUtil` | ✅ exists | Private-field access helper |
| `EconomyPanel` | new | The full-view table: header, scroll, sort/filter controls, totals row |
| `EconomyColumn` | new | **Enum of column definitions** — header, tier, value extractor, formatter, sortable, default-visible. Drives rendering *and* sort/filter uniformly |
| `EconomyRow` | new | Per-resource computed snapshot, rebuilt on refresh |
| `EconomyData` | new | The only class that touches game APIs: builds rows, computes derived metrics, applies sort + filter |
| `Format` | new | Number / gold / days / Δ formatting (wrap `GFORMAT`) |

**Refresh cadence:** rebuild rows on open and throttled while open (the render-time `update()`
pattern `UIGoods` uses), not the heavy calculations every frame.

---

## 7. Milestones

### Foundation — *v0.1: the consolidated table*

- [x] **M1 — Build template.** Mod compiles and appears in the launcher. *(done)*
- [x] **M2 — ECON button.** Injected into the settlement top bar via `GameUiApi`. *(done)*
- [ ] **M3 — Panel + table scaffold.** Clicking ECON opens a closable full-view panel that
  lists every `RESOURCES.ALL()` entry (icon + name), grouped by category, scrollable. Reuse
  `GTableBuilder`/`IFullView`.
  - *Done when:* opens/closes cleanly and repeatedly; lists all resources; no crash on view
    switch or new settlement.
- [ ] **M4 — Core columns.** Add Stored, Fill %, Prod/day, Cons/day, Net/day from
  `STOCKPILE.tally()` + `SETT.ROOMS().PROD`.
  - *Done when:* values match a spot-check against the vanilla resource tooltip; update as the
    game runs.

### Tier 1 — *v0.2: triage tools (the differentiators)*

- [ ] **M5 — Runway + Status flag.** Add the Runway column and the derived Status ⚑ (deficit /
  low runway / near-overflow / out-of-stock).
  - *Done when:* a forced deficit shows a shrinking runway and a flag; surpluses show `—`.
- [ ] **M6 — Sort & filter.** Click-to-sort any numeric column; filters for negative-net and
  food-only; reuse the search box pattern from `UIGoods`.
  - *Done when:* sorting by Net surfaces worst deficits first; filters narrow the list; search
    matches names.

### Tier 2 — *v0.3: depth*

- [ ] **M7 — Loss/economic columns.** Spoil/day, Maint/day, Import/Export/day (from
  `FResources`), and Δ7d trend (from `amountsDay()` history). `Value` and `Net gold/day` are
  computed from the *realizable* sell price defined in M8, never a global average.
  - *Done when:* spoilage matches a known-decaying good; value tracks price changes.
- [ ] **M8 — Trade-aware pricing & opportunity.** Add **Sell @ now** (best realizable price) and
  **Best @ avail** (best reachable price, tagged *trading / needs-deal / locked*), and the
  trade-opportunity sub-flag on Status. Price-cell hover lists partners with price + agreement
  status (mirroring `UIGoodsTraders`); click deep-links to the vanilla trade/diplomacy screen.
  - *Done when:* a good a non-partner values higher shows Best@avail > Sell@now and an
    opportunity flag; the hover lists factions with correct "agreement / no agreement" status;
    clicking opens vanilla's trade screen for that faction.
- [ ] **M9 — Totals row.** Σ Value, Σ Net gold/day, and days-of-food over `RESOURCES.EDI()`.
  - *Done when:* totals recompute with the filtered view; food estimate is sane vs. population.

### Tier 3 & Release — *v0.4 → v1.0*

- [ ] **M10 — Polish.** Net % column, optional column show/hide, click-through (row → biggest
  consumer room or region), color-coding negatives, alignment, scroll, resolution-fit.
- [ ] **M11 — Testing & stability.** Section 8 checklist; several in-game days without crash;
  save/load unaffected.
- [ ] **M12 — Workshop release.** `_Info.txt`, `preview.png`, supported-version + known-limits
  on the page.

---

## 8. Testing Checklist

```text
[ ] Game launches with the mod enabled; existing save loads; new settlement starts.
[ ] ECON button appears only in the settlement context.
[ ] Panel opens, scrolls, sorts, filters, and closes without crashing.
[ ] Stored / Prod / Cons / Net match the vanilla resource tooltip for several resources.
[ ] Runway and Status react correctly to a deliberately induced deficit.
[ ] Spoilage / Value / Trend populate and update over time.
[ ] A good a non-partner values higher shows Best@avail > Sell@now and an opportunity flag.
[ ] Price hover lists factions with correct agreement status; click opens the trade screen.
[ ] Totals row and days-of-food are reasonable.
[ ] No crash after several in-game days; save/load still works.
```

---

## 9. Design Decisions

- **Read-only first release.** No save data written; the mod only reads and renders.
- **Inject, don't replace.** Add UI via reflection into the top panel (per CLAUDE.md); never
  subclass/replace vanilla UI — better mod compatibility and update resilience.
- **Match vanilla numbers.** Read the *same* sources vanilla reads (`SETT.ROOMS().PROD`,
  `STOCKPILE.tally()`) so the table can't diverge from the tooltip; keep the PROD/FResources
  distinction explicit (section 5).
- **Honest, agreement-aware prices.** Money columns use the *realizable* price (current trade
  partners); a separate column and flag surface better prices behind attainable agreements.
  Never show a single context-free price. Surface partners + link to vanilla's trade screen
  rather than rebuilding it.
- **Column-driven UI.** A single `EconomyColumn` definition list powers rendering, sorting,
  filtering, and show/hide — adding a column is one entry, not edits in four places.
- **Reuse vanilla widgets** (`GTableBuilder`, `GScrollRows`, `GStat`, `GFORMAT`, `GButt`) for a
  native look and less custom layout code.
- **Defensive formatting.** Infinite runway → `—`; zero capacity → full; unknown/missing →
  blank, never a crash.

---

## 10. Version Roadmap

| Version | Milestones | Delivers |
|---|---|---|
| v0.1 | M3–M4 | All-resources table with Stored / Prod / Cons / Net |
| v0.2 | M5–M6 | Runway, Status flag, sorting & filtering |
| v0.3 | M7–M9 | Loss/economic columns, trade-aware pricing & opportunity, trend, totals + days-of-food |
| v0.4 | M10 | Net %, click-through, column toggles, polish |
| v1.0 | M11–M12 | Tested, documented Steam Workshop release |

---

## 11. Next Coding Task

M1–M2 are done. Start **M3**: make the ECON click open a closable full-view panel that lists
every `RESOURCES.ALL()` entry via `GTableBuilder`. Get the scaffold and lifecycle solid before
adding any columns — columns (M4+) are then incremental, low-risk additions on top.

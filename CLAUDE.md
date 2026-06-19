# Economy Overview Mod

Songs of Syx Java code mod (V71) that adds an ECON button to the settlement UI opening a resource economy overview panel.

## Build

```powershell
.\build.ps1                        # clean + build + deploy to game mods folder (default)
.\build.ps1 validate               # reinstall game jar into local Maven repo
.\build.ps1 package                # build only, no deploy
.\build.ps1 'install -DskipTests'  # build + deploy, skip tests
```

Requires JDK 21 at `C:\Users\tgonzalez\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.11+10` and Maven at `C:\Projects\tools\apache-maven-3.9.6\bin`. Both paths are set inside `build.ps1`. On first use after a game update, run `.\build.ps1 validate` before `.\build.ps1` to install the game JAR into the local Maven repo.

Mod deploys to: `%APPDATA%\songsofsyx\mods\Economy Overview\`

## Package

`src/main/java/your/mod/` — all mod source files live here.

## Game Lifecycle Order (critical discovery)

```
ScriptEngine constructor  →  script.initBeforeGameCreated()   # VIEW does not exist yet
                          →  script.initBeforeGameInited()    # VIEW does not exist yet
                          →  new VIEW(game)                   # SettView, UIPanelTop, etc. are created here
                          →  script.createInstance()          # VIEW is fully ready — safe to access VIEW.s()
```

**`SCRIPT.onViewSetup()` does not exist in V71.** The docs example is wrong. Use `createInstance()` for UI injection.

`UIPanelTopSett.addExtraElement()` exists as a static method but is never consumed by the constructor in V71 — it is dead code. Do not use it.

## Settlement UI Injection

To add a button to the settlement top bar, reflect into `VIEW.s().uiManager`:

1. `VIEW.s().uiManager` — public `InterManager` on `SettView`
2. `InterManager.inters` — private `Collection implements Iterable<Interrupter>`
3. Find `UIPanelTop` in that iterable (at `view.ui.top.UIPanelTop`)
4. `UIPanelTop.right` — private `GuiSection`; call `right.addRelBody(8, DIR.W, element)`

`GameUiApi.injectIntoSettlementUITopPanel(RENDEROBJ)` in this project encapsulates steps 1–4.

## IFullView Panel

`IFullView.WIDTH = C.WIDTH() - 32`, `HEIGHT = C.HEIGHT() - TOP_HEIGHT - 8` — dynamic at runtime.

IManager positions `section` at `(16, TOP_HEIGHT)` on `show()` via `GuiSection.Bounds.moveX1/Y1`, which shifts all children by delta. Positioning is idempotent — repeated shows don't re-shift children.

## Economy Data APIs

All settlement economy data accessed via `settlement.main.SETT`:

| Data | API |
|---|---|
| Stored amount | `SETT.ROOMS().STOCKPILE.tally().amountTotal(res)` → `long` |
| Storage capacity | `SETT.ROOMS().STOCKPILE.tally().space.total(res)` → `long` |
| Fill fraction (0–1) | `amountTotal / space.total`; treat as 1.0 when `space == 0` |
| Daily history | `SETT.ROOMS().STOCKPILE.tally().amountsDay().history(res).get(n)` — `get(0)` = in-progress day, `get(1)` = last completed day |
| Producers | `SETT.ROOMS().PROD.producers(res)` → `Iterable<Source>`; sum `rr.am()` |
| Consumers | `SETT.ROOMS().PROD.consumers(res)` → `Iterable<Source>`; sum `rr.am()` |

**`PROD.produced(res)` / `consumed(res)` do not exist.** Iterate `producers/consumers` and sum `Source.am()` manually (matches what `view/ui/goods/Row.java` does).

`Source` import: `settlement.room.industry.module.RoomProduction.Source`

## FResources History API (per-RTYPE daily flows)

`GAME.player().res()` → `FResources`. Use `.in(RTYPE)` / `.out(RTYPE)` to get inflow/outflow history by category:

```java
import game.faction.FResources.RTYPE;

// Last completed day's value:
long spoil  = GAME.player().res().out(RTYPE.SPOILAGE).history(res.tr()).get(1);
long maint  = GAME.player().res().out(RTYPE.MAINTENANCE).history(res.tr()).get(1);
long imp    = GAME.player().res().in(RTYPE.TRADE).history(res.tr()).get(1);
long exp    = GAME.player().res().out(RTYPE.TRADE).history(res.tr()).get(1);
long prod   = GAME.player().res().in(RTYPE.PRODUCED).history(res.tr()).get(1);
long cons   = GAME.player().res().out(RTYPE.CONSUMED).history(res.tr()).get(1);
// total() = net of all in/out (same as used by avg30dNet)
```

Guard with `hist.historyRecords() > 1` before calling `get(1)` to handle game start.

**Full RTYPE enum**: `PRODUCED`, `CONSUMED`, `TRADE`, `TAX`, `CONSTRUCTION`, `FURNISH`, `EQUIPPED`, `MAINTENANCE`, `SPOILAGE`, `ARMY_SUPPLY`, `SPOILS`, `DIPLOMACY`, `THEFT`.

`MAINTENANCE.estimateGlobal(res)` → real-time maintenance estimate (also accessible via RTYPE.MAINTENANCE history).

## Live-Updating Numeric Cells (GStat)

`GStat` (`util.gui.misc.GStat`) is an abstract SPRITE/TITLEABLE that re-evaluates its text every frame:

```java
GStat cell = new GStat() {
    @Override public void update(GText text) {
        GFORMAT.i(text, (long) SETT.ROOMS().STOCKPILE.tally().amountTotal(res));
    }
};
// render in parent's render() override:
cell.render(r, x1, x1 + colWidth, y1, y2);  // calls adjust() + renders left-aligned in bounds
```

`GStat` default font is `UI.FONT().S`. Default buffer is 64 chars — enough for any numeric column.

`adjust()` (called automatically by `render()`) does: `statText.clear()` → `update(statText)` → `statText.adjustWidth()`. Returning early from `update()` without writing anything leaves the cell blank — correct pattern for conditional display (e.g., runway when net ≥ 0).

`GFORMAT` (`util.info.GFORMAT`) formatting methods:

| Method | Output |
|---|---|
| `i(text, long)` | plain integer, abbreviated (1.5K, 2.3M) |
| `iIncr(text, long)` | `+N` green / `-N` red |
| `perc(text, double, int)` | percentage from 0.0–1.0 fraction |
| `iofkNoColor(text, long, long)` | `N/M` capacity display (no color) |

## GText Color Methods

`GText` fluent color setters — all return `GText` for chaining, set a predefined color from the game's palette:

| Method | Color |
|---|---|
| `.lablify()` | H1 gold (header/label) |
| `.lablifySub()` | H2 (sub-header) |
| `.normalify()` | NORMAL (default white) |
| `.errorify()` | ERROR (red) |
| `.warnify()` | WARNING (orange/yellow) |
| `.hoverify()` | HOVERABLE |
| `.clickify()` | CLICKABLE |

**Pattern for status labels**: pre-create one `GText` per state at row-construction time, select and render the right one per frame:

```java
GText statusLow  = new GText(UI.FONT().S, "LOW").errorify();
GText statusFull = new GText(UI.FONT().S, "NEAR FULL").warnify();
// In render():
if (status == STATUS_LOW) statusLow.render(r, x + COL_STATUS, yText);
```

`GText.render(r, x, y)` — 3-arg form renders top-left at (x, y). Use for static/conditional labels.

## Color System

`GCOLOR` (`util.colors.GCOLOR`) — static accessor for color palettes:

- `GCOLOR.T()` → `GCOLOR_TEXT`: text palette (NORMAL, H1, H2, ERROR, WARNING, IGREAT/IGOOD/INORMAL/IBAD/IWORST)
- `GCOLOR.UI()` → `GCOLOR_UI`: UI palette with model groups GOOD, BAD, SOSO, NEUTRAL, GREAT, NORMAL; each has `.normal`, `.hovered`, `.selected`, `.inactive`

`GCOLOR.UI().badToGood(ColorImp, double 0–1)` — interpolates BAD→SOSO→GOOD; useful for fill% coloring in future polish.

`COLOR` (`snake2d.util.color.COLOR`) — interface on all color objects:
- `color.bind()` / `COLOR.unbind()` — tints subsequent sprite renders
- `COLOR.render(r, x1, x2, y1, y2)` — fills a solid rectangle
- `COLOR.BLACK`, `COLOR.WHITE35`, etc. — named constants

## Table Row Pattern

Data rows use `HOVERABLE.HoverableAbs(WIDTH, ROW_H)` with manual rendering in the `render()` override. This avoids GuiSection child-positioning complexity for fixed-column layouts:

```java
return new HOVERABLE.HoverableAbs(WIDTH, ROW_H) {
    @Override
    protected void render(SPRITE_RENDERER r, float ds, boolean isHovered) {
        int x = body.x1(), y1 = body.y1(), y2 = body.y2();
        res.icon().small.render(r, x + COL_ICON, y1 + (ROW_H - Icon.S) / 2);
        nameText.render(r, x + COL_NAME, y1 + (ROW_H - nameText.height()) / 2);
        storedStat.render(r, x + COL_STORED, x + COL_STORED + COL_W, y1, y2);
        // ...
    }
    @Override public void hoverInfoGet(GUI_BOX text) { res.hoverDetailed(text); }
};
```

Column header row uses `RENDEROBJ.RenderImp` (non-interactive) and renders `GText` labels at the same offsets.

**Use `UI.FONT().S` for all column headers and name text.** H2 font glyphs are wide enough to overflow into adjacent columns at typical column gaps (70–80 px). FONT().S matches the GStat default and stays within bounds.

## Key Import Paths

| Class | Import |
|---|---|
| `GButt.Panel` | `util.gui.misc.GButt` |
| `RENDEROBJ` | `snake2d.util.gui.renderable.RENDEROBJ` |
| `GuiSection` | `snake2d.util.gui.GuiSection` |
| `DIR` | `snake2d.util.datatypes.DIR` |
| `UIPanelTop` | `view.ui.top.UIPanelTop` |
| `SettView` | `view.sett.SettView` |
| `VIEW` | `view.main.VIEW` |
| `SCRIPT` | `script.SCRIPT` |
| `SETT` | `settlement.main.SETT` |
| `Source` | `settlement.room.industry.module.RoomProduction.Source` |
| `GStat` | `util.gui.misc.GStat` |
| `GText` | `util.gui.misc.GText` |
| `GFORMAT` | `util.info.GFORMAT` |
| `IFullView` | `view.ui.manage.IFullView` |
| `GScrollRows` | `util.gui.table.GScrollRows` |
| `GHeader` | `util.gui.misc.GHeader` |
| `GCOLOR` | `util.colors.GCOLOR` |
| `COLOR` | `snake2d.util.color.COLOR` |

## Trade Pricing APIs

`FACTIONS.player().trade` → `PTrade` — tracks live market prices:

| Field | Type | API |
|---|---|---|
| Best sell price (what NPCs pay) | `HistoryTradable` | `FACTIONS.player().trade.pricesSell.get(res.tr())` → `int` |
| Market average price | `HistoryTradable` | `FACTIONS.player().trade.pricesAve.get(res.tr())` → `int` |
| Best buy price (what NPCs sell at) | `HistoryTradable` | `FACTIONS.player().trade.pricesBuy.get(res.tr())` → `int` |

`SETT.TRADE().seller(res.tr())` → `PSeller` — player's export settings for a resource:
- `.priceCapsI.get()` — player's minimum sell price (price cap)
- `.problem()` — why not exporting (`null` = exporting OK)
- `.warning()` — non-blocking warnings

**Best@avail** = scan reachable NPCs with `ff.res(res.tr()).priceBuyP()` and return the max. `priceBuyP()` = `priceAt(1) - totalFee(player, npc, distance, 1)` — trade-route pricing after toll, no manual-deal penalty. **Do NOT use `DealParty.manualPriceBuy()`** — it applies a 0.8× penalty for one-time deals, making Best@avail always lower than Sell@now (wrong). **Must guard with `ff.isActive() && RD.DIST().reachable(ff)`** — toll alone does not filter unreachable factions. Import: `game.faction.npc.FactionNPC`, `world.region.RD`.

`PTrade.pricesSell` is populated using `priceBuyP()` over current trade partners — same method, but restricted to established partners. Best@avail scans all reachable NPCs, so it can exceed Sell@now when a better deal is available from a non-current partner.

**Opportunity flag pattern**: `bestAvail > sellNow` → better deal available than current active price → color `GCOLOR.T().IGOOD`.

**Click-through to game trade panel**: `VIEW.UI().goods.detail(res, GAME.player())` — opens `UIGoods` focused on the resource, closing the current panel.

`HistoryTradable` extends `HistoryObject<TRADABLE>` — use `.get(TRADABLE)` for current value, `.history(TRADABLE).get(n)` for day-history (same pattern as `FResources`).

**ResourceRow must extend `CLICKABLE.ClickableAbs`** (not `HoverableAbs`) to support click actions. Render signature: `render(r, ds, isActive, isSelected, isHovered)`. Set click action with `clickActionSet(() -> ...)`.

`GBox.error()` does NOT exist. For error/warning text in hover tooltips, pre-create `GText` objects or use plain `textL()`.

## Milestone Status

- [x] Milestone 1 — Build template, mod appears in launcher
- [x] Milestone 2 — ECON button injected into settlement UI top bar
- [x] Milestone 3 — Scrollable resource panel (icon + name, by category) opens on ECON click
- [x] Milestone 4 — Stored, Fill%, Prod/d, Cons/d, Net/d columns with live GStat cells
- [x] Milestone 5 — Runway column + Status ⚑ flag (deficit / low runway / near-overflow / out-of-stock)
- [x] Milestone 6 — Sort & filter (click-to-sort, negative-net/food-only filters, search box)
- [x] Milestone 7 — Loss/economic columns: Spoil/d, Maint/d, Import/Export/d, Δ7d trend (Net/7d)
- [x] Milestone 8 — Trade-aware pricing: Sell@now, Best@avail, opportunity flag, hover + click-through
- [x] Milestone 9 — Totals row: Σ Value, Σ Net gold/day, days-of-food
- [ ] Milestone 10 — Polish: Net%, column show/hide, color-coding, click-through
- [ ] Milestone 11 — Testing & stability
- [ ] Milestone 12 — Workshop release

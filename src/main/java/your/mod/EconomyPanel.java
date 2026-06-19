package your.mod;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.FResources.RTYPE;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import init.constant.C;
import init.paths.PATH;
import init.paths.PATHS;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.sprite.UI.Icon;
import init.sprite.UI.UI;
import settlement.main.SETT;
import settlement.room.industry.module.RoomProduction.Source;
import snake2d.MButt;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.file.Json;
import snake2d.util.file.JsonE;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.text.StringInputSprite;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GInput;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GScrollRows;
import util.info.GFORMAT;
import view.main.VIEW;
import view.ui.manage.IFullView;
import world.region.RD;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EconomyPanel extends IFullView {

    private enum SortCol { NAME, STORED, FILL, NET30, HIST_RW, PROJ_RW, STATUS, SPOIL, MAINT, IMPORT, EXPORT, DELTA7, SELL_NOW, BEST_AVAIL }

    private static final int PAD   = 6;   // inner left/right padding
    private static final int IND_W = 12;  // static reserved gutter for the ^/v sort indicator

    /**
     * Single source of truth for column geometry. {@code x} and {@code w} are
     * measured/computed at construction in {@link #layoutColumns()} so columns
     * always fit their label (+ indicator gutter) and widest sample value,
     * regardless of font metrics.
     */
    private enum Col {
        NAME   ("Resource",   SortCol.NAME,       null),       // width handled specially (icon + names)
        STORED ("Stored",     SortCol.STORED,     "1.02K"),
        FILL   ("Fill%",      SortCol.FILL,       "100%"),
        NET30  ("Net/30d",    SortCol.NET30,      "+1.0K"),
        HIST   ("Hist.Run",   SortCol.HIST_RW,    "999"),
        PROJ   ("Proj.Run",   SortCol.PROJ_RW,    "999"),
        STATUS ("Status",     SortCol.STATUS,     "NEAR FULL"),
        SPOIL  ("Spoil/d",    SortCol.SPOIL,      "999"),
        MAINT  ("Maint/d",    SortCol.MAINT,      "999"),
        IMPORT ("Import/d",   SortCol.IMPORT,     "999"),
        EXPORT ("Export/d",   SortCol.EXPORT,     "999"),
        DELTA7 ("Net/7d",     SortCol.DELTA7,     "+1.0K"),
        SELL   ("Sell@now",   SortCol.SELL_NOW,   "1.02K"),
        AVG    ("Best@avail", SortCol.BEST_AVAIL, "1.02K");

        final String  label;
        final SortCol sort;
        final String  sample;  // widest representative data value (null = name column)
        int x;
        int w;

        Col(String label, SortCol sort, String sample) {
            this.label = label; this.sort = sort; this.sample = sample;
        }
    }

    /** Measure each column to fit label + indicator gutter and widest sample, then lay out x cumulatively. */
    private void layoutColumns() {
        for (Col c : Col.values()) {
            if (c == Col.NAME) {
                int longest = 0;
                for (RESOURCE res : RESOURCES.ALL()) {
                    longest = Math.max(longest, new GText(UI.FONT().S, res.name).width());
                }
                // icon + gap + longest name, plus the indicator gutter (Resource is sortable too)
                c.w = Icon.S + 8 + longest + IND_W + 2 * PAD;
            } else {
                int labelW = new GText(UI.FONT().S, c.label).width();
                int dataW  = c.sample == null ? 0 : new GText(UI.FONT().S, c.sample).width();
                // header needs label + gutter; data needs its sample — take the wider, add padding
                c.w = Math.max(labelW + IND_W, dataW) + 2 * PAD;
            }
        }
        int cx = 0;
        for (Col c : Col.values()) { c.x = cx; cx += c.w; }
    }

    private static final int ROW_H        = Icon.S + 8;
    private static final int SEARCH_CHARS = 20;

    private static final int    STATUS_OK      = 0;
    private static final int    STATUS_FULL    = 1;
    private static final int    STATUS_DEFICIT = 2;
    private static final int    STATUS_LOW     = 3;
    private static final int    STATUS_EMPTY   = 4;

    private static final int    LOW_RUNWAY_DAYS = 7;
    private static final double OVERFLOW_FILL   = 0.9;
    private static final int    NET30_DAYS      = 30;
    private static final long   RUNWAY_CAP      = 999;  // runway day-counts above this show "999+"
    private static final double WINSOR_FRACTION = 0.10; // fraction of days clamped at each end for Hist.Run

    /** Resource column cycles through three distinct orderings. */
    private enum NameSort { CATEGORY, ASC, DESC }

    private SortCol  sortCol       = SortCol.NAME;
    private boolean  sortAsc       = true;
    private NameSort nameSort      = NameSort.CATEGORY;
    private boolean filterDeficit = false;
    private boolean filterFood    = false;
    private StringInputSprite searchSprite;

    /** Settings file (under %APPDATA%\songsofsyx\settings) persisting hide choices across restarts. */
    private static final String CONFIG_FILE = "EconomyOverview";

    private final Set<RESOURCE> hiddenPins = new HashSet<>(); // force-hidden resources
    private final Set<RESOURCE> shownPins  = new HashSet<>(); // force-shown (overrides auto-hide)
    private boolean autoHide   = false;
    private boolean showHidden = false;                       // view toggle, not persisted

    private ResourceRow[]  resourceRows;
    private RENDEROBJ[]    rowsArray;
    private RENDEROBJ[]    defaultOrder;

    private FilterableScroll scroll;
    private RENDEROBJ        manualPage;

    public EconomyPanel() {
        super("Economy Overview", UI.icons().l.crate);
        section.body().setWidth(WIDTH).setHeight(0);

        loadConfig();
        layoutColumns();
        buildAllRows();

        GuiSection filterBar = buildFilterBar();
        // Totals row is pinned at HEIGHT - ROW_H; scroll gets remaining space minus gaps
        int scrollH = HEIGHT - filterBar.body().height() - 2 * ROW_H - 16;
        scroll = new FilterableScroll(rowsArray, scrollH, WIDTH);

        section.addDown(0, filterBar);
        section.addDown(4, buildHeaderRow());
        section.addDown(4, scroll.view());
        section.add(buildTotalsRow(), 0, HEIGHT - ROW_H);
    }

    // ── Row initialization ───────────────────────────────────────────────────

    private void buildAllRows() {
        List<ResourceRow> resList = new ArrayList<>();
        List<RENDEROBJ>   allRows = new ArrayList<>();
        int cat = -1;
        for (RESOURCE res : RESOURCES.ALL()) {
            if (res.category != cat) {
                if (cat != -1) allRows.add(separatorRow());
                cat = res.category;
            }
            ResourceRow row = new ResourceRow(res);
            resList.add(row);
            allRows.add(row);
        }
        resourceRows = resList.toArray(new ResourceRow[0]);
        rowsArray    = allRows.toArray(new RENDEROBJ[0]);
        defaultOrder = rowsArray.clone();
    }

    // ── Filter bar ───────────────────────────────────────────────────────────

    private GuiSection buildFilterBar() {
        searchSprite = new StringInputSprite(SEARCH_CHARS, UI.FONT().S) {
            @Override protected void change() {
                if (scroll != null) scroll.init();
            }
        }.placeHolder("Search...");

        GInput searchInput = new GInput(searchSprite);

        GButt.Checkbox deficitBtn = new GButt.Checkbox("Deficit");
        deficitBtn.hoverInfoSet("Show only resources with a negative net balance");
        deficitBtn.clickActionSet(() -> {
            filterDeficit = !filterDeficit;
            deficitBtn.selectedSet(filterDeficit);
            if (scroll != null) scroll.init();
        });

        GButt.Checkbox foodBtn = new GButt.Checkbox("Food");
        foodBtn.hoverInfoSet("Show only food resources");
        foodBtn.clickActionSet(() -> {
            filterFood = !filterFood;
            foodBtn.selectedSet(filterFood);
            if (scroll != null) scroll.init();
        });

        GButt.Checkbox autoHideBtn = new GButt.Checkbox("Auto-hide");
        autoHideBtn.selectedSet(autoHide);
        autoHideBtn.hoverInfoSet("Hide resources you neither produce nor have in storage");
        autoHideBtn.clickActionSet(() -> {
            autoHide = !autoHide;
            autoHideBtn.selectedSet(autoHide);
            saveConfig();
            if (scroll != null) scroll.init();
        });

        GButt.Checkbox showHiddenBtn = new GButt.Checkbox("Show hidden");
        showHiddenBtn.selectedSet(showHidden);
        showHiddenBtn.hoverInfoSet("Reveal hidden rows (dimmed) so you can restore them");
        showHiddenBtn.clickActionSet(() -> {
            showHidden = !showHidden;
            showHiddenBtn.selectedSet(showHidden);
            if (scroll != null) scroll.init();
        });

        manualPage = buildManualPage();
        GButt.Panel manualBtn = new GButt.Panel("Manual");
        manualBtn.hoverInfoSet("Field guide: what each column means and how it is calculated");
        manualBtn.clickActionSet(() -> VIEW.inters().popup.show(manualPage, manualBtn));

        GuiSection bar = new GuiSection();
        bar.add(searchInput, 0, 0);
        bar.addRight(8, deficitBtn);
        bar.addRight(8, foodBtn);
        bar.addRight(8, autoHideBtn);
        bar.addRight(8, showHiddenBtn);
        bar.addRight(8, manualBtn);
        return bar;
    }

    // ── Manual / field guide ─────────────────────────────────────────────────

    /** Build the scrollable "field guide" shown by the Manual button. */
    private RENDEROBJ buildManualPage() {
        final int W = 600;
        List<RENDEROBJ> rows = new ArrayList<>();

        rows.add(manualHeader(W, "Economy Overview - Field Guide"));
        rows.add(manualPara(W,
            "All values are read live from the game's own economy data (SETT.ROOMS and "
            + "the faction resource tally) - the same numbers the vanilla Goods screen "
            + "uses. \"/d\" means per day; daily figures are the last completed day unless "
            + "noted otherwise."));

        rows.add(manualEntry(W, "Stored",
            "Units currently held in stockpiles. Click the cell to open the goods/storage "
            + "view for that resource."));
        rows.add(manualEntry(W, "Fill%",
            "Stored divided by total storage capacity. Blank when there is no capacity for "
            + "the good."));
        rows.add(manualEntry(W, "Net/30d",
            "Average true net change per day over the last 30 days, across every flow type "
            + "(production, citizens eating, trade, construction, maintenance, spoilage, tax "
            + "and so on). Green is a surplus, red is a deficit."));

        rows.add(manualEntry(W, "Hist.Run  (Historical Runway)",
            "Days until the stockpile empties at the recent trend. It divides Stored by a "
            + "robust 30-day average net: the most extreme " + (int) (WINSOR_FRACTION * 100)
            + "% of days at each end are clamped before averaging, so a single huge day (a war, "
            + "a finished building, a big caravan) cannot dominate, while recurring flows like "
            + "regular trade are still counted. This can differ from the raw Net/30d column, "
            + "which is a plain average. \"+\" means no net loss. Capped at " + RUNWAY_CAP + "+."));
        rows.add(manualEntry(W, "Proj.Run  (Projected Runway)",
            "Days until empty at your current structural rate: Stored / projected net per day. "
            + "The projection sums the game's live production and consumption estimates "
            + "(industry, citizens eating, maintenance, spoilage, equipment wear, army supply, "
            + "housing, temples and tax) and folds in trade and construction from the last "
            + "completed day. It reacts immediately when you change your production setup, "
            + "whereas Hist.Run lags. \"+\" means no net loss. Capped at " + RUNWAY_CAP + "+."));
        rows.add(manualEntry(W, "Reading the two runways together",
            "Proj healthy but Hist falling = a recent one-off event drained you (a big trade "
            + "or construction project). Proj falling but Hist still healthy = a change you "
            + "just made will bite soon. They differ mainly because Hist averages 30 days of "
            + "actuals while Proj is a current-rate estimate."));

        rows.add(manualEntry(W, "Status",
            "Flag derived from stored level and the same robust 30-day net as Hist.Run: "
            + "OUT = empty; LOW = in deficit "
            + "with under " + LOW_RUNWAY_DAYS + " days left; DEFICIT = shrinking; NEAR FULL = at "
            + "or above " + (int) (OVERFLOW_FILL * 100) + "% of capacity."));
        rows.add(manualEntry(W, "Spoil/d  /  Maint/d",
            "Goods lost to spoilage, and goods consumed by building maintenance, on the last "
            + "completed day."));
        rows.add(manualEntry(W, "Import/d  /  Export/d",
            "Units bought in and sold out via trade on the last completed day."));
        rows.add(manualEntry(W, "Net/7d",
            "Like Net/30d but averaged over the last 7 days - a shorter, more responsive "
            + "trend."));
        rows.add(manualEntry(W, "Sell@now",
            "Best price an active trade partner currently pays for the good. Click to open "
            + "that trade deal."));
        rows.add(manualEntry(W, "Best@avail",
            "Best price any reachable faction would pay, including factions you do not yet "
            + "trade with. Shown green when it beats Sell@now - a better deal is available. "
            + "Click to open it."));
        rows.add(manualEntry(W, "Totals row",
            "Sum Net/d = sum of (30-day net x sell price) over visible rows; Sum Value = "
            + "stored x sell price; Food = total stored food / total daily food eaten. "
            + "Hidden rows are excluded from the totals."));

        rows.add(manualEntry(W, "Hiding rows  (the x)",
            "Hover a row and click the small \"x\" at the right of the Resource name to hide "
            + "it. Hidden rows drop out of the list and the totals. Your choices are saved "
            + "and restored the next time you play."));
        rows.add(manualEntry(W, "Auto-hide",
            "Hides every resource you currently produce none of and have none of in storage "
            + "- the goods that don't matter for this settlement. Anything you make or hold "
            + "stays. Resources reappear automatically once you start producing or storing "
            + "them again."));
        rows.add(manualEntry(W, "Show hidden",
            "Reveals all hidden rows (shown dimmed) so you can review them. Click the \"+\" "
            + "marker on a dimmed row to bring it back. Restoring an auto-hidden resource "
            + "pins it visible even while Auto-hide is on."));

        rows.add(manualPara(W, "Right-click, or the X in the corner, closes this guide."));

        int totalH = 0;
        for (RENDEROBJ rr : rows) totalH += rr.body().height();
        int h = Math.min(totalH + 4, (C.HEIGHT() * 2) / 3);
        return new GScrollRows(rows.toArray(new RENDEROBJ[0]), h, W).view();
    }

    private static RENDEROBJ manualHeader(int width, String text) {
        GText t = new GText(UI.FONT().H2, text).lablify();
        t.setMaxWidth(width - 12);
        final int th = t.height();
        return new RENDEROBJ.RenderImp(width, th + 12) {
            @Override public void render(SPRITE_RENDERER r, float ds) {
                int x = body.x1() + 6;
                int y = body.y1() + 6;
                t.render(r, x, x + width - 12, y, y + th);
            }
        };
    }

    private static RENDEROBJ manualPara(int width, String text) {
        GText t = new GText(UI.FONT().S, text).normalify();
        t.setMaxWidth(width - 12);
        t.setMultipleLines(true);
        final int th = t.height();
        return new RENDEROBJ.RenderImp(width, th + 8) {
            @Override public void render(SPRITE_RENDERER r, float ds) {
                int x = body.x1() + 6;
                int y = body.y1() + 4;
                t.render(r, x, x + width - 12, y, y + th);
            }
        };
    }

    private static RENDEROBJ manualEntry(int width, String head, String desc) {
        GText h = new GText(UI.FONT().S, head).lablify();
        h.setMaxWidth(width - 12);
        GText b = new GText(UI.FONT().S, desc).normalify();
        b.setMaxWidth(width - 12);
        b.setMultipleLines(true);
        final int hh = h.height();
        final int bh = b.height();
        return new RENDEROBJ.RenderImp(width, hh + 2 + bh + 10) {
            @Override public void render(SPRITE_RENDERER r, float ds) {
                int x = body.x1() + 6;
                int y = body.y1() + 4;
                h.render(r, x, x + width - 12, y, y + hh);
                b.render(r, x, x + width - 12, y + hh + 2, y + hh + 2 + bh);
            }
        };
    }

    // ── Sortable header row ──────────────────────────────────────────────────

    private static void drawColLines(SPRITE_RENDERER r, int x, int y1, int y2) {
        for (Col c : Col.values()) {
            if (c == Col.NAME) continue; // no separator before the first column
            COLOR.WHITE35.render(r, x + c.x - 1, x + c.x, y1, y2);
        }
    }

    private RENDEROBJ buildHeaderRow() {
        GuiSection row = new GuiSection() {
            @Override public void render(SPRITE_RENDERER r, float ds) {
                drawColLines(r, body().x1(), body().y1(), body().y2());
                super.render(r, ds);
                // Full-width bottom border separating header from data rows
                COLOR.WHITE35.render(r, body().x1(), body().x1() + WIDTH, body().y2() - 1, body().y2());
            }
        };
        row.body().setWidth(WIDTH).setHeight(ROW_H);
        for (Col c : Col.values()) addSortCol(row, c);
        return row;
    }

    private void addSortCol(GuiSection row, Col c) {
        GText normal  = new GText(UI.FONT().S, c.label).lablify();
        GText normalH = new GText(UI.FONT().S, c.label).clickify();
        GText ascInd  = new GText(UI.FONT().S, "^").lablify();
        GText ascIndH = new GText(UI.FONT().S, "^").clickify();
        GText desInd  = new GText(UI.FONT().S, "v").lablify();
        GText desIndH = new GText(UI.FONT().S, "v").clickify();

        CLICKABLE cell = new CLICKABLE.ClickableAbs(c.w, ROW_H) {
            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSel, boolean isHovered) {
                boolean sorted;
                GText ind;  // null = no arrow (Resource in category mode, or not the active column)
                if (c.sort == SortCol.NAME) {
                    boolean active = (sortCol == SortCol.NAME);
                    // category is the natural default order → shown without highlight or arrow
                    sorted = active && nameSort != NameSort.CATEGORY;
                    if (active && nameSort == NameSort.ASC)       ind = isHovered ? ascIndH : ascInd;
                    else if (active && nameSort == NameSort.DESC) ind = isHovered ? desIndH : desInd;
                    else                                          ind = null;
                } else {
                    sorted = (sortCol == c.sort);
                    ind = sorted ? (sortAsc ? (isHovered ? ascIndH : ascInd) : (isHovered ? desIndH : desInd)) : null;
                }
                GButt.ButtPanel.renderBG(r, isActive, sorted, isHovered, body);

                int yText = body.y1() + (ROW_H - normal.height()) / 2;
                // Label fixed at left edge — IND_W gutter on the right is always reserved,
                // so the label never shifts and the indicator never overlaps it.
                (isHovered ? normalH : normal).render(r, body.x1() + PAD, yText);
                if (ind != null) {
                    ind.render(r, body.x1() + c.w - IND_W + (IND_W - ind.width()) / 2, yText);
                }
            }
        };
        cell.clickActionSet(() -> onSortChange(c.sort));
        cell.hoverInfoSet(c.sort == SortCol.NAME
            ? "Sort: by category / A→Z / Z→A (click to cycle)"
            : "Sort by " + c.label);
        row.add(cell, c.x, 0);
    }

    // ── Sort logic ───────────────────────────────────────────────────────────

    private void onSortChange(SortCol col) {
        if (col == SortCol.NAME) {
            // Resource cycles: by category (default) → A→Z → Z→A → by category …
            nameSort = nextNameSort(nameSort);
            sortCol  = SortCol.NAME;
        } else if (sortCol == col) {
            sortAsc = !sortAsc;
        } else {
            sortCol = col;
            sortAsc = true;
        }
        applySortToArray();
        scroll.target.set(0);
        scroll.init();
    }

    private static NameSort nextNameSort(NameSort n) {
        switch (n) {
            case CATEGORY: return NameSort.ASC;
            case ASC:      return NameSort.DESC;
            default:       return NameSort.CATEGORY;
        }
    }

    private void applySortToArray() {
        if (sortCol == SortCol.NAME && nameSort == NameSort.CATEGORY) {
            System.arraycopy(defaultOrder, 0, rowsArray, 0, rowsArray.length);
            return;
        }
        ResourceRow[] sorted = resourceRows.clone();
        Arrays.sort(sorted, makeComparator());
        int i = 0;
        for (ResourceRow rr : sorted) rowsArray[i++] = rr;
        for (RENDEROBJ r : defaultOrder) {
            if (!(r instanceof ResourceRow)) rowsArray[i++] = r;
        }
    }

    private Comparator<ResourceRow> makeComparator() {
        Comparator<ResourceRow> cmp;
        switch (sortCol) {
            case STORED:
                cmp = Comparator.comparingDouble(rr ->
                        SETT.ROOMS().STOCKPILE.tally().amountTotal(rr.res));
                break;
            case FILL:
                cmp = Comparator.comparingDouble(rr -> {
                    double space  = SETT.ROOMS().STOCKPILE.tally().space.total(rr.res);
                    double amount = SETT.ROOMS().STOCKPILE.tally().amountTotal(rr.res);
                    return space <= 0 ? 0.0 : amount / space;
                });
                break;
            case NET30:
                cmp = Comparator.comparingLong(rr -> avg30dNet(rr.res));
                break;
            case HIST_RW:
                cmp = Comparator.comparingDouble(rr -> histRunway(rr.res));
                break;
            case PROJ_RW:
                cmp = Comparator.comparingDouble(rr -> projRunway(rr.res));
                break;
            case STATUS:
                cmp = Comparator.comparingInt(rr -> computeStatus(rr.res));
                break;
            case SPOIL:
                cmp = Comparator.comparingLong(rr -> lastDayOut(RTYPE.SPOILAGE, rr.res));
                break;
            case MAINT:
                cmp = Comparator.comparingLong(rr -> lastDayOut(RTYPE.MAINTENANCE, rr.res));
                break;
            case IMPORT:
                cmp = Comparator.comparingLong(rr -> lastDayIn(RTYPE.TRADE, rr.res));
                break;
            case EXPORT:
                cmp = Comparator.comparingLong(rr -> lastDayOut(RTYPE.TRADE, rr.res));
                break;
            case DELTA7:
                cmp = Comparator.comparingLong(rr -> avg7dNet(rr.res));
                break;
            case SELL_NOW:
                cmp = Comparator.comparingInt(rr -> sellNow(rr.res));
                break;
            case BEST_AVAIL:
                cmp = Comparator.comparingInt(rr -> bestAvail(rr.res));
                break;
            default: // NAME — uses nameSort, not sortAsc
                cmp = Comparator.comparing(rr -> rr.res.name.toString());
                return nameSort == NameSort.DESC ? cmp.reversed() : cmp;
        }
        return sortAsc ? cmp : cmp.reversed();
    }

    // ── Economy data helpers ─────────────────────────────────────────────────

    /**
     * Average true net change per day over the last 30 completed days, kept as a
     * {@code double} so sub-unit daily drains are not silently floored to zero
     * (which would otherwise read as an infinite runway). Uses {@code total()},
     * which is the net of every flow type — production, eating, trade,
     * construction, maintenance, spoilage, tax, etc.
     */
    private static double avg30dNetD(RESOURCE res) {
        var hist = GAME.player().res().total().history(res.tr());
        int days = Math.min(NET30_DAYS, hist.historyRecords() - 1);
        if (days <= 0) return 0;
        long total = 0;
        for (int i = 1; i <= days; i++) total += hist.get(i);
        return (double) total / days;
    }

    /** Rounded integer form of {@link #avg30dNetD} for display/sorting columns. */
    private static long avg30dNet(RESOURCE res) {
        return Math.round(avg30dNetD(res));
    }

    /**
     * Forward-looking net/day estimate. Sums the game's live production and
     * consumption sources (industry, citizens eating, maintenance, spoilage,
     * equipment, army supply, housing, temples, tax) as {@code double}s — casting
     * only once, so fractional flows are not lost — then folds in trade and
     * construction, which are not represented as PROD sources, using the last
     * completed day's actuals.
     */
    private static double projNetD(RESOURCE res) {
        double net = 0;
        for (Source s : SETT.ROOMS().PROD.producers(res)) net += s.am();
        for (Source s : SETT.ROOMS().PROD.consumers(res)) net -= s.am();
        net += lastDayIn(RTYPE.TRADE, res)        - lastDayOut(RTYPE.TRADE, res);
        net += lastDayIn(RTYPE.CONSTRUCTION, res) - lastDayOut(RTYPE.CONSTRUCTION, res);
        return net;
    }

    /**
     * Robust average daily net over the last {@link #NET30_DAYS} days, used for the
     * historical runway and Status flag. The most extreme {@link #WINSOR_FRACTION} of
     * days at each end are winsorized (clamped to the 10th/90th percentile) before
     * averaging, so a single large one-off day (a war, a finished building, a big
     * caravan) cannot dominate the estimate — while recurring bursty flows like
     * regular trade are still counted. With fewer than 10 days of history this is
     * identical to the plain mean ({@link #avg30dNetD}).
     */
    private static double histNetRobust(RESOURCE res) {
        var hist = GAME.player().res().total().history(res.tr());
        int days = Math.min(NET30_DAYS, hist.historyRecords() - 1);
        if (days <= 0) return 0;
        double[] v = new double[days];
        for (int i = 0; i < days; i++) v[i] = hist.get(i + 1);
        Arrays.sort(v);
        int k = (int) (days * WINSOR_FRACTION);
        if (k > 0) {
            double lo = v[k];
            double hi = v[days - 1 - k];
            for (int i = 0; i < k; i++) { v[i] = lo; v[days - 1 - i] = hi; }
        }
        double total = 0;
        for (double d : v) total += d;
        return total / days;
    }

    private static double histRunway(RESOURCE res) {
        double net = histNetRobust(res);
        if (net >= 0) return Double.MAX_VALUE;
        double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
        return stored <= 0 ? 0 : stored / -net;
    }

    private static double projRunway(RESOURCE res) {
        double net = projNetD(res);
        if (net >= 0) return Double.MAX_VALUE;
        double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
        return stored <= 0 ? 0 : stored / -net;
    }

    /** Render a runway day-count into a cell, capping the display at {@link #RUNWAY_CAP}. */
    private static void renderRunway(GText text, double net, RESOURCE res) {
        if (net >= 0) { text.add("+"); text.color(GCOLOR.T().IGOOD); return; }
        text.normalify();
        double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
        if (stored <= 0) { GFORMAT.i(text, 0L); return; }
        long days = (long) (stored / -net);
        if (days > RUNWAY_CAP) {
            GFORMAT.i(text, RUNWAY_CAP);
            text.add("+");
        } else {
            GFORMAT.i(text, days);
        }
    }

    private static long lastDayIn(RTYPE type, RESOURCE res) {
        var h = GAME.player().res().in(type).history(res.tr());
        return h.historyRecords() > 1 ? (long) h.get(1) : 0L;
    }

    private static long lastDayOut(RTYPE type, RESOURCE res) {
        var h = GAME.player().res().out(type).history(res.tr());
        return h.historyRecords() > 1 ? (long) h.get(1) : 0L;
    }

    private static int sellNow(RESOURCE res) {
        return FACTIONS.player().trade.pricesSell.get(res.tr());
    }

    private static FactionNPC sellNowNpc(RESOURCE res) {
        int best = 0;
        FactionNPC bestNpc = null;
        for (int i = 0; i < FACTIONS.NPCs().size(); i++) {
            FactionNPC ff = FACTIONS.NPCs().get(i);
            if (!ff.isActive() || !RD.DIST().reachable(ff)) continue;
            if (!DIP.TRADE().is(ff)) continue;
            int p = ff.res(res.tr()).priceBuyP();
            if (p > best) { best = p; bestNpc = ff; }
        }
        return bestNpc;
    }

    private static FactionNPC bestAvailNpc(RESOURCE res) {
        int best = 0;
        FactionNPC bestNpc = null;
        for (int i = 0; i < FACTIONS.NPCs().size(); i++) {
            FactionNPC ff = FACTIONS.NPCs().get(i);
            if (!ff.isActive() || !RD.DIST().reachable(ff)) continue;
            int p = ff.res(res.tr()).priceBuyP();
            if (p > best) { best = p; bestNpc = ff; }
        }
        return bestNpc;
    }

    private static int bestAvail(RESOURCE res) {
        FactionNPC npc = bestAvailNpc(res);
        return npc != null ? npc.res(res.tr()).priceBuyP() : 0;
    }

    private static long avg7dNet(RESOURCE res) {
        var hist = GAME.player().res().total().history(res.tr());
        int days = Math.min(7, hist.historyRecords() - 1);
        if (days <= 0) return 0;
        long total = 0;
        for (int i = 1; i <= days; i++) total += hist.get(i);
        return total / days;
    }

    private static int computeStatus(RESOURCE res) {
        double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
        if (stored <= 0) return STATUS_EMPTY;
        double space = SETT.ROOMS().STOCKPILE.tally().space.total(res);
        if (space > 0 && stored / space >= OVERFLOW_FILL) return STATUS_FULL;
        double net = histNetRobust(res);
        if (net < 0) {
            double runway = stored / -net;
            return runway < LOW_RUNWAY_DAYS ? STATUS_LOW : STATUS_DEFICIT;
        }
        return STATUS_OK;
    }

    // ── Filter helper (mirrors FilterableScroll.passesFilter for resource rows) ──

    private boolean isVisible(RESOURCE res) {
        if (isHidden(res) && !showHidden) return false;
        if (filterDeficit && avg30dNetD(res) >= 0) return false;
        if (filterFood && !RESOURCES.EDI().is(res)) return false;
        if (searchSprite != null && searchSprite.text().length() > 0) {
            String q = searchSprite.text().toString().toLowerCase();
            if (!res.name.toString().toLowerCase().startsWith(q)) return false;
        }
        return true;
    }

    // ── Hide / auto-hide ─────────────────────────────────────────────────────

    /** A resource qualifies for auto-hide when nothing is produced and none is stored. */
    private static boolean autoQualifiesHide(RESOURCE res) {
        return producedNow(res) == 0
                && SETT.ROOMS().STOCKPILE.tally().amountTotal(res) <= 0;
    }

    private static double producedNow(RESOURCE res) {
        double p = 0;
        for (Source s : SETT.ROOMS().PROD.producers(res)) p += s.am();
        return p;
    }

    private boolean isHidden(RESOURCE res) {
        if (hiddenPins.contains(res)) return true;
        if (shownPins.contains(res))  return false;
        return autoHide && autoQualifiesHide(res);
    }

    /** Flip the row's current visibility — driven by the per-row hide/restore marker. */
    private void toggleHidden(RESOURCE res) {
        boolean hiddenNow = isHidden(res);
        hiddenPins.remove(res);
        shownPins.remove(res);
        if (hiddenNow) {                                    // make it visible
            if (autoHide && autoQualifiesHide(res)) shownPins.add(res);
        } else {                                            // hide it
            hiddenPins.add(res);
        }
        saveConfig();
        if (scroll != null) scroll.init();
    }

    // ── Persistence (global settings file, survives restart) ─────────────────

    private void loadConfig() {
        try {
            PATH dir = PATHS.local().SETTINGS;
            if (!dir.exists(CONFIG_FILE)) return;
            Json j = new Json(dir.gets(CONFIG_FILE));
            autoHide = j.i("AUTO_HIDE", 0, 1, 0) != 0;
            resolveKeys(j, "HIDDEN", hiddenPins);
            resolveKeys(j, "SHOWN",  shownPins);
        } catch (Exception e) {
            // missing or corrupt config — start with defaults
        }
    }

    private static void resolveKeys(Json j, String field, Set<RESOURCE> into) {
        if (!j.has(field)) return;
        Set<String> keys = new HashSet<>(Arrays.asList(j.values(field)));
        for (RESOURCE res : RESOURCES.ALL()) {
            if (keys.contains(res.key)) into.add(res);
        }
    }

    private void saveConfig() {
        try {
            JsonE j = new JsonE();
            j.add("AUTO_HIDE", autoHide ? 1 : 0);
            j.add("HIDDEN", hiddenPins.stream().map(r -> r.key).toArray(String[]::new));
            j.add("SHOWN",  shownPins.stream().map(r -> r.key).toArray(String[]::new));
            j.save(PATHS.local().SETTINGS.get(CONFIG_FILE));
        } catch (Exception e) {
            // non-fatal: hide choices simply won't persist this session
        }
    }

    private static RENDEROBJ separatorRow() {
        return new RENDEROBJ.RenderImp(WIDTH, 10) {
            @Override public void render(SPRITE_RENDERER r, float ds) { }
        };
    }

    // ── Totals row (Milestone 9) ─────────────────────────────────────────────

    private RENDEROBJ buildTotalsRow() {
        GText totalsLabel  = new GText(UI.FONT().S, "TOTALS").lablify();
        GText netGoldLabel = new GText(UI.FONT().S, "Net/d:").lablifySub();
        GText valueLabel   = new GText(UI.FONT().S, "Value:").lablifySub();
        GText foodLabel    = new GText(UI.FONT().S, "Food:").lablifySub();

        // Σ Net gold/day = sum(avg30dNet(res) * sellNow(res)) over visible rows
        GStat netGoldStat = new GStat() {
            @Override public void update(GText text) {
                long total = 0;
                for (ResourceRow rr : resourceRows) {
                    if (!isVisible(rr.res)) continue;
                    total += avg30dNet(rr.res) * (long) sellNow(rr.res);
                }
                GFORMAT.iIncr(text, total);
            }
        };

        // Σ Value = sum(stored * sellNow(res)) over visible rows
        GStat valueStat = new GStat() {
            @Override public void update(GText text) {
                long total = 0;
                for (ResourceRow rr : resourceRows) {
                    if (!isVisible(rr.res)) continue;
                    total += (long) SETT.ROOMS().STOCKPILE.tally().amountTotal(rr.res)
                             * (long) sellNow(rr.res);
                }
                text.normalify();
                GFORMAT.i(text, total);
            }
        };

        // Days of food = sum(storedEDI) / sum(dailyFoodConsumption) — always over all EDI()
        GStat foodDaysStat = new GStat() {
            @Override public void update(GText text) {
                long storedFood = 0;
                long dailyCons  = 0;
                for (RESOURCE res : RESOURCES.EDI().res()) {
                    storedFood += (long) SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                    for (Source s : SETT.ROOMS().PROD.consumers(res)) dailyCons += (long) s.am();
                }
                text.normalify();
                if (dailyCons <= 0) {
                    text.add("--");
                } else {
                    GFORMAT.i(text, storedFood / dailyCons);
                    text.add("d");
                }
            }
        };

        final int LBL_GAP   = 4;
        final int STAT_W    = Col.NET30.w + 20;
        // Positions for the three aggregate groups (left edge of label)
        final int X_NET     = Col.NET30.x;
        final int X_VALUE   = Col.SELL.x;
        final int X_FOOD    = Col.AVG.x + Col.AVG.w + 8;

        return new RENDEROBJ.RenderImp(WIDTH, ROW_H) {
            @Override public void render(SPRITE_RENDERER r, float ds) {
                int x  = body.x1();
                int y1 = body.y1();
                int y2 = body.y2();
                int yT = y1 + (ROW_H - totalsLabel.height()) / 2;

                GCOLOR.UI().NORMAL.normal.render(r, x, x + WIDTH, y1, y2);
                COLOR.WHITE35.render(r, x, x + WIDTH, y1, y1 + 1);

                totalsLabel.render(r, x + Col.NAME.x + Icon.S + 8, yT);

                netGoldLabel.render(r, x + X_NET, yT);
                netGoldStat.render(r,
                    x + X_NET + netGoldLabel.width() + LBL_GAP,
                    x + X_NET + netGoldLabel.width() + LBL_GAP + STAT_W,
                    y1, y2);

                valueLabel.render(r, x + X_VALUE, yT);
                valueStat.render(r,
                    x + X_VALUE + valueLabel.width() + LBL_GAP,
                    x + X_VALUE + valueLabel.width() + LBL_GAP + STAT_W,
                    y1, y2);

                foodLabel.render(r, x + X_FOOD, yT);
                foodDaysStat.render(r,
                    x + X_FOOD + foodLabel.width() + LBL_GAP,
                    x + X_FOOD + foodLabel.width() + LBL_GAP + STAT_W,
                    y1, y2);
            }
        };
    }

    // ── ResourceRow ──────────────────────────────────────────────────────────

    private class ResourceRow extends CLICKABLE.ClickableAbs {
        final RESOURCE res;
        private final GText nameText;
        private final GStat storedStat;
        private final GStat fillStat;
        private final GStat net30dStat;
        private final GStat histRunwayStat;
        private final GStat projRunwayStat;
        private final GText statusEmptyText;
        private final GText statusLowText;
        private final GText statusDeficitText;
        private final GText statusFullText;
        private final GStat spoilStat;
        private final GStat maintStat;
        private final GStat importStat;
        private final GStat exportStat;
        private final GStat delta7Stat;
        private final GStat sellStat;
        private final GStat avgStat;
        private final GText hideMark    = new GText(UI.FONT().S, "x").normalify();
        private final GText hideMarkH   = new GText(UI.FONT().S, "x").clickify();
        private final GText restoreMark = new GText(UI.FONT().S, "+").clickify();
        private boolean leftWasDown = false;

        ResourceRow(RESOURCE res) {
            super(WIDTH, ROW_H);
            this.res = res;
            nameText = new GText(UI.FONT().S, res.name).lablify();

            storedStat = new GStat() {
                @Override public void update(GText text) {
                    GFORMAT.i(text, (long) SETT.ROOMS().STOCKPILE.tally().amountTotal(res));
                }
            };
            fillStat = new GStat() {
                @Override public void update(GText text) {
                    double space  = SETT.ROOMS().STOCKPILE.tally().space.total(res);
                    double amount = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                    if (space <= 0) return;
                    GFORMAT.perc(text, amount / space, 0);
                }
            };
            net30dStat = new GStat() {
                @Override public void update(GText text) {
                    GFORMAT.iIncr(text, avg30dNet(res));
                }
            };
            histRunwayStat = new GStat() {
                @Override public void update(GText text) {
                    renderRunway(text, histNetRobust(res), res);
                }
            };
            projRunwayStat = new GStat() {
                @Override public void update(GText text) {
                    renderRunway(text, projNetD(res), res);
                }
            };
            statusEmptyText   = new GText(UI.FONT().S, "OUT").errorify();
            statusLowText     = new GText(UI.FONT().S, "LOW").errorify();
            statusDeficitText = new GText(UI.FONT().S, "DEFICIT").warnify();
            statusFullText    = new GText(UI.FONT().S, "NEAR FULL").warnify();
            spoilStat = new GStat() {
                @Override public void update(GText text) {
                    long v = lastDayOut(RTYPE.SPOILAGE, res);
                    if (v > 0) GFORMAT.i(text, v);
                }
            };
            maintStat = new GStat() {
                @Override public void update(GText text) {
                    long v = lastDayOut(RTYPE.MAINTENANCE, res);
                    if (v > 0) GFORMAT.i(text, v);
                }
            };
            importStat = new GStat() {
                @Override public void update(GText text) {
                    long v = lastDayIn(RTYPE.TRADE, res);
                    if (v > 0) GFORMAT.i(text, v);
                }
            };
            exportStat = new GStat() {
                @Override public void update(GText text) {
                    long v = lastDayOut(RTYPE.TRADE, res);
                    if (v > 0) GFORMAT.i(text, v);
                }
            };
            delta7Stat = new GStat() {
                @Override public void update(GText text) {
                    GFORMAT.iIncr(text, avg7dNet(res));
                }
            };
            sellStat = new GStat() {
                @Override public void update(GText text) {
                    int price = sellNow(res);
                    if (price <= 0) return;
                    text.clickify();
                    GFORMAT.i(text, price);
                }
            };
            avgStat = new GStat() {
                @Override public void update(GText text) {
                    int best = bestAvail(res);
                    if (best <= 0) return;
                    int now  = sellNow(res);
                    if (best > now) text.color(GCOLOR.T().IGOOD);
                    else text.clickify();
                    GFORMAT.i(text, best);
                }
            };
        }

        @Override
        protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
            boolean leftDown = MButt.LEFT.isDown();
            if (isHovered && leftDown && !leftWasDown) {
                int relX = VIEW.mouse().x() - body.x1();
                if (inMarker(relX)) {
                    toggleHidden(res);
                } else if (inCol(relX, Col.SELL)) {
                    FactionNPC npc = sellNowNpc(res);
                    if (npc != null) {
                        VIEW.UI().manager.close();
                        VIEW.world().UI.factions.openSell(npc, res.tr());
                    }
                } else if (inCol(relX, Col.AVG)) {
                    FactionNPC npc = bestAvailNpc(res);
                    if (npc != null) {
                        VIEW.UI().manager.close();
                        VIEW.world().UI.factions.openSell(npc, res.tr());
                    }
                } else if (inCol(relX, Col.STORED) || inCol(relX, Col.FILL)) {
                    // Open the goods/storage overview for this resource
                    VIEW.UI().goods.detail(res, GAME.player());
                }
            }
            leftWasDown = leftDown;

            int x     = body.x1();
            int y1    = body.y1();
            int y2    = body.y2();
            int yText = y1 + (ROW_H - nameText.height()) / 2;

            // Hidden rows revealed via "Show hidden" render over a muted band.
            boolean dimmed = showHidden && isHidden(res);
            if (dimmed) COLOR.WHITE25.render(r, x, x + WIDTH, y1, y2 - 1);

            COLOR.WHITE35.render(r, x, x + WIDTH, y2 - 1, y2);
            drawColLines(r, x, y1, y2);

            if (isHovered) {
                int relX = VIEW.mouse().x() - x;
                if (inCol(relX, Col.SELL)) {
                    GCOLOR.UI().GOOD.hovered.render(r, x + Col.SELL.x, x + Col.SELL.x + Col.SELL.w, y1, y2 - 1);
                } else if (inCol(relX, Col.AVG)) {
                    GCOLOR.UI().GOOD.hovered.render(r, x + Col.AVG.x, x + Col.AVG.x + Col.AVG.w, y1, y2 - 1);
                } else if (inCol(relX, Col.STORED)) {
                    GCOLOR.UI().NEUTRAL.hovered.render(r, x + Col.STORED.x, x + Col.STORED.x + Col.STORED.w, y1, y2 - 1);
                } else if (inCol(relX, Col.FILL)) {
                    GCOLOR.UI().NEUTRAL.hovered.render(r, x + Col.FILL.x, x + Col.FILL.x + Col.FILL.w, y1, y2 - 1);
                }
            }

            res.icon().small.render(r, x + Col.NAME.x, y1 + (ROW_H - Icon.S) / 2);
            nameText.render(r,       x + Col.NAME.x + Icon.S + 8, yText);

            // Hide / restore marker in the NAME column's reserved right gutter.
            int gutter = x + Col.NAME.x + Col.NAME.w - IND_W;
            if (dimmed) {
                restoreMark.render(r, gutter + (IND_W - restoreMark.width()) / 2, yText);
            } else if (isHovered) {
                GText m = inMarker(VIEW.mouse().x() - x) ? hideMarkH : hideMark;
                m.render(r, gutter + (IND_W - m.width()) / 2, yText);
            }

            stat(storedStat,     x, Col.STORED, y1, y2, r);
            stat(fillStat,       x, Col.FILL,   y1, y2, r);
            stat(net30dStat,     x, Col.NET30,  y1, y2, r);
            stat(histRunwayStat, x, Col.HIST,   y1, y2, r);
            stat(projRunwayStat, x, Col.PROJ,   y1, y2, r);

            GText flag = statusFlag(computeStatus(res));
            if (flag != null) flag.render(r, x + Col.STATUS.x + PAD, yText);

            stat(spoilStat,  x, Col.SPOIL,  y1, y2, r);
            stat(maintStat,  x, Col.MAINT,  y1, y2, r);
            stat(importStat, x, Col.IMPORT, y1, y2, r);
            stat(exportStat, x, Col.EXPORT, y1, y2, r);
            stat(delta7Stat, x, Col.DELTA7, y1, y2, r);
            stat(sellStat,   x, Col.SELL,   y1, y2, r);
            stat(avgStat,    x, Col.AVG,    y1, y2, r);
        }

        /** Right-align a GStat within the column's inner padding box. */
        private void stat(GStat s, int x, Col c, int y1, int y2, SPRITE_RENDERER r) {
            s.render(r, x + c.x + PAD, x + c.x + c.w - PAD, y1, y2);
        }

        private boolean inCol(int relX, Col c) {
            return relX >= c.x && relX < c.x + c.w;
        }

        /** Hit box for the hide/restore marker: the NAME column's right gutter. */
        private boolean inMarker(int relX) {
            return relX >= Col.NAME.x + Col.NAME.w - IND_W - PAD
                    && relX <  Col.NAME.x + Col.NAME.w;
        }

        @Override
        public void hoverInfoGet(GUI_BOX text) {
            res.hoverDetailed(text);
            GBox b = (GBox) text;
            b.NL(8);
            long prod = 0;
            for (Source s : SETT.ROOMS().PROD.producers(res)) prod += (long) s.am();
            long cons = 0;
            for (Source s : SETT.ROOMS().PROD.consumers(res)) cons += (long) s.am();
            b.textL("Prod/d");   b.tab(6); b.add(GFORMAT.i(b.text(), prod));
            b.NL();
            b.textL("Cons/d");   b.tab(6); b.add(GFORMAT.i(b.text(), cons));
            long spoil = lastDayOut(RTYPE.SPOILAGE, res);
            if (spoil > 0) {
                b.NL();
                b.textL("Spoil/d"); b.tab(6); b.add(GFORMAT.i(b.text(), spoil));
            }
            long maint = lastDayOut(RTYPE.MAINTENANCE, res);
            if (maint > 0) {
                b.NL();
                b.textL("Maint/d"); b.tab(6); b.add(GFORMAT.i(b.text(), maint));
            }
            long imp = lastDayIn(RTYPE.TRADE, res);
            if (imp > 0) {
                b.NL();
                b.textL("Import/d"); b.tab(6); b.add(GFORMAT.i(b.text(), imp));
            }
            long exp = lastDayOut(RTYPE.TRADE, res);
            if (exp > 0) {
                b.NL();
                b.textL("Export/d"); b.tab(6); b.add(GFORMAT.i(b.text(), exp));
            }
            b.NL(8);
            int sell = sellNow(res);
            int best = bestAvail(res);
            b.textL("Sell@now"); b.tab(6);
            if (sell > 0) b.add(GFORMAT.i(b.text(), sell));
            else b.error("No active deal");
            if (best > 0) {
                b.NL();
                b.textL("Best@avail"); b.tab(6); b.add(GFORMAT.i(b.text(), best));
                if (best > sell) b.textL(" (better deal avail)");
            }
            CharSequence prob = SETT.TRADE().seller(res.tr()).problem();
            if (prob != null) {
                b.NL();
                b.error(prob);
            }
            b.NL(8);
            b.textL("Click Stored/Fill% → storage, Sell@now/Best@avail → trade");
            b.NL();
            b.textL(isHidden(res) ? "Click + (name) → restore this row" : "Click x (name) → hide this row");
        }

        private GText statusFlag(int status) {
            switch (status) {
                case STATUS_EMPTY:   return statusEmptyText;
                case STATUS_LOW:     return statusLowText;
                case STATUS_DEFICIT: return statusDeficitText;
                case STATUS_FULL:    return statusFullText;
                default:             return null;
            }
        }
    }

    // ── FilterableScroll ─────────────────────────────────────────────────────

    private class FilterableScroll extends GScrollRows {
        FilterableScroll(RENDEROBJ[] rows, int height, int width) {
            super(rows, height, width);
        }

        @Override
        protected boolean passesFilter(int i, RENDEROBJ row) {
            if (!(row instanceof ResourceRow)) {
                return isDefaultView();
            }
            ResourceRow rr = (ResourceRow) row;
            return isVisible(rr.res);
        }

        private boolean isDefaultView() {
            // Category separators only make sense in the grouped (category) ordering
            return sortCol == SortCol.NAME && nameSort == NameSort.CATEGORY
                    && !filterDeficit && !filterFood
                    && (searchSprite == null || searchSprite.text().length() == 0);
        }
    }
}

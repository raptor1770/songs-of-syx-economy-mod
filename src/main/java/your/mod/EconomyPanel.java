package your.mod;

import game.GAME;
import init.resources.RESOURCE;
import init.resources.RESOURCES;
import init.sprite.UI.Icon;
import init.sprite.UI.UI;
import settlement.main.SETT;
import settlement.room.industry.module.RoomProduction.Source;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.Hoverable.HOVERABLE;
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
import view.ui.manage.IFullView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class EconomyPanel extends IFullView {

    private enum SortCol { NAME, STORED, FILL, NET30, HIST_RW, PROJ_RW, STATUS }

    private static final int COL_ICON    = 0;
    private static final int COL_NAME    = Icon.S + 8;
    private static final int COL_STORED  = 210;
    private static final int COL_FILL    = 285;
    private static final int COL_NET30   = 340;
    private static final int COL_HIST_RW = 430;
    private static final int COL_PROJ_RW = 520;
    private static final int COL_STATUS  = 610;
    private static final int COL_W       = 85;
    private static final int ROW_H       = Icon.S + 8;
    private static final int SEARCH_CHARS = 20;

    private static final int    STATUS_OK      = 0;
    private static final int    STATUS_FULL    = 1;
    private static final int    STATUS_DEFICIT = 2;
    private static final int    STATUS_LOW     = 3;
    private static final int    STATUS_EMPTY   = 4;

    private static final int    LOW_RUNWAY_DAYS = 7;
    private static final double OVERFLOW_FILL   = 0.9;
    private static final int    NET30_DAYS      = 30;

    // Sort/filter state — read each frame by header cells and passesFilter
    private SortCol sortCol       = SortCol.NAME;
    private boolean sortAsc       = true;
    private boolean filterDeficit = false;
    private boolean filterFood    = false;
    private StringInputSprite searchSprite;

    // Row data
    private ResourceRow[]  resourceRows;  // one per RESOURCE
    private RENDEROBJ[]    rowsArray;     // mutable sorted order: resourceRows + separators
    private RENDEROBJ[]    defaultOrder;  // original category order for restore

    // Scroll view (filter applied via passesFilter; sort applied by reordering rowsArray)
    private FilterableScroll scroll;

    public EconomyPanel() {
        super("Economy Overview", UI.icons().l.crate);
        section.body().setWidth(WIDTH).setHeight(0);

        buildAllRows();

        // Build filter bar first so we can measure its height for scroll sizing
        GuiSection filterBar = buildFilterBar();
        int scrollH = HEIGHT - filterBar.body().height() - ROW_H - 12;
        scroll = new FilterableScroll(rowsArray, scrollH, WIDTH);

        // Add all content directly to section — no intermediate scrollHolder.
        // This prevents the "clear() during click iteration" corruption.
        section.addDown(0, filterBar);
        section.addDown(4, buildHeaderRow());
        section.addDown(4, scroll.view());
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

        GuiSection bar = new GuiSection();
        bar.add(searchInput, 0, 0);
        bar.addRight(8, deficitBtn);
        bar.addRight(8, foodBtn);
        return bar;
    }

    // ── Sortable header row ──────────────────────────────────────────────────
    // Built once; each cell reads sortCol/sortAsc every frame to pick its label.

    private RENDEROBJ buildHeaderRow() {
        GuiSection row = new GuiSection();
        row.body().setWidth(WIDTH).setHeight(ROW_H);
        addSortCol(row, "Resource",  SortCol.NAME,    COL_NAME,    COL_STORED - COL_NAME - 4);
        addSortCol(row, "Stored",    SortCol.STORED,  COL_STORED,  COL_W);
        addSortCol(row, "Fill%",     SortCol.FILL,    COL_FILL,    COL_W);
        addSortCol(row, "Net/30d",   SortCol.NET30,   COL_NET30,   COL_W);
        addSortCol(row, "Hist.Run",  SortCol.HIST_RW, COL_HIST_RW, COL_W);
        addSortCol(row, "Proj.Run",  SortCol.PROJ_RW, COL_PROJ_RW, COL_W);
        addSortCol(row, "Status",    SortCol.STATUS,  COL_STATUS,  WIDTH - COL_STATUS);
        return row;
    }

    private void addSortCol(GuiSection row, String label, SortCol col, int x, int w) {
        // Pre-create all visual variants; pick the right one each frame.
        GText normal  = new GText(UI.FONT().S, label).lablify();
        GText asc     = new GText(UI.FONT().S, label + " ▲").lablify();
        GText desc    = new GText(UI.FONT().S, label + " ▼").lablify();
        GText normalH = new GText(UI.FONT().S, label).clickify();
        GText ascH    = new GText(UI.FONT().S, label + " ▲").clickify();
        GText descH   = new GText(UI.FONT().S, label + " ▼").clickify();

        CLICKABLE cell = new CLICKABLE.ClickableAbs(w, ROW_H) {
            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isHov, boolean isSel, boolean isAct) {
                boolean active = (sortCol == col);
                GText txt;
                if (active && sortAsc)  txt = isHov ? ascH  : asc;
                else if (active)        txt = isHov ? descH : desc;
                else                    txt = isHov ? normalH : normal;
                txt.render(r, body.x1(), body.y1());
            }
        };
        cell.clickActionSet(() -> onSortChange(col));
        cell.hoverInfoSet("Sort by " + label);
        row.add(cell, x, 0);
    }

    // ── Sort logic ───────────────────────────────────────────────────────────

    private void onSortChange(SortCol col) {
        if (sortCol == col) sortAsc = !sortAsc;
        else { sortCol = col; sortAsc = true; }
        applySortToArray();
        scroll.target.set(0);
        scroll.init();
        // No section rebuild needed — header cells render dynamically.
    }

    private void applySortToArray() {
        if (sortCol == SortCol.NAME && sortAsc) {
            System.arraycopy(defaultOrder, 0, rowsArray, 0, rowsArray.length);
            return;
        }
        // Sorted resource rows first; separators at end (hidden by passesFilter)
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
            default: // NAME descending
                cmp = Comparator.comparing(rr -> rr.res.name.toString());
                break;
        }
        return sortAsc ? cmp : cmp.reversed();
    }

    // ── Economy data helpers ─────────────────────────────────────────────────

    private static long avg30dNet(RESOURCE res) {
        var hist = GAME.player().res().total().history(res.tr());
        int days = Math.min(NET30_DAYS, hist.historyRecords() - 1);
        if (days <= 0) return 0;
        long total = 0;
        for (int i = 1; i <= days; i++) total += hist.get(i);
        return total / days;
    }

    private static long projNet(RESOURCE res) {
        long net = 0;
        for (Source s : SETT.ROOMS().PROD.producers(res)) net += (long) s.am();
        for (Source s : SETT.ROOMS().PROD.consumers(res)) net -= (long) s.am();
        return net;
    }

    // Returns runway in days; Double.MAX_VALUE when net >= 0 (no deficit = infinite runway)
    private static double histRunway(RESOURCE res) {
        long net = avg30dNet(res);
        if (net >= 0) return Double.MAX_VALUE;
        double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
        return stored <= 0 ? 0 : stored / -net;
    }

    private static double projRunway(RESOURCE res) {
        long net = projNet(res);
        if (net >= 0) return Double.MAX_VALUE;
        double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
        return stored <= 0 ? 0 : stored / -net;
    }

    private static int computeStatus(RESOURCE res) {
        double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
        if (stored <= 0) return STATUS_EMPTY;
        double space = SETT.ROOMS().STOCKPILE.tally().space.total(res);
        if (space > 0 && stored / space >= OVERFLOW_FILL) return STATUS_FULL;
        long net = avg30dNet(res);
        if (net < 0) {
            long runway = (long) (stored / -net);
            return runway < LOW_RUNWAY_DAYS ? STATUS_LOW : STATUS_DEFICIT;
        }
        return STATUS_OK;
    }

    private static RENDEROBJ separatorRow() {
        return new RENDEROBJ.RenderImp(WIDTH, 10) {
            @Override public void render(SPRITE_RENDERER r, float ds) { }
        };
    }

    // ── ResourceRow ──────────────────────────────────────────────────────────

    private class ResourceRow extends HOVERABLE.HoverableAbs {
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
                    long net = avg30dNet(res);
                    if (net >= 0) { text.add("+"); text.color(GCOLOR.T().IGOOD); return; }
                    text.normalify();
                    double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                    GFORMAT.i(text, stored <= 0 ? 0L : (long) (stored / -net));
                }
            };
            projRunwayStat = new GStat() {
                @Override public void update(GText text) {
                    long net = projNet(res);
                    if (net >= 0) { text.add("+"); text.color(GCOLOR.T().IGOOD); return; }
                    text.normalify();
                    double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                    GFORMAT.i(text, stored <= 0 ? 0L : (long) (stored / -net));
                }
            };
            statusEmptyText   = new GText(UI.FONT().S, "OUT").errorify();
            statusLowText     = new GText(UI.FONT().S, "LOW").errorify();
            statusDeficitText = new GText(UI.FONT().S, "DEFICIT").warnify();
            statusFullText    = new GText(UI.FONT().S, "NEAR FULL").warnify();
        }

        @Override
        protected void render(SPRITE_RENDERER r, float ds, boolean isHovered) {
            int x     = body.x1();
            int y1    = body.y1();
            int y2    = body.y2();
            int yText = y1 + (ROW_H - nameText.height()) / 2;

            res.icon().small.render(r, x + COL_ICON, y1 + (ROW_H - Icon.S) / 2);
            nameText.render(r,       x + COL_NAME,    yText);
            storedStat.render(r,     x + COL_STORED,  x + COL_STORED  + COL_W, y1, y2);
            fillStat.render(r,       x + COL_FILL,    x + COL_FILL    + COL_W, y1, y2);
            net30dStat.render(r,     x + COL_NET30,   x + COL_NET30   + COL_W, y1, y2);
            histRunwayStat.render(r, x + COL_HIST_RW, x + COL_HIST_RW + COL_W, y1, y2);
            projRunwayStat.render(r, x + COL_PROJ_RW, x + COL_PROJ_RW + COL_W, y1, y2);

            GText flag = statusFlag(computeStatus(res));
            if (flag != null) flag.render(r, x + COL_STATUS, yText);
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
            b.textL("Prod/d"); b.tab(6); b.add(GFORMAT.i(b.text(), prod));
            b.NL();
            b.textL("Cons/d"); b.tab(6); b.add(GFORMAT.i(b.text(), cons));
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
                // Category separator — only visible in default NAME/ASC view with no active filters
                return isDefaultView();
            }
            ResourceRow rr = (ResourceRow) row;
            if (filterDeficit && avg30dNet(rr.res) >= 0) return false;
            if (filterFood   && !RESOURCES.EDI().is(rr.res)) return false;
            if (searchSprite != null && searchSprite.text().length() > 0) {
                String q = searchSprite.text().toString().toLowerCase();
                if (!rr.res.name.toString().toLowerCase().startsWith(q)) return false;
            }
            return true;
        }

        private boolean isDefaultView() {
            return sortCol == SortCol.NAME && sortAsc
                    && !filterDeficit && !filterFood
                    && (searchSprite == null || searchSprite.text().length() == 0);
        }
    }
}

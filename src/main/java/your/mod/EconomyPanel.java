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
import snake2d.util.gui.Hoverable.HOVERABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GScrollRows;
import util.info.GFORMAT;
import view.ui.manage.IFullView;

import java.util.ArrayList;
import java.util.List;

public final class EconomyPanel extends IFullView {

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

    private static final int    STATUS_OK      = 0;
    private static final int    STATUS_FULL    = 1;
    private static final int    STATUS_DEFICIT = 2;
    private static final int    STATUS_LOW     = 3;
    private static final int    STATUS_EMPTY   = 4;

    private static final int    LOW_RUNWAY_DAYS = 7;
    private static final double OVERFLOW_FILL   = 0.9;
    private static final int    NET30_DAYS      = 30;

    public EconomyPanel() {
        super("Economy Overview", UI.icons().l.crate);
        section.body().setWidth(WIDTH).setHeight(0);
        section.addDown(0, headerRow());
        GScrollRows scroll = new GScrollRows(buildRows(), HEIGHT - ROW_H - 8, WIDTH);
        section.addDown(4, scroll.view());
    }

    private static RENDEROBJ headerRow() {
        GText resLabel    = new GText(UI.FONT().S, "Resource").lablify();
        GText storedLabel = new GText(UI.FONT().S, "Stored").lablify();
        GText fillLabel   = new GText(UI.FONT().S, "Fill%").lablify();
        GText net30Label  = new GText(UI.FONT().S, "Net/30d").lablify();
        GText histRwLabel = new GText(UI.FONT().S, "Hist.Run").lablify();
        GText projRwLabel = new GText(UI.FONT().S, "Proj.Run").lablify();
        GText statusLabel = new GText(UI.FONT().S, "Status").lablify();

        return new RENDEROBJ.RenderImp(WIDTH, ROW_H) {
            @Override
            public void render(SPRITE_RENDERER r, float ds) {
                int y = body().y1();
                int x = body().x1();
                resLabel.render(r,    x + COL_NAME,    y);
                storedLabel.render(r, x + COL_STORED,  y);
                fillLabel.render(r,   x + COL_FILL,    y);
                net30Label.render(r,  x + COL_NET30,   y);
                histRwLabel.render(r, x + COL_HIST_RW, y);
                projRwLabel.render(r, x + COL_PROJ_RW, y);
                statusLabel.render(r, x + COL_STATUS,  y);
            }
        };
    }

    private static List<RENDEROBJ> buildRows() {
        List<RENDEROBJ> rows = new ArrayList<>(RESOURCES.ALL().size() + RESOURCES.CATEGORIES());
        int cat = -1;
        for (RESOURCE res : RESOURCES.ALL()) {
            if (res.category != cat) {
                if (cat != -1) rows.add(categorySeparator());
                cat = res.category;
            }
            rows.add(resourceRow(res));
        }
        return rows;
    }

    private static RENDEROBJ resourceRow(RESOURCE res) {
        GText nameText = new GText(UI.FONT().S, res.name).lablify();

        GStat storedStat = new GStat() {
            @Override public void update(GText text) {
                GFORMAT.i(text, (long) SETT.ROOMS().STOCKPILE.tally().amountTotal(res));
            }
        };
        GStat fillStat = new GStat() {
            @Override public void update(GText text) {
                double space  = SETT.ROOMS().STOCKPILE.tally().space.total(res);
                double amount = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                if (space <= 0) return; // no stockpile built — leave blank
                GFORMAT.perc(text, amount / space, 0);
            }
        };
        // 30-day rolling average net — correct for seasonal goods (harvest spikes amortised)
        GStat net30dStat = new GStat() {
            @Override public void update(GText text) {
                GFORMAT.iIncr(text, avg30dNet(res));
            }
        };
        // Historical runway: how long stock lasts at the 30-day trend rate
        GStat histRunwayStat = new GStat() {
            @Override public void update(GText text) {
                long net = avg30dNet(res);
                if (net >= 0) {
                    text.add("+"); text.color(GCOLOR.T().IGOOD);
                    return;
                }
                text.normalify();
                double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                GFORMAT.i(text, stored <= 0 ? 0L : (long) (stored / -net));
            }
        };
        // Projected runway: how long stock lasts at the current instantaneous production rate
        GStat projRunwayStat = new GStat() {
            @Override public void update(GText text) {
                long net = projNet(res);
                if (net >= 0) {
                    text.add("+"); text.color(GCOLOR.T().IGOOD);
                    return;
                }
                text.normalify();
                double stored = SETT.ROOMS().STOCKPILE.tally().amountTotal(res);
                GFORMAT.i(text, stored <= 0 ? 0L : (long) (stored / -net));
            }
        };

        GText statusEmptyText   = new GText(UI.FONT().S, "OUT").errorify();
        GText statusLowText     = new GText(UI.FONT().S, "LOW").errorify();
        GText statusDeficitText = new GText(UI.FONT().S, "DEFICIT").warnify();
        GText statusFullText    = new GText(UI.FONT().S, "NEAR FULL").warnify();

        return new HOVERABLE.HoverableAbs(WIDTH, ROW_H) {
            @Override
            protected void render(SPRITE_RENDERER r, float ds, boolean isHovered) {
                int x     = body.x1();
                int y1    = body.y1();
                int y2    = body.y2();
                int yText = y1 + (ROW_H - nameText.height()) / 2;

                res.icon().small.render(r, x + COL_ICON, y1 + (ROW_H - Icon.S) / 2);
                nameText.render(r,        x + COL_NAME,    yText);
                storedStat.render(r,      x + COL_STORED,  x + COL_STORED  + COL_W, y1, y2);
                fillStat.render(r,        x + COL_FILL,    x + COL_FILL    + COL_W, y1, y2);
                net30dStat.render(r,      x + COL_NET30,   x + COL_NET30   + COL_W, y1, y2);
                histRunwayStat.render(r,  x + COL_HIST_RW, x + COL_HIST_RW + COL_W, y1, y2);
                projRunwayStat.render(r,  x + COL_PROJ_RW, x + COL_PROJ_RW + COL_W, y1, y2);

                GText flag = statusFlag(computeStatus(res),
                        statusEmptyText, statusLowText, statusDeficitText, statusFullText);
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
                b.textL("Prod/d");
                b.tab(6);
                b.add(GFORMAT.i(b.text(), prod));
                b.NL();
                b.textL("Cons/d");
                b.tab(6);
                b.add(GFORMAT.i(b.text(), cons));
            }
        };
    }

    // Averages the last NET30_DAYS completed-day net changes from faction economy history.
    // Uses however many days are available if fewer than NET30_DAYS have elapsed.
    private static long avg30dNet(RESOURCE res) {
        var hist = GAME.player().res().total().history(res.tr());
        int days = Math.min(NET30_DAYS, hist.historyRecords() - 1);
        if (days <= 0) return 0;
        long total = 0;
        for (int i = 1; i <= days; i++) total += hist.get(i);
        return total / days;
    }

    // Current instantaneous net from production/consumption buildings (reflects today's setup).
    private static long projNet(RESOURCE res) {
        long net = 0;
        for (Source s : SETT.ROOMS().PROD.producers(res)) net += (long) s.am();
        for (Source s : SETT.ROOMS().PROD.consumers(res)) net -= (long) s.am();
        return net;
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

    private static GText statusFlag(int status,
            GText empty, GText low, GText deficit, GText full) {
        switch (status) {
            case STATUS_EMPTY:   return empty;
            case STATUS_LOW:     return low;
            case STATUS_DEFICIT: return deficit;
            case STATUS_FULL:    return full;
            default:             return null;
        }
    }

    private static RENDEROBJ categorySeparator() {
        return new RENDEROBJ.RenderImp(WIDTH, 10) {
            @Override public void render(SPRITE_RENDERER r, float ds) { }
        };
    }
}

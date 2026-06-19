package your.mod;

import init.constant.C;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.gui.misc.GButt;
import view.main.VIEW;
import view.ui.manage.IFullView;
import view.ui.manage.IManager;
import view.ui.top.UIPanelTop;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class GameUiApi {

    /**
     * Add an icon button for {@code panel} into the {@link IManager} top bar — the row of
     * Goods/Economy/Trade/Tourists/... buttons shown while any full-view panel is open. This lets
     * the user jump back to {@code panel} from any other tab without returning to the settlement view.
     * <p>
     * Mirrors the per-panel button {@code IManager} builds in its own constructor: a
     * {@link GButt.ButtPanel} around the panel icon, padded (16, 2), that calls {@code panel.activate()}
     * on click and highlights itself while {@code panel} is the open view.
     */
    public static void injectIntoIManagerTopBar(IFullView panel) {
        IManager manager = VIEW.UI().manager;

        Optional<GuiSection> top = ReflectionUtil.getDeclaredFieldValue("top", manager);
        if (!top.isPresent()) {
            System.err.println("[ECONOMY MOD] Could not find 'top' field on IManager");
            return;
        }

        // Cached once so the per-frame highlight check is a plain Field.get, not a field lookup.
        Optional<Field> currentField = ReflectionUtil.getDeclaredField("current", manager);

        GButt.ButtPanel button = new GButt.ButtPanel(panel.icon) {
            @Override
            protected void clickA() {
                panel.activate();
            }

            @Override
            protected void renAction() {
                boolean isOpen = currentField
                    .flatMap(f -> ReflectionUtil.<IFullView>getDeclaredFieldValue(f, manager))
                    .map(current -> current == panel)
                    .orElse(false);
                selectedSet(isOpen);
            }
        };
        button.hoverInfoSet(panel.title);
        button.pad(16, 2);

        // Slot the button right after the Economy & Trade button. The far-right exit (largest x2)
        // is left in place; the remaining elements are the centered panel-button cluster, added
        // left-to-right as goods, economy, tourists, ... so the Economy button is index 1.
        RENDEROBJ exit = null;
        for (RENDEROBJ r : top.get().elements()) {
            if (exit == null || r.body().x2() > exit.body().x2()) {
                exit = r;
            }
        }

        List<RENDEROBJ> cluster = new ArrayList<>();
        for (RENDEROBJ r : top.get().elements()) {
            if (r != exit) {
                cluster.add(r);
            }
        }
        cluster.sort(Comparator.comparingInt(r -> r.body().x1()));
        cluster.add(Math.min(2, cluster.size()), button);  // index 2 == just after Economy & Trade

        // Re-lay the cluster left-to-right (touching bodies; pad supplies the visual gap) and
        // re-center it on screen so adding an 8th button keeps the row symmetric.
        int totalW = 0;
        for (RENDEROBJ r : cluster) {
            totalW += r.body().width();
        }
        int x = (C.WIDTH() - totalW) / 2;
        for (RENDEROBJ r : cluster) {
            r.body().moveX1(x);
            r.body().centerY(0, IManager.TOP_HEIGHT);
            x = r.body().x2();
        }

        top.get().add(button);
    }

    public static void injectIntoSettlementUITopPanel(RENDEROBJ element) {
        Optional<UIPanelTop> panelTop = findInInterManager(VIEW.s().uiManager, UIPanelTop.class);

        if (!panelTop.isPresent()) {
            System.err.println("[ECONOMY MOD] Could not find UIPanelTop in settlement uiManager");
            return;
        }

        Optional<GuiSection> right = ReflectionUtil.getDeclaredFieldValue("right", panelTop.get());

        if (!right.isPresent()) {
            System.err.println("[ECONOMY MOD] Could not find 'right' field on UIPanelTop");
            return;
        }

        right.get().addRelBody(8, DIR.W, element);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> findInInterManager(Object uiManager, Class<T> clazz) {
        Optional<Iterable<?>> inters = ReflectionUtil.getDeclaredFieldValue("inters", uiManager);

        if (!inters.isPresent()) {
            return Optional.empty();
        }

        for (Object inter : inters.get()) {
            if (clazz.isInstance(inter)) {
                return Optional.of((T) inter);
            }
        }

        return Optional.empty();
    }
}

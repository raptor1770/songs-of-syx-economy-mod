package your.mod;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import view.main.VIEW;
import view.ui.top.UIPanelTop;

import java.util.Optional;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class GameUiApi {

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

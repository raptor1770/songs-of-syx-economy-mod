package your.mod;


import lombok.NoArgsConstructor;
import script.SCRIPT;
import util.gui.misc.GButt;
import util.info.INFO;
import view.ui.top.UIPanelTop;

@NoArgsConstructor
@SuppressWarnings("unused") // used by the game via reflection
public final class MainScript implements SCRIPT {

	private final INFO info = new INFO("Economy Overview", "Adds an economy overview panel to the settlement UI.");

	@Override
	public CharSequence name() {
		return info.name;
	}

	@Override
	public CharSequence desc() {
		return info.desc;
	}


	@Override
	public boolean isSelectable() {
		return SCRIPT.super.isSelectable();
	}

	@Override
	public boolean forceInit() {
		return SCRIPT.super.forceInit();
	}

	@Override
	public SCRIPT_INSTANCE createInstance() {
		EconomyPanel panel = new EconomyPanel();
		// Framed "ECON" button in the game's native top-bar style (border + background,
		// matching the wiki/menu buttons), rather than the bare bright text that stood
		// out before. Height matched to the top bar so it aligns with the native buttons.
		GButt.ButtPanel button = new GButt.ButtPanel("ECON");
		button.body().setHeight(UIPanelTop.HEIGHT);
		button.hoverInfoSet(panel.title);
		button.clickActionSet(panel::activate);
		GameUiApi.injectIntoSettlementUITopPanel(button);
		GameUiApi.injectIntoIManagerTopBar(panel);
		return new InstanceScript();
	}
}

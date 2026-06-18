package your.mod;


import lombok.NoArgsConstructor;
import script.SCRIPT;
import util.gui.misc.GButt;
import util.info.INFO;

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
		GButt.Panel button = new GButt.Panel("ECON");
		button.clickActionSet(panel::activate);
		GameUiApi.injectIntoSettlementUITopPanel(button);
		return new InstanceScript();
	}
}
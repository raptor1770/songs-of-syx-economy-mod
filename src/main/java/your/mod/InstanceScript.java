package your.mod;

import java.io.IOException;

import script.SCRIPT;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.file.*;
import util.gui.misc.GBox;
import view.keyboard.KEYS;

final class InstanceScript implements SCRIPT.SCRIPT_INSTANCE {

	@Override
	public void save(FilePutter file) {
	}

	@Override
	public void load(FileGetter file) {
	}

	@Override
	public void update(double deltaSeconds) {
	}

	@Override
	public void hoverTimer(double mouseTimer, GBox text) {
	}

	@Override
	public void render(Renderer renderer, float deltaSeconds) {
	}

	@Override
	public void keyPush(KEYS keys) {
	}

	@Override
	public void mouseClick(MButt button) {
	}

	@Override
	public void hover(COORDINATE mCoo, boolean mouseHasMoved) {
	}

	@Override
	public boolean handleBrokenSavedState() {
		return SCRIPT.SCRIPT_INSTANCE.super.handleBrokenSavedState();
	}
}

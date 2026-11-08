package com.btemu.client;

import com.btemu.TileEntitySpeaker;
import com.btemu.net.Msgs;
import net.minecraft.client.gui.ButtonElement;
import net.minecraft.client.gui.Screen;
import net.minecraft.client.gui.TextFieldElement;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;
import org.lwjgl.input.Keyboard;
import turniplabs.halplibe.helper.network.NetworkHandler;

final class SpeakerScreen extends Screen {
	private static final int ID_DONE = 0, ID_CANCEL = 1;

	private final World world;
	private final TilePos self;
	private TextFieldElement nameField;

	SpeakerScreen(World world, TilePos self) {
		super(null);
		this.world = world;
		this.self = self;
	}

	@Override
	public void init() {
		Keyboard.enableRepeatEvents(true);
		buttons.clear();
		int cx = width / 2, cy = height / 2;
		String old = nameField != null ? nameField.getText()
			: world.getTileEntity(self) instanceof TileEntitySpeaker s ? s.name : "";
		nameField = new TextFieldElement(this, fontRenderer, cx - 100, cy - 20, 200, 20, old, "Speaker name");
		nameField.setMaxStringLength(32);
		nameField.isFocused = true;
		add(new ButtonElement(ID_DONE, cx - 100, cy + 8, 98, 20, "Done"));
		add(new ButtonElement(ID_CANCEL, cx + 2, cy + 8, 98, 20, "Cancel"));
	}

	private void save() {
		NetworkHandler.sendToServer(new Msgs.SpeakerSave(self, nameField.getText().trim()));
	}

	@Override
	protected void buttonClicked(ButtonElement b) {
		if (!b.enabled) return;
		if (b.id == ID_DONE) save();
		mc.displayScreen(null);
	}

	@Override
	public void keyPressed(char c, int key, int mx, int my) {
		if (key == Keyboard.KEY_ESCAPE) {
			mc.displayScreen(null);
			return;
		}
		if (key == Keyboard.KEY_RETURN) {
			save();
			mc.displayScreen(null);
			return;
		}
		nameField.textboxKeyTyped(c, key);
	}

	@Override
	public void mouseClicked(int x, int y, int button) {
		super.mouseClicked(x, y, button);
		nameField.mouseClicked(x, y, button);
	}

	@Override
	public void tick() {
		nameField.updateCursorCounter();
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(int mx, int my, float pt) {
		renderBackground();
		int cx = width / 2, cy = height / 2;
		drawStringCenteredShadow(fontRenderer, "Name this speaker", cx, cy - 36, 0xFFFFFF);
		nameField.drawTextBox();
		nameField.updateCursor(mc, mx, my);
		super.render(mx, my, pt);
	}
}

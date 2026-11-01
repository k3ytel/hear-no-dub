package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How subtitles show on the screen itself. */
public enum ScreenMode {
	HUD("hud"),
	DANMAKU("danmaku"),
	ACTION_BAR("action_bar"),
	CHAT("chat"),
	NONE("none");

	private final String key;

	ScreenMode(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.screenmode." + key);
	}
}

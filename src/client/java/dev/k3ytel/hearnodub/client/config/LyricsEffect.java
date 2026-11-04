package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** What the word being sung does. */
public enum LyricsEffect {
	NONE("none"),
	LIFT("lift"),
	BOUNCE("bounce"),
	GLOW("glow"),
	WAVE("wave"),
	JELLY("jelly");

	private final String key;

	LyricsEffect(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.lyricseffect." + key);
	}
}

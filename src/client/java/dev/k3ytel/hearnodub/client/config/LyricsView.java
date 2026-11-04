package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How song lyrics are laid out on screen. */
public enum LyricsView {
	CLASSIC("classic"),
	SINGLE("single"),
	STACK("stack");

	private final String key;

	LyricsView(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.lyricsview." + key);
	}
}

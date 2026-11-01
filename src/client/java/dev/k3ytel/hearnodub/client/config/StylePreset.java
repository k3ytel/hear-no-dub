package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** Look of the subtitle text. */
public enum StylePreset {
	MOVIE("movie"),
	GLOW("glow"),
	BOX("box"),
	RETRO("retro"),
	CUSTOM("custom");

	private final String key;

	StylePreset(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.stylepreset." + key);
	}
}

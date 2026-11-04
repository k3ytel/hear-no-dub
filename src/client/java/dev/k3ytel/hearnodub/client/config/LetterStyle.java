package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How the letters of 3D Pop-in look. */
public enum LetterStyle {
	THICK("thick"),
	CHISELED("chiseled"),
	BALLOON("balloon"),
	NEON("neon"),
	GLASS("glass"),
	HOLOGRAM("hologram"),
	FLAT("flat"),
	CUBES("cubes");

	private final String key;

	LetterStyle(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.letterstyle." + key);
	}
}

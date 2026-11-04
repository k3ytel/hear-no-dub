package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How the glow on blocks is drawn when a shader pack is on. */
public enum GlowMode {
	AUTO("auto"),
	TINT("tint"),
	ADDITIVE("additive"),
	EMISSIVE("emissive");

	private final String key;

	GlowMode(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.glowmode." + key);
	}
}

package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How easily danger pause pauses the video. */
public enum DangerLevel {
	/** Only your health: low health while getting hurt, or a big hit. */
	RELAXED("relaxed"),
	/** Also mobs coming for you and lit creepers (unless you're fighting on purpose). */
	CAREFUL("careful");

	private final String key;

	DangerLevel(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.dangerlevel." + key);
	}
}

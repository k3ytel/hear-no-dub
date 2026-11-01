package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How lines appear and disappear. */
public enum Animation {
	FADE_POP("fade_pop"),
	FADE("fade"),
	TYPEWRITER("typewriter"),
	INSTANT("instant");

	private final String key;

	Animation(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.animation." + key);
	}
}

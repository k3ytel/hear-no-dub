package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How subtitles show in the 3D world. */
public enum WorldMode {
	NONE("none"),
	FLOATING("floating"),
	POPIN("popin"),
	BUBBLE("bubble");

	private final String key;

	WorldMode(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.worldmode." + key);
	}
}

package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** Where the browser's video is shown in the game. One at a time. */
public enum VideoMode {
	PICTURE_IN_PICTURE("pip"),
	FADED_BACKGROUND("background"),
	FLOATING("floating"),
	FLOATING_SIDE("side"),
	TV("tv"),
	ABOVE_MOB("mob");

	/** Shown in the 3D world (not on the HUD). */
	public boolean inWorld() {
		return this != PICTURE_IN_PICTURE && this != FADED_BACKGROUND;
	}

	private final String key;

	VideoMode(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.videomode." + key);
	}
}

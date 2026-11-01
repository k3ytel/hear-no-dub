package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How 3D text turns to face you. */
public enum Billboard {
	/** Turns to point straight at your eyes (like a sign turning to face you). */
	TOWARD_ME("toward_me"),
	/** Turns left/right toward you but stays vertical (doesn't lean back when you're above/below). */
	TOWARD_ME_VERTICAL("toward_me_vertical"),
	/** Flat to your screen, level with the horizon. */
	UPRIGHT("upright"),
	/** Flat to your screen, copying the camera's full rotation (like name tags). */
	FACE_CAMERA("face_camera");

	private final String key;

	Billboard(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.billboard." + key);
	}
}

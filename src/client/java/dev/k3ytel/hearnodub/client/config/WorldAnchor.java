package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How 3D subtitles are placed in the world. */
public enum WorldAnchor {
	/** Floats in front of you and lazily follows where you look. */
	FOLLOW("follow"),
	/** Each new line spawns in front of you, then stays put in the world until it ends. */
	PER_LINE("per_line"),
	/** Stays where you put it (press the "Place 3D subtitles here" key). */
	FIXED("fixed");

	private final String key;

	WorldAnchor(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.anchor." + key);
	}
}

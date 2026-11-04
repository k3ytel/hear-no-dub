package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How 3D Pop-in letters appear and disappear. */
public enum LetterAnimation {
	DROP("drop"),
	RISE("rise"),
	PARTICLES("particles"),
	FADE("fade");

	private final String key;

	LetterAnimation(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.letteranim." + key);
	}
}

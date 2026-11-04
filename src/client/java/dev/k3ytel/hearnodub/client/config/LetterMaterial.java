package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** What 3D Pop-in letters are made of. */
public enum LetterMaterial {
	COLOR("color"),
	RAINBOW("rainbow"),
	GROUND("ground"),
	GOLD("gold"),
	DIAMOND("diamond"),
	AMETHYST("amethyst"),
	ICE("ice");

	private final String key;

	LetterMaterial(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.lettermaterial." + key);
	}
}

package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** Whether 3D Pop-in places whole lines or each word on its own. */
public enum LetterSpawn {
	LINE("line"),
	WORD("word"),
	HEAD("head");

	private final String key;

	LetterSpawn(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.letterspawn." + key);
	}
}

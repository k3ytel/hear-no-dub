package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** How upcoming lyrics words are shown. */
public enum LyricsReveal {
	DIMMED("dimmed"),
	WORD_BY_WORD("word_by_word"),
	LETTER_BY_LETTER("letter_by_letter");

	private final String key;

	LyricsReveal(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.lyricsreveal." + key);
	}
}

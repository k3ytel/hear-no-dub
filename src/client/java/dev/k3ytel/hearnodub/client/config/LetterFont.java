package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** Font of the 3D Pop-in letters. */
public enum LetterFont {
	MINECRAFT("minecraft"),
	POSTER("poster"),
	COMIC("comic"),
	ROUNDED("rounded"),
	CHUNKY("chunky"),
	MARKER("marker"),
	SCRIPT("script"),
	LOBSTER("lobster"),
	ELEGANT("elegant"),
	GOTHIC("gothic"),
	RETRO("retro"),
	FUTURE("future"),
	STENCIL("stencil"),
	HORROR("horror"),
	ARCADE("arcade"),
	TALL("tall"),
	NEON_LINES("neon_lines"),
	BLOCKY("blocky"),
	BOLD("bold"),
	SERIF("serif"),
	IMPACT("impact"),
	MONO("mono"),
	GALACTIC("galactic"),
	ILLAGER("illager");

	private final String key;

	LetterFont(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.letterfont." + key);
	}
}

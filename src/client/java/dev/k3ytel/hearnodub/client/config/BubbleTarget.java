package dev.k3ytel.hearnodub.client.config;

import net.minecraft.network.chat.Component;

/** Who a speech bubble floats above. */
public enum BubbleTarget {
	AUTO("auto"),
	LOOKED_AT("looked_at"),
	SELF("self");

	private final String key;

	BubbleTarget(String key) {
		this.key = key;
	}

	public Component display() {
		return Component.translatable("hear-no-dub.config.bubbletarget." + key);
	}
}

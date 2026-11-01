package dev.k3ytel.hearnodub.client.render;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;

/**
 * The resolved look of subtitle text: a preset, or the custom settings.
 * Colors are ARGB.
 */
public record SubStyle(int textColor, boolean outline, int outlineColor, boolean shadow,
		boolean glow, int glowColor, boolean box, int boxColor) {

	/** @param world true for 3D text (uses the 3D background opacity) */
	public static SubStyle resolve(HearNoDubConfig c, boolean world) {
		int opacity = world ? c.worldBackgroundOpacity : c.backgroundOpacity;
		int boxAlpha = Math.round(opacity / 100f * 255f) << 24;
		return switch (c.stylePreset) {
			case MOVIE -> new SubStyle(0xFFFFFFFF, true, 0xFF000000, false, false, 0, false, 0);
			case GLOW -> new SubStyle(0xFFFFFFFF, false, 0, false, true, 0xFFE8F0FF, false, 0);
			case BOX -> new SubStyle(0xFFFFFFFF, false, 0, true, false, 0, opacity > 0, boxAlpha);
			case RETRO -> new SubStyle(0xFFFFD866, false, 0, true, false, 0, opacity > 0, boxAlpha | 0x3A2614);
			case CUSTOM -> new SubStyle(c.customTextColor | 0xFF000000, c.customOutline, c.customOutlineColor | 0xFF000000,
					c.customShadow, c.customGlow, c.customGlowColor | 0xFF000000,
					c.customBox && opacity > 0, boxAlpha | (c.customBoxColor & 0xFFFFFF));
		};
	}

	/** Multiplies the alpha of an ARGB color. */
	public static int fade(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}

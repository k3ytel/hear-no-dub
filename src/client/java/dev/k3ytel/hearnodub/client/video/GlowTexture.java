package dev.k3ytel.hearnodub.client.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** A soft round spot of light (white in the middle, fading to nothing), used for the TV glow. */
public final class GlowTexture {
	private GlowTexture() {}

	public static final Identifier ID = HearNoDubClient.id("glow");
	private static final int SIZE = 64;
	private static boolean made;

	/** Creates the texture the first time it's needed. Render thread only. */
	public static Identifier get() {
		if (!made) {
			made = true;
			DynamicTexture tex = new DynamicTexture(() -> "Hear No Dub glow", SIZE, SIZE, false) {
				{
					this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
				}
			};
			NativeImage img = tex.getPixels();
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					double dx = (x + 0.5) / SIZE * 2 - 1, dy = (y + 0.5) / SIZE * 2 - 1;
					double r = Math.min(1, Math.sqrt(dx * dx + dy * dy));
					double f = 1 - r;
					f = f * f * (3 - 2 * f); // smooth edge
					int v = (int) Math.round(255 * f);
					// same value in colour and alpha: works for additive and normal blending
					img.setPixel(x, y, (v << 24) | (v << 16) | (v << 8) | v);
				}
			}
			tex.upload();
			Minecraft.getInstance().getTextureManager().register(ID, tex);
		}
		return ID;
	}
}

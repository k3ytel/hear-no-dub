package dev.k3ytel.hearnodub.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pixels of Minecraft's own font letters (from the bitmap pages in font/default.json, so
 * resource packs that change the font are followed), used to build letters out of blocks.
 * Letters that only exist in the unicode fallback font return null.
 */
public final class GlyphBitmaps {
	private GlyphBitmaps() {}

	/**
	 * One letter's pixels.
	 *
	 * @param pixels  [row][column], true where the letter has a pixel
	 * @param scale   size of one pixel in text units (1 for the normal 8-pixel font)
	 * @param top     where row 0 sits, in text units below the top of the line
	 * @param left    where column 0 sits, in text units right of the pen position
	 * @param advance how far the pen moves after this letter, in text units
	 */
	public record Glyph(boolean[][] pixels, int width, float scale, float top, float left, float advance) {
		public int rows() {
			return pixels.length;
		}
	}

	private record Page(Identifier file, int height, int ascent, List<int[]> rows) {}

	private static final Map<String, Map<Integer, Page>> pagesByFont = new HashMap<>();
	private static final Map<Identifier, NativeImage> images = new HashMap<>();
	private static final Map<Long, Glyph> cache = new HashMap<>();
	private static final Set<Long> missing = new HashSet<>();

	/** Drops everything (resource packs changed). */
	public static void reset() {
		pagesByFont.clear();
		cache.clear();
		missing.clear();
		for (NativeImage img : images.values()) img.close();
		images.clear();
	}

	/** A letter of Minecraft's normal font. */
	public static Glyph get(int codepoint) {
		return get("default", codepoint);
	}

	/** A letter of one of Minecraft's fonts ("default", "alt" = enchanting table, "illageralt"). */
	public static Glyph get(String font, int codepoint) {
		if (codepoint == ' ') return null;
		long key = ((long) font.hashCode() << 32) | (codepoint & 0xFFFFFFFFL);
		Glyph g = cache.get(key);
		if (g != null || missing.contains(key)) return g;
		try {
			g = load(font, codepoint);
		} catch (Exception e) {
			HearNoDubClient.LOGGER.debug("No bitmap for U+{} in {}: {}", Integer.toHexString(codepoint), font, e.toString());
			g = null;
		}
		if (g == null) missing.add(key);
		else cache.put(key, g);
		return g;
	}

	private static Glyph load(String font, int cp) throws Exception {
		Map<Integer, Page> pages = pagesByFont.computeIfAbsent(font, f -> {
			Map<Integer, Page> out = new HashMap<>();
			readFont(Identifier.withDefaultNamespace(f), out, 0);
			HearNoDubClient.LOGGER.debug("Font {}: {} bitmap letters", f, out.size());
			return out;
		});
		Page page = pages.get(cp);
		if (page == null) return null;
		NativeImage img = images.get(page.file());
		if (img == null) {
			ResourceManager rm = Minecraft.getInstance().getResourceManager();
			Identifier tex = Identifier.fromNamespaceAndPath(page.file().getNamespace(), "textures/" + page.file().getPath());
			Resource res = rm.getResource(tex).orElse(null);
			if (res == null) return null;
			try (InputStream in = res.open()) {
				img = NativeImage.read(in);
			}
			images.put(page.file(), img);
		}
		int columns = 0;
		for (int[] row : page.rows()) columns = Math.max(columns, row.length);
		int cellW = img.getWidth() / Math.max(1, columns), cellH = img.getHeight() / Math.max(1, page.rows().size());
		int col = -1, row = -1;
		for (int r = 0; r < page.rows().size() && col < 0; r++) {
			int[] cps = page.rows().get(r);
			for (int c = 0; c < cps.length; c++) {
				if (cps[c] == cp) {
					row = r;
					col = c;
					break;
				}
			}
		}
		if (col < 0) return null;
		boolean[][] px = new boolean[cellH][cellW];
		int width = 0;
		boolean any = false;
		for (int y = 0; y < cellH; y++) {
			for (int x = 0; x < cellW; x++) {
				int argb = img.getPixel(col * cellW + x, row * cellH + y);
				boolean on = (argb >>> 24) > 16;
				px[y][x] = on;
				if (on) {
					any = true;
					width = Math.max(width, x + 1);
				}
			}
		}
		if (!any) return null;
		float scale = page.height() / (float) cellH;
		float advance = (int) (0.5f + width * scale) + 1; // how Minecraft spaces bitmap letters
		return new Glyph(px, width, scale, 7 - page.ascent(), 0, advance);
	}

	// ---------------------------------------------------------------- font json

	private static void readFont(Identifier font, Map<Integer, Page> out, int depth) {
		if (depth > 4) return;
		ResourceManager rm = Minecraft.getInstance().getResourceManager();
		Identifier json = Identifier.fromNamespaceAndPath(font.getNamespace(), "font/" + font.getPath() + ".json");
		List<Resource> stack = rm.getResourceStack(json);
		// Highest-priority pack last in the stack; its letters win
		for (int i = stack.size() - 1; i >= 0; i--) {
			try (Reader r = new InputStreamReader(stack.get(i).open(), StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
				JsonArray providers = root.getAsJsonArray("providers");
				if (providers == null) continue;
				for (JsonElement el : providers) {
					if (!el.isJsonObject()) continue;
					JsonObject p = el.getAsJsonObject();
					String type = p.has("type") ? p.get("type").getAsString() : "";
					if (type.equals("reference") && p.has("id")) {
						readFont(Identifier.parse(p.get("id").getAsString()), out, depth + 1);
					} else if (type.equals("bitmap") && p.has("file") && p.has("chars")) {
						Identifier file = Identifier.parse(p.get("file").getAsString());
						int height = p.has("height") ? p.get("height").getAsInt() : 8;
						int ascent = p.has("ascent") ? p.get("ascent").getAsInt() : 7;
						List<int[]> rows = new java.util.ArrayList<>();
						for (JsonElement row : p.getAsJsonArray("chars")) rows.add(row.getAsString().codePoints().toArray());
						Page page = new Page(file, height, ascent, rows);
						for (int[] row : rows) {
							for (int c : row) if (c != 0 && c != ' ') out.putIfAbsent(c, page);
						}
					}
				}
			} catch (Exception e) {
				HearNoDubClient.LOGGER.debug("Could not read {}: {}", json, e.toString());
			}
		}
	}

	// ---------------------------------------------------------------- plain white texture

	public static final Identifier WHITE = HearNoDubClient.id("white");
	private static boolean whiteMade;

	/** A small plain white texture for solid-coloured geometry. Render thread only. */
	public static Identifier white() {
		if (!whiteMade) {
			whiteMade = true;
			DynamicTexture tex = new DynamicTexture(() -> "Hear No Dub white", 4, 4, false) {
				{
					this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
				}
			};
			NativeImage img = tex.getPixels();
			for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setPixel(x, y, 0xFFFFFFFF);
			tex.upload();
			Minecraft.getInstance().getTextureManager().register(WHITE, tex);
		}
		return WHITE;
	}
}

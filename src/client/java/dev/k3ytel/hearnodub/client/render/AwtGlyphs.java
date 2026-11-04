package dev.k3ytel.hearnodub.client.render;

import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.LetterFont;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Letters from the computer's fonts (Arial Black, Georgia, Comic Sans, Impact, Consolas,
 * Segoe Script...) turned into pixel grids, so they can be built into 3D letters like the
 * Minecraft font. Drawn at a few pixels per text pixel, so curves come out smooth.
 * Sized so capital letters are as tall as Minecraft's (7 text pixels).
 */
public final class AwtGlyphs {
	private AwtGlyphs() {}

	private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
	private static final Map<String, Font> fonts = new HashMap<>();
	private static final Map<String, GlyphBitmaps.Glyph> cache = new HashMap<>();
	private static final Set<String> missing = new HashSet<>();
	private static Set<String> installed;

	/** True for fonts drawn by Java (bundled with the mod or from the computer) rather than Minecraft. */
	public static boolean isSystemFont(LetterFont f) {
		return switch (f) {
			case MINECRAFT, GALACTIC, ILLAGER -> false;
			default -> true;
		};
	}

	/** Fonts that ship with the mod (all SIL Open Font License or Apache 2.0, see fonts3d/licenses). */
	private static String bundled(LetterFont f) {
		return switch (f) {
			case POSTER -> "Bungee-Regular.ttf";
			case COMIC -> "Bangers-Regular.ttf";
			case ROUNDED -> "TitanOne-Regular.ttf";
			case CHUNKY -> "LuckiestGuy-Regular.ttf";
			case MARKER -> "PermanentMarker-Regular.ttf";
			case SCRIPT -> "Pacifico-Regular.ttf";
			case LOBSTER -> "Lobster-Regular.ttf";
			case ELEGANT -> "CinzelDecorative-Bold.ttf";
			case GOTHIC -> "UnifrakturMaguntia-Book.ttf";
			case RETRO -> "Righteous-Regular.ttf";
			case FUTURE -> "Audiowide-Regular.ttf";
			case STENCIL -> "BlackOpsOne-Regular.ttf";
			case HORROR -> "Creepster-Regular.ttf";
			case ARCADE -> "PressStart2P-Regular.ttf";
			case TALL -> "BebasNeue-Regular.ttf";
			case NEON_LINES -> "Monoton-Regular.ttf";
			case BLOCKY -> "RubikMonoOne-Regular.ttf";
			default -> null;
		};
	}

	private static Font loadBundled(String file) {
		try (var in = AwtGlyphs.class.getResourceAsStream("/assets/hear-no-dub/fonts3d/" + file)) {
			if (in == null) return null;
			return Font.createFont(Font.TRUETYPE_FONT, in);
		} catch (Exception e) {
			HearNoDubClient.LOGGER.warn("Could not load font {}: {}", file, e.toString());
			return null;
		}
	}

	/**
	 * @param res pixels per text pixel (higher = smoother, more geometry)
	 */
	public static GlyphBitmaps.Glyph get(LetterFont f, int cp, float res) {
		String key = f + "|" + res + "|" + cp;
		GlyphBitmaps.Glyph g = cache.get(key);
		if (g != null || missing.contains(key)) return g;
		try {
			g = make(f, cp, res);
		} catch (Throwable t) {
			HearNoDubClient.LOGGER.debug("Could not draw U+{} in {}: {}", Integer.toHexString(cp), f, t.toString());
			g = null;
		}
		if (g == null) missing.add(key);
		else cache.put(key, g);
		return g;
	}

	/** Width of a space in text pixels. */
	public static float spaceAdvance(LetterFont f, float res) {
		Font font = font(f, res);
		if (font == null) return 4;
		return (float) font.getStringBounds(" ", FRC).getWidth() / res;
	}

	private static GlyphBitmaps.Glyph make(LetterFont f, int cp, float res) {
		Font font = font(f, res);
		if (font == null || !font.canDisplay(cp)) return null;
		String s = new String(Character.toChars(cp));
		GlyphVector gv = font.createGlyphVector(FRC, s);
		float advance = (float) gv.getGlyphMetrics(0).getAdvance();
		Rectangle2D vb = gv.getVisualBounds();
		if (vb.isEmpty()) return null;
		int pad = 2;
		int w = (int) Math.ceil(vb.getWidth()) + pad * 2, h = (int) Math.ceil(vb.getHeight()) + pad * 2;
		if (w <= 0 || h <= 0 || w > 512 || h > 512) return null;
		double ox = -vb.getX() + pad, oy = -vb.getY() + pad; // pen position (baseline) inside the image
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g2 = img.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g2.setColor(java.awt.Color.WHITE);
		g2.setFont(font);
		g2.drawGlyphVector(gv, (float) ox, (float) oy);
		g2.dispose();
		boolean[][] px = new boolean[h][w];
		boolean any = false;
		int right = 0;
		var raster = img.getRaster();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean on = raster.getSample(x, y, 0) >= 110;
				px[y][x] = on;
				if (on) {
					any = true;
					right = Math.max(right, x + 1);
				}
			}
		}
		if (!any) return null;
		// row r sits at text y = 7 + (r - oy) / res  (baseline at 7, like Minecraft's font)
		float top = 7f - (float) oy / res;
		float left = (float) -ox / res;
		return new GlyphBitmaps.Glyph(px, right, 1f / res, top, left, advance / res);
	}

	/** The font for a style, sized so a capital H is 7 text pixels tall at this resolution. */
	private static Font font(LetterFont f, float res) {
		String key = f + "|" + res;
		if (fonts.containsKey(key)) return fonts.get(key);
		Font base = null;
		String family;
		String file = bundled(f);
		if (file != null) {
			Font loaded = loadBundled(file);
			if (loaded != null) base = loaded.deriveFont(Font.PLAIN, 100f);
			family = file;
		} else {
			family = null;
		}
		if (base == null) {
			String[] families;
			int style;
			switch (f) {
				case BOLD -> { families = new String[] {"Arial Black", "Segoe UI Black", "Verdana", "SansSerif"}; style = Font.BOLD; }
				case SERIF -> { families = new String[] {"Georgia", "Cambria", "Times New Roman", "Serif"}; style = Font.BOLD; }
				case IMPACT -> { families = new String[] {"Impact", "Haettenschweiler", "Arial Black", "SansSerif"}; style = Font.PLAIN; }
				case MONO -> { families = new String[] {"Consolas", "Cascadia Mono", "Courier New", "Monospaced"}; style = Font.BOLD; }
				default -> { families = new String[] {"SansSerif"}; style = Font.BOLD; }
			}
			family = pick(families);
			base = new Font(family, style, 100);
		}
		double capH = base.createGlyphVector(FRC, "H").getVisualBounds().getHeight();
		if (capH <= 1) capH = 72;
		Font sized = base.deriveFont((float) (7 * res * 100 / capH));
		HearNoDubClient.LOGGER.debug("3D letters: {} uses '{}'", f, family);
		fonts.put(key, sized);
		return sized;
	}

	private static String pick(String[] families) {
		if (installed == null) {
			installed = new HashSet<>();
			try {
				for (String n : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)) {
					installed.add(n.toLowerCase(Locale.ROOT));
				}
			} catch (Throwable t) {
				HearNoDubClient.LOGGER.debug("Could not list fonts: {}", t.toString());
			}
		}
		for (String f : families) {
			if (installed.contains(f.toLowerCase(Locale.ROOT))) return f;
		}
		String last = families[families.length - 1];
		return Arrays.asList("SansSerif", "Serif", "Monospaced", "Dialog").contains(last) ? last : "SansSerif";
	}
}

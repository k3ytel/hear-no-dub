package dev.k3ytel.hearnodub.client.render;

import dev.k3ytel.hearnodub.client.config.LetterStyle;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Turns a letter's pixels into 3D geometry for each letter style. Built once per letter and
 * style, then reused every frame. Coordinates are text pixels: x from the pen position, y down
 * from the top of the line, z toward the viewer (front face at 0, the letter goes back to -depth).
 * <p>
 * Most styles are a height map: every pixel gets a front height (flat for Thick, a chamfer for
 * Chiseled, a dome for Balloon, a round tube along the edge for Neon) and walls are built where
 * neighbouring heights differ, so the shapes catch the light like real objects.
 */
public final class LetterMesh {
	private LetterMesh() {}

	/** Per quad: 12 position floats, 3 normal, shade, white mix (shine), alpha multiplier. */
	public static final int QUAD = 18;
	/** Thickness of solid letters, in text pixels. */
	public static final float DEPTH = 2.6f;

	@SuppressWarnings("unchecked")
	private static final Map<GlyphBitmaps.Glyph, float[]>[] CACHE = new Map[LetterStyle.values().length];

	static {
		for (int i = 0; i < CACHE.length; i++) CACHE[i] = new IdentityHashMap<>();
	}

	public static float[] get(GlyphBitmaps.Glyph g, LetterStyle style) {
		return CACHE[style.ordinal()].computeIfAbsent(g, k -> switch (style) {
			case CUBES -> cubes(k);
			case FLAT -> flat(k);
			case CHISELED -> heightStyle(k, Profile.CHISEL);
			case BALLOON -> heightStyle(k, Profile.DOME);
			case NEON -> heightStyle(k, Profile.TUBE);
			case GLASS -> heightStyle(k, Profile.GLASS);
			case HOLOGRAM -> hologram(k);
			default -> heightStyle(k, Profile.FLAT);
		});
	}

	public static void clear() {
		for (Map<GlyphBitmaps.Glyph, float[]> m : CACHE) m.clear();
	}

	// ================================================================ builder

	private static final class Builder {
		float[] data = new float[QUAD * 64];
		int n;

		void quad(float shade, float white, float alpha, float nx, float ny, float nz, float... xyz) {
			if ((n + 1) * QUAD > data.length) data = Arrays.copyOf(data, data.length * 2);
			int o = n * QUAD;
			System.arraycopy(xyz, 0, data, o, 12);
			data[o + 12] = nx;
			data[o + 13] = ny;
			data[o + 14] = nz;
			data[o + 15] = shade;
			data[o + 16] = white;
			data[o + 17] = alpha;
			n++;
		}

		float[] done() {
			return Arrays.copyOf(data, n * QUAD);
		}
	}

	// ================================================================ height-map styles

	private enum Profile { FLAT, CHISEL, DOME, TUBE, GLASS }

	/** Light comes from the upper left, a bit toward the viewer. */
	private static final float LX = -0.45f, LY = -0.65f, LZ = 0.62f;

	private static float[] heightStyle(GlyphBitmaps.Glyph g, Profile profile) {
		boolean[][] mask = g.pixels();
		int rows = mask.length, cols = mask[0].length;
		float s = g.scale(), ox = g.left(), oy = g.top();
		int[][] dist = distance(mask);

		// how wide the bevel / dome / tube is, in pixels
		int R = switch (profile) {
			case CHISEL -> Math.max(1, Math.round(0.7f / s));
			case DOME -> Math.max(2, Math.round(2.0f / s));
			case TUBE -> Math.max(1, Math.round(1.1f / s));
			default -> 1;
		};
		float depth = switch (profile) {
			case DOME -> 3.2f;
			case TUBE -> 0.9f;
			default -> DEPTH;
		};
		boolean[][] on = new boolean[rows][cols];
		float[][] z = new float[rows][cols];
		float[][] white = new float[rows][cols];
		float[][] alpha = new float[rows][cols];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				if (!mask[r][c]) continue;
				int d = dist[r][c];
				on[r][c] = true;
				alpha[r][c] = 1;
				switch (profile) {
					case CHISEL -> z[r][c] = -0.95f * (1 - Math.min(d, R + 1) / (float) (R + 1));
					case DOME -> {
						float k = Math.min(1f, (d - 0.5f) / R);
						z[r][c] = -1.7f * (1 - (float) Math.sqrt(Math.max(0, 1 - (1 - k) * (1 - k))));
					}
					case TUBE -> {
						if (d > R * 2) {
							on[r][c] = false;
							continue;
						}
						float k = (d - 0.5f) / (R * 2f); // 0 at the outer edge, 1 at the inner edge
						float round = (float) Math.sqrt(Math.max(0, 1 - (2 * k - 1) * (2 * k - 1)));
						z[r][c] = -0.55f * (1 - round);
						white[r][c] = 0.75f * round * round; // white-hot core
					}
					case GLASS -> {
						alpha[r][c] = d <= 1 ? 0.75f : 0.42f;
						white[r][c] = d <= 1 ? 0.35f : 0.05f;
					}
					default -> { }
				}
			}
		}

		Builder b = new Builder();
		// front faces, lit by their slope
		for (int r = 0; r < rows; r++) {
			float grad = 1.0f - 0.14f * r / Math.max(1, rows - 1); // a touch darker toward the bottom
			for (int c = 0; c < cols; ) {
				if (!on[r][c]) { c++; continue; }
				float[] n = normal(z, on, r, c, s);
				float shade = grad * shadeFor(n, profile);
				float wh = white[r][c] + (profile == Profile.DOME || profile == Profile.CHISEL ? specular(n) : 0);
				float a = alpha[r][c];
				int start = c;
				float zz = z[r][c];
				c++;
				while (c < cols && on[r][c] && Math.abs(z[r][c] - zz) < 1e-4f && Math.abs(white[r][c] - white[r][start]) < 1e-3f
						&& Math.abs(alpha[r][c] - a) < 1e-3f && sameNormal(normal(z, on, r, c, s), n)) c++;
				float x0 = ox + start * s, x1 = ox + c * s, y0 = oy + r * s, y1 = y0 + s;
				b.quad(shade, wh, a, n[0], n[1], n[2], x0, y1, zz, x1, y1, zz, x1, y0, zz, x0, y0, zz);
			}
		}
		// back plate
		float back = -depth;
		float backAlpha = profile == Profile.GLASS ? 0.35f : 1f;
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; ) {
				if (!on[r][c]) { c++; continue; }
				int start = c;
				while (c < cols && on[r][c]) c++;
				float x0 = ox + start * s, x1 = ox + c * s, y0 = oy + r * s, y1 = y0 + s;
				b.quad(0.5f, 0, backAlpha, 0, 0, -1, x0, y0, back, x1, y0, back, x1, y1, back, x0, y1, back);
			}
		}
		// walls between a pixel and a deeper (or empty) neighbour; merged into strips where they line up
		float wallAlpha = profile == Profile.GLASS ? 0.55f : 1f;
		float wallWhite = profile == Profile.TUBE ? 0.25f : 0f;
		for (int r = 0; r < rows; r++) {
			for (int pass = 0; pass < 2; pass++) { // 0 = top, 1 = bottom
				int rr = pass == 0 ? r - 1 : r + 1;
				for (int c = 0; c < cols; ) {
					float[] w = wall(on, z, r, c, rr, c, back);
					if (w == null) { c++; continue; }
					int start = c;
					c++;
					while (c < cols) {
						float[] w2 = wall(on, z, r, c, rr, c, back);
						if (w2 == null || Math.abs(w2[0] - w[0]) > 1e-4f || Math.abs(w2[1] - w[1]) > 1e-4f) break;
						c++;
					}
					float x0 = ox + start * s, x1 = ox + c * s;
					if (pass == 0) {
						float y = oy + r * s;
						b.quad(0.92f, wallWhite, wallAlpha, 0, -1, 0, x0, y, w[0], x1, y, w[0], x1, y, w[1], x0, y, w[1]);
					} else {
						float y = oy + (r + 1) * s;
						b.quad(0.5f, wallWhite, wallAlpha, 0, 1, 0, x0, y, w[1], x1, y, w[1], x1, y, w[0], x0, y, w[0]);
					}
				}
			}
		}
		for (int c = 0; c < cols; c++) {
			for (int pass = 0; pass < 2; pass++) { // 0 = left, 1 = right
				int cc = pass == 0 ? c - 1 : c + 1;
				for (int r = 0; r < rows; ) {
					float[] w = wall(on, z, r, c, r, cc, back);
					if (w == null) { r++; continue; }
					int start = r;
					r++;
					while (r < rows) {
						float[] w2 = wall(on, z, r, c, r, cc, back);
						if (w2 == null || Math.abs(w2[0] - w[0]) > 1e-4f || Math.abs(w2[1] - w[1]) > 1e-4f) break;
						r++;
					}
					float y0 = oy + start * s, y1 = oy + r * s;
					if (pass == 0) {
						float x = ox + c * s;
						b.quad(0.68f, wallWhite, wallAlpha, -1, 0, 0, x, y0, w[1], x, y1, w[1], x, y1, w[0], x, y0, w[0]);
					} else {
						float x = ox + (c + 1) * s;
						b.quad(0.76f, wallWhite, wallAlpha, 1, 0, 0, x, y0, w[0], x, y1, w[0], x, y1, w[1], x, y0, w[1]);
					}
				}
			}
		}
		return b.done();
	}

	/** {top z, bottom z} of the wall between pixel (r,c) and its neighbour, or null for none. */
	private static float[] wall(boolean[][] on, float[][] z, int r, int c, int nr, int nc, float back) {
		if (!on[r][c]) return null;
		boolean inside = nr >= 0 && nr < on.length && nc >= 0 && nc < on[0].length && on[nr][nc];
		float top = z[r][c];
		float bottom = inside ? z[nr][nc] : back;
		if (top - bottom < 1e-4f) return null;
		return new float[] {top, bottom};
	}

	private static float[] normal(float[][] z, boolean[][] on, int r, int c, float s) {
		float zl = c > 0 && on[r][c - 1] ? z[r][c - 1] : z[r][c];
		float zr = c + 1 < on[r].length && on[r][c + 1] ? z[r][c + 1] : z[r][c];
		float zu = r > 0 && on[r - 1][c] ? z[r - 1][c] : z[r][c];
		float zd = r + 1 < on.length && on[r + 1][c] ? z[r + 1][c] : z[r][c];
		float dx = (zr - zl) / (2 * s), dy = (zd - zu) / (2 * s);
		float nx = -dx, ny = -dy, nz = 1;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return new float[] {nx / len, ny / len, nz / len};
	}

	private static boolean sameNormal(float[] a, float[] b) {
		return Math.abs(a[0] - b[0]) < 1e-3f && Math.abs(a[1] - b[1]) < 1e-3f;
	}

	private static float shadeFor(float[] n, Profile p) {
		float dot = Math.max(0, n[0] * LX + n[1] * LY + n[2] * LZ) / 1.0f;
		float lit = 0.55f + 0.5f * dot / LZ * 0.9f;
		return p == Profile.FLAT || p == Profile.GLASS ? 1f : Math.min(1.15f, lit);
	}

	private static float specular(float[] n) {
		// half vector between the light and the viewer (0,0,1)
		float hx = LX, hy = LY, hz = LZ + 1;
		float len = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
		float d = Math.max(0, (n[0] * hx + n[1] * hy + n[2] * hz) / len);
		return (float) Math.pow(d, 28) * 0.55f;
	}

	/** Chessboard distance from each lit pixel to the nearest unlit one (1 = on the edge). */
	private static int[][] distance(boolean[][] mask) {
		int rows = mask.length, cols = mask[0].length;
		int[][] d = new int[rows][cols];
		ArrayDeque<int[]> q = new ArrayDeque<>();
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				if (!mask[r][c]) continue;
				boolean edge = false;
				for (int dr = -1; dr <= 1 && !edge; dr++) {
					for (int dc = -1; dc <= 1; dc++) {
						int rr = r + dr, cc = c + dc;
						if (rr < 0 || rr >= rows || cc < 0 || cc >= cols || !mask[rr][cc]) {
							edge = true;
							break;
						}
					}
				}
				if (edge) {
					d[r][c] = 1;
					q.add(new int[] {r, c});
				}
			}
		}
		while (!q.isEmpty()) {
			int[] p = q.poll();
			for (int dr = -1; dr <= 1; dr++) {
				for (int dc = -1; dc <= 1; dc++) {
					int rr = p[0] + dr, cc = p[1] + dc;
					if (rr < 0 || rr >= rows || cc < 0 || cc >= cols || !mask[rr][cc] || d[rr][cc] != 0) continue;
					d[rr][cc] = d[p[0]][p[1]] + 1;
					q.add(new int[] {rr, cc});
				}
			}
		}
		return d;
	}

	// ================================================================ other styles

	/** A little statue: every font pixel is its own small cube with a gap around it. */
	private static float[] cubes(GlyphBitmaps.Glyph g) {
		boolean[][] px = g.pixels();
		float s = g.scale(), ox = g.left(), oy = g.top();
		float gap = 0.11f * s, d = s - 2 * gap;
		int rows = px.length;
		Builder m = new Builder();
		for (int r = 0; r < rows; r++) {
			float shade = 1.0f - 0.12f * r / Math.max(1, rows - 1);
			for (int c = 0; c < px[r].length; c++) {
				if (!px[r][c]) continue;
				float x0 = ox + c * s + gap, x1 = x0 + d, y0 = oy + r * s + gap, y1 = y0 + d;
				float z0 = -gap, z1 = -gap - d;
				m.quad(shade, 0, 1, 0, 0, 1, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0);
				m.quad(0.55f, 0, 1, 0, 0, -1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
				m.quad(0.92f * shade, 0, 1, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
				m.quad(0.5f, 0, 1, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
				m.quad(0.7f * shade, 0, 1, -1, 0, 0, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
				m.quad(0.7f * shade, 0, 1, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
			}
		}
		return m.done();
	}

	/** Flat letters with a crisp outline behind them (shade -1 = outline colour). */
	private static float[] flat(GlyphBitmaps.Glyph g) {
		boolean[][] px = g.pixels();
		float s = g.scale(), ox = g.left(), oy = g.top();
		int rows = px.length, cols = px[0].length;
		int grow = Math.max(1, Math.round(0.55f / s));
		Builder m = new Builder();
		for (int r = -grow; r < rows + grow; r++) {
			for (int c = -grow; c < cols + grow; ) {
				if (!near(px, r, c, grow)) { c++; continue; }
				int start = c;
				while (c < cols + grow && near(px, r, c, grow)) c++;
				float x0 = ox + start * s, x1 = ox + c * s, y0 = oy + r * s, y1 = y0 + s;
				m.quad(-1, 0, 1, 0, 0, 1, x0, y1, -0.12f, x1, y1, -0.12f, x1, y0, -0.12f, x0, y0, -0.12f);
			}
		}
		for (int r = 0; r < rows; r++) {
			float shade = 1.0f - 0.1f * r / Math.max(1, rows - 1);
			for (int c = 0; c < cols; ) {
				if (!px[r][c]) { c++; continue; }
				int start = c;
				while (c < cols && px[r][c]) c++;
				float x0 = ox + start * s, x1 = ox + c * s, y0 = oy + r * s, y1 = y0 + s;
				m.quad(shade, 0, 1, 0, 0, 1, x0, y1, 0, x1, y1, 0, x1, y0, 0, x0, y0, 0);
			}
		}
		return m.done();
	}

	/** Sci-fi hologram: thin glowing slices with scan-line gaps, see-through. */
	private static float[] hologram(GlyphBitmaps.Glyph g) {
		boolean[][] px = g.pixels();
		float s = g.scale(), ox = g.left(), oy = g.top();
		int rows = px.length, cols = px[0].length;
		int period = Math.max(2, Math.round(0.75f / s));
		Builder m = new Builder();
		for (int layer = 0; layer < 2; layer++) {
			float zz = layer == 0 ? 0 : -1.2f;
			float a = layer == 0 ? 0.8f : 0.3f;
			for (int r = 0; r < rows; r++) {
				if (r % period == period - 1) continue; // scan-line gap
				for (int c = 0; c < cols; ) {
					if (!px[r][c]) { c++; continue; }
					int start = c;
					while (c < cols && px[r][c]) c++;
					float x0 = ox + start * s, x1 = ox + c * s, y0 = oy + r * s, y1 = y0 + s;
					m.quad(1f, 0.25f, a, 0, 0, 1, x0, y1, zz, x1, y1, zz, x1, y0, zz, x0, y0, zz);
				}
			}
		}
		return m.done();
	}

	private static boolean near(boolean[][] px, int r, int c, int grow) {
		for (int dr = -grow; dr <= grow; dr++) {
			int rr = r + dr;
			if (rr < 0 || rr >= px.length) continue;
			for (int dc = -grow; dc <= grow; dc++) {
				int cc = c + dc;
				if (cc < 0 || cc >= px[rr].length) continue;
				if (dr * dr + dc * dc <= grow * grow + grow && px[rr][cc]) return true;
			}
		}
		return false;
	}
}

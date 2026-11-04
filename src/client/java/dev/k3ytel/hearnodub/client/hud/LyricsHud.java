package dev.k3ytel.hearnodub.client.hud;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.LyricsEffect;
import dev.k3ytel.hearnodub.client.config.LyricsReveal;
import dev.k3ytel.hearnodub.client.config.LyricsView;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import dev.k3ytel.hearnodub.client.render.SubStyle;
import dev.k3ytel.hearnodub.client.render.SubText;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The song lyrics display: karaoke done properly.
 * <ul>
 *   <li>Stacked view: the line being sung in the middle, the previous and next lines smaller
 *       and dimmed; lines glide up when the next one starts.</li>
 *   <li>Every word is timed (from word-synced lyrics, or guessed from the line): colour sweeps
 *       across it as it's sung, and the word being sung lifts, bounces, glows, waves or wobbles.</li>
 *   <li>Upcoming words can be dimmed, or appear word by word / letter by letter as they're sung.</li>
 *   <li>Held notes ripple, SHOUTED words shake, background vocals show small under the line,
 *       instrumental breaks show breathing dots, and a title card opens each song.</li>
 * </ul>
 */
public final class LyricsHud {
	private LyricsHud() {}

	private static final int[][] RING = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
	private static final Random RANDOM = new Random();
	private static final float SIDE_SCALE = 0.72f;
	private static final long SCROLL_MS = 420;
	/** Show breathing dots when the gap before the next line is at least this long. */
	private static final long BREAK_MS = 4500;

	// main lines (no background vocals) of the loaded track, rebuilt when the track changes
	private static List<SubtitleTrack.Cue> cachedAll;
	private static List<SubtitleTrack.Cue> main = List.of();
	private static List<SubtitleTrack.Cue> background = List.of();
	private static final Map<SubtitleTrack.Cue, long[][]> timings = new IdentityHashMap<>();

	private static int lastIndex = Integer.MIN_VALUE;
	private static long scrollAt;

	// title card
	private static String cardFor;
	private static long cardAt;

	/** Draws the lyrics display. Returns false when the normal caption should be used instead. */
	public static boolean draw(GuiGraphicsExtractor g, Font font, SubtitleManager m, HearNoDubConfig cfg) {
		if (cfg.lyricsView == LyricsView.CLASSIC || !m.isLyricsTrack()) return false;
		List<SubtitleTrack.Cue> all = m.allCues();
		if (all != cachedAll) {
			cachedAll = all;
			List<SubtitleTrack.Cue> mm = new ArrayList<>(), bb = new ArrayList<>();
			for (SubtitleTrack.Cue c : all) (c.isBackground() ? bb : mm).add(c);
			main = mm;
			background = bb;
			timings.clear();
			lastIndex = Integer.MIN_VALUE;
		}
		long t = m.subtitleTimeMs();
		long now = System.currentTimeMillis();
		titleCard(g, font, m, cfg, now);
		if (t < 0 || main.isEmpty()) return true;

		SubStyle style = SubStyle.resolve(cfg, false);
		float S = cfg.textScale * cfg.lyricsScale;
		int W = g.guiWidth(), H = g.guiHeight();
		float cx = cfg.posX * W;
		int rowH = font.lineHeight + 3;
		float y0 = cfg.posY * H - rowH * S * 1.6f;

		int idx = indexAt(t);
		// Line glides up when the next one starts; jumps (seeking) snap
		if (idx != lastIndex) {
			scrollAt = idx == lastIndex + 1 ? now : 0;
			lastIndex = idx;
		}
		float e = scrollAt == 0 ? 1f : easeOut(Math.min(1f, (now - scrollAt) / (float) SCROLL_MS));

		boolean showCurrent = idx >= 0 && showing(idx, t);
		int wrap = Math.max(60, Math.min(cfg.maxLineWidth, (int) ((W - 16) / S)));

		// Layout now and (while scrolling) where things were a moment ago
		Map<Integer, float[]> to = layout(font, idx, y0, S, rowH, wrap, cfg);
		Map<Integer, float[]> from = e < 1f ? layout(font, idx - 1, y0, S, rowH, wrap, cfg) : to;
		for (int k = idx - 2; k <= idx + 2; k++) {
			if (k < 0 || k >= main.size()) continue;
			float[] a = from.get(k), b = to.get(k);
			if (a == null && b == null) continue;
			if (a == null) a = new float[] {b[0] + rowH * S * 1.2f, b[1], 0};
			if (b == null) b = new float[] {a[0] - rowH * S * 1.2f, a[1], 0};
			float y = lerp(a[0], b[0], e), scale = lerp(a[1], b[1], e), alpha = lerp(a[2], b[2], e);
			alpha *= breakFade(k, idx, t, showCurrent);
			if (alpha <= 0.02f) continue;
			int state = k < idx ? -1 : k > idx ? 1 : 0;
			if (k == idx && !showCurrent) state = -1;
			boolean chorus = cfg.lyricsChorus && dev.k3ytel.hearnodub.client.subs.Chorus.isChorus(all, main.get(k));
			float cs = chorus && k == idx ? 1.1f : 1f;
			drawLine(g, font, main.get(k), cx, y, scale * S * cs, alpha, state, t, now, wrap, rowH, cfg, style, chorus);
			if (k == idx) drawBackground(g, font, main.get(k), cx, y, scale * S, alpha, t, now, wrap, rowH, cfg, style);
		}

		// Instrumental break: breathing dots where the next line will be sung
		if (!showCurrent) {
			long prevEnd = idx >= 0 ? main.get(idx).endMs() : 0;
			if (idx + 1 < main.size()) {
				long next = main.get(idx + 1).startMs();
				if (next - prevEnd >= BREAK_MS && t < next) {
					float p = (t - prevEnd) / (float) (next - prevEnd);
					drawDots(g, cx, y0, S, p, next - t, now, cfg, style);
				}
			}
		}
		return true;
	}

	/**
	 * During a long instrumental part the old lines fade away quickly and the next line only
	 * shows up a couple of seconds before it's sung (instead of hanging around the whole break).
	 */
	private static float breakFade(int k, int idx, long t, boolean showCurrent) {
		if (showCurrent) return 1;
		long ended = idx >= 0 ? main.get(idx).endMs() : 0;
		long next = idx + 1 < main.size() ? main.get(idx + 1).startMs() : Long.MAX_VALUE;
		if (k <= idx) {
			float gone = clamp01((t - ended) / 900f);
			return (k == idx ? 0.55f : 1f) * (1 - gone);
		}
		if (k == idx + 1) {
			if (next == Long.MAX_VALUE) return 0;
			return clamp01(1 - (next - t - 600) / 2000f);
		}
		return 0;
	}

	/** The next few lines with a countdown, top left (handy to plan a shot). */
	public static void drawUpcoming(GuiGraphicsExtractor g, Font font, SubtitleManager m, HearNoDubConfig cfg) {
		if (!cfg.enabled || !cfg.lyricsUpcoming || !m.isLyricsTrack()) return;
		long t = m.subtitleTimeMs();
		if (t < 0) return;
		List<String> rows = new ArrayList<>();
		for (SubtitleTrack.Cue c : m.allCues()) {
			if (c.isBackground() || c.startMs() <= t) continue;
			String line = c.text().replace('\n', ' ');
			if (font.width(line) > 220) line = font.plainSubstrByWidth(line, 210) + "…";
			rows.add(String.format(java.util.Locale.ROOT, "%5.1fs  %s", (c.startMs() - t) / 1000.0, line));
			if (rows.size() >= 4) break;
		}
		if (rows.isEmpty()) return;
		int w = 0;
		for (String r : rows) w = Math.max(w, font.width(r));
		int x = 6, y = g.guiHeight() / 2 - rows.size() * 6;
		g.fill(x - 3, y - 3, x + w + 3, y + rows.size() * (font.lineHeight + 2) + 1, 0x90000000);
		for (int i = 0; i < rows.size(); i++) {
			g.text(font, rows.get(i), x, y + i * (font.lineHeight + 2), i == 0 ? 0xFFFFE680 : 0xFFAAAAAA, true);
		}
	}

	/** The last main line that has started, or -1. */
	private static int indexAt(long t) {
		int lo = 0, hi = main.size() - 1, ans = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (main.get(mid).startMs() <= t) {
				ans = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return ans;
	}

	/** A line stays up until it ends, or until the next one if that comes soon. */
	private static boolean showing(int idx, long t) {
		SubtitleTrack.Cue c = main.get(idx);
		if (t < c.endMs()) return true;
		if (idx + 1 >= main.size()) return t < c.endMs() + 1500;
		long next = main.get(idx + 1).startMs();
		return next - c.endMs() < BREAK_MS && t < next;
	}

	/**
	 * Where each line sits when line {@code center} is the current one: {y, scale, alpha} by
	 * line index. The stacked view shows one line above and below.
	 */
	private static Map<Integer, float[]> layout(Font font, int center, float y0, float S, int rowH, int wrap, HearNoDubConfig cfg) {
		Map<Integer, float[]> out = new java.util.HashMap<>();
		boolean stack = cfg.lyricsView == LyricsView.STACK;
		float dim = Math.max(0.25f, cfg.lyricsDimOpacity / 100f + 0.15f);
		float gap = 5 * S;
		// before the first line, the first line waits "below" the centre
		int c = Math.max(center, -1);
		float[] cur = {y0, 1f, c >= 0 ? 1f : 0f};
		if (c >= 0 && c < main.size()) out.put(c, cur);
		float hc = c >= 0 && c < main.size() ? rows(font, main.get(c), wrap) * rowH * S : rowH * S;
		float yUp = y0 - hc / 2, yDown = y0 + hc / 2;
		for (int k = c - 1; k >= c - 2 && k >= 0; k--) {
			float h = rows(font, main.get(k), wrap) * rowH * S * SIDE_SCALE;
			float y = yUp - gap - h / 2;
			yUp = y - h / 2;
			float a = stack && k == c - 1 ? dim : 0f;
			out.put(k, new float[] {y, SIDE_SCALE, a});
		}
		for (int k = c + 1; k <= c + 2 && k < main.size(); k++) {
			float h = rows(font, main.get(k), wrap) * rowH * S * SIDE_SCALE;
			float y = yDown + gap + h / 2;
			yDown = y + h / 2;
			float a = stack && k == c + 1 ? dim : (c < 0 && k == 0 ? dim : 0f);
			out.put(k, new float[] {y, SIDE_SCALE, a});
		}
		return out;
	}

	// ================================================================ a line

	private record Word(String text, int width, int row, float x) {}

	/** Splits a line into words and wraps them into rows (x = left edge within its row). */
	private static List<Word> words(Font font, SubtitleTrack.Cue c, int wrap, int[] rowWidths) {
		String[] parts = c.text().replace('\n', ' ').trim().split("\\s+");
		List<Word> out = new ArrayList<>();
		int space = font.width(" ");
		int row = 0;
		float x = 0;
		for (String p : parts) {
			if (p.isEmpty()) continue;
			int w = font.width(p);
			if (x > 0 && x + space + w > wrap) {
				if (row < rowWidths.length) rowWidths[row] = (int) x;
				row++;
				x = 0;
			}
			if (x > 0) x += space;
			out.add(new Word(p, w, row, x));
			x += w;
		}
		if (row < rowWidths.length) rowWidths[row] = (int) x;
		return out;
	}

	private static int rows(Font font, SubtitleTrack.Cue c, int wrap) {
		List<Word> w = words(font, c, wrap, new int[8]);
		return w.isEmpty() ? 1 : w.getLast().row() + 1;
	}

	/** {start, end} of every word in ms: real word timing, or a guess from the line. */
	private static long[][] timing(SubtitleTrack.Cue c, int count, HearNoDubConfig cfg) {
		long[][] cached = timings.get(c);
		if (cached != null && cached.length == count) return cached;
		long[][] out = new long[count][2];
		if (c.hasWordTimes() && c.words().length == count) {
			for (int i = 0; i < count; i++) {
				out[i][0] = c.startMs() + c.words()[i];
				out[i][1] = c.startMs() + c.wordEnd(i);
			}
		} else {
			// longer words take longer to sing; leave a little breath at the end of the line
			String[] parts = c.text().replace('\n', ' ').trim().split("\\s+");
			int chars = 0;
			for (String p : parts) chars += p.length() + 1;
			long span = Math.min((long) (c.durationMs() * 0.88), Math.max(700, chars * 105L));
			long at = c.startMs();
			for (int i = 0; i < count; i++) {
				int len = i < parts.length ? parts[i].length() + 1 : 2;
				long d = Math.max(90, span * len / Math.max(1, chars));
				out[i][0] = at;
				out[i][1] = at + d;
				at += d;
			}
		}
		timings.put(c, out);
		return out;
	}

	/**
	 * @param state -1 = already sung, 0 = being sung now, 1 = coming up
	 */
	private static void drawLine(GuiGraphicsExtractor g, Font font, SubtitleTrack.Cue c, float cx, float cy, float scale,
			float alpha, int state, long t, long now, int wrap, int rowH, HearNoDubConfig cfg, SubStyle style, boolean small) {
		int[] rowWidths = new int[8];
		List<Word> words = words(font, c, wrap, rowWidths);
		if (words.isEmpty()) return;
		int rows = words.getLast().row() + 1;
		long[][] tm = timing(c, words.size(), cfg);
		int base = SubText.colorFor(c, cfg, cfg.lyricsHighlightColor) & 0xFFFFFF;
		float dim = cfg.lyricsDimOpacity / 100f;
		boolean italic = c.isBackground();
		float top = cy - rows * rowH * scale / 2f;

		for (int i = 0; i < words.size(); i++) {
			Word w = words.get(i);
			long ws = tm[i][0], we = Math.max(tm[i][0] + 1, tm[i][1]);
			int rowW = w.row() < rowWidths.length ? rowWidths[w.row()] : 0;
			float wordLeft = cx - rowW * scale / 2f + w.x() * scale;
			float rowBottom = top + (w.row() + 1) * rowH * scale - 2 * scale;

			// how far through this word we are
			float p; // 0..1 sung
			float env; // 0..1 "being sung right now"
			if (state < 0) {
				p = 1;
				env = 0;
			} else if (state > 0) {
				p = 0;
				env = 0;
			} else {
				p = clamp01((t - ws) / (float) (we - ws));
				env = t < ws ? 0 : t < ws + 90 ? (t - ws) / 90f : t < we ? 1 : Math.max(0, 1 - (t - we) / 300f);
			}
			boolean started = state < 0 || state == 0 && t >= ws;

			// reveal (how upcoming words look)
			float appear = 1, appearDy = 0, appearScale = 1;
			LyricsReveal reveal = cfg.lyricsReveal;
			if (state > 0 && reveal != LyricsReveal.DIMMED) continue; // not sung yet: nothing to show
			if (state == 0 && reveal == LyricsReveal.WORD_BY_WORD) {
				if (!started) continue;
				float k = clamp01((t - ws) / 220f);
				appear = clamp01((t - ws) / 90f);
				appearScale = 0.55f + 0.45f * backOut(k);
				appearDy = (1 - easeOut(k)) * 4;
			}

			// word effect
			float wdy = 0, wsx = 1, wsy = 1, glow = 0;
			boolean shout = cfg.lyricsEmphasis && isShout(w.text());
			LyricsEffect fx = cfg.lyricsEffect;
			if (env > 0 || state == 0 && t >= ws && t < ws + 500) {
				switch (fx) {
					case LIFT -> {
						wdy = -2.2f * env;
						wsx = wsy = 1 + 0.12f * env;
					}
					case BOUNCE -> {
						float u = clamp01((t - ws) / 300f);
						float hop = (float) Math.sin(Math.PI * u);
						wdy = -4.5f * hop;
						wsx = wsy = 1 + 0.06f * hop;
					}
					case GLOW -> {
						glow = env;
						wsx = wsy = 1 + 0.05f * env;
					}
					case WAVE -> wsx = wsy = 1 + 0.04f * env;
					case JELLY -> {
						float u = clamp01((t - ws) / 520f);
						float d = (float) (Math.exp(-5 * u) * Math.cos(3.2 * Math.PI * u));
						wsx = 1 + 0.26f * d;
						wsy = 1 - 0.2f * d;
						wdy = -1.2f * env;
					}
					default -> { }
				}
				if (shout && env > 0) {
					wsx *= 1 + 0.1f * env;
					wsy *= 1 + 0.1f * env;
				}
			}
			if (style.glow() && env > 0) glow = Math.max(glow, 0.5f * env);
			if (env > 0 && cfg.letterAudioReact && dev.k3ytel.hearnodub.client.media.AudioLevel.active()) {
				float beat = dev.k3ytel.hearnodub.client.media.AudioLevel.pulse();
				wsx *= 1 + 0.08f * beat * env;
				wsy *= 1 + 0.08f * beat * env;
				wdy -= 1.5f * beat * env;
			}

			int color = shout && env > 0 ? mix(base, 0xFFD27F, 0.35f * env) : base;
			boolean chorusLine = small;
			float pivotX = wordLeft + w.width() * scale / 2f;
			float pivotY = rowBottom;

			// letters
			String text = w.text();
			int n = text.codePointCount(0, text.length());
			float lx = 0;
			int ci = 0;
			boolean held = (we - ws) > 1000 && env > 0; // held notes ripple
			for (int off = 0; off < text.length(); ) {
				int cp = text.codePointAt(off);
				off += Character.charCount(cp);
				Style st = Style.EMPTY.withItalic(italic);
				FormattedCharSequence seq = FormattedCharSequence.codepoint(cp, st);
				int cw = font.width(seq);
				float charP = clamp01(p * n - ci); // colour sweep inside the word
				float a;
				if (started || state < 0) {
					a = cfg.lyricsFill ? lerp(dim, 1, state == 0 ? charP : 1) : 1;
				} else {
					a = dim;
				}
				float cdy = 0, cscale = 1;
				if (state == 0 && reveal == LyricsReveal.LETTER_BY_LETTER) {
					long at = ws + (long) ((we - ws) * (ci / (float) Math.max(1, n)) * 0.85f);
					if (t < at) {
						lx += cw;
						ci++;
						continue;
					}
					float k = clamp01((t - at) / 140f);
					cscale = 0.4f + 0.6f * backOut(k);
					cdy = (1 - easeOut(k)) * 3;
				}
				if (fx == LyricsEffect.WAVE && env > 0) cdy += -1.8f * env * (float) Math.sin(now * 0.012 - ci * 0.8);
				if (held) cdy += -1.3f * env * (float) Math.sin(now * 0.009 - ci * 0.9);
				float jx = 0, jy = 0;
				if (shout && env > 0) {
					jx = (RANDOM.nextFloat() - 0.5f) * 0.9f * env;
					jy = (RANDOM.nextFloat() - 0.5f) * 0.9f * env;
				}
				float fa = a * alpha * appear;
				int ccol = color;
				if (chorusLine && started) { // a shine sweeping across chorus lines
					float sweep = (float) Math.sin(now * 0.005 - (i * 6 + ci) * 0.45);
					if (sweep > 0) ccol = mix(color, 0xFFFFFF, 0.5f * (float) Math.pow(sweep, 10));
				}
				if (fa > 0.02f) {
					g.pose().pushMatrix();
					g.pose().translate(pivotX, pivotY);
					g.pose().scale(scale * wsx * appearScale, scale * wsy * appearScale);
					g.pose().translate(-w.width() / 2f + lx + jx, -font.lineHeight + 1 + wdy / (wsy) + appearDy + jy);
					if (cscale != 1 || cdy != 0) {
						g.pose().translate(cw / 2f, font.lineHeight + cdy);
						g.pose().scale(cscale, cscale);
						g.pose().translate(-cw / 2f, -font.lineHeight);
					}
					if (glow > 0.01f) {
						int gc = SubStyle.fade(0xFF000000 | color, 0.22f * glow * fa);
						for (int[] d : RING) text(g, font, seq, d[0], d[1], gc, false);
						int gc2 = SubStyle.fade(0xFF000000 | color, 0.1f * glow * fa);
						text(g, font, seq, -2, 0, gc2, false);
						text(g, font, seq, 2, 0, gc2, false);
						text(g, font, seq, 0, -2, gc2, false);
						text(g, font, seq, 0, 2, gc2, false);
					}
					if (style.outline()) {
						int oc = SubStyle.fade(style.outlineColor(), fa * 0.9f);
						for (int[] d : RING) text(g, font, seq, d[0], d[1], oc, false);
					}
					text(g, font, seq, 0, 0, SubStyle.fade(0xFF000000 | ccol, fa), style.shadow() && !style.outline());
					g.pose().popMatrix();
				}
				lx += cw;
				ci++;
			}
		}
	}

	/** Background vocals that belong to this line, small and italic under it. */
	private static void drawBackground(GuiGraphicsExtractor g, Font font, SubtitleTrack.Cue line, float cx, float cy, float scale,
			float alpha, long t, long now, int wrap, int rowH, HearNoDubConfig cfg, SubStyle style) {
		if (background.isEmpty()) return;
		int rows = rows(font, line, wrap);
		float y = cy + rows * rowH * scale / 2f + rowH * scale * 0.45f;
		for (SubtitleTrack.Cue b : background) {
			if (b.startMs() > line.endMs() || b.endMs() < line.startMs()) continue;
			if (t < b.startMs() - 200 && cfg.lyricsReveal != LyricsReveal.DIMMED) continue;
			drawLine(g, font, b, cx, y, scale * 0.66f, alpha * 0.85f, t >= b.endMs() + 300 ? -1 : 0, t, now, wrap, rowH, cfg, style, false);
			y += rowH * scale * 0.7f;
		}
	}

	private static boolean isShout(String w) {
		if (w.endsWith("!")) return true;
		int letters = 0;
		for (int i = 0; i < w.length(); i++) {
			char ch = w.charAt(i);
			if (Character.isLetter(ch)) {
				if (!Character.isUpperCase(ch)) return false;
				letters++;
			}
		}
		return letters >= 2;
	}

	// ================================================================ breaks, title card

	/** Three dots that fill up across an instrumental break and pop when singing resumes. */
	private static void drawDots(GuiGraphicsExtractor g, float cx, float cy, float S, float p, long left, long now,
			HearNoDubConfig cfg, SubStyle style) {
		int color = cfg.lyricsHighlightColor & 0xFFFFFF;
		float out = left < 350 ? left / 350f : 1; // shrink away just before the line
		float in = clamp01(p * 12); // grow in at the start
		for (int i = 0; i < 3; i++) {
			float fill = clamp01(p * 3 - i);
			float breathe = 1 + 0.08f * (float) Math.sin(now * 0.004 + i * 0.6);
			float beat = cfg.letterAudioReact ? dev.k3ytel.hearnodub.client.media.AudioLevel.pulse() : 0;
			float size = (2.2f + 1.4f * fill) * breathe * (1 + 0.3f * beat) * S * in * out;
			float a = (0.35f + 0.65f * fill) * in * out;
			float x = cx + (i - 1) * 9 * S;
			circle(g, x, cy, size, SubStyle.fade(0xFF000000 | color, a));
		}
	}

	private static void circle(GuiGraphicsExtractor g, float x, float y, float r, int color) {
		if ((color >>> 24) < 6 || r < 0.3f) return;
		// a filled circle from horizontal strips (GUI fill works in whole pixels, so draw scaled up)
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		float k = r / 8f;
		g.pose().scale(k, k);
		for (int dy = -8; dy < 8; dy++) {
			float yy = dy + 0.5f;
			int half = (int) Math.round(Math.sqrt(Math.max(0, 64 - yy * yy)));
			if (half > 0) g.fill(-half, dy, half, dy + 1, color);
		}
		g.pose().popMatrix();
	}

	private static void titleCard(GuiGraphicsExtractor g, Font font, SubtitleManager m, HearNoDubConfig cfg, long now) {
		if (!cfg.lyricsTitleCard) return;
		MediaBridge.Session s = m.session();
		if (s == null || s.title == null || s.title.isBlank()) return;
		String key = s.app + "|" + s.title;
		if (!key.equals(cardFor)) {
			cardFor = key;
			cardAt = now;
		}
		long el = now - cardAt;
		if (el > 5000) return;
		float in = easeOut(clamp01(el / 450f));
		float out = clamp01((5000 - el) / 600f);
		float a = in * out;
		if (a <= 0.02f) return;
		int W = g.guiWidth();
		float y = 10 + (1 - in) * -14;
		int color = cfg.lyricsHighlightColor & 0xFFFFFF;
		String title = "♫ " + s.title;
		float ts = 1.35f * cfg.textScale;
		g.pose().pushMatrix();
		g.pose().translate(W / 2f, y);
		g.pose().scale(ts, ts);
		int tw = font.width(title);
		text(g, font, FormattedCharSequence.forward(title, Style.EMPTY.withBold(true)), -tw / 2, 0, SubStyle.fade(0xFF000000 | color, a), true);
		g.pose().popMatrix();
		if (s.artist != null && !s.artist.isBlank()) {
			float as = 0.95f * cfg.textScale;
			g.pose().pushMatrix();
			g.pose().translate(W / 2f, y + font.lineHeight * ts + 3);
			g.pose().scale(as, as);
			int aw = font.width(s.artist);
			// the artist slides in a moment later
			float a2 = clamp01((el - 150) / 450f) * out;
			text(g, font, FormattedCharSequence.forward(s.artist, Style.EMPTY), -aw / 2, 0, SubStyle.fade(0xFFBBBBBB, a2), true);
			g.pose().popMatrix();
		}
	}

	// ================================================================ helpers

	private static void text(GuiGraphicsExtractor g, Font font, FormattedCharSequence seq, int x, int y, int color, boolean shadow) {
		if ((color >>> 24) < 6) return; // Minecraft draws alpha below 4 as fully opaque
		g.text(font, seq, x, y, color, shadow);
	}

	private static int mix(int a, int b, float k) {
		int r = Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
		int gg = Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
		int bl = Math.round((a & 255) * (1 - k) + (b & 255) * k);
		return r << 16 | gg << 8 | bl;
	}

	private static float lerp(float a, float b, float k) {
		return a + (b - a) * k;
	}

	private static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	private static float easeOut(float k) {
		float x = 1 - k;
		return 1 - x * x * x;
	}

	private static float backOut(float k) {
		float c1 = 1.70158f, c3 = c1 + 1;
		float x = k - 1;
		return 1 + c3 * x * x * x + c1 * x * x;
	}
}

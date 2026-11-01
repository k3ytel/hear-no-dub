package dev.k3ytel.hearnodub.client.render;

import dev.k3ytel.hearnodub.client.config.Animation;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the current cues into wrapped, styled lines ready to draw, applying karaoke,
 * typewriter, italics/bold, speaker colors and the fade/pop animation. Shared by every
 * display mode so they all look the same.
 */
public final class SubText {
	private SubText() {}

	public static final int MAX_LINES = 3;
	public static final int LINE_GAP = 1;

	private static final int FADE_IN_MS = 160;
	private static final int FADE_OUT_MS = 160;
	private static final int POP_MS = 200;
	private static final int TYPE_MS_PER_CHAR = 35;

	private static final int[] SPEAKER_PALETTE = {
			0xFFE680, 0x80E0FF, 0xFFA0C8, 0xA8FF98, 0xFFB870, 0xC8A8FF, 0x9EF0D8, 0xFF8C8C
	};

	/**
	 * One drawable line. {@code colored} has the real colors; {@code shape} has the same
	 * glyphs with no color, for outline/glow passes.
	 */
	public record Line(FormattedCharSequence colored, FormattedCharSequence shape, int width, boolean preview) {}

	/**
	 * A block of lines to draw together.
	 *
	 * @param key   identifies the group of cues (changes when the next subtitle starts)
	 * @param alpha 0..1 fade
	 * @param scale pop animation scale (1 = normal)
	 */
	public record Block(List<Line> lines, int width, int height, float alpha, float scale, long key) {
		public boolean isEmpty() {
			return lines.isEmpty();
		}
	}

	public static final Block EMPTY = new Block(List.of(), 0, 0, 0, 1, Long.MIN_VALUE);

	/**
	 * @param cues    the cues on screen now
	 * @param next    the upcoming cue for the preview (or null)
	 * @param nowMs   current time in the subtitle file
	 * @param wrapPx  wrap width in font pixels
	 */
	public static Block build(Font font, List<SubtitleTrack.Cue> cues, SubtitleTrack.Cue next, long nowMs,
			HearNoDubConfig cfg, int wrapPx, int textColor) {
		if (cues.isEmpty()) return EMPTY;

		long groupStart = Long.MAX_VALUE, groupEnd = Long.MIN_VALUE;
		for (SubtitleTrack.Cue c : cues) {
			groupStart = Math.min(groupStart, c.startMs());
			groupEnd = Math.max(groupEnd, c.endMs());
		}
		long elapsed = Math.max(0, nowMs - groupStart);
		long left = Math.max(0, groupEnd - nowMs);

		List<Line> lines = new ArrayList<>();
		outer:
		for (SubtitleTrack.Cue c : cues) {
			int visible = visibleChars(c, nowMs, cfg);
			for (List<SubtitleTrack.Span> textLine : splitLines(c.spans(), visible)) {
				if (textLine.isEmpty()) continue;
				Component colored = component(textLine, cfg, colorFor(c, cfg, textColor), true);
				Component shape = component(textLine, cfg, 0, false);
				List<FormattedCharSequence> wc = font.split(colored, wrapPx);
				List<FormattedCharSequence> ws = font.split(shape, wrapPx);
				for (int i = 0; i < wc.size(); i++) {
					if (lines.size() >= MAX_LINES) break outer;
					FormattedCharSequence s = i < ws.size() ? ws.get(i) : wc.get(i);
					lines.add(new Line(wc.get(i), s, font.width(wc.get(i)), false));
				}
			}
		}
		if (lines.isEmpty()) return EMPTY;

		if (next != null && cfg.nextLinePreview) {
			String firstLine = next.text().split("\n", 2)[0];
			Component p = Component.literal(firstLine).withStyle(st -> st.withItalic(true).withColor(TextColor.fromRgb(0xAAAAAA)));
			List<FormattedCharSequence> wp = font.split(p, wrapPx);
			if (!wp.isEmpty()) {
				FormattedCharSequence seq = wp.getFirst();
				lines.add(new Line(seq, font.split(Component.literal(firstLine).withStyle(st -> st.withItalic(true)), wrapPx).getFirst(),
						font.width(seq), true));
			}
		}

		int width = 0;
		for (Line l : lines) width = Math.max(width, l.width());
		int height = lines.size() * (font.lineHeight + LINE_GAP) - LINE_GAP;

		float alpha = 1f, scale = 1f;
		Animation anim = cfg.animation;
		if (anim == Animation.FADE || anim == Animation.FADE_POP || anim == Animation.TYPEWRITER) {
			alpha = Math.min(1f, Math.min(elapsed / (float) FADE_IN_MS, left / (float) FADE_OUT_MS));
			alpha = Math.max(0.05f, alpha);
		}
		if (anim == Animation.FADE_POP) {
			float t = Math.min(1f, elapsed / (float) POP_MS);
			float ease = 1 - (1 - t) * (1 - t) * (1 - t);
			scale = 0.82f + 0.18f * ease;
		}
		return new Block(lines, width, height, alpha, scale, groupStart);
	}

	/** How many characters of a cue are shown now (karaoke and/or typewriter). */
	private static int visibleChars(SubtitleTrack.Cue c, long nowMs, HearNoDubConfig cfg) {
		String text = c.text();
		int total = text.length();
		long elapsed = Math.max(0, nowMs - c.startMs());
		int visible = total;
		if (cfg.animation == Animation.TYPEWRITER) {
			long typeMs = Math.min((long) (c.durationMs() * 0.6), (long) total * TYPE_MS_PER_CHAR);
			visible = typeMs <= 0 ? total : (int) Math.min(total, Math.ceil(total * (elapsed / (double) typeMs)));
		}
		if (cfg.karaoke) {
			// Words spread evenly over 90% of the line's time
			List<Integer> wordEnds = new ArrayList<>();
			boolean inWord = false;
			for (int i = 0; i < total; i++) {
				boolean ws = Character.isWhitespace(text.charAt(i));
				if (!ws) inWord = true;
				if (ws && inWord) {
					wordEnds.add(i);
					inWord = false;
				}
			}
			if (inWord) wordEnds.add(total);
			if (!wordEnds.isEmpty()) {
				double progress = Math.min(1.0, elapsed / (c.durationMs() * 0.9));
				int words = Math.max(1, (int) Math.ceil(progress * wordEnds.size()));
				visible = Math.min(visible, wordEnds.get(Math.min(words, wordEnds.size()) - 1));
			}
		}
		return Math.max(0, visible);
	}

	/** Cuts spans to the first {@code visible} characters and splits them into text lines. */
	private static List<List<SubtitleTrack.Span>> splitLines(List<SubtitleTrack.Span> spans, int visible) {
		List<List<SubtitleTrack.Span>> out = new ArrayList<>();
		List<SubtitleTrack.Span> cur = new ArrayList<>();
		int used = 0;
		for (SubtitleTrack.Span s : spans) {
			if (used >= visible) break;
			String t = s.text();
			if (used + t.length() > visible) t = t.substring(0, visible - used);
			used += t.length();
			String[] parts = t.split("\n", -1);
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) {
					out.add(cur);
					cur = new ArrayList<>();
				}
				if (!parts[i].isEmpty()) cur.add(new SubtitleTrack.Span(parts[i], s.italic(), s.bold()));
			}
		}
		out.add(cur);
		return out;
	}

	private static Component component(List<SubtitleTrack.Span> spans, HearNoDubConfig cfg, int rgb, boolean withColor) {
		MutableComponent root = Component.empty();
		for (SubtitleTrack.Span s : spans) {
			boolean it = cfg.keepFormatting && s.italic();
			boolean b = cfg.keepFormatting && s.bold();
			MutableComponent part = Component.literal(s.text());
			part = part.withStyle(st -> {
				st = st.withItalic(it).withBold(b);
				return withColor ? st.withColor(TextColor.fromRgb(rgb & 0xFFFFFF)) : st;
			});
			root.append(part);
		}
		return root;
	}

	public static int colorFor(SubtitleTrack.Cue c, HearNoDubConfig cfg, int textColor) {
		if (cfg.speakerColors && c.speaker() != null && !c.speaker().isBlank()) {
			int h = c.speaker().trim().toLowerCase().hashCode();
			return SPEAKER_PALETTE[Math.floorMod(h, SPEAKER_PALETTE.length)];
		}
		return textColor & 0xFFFFFF;
	}
}

package dev.k3ytel.hearnodub.client.subs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads song lyrics in LRC format into cues.
 * <ul>
 *   <li>Plain LRC: {@code [01:23.45]a line}, several timestamps on one line are allowed.</li>
 *   <li>Enhanced LRC (word-synced): {@code [01:23.45]<01:23.45>a <01:23.90>line <01:24.30>},
 *       the word times end up in {@link SubtitleTrack.Cue#words()}.</li>
 *   <li>{@code [offset:+250]} is applied; other tags ({@code [ar:]}, {@code [ti:]}...) are ignored.</li>
 * </ul>
 * A line shows until the next line starts (or an empty timed line, which LRC uses for breaks).
 */
public final class LyricsParser {
	private LyricsParser() {}

	private static final Pattern LINE_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
	private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:\\s*([+-]?\\d+)\\s*]");
	private static final Pattern LRC_START = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]");

	/** Longest a line stays up when the next one is far away (instrumental parts). */
	private static final long MAX_LINE_MS = 9000;
	/** How long the last line stays up. */
	private static final long LAST_LINE_MS = 5000;

	public static boolean looksLikeLrc(String content) {
		Matcher m = LRC_START.matcher(content);
		int n = 0;
		while (m.find() && n < 3) n++;
		return n >= 3;
	}

	private record Raw(long start, String text, int[] words, int[] ends, long wordEnd) {}

	public static List<SubtitleTrack.Cue> parse(String content) {
		long offset = 0;
		Matcher om = OFFSET.matcher(content);
		if (om.find()) {
			try {
				offset = Long.parseLong(om.group(1));
			} catch (NumberFormatException ignored) {
			}
		}
		List<Raw> raws = new ArrayList<>();
		for (String line : content.split("\n")) {
			line = line.strip();
			List<Long> starts = new ArrayList<>();
			int pos = 0;
			Matcher m = LINE_TIME.matcher(line);
			while (m.find(pos) && m.start() == pos) {
				starts.add(time(m.group(1), m.group(2), m.group(3)));
				pos = m.end();
			}
			if (starts.isEmpty()) continue;
			String rest = line.substring(pos);
			for (long start : starts) {
				Raw r = words(start - offset, rest, offset);
				raws.add(r);
			}
		}
		raws.sort((a, b) -> Long.compare(a.start, b.start));

		// Spread-out plain lyrics: each line stays until the next one, however long
		long maxLine = content.contains("[hnd:unsynced]") ? Long.MAX_VALUE / 4 : MAX_LINE_MS;
		List<SubtitleTrack.Cue> cues = new ArrayList<>();
		for (int i = 0; i < raws.size(); i++) {
			Raw r = raws.get(i);
			if (r.text.isEmpty()) continue; // a break marker
			long start = Math.max(0, r.start);
			long next = i + 1 < raws.size() ? raws.get(i + 1).start : Long.MAX_VALUE;
			long end;
			if (next == Long.MAX_VALUE) end = start + Math.max(LAST_LINE_MS, r.wordEnd - start + 800);
			else end = Math.min(next, start + Math.max(maxLine, r.wordEnd - start + 800));
			if (end <= start) end = start + 1500; // two lines at the same time: keep both briefly
			cues.add(new SubtitleTrack.Cue(start, end, r.text, "",
					List.of(new SubtitleTrack.Span(r.text, false, false)), r.words, r.ends));
		}
		return cues;
	}

	/** Strips word tags from a line and records when each word starts. */
	private static Raw words(long lineStart, String rest, long offset) {
		Matcher m = WORD_TIME.matcher(rest);
		if (!m.find()) return new Raw(lineStart, clean(rest), null, null, 0);
		m.reset();
		StringBuilder text = new StringBuilder();
		List<Long> wordTimes = new ArrayList<>();
		long lastTag = -1;
		long wordEnd = 0;
		int pos = 0;
		// Text before the first tag belongs to the line start
		while (m.find()) {
			appendWords(text, wordTimes, rest.substring(pos, m.start()), lastTag < 0 ? lineStart : lastTag);
			lastTag = time(m.group(1), m.group(2), m.group(3)) - offset;
			wordEnd = Math.max(wordEnd, lastTag);
			pos = m.end();
		}
		appendWords(text, wordTimes, rest.substring(pos), lastTag < 0 ? lineStart : lastTag);
		String plain = text.toString().strip().replaceAll("\\s+", " ");
		int count = plain.isEmpty() ? 0 : plain.split(" ").length;
		int[] offsets = null, ends = null;
		if (count > 0 && count == wordTimes.size()) {
			long first = Math.min(lineStart, wordTimes.getFirst());
			offsets = new int[count];
			ends = new int[count];
			for (int i = 0; i < count; i++) offsets[i] = (int) Math.max(0, wordTimes.get(i) - first);
			for (int i = 0; i < count; i++) {
				long e = i + 1 < count ? wordTimes.get(i + 1) : Math.max(wordEnd, wordTimes.get(i) + 400);
				ends[i] = (int) Math.max(offsets[i] + 1, e - first);
			}
			lineStart = first;
		}
		return new Raw(lineStart, plain, offsets, ends, wordEnd);
	}

	/** Adds the words in a piece of text, all starting at {@code at}. Pieces may split a word. */
	private static void appendWords(StringBuilder text, List<Long> times, String piece, long at) {
		if (piece.isEmpty()) return;
		boolean continuing = !text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))
				&& !Character.isWhitespace(piece.charAt(0));
		String[] parts = piece.strip().isEmpty() ? new String[0] : piece.strip().split("\\s+");
		for (int i = 0; i < parts.length; i++) {
			// A tag in the middle of a word ("beau<..>tiful") keeps the word's first time
			if (!(i == 0 && continuing)) times.add(at);
		}
		text.append(piece);
	}

	private static String clean(String s) {
		return s.strip().replaceAll("\\s+", " ");
	}

	private static long time(String m, String s, String frac) {
		long ms = 0;
		if (frac != null) {
			ms = Long.parseLong(frac);
			if (frac.length() == 1) ms *= 100;
			else if (frac.length() == 2) ms *= 10;
		}
		return Long.parseLong(m) * 60_000L + Long.parseLong(s) * 1000L + ms;
	}

	/**
	 * Turns plain (unsynced) lyrics into LRC, spreading the lines over the song by length.
	 * The first and last few seconds are left empty for the intro and outro.
	 */
	public static String spreadPlain(String plain, long durationMs) {
		String[] lines = plain.replace("\r", "").split("\n");
		if (durationMs <= 0) durationMs = 200_000;
		long from = Math.min(10_000, durationMs / 12);
		long to = durationMs - Math.min(10_000, durationMs / 15);
		double total = 0;
		for (String l : lines) total += weight(l);
		if (total <= 0) return "";
		StringBuilder out = new StringBuilder("[hnd:unsynced]\n");
		double acc = 0;
		for (String l : lines) {
			long t = from + Math.round((to - from) * (acc / total));
			acc += weight(l);
			String text = l.strip();
			out.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]", t / 60_000, (t / 1000) % 60, (t % 1000) / 10))
					.append(text).append('\n');
		}
		return out.toString();
	}

	/** Longer lines take longer to sing; a blank line (verse break) is a short pause. */
	private static double weight(String line) {
		String t = line.strip();
		return t.isEmpty() ? 6 : 12 + t.length();
	}
}

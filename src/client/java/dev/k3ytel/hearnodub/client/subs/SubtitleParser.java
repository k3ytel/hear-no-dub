package dev.k3ytel.hearnodub.client.subs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads .srt, .vtt, .ass/.ssa subtitles and .lrc lyrics into a {@link SubtitleTrack}.
 * Italics and bold are kept; other styling is dropped. For .ass, sign/typesetting lines
 * are skipped so only dialogue shows, and the "Name" field becomes the speaker.
 */
public final class SubtitleParser {
	private SubtitleParser() {}

	private static final Pattern SRT_TIME = Pattern.compile(
			"(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})");
	/** "ASTA: Let's go!" style speaker labels in SRT (common in hearing-impaired subs). */
	private static final Pattern SRT_SPEAKER = Pattern.compile("^([A-Z][A-Z .'-]{1,20}):\\s");

	public static SubtitleTrack parse(String content) {
		if (content.startsWith("﻿")) content = content.substring(1);
		content = content.replace("\r\n", "\n").replace('\r', '\n');
		String lower = content.toLowerCase(Locale.ROOT);
		if (lower.contains("[events]") && lower.contains("dialogue:")) {
			return new SubtitleTrack(parseAss(content));
		}
		if (TtmlParser.looksLikeTtml(content)) {
			return new SubtitleTrack(TtmlParser.parse(content));
		}
		if (!content.contains("-->") && LyricsParser.looksLikeLrc(content)) {
			return new SubtitleTrack(LyricsParser.parse(content));
		}
		return new SubtitleTrack(parseSrt(content));
	}

	// ---------------------------------------------------------------- SRT / VTT

	private static List<SubtitleTrack.Cue> parseSrt(String content) {
		List<SubtitleTrack.Cue> cues = new ArrayList<>();
		String[] lines = content.split("\n");
		int i = 0;
		while (i < lines.length) {
			Matcher m = SRT_TIME.matcher(lines[i]);
			if (!m.find()) {
				i++;
				continue;
			}
			long start = time(m.group(1), m.group(2), m.group(3), m.group(4));
			long end = time(m.group(5), m.group(6), m.group(7), m.group(8));
			i++;
			StringBuilder text = new StringBuilder();
			while (i < lines.length && !lines[i].isBlank()) {
				if (SRT_TIME.matcher(lines[i]).find()) break;
				if (!text.isEmpty()) text.append('\n');
				text.append(lines[i].trim());
				i++;
			}
			List<SubtitleTrack.Span> spans = markup(text.toString());
			String plain = plain(spans);
			if (plain.isEmpty() || end <= start) continue;
			Matcher sp = SRT_SPEAKER.matcher(plain);
			String speaker = sp.find() ? sp.group(1).trim() : "";
			cues.add(new SubtitleTrack.Cue(start, end, plain, speaker, spans));
		}
		return cues;
	}

	private static long time(String h, String m, String s, String frac) {
		long ms = Long.parseLong(frac);
		if (frac.length() == 1) ms *= 100;
		else if (frac.length() == 2) ms *= 10;
		return (h == null ? 0 : Long.parseLong(h)) * 3_600_000L
				+ Long.parseLong(m) * 60_000L
				+ Long.parseLong(s) * 1000L
				+ ms;
	}

	// ---------------------------------------------------------------- ASS / SSA

	private static List<SubtitleTrack.Cue> parseAss(String content) {
		List<SubtitleTrack.Cue> cues = new ArrayList<>();
		boolean inEvents = false;
		List<String> format = Arrays.asList("layer", "start", "end", "style", "name",
				"marginl", "marginr", "marginv", "effect", "text");
		for (String raw : content.split("\n")) {
			String line = raw.trim();
			if (line.startsWith("[")) {
				inEvents = line.equalsIgnoreCase("[events]");
				continue;
			}
			if (!inEvents) continue;
			if (line.regionMatches(true, 0, "Format:", 0, 7)) {
				List<String> f = new ArrayList<>();
				for (String part : line.substring(7).split(",")) f.add(part.trim().toLowerCase(Locale.ROOT));
				format = f;
				continue;
			}
			if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)) continue;

			String[] parts = line.substring(9).split(",", format.size());
			if (parts.length < format.size()) continue;
			String start = field(parts, format, "start");
			String end = field(parts, format, "end");
			String style = field(parts, format, "style");
			String name = field(parts, format, "name");
			String text = field(parts, format, "text");
			if (start == null || end == null || text == null) continue;
			if (isSign(style, text)) continue;

			List<SubtitleTrack.Span> spans = markup(text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "));
			String plain = plain(spans);
			long s = assTime(start), e = assTime(end);
			if (!plain.isEmpty() && s >= 0 && e > s) {
				cues.add(new SubtitleTrack.Cue(s, e, plain, name == null ? "" : name.trim(), spans));
			}
		}
		return cues;
	}

	private static String field(String[] parts, List<String> format, String name) {
		int i = format.indexOf(name);
		return i >= 0 && i < parts.length ? parts[i].trim() : null;
	}

	/** Typesetting (signs, positioned text, vector drawings) isn't dialogue. */
	private static boolean isSign(String style, String text) {
		String s = style == null ? "" : style.toLowerCase(Locale.ROOT);
		if (s.contains("sign") || s.contains("typeset") || s.contains("title") || s.equals("ts")) return true;
		return text.contains("\\pos(") || text.contains("\\move(") || text.matches(".*\\\\p[1-9].*");
	}

	/** H:MM:SS.cc → ms, or -1 if malformed. */
	private static long assTime(String t) {
		try {
			String[] hms = t.split(":");
			if (hms.length != 3) return -1;
			String[] sec = hms[2].split("\\.");
			long ms = sec.length > 1 ? Long.parseLong((sec[1] + "00").substring(0, 3)) : 0;
			return Long.parseLong(hms[0]) * 3_600_000L + Long.parseLong(hms[1]) * 60_000L
					+ Long.parseLong(sec[0]) * 1000L + ms;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// ---------------------------------------------------------------- markup

	/**
	 * Splits text into spans by &lt;i&gt;/&lt;b&gt; tags and {\i1}/{\b1} override blocks.
	 * Every other tag or override is dropped. Lines are trimmed and empty lines removed.
	 */
	static List<SubtitleTrack.Span> markup(String raw) {
		List<SubtitleTrack.Span> spans = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean italic = false, bold = false;
		int i = 0;
		while (i < raw.length()) {
			char c = raw.charAt(i);
			if (c == '<') {
				int close = raw.indexOf('>', i);
				if (close > i) {
					String tag = raw.substring(i + 1, close).trim().toLowerCase(Locale.ROOT);
					boolean ni = italic, nb = bold;
					if (tag.equals("i")) ni = true;
					else if (tag.equals("/i")) ni = false;
					else if (tag.equals("b")) nb = true;
					else if (tag.equals("/b")) nb = false;
					if (ni != italic || nb != bold) {
						flush(spans, cur, italic, bold);
						italic = ni;
						bold = nb;
					}
					i = close + 1;
					continue;
				}
			}
			if (c == '{') {
				int close = raw.indexOf('}', i);
				if (close > i) {
					String block = raw.substring(i + 1, close);
					boolean ni = italic, nb = bold;
					for (String o : block.split("\\\\")) {
						if (o.equals("i1")) ni = true;
						else if (o.equals("i0") || o.equals("i")) ni = false;
						else if (o.equals("b1") || o.matches("b[5-9]00")) nb = true;
						else if (o.equals("b0") || o.equals("b")) nb = false;
						else if (o.equals("r")) {
							ni = false;
							nb = false;
						}
					}
					if (ni != italic || nb != bold) {
						flush(spans, cur, italic, bold);
						italic = ni;
						bold = nb;
					}
					i = close + 1;
					continue;
				}
			}
			cur.append(c);
			i++;
		}
		flush(spans, cur, italic, bold);
		return tidy(spans);
	}

	private static void flush(List<SubtitleTrack.Span> spans, StringBuilder cur, boolean italic, boolean bold) {
		if (cur.isEmpty()) return;
		String t = cur.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
		spans.add(new SubtitleTrack.Span(t, italic, bold));
		cur.setLength(0);
	}

	/** Trims each text line and drops empty ones, keeping the formatting boundaries. */
	private static List<SubtitleTrack.Span> tidy(List<SubtitleTrack.Span> spans) {
		// Work on the joined text with a per-character style map
		StringBuilder text = new StringBuilder();
		List<int[]> styles = new ArrayList<>(); // {italic, bold} per char
		for (SubtitleTrack.Span s : spans) {
			for (int k = 0; k < s.text().length(); k++) {
				text.append(s.text().charAt(k));
				styles.add(new int[]{s.italic() ? 1 : 0, s.bold() ? 1 : 0});
			}
		}
		StringBuilder outText = new StringBuilder();
		List<int[]> outStyles = new ArrayList<>();
		int lineStart = 0;
		String all = text.toString();
		while (lineStart <= all.length()) {
			int nl = all.indexOf('\n', lineStart);
			int lineEnd = nl < 0 ? all.length() : nl;
			int a = lineStart, b = lineEnd;
			while (a < b && Character.isWhitespace(all.charAt(a))) a++;
			while (b > a && Character.isWhitespace(all.charAt(b - 1))) b--;
			if (b > a) {
				if (!outText.isEmpty()) {
					outText.append('\n');
					outStyles.add(new int[]{0, 0});
				}
				for (int k = a; k < b; k++) {
					outText.append(all.charAt(k));
					outStyles.add(styles.get(k));
				}
			}
			if (nl < 0) break;
			lineStart = nl + 1;
		}
		List<SubtitleTrack.Span> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int ci = -1, cb = -1;
		for (int k = 0; k < outText.length(); k++) {
			char ch = outText.charAt(k);
			if (ch == '\n') {
				// line breaks get their own plain span
				if (!cur.isEmpty()) out.add(new SubtitleTrack.Span(cur.toString(), ci == 1, cb == 1));
				cur.setLength(0);
				out.add(new SubtitleTrack.Span("\n", false, false));
				continue;
			}
			int[] st = outStyles.get(k);
			if (!cur.isEmpty() && (st[0] != ci || st[1] != cb)) {
				out.add(new SubtitleTrack.Span(cur.toString(), ci == 1, cb == 1));
				cur.setLength(0);
			}
			if (cur.isEmpty()) {
				ci = st[0];
				cb = st[1];
			}
			cur.append(ch);
		}
		if (!cur.isEmpty()) out.add(new SubtitleTrack.Span(cur.toString(), ci == 1, cb == 1));
		return out;
	}

	static String plain(List<SubtitleTrack.Span> spans) {
		StringBuilder sb = new StringBuilder();
		for (SubtitleTrack.Span s : spans) sb.append(s.text());
		return sb.toString();
	}
}

package dev.k3ytel.hearnodub.client.subs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads .srt, .vtt and .ass/.ssa subtitles into a {@link SubtitleTrack}.
 * Styling is stripped; for .ass, sign/typesetting lines are skipped so only dialogue shows.
 */
public final class SubtitleParser {
	private SubtitleParser() {}

	private static final Pattern SRT_TIME = Pattern.compile(
			"(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})");
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
	private static final Pattern ASS_TAG = Pattern.compile("\\{[^}]*}");

	public static SubtitleTrack parse(String content) {
		if (content.startsWith("﻿")) content = content.substring(1);
		content = content.replace("\r\n", "\n").replace('\r', '\n');
		String lower = content.toLowerCase(Locale.ROOT);
		if (lower.contains("[events]") && lower.contains("dialogue:")) {
			return new SubtitleTrack(parseAss(content));
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
			String clean = cleanText(text.toString());
			if (!clean.isEmpty() && end > start) cues.add(new SubtitleTrack.Cue(start, end, clean));
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
			String text = field(parts, format, "text");
			if (start == null || end == null || text == null) continue;
			if (isSign(style, text)) continue;

			String clean = cleanText(ASS_TAG.matcher(text).replaceAll("")
					.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "));
			long s = assTime(start), e = assTime(end);
			if (!clean.isEmpty() && s >= 0 && e > s) cues.add(new SubtitleTrack.Cue(s, e, clean));
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

	// ---------------------------------------------------------------- shared

	private static String cleanText(String text) {
		String t = HTML_TAG.matcher(text).replaceAll("");
		t = ASS_TAG.matcher(t).replaceAll("");
		t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
		StringBuilder out = new StringBuilder();
		for (String line : t.split("\n")) {
			String l = line.strip();
			if (l.isEmpty()) continue;
			if (!out.isEmpty()) out.append('\n');
			out.append(l);
		}
		return out.toString();
	}
}

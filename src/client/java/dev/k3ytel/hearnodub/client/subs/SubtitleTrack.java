package dev.k3ytel.hearnodub.client.subs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A parsed subtitle file: cues sorted by start time, with fast lookup by time. */
public final class SubtitleTrack {
	/** A piece of text with its formatting. Newlines are kept inside the text. */
	public record Span(String text, boolean italic, boolean bold) {}

	/**
	 * One subtitle line (may hold several text lines).
	 *
	 * @param text    plain text, lines separated by '\n'
	 * @param speaker who's talking ("" when the file doesn't say)
	 * @param spans   the same text split by formatting (italic/bold)
	 * @param words   when each word starts, in ms after startMs (one per whitespace-separated
	 *                word of text), from word-synced lyrics; null when unknown
	 * @param wordEnds when each word ends, in ms after startMs; null when unknown
	 */
	public record Cue(long startMs, long endMs, String text, String speaker, List<Span> spans, int[] words, int[] wordEnds) {
		/** Speaker name used for background vocals / ad-libs in lyrics. */
		public static final String BACKGROUND = "(bg)";

		public Cue(long startMs, long endMs, String text, String speaker, List<Span> spans) {
			this(startMs, endMs, text, speaker, spans, null, null);
		}

		public Cue(long startMs, long endMs, String text, String speaker, List<Span> spans, int[] words) {
			this(startMs, endMs, text, speaker, spans, words, null);
		}

		public Cue(long startMs, long endMs, String text) {
			this(startMs, endMs, text, "", List.of(new Span(text, false, false)));
		}

		public boolean hasWordTimes() {
			return words != null && words.length > 0;
		}

		/** When word i stops being sung, in ms after startMs. */
		public int wordEnd(int i) {
			if (wordEnds != null && i < wordEnds.length) return wordEnds[i];
			if (words != null && i + 1 < words.length) return words[i + 1];
			return (int) durationMs();
		}

		/** Background vocals / ad-libs (from TTML lyrics). */
		public boolean isBackground() {
			return BACKGROUND.equals(speaker);
		}

		public long durationMs() {
			return Math.max(1, endMs - startMs);
		}
	}

	private final List<Cue> cues;
	/** Longest cue duration, so a lookup knows how far back an overlapping cue can start. */
	private final long longestMs;

	public SubtitleTrack(List<Cue> cues) {
		List<Cue> sorted = new ArrayList<>(cues);
		sorted.sort(Comparator.comparingLong(Cue::startMs));
		this.cues = List.copyOf(sorted);
		long longest = 0;
		for (Cue c : sorted) longest = Math.max(longest, c.endMs() - c.startMs());
		this.longestMs = longest;
	}

	/** All cues, in start order. */
	public List<Cue> all() {
		return cues;
	}

	public int size() {
		return cues.size();
	}

	public boolean isEmpty() {
		return cues.isEmpty();
	}

	/** The first cue starting after timeMs, or null. */
	public Cue next(long timeMs) {
		int lo = 0, hi = cues.size() - 1, ans = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (cues.get(mid).startMs() > timeMs) {
				ans = mid;
				hi = mid - 1;
			} else {
				lo = mid + 1;
			}
		}
		return ans < 0 ? null : cues.get(ans);
	}

	/** The last n cues that started at or before timeMs, oldest first. */
	public List<Cue> recent(long timeMs, int n) {
		List<Cue> out = new ArrayList<>();
		for (int i = cues.size() - 1; i >= 0 && out.size() < n; i--) {
			if (cues.get(i).startMs() <= timeMs) out.add(0, cues.get(i));
		}
		return out;
	}

	/** Cues starting between fromMs and toMs, in start order. */
	public List<Cue> between(long fromMs, long toMs) {
		List<Cue> out = new ArrayList<>();
		for (Cue c : cues) {
			if (c.startMs() > toMs) break;
			if (c.startMs() >= fromMs) out.add(c);
		}
		return out;
	}

	/** All cues visible at the given time, in start order. */
	public List<Cue> activeAt(long timeMs) {
		List<Cue> out = new ArrayList<>(3);
		// last cue that starts at or before timeMs
		int lo = 0, hi = cues.size() - 1, idx = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (cues.get(mid).startMs() <= timeMs) {
				idx = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		for (int i = idx; i >= 0; i--) {
			Cue c = cues.get(i);
			if (timeMs - c.startMs() > longestMs) break;
			if (c.endMs() > timeMs) out.add(0, c);
		}
		return out;
	}
}

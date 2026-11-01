package dev.k3ytel.hearnodub.client.subs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A parsed subtitle file: cues sorted by start time, with fast lookup by time. */
public final class SubtitleTrack {
	public record Cue(long startMs, long endMs, String text) {}

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

	public int size() {
		return cues.size();
	}

	public boolean isEmpty() {
		return cues.isEmpty();
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

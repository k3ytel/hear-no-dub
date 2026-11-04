package dev.k3ytel.hearnodub.client.subs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the chorus of a song: lines that come back several times. A line counts when it
 * appears at least three times, or twice with a neighbouring line that also repeats (choruses
 * come in blocks). Worked out once per track.
 */
public final class Chorus {
	private Chorus() {}

	private static List<SubtitleTrack.Cue> cachedFor;
	private static Set<Long> chorus = Set.of();

	public static boolean isChorus(List<SubtitleTrack.Cue> all, SubtitleTrack.Cue c) {
		if (c == null || all == null || all.isEmpty()) return false;
		if (all != cachedFor) {
			cachedFor = all;
			chorus = find(all);
		}
		return chorus.contains(c.startMs());
	}

	/** True when any of these cues is part of a chorus. */
	public static boolean anyChorus(List<SubtitleTrack.Cue> all, List<SubtitleTrack.Cue> cues) {
		for (SubtitleTrack.Cue c : cues) if (isChorus(all, c)) return true;
		return false;
	}

	private static Set<Long> find(List<SubtitleTrack.Cue> all) {
		List<SubtitleTrack.Cue> main = new ArrayList<>();
		for (SubtitleTrack.Cue c : all) if (!c.isBackground()) main.add(c);
		Map<String, Integer> count = new HashMap<>();
		String[] keys = new String[main.size()];
		for (int i = 0; i < main.size(); i++) {
			keys[i] = normalize(main.get(i).text());
			if (keys[i].length() >= 6) count.merge(keys[i], 1, Integer::sum);
		}
		Set<Long> out = new HashSet<>();
		for (int i = 0; i < main.size(); i++) {
			int n = count.getOrDefault(keys[i], 0);
			if (n < 2) continue;
			boolean neighbour = i > 0 && count.getOrDefault(keys[i - 1], 0) >= 2
					|| i + 1 < main.size() && count.getOrDefault(keys[i + 1], 0) >= 2;
			if (n >= 3 || neighbour) out.add(main.get(i).startMs());
		}
		return out;
	}

	private static String normalize(String s) {
		StringBuilder b = new StringBuilder();
		for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
			if (Character.isLetterOrDigit(ch)) b.append(ch);
		}
		return b.toString();
	}
}

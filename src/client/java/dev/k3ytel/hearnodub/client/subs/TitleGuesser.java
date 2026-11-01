package dev.k3ytel.hearnodub.client.subs;

import dev.k3ytel.hearnodub.client.media.MediaBridge;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guesses show, season and episode from the media title and the browser tab title.
 * Sites like Miruro only give Windows the episode name, so the tab title is often
 * where the show name comes from.
 */
public final class TitleGuesser {
	private TitleGuesser() {}

	/** What we think is playing. Season/episode are 0 when unknown. */
	public record Guess(String query, int season, int episode, String tabTitle) {
		public String describe() {
			StringBuilder sb = new StringBuilder(query);
			if (season > 0 || episode > 0) {
				sb.append(' ');
				if (season > 0) sb.append('S').append(season);
				if (episode > 0) sb.append('E').append(episode);
			}
			return sb.toString();
		}
	}

	private static final Pattern BROWSER_SUFFIX = Pattern.compile(
			"\\s*(?:and \\d+ more pages?\\s*)?(?:[-—–]\\s*(?:Personal|Work|Profile \\d+)\\s*)?"
					+ "[-—–]\\s*(?:Mozilla Firefox.*|Google Chrome|Microsoft\\W*Edge|Brave|Opera|Vivaldi)\\s*$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SITE_SUFFIX = Pattern.compile(
			"\\s*[|\\-—–·•]\\s*(?:Miruro|Crunchyroll|YouTube|HiAnime|Zoro|9anime|AniWatch|Netflix|Gogoanime|AnimePahe|Aniwave)[^|\\-—–]*$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SXXEYY = Pattern.compile("\\bS(\\d{1,2})\\s*E(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEASON = Pattern.compile("\\b(?:Season|Staffel|Saison)\\s*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern EPISODE = Pattern.compile("\\b(?:Episode|Ep\\.?|E)\\s*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern NOISE = Pattern.compile(
			"\\b(?:watch|online|free|english|sub(?:bed)?|dub(?:bed)?|in hd|hd|full)\\b|[()\\[\\]]",
			Pattern.CASE_INSENSITIVE);

	public static Guess guess(MediaBridge.Session session, List<MediaBridge.Window> windows) {
		String media = session.title == null ? "" : session.title.trim();
		String tab = pickTab(media, windows);
		String cleanTab = tab == null ? "" : stripSuffixes(tab);

		int season = 0, episode = 0;
		String showPart = null;

		for (String source : new String[]{cleanTab, media}) {
			if (source.isEmpty()) continue;
			Matcher m = SXXEYY.matcher(source);
			if (m.find()) {
				season = Integer.parseInt(m.group(1));
				episode = Integer.parseInt(m.group(2));
				showPart = source.substring(0, m.start());
				break;
			}
			Matcher ep = EPISODE.matcher(source);
			if (ep.find()) {
				episode = Integer.parseInt(ep.group(1));
				showPart = source.substring(0, ep.start());
				Matcher se = SEASON.matcher(source);
				if (se.find()) {
					season = Integer.parseInt(se.group(1));
					if (se.start() < ep.start()) showPart = source.substring(0, se.start());
				}
				break;
			}
		}

		String query = showPart != null ? tidy(showPart) : "";
		if (query.isEmpty()) {
			// No episode marker: use the tab title (minus the episode name) or the media title
			String t = cleanTab;
			if (!media.isEmpty() && t.toLowerCase(Locale.ROOT).contains(media.toLowerCase(Locale.ROOT))) {
				t = t.replace(media, " ");
			}
			query = tidy(t);
			if (query.isEmpty()) query = tidy(media);
			if (query.isEmpty()) query = media;
		}
		return new Guess(query, season, episode, tab == null ? "" : tab);
	}

	/** The browser tab most likely playing the video: one that contains the media title, else the first. */
	private static String pickTab(String media, List<MediaBridge.Window> windows) {
		if (windows == null || windows.isEmpty()) return null;
		if (!media.isEmpty()) {
			String m = media.toLowerCase(Locale.ROOT);
			for (MediaBridge.Window w : windows) {
				if (w.title != null && w.title.toLowerCase(Locale.ROOT).contains(m)) return w.title;
			}
		}
		return windows.getFirst().title;
	}

	public static String stripSuffixes(String title) {
		String t = BROWSER_SUFFIX.matcher(title).replaceAll("");
		String prev;
		do {
			prev = t;
			t = SITE_SUFFIX.matcher(t).replaceAll("");
		} while (!t.equals(prev));
		return t.trim();
	}

	private static String tidy(String s) {
		String t = NOISE.matcher(s).replaceAll(" ");
		t = t.replaceAll("[|\\-—–:·•,]+\\s*$", "").replaceAll("^\\s*[|\\-—–:·•,]+", "");
		return t.replaceAll("\\s+", " ").trim();
	}
}

package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.k3ytel.hearnodub.client.HearNoDubClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Looks shows up on TVmaze (free, no key) to get the show's IMDb ID and its episode list.
 * That turns "Attack on Titan" + episode name "To You, in 2000 Years..." into S1E1,
 * which is what OpenSubtitles needs to find the right file.
 */
public final class EpisodeLookup {
	private static final String BASE = "https://api.tvmaze.com";

	public record Episode(int season, int number, String name) {}

	public record Show(String name, String imdbId, List<Episode> episodes) {
		/** IMDb ID without the "tt" prefix, as OpenSubtitles wants it, or 0. */
		public long imdbNumber() {
			if (imdbId == null || imdbId.isBlank()) return 0;
			try {
				return Long.parseLong(imdbId.replaceAll("\\D", ""));
			} catch (NumberFormatException e) {
				return 0;
			}
		}
	}

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final Map<String, Show> cache = new HashMap<>();

	/** Finds the show, trying shorter versions of long light-novel titles. Null if not found. */
	public synchronized Show findShow(String query) {
		String key = query.toLowerCase(Locale.ROOT).trim();
		if (cache.containsKey(key)) return cache.get(key);

		Show show = null;
		for (String attempt : attempts(query)) {
			try {
				show = fetch(attempt);
			} catch (Exception e) {
				HearNoDubClient.LOGGER.warn("TVmaze lookup for '{}' failed: {}", attempt, e.toString());
			}
			if (show != null) break;
		}
		cache.put(key, show);
		HearNoDubClient.LOGGER.info("TVmaze: '{}' -> {}", query,
				show == null ? "not found" : show.name() + " (" + show.imdbId() + ", " + show.episodes().size() + " episodes)");
		return show;
	}

	/** Full name, then the part before ':' / ',' / '-' (for very long titles). */
	private static List<String> attempts(String query) {
		List<String> out = new ArrayList<>();
		String q = query.trim();
		out.add(q);
		for (String sep : new String[]{":", ",", " - ", "!", "?"}) {
			int i = q.indexOf(sep);
			if (i > 2) {
				String shorter = q.substring(0, i).trim();
				if (!out.contains(shorter)) out.add(shorter);
			}
		}
		return out;
	}

	private Show fetch(String query) throws IOException, InterruptedException {
		String url = BASE + "/singlesearch/shows?embed=episodes&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
		HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", Http.USER_AGENT)
				.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() == 404) return null;
		if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());

		JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
		String name = json.has("name") ? json.get("name").getAsString() : query;
		String imdb = null;
		if (json.has("externals") && json.get("externals").isJsonObject()) {
			JsonElement e = json.getAsJsonObject("externals").get("imdb");
			if (e != null && !e.isJsonNull()) imdb = e.getAsString();
		}
		List<Episode> episodes = new ArrayList<>();
		if (json.has("_embedded")) {
			JsonArray eps = json.getAsJsonObject("_embedded").getAsJsonArray("episodes");
			if (eps != null) {
				for (JsonElement el : eps) {
					JsonObject ep = el.getAsJsonObject();
					if (ep.get("season") == null || ep.get("season").isJsonNull()
							|| ep.get("number") == null || ep.get("number").isJsonNull()) continue;
					String epName = ep.get("name") == null || ep.get("name").isJsonNull() ? "" : ep.get("name").getAsString();
					episodes.add(new Episode(ep.get("season").getAsInt(), ep.get("number").getAsInt(), epName));
				}
			}
		}
		return new Show(name, imdb, episodes);
	}

	/** The episode whose name best matches the media title, or null if nothing is close. */
	public static Episode matchEpisode(Show show, String episodeTitle) {
		if (show == null || episodeTitle == null || episodeTitle.isBlank()) return null;
		String target = norm(episodeTitle);
		if (target.isEmpty()) return null;
		Set<String> targetWords = words(target);

		Episode best = null;
		double bestScore = 0;
		for (Episode ep : show.episodes()) {
			String n = norm(ep.name());
			if (n.isEmpty()) continue;
			double score;
			if (n.equals(target)) {
				score = 1.0;
			} else if (Math.min(n.length(), target.length()) >= 6 && (n.contains(target) || target.contains(n))) {
				score = 0.9;
			} else {
				score = dice(targetWords, words(n));
			}
			if (score > bestScore) {
				bestScore = score;
				best = ep;
			}
		}
		return bestScore >= 0.7 ? best : null;
	}

	/** 0..1 word-overlap similarity of two titles (ignores case, punctuation, one-letter typos). */
	public static double similarity(String a, String b) {
		if (a == null || b == null) return 0;
		Set<String> wa = words(norm(a)), wb = words(norm(b));
		// Compare against the shorter title's words so "Show S01E05 WEB" still matches "Show"
		if (wa.size() > wb.size()) {
			Set<String> t = wa;
			wa = wb;
			wb = t;
		}
		if (wa.isEmpty()) return 0;
		int common = 0;
		for (String w : wa) {
			if (wb.contains(w)) common++;
			else if (w.length() >= 4) for (String v : wb) if (v.length() >= 4 && withinOneEdit(w, v)) { common++; break; }
		}
		return common / (double) wa.size();
	}

	private static String norm(String s) {
		return s.toLowerCase(Locale.ROOT)
				.replace('’', '\'')
				.replaceAll("[^\\p{L}\\p{N}]+", " ")
				.trim();
	}

	private static Set<String> words(String normalized) {
		Set<String> out = new HashSet<>();
		for (String w : normalized.split(" ")) if (!w.isEmpty()) out.add(w);
		return out;
	}

	/** Word overlap; words count as equal when they differ by one letter (romanization: Ryoumen/Ryomen). */
	private static double dice(Set<String> a, Set<String> b) {
		if (a.isEmpty() || b.isEmpty()) return 0;
		int common = 0;
		for (String w : a) {
			if (b.contains(w)) {
				common++;
				continue;
			}
			if (w.length() < 4) continue;
			for (String v : b) {
				if (v.length() >= 4 && withinOneEdit(w, v)) {
					common++;
					break;
				}
			}
		}
		return 2.0 * common / (a.size() + b.size());
	}

	private static boolean withinOneEdit(String a, String b) {
		int la = a.length(), lb = b.length();
		if (Math.abs(la - lb) > 1) return false;
		int i = 0, j = 0, edits = 0;
		while (i < la && j < lb) {
			if (a.charAt(i) == b.charAt(j)) {
				i++;
				j++;
				continue;
			}
			if (++edits > 1) return false;
			if (la > lb) i++;
			else if (lb > la) j++;
			else {
				i++;
				j++;
			}
		}
		return edits + (la - i) + (lb - j) <= 1;
	}
}

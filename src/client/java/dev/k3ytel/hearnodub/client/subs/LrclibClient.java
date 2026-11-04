package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.k3ytel.hearnodub.client.HearNoDubClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LRCLIB (lrclib.net): a free, open lyrics database with no API key. Most songs have
 * line-synced lyrics, a few have word-synced (enhanced LRC) ones, and many more have
 * plain lyrics, which get spread over the song and marked "not synced".
 */
public final class LrclibClient {
	private static final String API = "https://lrclib.net/api";

	/** A lyrics record found for a song. */
	public record Song(long id, String track, String artist, String album, double durationS,
			boolean instrumental, boolean synced, boolean plain, boolean wordSynced) {}

	/** " - Remastered 2011", " (feat. X)", " - Radio Edit"... which LRCLIB names usually leave out. */
	private static final Pattern TITLE_JUNK = Pattern.compile(
			"(?i)\\s*(?:-\\s*(?:\\d{4}\\s+)?(?:remaster(?:ed)?|radio edit|single version|live|mono|stereo|bonus track|explicit)[^-]*$"
					+ "|[(\\[](?:feat\\.?|ft\\.?|with)\\s[^)\\]]*[)\\]])");

	public static String cleanTitle(String title) {
		if (title == null) return "";
		String t = title;
		for (int i = 0; i < 3; i++) t = TITLE_JUNK.matcher(t).replaceAll("");
		return t.strip();
	}

	/** First artist of "A, B & C" or "A feat. B". */
	public static String mainArtist(String artist) {
		if (artist == null) return "";
		return artist.split("(?i),|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|;")[0].strip();
	}

	/**
	 * Finds lyrics for a song, best match first. Tries an exact lookup with the duration, then
	 * searches by track and artist, then a free-text search.
	 */
	public List<Candidate> search(String title, String artist, String album, long durationMs)
			throws IOException, InterruptedException {
		String track = cleanTitle(title);
		String main = mainArtist(artist);
		Map<Long, Song> found = new LinkedHashMap<>();

		if (!track.isEmpty() && !main.isEmpty() && durationMs > 0) {
			Map<String, String> p = new LinkedHashMap<>();
			p.put("track_name", track);
			p.put("artist_name", main);
			if (album != null && !album.isBlank()) p.put("album_name", album);
			p.put("duration", String.valueOf(Math.round(durationMs / 1000.0)));
			JsonElement el = get("/get?" + query(p));
			if (el != null && el.isJsonObject()) add(found, el.getAsJsonObject());
		}
		if (!track.isEmpty()) {
			Map<String, String> p = new LinkedHashMap<>();
			p.put("track_name", track);
			if (!main.isEmpty()) p.put("artist_name", main);
			addAll(found, get("/search?" + query(p)));
		}
		if (found.size() < 3) {
			Map<String, String> p = new LinkedHashMap<>();
			p.put("q", (track + " " + main).strip());
			addAll(found, get("/search?" + query(p)));
		}

		List<Song> songs = new ArrayList<>(found.values());
		songs.sort((a, b) -> Integer.compare(score(b, track, main, durationMs), score(a, track, main, durationMs)));
		List<Candidate> out = new ArrayList<>();
		for (Song s : songs) {
			if (s.instrumental) {
				out.add(candidate(s, "instr", "instrumental", durationMs));
				continue;
			}
			if (s.synced) out.add(candidate(s, "sync", s.wordSynced ? "word-synced" : "synced", durationMs));
			if (s.plain) out.add(candidate(s, "plain", "not synced", durationMs));
		}
		// Synced versions of good matches before plain ones
		out.sort((a, b) -> Integer.compare(rank(b, songs, track, main, durationMs), rank(a, songs, track, main, durationMs)));
		HearNoDubClient.LOGGER.info("LRCLIB '{}' by '{}' ({}s) -> {} songs", track, main, durationMs / 1000, songs.size());
		return out;
	}

	private static Candidate candidate(Song s, String kind, String tag, long durationMs) {
		String label = s.artist + " - " + s.track + "  (" + tag + ")";
		long diff = durationMs > 0 ? Math.round(Math.abs(s.durationS * 1000 - durationMs) / 1000.0) : -1;
		String title = s.album == null || s.album.isBlank() ? s.track : s.album;
		if (diff >= 0) title += diff <= 2 ? "  [length matches]" : "  [" + diff + "s off]";
		return new Candidate("lrclib", s.id + "-" + kind, label, title, 0, 0, 0, false, "");
	}

	private static int rank(Candidate c, List<Song> songs, String track, String artist, long durationMs) {
		long id = Long.parseLong(c.id().substring(0, c.id().indexOf('-')));
		int base = 0;
		for (Song s : songs) if (s.id == id) base = score(s, track, artist, durationMs);
		if (c.id().endsWith("-sync")) base += 400;
		if (c.id().endsWith("-instr")) base -= 300;
		return base;
	}

	/** How well a record matches: duration matters most (it tells versions apart), then names. */
	static int score(Song s, String track, String artist, long durationMs) {
		int score = 0;
		if (durationMs > 0) {
			double diff = Math.abs(s.durationS * 1000 - durationMs) / 1000.0;
			if (diff <= 2) score += 1000;
			else if (diff <= 5) score += 600;
			else if (diff <= 15) score += 100;
			else score -= 800;
		}
		score += (int) (400 * EpisodeLookup.similarity(s.track, track));
		score += (int) (400 * EpisodeLookup.similarity(s.artist, artist));
		if (s.synced) score += 50;
		if (s.wordSynced) score += 30;
		return score;
	}

	/** True when this candidate is word-synced or line-synced lyrics with a matching length. */
	public static boolean isConfident(Candidate c) {
		return c.title().contains("[length matches]") && !c.id().endsWith("-instr");
	}

	public static boolean isPlain(Candidate c) {
		return c.id().endsWith("-plain");
	}

	public static boolean isInstrumental(Candidate c) {
		return c.id().endsWith("-instr");
	}

	/** Downloads the lyrics as LRC text (plain lyrics are spread over the song). */
	public String download(Candidate c, long durationMs) throws IOException, InterruptedException {
		String id = c.id().substring(0, c.id().indexOf('-'));
		JsonElement el = get("/get/" + id);
		if (el == null || !el.isJsonObject()) throw new IOException("LRCLIB: lyrics not found");
		JsonObject o = el.getAsJsonObject();
		if (bool(o, "instrumental")) throw new IOException("LRCLIB: this song is instrumental");
		String synced = Http.str(o, "syncedLyrics");
		if (!isPlain(c) && !synced.isBlank()) return synced;
		String plain = Http.str(o, "plainLyrics");
		if (plain.isBlank()) throw new IOException("LRCLIB: no lyrics text");
		long len = o.has("duration") && !o.get("duration").isJsonNull()
				? Math.round(o.get("duration").getAsDouble() * 1000) : durationMs;
		if (durationMs > 0) len = durationMs;
		return LyricsParser.spreadPlain(plain, len);
	}

	// ---------------------------------------------------------------- helpers

	private static void addAll(Map<Long, Song> found, JsonElement el) {
		if (el == null || !el.isJsonArray()) return;
		JsonArray arr = el.getAsJsonArray();
		for (int i = 0; i < Math.min(arr.size(), 15); i++) {
			if (arr.get(i).isJsonObject()) add(found, arr.get(i).getAsJsonObject());
		}
	}

	private static void add(Map<Long, Song> found, JsonObject o) {
		if (!o.has("id") || o.get("id").isJsonNull()) return;
		long id = o.get("id").getAsLong();
		double dur = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
		String syncedText = Http.str(o, "syncedLyrics");
		boolean synced = !syncedText.isBlank();
		boolean wordSynced = synced && syncedText.matches("(?s).*<\\d{1,3}:\\d{2}.*");
		boolean plain = !Http.str(o, "plainLyrics").isBlank();
		boolean instrumental = bool(o, "instrumental");
		if (!synced && !plain && !instrumental) return;
		found.putIfAbsent(id, new Song(id, Http.str(o, "trackName"), Http.str(o, "artistName"),
				Http.str(o, "albumName"), dur, instrumental, synced, plain, wordSynced));
	}

	private static boolean bool(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
	}

	private static String query(Map<String, String> p) {
		StringBuilder sb = new StringBuilder();
		for (var e : p.entrySet()) {
			if (!sb.isEmpty()) sb.append('&');
			sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	private static long lastRequest;

	/**
	 * GET a JSON endpoint; null on 404. LRCLIB asks clients to name themselves, send one
	 * request at a time with a short pause between them, and honour Retry-After on 429.
	 */
	private static synchronized JsonElement get(String path) throws IOException, InterruptedException {
		for (int attempt = 0; ; attempt++) {
			long wait = lastRequest + 250 - System.currentTimeMillis();
			if (wait > 0) Thread.sleep(wait);
			lastRequest = System.currentTimeMillis();
			HttpResponse<String> resp = Http.sendString(Http.request(API + path)
					.setHeader("User-Agent", Http.APP_AGENT)
					.header("Accept", "application/json")
					.timeout(Duration.ofSeconds(15))
					.GET().build());
			int code = resp.statusCode();
			if (code == 404) return null;
			if (code == 429 && attempt == 0) {
				long secs = resp.headers().firstValue("Retry-After").map(v -> {
					try {
						return Long.parseLong(v.trim());
					} catch (NumberFormatException e) {
						return 5L;
					}
				}).orElse(5L);
				if (secs <= 8) {
					Thread.sleep((long) (secs * 1000 * (1 + Math.random() * 0.3)));
					continue;
				}
				throw new IOException("LRCLIB: busy, try again in " + secs + " s");
			}
			if (code / 100 != 2) throw new IOException("LRCLIB: HTTP " + code + (code >= 500 ? " (site down?)" : ""));
			return JsonParser.parseString(resp.body());
		}
	}

	static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}
}

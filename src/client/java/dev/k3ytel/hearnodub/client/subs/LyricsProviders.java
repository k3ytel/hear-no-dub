package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics sources besides LRCLIB. Each search returns {@link Candidate}s whose label ends with
 * the sync level in brackets ("(word-synced)", "(synced)", "(not synced)") and whose title
 * says whether the song length matched, the same way {@link LrclibClient} does.
 */
public final class LyricsProviders {
	private LyricsProviders() {}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}

	private static String query(Map<String, String> p) {
		StringBuilder sb = new StringBuilder();
		for (var e : p.entrySet()) {
			if (e.getValue() == null || e.getValue().isBlank()) continue;
			if (!sb.isEmpty()) sb.append('&');
			sb.append(e.getKey()).append('=').append(enc(e.getValue()));
		}
		return sb.toString();
	}

	private static Map<String, String> params(String query) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String part : query.split("&")) {
			int eq = part.indexOf('=');
			if (eq <= 0) continue;
			out.put(part.substring(0, eq), java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	/** Stable short id for a query, so the same search reuses the cache. */
	private static String idOf(String s) {
		return Integer.toHexString(s.toLowerCase(Locale.ROOT).hashCode());
	}

	private static String lengthTag(long wantMs, long gotMs) {
		if (wantMs <= 0 || gotMs <= 0) return "";
		long diff = Math.round(Math.abs(gotMs - wantMs) / 1000.0);
		return diff <= 2 ? "  [length matches]" : "  [" + diff + "s off]";
	}

	// ================================================================ Better Lyrics

	/**
	 * Better Lyrics (api.betterlyrics.org): free, word and syllable synced TTML, with
	 * background vocals and duets. Needs title, artist, album and length; misses that aren't
	 * cached answer 401 without an API key, which just means "nothing here".
	 * The project asks for a credit link: https://better-lyrics.boidu.dev
	 */
	public static final class BetterLyrics {
		private static final String API = "https://api.betterlyrics.org/getLyrics";
		private static final Map<String, String> found = new ConcurrentHashMap<>();

		public List<Candidate> search(String title, String artist, String album, long durationMs)
				throws IOException, InterruptedException {
			String track = LrclibClient.cleanTitle(title);
			String main = LrclibClient.mainArtist(artist);
			if (track.isEmpty() || main.isEmpty()) return List.of();
			Map<String, String> p = new LinkedHashMap<>();
			p.put("s", track);
			p.put("a", main);
			p.put("al", album == null ? "" : album);
			if (durationMs > 0) p.put("d", String.valueOf(Math.round(durationMs / 1000.0)));
			String q = query(p);
			String ttml = fetch(q);
			if (ttml == null || ttml.isBlank()) return List.of();
			String id = idOf(q);
			found.put(id, ttml);
			boolean words = ttml.contains("<span");
			String label = main + " - " + track + "  (" + (words ? "word-synced" : "synced") + ")";
			long last = lastEnd(ttml);
			String tag = durationMs > 0 && (last <= 0 || last <= durationMs + 8000) ? "  [length matches]" : "";
			return List.of(new Candidate("betterlyrics", id, label, (album == null || album.isBlank() ? track : album) + tag,
					0, 0, 0, false, q));
		}

		public String download(Candidate c) throws IOException, InterruptedException {
			String hit = found.get(c.id());
			if (hit != null) return hit;
			String ttml = fetch(c.url());
			if (ttml == null || ttml.isBlank()) throw new IOException("Better Lyrics: lyrics not found");
			return ttml;
		}

		private static String fetch(String q) throws IOException, InterruptedException {
			HttpResponse<String> resp = Http.sendString(Http.request(API + "?" + q)
					.setHeader("User-Agent", Http.APP_AGENT)
					.header("Accept", "application/json")
					.timeout(Duration.ofSeconds(12))
					.GET().build());
			int code = resp.statusCode();
			if (code == 404 || code == 401 || code == 422) return null;
			if (code / 100 != 2) throw new IOException("Better Lyrics: HTTP " + code);
			JsonElement el = JsonParser.parseString(resp.body());
			if (!el.isJsonObject()) return null;
			return Http.str(el.getAsJsonObject(), "ttml");
		}

		private static final Pattern END = Pattern.compile("end=\"([^\"]+)\"");

		private static long lastEnd(String ttml) {
			Matcher m = END.matcher(ttml);
			long last = -1;
			while (m.find()) last = Math.max(last, TtmlParser.time(m.group(1)));
			return last;
		}
	}

	// ================================================================ NetEase

	/**
	 * NetEase Cloud Music (music.163.com): an unofficial but huge source of synced lyrics,
	 * many with word timing ("yrc"). Not an official API: it can stop working at any time.
	 */
	public static final class NetEase {
		private static final String SEARCH = "https://music.163.com/api/search/pc";
		private static final String LYRIC = "https://music.163.com/api/song/lyric";

		private static java.net.http.HttpRequest.Builder req(String url) {
			return Http.request(url)
					.setHeader("User-Agent", Http.APP_AGENT)
					.header("Referer", "https://music.163.com/")
					.header("Cookie", "os=pc; appver=2.9.7")
					.timeout(Duration.ofSeconds(12));
		}

		public List<Candidate> search(String title, String artist, long durationMs) throws IOException, InterruptedException {
			String track = LrclibClient.cleanTitle(title);
			String main = LrclibClient.mainArtist(artist);
			String q = (track + " " + main).strip();
			if (q.isEmpty()) return List.of();
			HttpResponse<String> resp = Http.sendString(req(SEARCH + "?" + query(Map.of("s", q, "type", "1", "limit", "10", "offset", "0")))
					.GET().build());
			if (resp.statusCode() / 100 != 2) throw new IOException("NetEase: HTTP " + resp.statusCode());
			JsonElement el = JsonParser.parseString(resp.body());
			if (!el.isJsonObject()) return List.of();
			JsonObject root = el.getAsJsonObject();
			if (!root.has("result") || !root.get("result").isJsonObject()) return List.of();
			JsonObject result = root.getAsJsonObject("result");
			if (!result.has("songs") || !result.get("songs").isJsonArray()) return List.of();
			JsonArray songs = result.getAsJsonArray("songs");

			record Song(long id, String name, String artist, String album, long ms, int score) {}
			List<Song> list = new ArrayList<>();
			for (JsonElement s : songs) {
				if (!s.isJsonObject()) continue;
				JsonObject o = s.getAsJsonObject();
				long id = o.get("id").getAsLong();
				String name = Http.str(o, "name");
				String art = "";
				if (o.has("artists") && o.get("artists").isJsonArray() && !o.getAsJsonArray("artists").isEmpty()) {
					art = Http.str(o.getAsJsonArray("artists").get(0).getAsJsonObject(), "name");
				}
				String alb = o.has("album") && o.get("album").isJsonObject() ? Http.str(o.getAsJsonObject("album"), "name") : "";
				long ms = o.has("duration") ? o.get("duration").getAsLong() : 0;
				int score = (int) (400 * EpisodeLookup.similarity(name, track)) + (int) (400 * EpisodeLookup.similarity(art, main));
				if (durationMs > 0 && ms > 0) {
					double diff = Math.abs(ms - durationMs) / 1000.0;
					score += diff <= 2 ? 1000 : diff <= 5 ? 600 : diff <= 15 ? 100 : -800;
				}
				list.add(new Song(id, name, art, alb, ms, score));
			}
			list.sort((a, b) -> Integer.compare(b.score(), a.score()));
			List<Candidate> out = new ArrayList<>();
			for (Song s : list.subList(0, Math.min(5, list.size()))) {
				// The sync level is only known after downloading; NetEase almost always has line sync
				out.add(new Candidate("netease", String.valueOf(s.id()), s.artist() + " - " + s.name() + "  (synced)",
						(s.album().isBlank() ? s.name() : s.album()) + lengthTag(durationMs, s.ms()), 0, 0, 0, false, ""));
			}
			return out;
		}

		/** Word-synced "yrc" when NetEase has it (as enhanced LRC), else the line-synced LRC. */
		public String download(Candidate c) throws IOException, InterruptedException {
			JsonObject o = lyricJson(LYRIC + "/v1?" + query(Map.of("id", c.id(), "lv", "1", "yv", "1", "tv", "-1")));
			if (o == null) o = lyricJson(LYRIC + "?" + query(Map.of("id", c.id(), "lv", "1", "kv", "1", "tv", "-1")));
			if (o == null) throw new IOException("NetEase: lyrics not found");
			String yrc = section(o, "yrc");
			if (!yrc.isBlank()) {
				String lrc = yrcToLrc(yrc);
				if (LyricsParser.looksLikeLrc(lrc)) return lrc;
			}
			String lrc = section(o, "lrc");
			if (lrc.isBlank()) throw new IOException("NetEase: no lyrics for this song");
			return dropCredits(lrc);
		}

		private static JsonObject lyricJson(String url) throws IOException, InterruptedException {
			HttpResponse<String> resp = Http.sendString(req(url).GET().build());
			if (resp.statusCode() / 100 != 2) return null;
			JsonElement el = JsonParser.parseString(resp.body());
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		}

		private static String section(JsonObject o, String key) {
			if (!o.has(key) || !o.get(key).isJsonObject()) return "";
			return Http.str(o.getAsJsonObject(key), "lyric");
		}

		private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
		private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");

		/** "[16210,3460](16210,670,0)Every (16880,410,0)night" -> "[00:16.21]<00:16.21>Every <00:16.88>night <..end..>" */
		static String yrcToLrc(String yrc) {
			StringBuilder out = new StringBuilder();
			for (String line : yrc.split("\n")) {
				Matcher m = YRC_LINE.matcher(line.strip());
				if (!m.matches()) continue; // JSON credit lines start with "{"
				long start = Long.parseLong(m.group(1));
				out.append(tag('[', start, ']'));
				Matcher w = YRC_WORD.matcher(m.group(3));
				long end = start;
				while (w.find()) {
					long ws = Long.parseLong(w.group(1)), wd = Long.parseLong(w.group(2));
					out.append(tag('<', ws, '>')).append(w.group(3));
					end = Math.max(end, ws + wd);
				}
				out.append(tag('<', end, '>')).append('\n');
			}
			return out.toString();
		}

		private static String tag(char open, long ms, char close) {
			return String.format(Locale.ROOT, "%c%02d:%02d.%02d%c", open, ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10, close);
		}

		private static final Pattern CREDIT = Pattern.compile(
				"(?i)^\\[[^]]*]\\s*(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|录音|监制|出品|lyrics?( by)?|composer|composed by|arranger|producer|written by)\\s*[:：]");

		private static String dropCredits(String lrc) {
			StringBuilder out = new StringBuilder();
			for (String line : lrc.split("\n")) {
				String l = line.strip();
				if (l.startsWith("{") || CREDIT.matcher(l).find()) continue;
				out.append(line).append('\n');
			}
			return out.toString();
		}
	}

	// ================================================================ lyrics.ovh

	/** lyrics.ovh: free plain lyrics by artist + title (not synced; spread over the song). */
	public static final class LyricsOvh {
		private static final String API = "https://api.lyrics.ovh/v1/";
		private static final Map<String, String> found = new ConcurrentHashMap<>();

		public List<Candidate> search(String title, String artist) throws IOException, InterruptedException {
			String track = LrclibClient.cleanTitle(title);
			String main = LrclibClient.mainArtist(artist);
			if (track.isEmpty() || main.isEmpty()) return List.of();
			String path = enc(main).replace("+", "%20") + "/" + enc(track).replace("+", "%20");
			String text = fetch(path);
			if (text == null || text.isBlank()) return List.of();
			String id = idOf(path);
			found.put(id, text);
			return List.of(new Candidate("lyricsovh", id, main + " - " + track + "  (not synced)", track + "  [title match]",
					0, 0, 0, false, path));
		}

		public String download(Candidate c, long durationMs) throws IOException, InterruptedException {
			String text = found.get(c.id());
			if (text == null) text = fetch(c.url());
			if (text == null || text.isBlank()) throw new IOException("lyrics.ovh: lyrics not found");
			// first line is often "Paroles de la chanson X par Y"
			String[] lines = text.replace("\r", "").split("\n", 2);
			if (lines.length == 2 && lines[0].toLowerCase(Locale.ROOT).startsWith("paroles de la chanson")) text = lines[1];
			return LyricsParser.spreadPlain(text.strip(), durationMs);
		}

		private static String fetch(String path) throws IOException, InterruptedException {
			HttpResponse<String> resp = Http.sendString(Http.request(API + path)
					.setHeader("User-Agent", Http.APP_AGENT)
					.timeout(Duration.ofSeconds(12))
					.GET().build());
			if (resp.statusCode() == 404) return null;
			if (resp.statusCode() / 100 != 2) throw new IOException("lyrics.ovh: HTTP " + resp.statusCode());
			JsonElement el = JsonParser.parseString(resp.body());
			return el.isJsonObject() ? Http.str(el.getAsJsonObject(), "lyrics") : null;
		}
	}

	// ================================================================ ranking

	/** Higher = better: word-synced > synced > plain, matching length first, then source. */
	public static int rank(Candidate c) {
		String l = c.label();
		int r = l.endsWith("(word-synced)") ? 3000 : l.endsWith("(synced)") ? 2000 : l.endsWith("(not synced)") ? 1000 : 0;
		String t = c.title();
		if (t.contains("[length matches]")) r += 600;
		else if (t.contains("[title match]")) r += 300;
		else {
			Matcher m = Pattern.compile("\\[(\\d+)s off]").matcher(t);
			if (m.find()) r -= Math.min(900, Integer.parseInt(m.group(1)) * 20);
		}
		r += switch (c.source()) {
			case "betterlyrics" -> 40;
			case "lrclib" -> 30;
			case "netease" -> 10;
			default -> 0;
		};
		return r;
	}

	/** Good enough to load without asking. */
	public static boolean confident(Candidate c) {
		String t = c.title();
		return !c.label().endsWith("(instrumental)") && (t.contains("[length matches]") || t.contains("[title match]"));
	}
}

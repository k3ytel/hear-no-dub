package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import dev.k3ytel.hearnodub.client.media.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skip intro / ending. Finds the anime on AniList (to get its MyAnimeList ID), then asks
 * AniSkip (the community database most anime sites use for their own skip buttons) for the
 * opening/ending times of the episode. Shows a "Skip opening [O]" prompt while one plays, and
 * can skip by itself after a short countdown.
 */
public final class IntroSkipper {
	private static final IntroSkipper INSTANCE = new IntroSkipper();

	private static final String ANILIST = "https://graphql.anilist.co";
	private static final String ANISKIP = "https://api.aniskip.com/v2/skip-times/";
	private static final long MOVIE_MS = 70 * 60 * 1000L;
	/** The prompt disappears this long before the part ends (skipping then is pointless). */
	private static final long END_MARGIN_MS = 1500;

	public enum Kind {
		OPENING, ENDING, RECAP;

		public Component display() {
			return Component.translatable("hear-no-dub.skip." + name().toLowerCase(Locale.ROOT));
		}
	}

	public record Interval(Kind kind, long startMs, long endMs) {}

	/** What the HUD shows: the part playing now, and the auto-skip countdown (or -1 when manual). */
	public record Prompt(Interval interval, float countdown, int secondsLeft) {
		public boolean auto() {
			return countdown >= 0;
		}
	}

	private record Anime(int malId, String title) {}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HearNoDub-Skip");
		t.setDaemon(true);
		return t;
	});
	private final EpisodeLookup lookup = new EpisodeLookup();

	// Written by the worker, read on the client thread
	private volatile String videoKey;
	private volatile List<Interval> intervals = List.of();
	private volatile Component info = Component.empty();
	/** "query|season|episode" of the last lookup, so the same episode isn't looked up twice. */
	private volatile String resolvedFor = "";

	// Client thread only
	private Interval current;
	private long countdownMs;
	private long lastTickMs;
	private final Set<Interval> handled = new HashSet<>();

	private IntroSkipper() {}

	public static IntroSkipper get() {
		return INSTANCE;
	}

	// ================================================================ finding the times

	/** A new video started. Looks up its skip times in the background. */
	public void onNewVideo(String key, MediaBridge.Session s, TitleGuesser.Guess guess) {
		videoKey = key;
		intervals = List.of();
		info = Component.empty();
		resolvedFor = "";
		current = null;
		handled.clear();
		if (!HearNoDubConfig.get().skipTimes || s == null || guess == null || guess.query().isBlank()) return;
		if (s.end >= MOVIE_MS) {
			info = Component.translatable("hear-no-dub.skip.info.movie");
			return;
		}
		lookUp(key, guess.query(), guess.season(), guess.episode(), s.title, s.end);
	}

	/**
	 * The subtitle search worked out the season/episode (e.g. from the episode title). Used
	 * when the first lookup didn't find anything.
	 */
	public void onEpisodeKnown(String key, String query, int season, int episode, MediaBridge.Session s) {
		if (!HearNoDubConfig.get().skipTimes || key == null || !key.equals(videoKey) || episode <= 0) return;
		if (!intervals.isEmpty() || s == null || s.end >= MOVIE_MS) return;
		if (resolvedFor.equals(resolvedKey(query, season, episode))) return;
		lookUp(key, query, season, episode, s.title, s.end);
	}

	private void lookUp(String key, String query, int season, int episode, String episodeTitle, long lengthMs) {
		info = Component.translatable("hear-no-dub.skip.info.looking");
		worker.execute(() -> {
			Component result;
			List<Interval> found = List.of();
			int s = season, e = episode;
			try {
				if (e <= 0) {
					EpisodeLookup.Show show = lookup.findShow(query);
					EpisodeLookup.Episode ep = show == null ? null : EpisodeLookup.matchEpisode(show, episodeTitle);
					if (ep != null) {
						s = ep.season();
						e = ep.number();
					}
				}
				if (e <= 0) {
					result = Component.translatable("hear-no-dub.skip.info.no_episode");
				} else {
					Anime anime = findAnime(query, s);
					if (anime == null) {
						result = Component.translatable("hear-no-dub.skip.info.no_anime", query);
					} else {
						found = fetchTimes(anime.malId(), e, lengthMs);
						result = found.isEmpty()
								? Component.translatable("hear-no-dub.skip.info.none", anime.title(), e)
								: Component.translatable("hear-no-dub.skip.info.found", anime.title(), e, describe(found));
					}
				}
				HearNoDubClient.LOGGER.info("Skip times for '{}' S{}E{}: {}", query, s, e, result.getString());
			} catch (Exception ex) {
				HearNoDubClient.LOGGER.warn("Skip time lookup failed: {}", ex.toString());
				result = Component.translatable("hear-no-dub.skip.info.error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
			}
			if (!key.equals(videoKey)) return; // another video started meanwhile
			resolvedFor = resolvedKey(query, s, e);
			intervals = found;
			info = result;
		});
	}

	private static String resolvedKey(String query, int season, int episode) {
		return query.toLowerCase(Locale.ROOT) + "|" + season + "|" + episode;
	}

	private static String describe(List<Interval> list) {
		List<String> parts = new ArrayList<>();
		for (Interval i : list) {
			parts.add(i.kind().display().getString() + " " + time(i.startMs()) + "–" + time(i.endMs()));
		}
		return String.join(", ", parts);
	}

	// ---------------------------------------------------------------- AniList

	private static final Pattern SEASON_WORD = Pattern.compile("\\bseason\\s*(\\d{1,2})\\b");
	private static final Pattern SEASON_ORD = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b");
	private static final Pattern SEASON_ROMAN = Pattern.compile("\\b(ii|iii|iv|v|vi)\\s*$");

	/** Finds the AniList entry for the show (and season), returning its MyAnimeList ID. */
	private Anime findAnime(String query, int season) throws IOException, InterruptedException {
		String gql = "query($s:String){Page(perPage:10){media(search:$s,type:ANIME,sort:SEARCH_MATCH)"
				+ "{idMal format seasonYear title{romaji english} synonyms}}}";
		JsonObject body = new JsonObject();
		body.addProperty("query", gql);
		JsonObject vars = new JsonObject();
		vars.addProperty("s", query);
		body.add("variables", vars);

		HttpRequest req = Http.request(ANILIST)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> resp = Http.sendString(req);
		if (resp.statusCode() / 100 != 2) throw new IOException("AniList HTTP " + resp.statusCode());

		JsonArray media = JsonParser.parseString(resp.body()).getAsJsonObject()
				.getAsJsonObject("data").getAsJsonObject("Page").getAsJsonArray("media");
		Anime best = null;
		double bestScore = 0.5; // below this it's probably a different show
		for (int i = 0; i < media.size(); i++) {
			JsonObject m = media.get(i).getAsJsonObject();
			int mal = Http.num(m, "idMal");
			if (mal <= 0) continue;
			JsonObject t = m.getAsJsonObject("title");
			List<String> names = new ArrayList<>();
			String romaji = Http.str(t, "romaji"), english = Http.str(t, "english");
			if (!romaji.isBlank()) names.add(romaji);
			if (!english.isBlank()) names.add(english);
			JsonElement syn = m.get("synonyms");
			if (syn != null && syn.isJsonArray()) for (JsonElement el : syn.getAsJsonArray()) names.add(el.getAsString());

			double sim = 0;
			int titleSeason = 1;
			for (String n : names) {
				sim = Math.max(sim, EpisodeLookup.similarity(query, n));
				titleSeason = Math.max(titleSeason, seasonOf(n));
			}
			double score = sim - i * 0.01; // AniList's own ranking breaks ties
			String format = Http.str(m, "format");
			switch (format) {
				case "TV", "TV_SHORT", "ONA" -> score += 0.1;
				case "OVA" -> score -= 0.2;
				default -> score -= 0.5; // movies, specials, music videos
			}
			if (season > 0) score += titleSeason == season ? 0.4 : -0.3;
			if (score > bestScore) {
				bestScore = score;
				best = new Anime(mal, english.isBlank() ? romaji : english);
			}
		}
		return best;
	}

	/** Season number written in an anime title ("Season 2", "2nd Season", "II"), or 1. */
	static int seasonOf(String title) {
		String t = title.toLowerCase(Locale.ROOT);
		Matcher m = SEASON_WORD.matcher(t);
		if (m.find()) return Integer.parseInt(m.group(1));
		m = SEASON_ORD.matcher(t);
		if (m.find()) return Integer.parseInt(m.group(1));
		m = SEASON_ROMAN.matcher(t);
		if (m.find()) {
			return switch (m.group(1)) {
				case "ii" -> 2;
				case "iii" -> 3;
				case "iv" -> 4;
				case "v" -> 5;
				default -> 6;
			};
		}
		return 1;
	}

	// ---------------------------------------------------------------- AniSkip

	private List<Interval> fetchTimes(int malId, int episode, long lengthMs) throws IOException, InterruptedException {
		long lengthSec = Math.max(0, lengthMs / 1000);
		List<Interval> list = lengthSec > 0 ? queryAniSkip(malId, episode, lengthSec) : List.of();
		if (list.isEmpty()) list = queryAniSkip(malId, episode, 0); // any upload, whatever its length
		return list;
	}

	private List<Interval> queryAniSkip(int malId, int episode, long lengthSec) throws IOException, InterruptedException {
		String url = ANISKIP + malId + "/" + episode
				+ "?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=" + lengthSec;
		HttpResponse<String> resp = Http.sendString(Http.request(url).header("Accept", "application/json").GET().build());
		if (resp.statusCode() == 404) return List.of(); // "no skip times found"
		if (resp.statusCode() / 100 != 2) throw new IOException("AniSkip HTTP " + resp.statusCode());

		JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
		if (!Http.bool(root, "found") || !root.has("results")) return List.of();
		List<Interval> out = new ArrayList<>();
		EnumSet<Kind> seen = EnumSet.noneOf(Kind.class);
		for (JsonElement el : root.getAsJsonArray("results")) {
			JsonObject r = el.getAsJsonObject();
			Kind kind = switch (Http.str(r, "skipType")) {
				case "op", "mixed-op" -> Kind.OPENING;
				case "ed", "mixed-ed" -> Kind.ENDING;
				case "recap" -> Kind.RECAP;
				default -> null;
			};
			if (kind == null || !seen.add(kind)) continue; // first (best voted) one of each kind
			JsonObject iv = r.getAsJsonObject("interval");
			long start = Math.round(iv.get("startTime").getAsDouble() * 1000);
			long end = Math.round(iv.get("endTime").getAsDouble() * 1000);
			if (end - start >= 5000) out.add(new Interval(kind, start, end));
		}
		out.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
		return List.copyOf(out);
	}

	// ================================================================ while watching

	/** Called every client tick: tracks which part is playing and runs the auto-skip countdown. */
	public void tick(Minecraft mc, MediaBridge.Session s) {
		long nowMs = System.currentTimeMillis();
		long dt = lastTickMs == 0 ? 0 : Math.min(500, nowMs - lastTickMs);
		lastTickMs = nowMs;
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (!cfg.skipTimes || s == null) {
			current = null;
			return;
		}
		long pos = s.livePositionMs(nowMs);
		Interval in = null;
		for (Interval i : intervals) {
			if (pos >= i.startMs() && pos < i.endMs() - END_MARGIN_MS) in = i;
		}
		if (in != current) {
			current = in;
			countdownMs = 0;
		}
		if (current == null || !autoSkips(cfg, current.kind()) || handled.contains(current)) return;
		if (s.isPlaying()) countdownMs += dt;
		if (countdownMs >= cfg.autoSkipDelaySec * 1000L) skip(mc, current);
	}

	private static boolean autoSkips(HearNoDubConfig cfg, Kind kind) {
		return kind == Kind.ENDING ? cfg.autoSkipEnding : cfg.autoSkipOpening;
	}

	/** The skip key: skips the opening/ending playing now, or jumps ahead by the fallback length. */
	public void skipPressed(Minecraft mc) {
		if (current != null) {
			skip(mc, current);
			return;
		}
		int sec = HearNoDubConfig.get().skipFallbackSec;
		Playback.skipBy(mc, sec * 1000L, Component.translatable("hear-no-dub.osd.skipped_sec", sec));
	}

	private void skip(Minecraft mc, Interval i) {
		handled.add(i); // seeking back into it later won't auto-skip again
		Playback.seekTo(mc, i.endMs(), Component.translatable("hear-no-dub.osd.skipped", i.kind().display()));
		current = null;
		countdownMs = 0;
	}

	/** What the HUD should show right now, or null. */
	public Prompt prompt() {
		Interval i = current;
		if (i == null) return null;
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (!autoSkips(cfg, i.kind()) || handled.contains(i)) return new Prompt(i, -1f, 0);
		long total = Math.max(1, cfg.autoSkipDelaySec * 1000L);
		float left = Math.max(0f, 1f - countdownMs / (float) total);
		int secs = (int) Math.ceil(Math.max(0, total - countdownMs) / 1000.0);
		return new Prompt(i, left, secs);
	}

	/** Skip times of the current video (for the picker's progress bar). */
	public List<Interval> intervals() {
		return intervals;
	}

	/** One line about the lookup, for the picker. */
	public Component info() {
		return info;
	}

	private static String time(long ms) {
		long total = Math.max(0, ms) / 1000;
		return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
	}
}

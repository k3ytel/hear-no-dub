package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The brain of the mod: follows which video is playing, finds and loads subtitles for it,
 * keeps the sync offset, and answers "which lines are on screen right now?".
 *
 * Everything public is called on the client thread. Network and file work runs on a
 * background thread and hands its result back with {@code Minecraft.execute}.
 */
public final class SubtitleManager {
	private static final SubtitleManager INSTANCE = new SubtitleManager();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

	private static final Path STATE_FILE = HearNoDubConfig.DIR.resolve("state.json");
	private static final Path CACHE_DIR = HearNoDubConfig.DIR.resolve("cache");
	public static final Path LOCAL_DIR = HearNoDubConfig.DIR.resolve("subs");

	/** A subtitle the user can pick: from an online source, or a local file. */
	public record Entry(String key, String label, String detail, Candidate remote, Path local) {}

	/** Saved per video: which subtitle was chosen. Plus saved sync offsets per subtitle. */
	private static final class State {
		Map<String, Choice> choices = new HashMap<>();
		Map<String, Long> shifts = new HashMap<>();
		/** Last offset used for each show, used as the starting offset for its next episodes. */
		Map<String, Long> showShifts = new HashMap<>();
	}

	private static final class Choice {
		String key;
		String label;
		/** "os", "subdl", "jimaku", "lrclib"; null in files saved by v0.1.0 (= OpenSubtitles). */
		String source;
		String id;
		String url;
		int episode;
		/** v0.1.0 OpenSubtitles file id. */
		long fileId;
		String localName;
	}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HearNoDub-Worker");
		t.setDaemon(true);
		return t;
	});
	private final OpenSubtitlesClient openSubtitles = new OpenSubtitlesClient();
	private final SubDlClient subdl = new SubDlClient();
	private final JimakuClient jimaku = new JimakuClient();
	private final LrclibClient lrclib = new LrclibClient();
	private final LyricsProviders.BetterLyrics betterLyrics = new LyricsProviders.BetterLyrics();
	private final LyricsProviders.NetEase netEase = new LyricsProviders.NetEase();
	private final LyricsProviders.LyricsOvh lyricsOvh = new LyricsProviders.LyricsOvh();
	private final EpisodeLookup lookup = new EpisodeLookup();
	private State state = new State();

	// ---------------------------------------------------------------- current video
	private MediaBridge.Session session;
	private String videoKey;
	private String pinnedApp;
	private TitleGuesser.Guess guess;
	private boolean warnedNoKey;

	// ---------------------------------------------------------------- current subtitles
	private SubtitleTrack track;
	private String trackKey;
	private String trackLabel;
	private long shiftMs;

	// ---------------------------------------------------------------- picker state
	private List<Entry> results = List.of();
	private Component status = Component.empty();
	private boolean busy;
	/** Key of the subtitle currently being downloaded, so repeated clicks don't queue it again. */
	private String loadingKey;

	private SubtitleManager() {}

	public static SubtitleManager get() {
		return INSTANCE;
	}

	// ================================================================ per tick

	public void tick(Minecraft mc) {
		MediaBridge.Snapshot snap = MediaBridge.get().latest();
		MediaBridge.Session chosen = choose(snap.sessions);
		session = chosen;
		if (chosen == null || chosen.title == null || chosen.title.isBlank()) return;

		String key = chosen.app + "|" + chosen.title;
		if (!key.equals(videoKey)) {
			videoKey = key;
			onNewVideo(mc, chosen, snap.windows);
		}
	}

	/** Pinned app if present, else the app we were already following, else whatever is playing. */
	private MediaBridge.Session choose(List<MediaBridge.Session> sessions) {
		if (sessions == null || sessions.isEmpty()) return null;
		if (pinnedApp != null) {
			for (MediaBridge.Session s : sessions) if (pinnedApp.equals(s.app)) return s;
		}
		// Music players never get picked automatically (switch to them in the picker if wanted)
		List<MediaBridge.Session> videos = sessions.stream().filter(s -> !s.isMusicApp()).toList();
		HearNoDubConfig cfg = HearNoDubConfig.get();
		boolean musicPlaying = sessions.stream().anyMatch(s -> s.isMusicApp() && s.isPlaying());
		boolean videoPlaying = videos.stream().anyMatch(MediaBridge.Session::isPlaying);
		boolean letMusicIn = cfg.lyricsReady() && cfg.lyricsFollowMusic && musicPlaying && !videoPlaying;
		if (!videos.isEmpty() && !letMusicIn) sessions = videos;
		if (session != null) {
			for (MediaBridge.Session s : sessions) {
				if (s.app.equals(session.app) && (s.isPlaying() || noneOtherPlaying(sessions, s))) return s;
			}
		}
		for (MediaBridge.Session s : sessions) if (s.isPlaying()) return s;
		return sessions.getFirst();
	}

	private static boolean noneOtherPlaying(List<MediaBridge.Session> sessions, MediaBridge.Session self) {
		for (MediaBridge.Session s : sessions) if (s != self && s.isPlaying()) return false;
		return true;
	}

	private void onNewVideo(Minecraft mc, MediaBridge.Session s, List<MediaBridge.Window> windows) {
		clearTrack();
		results = List.of();
		guess = TitleGuesser.guess(s, windows);
		IntroSkipper.get().onNewVideo(videoKey, s, guess);
		HearNoDubClient.LOGGER.info("New video: app='{}' title='{}' artist='{}' album='{}' end={}ms tab='{}' -> guess '{}'",
				s.app, s.title, s.artist, s.album, s.end, guess.tabTitle(), guess.describe());

		boolean music = s.isMusicApp() && HearNoDubConfig.get().lyricsReady();
		if (music) guess = new TitleGuesser.Guess(LrclibClient.cleanTitle(s.title), 0, 0, guess.tabTitle());

		Choice saved = state.choices.get(videoKey);
		if (saved != null) {
			load(mc, entryOf(saved), Mode.SAVED);
			return;
		}
		if (music) {
			if (HearNoDubConfig.get().autoSearch) searchLyrics(mc, s.title, true);
			else status = Component.translatable("hear-no-dub.status.idle");
			return;
		}
		if (!HearNoDubConfig.get().autoSearch) {
			status = Component.translatable("hear-no-dub.status.idle");
			return;
		}
		if (!HearNoDubConfig.get().anySourceReady()) {
			status = Component.translatable("hear-no-dub.status.no_key").withStyle(ChatFormatting.YELLOW);
			if (!warnedNoKey) {
				warnedNoKey = true;
				toast(mc, Component.translatable("hear-no-dub.toast.no_key"));
			}
			return;
		}
		search(mc, guess.query(), guess.season(), guess.episode(), true);
	}

	private static Entry entryOf(Choice c) {
		if (c.localName != null) return new Entry(c.key, c.label, "", null, LOCAL_DIR.resolve(c.localName));
		String source = c.source != null ? c.source : "os";
		String id = c.id != null ? c.id : String.valueOf(c.fileId);
		Candidate cand = new Candidate(source, id, c.label, "", 0, c.episode, 0, false, c.url == null ? "" : c.url);
		return new Entry(cand.key(), c.label, "", cand, null);
	}

	// ================================================================ search & load

	private enum Mode { AUTO, MANUAL, SAVED }

	/** Videos at least this long are treated as movies. */
	private static final long MOVIE_MS = 70 * 60 * 1000L;

	/**
	 * Finds subtitles. Steps: look the show up on TVmaze (IMDb ID + episode list), work out
	 * season/episode from the episode name if they're unknown, then search OpenSubtitles.
	 * With autoPick, the best result is loaded, but only if it's clearly the right episode.
	 */
	public void search(Minecraft mc, String query, int season, int episode, boolean autoPick) {
		if (query == null || query.isBlank()) return;
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (session != null && session.isMusicApp() && cfg.lyricsReady()) {
			searchLyrics(mc, query, autoPick);
			return;
		}
		if (!cfg.anySourceReady()) {
			status = Component.translatable("hear-no-dub.status.no_key").withStyle(ChatFormatting.YELLOW);
			return;
		}
		busy = true;
		status = Component.translatable("hear-no-dub.status.searching", query).withStyle(ChatFormatting.GRAY);
		String forVideo = videoKey;
		String episodeTitle = session != null ? session.title : "";
		String tab = guess != null ? guess.tabTitle() : "";
		boolean useSubdl = cfg.subdlReady(), useOs = cfg.osReady(), useJimaku = cfg.jimakuReady();
		// Over ~70 minutes it's a movie (sites often still call it "Episode 1")
		boolean movie = session != null && session.end >= MOVIE_MS && season == 0;
		worker.execute(() -> {
			try {
				int s = movie ? 0 : season, e = movie ? 0 : episode;
				EpisodeLookup.Show show = movie ? null : lookup.findShow(query);
				if (show != null && s == 0 && e == 0) {
					EpisodeLookup.Episode ep = EpisodeLookup.matchEpisode(show, episodeTitle);
					if (ep != null) {
						s = ep.season();
						e = ep.number();
					}
					HearNoDubClient.LOGGER.info("Episode match for '{}': {}", episodeTitle,
							ep == null ? "none" : "S" + ep.season() + "E" + ep.number() + " '" + ep.name() + "'");
				}
				long imdb = show == null ? 0 : show.imdbNumber();
				String imdbId = show == null ? null : show.imdbId();

				List<Candidate> found = new ArrayList<>();
				List<String> counts = new ArrayList<>();
				List<String> errors = new ArrayList<>();

				if (useSubdl) {
					try {
						List<Candidate> r = List.of();
						if (imdbId != null && !imdbId.isBlank()) r = subdl.search(query, imdbId, s, e, movie);
						if (r.isEmpty()) r = subdl.search(query, null, s, e, movie);
						if (r.isEmpty() && (s > 0 || e > 0)) r = subdl.search(query, imdbId, 0, 0, movie);
						found.addAll(r);
						counts.add("SubDL " + r.size());
					} catch (Exception ex) {
						HearNoDubClient.LOGGER.warn("SubDL search failed: {}", ex.toString());
						errors.add("SubDL: " + message(ex));
					}
				}
				if (useOs) {
					try {
						List<Candidate> r = List.of();
						if (imdb > 0) r = openSubtitles.search(query, s, e, imdb);
						if (r.isEmpty()) r = openSubtitles.search(query, s, e, 0);
						if (r.isEmpty() && (s > 0 || e > 0)) r = openSubtitles.search(query, 0, 0, 0);
						found.addAll(r);
						counts.add("OpenSubtitles " + r.size());
					} catch (Exception ex) {
						HearNoDubClient.LOGGER.warn("OpenSubtitles search failed: {}", ex.toString());
						errors.add("OpenSubtitles: " + message(ex));
					}
				}
				if (useJimaku) {
					try {
						String name = show != null ? show.name() : query;
						List<Candidate> r = jimaku.search(name, e);
						found.addAll(r);
						counts.add("Jimaku " + r.size());
					} catch (Exception ex) {
						HearNoDubClient.LOGGER.warn("Jimaku search failed: {}", ex.toString());
						errors.add("Jimaku: " + message(ex));
					}
				}

				final int fs = s, fe = e;
				final boolean knownShow = imdbId != null && !imdbId.isBlank();
				List<Candidate> sorted = new ArrayList<>(found);
				sorted.sort(Comparator.comparingInt((Candidate c) -> score(c, fs, fe)).reversed());
				List<Entry> entries = new ArrayList<>();
				for (Candidate c : sorted) entries.add(entryOf(c));
				HearNoDubClient.LOGGER.info("Search '{}' S{}E{} imdb={} -> {} results ({}){}{}", query, fs, fe, imdbId, entries.size(),
						String.join(", ", counts), errors.isEmpty() ? "" : " errors: " + errors,
						sorted.isEmpty() ? "" : ", best: [" + sorted.getFirst().sourceName() + "] " + sorted.getFirst().label());

				mc.execute(() -> {
					busy = false;
					IntroSkipper.get().onEpisodeKnown(forVideo, query, fs, fe, session);
					if (!java.util.Objects.equals(forVideo, videoKey) && autoPick) return;
					results = entries;
					if (autoPick) guess = new TitleGuesser.Guess(query, fs, fe, tab);
					if (entries.isEmpty()) {
						status = errors.isEmpty()
								? Component.translatable("hear-no-dub.status.none", query).withStyle(ChatFormatting.YELLOW)
								: Component.translatable("hear-no-dub.status.error", String.join(" · ", errors)).withStyle(ChatFormatting.RED);
						if (autoPick) toast(mc, Component.translatable("hear-no-dub.toast.none", query));
						return;
					}
					String summary = String.join(" · ", counts) + (errors.isEmpty() ? "" : " · " + String.join(" · ", errors));
					status = Component.translatable("hear-no-dub.status.found_from", entries.size(), summary);
					if (!autoPick) return;
					Candidate best = sorted.getFirst();
					// Without an exact show match (IMDb), the result's title must look like the show
					boolean titleOk = knownShow || EpisodeLookup.similarity(best.title().isBlank() ? best.label() : best.title(), query) >= 0.6
							|| EpisodeLookup.similarity(best.label(), query) >= 0.6;
					boolean sure = movie
							? titleOk
							: titleOk && fe > 0 && best.episode() == fe && (fs == 0 || best.season() == 0 || best.season() == fs);
					if (sure) {
						load(mc, entries.getFirst(), Mode.AUTO);
					} else {
						status = Component.translatable("hear-no-dub.status.unsure").withStyle(ChatFormatting.YELLOW);
						toast(mc, Component.translatable("hear-no-dub.toast.unsure", keyName()));
					}
				});
			} catch (Throwable t) {
				HearNoDubClient.LOGGER.warn("Search failed", t);
				mc.execute(() -> {
					busy = false;
					status = Component.translatable("hear-no-dub.status.error", message(t)).withStyle(ChatFormatting.RED);
					if (autoPick) toast(mc, Component.translatable("hear-no-dub.toast.error", message(t)));
				});
			}
		});
	}

	/**
	 * Looks up song lyrics on LRCLIB for the music app's song. The title can be the session's
	 * or one typed in the picker; artist, album and length come from the session.
	 * With autoPick, the best match is loaded when its length matches the song.
	 */
	private void searchLyrics(Minecraft mc, String title, boolean autoPick) {
		MediaBridge.Session s = session;
		if (s == null) return;
		busy = true;
		String shown = LrclibClient.cleanTitle(title);
		status = Component.translatable("hear-no-dub.status.searching_lyrics", shown).withStyle(ChatFormatting.GRAY);
		String forVideo = videoKey;
		// A typed title that isn't this song's: search without the session's album/length
		boolean sameSong = LrclibClient.cleanTitle(s.title).equalsIgnoreCase(shown);
		String artist = s.artist, album = sameSong ? s.album : null;
		long length = sameSong ? s.end : 0;
		HearNoDubConfig cfg = HearNoDubConfig.get();
		boolean useBl = cfg.useBetterLyrics, useLrc = cfg.useLrclib, useNe = cfg.useNetEase, useOvh = cfg.useLyricsOvh;
		worker.execute(() -> {
			List<Candidate> found = new ArrayList<>();
			List<String> counts = new ArrayList<>(), errors = new ArrayList<>();
			// One source at a time (LRCLIB asks for no parallel requests); best sources first
			lyricSource("Better Lyrics", useBl, found, counts, errors, () -> betterLyrics.search(title, artist, album, length));
			lyricSource("LRCLIB", useLrc, found, counts, errors, () -> lrclib.search(title, artist, album, length));
			lyricSource("NetEase", useNe, found, counts, errors, () -> netEase.search(title, artist, length));
			// plain lyrics only matter when nothing synced turned up, or when searching by hand
			boolean synced = found.stream().anyMatch(c -> LyricsProviders.rank(c) >= 2000);
			lyricSource("lyrics.ovh", useOvh && (!synced || !autoPick), found, counts, errors, () -> lyricsOvh.search(title, artist));

			List<Candidate> sorted = new ArrayList<>(found);
			sorted.sort((x, y) -> Integer.compare(LyricsProviders.rank(y), LyricsProviders.rank(x)));
			List<Entry> entries = new ArrayList<>();
			for (Candidate c : sorted) entries.add(entryOf(c));
			HearNoDubClient.LOGGER.info("Lyrics '{}' by '{}' -> {} ({}){}", shown, artist, entries.size(), String.join(", ", counts),
					errors.isEmpty() ? "" : " errors: " + errors);
			mc.execute(() -> {
				busy = false;
				if (!java.util.Objects.equals(forVideo, videoKey) && autoPick) return;
				results = entries;
				if (entries.isEmpty()) {
					status = errors.isEmpty()
							? Component.translatable("hear-no-dub.status.no_lyrics", shown).withStyle(ChatFormatting.YELLOW)
							: Component.translatable("hear-no-dub.status.error", String.join(" · ", errors)).withStyle(ChatFormatting.RED);
					if (autoPick) toast(mc, Component.translatable("hear-no-dub.toast.no_lyrics", shown));
					return;
				}
				String summary = String.join(" · ", counts) + (errors.isEmpty() ? "" : " · " + String.join(" · ", errors));
				status = Component.translatable("hear-no-dub.status.found_from", entries.size(), summary);
				if (!autoPick) return;
				Candidate best = sorted.getFirst();
				if (LrclibClient.isInstrumental(best)) {
					status = Component.translatable("hear-no-dub.status.instrumental", shown).withStyle(ChatFormatting.GRAY);
					return;
				}
				if (LyricsProviders.confident(best) || (length <= 0 && sorted.size() == 1)) {
					load(mc, entries.getFirst(), Mode.AUTO);
					if (LyricsProviders.rank(best) < 2000) toast(mc, Component.translatable("hear-no-dub.toast.plain_lyrics", shown));
				} else {
					status = Component.translatable("hear-no-dub.status.lyrics_unsure").withStyle(ChatFormatting.YELLOW);
					toast(mc, Component.translatable("hear-no-dub.toast.lyrics_unsure", keyName()));
				}
			});
		});
	}

	private interface LyricSearch {
		List<Candidate> run() throws Exception;
	}

	private static void lyricSource(String name, boolean enabled, List<Candidate> found, List<String> counts,
			List<String> errors, LyricSearch search) {
		if (!enabled) return;
		try {
			List<Candidate> r = search.run();
			found.addAll(r);
			counts.add(name + " " + r.size());
		} catch (Throwable t) {
			HearNoDubClient.LOGGER.warn("{} lyrics search failed: {}", name, t.toString());
			errors.add(name + ": " + message(t));
		}
	}

	// ---------------------------------------------------------------- demo song (Lyrics Studio)
	private long demoStart = -1;

	/** Plays the built-in demo song instead of the real subtitles (while the Lyrics Studio is open). */
	public void setDemo(boolean on) {
		demoStart = on ? System.currentTimeMillis() : -1;
	}

	public boolean demo() {
		return demoStart >= 0;
	}

	/** Starts the demo song over. */
	public void restartDemo() {
		if (demoStart >= 0) demoStart = System.currentTimeMillis();
	}

	private long demoTime() {
		return (System.currentTimeMillis() - demoStart) % DemoSong.LENGTH_MS;
	}

	/** True when the loaded track is song lyrics (shown with the lyrics display). */
	public boolean isLyricsTrack() {
		if (demoStart >= 0) return true;
		if (track == null || trackKey == null) return false;
		if (session != null && session.isMusicApp()) return true;
		String k = trackKey.toLowerCase(Locale.ROOT);
		return k.startsWith("lrclib:") || k.startsWith("betterlyrics:") || k.startsWith("netease:") || k.startsWith("lyricsovh:")
				|| k.endsWith(".lrc") || k.endsWith(".ttml");
	}

	private static int score(Candidate c, int season, int episode) {
		int s = 0;
		if (episode > 0 && c.episode() == episode) s += 1000;
		if (episode > 0 && c.episode() > 0 && c.episode() != episode) s -= 2000;
		if (season > 0 && c.season() == season) s += 300;
		if (season > 0 && c.season() > 0 && c.season() != season) s -= 500;
		if (c.hearingImpaired()) s -= 50;
		if (isStreamingRip(c)) s += 400;
		// SubDL first: its daily limits are far higher, so OpenSubtitles downloads are saved for when it's needed
		if (c.source().equals("subdl")) s += 150;
		s += Math.min(c.downloads(), 200_000) / 1000;
		return s;
	}

	/** Releases ripped from streaming sites are timed like the streams sites such as Miruro use. */
	private static final java.util.regex.Pattern STREAM_RIP = java.util.regex.Pattern.compile(
			"subsplease|horriblesubs|erai|crunchyroll|\\bcr\\b|amzn|\\bnf\\b|netflix|hidive|web[-. ]?dl|web[-. ]?rip|\\bweb\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	private static boolean isStreamingRip(Candidate c) {
		return STREAM_RIP.matcher(c.label()).find();
	}

	private static Entry entryOf(Candidate c) {
		StringBuilder detail = new StringBuilder("[").append(c.sourceName()).append("]");
		if (!c.title().isBlank()) detail.append("  ").append(c.title());
		if (c.season() > 0 || c.episode() > 0) {
			detail.append("  ");
			if (c.season() > 0) detail.append('S').append(c.season());
			if (c.episode() > 0) detail.append('E').append(c.episode());
		}
		if (c.downloads() > 0) detail.append("  \u2193").append(c.downloads());
		if (c.hearingImpaired()) detail.append("  [HI]");
		if (isStreamingRip(c)) detail.append("  [stream timing]");
		return new Entry(c.key(), c.label(), detail.toString(), c, null);
	}

	/** Local .srt/.ass/.ssa/.vtt/.lrc files in config/hear-no-dub/subs. */
	public List<Entry> localEntries() {
		List<Entry> out = new ArrayList<>();
		try {
			Files.createDirectories(LOCAL_DIR);
			try (Stream<Path> files = Files.list(LOCAL_DIR)) {
				files.filter(Files::isRegularFile)
						.filter(p -> {
							String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
							return Http.isSubtitleName(n);
						})
						.sorted()
						.forEach(p -> out.add(new Entry("local:" + p.getFileName(), p.getFileName().toString(),
								"local file", null, p)));
			}
		} catch (IOException e) {
			HearNoDubClient.LOGGER.warn("Could not list {}", LOCAL_DIR, e);
		}
		return out;
	}

	public void showLocal() {
		results = localEntries();
		status = results.isEmpty()
				? Component.translatable("hear-no-dub.status.no_local").withStyle(ChatFormatting.YELLOW)
				: Component.translatable("hear-no-dub.status.found", results.size());
	}

	/** Loads a subtitle chosen in the picker. */
	public void pick(Minecraft mc, Entry entry) {
		load(mc, entry, Mode.MANUAL);
	}

	private void load(Minecraft mc, Entry entry, Mode mode) {
		if (entry.key().equals(loadingKey)) return; // already downloading this one
		HearNoDubClient.LOGGER.info("Loading subtitles ({}): {} [{}]", mode, entry.label(), entry.key());
		busy = true;
		loadingKey = entry.key();
		status = Component.translatable("hear-no-dub.status.loading", entry.label()).withStyle(ChatFormatting.GRAY);
		String forVideo = videoKey;
		int episode = guess != null ? guess.episode() : 0;
		worker.execute(() -> {
			try {
				String text = entry.local() != null
						? Http.decode(Files.readAllBytes(entry.local()))
						: fetch(entry.remote(), episode);
				SubtitleTrack parsed = SubtitleParser.parse(text);
				HearNoDubClient.LOGGER.info("Parsed {} lines from {}", parsed.size(), entry.key());
				mc.execute(() -> {
					busy = false;
					loadingKey = null;
					if (parsed.isEmpty()) {
						status = Component.translatable("hear-no-dub.status.empty", entry.label()).withStyle(ChatFormatting.RED);
						return;
					}
					if (forVideo != null) {
						Choice c = new Choice();
						c.key = entry.key();
						c.label = entry.label();
						if (entry.local() != null) {
							c.localName = entry.local().getFileName().toString();
						} else {
							c.source = entry.remote().source();
							c.id = entry.remote().id();
							c.url = entry.remote().url();
							c.episode = episode > 0 ? episode : entry.remote().episode();
						}
						state.choices.put(forVideo, c);
						saveState();
					}
					if (!java.util.Objects.equals(forVideo, videoKey)) return;
					track = parsed;
					trackKey = entry.key();
					trackLabel = entry.label();
					Long saved = state.shifts.get(trackKey);
					if (saved == null && showKey() != null) {
						saved = state.showShifts.get(showKey());
						if (saved != null) {
							state.shifts.put(trackKey, saved);
							saveState();
						}
					}
					shiftMs = saved == null ? 0 : saved;
					status = Component.translatable("hear-no-dub.status.using", entry.label(), parsed.size())
							.withStyle(ChatFormatting.GREEN);
					if (mode == Mode.AUTO) {
						toast(mc, Component.translatable("hear-no-dub.toast.found", shortLabel(), keyName()));
					} else if (mode == Mode.SAVED) {
						toast(mc, Component.translatable("hear-no-dub.toast.saved", shortLabel()));
					}
					int left = openSubtitles.remaining();
					if (entry.remote() != null && entry.remote().source().equals("os") && left >= 0 && left <= 3) {
						toast(mc, Component.translatable("hear-no-dub.toast.quota_low", left));
					}
				});
			} catch (Throwable t) {
				HearNoDubClient.LOGGER.warn("Loading subtitles failed", t);
				mc.execute(() -> {
					busy = false;
					loadingKey = null;
					status = Component.translatable("hear-no-dub.status.error", message(t)).withStyle(ChatFormatting.RED);
					if (mode != Mode.MANUAL) toast(mc, Component.translatable("hear-no-dub.toast.error", message(t)));
				});
			}
		});
	}

	/** Cached copy if there is one, otherwise a download from the candidate's source (then cached). */
	private String fetch(Candidate c, int episode) throws IOException, InterruptedException {
		Path cached = CACHE_DIR.resolve(c.source() + "-" + c.id() + ".sub");
		if (Files.exists(cached)) return Files.readString(cached, StandardCharsets.UTF_8);
		if (c.source().equals("os")) {
			Path legacy = CACHE_DIR.resolve(c.id() + ".srt"); // v0.1.0 cache name
			if (Files.exists(legacy)) return Files.readString(legacy, StandardCharsets.UTF_8);
		}
		String text = switch (c.source()) {
			case "os" -> openSubtitles.download(c);
			case "subdl" -> subdl.download(c, episode);
			case "jimaku" -> jimaku.download(c, episode);
			case "lrclib" -> lrclib.download(c, session != null ? session.end : 0);
			case "betterlyrics" -> betterLyrics.download(c);
			case "netease" -> netEase.download(c);
			case "lyricsovh" -> lyricsOvh.download(c, session != null ? session.end : 0);
			default -> throw new IOException("Unknown source " + c.source());
		};
		Files.createDirectories(CACHE_DIR);
		Files.writeString(cached, text, StandardCharsets.UTF_8);
		return text;
	}

	/** "OpenSubtitles: N downloads left today", or null when unknown. */
	public Component quotaText() {
		int left = openSubtitles.remaining();
		if (left < 0) return null;
		return Component.translatable("hear-no-dub.picker.quota", left)
				.withStyle(left <= 3 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY);
	}

	private String shortLabel() {
		if (guess != null && !guess.query().isBlank()) return guess.describe();
		return trackLabel == null ? "" : trackLabel;
	}

	private static String keyName() {
		return HearNoDubClient.pickerKey == null ? "K" : HearNoDubClient.pickerKey.getTranslatedKeyMessage().getString();
	}

	private void clearTrack() {
		track = null;
		trackKey = null;
		trackLabel = null;
		shiftMs = 0;
	}

	public void unload() {
		clearTrack();
		if (videoKey != null) {
			state.choices.remove(videoKey);
			saveState();
		}
		status = Component.translatable("hear-no-dub.status.unloaded");
	}

	// ================================================================ sync

	public void nudge(long deltaMs) {
		if (trackKey == null) return;
		setShift(shiftMs + deltaMs);
	}

	public void resetShift() {
		if (trackKey == null) return;
		setShift(0);
	}

	private void setShift(long ms) {
		shiftMs = ms;
		if (shiftMs == 0) state.shifts.remove(trackKey);
		else state.shifts.put(trackKey, shiftMs);
		String show = showKey();
		if (show != null) state.showShifts.put(show, shiftMs);
		saveState();
	}

	private String showKey() {
		return guess == null || guess.query().isBlank() ? null : guess.query().toLowerCase(Locale.ROOT);
	}

	/** Reaction time: by the time you click, the line has been playing for about this long. */
	private static final long REACTION_MS = 600;

	/** "I just heard this line": shifts the subtitles so that line starts (about) now. */
	public void syncTo(SubtitleTrack.Cue cue) {
		MediaBridge.Session s = session;
		if (trackKey == null || s == null) return;
		long video = s.livePositionMs(System.currentTimeMillis());
		long reaction = s.isPlaying() ? REACTION_MS : 0;
		setShift(cue.startMs() + reaction - video);
	}

	/** Lines from about 40 s before to 20 s after the current (shifted) time, for syncing by line. */
	public List<SubtitleTrack.Cue> cuesAround() {
		SubtitleTrack t = track;
		MediaBridge.Session s = session;
		if (t == null || s == null) return List.of();
		long now = s.livePositionMs(System.currentTimeMillis()) + shiftMs;
		return t.between(now - 40_000, now + 20_000);
	}

	/** Lines starting between two subtitle times (for danmaku), in start order. */
	public List<SubtitleTrack.Cue> cuesStartedBetween(long fromMs, long toMs) {
		SubtitleTrack t = track;
		return t == null ? List.of() : t.between(fromMs, toMs);
	}

	/** Every line of the loaded subtitles (for searching), or empty. */
	public List<SubtitleTrack.Cue> allCues() {
		if (demoStart >= 0) return DemoSong.track().all();
		SubtitleTrack t = track;
		return t == null ? List.of() : t.all();
	}

	/** How long before a line the video starts when you jump to it. */
	private static final long JUMP_LEAD_MS = 500;

	/** Jumps the video to where this line is spoken (taking the sync offset into account). */
	public void jumpTo(Minecraft mc, SubtitleTrack.Cue cue) {
		if (track == null) return;
		long target = Math.max(0, cue.startMs() - shiftMs - JUMP_LEAD_MS);
		dev.k3ytel.hearnodub.client.media.Playback.seekTo(mc, target,
				Component.translatable("hear-no-dub.osd.jumped"));
	}

	/** Current time in the subtitle file (video time + offset), or -1. */
	public long subtitleTimeMs() {
		if (demoStart >= 0) return demoTime();
		MediaBridge.Session s = session;
		if (track == null || s == null) return -1;
		return s.livePositionMs(System.currentTimeMillis()) + shiftMs;
	}

	public Component offsetMessage() {
		if (track == null) return Component.translatable("hear-no-dub.msg.no_subs").withStyle(ChatFormatting.GRAY);
		return offsetText();
	}

	public Component offsetText() {
		if (shiftMs == 0) return Component.translatable("hear-no-dub.msg.in_sync");
		String secs = String.format(Locale.ROOT, "%.2f", Math.abs(shiftMs) / 1000.0);
		return Component.translatable(shiftMs > 0 ? "hear-no-dub.msg.earlier" : "hear-no-dub.msg.later", secs);
	}

	// ================================================================ what's on screen

	/** Cues to show right now (empty when nothing is loaded or playing). */
	public List<SubtitleTrack.Cue> activeCues() {
		if (demoStart >= 0) return DemoSong.track().activeAt(demoTime());
		SubtitleTrack t = track;
		MediaBridge.Session s = session;
		if (t == null || s == null) return List.of();
		return t.activeAt(s.livePositionMs(System.currentTimeMillis()) + shiftMs);
	}

	/** The next line after the current time (for the preview), or null. */
	public SubtitleTrack.Cue nextCue() {
		if (demoStart >= 0) return DemoSong.track().next(demoTime());
		SubtitleTrack t = track;
		long now = subtitleTimeMs();
		if (t == null || now < 0) return null;
		return t.next(now);
	}

	/** The last n lines that have started (for "what did they say?"). */
	public List<SubtitleTrack.Cue> recentCues(int n) {
		SubtitleTrack t = track;
		long now = subtitleTimeMs();
		if (t == null || now < 0) return List.of();
		return t.recent(now, n);
	}

	private static final long HISTORY_MS = 7000;
	private long historyUntil;

	/** Shows the history panel for a few seconds, or hides it if it's already up. */
	public void toggleHistory() {
		historyUntil = historyVisible() ? 0 : System.currentTimeMillis() + HISTORY_MS;
	}

	public boolean historyVisible() {
		return System.currentTimeMillis() < historyUntil;
	}

	// ================================================================ getters for the picker

	public MediaBridge.Session session() { return session; }
	public TitleGuesser.Guess guess() { return guess; }
	public List<Entry> results() { return results; }
	public Component status() { return status; }
	public boolean busy() { return busy; }
	public String trackKey() { return trackKey; }
	public String trackLabel() { return trackLabel; }
	public boolean hasTrack() { return track != null; }
	/** Sync offset in ms (positive = subtitles earlier). */
	public long shiftMs() { return shiftMs; }

	/** Cycles which app's media is followed (when several are open). */
	public void switchSession() {
		List<MediaBridge.Session> all = MediaBridge.get().latest().sessions;
		if (all.size() < 2) return;
		int idx = 0;
		for (int i = 0; i < all.size(); i++) {
			if (session != null && all.get(i).app.equals(session.app)) idx = i;
		}
		MediaBridge.Session next = all.get((idx + 1) % all.size());
		pinnedApp = next.app;
		session = next;
	}

	public int sessionCount() {
		return MediaBridge.get().latest().sessions.size();
	}

	// ================================================================ helpers

	private static void toast(Minecraft mc, Component msg) {
		if (!HearNoDubConfig.get().showToasts) return;
		SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, Component.translatable("hear-no-dub.name"), msg);
	}

	private static String message(Throwable e) {
		String m = e.getMessage();
		if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
		return m.length() > 80 ? m.substring(0, 80) + "…" : m;
	}

	public void loadState() {
		if (!Files.exists(STATE_FILE)) return;
		try (Reader r = Files.newBufferedReader(STATE_FILE)) {
			State s = GSON.fromJson(r, State.class);
			if (s != null) {
				if (s.choices == null) s.choices = new HashMap<>();
				if (s.shifts == null) s.shifts = new HashMap<>();
				if (s.showShifts == null) s.showShifts = new HashMap<>();
				state = s;
			}
		} catch (Exception e) {
			HearNoDubClient.LOGGER.warn("Could not read {}", STATE_FILE, e);
		}
	}

	private void saveState() {
		try {
			Files.createDirectories(STATE_FILE.getParent());
			try (Writer w = Files.newBufferedWriter(STATE_FILE)) {
				GSON.toJson(state, w);
			}
		} catch (IOException e) {
			HearNoDubClient.LOGGER.warn("Could not save {}", STATE_FILE, e);
		}
	}
}

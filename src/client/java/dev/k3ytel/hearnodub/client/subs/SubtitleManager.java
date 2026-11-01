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

	/** A subtitle the user can pick: from OpenSubtitles, or a local file. */
	public record Entry(String key, String label, String detail, OpenSubtitlesClient.Result remote, Path local) {}

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
		long fileId;
		String localName;
	}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HearNoDub-Worker");
		t.setDaemon(true);
		return t;
	});
	private final OpenSubtitlesClient client = new OpenSubtitlesClient();
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
		if (!videos.isEmpty()) sessions = videos;
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
		HearNoDubClient.LOGGER.info("New video: app='{}' title='{}' artist='{}' album='{}' end={}ms tab='{}' -> guess '{}'",
				s.app, s.title, s.artist, s.album, s.end, guess.tabTitle(), guess.describe());

		Choice saved = state.choices.get(videoKey);
		if (saved != null) {
			Entry entry = saved.localName != null
					? new Entry(saved.key, saved.label, "", null, LOCAL_DIR.resolve(saved.localName))
					: new Entry(saved.key, saved.label, "", remoteStub(saved), null);
			load(mc, entry, Mode.SAVED);
			return;
		}
		if (!HearNoDubConfig.get().autoSearch) {
			status = Component.translatable("hear-no-dub.status.idle");
			return;
		}
		if (!HearNoDubConfig.get().hasApiKey()) {
			status = Component.translatable("hear-no-dub.status.no_key").withStyle(ChatFormatting.YELLOW);
			if (!warnedNoKey) {
				warnedNoKey = true;
				toast(mc, Component.translatable("hear-no-dub.toast.no_key"));
			}
			return;
		}
		search(mc, guess.query(), guess.season(), guess.episode(), true);
	}

	private static OpenSubtitlesClient.Result remoteStub(Choice c) {
		return new OpenSubtitlesClient.Result(c.fileId, c.label, c.label, 0, c.label, 0, 0, false);
	}

	// ================================================================ search & load

	private enum Mode { AUTO, MANUAL, SAVED }

	/**
	 * Finds subtitles. Steps: look the show up on TVmaze (IMDb ID + episode list), work out
	 * season/episode from the episode name if they're unknown, then search OpenSubtitles.
	 * With autoPick, the best result is loaded, but only if it's clearly the right episode.
	 */
	public void search(Minecraft mc, String query, int season, int episode, boolean autoPick) {
		if (query == null || query.isBlank()) return;
		if (!HearNoDubConfig.get().hasApiKey()) {
			status = Component.translatable("hear-no-dub.status.no_key").withStyle(ChatFormatting.YELLOW);
			return;
		}
		busy = true;
		status = Component.translatable("hear-no-dub.status.searching", query).withStyle(ChatFormatting.GRAY);
		String forVideo = videoKey;
		String episodeTitle = session != null ? session.title : "";
		String tab = guess != null ? guess.tabTitle() : "";
		worker.execute(() -> {
			try {
				int s = season, e = episode;
				EpisodeLookup.Show show = lookup.findShow(query);
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

				List<OpenSubtitlesClient.Result> found = List.of();
				if (imdb > 0) found = client.search(query, s, e, imdb);
				if (found.isEmpty()) found = client.search(query, s, e, 0);
				if (found.isEmpty() && (s > 0 || e > 0)) found = client.search(query, 0, 0, 0);

				final int fs = s, fe = e;
				List<OpenSubtitlesClient.Result> sorted = new ArrayList<>(found);
				sorted.sort(Comparator.comparingInt((OpenSubtitlesClient.Result r) -> score(r, fs, fe)).reversed());
				List<Entry> entries = new ArrayList<>();
				for (OpenSubtitlesClient.Result r : sorted) entries.add(entryOf(r));
				HearNoDubClient.LOGGER.info("Search '{}' S{}E{} imdb={} -> {} results{}", query, fs, fe, imdb, entries.size(),
						sorted.isEmpty() ? "" : ", best: " + sorted.getFirst().label());

				mc.execute(() -> {
					busy = false;
					if (!java.util.Objects.equals(forVideo, videoKey) && autoPick) return;
					results = entries;
					if (autoPick) guess = new TitleGuesser.Guess(query, fs, fe, tab);
					if (entries.isEmpty()) {
						status = Component.translatable("hear-no-dub.status.none", query).withStyle(ChatFormatting.YELLOW);
						if (autoPick) toast(mc, Component.translatable("hear-no-dub.toast.none", query));
						return;
					}
					status = Component.translatable("hear-no-dub.status.found", entries.size());
					if (!autoPick) return;
					OpenSubtitlesClient.Result best = sorted.getFirst();
					boolean sure = fe > 0 && best.episode() == fe && (fs == 0 || best.season() == 0 || best.season() == fs);
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

	private static int score(OpenSubtitlesClient.Result r, int season, int episode) {
		int s = 0;
		if (episode > 0 && r.episode() == episode) s += 1000;
		if (episode > 0 && r.episode() > 0 && r.episode() != episode) s -= 2000;
		if (season > 0 && r.season() == season) s += 300;
		if (r.hearingImpaired()) s -= 50;
		if (isStreamingRip(r)) s += 400;
		s += Math.min(r.downloads(), 200_000) / 1000;
		return s;
	}

	/** Releases ripped from streaming sites are timed like the streams sites such as Miruro use. */
	private static final java.util.regex.Pattern STREAM_RIP = java.util.regex.Pattern.compile(
			"subsplease|horriblesubs|erai|crunchyroll|\\bcr\\b|amzn|\\bnf\\b|netflix|hidive|web[-. ]?dl|web[-. ]?rip|\\bweb\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	private static boolean isStreamingRip(OpenSubtitlesClient.Result r) {
		return STREAM_RIP.matcher(r.label()).find() || (r.fileName() != null && STREAM_RIP.matcher(r.fileName()).find());
	}

	private static Entry entryOf(OpenSubtitlesClient.Result r) {
		StringBuilder detail = new StringBuilder();
		if (!r.title().isBlank()) detail.append(r.title());
		if (r.season() > 0 || r.episode() > 0) {
			detail.append("  ");
			if (r.season() > 0) detail.append('S').append(r.season());
			if (r.episode() > 0) detail.append('E').append(r.episode());
		}
		detail.append("  ↓").append(r.downloads());
		if (r.hearingImpaired()) detail.append("  [HI]");
		if (isStreamingRip(r)) detail.append("  [stream timing]");
		return new Entry("os:" + r.fileId(), r.label(), detail.toString(), r, null);
	}

	/** Local .srt/.ass/.ssa/.vtt files in config/hear-no-dub/subs. */
	public List<Entry> localEntries() {
		List<Entry> out = new ArrayList<>();
		try {
			Files.createDirectories(LOCAL_DIR);
			try (Stream<Path> files = Files.list(LOCAL_DIR)) {
				files.filter(Files::isRegularFile)
						.filter(p -> {
							String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
							return n.endsWith(".srt") || n.endsWith(".ass") || n.endsWith(".ssa") || n.endsWith(".vtt");
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
		HearNoDubClient.LOGGER.info("Loading subtitles ({}): {} [{}]", mode, entry.label(), entry.key());
		busy = true;
		status = Component.translatable("hear-no-dub.status.loading", entry.label()).withStyle(ChatFormatting.GRAY);
		String forVideo = videoKey;
		worker.execute(() -> {
			try {
				String text;
				if (entry.local() != null) {
					text = Files.readString(entry.local(), StandardCharsets.UTF_8);
				} else {
					Path cached = CACHE_DIR.resolve(entry.remote().fileId() + ".srt");
					if (Files.exists(cached)) {
						text = Files.readString(cached, StandardCharsets.UTF_8);
					} else {
						text = client.download(entry.remote().fileId());
						Files.createDirectories(CACHE_DIR);
						Files.writeString(cached, text, StandardCharsets.UTF_8);
					}
				}
				SubtitleTrack parsed = SubtitleParser.parse(text);
				HearNoDubClient.LOGGER.info("Parsed {} lines from {}", parsed.size(), entry.key());
				mc.execute(() -> {
					busy = false;
					if (parsed.isEmpty()) {
						status = Component.translatable("hear-no-dub.status.empty", entry.label()).withStyle(ChatFormatting.RED);
						return;
					}
					if (forVideo != null) {
						Choice c = new Choice();
						c.key = entry.key();
						c.label = entry.label();
						if (entry.local() != null) c.localName = entry.local().getFileName().toString();
						else c.fileId = entry.remote().fileId();
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
				});
			} catch (Throwable t) {
				HearNoDubClient.LOGGER.warn("Loading subtitles failed", t);
				mc.execute(() -> {
					busy = false;
					status = Component.translatable("hear-no-dub.status.error", message(t)).withStyle(ChatFormatting.RED);
					if (mode != Mode.MANUAL) toast(mc, Component.translatable("hear-no-dub.toast.error", message(t)));
				});
			}
		});
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

	/** Current time in the subtitle file (video time + offset), or -1. */
	public long subtitleTimeMs() {
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
		SubtitleTrack t = track;
		MediaBridge.Session s = session;
		if (t == null || s == null) return List.of();
		return t.activeAt(s.livePositionMs(System.currentTimeMillis()) + shiftMs);
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

package dev.k3ytel.hearnodub.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All settings, stored as JSON in config/hear-no-dub.json.
 * Fields are public so Gson and the config screen can use them directly.
 */
public final class HearNoDubConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("hear-no-dub.json");

	/** Folder for the bridge script, cache, saved choices and local subtitle files. */
	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("hear-no-dub");

	private static HearNoDubConfig instance = new HearNoDubConfig();

	// ---------------------------------------------------------------- general
	public boolean enabled = true;
	/** Search and download subtitles automatically when a new video starts. */
	public boolean autoSearch = true;
	public boolean showToasts = true;
	/** How far one press of [ or ] moves the subtitles. */
	public int nudgeMs = 250;

	// ---------------------------------------------------------------- sources
	public boolean useSubdl = true;
	public String subdlKey = "";

	public boolean useOpenSubtitles = true;
	/** OpenSubtitles API key. */
	public String apiKey = "";
	public String username = "";
	public String password = "";

	public boolean useJimaku = false;
	public String jimakuKey = "";
	/** Also offer Jimaku's Japanese files (off: only files that look English). */
	public boolean jimakuJapanese = false;

	// ---------------------------------------------------------------- look
	public float textScale = 1.0f;
	/** Background opacity in percent (0 = no background). */
	public int backgroundOpacity = 50;
	/** Max line width in GUI pixels (before scaling) before text wraps. */
	public int maxLineWidth = 320;
	/** Danmaku: seconds a line takes to cross the screen. */
	public int danmakuSeconds = 8;
	public float danmakuScale = 1.5f;
	/** Danmaku: how much of the screen (from the top) the lanes use, in percent. */
	public int danmakuArea = 60;
	public int danmakuOpacity = 100;
	/** Center of the caption box, as a fraction of the screen width. */
	public float posX = 0.5f;
	/** Bottom edge of the caption box, as a fraction of the screen height. */
	public float posY = 0.80f;

	// ---------------------------------------------------------------- 3D (in-world) text
	// ---------------------------------------------------------------- display modes
	public ScreenMode screenMode = ScreenMode.HUD;
	public WorldMode worldMode = WorldMode.NONE;
	/** v0.2 setting ("HUD", "WORLD", "BOTH"); migrated to screenMode/worldMode on load. */
	public String displayMode;

	// ---------------------------------------------------------------- style
	public StylePreset stylePreset = StylePreset.BOX;
	public int customTextColor = 0xFFFFFFFF;
	public boolean customOutline = true;
	public int customOutlineColor = 0xFF000000;
	public boolean customShadow = false;
	public boolean customGlow = false;
	public int customGlowColor = 0xFFFFFFFF;
	public boolean customBox = false;
	public int customBoxColor = 0xFF000000;
	/** Keep italics/bold from the subtitle file. */
	public boolean keepFormatting = true;
	/** Give each named speaker their own color. */
	public boolean speakerColors = true;
	/** Reveal words one by one over each line's duration. */
	public boolean karaoke = false;
	public Animation animation = Animation.FADE_POP;
	/** Show the upcoming line faintly under the current one. */
	public boolean nextLinePreview = false;

	// ---------------------------------------------------------------- pop-in + bubble
	public float popinDistance = 4.0f;
	/** Max sideways angle (degrees) a pop-in line can spawn from the crosshair. */
	public int popinSpreadX = 25;
	/** Max up/down angle (degrees). */
	public int popinSpreadY = 12;
	public float popinScale = 1.0f;
	public BubbleTarget bubbleTarget = BubbleTarget.AUTO;
	/** In third person (F5), show speech bubbles instead of the normal 3D mode. */
	public boolean thirdPersonBubble = false;
	public int bubbleRange = 16;

	// ---------------------------------------------------------------- video control
	/** Pause the video when the Esc menu opens, resume when it closes. */
	public boolean autoPause = true;
	/** Pause the video when you're in danger, resume when safe. */
	public boolean dangerPause = true;
	public DangerLevel dangerLevel = DangerLevel.RELAXED;
	/** Below this much health (percent), any damage counts as danger. */
	public int dangerHealthPercent = 40;
	/** Losing this many hearts within 3 seconds counts as danger. */
	public int dangerBurstHearts = 4;
	/** How close hostile mobs must be to count, in blocks. */
	public int dangerRange = 12;
	/** Seconds of safety before the video plays again. */
	public int dangerResumeSec = 4;
	/** How far the skip back / forward keys jump, in seconds. */
	public int seekStepSec = 10;
	/** How much one press of the volume keys changes the browser's volume, in percent. */
	public int volumeStep = 10;
	/** Show the small popup at the top of the screen after seeking or changing the volume. */
	public boolean mediaPopup = true;

	// ---------------------------------------------------------------- video in the game (experimental)
	/** Show the browser's video in the corner of the HUD (V). */
	public boolean videoEnabled = false;
	/** Frames per second the capture helper aims for. */
	public int videoFps = 24;
	/** Max picture height in pixels (360, 480, 720, 1080). */
	public int videoQuality = 480;
	public VideoMode videoMode = VideoMode.PICTURE_IN_PICTURE;
	/** Width of the picture-in-picture box, in percent of the screen width. */
	public int videoSize = 35;
	/** Where the picture-in-picture box sits: 0 = left/top edge, 1 = right/bottom edge of the free space. */
	public float videoPosX = 1f;
	public float videoPosY = 0f;
	/** Picture-in-picture opacity in percent. */
	public int videoOpacity = 100;
	/** Faded background opacity in percent. */
	public int videoBackgroundOpacity = 35;
	/** Width in blocks of the floating video screen. */
	public float videoWorldWidth = 2.0f;
	/** Distance in blocks of the floating screen in front of you. */
	public float videoWorldDistance = 3.0f;
	/** Height of the floating screen relative to eye level, in blocks. */
	public float videoWorldHeight = 0.0f;
	/** Width in blocks of the screen above a mob. */
	public float videoMobWidth = 1.2f;
	/** Gap in blocks between a mob's head and its screen. */
	public float videoMobLift = 0.35f;
	/** "Floating, to the side": how far to the right (negative = left) of your view, in degrees. */
	public int videoSideAngle = 35;
	/** "Floating, to the side": how far up (negative = down) from your view, in degrees. */
	public int videoSideTilt = 5;
	/** Floating screens: roughly how many seconds they take to catch up with your view (0 = glued). */
	public float videoFollowDelay = 0.8f;
	/** How far in front of you a TV placed in the air goes. */
	public float videoTvDistance = 3.0f;
	/** Use a hand-picked video area instead of auto-detection (set in the "Fix video area" screen). */
	public boolean videoCropManual = false;
	/** The hand-picked area in window pixels, and the window size it was picked for. */
	public int videoCropX, videoCropY, videoCropW, videoCropH, videoCropWinW, videoCropWinH;
	/** Width in blocks of a newly placed TV. */
	public float videoTvWidth = 3.0f;
	/** When placing the TV on a wall, make it as big as the flat wall around it allows. */
	public boolean videoTvFitWall = true;
	/** Glow the screen's colours onto its surroundings, in percent (0 = off). */
	public int videoGlow = 70;
	/** Show 3D subtitles under the video screen (above it for a mob screen) instead of their own 3D mode. */
	public boolean videoSubsAttach = true;
	/** Lower the browser's volume the farther you are from the video screen. */
	public boolean videoDistanceVolume = false;
	/** Distance in blocks at which the sound is gone. */
	public int videoVolumeRange = 24;

	// ---------------------------------------------------------------- skip intro / ending
	/** Look up opening/ending times (AniSkip) and offer to skip them. */
	public boolean skipTimes = true;
	public boolean autoSkipOpening = false;
	public boolean autoSkipEnding = false;
	/** Seconds the "Skipping opening in…" countdown runs before an automatic skip. */
	public int autoSkipDelaySec = 3;
	/** How far the skip key jumps when no opening/ending is playing (typical anime opening length). */
	public int skipFallbackSec = 85;
	public WorldAnchor worldAnchor = WorldAnchor.FOLLOW;
	/** Distance in blocks in front of you (follow mode, and where "place here" puts it). */
	public float worldDistance = 4.0f;
	/** Height offset in blocks from eye level (negative = lower). */
	public float worldHeight = -0.5f;
	public float worldScale = 1.0f;
	/** How quickly follow mode catches up with your view (1 = instantly). */
	public float worldFollowSpeed = 0.08f; // v0.2/0.3 setting, replaced by worldFollowDelay
	/** Follow mode: roughly how many seconds the text takes to catch up with your view (0 = glued). */
	public float worldFollowDelay = 0.8f;
	/** "New spot each line": spawn at a random spot in view (Pop-in spread) instead of straight ahead. */
	public boolean perLineRandom = false;
	/** How 3D text turns to face you. */
	public Billboard billboard = Billboard.TOWARD_ME;
	/** Draw through blocks instead of being hidden behind them. */
	public boolean worldSeeThrough = false;
	public int worldBackgroundOpacity = 40;

	// ---------------------------------------------------------------- access

	public static HearNoDubConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				HearNoDubConfig loaded = GSON.fromJson(reader, HearNoDubConfig.class);
				if (loaded != null) {
					instance = loaded;
					instance.clamp();
					save(); // writes back migrated values
					return;
				}
			} catch (Exception e) {
				HearNoDubClient.LOGGER.error("Could not read {}, using defaults", PATH, e);
			}
		}
		instance = new HearNoDubConfig();
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			HearNoDubClient.LOGGER.error("Could not save {}", PATH, e);
		}
	}

	/** Old or hand-edited files may hold missing or out-of-range values. */
	private void clamp() {
		if (apiKey == null) apiKey = "";
		if (username == null) username = "";
		if (password == null) password = "";
		if (subdlKey == null) subdlKey = "";
		if (jimakuKey == null) jimakuKey = "";
		if (displayMode != null) {
			switch (displayMode.toUpperCase(java.util.Locale.ROOT)) {
				case "WORLD" -> { screenMode = ScreenMode.NONE; worldMode = WorldMode.FLOATING; }
				case "BOTH" -> { screenMode = ScreenMode.HUD; worldMode = WorldMode.FLOATING; }
				default -> { }
			}
			displayMode = null;
		}
		if (screenMode == null) screenMode = ScreenMode.HUD;
		if (worldMode == null) worldMode = WorldMode.NONE;
		if (stylePreset == null) stylePreset = StylePreset.BOX;
		if (animation == null) animation = Animation.FADE_POP;
		if (bubbleTarget == null) bubbleTarget = BubbleTarget.AUTO;
		popinDistance = Math.max(1f, Math.min(16f, popinDistance));
		popinSpreadX = Math.max(5, Math.min(60, popinSpreadX));
		popinSpreadY = Math.max(0, Math.min(40, popinSpreadY));
		popinScale = Math.max(0.25f, Math.min(4f, popinScale));
		bubbleRange = Math.max(4, Math.min(64, bubbleRange));
		if (worldAnchor == null) worldAnchor = WorldAnchor.FOLLOW;
		if (billboard == null) billboard = Billboard.TOWARD_ME;
		worldDistance = Math.max(1f, Math.min(16f, worldDistance));
		worldHeight = Math.max(-3f, Math.min(3f, worldHeight));
		worldScale = Math.max(0.25f, Math.min(4f, worldScale));
		worldFollowSpeed = Math.max(0.01f, Math.min(1f, worldFollowSpeed));
		worldFollowDelay = Math.max(0f, Math.min(3f, worldFollowDelay));
		worldBackgroundOpacity = Math.max(0, Math.min(100, worldBackgroundOpacity));
		textScale = Math.max(0.5f, Math.min(3.0f, textScale));
		backgroundOpacity = Math.max(0, Math.min(100, backgroundOpacity));
		maxLineWidth = Math.max(100, Math.min(800, maxLineWidth));
		nudgeMs = Math.max(50, Math.min(2000, nudgeMs));
		danmakuSeconds = Math.max(3, Math.min(20, danmakuSeconds == 0 ? 8 : danmakuSeconds));
		danmakuScale = Math.max(0.5f, Math.min(4f, danmakuScale == 0 ? 1.5f : danmakuScale));
		danmakuArea = Math.max(15, Math.min(100, danmakuArea == 0 ? 60 : danmakuArea));
		danmakuOpacity = Math.max(10, Math.min(100, danmakuOpacity == 0 ? 100 : danmakuOpacity));
		dangerRange = Math.max(4, Math.min(32, dangerRange == 0 ? 12 : dangerRange));
		if (dangerLevel == null) dangerLevel = DangerLevel.RELAXED;
		dangerHealthPercent = Math.max(10, Math.min(90, dangerHealthPercent == 0 ? 40 : dangerHealthPercent));
		dangerBurstHearts = Math.max(1, Math.min(10, dangerBurstHearts == 0 ? 4 : dangerBurstHearts));
		dangerResumeSec = Math.max(1, Math.min(30, dangerResumeSec == 0 ? 4 : dangerResumeSec));
		seekStepSec = Math.max(1, Math.min(120, seekStepSec == 0 ? 10 : seekStepSec));
		volumeStep = Math.max(1, Math.min(50, volumeStep == 0 ? 10 : volumeStep));
		autoSkipDelaySec = Math.max(0, Math.min(10, autoSkipDelaySec));
		skipFallbackSec = Math.max(10, Math.min(300, skipFallbackSec == 0 ? 85 : skipFallbackSec));
		videoFps = Math.max(5, Math.min(30, videoFps == 0 ? 24 : videoFps));
		videoQuality = Math.max(240, Math.min(1080, videoQuality == 0 ? 480 : videoQuality));
		videoSize = Math.max(10, Math.min(90, videoSize == 0 ? 35 : videoSize));
		if (videoMode == null) videoMode = VideoMode.PICTURE_IN_PICTURE;
		videoWorldWidth = Math.max(0.5f, Math.min(8f, videoWorldWidth == 0 ? 2f : videoWorldWidth));
		videoWorldDistance = Math.max(1f, Math.min(16f, videoWorldDistance == 0 ? 3f : videoWorldDistance));
		videoWorldHeight = Math.max(-3f, Math.min(3f, videoWorldHeight));
		videoMobWidth = Math.max(0.3f, Math.min(4f, videoMobWidth == 0 ? 1.2f : videoMobWidth));
		videoTvWidth = Math.max(0.5f, Math.min(16f, videoTvWidth == 0 ? 3f : videoTvWidth));
		videoMobLift = Math.max(0f, Math.min(3f, videoMobLift));
		videoSideAngle = Math.max(-80, Math.min(80, videoSideAngle));
		videoSideTilt = Math.max(-40, Math.min(40, videoSideTilt));
		videoFollowDelay = Math.max(0f, Math.min(3f, videoFollowDelay));
		videoTvDistance = Math.max(1f, Math.min(16f, videoTvDistance == 0 ? 3f : videoTvDistance));
		videoVolumeRange = Math.max(6, Math.min(96, videoVolumeRange == 0 ? 24 : videoVolumeRange));
		videoGlow = Math.max(0, Math.min(100, videoGlow));
		videoPosX = Math.max(0f, Math.min(1f, videoPosX));
		videoPosY = Math.max(0f, Math.min(1f, videoPosY));
		videoOpacity = Math.max(10, Math.min(100, videoOpacity == 0 ? 100 : videoOpacity));
		videoBackgroundOpacity = Math.max(5, Math.min(100, videoBackgroundOpacity == 0 ? 35 : videoBackgroundOpacity));
		posX = Math.max(0f, Math.min(1f, posX));
		posY = Math.max(0f, Math.min(1f, posY));
	}

	public boolean osReady() {
		return useOpenSubtitles && apiKey != null && !apiKey.isBlank();
	}

	public boolean subdlReady() {
		return useSubdl && subdlKey != null && !subdlKey.isBlank();
	}

	public boolean jimakuReady() {
		return useJimaku && jimakuKey != null && !jimakuKey.isBlank();
	}

	/** At least one online source is switched on and has its key. */
	public boolean anySourceReady() {
		return osReady() || subdlReady() || jimakuReady();
	}
}

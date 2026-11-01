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

	// ---------------------------------------------------------------- OpenSubtitles
	public String apiKey = "";
	public String username = "";
	public String password = "";

	// ---------------------------------------------------------------- look
	public float textScale = 1.0f;
	/** Background opacity in percent (0 = no background). */
	public int backgroundOpacity = 50;
	/** Max line width in GUI pixels (before scaling) before text wraps. */
	public int maxLineWidth = 320;
	/** Center of the caption box, as a fraction of the screen width. */
	public float posX = 0.5f;
	/** Bottom edge of the caption box, as a fraction of the screen height. */
	public float posY = 0.80f;

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
		textScale = Math.max(0.5f, Math.min(3.0f, textScale));
		backgroundOpacity = Math.max(0, Math.min(100, backgroundOpacity));
		maxLineWidth = Math.max(100, Math.min(800, maxLineWidth));
		nudgeMs = Math.max(50, Math.min(2000, nudgeMs));
		posX = Math.max(0f, Math.min(1f, posX));
		posY = Math.max(0f, Math.min(1f, posY));
	}

	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}
}

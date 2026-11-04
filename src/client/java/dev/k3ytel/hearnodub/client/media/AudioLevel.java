package dev.k3ytel.hearnodub.client.media;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * How loud the music is right now, so lyrics can move with it. The media bridge measures the
 * playing app's loudness (Windows' per-app peak meter, ~60 times a second) into audio.bin;
 * this reads it each frame and turns it into a smooth level, a slow average, and beats.
 */
public final class AudioLevel {
	private AudioLevel() {}

	private static final Path FILE = HearNoDubConfig.DIR.resolve("audio.bin");
	private static MappedByteBuffer map;
	private static String watching = "";
	private static long lastAsk;

	private static float level, average = 0.2f, pulse;
	// bass / mids / treble of the speakers (loopback), each with its own running average
	private static final float[] band = new float[3], bandAvg = {0.02f, 0.02f, 0.01f};
	private static float kick, shimmer;
	private static long lastKick;
	private static int mapped;
	private static long lastBeat, lastUpdate;
	private static int lastSeq = -1;
	private static long beats;

	/** Call every client tick: tells the bridge which app to listen to. */
	public static void tick() {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		MediaBridge.Session s = SubtitleManager.get().session();
		String want = "";
		if (cfg.enabled && cfg.letterAudioReact && s != null && s.isPlaying()) {
			String p = s.processName();
			if (p != null) want = p;
		}
		long now = System.currentTimeMillis();
		if ((!want.equals(watching) || (!want.isEmpty() && now - lastAsk > 30_000)) && now - lastAsk > 1000) {
			lastAsk = now;
			if (MediaBridge.get().meter(want.isEmpty() ? "off" : want)) watching = want;
		}
	}

	private static boolean demo;

	/** A made-up 120 BPM beat for the Lyrics Studio preview (no real music needed). */
	public static void setDemo(boolean on) {
		demo = on;
	}

	private static void demoUpdate() {
		long ms = System.currentTimeMillis();
		float phase = (ms % 500) / 500f;
		pulse = (float) Math.exp(-phase * 6);
		kick = ms % 1000 < 500 ? pulse : 0;
		shimmer = (float) Math.max(0, Math.sin(ms * 0.0126)) * 0.6f;
		level = 0.4f + 0.3f * pulse;
		average = 0.45f;
		long b = ms / 500;
		if (b != beats) beats = b;
	}

	/** Reads the latest loudness (once per frame is plenty). */
	private static void update() {
		if (demo) {
			demoUpdate();
			return;
		}
		long now = System.nanoTime();
		if (now - lastUpdate < 4_000_000L) return;
		float dt = lastUpdate == 0 ? 0.016f : Math.min(0.1f, (now - lastUpdate) / 1e9f);
		lastUpdate = now;
		float peak = 0;
		boolean fresh = false;
		try {
			long size = map == null || mapped < 32 ? (Files.exists(FILE) ? Files.size(FILE) : 0) : 32;
			if ((map == null && size >= 20) || (mapped < 32 && size >= 32)) {
				int len = size >= 32 ? 32 : 20;
				try (FileChannel ch = FileChannel.open(FILE, StandardOpenOption.READ)) {
					map = ch.map(FileChannel.MapMode.READ_ONLY, 0, len);
				}
				map.order(ByteOrder.LITTLE_ENDIAN);
				mapped = len;
			}
			if (map != null && map.getInt(0) == 0x41444E48) {
				int seq = map.getInt(4);
				long at = map.getLong(12);
				fresh = Math.abs(System.currentTimeMillis() - at) < 1000;
				if (fresh) peak = Math.max(0, Math.min(1, map.getFloat(8)));
				if (seq != lastSeq) lastSeq = seq;
			}
		} catch (IOException | RuntimeException e) {
			map = null;
		}
		if (!fresh || watching.isEmpty()) peak = 0;
		// fast attack, slower release
		float k = peak > level ? 1 - (float) Math.exp(-dt / 0.02f) : 1 - (float) Math.exp(-dt / 0.12f);
		level += (peak - level) * k;
		average += (level - average) * (1 - (float) Math.exp(-dt / 1.5f));
		// a beat: a quick jump well above the recent average
		long ms = System.currentTimeMillis();
		if (peak > 0.05f && peak > average * 1.45f + 0.03f && ms - lastBeat > 180) {
			lastBeat = ms;
			beats++;
			pulse = Math.min(1, 0.5f + (peak - average) * 2.5f);
		}
		pulse *= (float) Math.exp(-dt / 0.14f);
		// frequency bands
		for (int i = 0; i < 3; i++) {
			float v = fresh && mapped >= 32 ? Math.max(0, map.getFloat(20 + i * 4)) : 0;
			float kb = v > band[i] ? 1 - (float) Math.exp(-dt / 0.015f) : 1 - (float) Math.exp(-dt / 0.1f);
			band[i] += (v - band[i]) * kb;
			bandAvg[i] += (band[i] - bandAvg[i]) * (1 - (float) Math.exp(-dt / 2f));
		}
		if (band[0] > bandAvg[0] * 1.6f + 0.004f && ms - lastKick > 200) {
			lastKick = ms;
			kick = 1;
		}
		kick *= (float) Math.exp(-dt / 0.12f);
		float tr = bandAvg[2] < 1e-4f ? 0 : band[2] / bandAvg[2];
		shimmer += (Math.max(0, Math.min(1, (tr - 1) * 0.8f)) - shimmer) * (1 - (float) Math.exp(-dt / 0.05f));
	}

	/** Smoothed loudness 0..1. */
	public static float level() {
		update();
		return level;
	}

	/** Loudness compared with the song's usual loudness (about 1 = normal, 2 = loud part). */
	public static float relative() {
		update();
		return average < 0.01f ? 0 : Math.min(3, level / average);
	}

	/** 1 right on a beat, fading to 0 within ~0.3 s. */
	public static float pulse() {
		update();
		return pulse;
	}

	/** 1 on a bass kick, fading quickly (from the speakers' bass band; 0 without band data). */
	public static float kick() {
		update();
		return kick;
	}

	/** 0..1, high when the treble (hi-hats, cymbals) is louder than usual. */
	public static float shimmer() {
		update();
		return shimmer;
	}

	/** Bass compared with its usual level (about 1 = normal). */
	public static float bass() {
		update();
		return bandAvg[0] < 1e-4f ? 0 : Math.min(3, band[0] / bandAvg[0]);
	}

	/** True when bass/treble numbers are coming in. */
	public static boolean bands() {
		update();
		return demo || mapped >= 32 && bandAvg[0] > 1e-4f;
	}

	/** Counts beats so far (changes on every beat). */
	public static long beats() {
		update();
		return beats;
	}

	/** True when real loudness numbers are coming in. */
	public static boolean active() {
		update();
		return demo || !watching.isEmpty() && map != null && average > 0.005f;
	}
}

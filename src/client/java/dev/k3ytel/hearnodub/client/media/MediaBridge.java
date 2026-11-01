package dev.k3ytel.hearnodub.client.media;

import com.google.gson.Gson;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Runs the hidden PowerShell helper (bridge.ps1) and keeps the latest snapshot of
 * what Windows' media controls report. Windows-only; on other systems it stays idle.
 */
public final class MediaBridge {
	private static final MediaBridge INSTANCE = new MediaBridge();
	private static final Gson GSON = new Gson();
	private static final int MAX_RESTARTS = 5;

	public enum Status { STARTING, RUNNING, UNSUPPORTED, FAILED }

	/** One media session (one app that plays something), as reported by the bridge. */
	public static final class Session {
		public String app = "";
		public String title = "";
		public String artist = "";
		public String album = "";
		/** Position snapshot in ms, taken at {@link #updated} (unix ms). */
		public long pos;
		public long end;
		public long updated;
		public String status = "";

		public boolean isPlaying() {
			return "Playing".equalsIgnoreCase(status);
		}

		/**
		 * Current position: the snapshot plus the time since it was taken, while playing.
		 * Right after the mod asks for a seek, the target is used until Windows reports the new
		 * position (otherwise a second quick press would start from the old spot).
		 */
		public long livePositionMs(long nowMs) {
			Seek seek = INSTANCE.lastSeek;
			if (seek != null && seek.app.equals(app) && updated < seek.sentAt && nowMs - seek.sentAt < SEEK_HOLD_MS) {
				long p = seek.targetMs + (isPlaying() ? Math.max(0, nowMs - seek.sentAt) : 0);
				if (end > 0) p = Math.min(p, end);
				return Math.max(0, p);
			}
			long p = pos;
			if (isPlaying() && updated > 0) p += Math.max(0, nowMs - updated);
			if (end > 0) p = Math.min(p, end);
			return Math.max(0, p);
		}

		public boolean isMusicApp() {
			String a = app == null ? "" : app.toLowerCase(Locale.ROOT);
			return a.contains("spotify") || a.contains("zunemusic") || a.contains("applemusic")
					|| a.contains("deezer") || a.contains("tidal");
		}

		/** Windows process name that plays this app's sound ("firefox", "chrome", ...), or null. */
		public String processName() {
			return switch (appName()) {
				case "Firefox" -> "firefox";
				case "Edge" -> "msedge";
				case "Chrome" -> "chrome";
				case "Brave" -> "brave";
				case "Opera" -> "opera";
				case "Spotify" -> "Spotify";
				case "VLC" -> "vlc";
				default -> null;
			};
		}

		/** Short readable name of the app ("Firefox", "Chrome", ...). */
		public String appName() {
			String a = app == null ? "" : app.toLowerCase(Locale.ROOT);
			if (a.equals("308046b0af4a39cb") || a.contains("firefox")) return "Firefox";
			if (a.contains("msedge") || a.contains("edge")) return "Edge";
			if (a.contains("chrome")) return "Chrome";
			if (a.contains("brave")) return "Brave";
			if (a.contains("opera")) return "Opera";
			if (a.contains("spotify")) return "Spotify";
			if (a.contains("vlc")) return "VLC";
			return app == null || app.isEmpty() ? "?" : app;
		}
	}

	/** An open browser window and its active tab title. */
	public static final class Window {
		public String proc = "";
		public String title = "";
	}

	/** Result of the last volume change: level 0..1, or -1 if it didn't work. */
	public static final class Volume {
		public String proc = "";
		public double level = -1;
		public int seq;
	}

	public static final class Snapshot {
		public List<Session> sessions = List.of();
		public List<Window> windows = List.of();
		public Volume vol;
		public String error;
	}

	/** A seek the mod just asked for. */
	private record Seek(String app, long targetMs, long sentAt) {}

	private static final long SEEK_HOLD_MS = 3000;

	private volatile Snapshot latest = new Snapshot();
	private volatile Volume lastVolume;
	private volatile Seek lastSeek;
	private volatile Status status = Status.STARTING;
	private volatile String lastError = "";
	private volatile boolean stopping = false;
	private Process process;
	private int restarts = 0;

	private MediaBridge() {}

	public static MediaBridge get() {
		return INSTANCE;
	}

	public Snapshot latest() {
		return latest;
	}

	public Status status() {
		return status;
	}

	/** The last volume change the bridge reported, or null. */
	public Volume lastVolume() {
		return lastVolume;
	}

	/** Asks the app to jump to a position (ms). Returns false when the bridge isn't running. */
	public boolean seek(Session s, long targetMs) {
		if (s == null) return false;
		long target = Math.max(0, s.end > 0 ? Math.min(targetMs, s.end - 500) : targetMs);
		if (!writeCommand("seek " + target + " " + s.app)) return false;
		lastSeek = new Seek(s.app, target, System.currentTimeMillis());
		return true;
	}

	/** Changes the app's own volume (Windows volume mixer) by delta (-1..1). */
	public boolean changeVolume(Session s, double delta) {
		String proc = s == null ? null : s.processName();
		if (proc == null) return false;
		return writeCommand(String.format(Locale.ROOT, "vol %.2f %s", delta, proc));
	}

	public String lastError() {
		return lastError;
	}

	public void start() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (!os.contains("win")) {
			status = Status.UNSUPPORTED;
			HearNoDubClient.LOGGER.info("Not on Windows ({}); media detection is disabled", os);
			return;
		}
		Thread t = new Thread(this::runLoop, "HearNoDub-MediaBridge");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Asks the bridge to "toggle", "pause" or "play" the given app's media. Commands go through
	 * small files the bridge checks every 100 ms. Returns false when the bridge isn't running.
	 */
	public boolean sendCommand(String action, String app) {
		if (app == null || app.isEmpty()) return false;
		return writeCommand(action + " " + app);
	}

	private boolean writeCommand(String command) {
		if (status != Status.RUNNING) return false;
		try {
			Path dir = HearNoDubConfig.DIR.resolve("cmd");
			Files.createDirectories(dir);
			String name = System.nanoTime() + "";
			Path tmp = dir.resolve(name + ".tmp");
			Files.writeString(tmp, command, StandardCharsets.UTF_8);
			Files.move(tmp, dir.resolve(name + ".cmd"), StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			HearNoDubClient.LOGGER.warn("Could not send '{}' to the media bridge", command, e);
			return false;
		}
	}

	public void stop() {
		stopping = true;
		Process p = process;
		if (p != null) p.destroy();
	}

	private void runLoop() {
		Path script;
		try {
			script = writeScript();
		} catch (IOException e) {
			fail("Could not write bridge script: " + e.getMessage());
			return;
		}

		while (!stopping) {
			try {
				runOnce(script);
			} catch (Exception e) {
				HearNoDubClient.LOGGER.warn("Media bridge stopped", e);
				lastError = e.getMessage() == null ? e.toString() : e.getMessage();
			}
			if (stopping) return;
			latest = new Snapshot();
			if (++restarts > MAX_RESTARTS) {
				fail("Media bridge keeps crashing: " + lastError);
				return;
			}
			status = Status.STARTING;
			sleep(5000);
		}
	}

	private void runOnce(Path script) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(
				"powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
				"-WindowStyle", "Hidden", "-File", script.toString(),
				"-ParentPid", String.valueOf(ProcessHandle.current().pid()));
		pb.redirectErrorStream(false);
		process = pb.start();
		drainStderr(process.getErrorStream());

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.startsWith("﻿")) line = line.substring(1);
				if (!line.startsWith("{")) continue;
				Snapshot snap;
				try {
					snap = GSON.fromJson(line, Snapshot.class);
				} catch (Exception e) {
					continue;
				}
				if (snap == null) continue;
				if (snap.error != null) {
					lastError = snap.error;
					HearNoDubClient.LOGGER.warn("Media bridge error: {}", snap.error);
					continue;
				}
				if (snap.vol != null) lastVolume = snap.vol;
				if (snap.sessions == null && snap.windows == null) continue; // volume-only line
				if (snap.sessions == null) snap.sessions = List.of();
				if (snap.windows == null) snap.windows = List.of();
				latest = snap;
				if (status != Status.RUNNING) {
					status = Status.RUNNING;
					restarts = 0;
					HearNoDubClient.LOGGER.info("Media bridge running");
				}
			}
		}
		process.waitFor();
		if (!stopping) HearNoDubClient.LOGGER.warn("Media bridge exited with code {}", process.exitValue());
	}

	private static void drainStderr(InputStream err) {
		Thread t = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (!line.isBlank()) HearNoDubClient.LOGGER.warn("[bridge] {}", line);
				}
			} catch (IOException ignored) {
			}
		}, "HearNoDub-BridgeErr");
		t.setDaemon(true);
		t.start();
	}

	/** Copies the bundled script to config/hear-no-dub/bridge.ps1 (overwritten every launch). */
	private static Path writeScript() throws IOException {
		Path target = HearNoDubConfig.DIR.resolve("bridge.ps1");
		Files.createDirectories(target.getParent());
		try (InputStream in = MediaBridge.class.getResourceAsStream("/hear-no-dub/bridge.ps1")) {
			if (in == null) throw new IOException("bridge.ps1 missing from the mod jar");
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		// Old commands left over from a crash must not play/pause anything now
		Path cmd = target.getParent().resolve("cmd");
		if (Files.isDirectory(cmd)) {
			try (var files = Files.list(cmd)) {
				files.forEach(f -> {
					try {
						Files.deleteIfExists(f);
					} catch (IOException ignored) {
					}
				});
			}
		}
		return target;
	}

	private void fail(String msg) {
		lastError = msg;
		status = Status.FAILED;
		HearNoDubClient.LOGGER.error(msg);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

package dev.k3ytel.hearnodub.client.video;

import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Runs the hidden capture helper (capture.ps1) while "Show video" is on. The helper writes
 * frames into config/hear-no-dub/video.bin, which both sides map into memory; this class
 * reads the newest complete frame out of it.
 */
public final class VideoCapture {
	private static final VideoCapture INSTANCE = new VideoCapture();

	/** Same layout as in capture.ps1. */
	static final int HEADER = 96;
	static final int MAX_W = 2560, MAX_H = 1080;
	private static final int MAGIC = 0x56444E48;
	private static final long FILE_SIZE = HEADER + (long) MAX_W * MAX_H * 4;

	public enum State { OFF, STARTING, RUNNING, IDLE, NO_WINDOW, MINIMIZED, STALLED, UNSUPPORTED, FAILED }

	/** Nothing playing for this long: the helper stops (the last picture stays) until it plays again. */
	private static final long IDLE_AFTER_MS = 30_000;

	private Process process;
	private MappedByteBuffer buffer;
	private FileChannel channel;
	private volatile String lastError = "";
	private String runningWith = "";
	private int lastHeartbeat = -1;
	private long heartbeatChangedAt;
	private long startedAt;
	private int crashes;
	private boolean idle;
	private long lastPlayingAt = System.currentTimeMillis();
	private boolean editingCrop;

	private VideoCapture() {}

	public static VideoCapture get() {
		return INSTANCE;
	}

	/** Called every client tick: starts, stops or restarts the helper to match the settings. */
	public void tick() {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (!cfg.videoEnabled || !isWindows()) {
			if (process != null) stop();
			lastPlayingAt = System.currentTimeMillis();
			idle = false;
			return;
		}
		String settings = settingsKey(cfg);

		// Stop grabbing frames while nothing plays (paused for a while, browser closed)
		MediaBridge.Session s = SubtitleManager.get().session();
		long now = System.currentTimeMillis();
		if ((s != null && s.isPlaying()) || editingCrop) lastPlayingAt = now;
		boolean shouldIdle = now - lastPlayingAt > IDLE_AFTER_MS;
		if (shouldIdle != idle) {
			idle = shouldIdle;
			if (idle && process != null) {
				HearNoDubClient.LOGGER.info("Nothing playing for a while, pausing video capture");
				stop();
			}
		}
		if (idle) return;
		writeControl(cfg);

		if (process != null && !process.isAlive()) {
			HearNoDubClient.LOGGER.warn("Video capture helper exited ({}): {}", process.exitValue(), lastError);
			process = null;
			if (++crashes > 3) return; // stays FAILED until turned off and on
		}
		if (process == null && crashes <= 3) start(settings, cfg);
		else if (process != null && !settings.equals(runningWith)) {
			stop();
			start(settings, cfg);
		}
	}

	/** Turned on with the key or in the settings: forget earlier failures. */
	public void resetFailures() {
		crashes = 0;
		lastError = "";
	}

	public State state() {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (!cfg.videoEnabled) return State.OFF;
		if (!isWindows()) return State.UNSUPPORTED;
		if (crashes > 3) return State.FAILED;
		if (idle) return State.IDLE;
		if (process == null || buffer == null) return State.STARTING;
		long now = System.currentTimeMillis();
		int hb = buffer.getInt(48);
		if (hb != lastHeartbeat) {
			lastHeartbeat = hb;
			heartbeatChangedAt = now;
		}
		if (hb == 0) return now - startedAt > 15000 ? State.STALLED : State.STARTING;
		if (now - heartbeatChangedAt > 3000) return State.STALLED;
		return switch (buffer.getInt(16)) {
			case 1 -> State.NO_WINDOW;
			case 2 -> State.MINIMIZED;
			default -> buffer.getInt(0) == MAGIC ? State.RUNNING : State.STARTING;
		};
	}

	public String lastError() {
		return lastError;
	}

	/** The process name of the browser we capture ("firefox", ...). */
	public String browserName() {
		MediaBridge.Session s = SubtitleManager.get().session();
		String app = s == null ? "Firefox" : s.appName();
		return app;
	}

	/** Average colour of the current picture, 0xRRGGBB (black when unknown). */
	public int averageColor() {
		return buffer == null ? 0 : buffer.getInt(88) & 0xFFFFFF;
	}

	/** Average time the helper needs per frame, in ms (0 when unknown). */
	public double captureMs() {
		return buffer == null ? 0 : buffer.getInt(44) / 10.0;
	}

	/**
	 * Copies the newest complete frame into dst if it's newer than lastSeq.
	 * Returns its sequence number, or lastSeq when there's nothing new (or it changed while copying).
	 */
	int readFrame(int lastSeq, FrameSink dst) {
		MappedByteBuffer b = buffer;
		if (b == null || b.getInt(0) != MAGIC) return lastSeq;
		int seq = b.getInt(4);
		if (seq == lastSeq || (seq & 1) == 1) return lastSeq;
		VarHandle.acquireFence();
		int w = b.getInt(8), h = b.getInt(12);
		if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) return lastSeq;
		ByteBuffer pixels = b.slice(HEADER, w * h * 4);
		if (!dst.accept(w, h, pixels)) return lastSeq;
		VarHandle.acquireFence();
		return b.getInt(4) == seq ? seq : lastSeq;
	}

	interface FrameSink {
		/** Copies the RGBA pixels; returns false if it couldn't. */
		boolean accept(int width, int height, ByteBuffer rgba);
	}

	/** The picture can be shown in this state (the last frame stays up while idle or restarting). */
	public static boolean showable(State s) {
		return s == State.RUNNING || s == State.IDLE || s == State.STARTING;
	}

	// ---------------------------------------------------------------- crop control

	/** While the crop screen is open, the helper sends the whole window instead of the crop. */
	public void setEditingCrop(boolean editing) {
		editingCrop = editing;
		if (editing) lastPlayingAt = System.currentTimeMillis();
		writeControl(HearNoDubConfig.get());
	}

	/** Window size the helper sees, {width, height}, or null. */
	public int[] windowSize() {
		if (buffer == null || buffer.getInt(20) <= 0) return null;
		return new int[]{buffer.getInt(20), buffer.getInt(24)};
	}

	/** What auto-detection found, {x, y, w, h} in window pixels, or null. */
	public int[] autoCrop() {
		if (buffer == null || buffer.getInt(80) <= 0) return null;
		return new int[]{buffer.getInt(72), buffer.getInt(76), buffer.getInt(80), buffer.getInt(84)};
	}

	/** True when the frames are currently the whole window (crop screen). */
	public boolean showingWholeWindow() {
		return buffer != null && buffer.getInt(28) == 0 && buffer.getInt(32) == 0
				&& buffer.getInt(36) == buffer.getInt(20) && buffer.getInt(40) == buffer.getInt(24);
	}

	/** Tells the helper which part of the window to send: auto, the saved manual area, or all of it. */
	private void writeControl(HearNoDubConfig cfg) {
		MappedByteBuffer b = buffer;
		if (b == null) return;
		int mode = 0;
		if (editingCrop) {
			mode = 2;
		} else if (cfg.videoCropManual && cfg.videoCropW > 0) {
			// A manual area only fits the window size it was made for
			int[] win = windowSize();
			if (win == null || (win[0] == cfg.videoCropWinW && win[1] == cfg.videoCropWinH)) {
				mode = 1;
				b.putInt(56, cfg.videoCropX);
				b.putInt(60, cfg.videoCropY);
				b.putInt(64, cfg.videoCropW);
				b.putInt(68, cfg.videoCropH);
			}
		}
		b.putInt(52, mode);
	}

	/** Size of the video area in the window, for the status line ("1280×720 of 1920×1140"). */
	public String cropInfo() {
		if (buffer == null) return "";
		return String.format(Locale.ROOT, "%d×%d of %d×%d", buffer.getInt(36), buffer.getInt(40), buffer.getInt(20), buffer.getInt(24));
	}

	// ---------------------------------------------------------------- process

	private void start(String settings, HearNoDubConfig cfg) {
		try {
			Path dir = HearNoDubConfig.DIR;
			Files.createDirectories(dir);
			Path script = dir.resolve("capture.ps1");
			try (InputStream in = VideoCapture.class.getResourceAsStream("/hear-no-dub/capture.ps1")) {
				if (in == null) throw new IOException("capture.ps1 missing from the mod jar");
				Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
			}
			Path file = dir.resolve("video.bin");
			openBuffer(file);
			writeControl(cfg);

			MediaBridge.Session s = SubtitleManager.get().session();
			String proc = s != null && s.processName() != null ? s.processName() : "firefox";
			ProcessBuilder pb = new ProcessBuilder(
					"powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
					"-WindowStyle", "Hidden", "-File", script.toString(),
					"-ParentPid", String.valueOf(ProcessHandle.current().pid()),
					"-Path", file.toString(),
					"-Proc", proc,
					"-Fps", String.valueOf(cfg.videoFps),
					"-MaxHeight", String.valueOf(cfg.videoQuality));
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			process = pb.start();
			drainStderr(process.getErrorStream());
			runningWith = settings;
			startedAt = System.currentTimeMillis();
			lastHeartbeat = -1;
			HearNoDubClient.LOGGER.info("Video capture started ({} at {} fps, up to {}p)", proc, cfg.videoFps, cfg.videoQuality);
		} catch (IOException e) {
			lastError = e.getMessage();
			crashes = 99;
			HearNoDubClient.LOGGER.error("Could not start video capture", e);
		}
	}

	public void stop() {
		Process p = process;
		process = null;
		if (p != null) {
			p.descendants().forEach(ProcessHandle::destroy);
			p.destroy();
			HearNoDubClient.LOGGER.info("Video capture stopped");
		}
	}

	private void openBuffer(Path file) throws IOException {
		if (buffer != null) {
			// Clear the header so an old frame isn't shown as new
			for (int i = 0; i < HEADER; i += 4) buffer.putInt(i, 0);
			return;
		}
		RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
		if (raf.length() != FILE_SIZE) raf.setLength(FILE_SIZE);
		channel = raf.getChannel();
		MappedByteBuffer b = channel.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
		b.order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < HEADER; i += 4) b.putInt(i, 0);
		buffer = b;
	}

	private void drainStderr(InputStream err) {
		Thread t = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.isBlank()) continue;
					lastError = line.trim();
					HearNoDubClient.LOGGER.warn("[capture] {}", line);
				}
			} catch (IOException ignored) {
			}
		}, "HearNoDub-CaptureErr");
		t.setDaemon(true);
		t.start();
	}

	private static String settingsKey(HearNoDubConfig cfg) {
		MediaBridge.Session s = SubtitleManager.get().session();
		String proc = s != null && s.processName() != null ? s.processName() : "firefox";
		return proc + "|" + cfg.videoFps + "|" + cfg.videoQuality;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}

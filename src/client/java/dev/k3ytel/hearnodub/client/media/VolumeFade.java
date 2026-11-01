package dev.k3ytel.hearnodub.client.media;

import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.hud.MediaOsd;
import dev.k3ytel.hearnodub.client.hud.VideoWorld;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * "Fade with distance": while a video screen is in the world, the browser's volume goes down
 * the farther you are from it, like a real speaker. The volume you had (the "full" volume) is
 * read when the fade starts and put back when it stops; the volume keys change that full volume.
 */
public final class VolumeFade {
	private VolumeFade() {}

	/** Full volume within this many blocks of the screen's edge. */
	private static final double NEAR = 3;
	private static final long MIN_GAP_MS = 120;

	private static boolean active;
	private static boolean waitingForBase;
	private static int requestSeq;
	private static double base = -1;
	private static double lastSent = -1;
	private static long lastSentAt;
	private static MediaBridge.Session session;
	private static String lastWhy = "?";
	private static long askedAt;

	public static void tick(Minecraft mc) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		MediaBridge.Session s = SubtitleManager.get().session();
		Vec3 screen = VideoWorld.screenCenter();
		String why = !cfg.videoDistanceVolume ? "setting is off"
				: !cfg.videoEnabled ? "video is off"
				: !cfg.videoMode.inWorld() ? "video mode " + cfg.videoMode + " isn't in the world"
				: screen == null ? "no screen drawn"
				: mc.player == null ? "not in a world"
				: s == null ? "nothing playing"
				: s.processName() == null ? "can't control " + s.appName() + "'s volume"
				: "";
		if (!why.equals(lastWhy)) {
			lastWhy = why;
			HearNoDubClient.LOGGER.debug("Volume fade: {}", why.isEmpty() ? "starting" : "not active (" + why + ")");
		}
		if (!why.isEmpty()) {
			restore();
			return;
		}
		session = s;
		if (!active) {
			// Ask for the current level first ("change by 0" reports it back)
			active = true;
			waitingForBase = true;
			MediaBridge.Volume v = MediaBridge.get().lastVolume();
			requestSeq = v == null ? -1 : v.seq;
			boolean sent = MediaBridge.get().changeVolume(s, 0);
			askedAt = System.currentTimeMillis();
			HearNoDubClient.LOGGER.debug("Volume fade: asking the bridge for {}'s volume (sent: {})", s.processName(), sent);
			return;
		}
		if (waitingForBase) {
			MediaBridge.Volume v = MediaBridge.get().lastVolume();
			if (v == null || v.seq == requestSeq) {
				if (System.currentTimeMillis() - askedAt > 3000) {
					// No answer: assume full volume rather than doing nothing
					HearNoDubClient.LOGGER.warn("Volume fade: no answer from the bridge, assuming 100%");
					waitingForBase = false;
					base = 1.0;
				}
				return;
			}
			waitingForBase = false;
			base = v.level >= 0 ? v.level : 1.0;
			HearNoDubClient.LOGGER.info("Volume fade on: full volume is {}% ({}){}", Math.round(base * 100), s.processName(),
					v.level < 0 ? " (couldn't read it, is the browser playing sound?)" : "");
		}

		double d = VideoWorld.distanceToScreen(mc.player.getEyePosition(1f));
		if (d < 0) return;
		double range = Math.max(NEAR + 1, cfg.videoVolumeRange);
		double f = Math.max(0, Math.min(1, 1 - (d - NEAR) / (range - NEAR)));
		double target = Math.round(base * f * f * 100) / 100.0; // squared: falls off faster, like real sound
		long now = System.currentTimeMillis();
		if (Math.abs(target - lastSent) >= 0.01 && now - lastSentAt >= MIN_GAP_MS) {
			if (Math.abs(target - lastSent) >= 0.1 || target == 0 || target == base) {
				HearNoDubClient.LOGGER.debug("Volume fade: {} blocks away -> {}%", Math.round(d), Math.round(target * 100));
			}
			if (MediaBridge.get().setVolume(s, target)) {
				lastSent = target;
				lastSentAt = now;
			}
		}
	}

	/** Volume keys while fading: change the full volume. Returns false when not fading. */
	public static boolean adjustBase(MediaBridge.Session s, double delta) {
		if (!active || waitingForBase) return false;
		base = Math.max(0, Math.min(1, Math.round((base + delta) * 100) / 100.0));
		lastSent = -1; // send the new level on the next tick
		MediaOsd.showVolume(s.appName(), base);
		return true;
	}

	/** Puts the full volume back. Returns true if a command was sent. */
	public static boolean restore() {
		boolean sent = false;
		if (active && !waitingForBase && base >= 0 && session != null) {
			sent = MediaBridge.get().setVolume(session, base);
			HearNoDubClient.LOGGER.info("Volume fade off: volume back to {}%", Math.round(base * 100));
		}
		active = false;
		waitingForBase = false;
		base = -1;
		lastSent = -1;
		return sent;
	}
}

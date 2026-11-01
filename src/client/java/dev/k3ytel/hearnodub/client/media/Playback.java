package dev.k3ytel.hearnodub.client.media;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.hud.MediaOsd;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Seeking and volume for the video the mod is following, with the on-screen popup. */
public final class Playback {
	private Playback() {}

	/** Jumps forward (positive) or back (negative) from the current position. */
	public static void skipBy(Minecraft mc, long deltaMs, Component label) {
		MediaBridge.Session s = SubtitleManager.get().session();
		if (s == null) {
			nothingPlaying(mc);
			return;
		}
		seekTo(mc, s.livePositionMs(System.currentTimeMillis()) + deltaMs, label);
	}

	/** Jumps to a position in the video (ms). */
	public static void seekTo(Minecraft mc, long targetMs, Component label) {
		MediaBridge.Session s = SubtitleManager.get().session();
		if (s == null || !MediaBridge.get().seek(s, targetMs)) {
			nothingPlaying(mc);
			return;
		}
		MediaOsd.showSeek(label);
	}

	/** Changes the browser's own volume by one step (direction +1 or -1). */
	public static void changeVolume(Minecraft mc, int direction) {
		MediaBridge.Session s = SubtitleManager.get().session();
		if (s == null) {
			nothingPlaying(mc);
			return;
		}
		if (s.processName() == null) {
			MediaOsd.showText(Component.translatable("hear-no-dub.osd.volume_unsupported", s.appName()));
			return;
		}
		double step = HearNoDubConfig.get().volumeStep / 100.0;
		if (VolumeFade.adjustBase(s, direction * step)) return; // fading with distance: change the full volume instead
		if (MediaBridge.get().changeVolume(s, direction * step)) MediaOsd.expectVolume(s.appName());
		else nothingPlaying(mc);
	}

	private static void nothingPlaying(Minecraft mc) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.translatable("hear-no-dub.msg.nothing_playing").withStyle(ChatFormatting.GRAY));
		}
	}
}

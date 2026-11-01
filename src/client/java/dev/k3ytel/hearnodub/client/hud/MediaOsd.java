package dev.k3ytel.hearnodub.client.hud;

import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import dev.k3ytel.hearnodub.client.subs.IntroSkipper;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The small "now playing" popup at the top of the screen (after seeking or changing the
 * volume), and the "Skip opening [O]" prompt in the bottom right corner.
 */
public class MediaOsd implements HudElement {
	private enum Kind { SEEK, VOLUME, TEXT }

	private static final long SHOW_MS = 1800;
	private static final long VOLUME_WAIT_MS = 2500;
	private static final int BOX_W = 170;

	private static Kind kind;
	private static Component label = Component.empty();
	private static long until;
	private static double volumeLevel;

	private static long volumeWaitUntil;
	private static String volumeApp = "";
	private static int lastVolumeSeq = -1;

	public static void showSeek(Component text) {
		show(Kind.SEEK, text);
	}

	public static void showText(Component text) {
		show(Kind.TEXT, text);
	}

	/** A volume change was sent; the popup appears when the bridge reports the new level. */
	public static void expectVolume(String appName) {
		volumeWaitUntil = System.currentTimeMillis() + VOLUME_WAIT_MS;
		volumeApp = appName;
	}

	private static void show(Kind k, Component text) {
		kind = k;
		label = text;
		until = System.currentTimeMillis() + SHOW_MS;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		pollVolume();
		if (mc.gui.hud.isHidden()) return;
		Font font = mc.font;
		long now = System.currentTimeMillis();
		if (HearNoDubConfig.get().mediaPopup && now < until && kind != null) drawPopup(g, font, now);
		drawSkipPrompt(g, font);
	}

	private static void pollVolume() {
		MediaBridge.Volume v = MediaBridge.get().lastVolume();
		if (v == null || v.seq == lastVolumeSeq) return;
		lastVolumeSeq = v.seq;
		if (System.currentTimeMillis() > volumeWaitUntil) return; // old result from before this launch's presses
		volumeWaitUntil = 0;
		if (v.level < 0) {
			show(Kind.TEXT, Component.translatable("hear-no-dub.osd.volume_failed", volumeApp));
		} else {
			volumeLevel = v.level;
			show(Kind.VOLUME, Component.translatable("hear-no-dub.osd.volume", volumeApp));
		}
	}

	// ---------------------------------------------------------------- top popup

	private static void drawPopup(GuiGraphicsExtractor g, Font font, long now) {
		int x = (g.guiWidth() - BOX_W) / 2;
		int y = 6;
		boolean bar = kind != Kind.TEXT;
		int h = bar ? 20 : 13;
		g.fill(x, y, x + BOX_W, y + h, 0xC0101014);

		String left = font.plainSubstrByWidth(label.getString(), BOX_W - 70);
		g.text(font, left, x + 5, y + 3, 0xFFFFFFFF, true);

		if (kind == Kind.SEEK) {
			MediaBridge.Session s = SubtitleManager.get().session();
			if (s == null) return;
			long pos = s.livePositionMs(now);
			String right = time(pos) + (s.end > 0 ? " / " + time(s.end) : "");
			g.text(font, right, x + BOX_W - 5 - font.width(right), y + 3, 0xFFBBBBBB, true);
			drawBar(g, x + 5, y + 14, BOX_W - 10, s.end > 0 ? pos / (double) s.end : 0);
		} else if (kind == Kind.VOLUME) {
			String right = Math.round(volumeLevel * 100) + "%";
			g.text(font, right, x + BOX_W - 5 - font.width(right), y + 3, 0xFFBBBBBB, true);
			drawBar(g, x + 5, y + 14, BOX_W - 10, volumeLevel);
		}
	}

	private static void drawBar(GuiGraphicsExtractor g, int x, int y, int w, double fraction) {
		double f = Math.max(0, Math.min(1, fraction));
		g.fill(x, y, x + w, y + 2, 0x60FFFFFF);
		g.fill(x, y, x + (int) Math.round(w * f), y + 2, 0xFFFFFFFF);
	}

	// ---------------------------------------------------------------- skip prompt

	private static void drawSkipPrompt(GuiGraphicsExtractor g, Font font) {
		IntroSkipper.Prompt p = IntroSkipper.get().prompt();
		if (p == null) return;
		String key = HearNoDubClient.skipKey == null ? "O" : HearNoDubClient.skipKey.getTranslatedKeyMessage().getString();
		Component text = p.auto()
				? Component.translatable("hear-no-dub.skip.auto", p.interval().kind().display(), p.secondsLeft(), key)
				: Component.translatable("hear-no-dub.skip.prompt", p.interval().kind().display(), key);
		int w = font.width(text) + 12;
		int h = p.auto() ? 18 : 15;
		int x = g.guiWidth() - w - 8;
		int y = g.guiHeight() - 62;
		g.fill(x, y, x + w, y + h, 0xC0101014);
		g.outline(x, y, w, h, 0x80FFFFFF);
		g.text(font, text, x + 6, y + 4, 0xFFFFFFFF, true);
		if (p.auto()) {
			int bw = w - 4;
			g.fill(x + 2, y + h - 3, x + 2 + Math.round(bw * p.countdown()), y + h - 2, 0xFFFFD866);
		}
	}

	private static String time(long ms) {
		long total = Math.max(0, ms) / 1000;
		if (total >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60);
		return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
	}
}

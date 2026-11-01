package dev.k3ytel.hearnodub.client.hud;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.VideoMode;
import dev.k3ytel.hearnodub.client.video.VideoCapture;
import dev.k3ytel.hearnodub.client.video.VideoTexture;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The video on the HUD: picture-in-picture (a movable box) or faded background (the whole
 * screen, see-through). Drawn first, so the hotbar, subtitles and chat stay on top of it.
 */
public class VideoHud implements HudElement {
	public static final int MARGIN = 6;

	/** Where the picture-in-picture box goes, in GUI pixels. */
	public record Box(int x0, int y0, int x1, int y1) {
		public int width() { return x1 - x0; }
		public int height() { return y1 - y0; }
		public boolean contains(double x, double y) { return x >= x0 && x < x1 && y >= y0 && y < y1; }
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (!cfg.videoEnabled) {
			VideoTexture.release();
			return;
		}
		boolean haveFrame = VideoTexture.update();
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden()) return;
		boolean running = haveFrame && VideoCapture.showable(VideoCapture.get().state());

		if (cfg.videoMode.inWorld()) {
			// The picture is drawn in the world (VideoWorld); here only say why there's none
			if (!running) {
				drawStatus(g, mc.font, layout(cfg, g.guiWidth(), g.guiHeight()), message(VideoCapture.get().state()));
			} else if (cfg.videoMode == dev.k3ytel.hearnodub.client.config.VideoMode.TV
					&& dev.k3ytel.hearnodub.client.video.TvScreen.current() == null) {
				drawStatus(g, mc.font, layout(cfg, g.guiWidth(), g.guiHeight()),
						Component.translatable("hear-no-dub.video.place_tv", dev.k3ytel.hearnodub.client.video.TvScreen.placeKeyName()));
			}
			return;
		}

		if (cfg.videoMode == VideoMode.FADED_BACKGROUND) {
			if (running) drawBackground(g, cfg);
			else drawStatus(g, mc.font, layout(cfg, g.guiWidth(), g.guiHeight()), message(VideoCapture.get().state()));
			return;
		}
		Box box = layout(cfg, g.guiWidth(), g.guiHeight());
		if (running) drawVideo(g, box, cfg.videoOpacity);
		else drawStatus(g, mc.font, box, message(VideoCapture.get().state()));
	}

	/** The picture-in-picture box for the current settings and screen size. */
	public static Box layout(HearNoDubConfig cfg, int screenW, int screenH) {
		int w = Math.max(60, screenW * cfg.videoSize / 100);
		int h = VideoTexture.width() > 0 ? Math.round(w * VideoTexture.height() / (float) VideoTexture.width()) : w * 9 / 16;
		if (h > screenH - MARGIN * 2) { // too tall for the screen: shrink to fit
			h = screenH - MARGIN * 2;
			w = VideoTexture.height() > 0 ? Math.round(h * VideoTexture.width() / (float) VideoTexture.height()) : h * 16 / 9;
		}
		int x = MARGIN + Math.round((screenW - w - MARGIN * 2) * cfg.videoPosX);
		int y = MARGIN + Math.round((screenH - h - MARGIN * 2) * cfg.videoPosY);
		return new Box(x, y, x + w, y + h);
	}

	/** Draws the current frame (if any) into the box. Also used by the move/resize screen. */
	public static boolean drawVideo(GuiGraphicsExtractor g, Box b, int opacityPercent) {
		if (VideoTexture.texture() == null || VideoTexture.width() <= 0) return false;
		int alpha = Math.round(255 * opacityPercent / 100f);
		if (alpha >= 250) g.fill(b.x0() - 1, b.y0() - 1, b.x1() + 1, b.y1() + 1, 0xFF000000);
		blit(g, b.x0(), b.y0(), b.width(), b.height(), alpha);
		return true;
	}

	/** Fills the screen, keeping the picture's shape (no stretching), see-through. */
	private static void drawBackground(GuiGraphicsExtractor g, HearNoDubConfig cfg) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		float tw = VideoTexture.width(), th = VideoTexture.height();
		float scale = Math.min(sw / tw, sh / th);
		int w = Math.round(tw * scale), h = Math.round(th * scale);
		blit(g, (sw - w) / 2, (sh - h) / 2, w, h, Math.round(255 * cfg.videoBackgroundOpacity / 100f));
	}

	private static void blit(GuiGraphicsExtractor g, int x, int y, int w, int h, int alpha) {
		int tw = VideoTexture.width(), th = VideoTexture.height();
		int color = (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
		g.blit(RenderPipelines.GUI_TEXTURED, VideoTexture.ID, x, y, 0f, 0f, w, h, tw, th, tw, th, color);
	}

	/** No picture (yet): a dark box that says why. */
	private static void drawStatus(GuiGraphicsExtractor g, Font font, Box b, Component msg) {
		g.fill(b.x0(), b.y0(), b.x1(), b.y1(), 0xC0000000);
		var lines = font.split(msg, b.width() - 8);
		int ty = b.y0() + (b.height() - lines.size() * (font.lineHeight + 1)) / 2;
		for (FormattedCharSequence line : lines) {
			g.text(font, line, b.x0() + 4, ty, 0xFFDDDDDD, true);
			ty += font.lineHeight + 1;
		}
	}

	public static Component message(VideoCapture.State state) {
		VideoCapture vc = VideoCapture.get();
		return switch (state) {
			case IDLE -> Component.translatable("hear-no-dub.video.idle");
			case NO_WINDOW -> Component.translatable("hear-no-dub.video.no_window", vc.browserName());
			case MINIMIZED -> Component.translatable("hear-no-dub.video.minimized", vc.browserName());
			case STALLED -> Component.translatable("hear-no-dub.video.stalled");
			case UNSUPPORTED -> Component.translatable("hear-no-dub.video.unsupported");
			case FAILED -> Component.translatable("hear-no-dub.video.failed", vc.lastError());
			default -> Component.translatable("hear-no-dub.video.starting");
		};
	}
}

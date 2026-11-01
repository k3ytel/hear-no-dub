package dev.k3ytel.hearnodub.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.hud.VideoHud;
import dev.k3ytel.hearnodub.client.video.VideoCapture;
import dev.k3ytel.hearnodub.client.video.VideoTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * "Fix video area": shows the whole browser window with the video area outlined. Drag the
 * edges or corners (or the middle, to move it), then "Use this area". "Auto" goes back to
 * finding the video by itself.
 */
public class VideoCropScreen extends Screen {
	private static final int GRAB = 5;

	private final Screen parent;
	/** The area being edited, in window pixels: x0, y0, x1, y1. Null until the window is known. */
	private int[] rect;
	private int dragEdges; // bit 1 left, 2 right, 4 top, 8 bottom; 15 = move
	private double grabX, grabY;
	private int[] grabRect;

	// Where the window picture is drawn on screen
	private int imgX0, imgY0, imgW, imgH;

	public VideoCropScreen(Screen parent) {
		super(Component.translatable("hear-no-dub.crop.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		VideoCapture.get().setEditingCrop(true);
		int y = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.crop.auto"), b -> {
			HearNoDubConfig.get().videoCropManual = false;
			HearNoDubConfig.save();
			onClose();
		}).bounds(width / 2 - 154, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.crop.use"), b -> save())
				.bounds(width / 2 - 50, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(width / 2 + 54, y, 100, 20).build());
	}

	private void save() {
		int[] win = VideoCapture.get().windowSize();
		if (rect != null && win != null) {
			HearNoDubConfig cfg = HearNoDubConfig.get();
			cfg.videoCropManual = true;
			cfg.videoCropX = rect[0];
			cfg.videoCropY = rect[1];
			cfg.videoCropW = rect[2] - rect[0];
			cfg.videoCropH = rect[3] - rect[1];
			cfg.videoCropWinW = win[0];
			cfg.videoCropWinH = win[1];
			HearNoDubConfig.save();
		}
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		VideoTexture.update();
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		g.centeredText(font, Component.translatable("hear-no-dub.crop.hint"), width / 2, 20, 0xFFAAAAAA);

		if (!HearNoDubConfig.get().videoEnabled) {
			g.centeredText(font, Component.translatable("hear-no-dub.crop.off"), width / 2, height / 2, 0xFFDDDDDD);
			return;
		}
		VideoCapture vc = VideoCapture.get();
		int[] win = vc.windowSize();
		if (win == null || !vc.showingWholeWindow() || VideoTexture.width() <= 0) {
			g.centeredText(font, Component.translatable("hear-no-dub.crop.waiting"), width / 2, height / 2, 0xFFDDDDDD);
			return;
		}
		if (rect == null) rect = initialRect(win);

		// Fit the window picture into the space between the title and the buttons
		int areaW = width - 20, areaH = height - 34 - 34;
		float scale = Math.min(areaW / (float) win[0], areaH / (float) win[1]);
		imgW = Math.round(win[0] * scale);
		imgH = Math.round(win[1] * scale);
		imgX0 = (width - imgW) / 2;
		imgY0 = 34 + (areaH - imgH) / 2;
		VideoHud.drawVideo(g, new VideoHud.Box(imgX0, imgY0, imgX0 + imgW, imgY0 + imgH), 100);

		// Darken everything outside the area, then outline it
		int x0 = sx(rect[0], win), x1 = sx(rect[2], win), y0 = sy(rect[1], win), y1 = sy(rect[3], win);
		int shade = 0xA0000000;
		g.fill(imgX0, imgY0, imgX0 + imgW, y0, shade);
		g.fill(imgX0, y1, imgX0 + imgW, imgY0 + imgH, shade);
		g.fill(imgX0, y0, x0, y1, shade);
		g.fill(x1, y0, imgX0 + imgW, y1, shade);
		g.outline(x0, y0, x1 - x0, y1 - y0, 0xFFFFFF55);
		int[][] corners = {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}};
		for (int[] c : corners) g.fill(c[0] - 2, c[1] - 2, c[0] + 2, c[1] + 2, 0xFFFFFF55);

		String size = (rect[2] - rect[0]) + " × " + (rect[3] - rect[1]);
		g.text(font, size, x0 + 3, y0 + 3, 0xFFFFFF55, true);
	}

	/** Starts from the saved manual area (if it fits this window), else what auto-detection found. */
	private static int[] initialRect(int[] win) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (cfg.videoCropManual && cfg.videoCropW > 0 && cfg.videoCropWinW == win[0] && cfg.videoCropWinH == win[1]) {
			return new int[]{cfg.videoCropX, cfg.videoCropY, cfg.videoCropX + cfg.videoCropW, cfg.videoCropY + cfg.videoCropH};
		}
		int[] a = VideoCapture.get().autoCrop();
		if (a != null) return new int[]{a[0], a[1], a[0] + a[2], a[1] + a[3]};
		return new int[]{0, 0, win[0], win[1]};
	}

	private int sx(int wx, int[] win) {
		return imgX0 + Math.round(wx * imgW / (float) win[0]);
	}

	private int sy(int wy, int[] win) {
		return imgY0 + Math.round(wy * imgH / (float) win[1]);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int[] win = VideoCapture.get().windowSize();
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && rect != null && win != null) {
			double x = event.x(), y = event.y();
			int x0 = sx(rect[0], win), x1 = sx(rect[2], win), y0 = sy(rect[1], win), y1 = sy(rect[3], win);
			boolean inX = x > x0 - GRAB && x < x1 + GRAB, inY = y > y0 - GRAB && y < y1 + GRAB;
			int edges = 0;
			if (inY && Math.abs(x - x0) <= GRAB) edges |= 1;
			if (inY && Math.abs(x - x1) <= GRAB) edges |= 2;
			if (inX && Math.abs(y - y0) <= GRAB) edges |= 4;
			if (inX && Math.abs(y - y1) <= GRAB) edges |= 8;
			if (edges == 0 && x > x0 && x < x1 && y > y0 && y < y1) edges = 15;
			if (edges != 0) {
				dragEdges = edges;
				grabX = x;
				grabY = y;
				grabRect = rect.clone();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		int[] win = VideoCapture.get().windowSize();
		if (dragEdges == 0 || win == null || imgW <= 0) return super.mouseDragged(event, dx, dy);
		int mx = (int) Math.round((event.x() - grabX) * win[0] / imgW);
		int my = (int) Math.round((event.y() - grabY) * win[1] / imgH);
		int[] r = grabRect.clone();
		if (dragEdges == 15) {
			mx = Math.max(-r[0], Math.min(win[0] - r[2], mx));
			my = Math.max(-r[1], Math.min(win[1] - r[3], my));
			r[0] += mx; r[2] += mx; r[1] += my; r[3] += my;
		} else {
			int min = 32;
			if ((dragEdges & 1) != 0) r[0] = Math.max(0, Math.min(r[2] - min, r[0] + mx));
			if ((dragEdges & 2) != 0) r[2] = Math.min(win[0], Math.max(r[0] + min, r[2] + mx));
			if ((dragEdges & 4) != 0) r[1] = Math.max(0, Math.min(r[3] - min, r[1] + my));
			if ((dragEdges & 8) != 0) r[3] = Math.min(win[1], Math.max(r[1] + min, r[3] + my));
		}
		rect = r;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragEdges != 0 && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragEdges = 0;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		VideoCapture.get().setEditingCrop(false);
		super.removed();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

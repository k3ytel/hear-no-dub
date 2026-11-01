package dev.k3ytel.hearnodub.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.hud.VideoHud;
import dev.k3ytel.hearnodub.client.video.VideoTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/** Drag the picture-in-picture box to move it; drag its corner (or scroll) to resize it. */
public class VideoPositionScreen extends Screen {
	private static final int HANDLE = 8;

	private final Supplier<Screen> returnTo;
	private boolean moving, resizing;
	private double grabDx, grabDy;

	public VideoPositionScreen(Supplier<Screen> returnTo) {
		super(Component.translatable("hear-no-dub.video_position.title"));
		this.returnTo = returnTo;
	}

	@Override
	protected void init() {
		int y = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.position.reset"), b -> {
			HearNoDubConfig def = new HearNoDubConfig();
			HearNoDubConfig cfg = HearNoDubConfig.get();
			cfg.videoPosX = def.videoPosX;
			cfg.videoPosY = def.videoPosY;
			cfg.videoSize = def.videoSize;
		}).bounds(width / 2 - 104, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 4, y, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		VideoTexture.update(); // keep the picture moving while this screen is open
		g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		g.centeredText(font, Component.translatable("hear-no-dub.video_position.hint"), width / 2, 22, 0xFFAAAAAA);

		VideoHud.Box b = box();
		if (!VideoHud.drawVideo(g, b, 100)) {
			g.fill(b.x0(), b.y0(), b.x1(), b.y1(), 0xC0202020);
			g.centeredText(font, Component.translatable("hear-no-dub.video_position.sample"),
					(b.x0() + b.x1()) / 2, (b.y0() + b.y1()) / 2 - 4, 0xFFBBBBBB);
		}
		boolean hot = moving || resizing || b.contains(mouseX, mouseY);
		g.outline(b.x0() - 1, b.y0() - 1, b.width() + 2, b.height() + 2, hot ? 0xFFFFFF55 : 0x80FFFFFF);
		// resize handle in the corner that points to the middle of the screen
		int hx = handleX(b), hy = handleY(b);
		g.fill(hx, hy, hx + HANDLE, hy + HANDLE, resizing || overHandle(b, mouseX, mouseY) ? 0xFFFFFF55 : 0xFFFFFFFF);
	}

	private VideoHud.Box box() {
		return VideoHud.layout(HearNoDubConfig.get(), width, height);
	}

	/** The handle sits on the corner facing the middle of the screen, so it's never off-screen. */
	private boolean handleLeft(VideoHud.Box b) {
		return (b.x0() + b.x1()) / 2 > width / 2;
	}

	private boolean handleTop(VideoHud.Box b) {
		return (b.y0() + b.y1()) / 2 > height / 2;
	}

	private int handleX(VideoHud.Box b) {
		return handleLeft(b) ? b.x0() - HANDLE / 2 : b.x1() - HANDLE / 2;
	}

	private int handleY(VideoHud.Box b) {
		return handleTop(b) ? b.y0() - HANDLE / 2 : b.y1() - HANDLE / 2;
	}

	private boolean overHandle(VideoHud.Box b, double x, double y) {
		int hx = handleX(b), hy = handleY(b);
		return x >= hx - 2 && x < hx + HANDLE + 2 && y >= hy - 2 && y < hy + HANDLE + 2;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			VideoHud.Box b = box();
			if (overHandle(b, event.x(), event.y())) {
				resizing = true;
				return true;
			}
			if (b.contains(event.x(), event.y())) {
				moving = true;
				grabDx = event.x() - b.x0();
				grabDy = event.y() - b.y0();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		VideoHud.Box b = box();
		if (moving) {
			placeAt(cfg, event.x() - grabDx, event.y() - grabDy, b.width(), b.height());
			return true;
		}
		if (resizing) {
			// the corner opposite the handle stays put
			boolean left = handleLeft(b), top = handleTop(b);
			double fixedX = left ? b.x1() : b.x0();
			double newW = Math.abs(event.x() - fixedX);
			double aspect = b.height() / (double) Math.max(1, b.width());
			cfg.videoSize = Math.max(10, Math.min(90, (int) Math.round(newW * 100 / width)));
			VideoHud.Box nb = box();
			double x0 = left ? fixedX - nb.width() : fixedX;
			double fixedY = top ? b.y1() : b.y0();
			double y0 = top ? fixedY - nb.width() * aspect : fixedY;
			placeAt(cfg, x0, y0, nb.width(), nb.height());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	/** Stores a top-left position as fractions of the free space. */
	private void placeAt(HearNoDubConfig cfg, double x0, double y0, int w, int h) {
		double freeW = width - w - VideoHud.MARGIN * 2, freeH = height - h - VideoHud.MARGIN * 2;
		cfg.videoPosX = freeW <= 0 ? 0.5f : clamp01((float) ((x0 - VideoHud.MARGIN) / freeW));
		cfg.videoPosY = freeH <= 0 ? 0.5f : clamp01((float) ((y0 - VideoHud.MARGIN) / freeH));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0) {
			HearNoDubConfig cfg = HearNoDubConfig.get();
			cfg.videoSize = Math.max(10, Math.min(90, cfg.videoSize + (scrollY > 0 ? 2 : -2)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if ((moving || resizing) && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			moving = false;
			resizing = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		HearNoDubConfig.save();
		minecraft.gui.setScreen(returnTo.get());
	}
}

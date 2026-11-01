package dev.k3ytel.hearnodub.client.gui;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.VideoMode;
import dev.k3ytel.hearnodub.client.hud.VideoHud;
import dev.k3ytel.hearnodub.client.video.TvScreen;
import dev.k3ytel.hearnodub.client.video.VideoCapture;
import dev.k3ytel.hearnodub.client.video.VideoTexture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The "Video…" button in the K menu: on/off, mode, fix the area, move the box, with a live preview. */
public class VideoMenuScreen extends Screen {
	private final Screen parent;
	private Button toggle, mode, move;

	public VideoMenuScreen(Screen parent) {
		super(Component.translatable("hear-no-dub.video_menu.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		int bw = 150, gap = 4;
		int x0 = width / 2 - bw - gap / 2, x1 = width / 2 + gap / 2;
		int y = height - 26 - 24 * 2;

		toggle = Button.builder(toggleLabel(), b -> {
			if (!cfg.videoEnabled) VideoCapture.get().resetFailures();
			cfg.videoEnabled = !cfg.videoEnabled;
			HearNoDubConfig.save();
			b.setMessage(toggleLabel());
		}).bounds(x0, y, bw, 20).build();
		addRenderableWidget(toggle);
		mode = Button.builder(modeLabel(), b -> {
			VideoMode[] modes = VideoMode.values();
			cfg.videoMode = modes[(cfg.videoMode.ordinal() + 1) % modes.length];
			HearNoDubConfig.save();
			b.setMessage(modeLabel());
		}).bounds(x1, y, bw, 20).build();
		addRenderableWidget(mode);

		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.video_menu.crop"),
				b -> minecraft.gui.setScreen(new VideoCropScreen(this))).bounds(x0, y, bw, 20).build());
		move = Button.builder(Component.translatable("hear-no-dub.video_menu.move"),
				b -> minecraft.gui.setScreen(new VideoPositionScreen(() -> this))).bounds(x1, y, bw, 20).build();
		addRenderableWidget(move);

		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.picker.settings"),
				b -> minecraft.gui.setScreen(HearNoDubConfigScreen.create(this))).bounds(x0, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(x1, y, bw, 20).build());
	}

	private static Component toggleLabel() {
		boolean on = HearNoDubConfig.get().videoEnabled;
		return Component.translatable("hear-no-dub.video_menu.toggle",
				Component.translatable(on ? "options.on" : "options.off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	private static Component modeLabel() {
		return Component.translatable("hear-no-dub.video_menu.mode", HearNoDubConfig.get().videoMode.display());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		HearNoDubConfig cfg = HearNoDubConfig.get();
		move.active = cfg.videoMode == VideoMode.PICTURE_IN_PICTURE;
		VideoTexture.update();
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);

		// Live preview
		int top = 22, bottom = height - 26 - 24 * 2 - 30;
		int pw = Math.min(width - 40, 320), ph = Math.max(20, bottom - top);
		float aspect = VideoTexture.width() > 0 ? VideoTexture.height() / (float) VideoTexture.width() : 9 / 16f;
		int w = Math.min(pw, Math.round(ph / aspect)), h = Math.round(w * aspect);
		VideoHud.Box box = new VideoHud.Box((width - w) / 2, top, (width + w) / 2, top + h);
		VideoCapture vc = VideoCapture.get();
		VideoCapture.State state = vc.state();
		if (!cfg.videoEnabled || !VideoCapture.showable(state) || !VideoHud.drawVideo(g, box, 100)) {
			g.fill(box.x0(), box.y0(), box.x1(), box.y1(), 0xC0000000);
			Component msg = cfg.videoEnabled ? VideoHud.message(state) : Component.translatable("hear-no-dub.video_menu.off");
			g.centeredText(font, msg, width / 2, box.y0() + h / 2 - 4, 0xFFBBBBBB);
		}

		// Status lines under the preview
		int ty = box.y1() + 6;
		if (cfg.videoEnabled && state == VideoCapture.State.RUNNING) {
			String area = cfg.videoCropManual ? Component.translatable("hear-no-dub.video_menu.area_manual").getString()
					: Component.translatable("hear-no-dub.video_menu.area_auto").getString();
			String line = String.format(Locale.ROOT, "%s · %s · %.0f ms / frame", area, vc.cropInfo(), vc.captureMs());
			g.centeredText(font, line, width / 2, ty, 0xFF999999);
			ty += 11;
		}
		if (cfg.videoMode == VideoMode.TV) {
			Component tv = TvScreen.current() == null
					? Component.translatable("hear-no-dub.video_menu.tv_none", TvScreen.placeKeyName())
					: Component.translatable("hear-no-dub.video_menu.tv_placed", TvScreen.placeKeyName());
			g.centeredText(font, tv, width / 2, ty, 0xFF999999);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

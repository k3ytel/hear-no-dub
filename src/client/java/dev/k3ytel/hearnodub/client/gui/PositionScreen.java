package dev.k3ytel.hearnodub.client.gui;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.hud.SubtitleHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

/** Drag a sample caption to where the subtitles should appear. */
public class PositionScreen extends Screen {
	private static final List<String> SAMPLE = List.of(
			"This is where your subtitles will appear.",
			"Drag me anywhere!");

	private final Supplier<Screen> returnTo;
	private boolean dragging;
	private double grabDx, grabDy;

	public PositionScreen(Supplier<Screen> returnTo) {
		super(Component.translatable("hear-no-dub.position.title"));
		this.returnTo = returnTo;
	}

	@Override
	protected void init() {
		int y = height - 26;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.position.reset"), b -> {
			HearNoDubConfig def = new HearNoDubConfig();
			HearNoDubConfig.get().posX = def.posX;
			HearNoDubConfig.get().posY = def.posY;
		}).bounds(width / 2 - 104, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 4, y, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		g.centeredText(font, Component.translatable("hear-no-dub.position.hint"), width / 2, 22, 0xFFAAAAAA);
		SubtitleHud.draw(g, font, SAMPLE, HearNoDubConfig.get());

		SubtitleHud.Box box = box();
		if (box != null && (dragging || inside(box, mouseX, mouseY))) {
			g.outline((int) box.x() - 1, (int) box.y() - 1, (int) box.width() + 2, (int) box.height() + 2, 0xFFFFFF55);
		}
	}

	private SubtitleHud.Box box() {
		return SubtitleHud.layout(font, SAMPLE, HearNoDubConfig.get(), width, height);
	}

	private static boolean inside(SubtitleHud.Box b, double x, double y) {
		return x >= b.x() && x < b.x() + b.width() && y >= b.y() && y < b.y() + b.height();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		SubtitleHud.Box b = box();
		if (event.button() == 0 && b != null && inside(b, event.x(), event.y())) {
			dragging = true;
			grabDx = event.x() - b.x();
			grabDy = event.y() - b.y();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			SubtitleHud.Box b = box();
			if (b != null) {
				HearNoDubConfig cfg = HearNoDubConfig.get();
				double left = event.x() - grabDx;
				double top = event.y() - grabDy;
				cfg.posX = clamp01((float) ((left + b.width() / 2) / width));
				cfg.posY = clamp01((float) ((top + b.height()) / height));
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging && event.button() == 0) {
			dragging = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	@Override
	public void onClose() {
		HearNoDubConfig.save();
		minecraft.gui.setScreen(returnTo.get());
	}
}

package dev.k3ytel.hearnodub.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.LetterAnimation;
import dev.k3ytel.hearnodub.client.config.LetterFont;
import dev.k3ytel.hearnodub.client.config.LetterMaterial;
import dev.k3ytel.hearnodub.client.config.LetterSpawn;
import dev.k3ytel.hearnodub.client.config.LetterStyle;
import dev.k3ytel.hearnodub.client.config.LyricsEffect;
import dev.k3ytel.hearnodub.client.config.LyricsReveal;
import dev.k3ytel.hearnodub.client.config.LyricsView;
import dev.k3ytel.hearnodub.client.config.ScreenMode;
import dev.k3ytel.hearnodub.client.config.WorldMode;
import dev.k3ytel.hearnodub.client.config.GlowMode;
import dev.k3ytel.hearnodub.client.hud.PopIn3D;
import dev.k3ytel.hearnodub.client.media.AudioLevel;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lyrics Studio: change how lyrics look and see it right away. The game keeps running behind
 * a slim panel, a made-up demo song plays (with a fake beat), and every button applies at once.
 * Left click = next choice, right click = previous.
 */
public class LyricsStudioScreen extends Screen {
	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private boolean demo = true;

	/** One cycling setting: its label, and how to step forward/back. */
	private record Row(Button button, Supplier<Component> label, Consumer<Integer> step) {}

	public LyricsStudioScreen(Screen parent) {
		super(Component.translatable("hear-no-dub.studio.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rows.clear();
		HearNoDubConfig cfg = HearNoDubConfig.get();
		SubtitleManager.get().setDemo(demo);
		AudioLevel.setDemo(demo);

		int bw = 112, bh = 16, gap = 1; // compact so everything fits at big GUI scales
		int x0 = 6, x1 = x0 + bw + gap;
		int y = 22;
		// column 1: where and what the letters look like
		y = header(y);
		cycle(x0, y, bw, bh, "screen", () -> cfg.screenMode.display(), d -> cfg.screenMode = step(ScreenMode.values(), cfg.screenMode, d));
		cycle(x1, y, bw, bh, "world", () -> cfg.worldMode.display(), d -> {
			cfg.worldMode = step(WorldMode.values(), cfg.worldMode, d);
			PopIn3D.clear();
		});
		y += bh + gap;
		cycle(x0, y, bw, bh, "spawn", () -> cfg.letterSpawn.display(), d -> {
			cfg.letterSpawn = step(LetterSpawn.values(), cfg.letterSpawn, d);
			PopIn3D.clear();
		});
		cycle(x1, y, bw, bh, "style", () -> cfg.letterStyle.display(), d -> {
			cfg.letterStyle = step(LetterStyle.values(), cfg.letterStyle, d);
			PopIn3D.clear();
		});
		y += bh + gap;
		cycle(x0, y, bw, bh, "font", () -> cfg.letterFont.display(), d -> {
			cfg.letterFont = step(LetterFont.values(), cfg.letterFont, d);
			PopIn3D.clear();
		});
		cycle(x1, y, bw, bh, "material", () -> cfg.letterMaterial.display(), d -> {
			cfg.letterMaterial = step(LetterMaterial.values(), cfg.letterMaterial, d);
			PopIn3D.clear();
		});
		y += bh + gap;
		cycle(x0, y, bw, bh, "animation", () -> cfg.letterAnimation.display(), d -> cfg.letterAnimation = step(LetterAnimation.values(), cfg.letterAnimation, d));
		cycle(x1, y, bw, bh, "size", () -> Component.literal(String.format(java.util.Locale.ROOT, "%.2f×", cfg.letterScale)), d -> {
			cfg.letterScale = Math.max(0.3f, Math.min(3f, Math.round((cfg.letterScale + d * 0.1f) * 100) / 100f));
			PopIn3D.clear();
		});
		y += bh + gap;
		toggle(x0, y, bw, bh, "glow", () -> cfg.letterGlow, v -> cfg.letterGlow = v);
		cycle(x1, y, bw, bh, "glow_strength", () -> Component.literal(cfg.letterGlowStrength + "%"), d ->
				cfg.letterGlowStrength = Math.max(5, Math.min(100, cfg.letterGlowStrength + d * 10)));
		y += bh + gap;
		cycle(x0, y, bw, bh, "glow_mode", () -> cfg.glowMode.display(), d -> cfg.glowMode = step(GlowMode.values(), cfg.glowMode, d));
		toggle(x1, y, bw, bh, "shadow", () -> cfg.letterShadow, v -> cfg.letterShadow = v);
		y += bh + gap;
		cycle(x0, y, bw, bh, "min_distance", () -> Component.literal(String.format(java.util.Locale.ROOT, "%.1f", cfg.letterMinDistance)), d -> {
			cfg.letterMinDistance = Math.max(2f, Math.min(32f, cfg.letterMinDistance + d * 0.5f));
			if (cfg.letterDistance < cfg.letterMinDistance + 1) cfg.letterDistance = cfg.letterMinDistance + 1;
			PopIn3D.clear();
		});
		cycle(x1, y, bw, bh, "max_distance", () -> Component.literal(String.format(java.util.Locale.ROOT, "%.1f", cfg.letterDistance)), d -> {
			cfg.letterDistance = Math.max(cfg.letterMinDistance + 1, Math.min(64f, cfg.letterDistance + d * 1f));
			PopIn3D.clear();
		});
		y += bh + gap + 4;

		// karaoke
		y = header(y);
		cycle(x0, y, bw, bh, "view", () -> cfg.lyricsView.display(), d -> cfg.lyricsView = step(LyricsView.values(), cfg.lyricsView, d));
		cycle(x1, y, bw, bh, "reveal", () -> cfg.lyricsReveal.display(), d -> {
			cfg.lyricsReveal = step(LyricsReveal.values(), cfg.lyricsReveal, d);
			PopIn3D.clear();
		});
		y += bh + gap;
		cycle(x0, y, bw, bh, "effect", () -> cfg.lyricsEffect.display(), d -> cfg.lyricsEffect = step(LyricsEffect.values(), cfg.lyricsEffect, d));
		toggle(x1, y, bw, bh, "fill", () -> cfg.lyricsFill, v -> cfg.lyricsFill = v);
		y += bh + gap;
		toggle(x0, y, bw, bh, "shout", () -> cfg.lyricsEmphasis, v -> cfg.lyricsEmphasis = v);
		y += bh + gap + 4;

		// extras
		y = header(y);
		toggle(x0, y, bw, bh, "music", () -> cfg.letterAudioReact, v -> cfg.letterAudioReact = v);
		toggle(x1, y, bw, bh, "effects", () -> cfg.letterLandingFx, v -> cfg.letterLandingFx = v);
		y += bh + gap;
		toggle(x0, y, bw, bh, "chorus", () -> cfg.lyricsChorus, v -> {
			cfg.lyricsChorus = v;
			PopIn3D.clear();
		});
		toggle(x1, y, bw, bh, "bands", () -> cfg.letterBands, v -> cfg.letterBands = v);
		y += bh + gap;
		toggle(x0, y, bw, bh, "weather", () -> cfg.letterWeather, v -> cfg.letterWeather = v);
		toggle(x1, y, bw, bh, "director", () -> cfg.letterDirector, v -> cfg.letterDirector = v);
		y += bh + gap;
		toggle(x0, y, bw, bh, "mobs", () -> cfg.letterMobsLook, v -> cfg.letterMobsLook = v);
		toggle(x1, y, bw, bh, "upcoming", () -> cfg.lyricsUpcoming, v -> cfg.lyricsUpcoming = v);
		y += bh + gap + 4;

		// bottom bar
		int by = height - 24;
		addRenderableWidget(Button.builder(demoLabel(), b -> {
			demo = !demo;
			SubtitleManager.get().setDemo(demo);
			AudioLevel.setDemo(demo);
			PopIn3D.clear();
			b.setMessage(demoLabel());
		}).bounds(x0, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.studio.respawn"), b -> {
			PopIn3D.clear();
			SubtitleManager.get().restartDemo();
		}).bounds(x1, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.studio.all"),
				b -> minecraft.gui.setScreen(HearNoDubConfigScreen.create(this))).bounds(width - 6 - bw * 2 - gap, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width - 6 - bw, by, bw, 20).build());
	}

	private int header(int y) {
		return y + 11; // a title is drawn above each block of buttons
	}

	private void cycle(int x, int y, int w, int h, String key, Supplier<Component> value, Consumer<Integer> step) {
		Supplier<Component> label = () -> Component.translatable("hear-no-dub.studio." + key).append(": ").append(value.get().copy().withStyle(ChatFormatting.YELLOW));
		Button b = Button.builder(label.get(), btn -> {
			step.accept(1);
			changed();
		}).bounds(x, y, w, h).build();
		addRenderableWidget(b);
		rows.add(new Row(b, label, step));
	}

	private void toggle(int x, int y, int w, int h, String key, Supplier<Boolean> get, Consumer<Boolean> set) {
		cycle(x, y, w, h, key, () -> Component.translatable(get.get() ? "options.on" : "options.off")
				.withStyle(get.get() ? ChatFormatting.GREEN : ChatFormatting.RED), d -> set.accept(!get.get()));
	}

	private void changed() {
		HearNoDubConfig.save();
		for (Row r : rows) r.button().setMessage(r.label().get());
	}

	private static <E extends Enum<E>> E step(E[] values, E current, int d) {
		return values[Math.floorMod(current.ordinal() + d, values.length)];
	}

	private Component demoLabel() {
		return Component.translatable("hear-no-dub.studio.demo",
				Component.translatable(demo ? "options.on" : "options.off").withStyle(demo ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			for (Row r : rows) {
				if (r.button().isMouseOver(event.x(), event.y())) {
					r.step().accept(-1);
					changed();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// no blur or darkening: the world behind is the preview
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int panelW = 6 + 112 * 2 + 2 + 6;
		g.fill(0, 0, panelW, height, 0x88000000);
		g.text(font, title, 8, 8, 0xFFFFE680, true);
		// section titles
		int y = 22;
		g.text(font, Component.translatable("hear-no-dub.studio.section.letters"), 8, y, 0xFFAAAAAA, false);
		y += 11 + (16 + 1) * 7 + 4;
		g.text(font, Component.translatable("hear-no-dub.studio.section.karaoke"), 8, y, 0xFFAAAAAA, false);
		y += 11 + (16 + 1) * 3 + 4;
		g.text(font, Component.translatable("hear-no-dub.studio.section.extras"), 8, y, 0xFFAAAAAA, false);
		g.text(font, Component.translatable("hear-no-dub.studio.hint"), 8, height - 36, 0xFF888888, false);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		SubtitleManager.get().setDemo(false);
		AudioLevel.setDemo(false);
		PopIn3D.clear();
		HearNoDubConfig.save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

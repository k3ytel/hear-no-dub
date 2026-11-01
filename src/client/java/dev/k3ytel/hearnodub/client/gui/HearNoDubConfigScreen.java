package dev.k3ytel.hearnodub.client.gui;

import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.StateManager;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.k3ytel.hearnodub.client.config.Animation;
import dev.k3ytel.hearnodub.client.config.Billboard;
import dev.k3ytel.hearnodub.client.config.BubbleTarget;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.ScreenMode;
import dev.k3ytel.hearnodub.client.config.StylePreset;
import dev.k3ytel.hearnodub.client.config.WorldAnchor;
import dev.k3ytel.hearnodub.client.config.WorldMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.awt.Color;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** The config screen (YACL). Opened from Mod Menu or the picker's Settings button. */
public final class HearNoDubConfigScreen {
	private HearNoDubConfigScreen() {}

	public static Screen create(Screen parent) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		HearNoDubConfig def = new HearNoDubConfig();

		// ------------------------------------------------------------ General
		ConfigCategory general = ConfigCategory.createBuilder()
				.name(tr("category.general"))
				.option(bool("enabled", def.enabled, () -> cfg.enabled, v -> cfg.enabled = v))
				.option(bool("auto_search", def.autoSearch, () -> cfg.autoSearch, v -> cfg.autoSearch = v))
				.option(bool("toasts", def.showToasts, () -> cfg.showToasts, v -> cfg.showToasts = v))
				.option(bool("auto_pause", def.autoPause, () -> cfg.autoPause, v -> cfg.autoPause = v))
				.option(intSlider("nudge", def.nudgeMs, () -> cfg.nudgeMs, v -> cfg.nudgeMs = v, 50, 2000, 50,
						v -> String.format(Locale.ROOT, "%.2f s", v / 1000.0)))
				.build();

		// ------------------------------------------------------------ Sources
		ConfigCategory sources = ConfigCategory.createBuilder()
				.name(tr("category.sources"))
				.option(LabelOption.create(tr("sources.help").withStyle(ChatFormatting.GRAY)))
				.group(OptionGroup.createBuilder()
						.name(tr("group.subdl"))
						.description(OptionDescription.of(tr("group.subdl.desc")))
						.option(bool("use_subdl", def.useSubdl, () -> cfg.useSubdl, v -> cfg.useSubdl = v))
						.option(text("subdl_key", () -> cfg.subdlKey, v -> cfg.subdlKey = v.trim()))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.os"))
						.description(OptionDescription.of(tr("group.os.desc")))
						.option(bool("use_os", def.useOpenSubtitles, () -> cfg.useOpenSubtitles, v -> cfg.useOpenSubtitles = v))
						.option(text("api_key", () -> cfg.apiKey, v -> cfg.apiKey = v.trim()))
						.option(text("username", () -> cfg.username, v -> cfg.username = v.trim()))
						.option(text("password", () -> cfg.password, v -> cfg.password = v))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.jimaku"))
						.description(OptionDescription.of(tr("group.jimaku.desc")))
						.option(bool("use_jimaku", def.useJimaku, () -> cfg.useJimaku, v -> cfg.useJimaku = v))
						.option(text("jimaku_key", () -> cfg.jimakuKey, v -> cfg.jimakuKey = v.trim()))
						.option(bool("jimaku_japanese", def.jimakuJapanese, () -> cfg.jimakuJapanese, v -> cfg.jimakuJapanese = v))
						.build())
				.build();

		// ------------------------------------------------------------ Display
		ConfigCategory display = ConfigCategory.createBuilder()
				.name(tr("category.display"))
				.option(enumOption("screen_mode", ScreenMode.class, def.screenMode, () -> cfg.screenMode, v -> cfg.screenMode = v,
						ScreenMode::display))
				.option(enumOption("world_mode", WorldMode.class, def.worldMode, () -> cfg.worldMode, v -> cfg.worldMode = v,
						WorldMode::display))
				.group(OptionGroup.createBuilder()
						.name(tr("group.hud"))
						.description(OptionDescription.of(tr("group.hud.desc")))
						.option(ButtonOption.createBuilder()
								.name(tr("move").withStyle(ChatFormatting.AQUA))
								.description(OptionDescription.of(tr("move.desc")))
								.text(tr("open"))
								.action((screen, opt) -> {
									HearNoDubConfig.save();
									Minecraft.getInstance().gui.setScreen(new PositionScreen(() -> create(parent)));
								})
								.build())
						.option(floatSlider("scale", def.textScale, () -> cfg.textScale, v -> cfg.textScale = v, 0.5f, 3.0f, 0.1f, "%.1f×"))
						.option(intSlider("opacity", def.backgroundOpacity, () -> cfg.backgroundOpacity, v -> cfg.backgroundOpacity = v,
								0, 100, 5, v -> v + "%"))
						.option(intSlider("width", def.maxLineWidth, () -> cfg.maxLineWidth, v -> cfg.maxLineWidth = v,
								100, 800, 10, v -> v + " px"))
						.build())
				.build();

		// ------------------------------------------------------------ Style
		ConfigCategory style = ConfigCategory.createBuilder()
				.name(tr("category.style"))
				.option(enumOption("preset", StylePreset.class, def.stylePreset, () -> cfg.stylePreset, v -> cfg.stylePreset = v,
						StylePreset::display))
				.option(enumOption("animation", Animation.class, def.animation, () -> cfg.animation, v -> cfg.animation = v,
						Animation::display))
				.option(bool("karaoke", def.karaoke, () -> cfg.karaoke, v -> cfg.karaoke = v))
				.option(bool("next_preview", def.nextLinePreview, () -> cfg.nextLinePreview, v -> cfg.nextLinePreview = v))
				.option(bool("keep_formatting", def.keepFormatting, () -> cfg.keepFormatting, v -> cfg.keepFormatting = v))
				.option(bool("speaker_colors", def.speakerColors, () -> cfg.speakerColors, v -> cfg.speakerColors = v))
				.group(OptionGroup.createBuilder()
						.name(tr("group.custom"))
						.description(OptionDescription.of(tr("group.custom.desc")))
						.option(color("text_color", def.customTextColor, () -> cfg.customTextColor, v -> cfg.customTextColor = v))
						.option(bool("outline", def.customOutline, () -> cfg.customOutline, v -> cfg.customOutline = v))
						.option(color("outline_color", def.customOutlineColor, () -> cfg.customOutlineColor, v -> cfg.customOutlineColor = v))
						.option(bool("shadow", def.customShadow, () -> cfg.customShadow, v -> cfg.customShadow = v))
						.option(bool("glow", def.customGlow, () -> cfg.customGlow, v -> cfg.customGlow = v))
						.option(color("glow_color", def.customGlowColor, () -> cfg.customGlowColor, v -> cfg.customGlowColor = v))
						.option(bool("box", def.customBox, () -> cfg.customBox, v -> cfg.customBox = v))
						.option(color("box_color", def.customBoxColor, () -> cfg.customBoxColor, v -> cfg.customBoxColor = v))
						.build())
				.build();

		// ------------------------------------------------------------ 3D modes
		ConfigCategory world = ConfigCategory.createBuilder()
				.name(tr("category.world"))
				.option(LabelOption.create(tr("world.help").withStyle(ChatFormatting.GRAY)))
				.option(intSlider("world_opacity", def.worldBackgroundOpacity, () -> cfg.worldBackgroundOpacity,
						v -> cfg.worldBackgroundOpacity = v, 0, 100, 5, v -> v + "%"))
				.option(enumOption("billboard", Billboard.class, def.billboard, () -> cfg.billboard, v -> cfg.billboard = v,
						Billboard::display))
				.option(bool("world_see_through", def.worldSeeThrough, () -> cfg.worldSeeThrough, v -> cfg.worldSeeThrough = v))
				.group(OptionGroup.createBuilder()
						.name(tr("group.floating"))
						.description(OptionDescription.of(tr("group.floating.desc")))
						.option(enumOption("anchor", WorldAnchor.class, def.worldAnchor, () -> cfg.worldAnchor, v -> cfg.worldAnchor = v,
								WorldAnchor::display))
						.option(floatSlider("world_distance", def.worldDistance, () -> cfg.worldDistance, v -> cfg.worldDistance = v,
								1f, 16f, 0.5f, "%.1f blocks"))
						.option(floatSlider("world_height", def.worldHeight, () -> cfg.worldHeight, v -> cfg.worldHeight = v,
								-3f, 3f, 0.1f, "%.1f blocks"))
						.option(floatSlider("world_scale", def.worldScale, () -> cfg.worldScale, v -> cfg.worldScale = v,
								0.25f, 4f, 0.05f, "%.2f×"))
						.option(floatSlider("world_follow_delay", def.worldFollowDelay, () -> cfg.worldFollowDelay, v -> cfg.worldFollowDelay = v,
								0f, 3f, 0.1f, "%.1f s"))
						.option(bool("per_line_random", def.perLineRandom, () -> cfg.perLineRandom, v -> cfg.perLineRandom = v))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.popin"))
						.description(OptionDescription.of(tr("group.popin.desc")))
						.option(floatSlider("popin_distance", def.popinDistance, () -> cfg.popinDistance, v -> cfg.popinDistance = v,
								1f, 16f, 0.5f, "%.1f blocks"))
						.option(intSlider("popin_spread_x", def.popinSpreadX, () -> cfg.popinSpreadX, v -> cfg.popinSpreadX = v,
								5, 60, 1, v -> v + "°"))
						.option(intSlider("popin_spread_y", def.popinSpreadY, () -> cfg.popinSpreadY, v -> cfg.popinSpreadY = v,
								0, 40, 1, v -> v + "°"))
						.option(floatSlider("popin_scale", def.popinScale, () -> cfg.popinScale, v -> cfg.popinScale = v,
								0.25f, 4f, 0.05f, "%.2f×"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.bubble"))
						.description(OptionDescription.of(tr("group.bubble.desc")))
						.option(bool("third_person_bubble", def.thirdPersonBubble, () -> cfg.thirdPersonBubble, v -> cfg.thirdPersonBubble = v))
						.option(enumOption("bubble_target", BubbleTarget.class, def.bubbleTarget, () -> cfg.bubbleTarget,
								v -> cfg.bubbleTarget = v, BubbleTarget::display))
						.option(intSlider("bubble_range", def.bubbleRange, () -> cfg.bubbleRange, v -> cfg.bubbleRange = v,
								4, 64, 1, v -> v + " blocks"))
						.build())
				.build();

		return YetAnotherConfigLib.createBuilder()
				.title(tr("title"))
				.category(general)
				.category(sources)
				.category(display)
				.category(style)
				.category(world)
				.save(HearNoDubConfig::save)
				.build()
				.generateScreen(parent);
	}

	// ---------------------------------------------------------------- option helpers

	private static Option<Boolean> bool(String key, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
		return Option.<Boolean>createBuilder()
				.name(tr(key))
				.description(OptionDescription.of(tr(key + ".desc")))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(TickBoxControllerBuilder::create)
				.build();
	}

	private static Option<String> text(String key, Supplier<String> get, Consumer<String> set) {
		return Option.<String>createBuilder()
				.name(tr(key))
				.description(OptionDescription.of(tr(key + ".desc")))
				.stateManager(StateManager.createInstant(Binding.generic("", get, set)))
				.controller(StringControllerBuilder::create)
				.build();
	}

	private static Option<Integer> intSlider(String key, int def, Supplier<Integer> get, Consumer<Integer> set,
			int min, int max, int step, Function<Integer, String> format) {
		return Option.<Integer>createBuilder()
				.name(tr(key))
				.description(OptionDescription.of(tr(key + ".desc")))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(opt -> IntegerSliderControllerBuilder.create(opt)
						.range(min, max).step(step)
						.formatValue(v -> Component.literal(format.apply(v))))
				.build();
	}

	private static Option<Float> floatSlider(String key, float def, Supplier<Float> get, Consumer<Float> set,
			float min, float max, float step, String format) {
		return Option.<Float>createBuilder()
				.name(tr(key))
				.description(OptionDescription.of(tr(key + ".desc")))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(opt -> FloatSliderControllerBuilder.create(opt)
						.range(min, max).step(step)
						.formatValue(v -> Component.literal(String.format(Locale.ROOT, format, v))))
				.build();
	}

	private static <E extends Enum<E>> Option<E> enumOption(String key, Class<E> type, E def, Supplier<E> get, Consumer<E> set,
			Function<E, Component> display) {
		return Option.<E>createBuilder()
				.name(tr(key))
				.description(OptionDescription.of(tr(key + ".desc")))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(opt -> EnumControllerBuilder.create(opt)
						.enumClass(type)
						.formatValue(display::apply))
				.build();
	}

	/** Colors are stored as ARGB ints; YACL edits java.awt.Color. */
	private static Option<Color> color(String key, int def, IntSupplier get, IntConsumer set) {
		return Option.<Color>createBuilder()
				.name(tr(key))
				.description(OptionDescription.of(tr(key + ".desc")))
				.stateManager(StateManager.createInstant(Binding.generic(new Color(def & 0xFFFFFF),
						() -> new Color(get.getAsInt() & 0xFFFFFF),
						v -> set.accept(0xFF000000 | (v.getRGB() & 0xFFFFFF)))))
				.controller(ColorControllerBuilder::create)
				.build();
	}

	private static MutableComponent tr(String key) {
		return Component.translatable("hear-no-dub.config." + key);
	}
}

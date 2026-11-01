package dev.k3ytel.hearnodub.client.gui;

import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
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
import dev.k3ytel.hearnodub.client.config.DangerLevel;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.ScreenMode;
import dev.k3ytel.hearnodub.client.config.StylePreset;
import dev.k3ytel.hearnodub.client.config.VideoMode;
import dev.k3ytel.hearnodub.client.config.WorldAnchor;
import dev.k3ytel.hearnodub.client.config.WorldMode;
import dev.k3ytel.hearnodub.client.video.VideoCapture;
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

/**
 * The config screen (YACL). Opened from Mod Menu or the picker's Settings button.
 * Tabs: General · Subtitles · Style · Video · Playback · Sources.
 */
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
				.option(intSlider("nudge", def.nudgeMs, () -> cfg.nudgeMs, v -> cfg.nudgeMs = v, 50, 2000, 50,
						v -> String.format(Locale.ROOT, "%.2f s", v / 1000.0)))
				.build();

		// ------------------------------------------------------------ Subtitles (where they show)
		ConfigCategory subtitles = ConfigCategory.createBuilder()
				.name(tr("category.subtitles"))
				.option(enumOption("screen_mode", ScreenMode.class, def.screenMode, () -> cfg.screenMode, v -> cfg.screenMode = v,
						ScreenMode::display))
				.option(enumOption("world_mode", WorldMode.class, def.worldMode, () -> cfg.worldMode, v -> cfg.worldMode = v,
						WorldMode::display))
				.group(OptionGroup.createBuilder()
						.name(tr("group.hud"))
						.description(OptionDescription.of(tr("group.hud.desc")))
						.option(button("move", () -> new PositionScreen(() -> create(parent))))
						.option(floatSlider("scale", def.textScale, () -> cfg.textScale, v -> cfg.textScale = v, 0.5f, 3.0f, 0.1f, "%.1f×"))
						.option(intSlider("opacity", def.backgroundOpacity, () -> cfg.backgroundOpacity, v -> cfg.backgroundOpacity = v,
								0, 100, 5, v -> v + "%"))
						.option(intSlider("width", def.maxLineWidth, () -> cfg.maxLineWidth, v -> cfg.maxLineWidth = v,
								100, 800, 10, v -> v + " px"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.danmaku"))
						.description(OptionDescription.of(tr("group.danmaku.desc")))
						.collapsed(true)
						.option(intSlider("danmaku_seconds", def.danmakuSeconds, () -> cfg.danmakuSeconds, v -> cfg.danmakuSeconds = v,
								3, 20, 1, v -> v + " s"))
						.option(floatSlider("danmaku_scale", def.danmakuScale, () -> cfg.danmakuScale, v -> cfg.danmakuScale = v,
								0.5f, 4f, 0.1f, "%.1f×"))
						.option(intSlider("danmaku_area", def.danmakuArea, () -> cfg.danmakuArea, v -> cfg.danmakuArea = v,
								15, 100, 5, v -> v + "%"))
						.option(intSlider("danmaku_opacity", def.danmakuOpacity, () -> cfg.danmakuOpacity, v -> cfg.danmakuOpacity = v,
								10, 100, 5, v -> v + "%"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.world3d"))
						.description(OptionDescription.of(tr("group.world3d.desc")))
						.option(enumOption("billboard", Billboard.class, def.billboard, () -> cfg.billboard, v -> cfg.billboard = v,
								Billboard::display))
						.option(bool("world_see_through", def.worldSeeThrough, () -> cfg.worldSeeThrough, v -> cfg.worldSeeThrough = v))
						.option(intSlider("world_opacity", def.worldBackgroundOpacity, () -> cfg.worldBackgroundOpacity,
								v -> cfg.worldBackgroundOpacity = v, 0, 100, 5, v -> v + "%"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.floating"))
						.description(OptionDescription.of(tr("group.floating.desc")))
						.collapsed(true)
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
						.collapsed(true)
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
						.collapsed(true)
						.option(enumOption("bubble_target", BubbleTarget.class, def.bubbleTarget, () -> cfg.bubbleTarget,
								v -> cfg.bubbleTarget = v, BubbleTarget::display))
						.option(intSlider("bubble_range", def.bubbleRange, () -> cfg.bubbleRange, v -> cfg.bubbleRange = v,
								4, 64, 1, v -> v + " blocks"))
						.option(bool("third_person_bubble", def.thirdPersonBubble, () -> cfg.thirdPersonBubble, v -> cfg.thirdPersonBubble = v))
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

		// ------------------------------------------------------------ Video (in the game)
		ConfigCategory video = ConfigCategory.createBuilder()
				.name(tr("category.video"))
				.option(bool("video_enabled", def.videoEnabled, () -> cfg.videoEnabled, v -> {
					if (v && !cfg.videoEnabled) VideoCapture.get().resetFailures();
					cfg.videoEnabled = v;
				}))
				.option(enumOption("video_mode", VideoMode.class, def.videoMode, () -> cfg.videoMode, v -> cfg.videoMode = v,
						VideoMode::display))
				.option(intSlider("video_opacity", def.videoOpacity, () -> cfg.videoOpacity, v -> cfg.videoOpacity = v,
						10, 100, 5, v -> v + "%"))
				.option(intSlider("video_quality", def.videoQuality, () -> cfg.videoQuality, v -> cfg.videoQuality = v,
						360, 1080, 120, v -> v + "p"))
				.option(intSlider("video_fps", def.videoFps, () -> cfg.videoFps, v -> cfg.videoFps = v, 5, 30, 1,
						v -> v + " fps"))
				.group(OptionGroup.createBuilder()
						.name(tr("group.video_pip"))
						.description(OptionDescription.of(tr("group.video_pip.desc")))
						.collapsed(true)
						.option(button("video_move", () -> new VideoPositionScreen(() -> create(parent))))
						.option(intSlider("video_size", def.videoSize, () -> cfg.videoSize, v -> cfg.videoSize = v, 10, 90, 1,
								v -> v + "%"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.video_background"))
						.description(OptionDescription.of(tr("group.video_background.desc")))
						.collapsed(true)
						.option(intSlider("video_bg_opacity", def.videoBackgroundOpacity, () -> cfg.videoBackgroundOpacity,
								v -> cfg.videoBackgroundOpacity = v, 5, 100, 5, v -> v + "%"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.video_floating"))
						.description(OptionDescription.of(tr("group.video_floating.desc")))
						.collapsed(true)
						.option(floatSlider("video_world_width", def.videoWorldWidth, () -> cfg.videoWorldWidth,
								v -> cfg.videoWorldWidth = v, 0.5f, 8f, 0.1f, "%.1f blocks"))
						.option(floatSlider("video_world_distance", def.videoWorldDistance, () -> cfg.videoWorldDistance,
								v -> cfg.videoWorldDistance = v, 1f, 16f, 0.5f, "%.1f blocks"))
						.option(floatSlider("video_world_height", def.videoWorldHeight, () -> cfg.videoWorldHeight,
								v -> cfg.videoWorldHeight = v, -3f, 3f, 0.1f, "%.1f blocks"))
						.option(intSlider("video_side_angle", def.videoSideAngle, () -> cfg.videoSideAngle,
								v -> cfg.videoSideAngle = v, -80, 80, 1, v -> v + "°"))
						.option(intSlider("video_side_tilt", def.videoSideTilt, () -> cfg.videoSideTilt,
								v -> cfg.videoSideTilt = v, -40, 40, 1, v -> v + "°"))
						.option(floatSlider("video_follow_delay", def.videoFollowDelay, () -> cfg.videoFollowDelay,
								v -> cfg.videoFollowDelay = v, 0f, 3f, 0.1f, "%.1f s"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.video_tv"))
						.description(OptionDescription.of(tr("group.video_tv.desc")))
						.collapsed(true)
						.option(bool("video_tv_fit", def.videoTvFitWall, () -> cfg.videoTvFitWall, v -> cfg.videoTvFitWall = v))
						.option(floatSlider("video_tv_width", def.videoTvWidth, () -> cfg.videoTvWidth,
								v -> cfg.videoTvWidth = v, 0.5f, 16f, 0.5f, "%.1f blocks"))
						.option(floatSlider("video_tv_distance", def.videoTvDistance, () -> cfg.videoTvDistance,
								v -> cfg.videoTvDistance = v, 1f, 16f, 0.5f, "%.1f blocks"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.video_mob"))
						.description(OptionDescription.of(tr("group.video_mob.desc")))
						.collapsed(true)
						.option(floatSlider("video_mob_width", def.videoMobWidth, () -> cfg.videoMobWidth,
								v -> cfg.videoMobWidth = v, 0.3f, 4f, 0.1f, "%.1f blocks"))
						.option(floatSlider("video_mob_lift", def.videoMobLift, () -> cfg.videoMobLift,
								v -> cfg.videoMobLift = v, 0f, 3f, 0.05f, "%.2f blocks"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.video_world"))
						.description(OptionDescription.of(tr("group.video_world.desc")))
						.option(intSlider("video_glow", def.videoGlow, () -> cfg.videoGlow, v -> cfg.videoGlow = v,
								0, 100, 5, v -> v == 0 ? "Off" : v + "%"))
						.option(bool("video_subs_attach", def.videoSubsAttach, () -> cfg.videoSubsAttach, v -> cfg.videoSubsAttach = v))
						.option(bool("video_distance_volume", def.videoDistanceVolume, () -> cfg.videoDistanceVolume,
								v -> cfg.videoDistanceVolume = v))
						.option(intSlider("video_volume_range", def.videoVolumeRange, () -> cfg.videoVolumeRange,
								v -> cfg.videoVolumeRange = v, 6, 96, 2, v -> v + " blocks"))
						.build())
				.build();

		// ------------------------------------------------------------ Playback (controlling the browser's video)
		ConfigCategory playback = ConfigCategory.createBuilder()
				.name(tr("category.playback"))
				.option(bool("auto_pause", def.autoPause, () -> cfg.autoPause, v -> cfg.autoPause = v))
				.option(intSlider("seek_step", def.seekStepSec, () -> cfg.seekStepSec, v -> cfg.seekStepSec = v, 1, 60, 1,
						v -> v + " s"))
				.option(intSlider("volume_step", def.volumeStep, () -> cfg.volumeStep, v -> cfg.volumeStep = v, 1, 25, 1,
						v -> v + "%"))
				.option(bool("media_popup", def.mediaPopup, () -> cfg.mediaPopup, v -> cfg.mediaPopup = v))
				.group(OptionGroup.createBuilder()
						.name(tr("group.danger"))
						.description(OptionDescription.of(tr("group.danger.desc")))
						.option(bool("danger_pause", def.dangerPause, () -> cfg.dangerPause, v -> cfg.dangerPause = v))
						.option(enumOption("danger_level", DangerLevel.class, def.dangerLevel, () -> cfg.dangerLevel,
								v -> cfg.dangerLevel = v, DangerLevel::display))
						.option(intSlider("danger_health", def.dangerHealthPercent, () -> cfg.dangerHealthPercent,
								v -> cfg.dangerHealthPercent = v, 10, 90, 5, v -> v + "%"))
						.option(intSlider("danger_burst", def.dangerBurstHearts, () -> cfg.dangerBurstHearts,
								v -> cfg.dangerBurstHearts = v, 1, 10, 1, v -> v + (v == 1 ? " heart" : " hearts")))
						.option(intSlider("danger_range", def.dangerRange, () -> cfg.dangerRange, v -> cfg.dangerRange = v,
								4, 32, 1, v -> v + " blocks"))
						.option(intSlider("danger_resume", def.dangerResumeSec, () -> cfg.dangerResumeSec,
								v -> cfg.dangerResumeSec = v, 1, 30, 1, v -> v + " s"))
						.build())
				.group(OptionGroup.createBuilder()
						.name(tr("group.skip"))
						.description(OptionDescription.of(tr("group.skip.desc")))
						.option(bool("skip_times", def.skipTimes, () -> cfg.skipTimes, v -> cfg.skipTimes = v))
						.option(bool("auto_skip_opening", def.autoSkipOpening, () -> cfg.autoSkipOpening, v -> cfg.autoSkipOpening = v))
						.option(bool("auto_skip_ending", def.autoSkipEnding, () -> cfg.autoSkipEnding, v -> cfg.autoSkipEnding = v))
						.option(intSlider("auto_skip_delay", def.autoSkipDelaySec, () -> cfg.autoSkipDelaySec,
								v -> cfg.autoSkipDelaySec = v, 0, 10, 1, v -> v + " s"))
						.option(intSlider("skip_fallback", def.skipFallbackSec, () -> cfg.skipFallbackSec,
								v -> cfg.skipFallbackSec = v, 30, 180, 5, v -> v + " s"))
						.build())
				.build();

		// ------------------------------------------------------------ Sources
		ConfigCategory sources = ConfigCategory.createBuilder()
				.name(tr("category.sources"))
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
						.collapsed(true)
						.option(bool("use_jimaku", def.useJimaku, () -> cfg.useJimaku, v -> cfg.useJimaku = v))
						.option(text("jimaku_key", () -> cfg.jimakuKey, v -> cfg.jimakuKey = v.trim()))
						.option(bool("jimaku_japanese", def.jimakuJapanese, () -> cfg.jimakuJapanese, v -> cfg.jimakuJapanese = v))
						.build())
				.build();

		return YetAnotherConfigLib.createBuilder()
				.title(tr("title"))
				.category(general)
				.category(subtitles)
				.category(style)
				.category(video)
				.category(playback)
				.category(sources)
				.save(HearNoDubConfig::save)
				.build()
				.generateScreen(parent);
	}

	// ---------------------------------------------------------------- option helpers

	/** A button that saves and opens another screen (Move subtitles, Move video). */
	private static ButtonOption button(String key, Supplier<Screen> screen) {
		return ButtonOption.createBuilder()
				.name(tr(key).withStyle(ChatFormatting.AQUA))
				.description(OptionDescription.of(tr(key + ".desc")))
				.text(tr("open"))
				.action((s, opt) -> {
					HearNoDubConfig.save();
					Minecraft.getInstance().gui.setScreen(screen.get());
				})
				.build();
	}

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

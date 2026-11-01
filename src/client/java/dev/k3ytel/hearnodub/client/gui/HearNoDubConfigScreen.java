package dev.k3ytel.hearnodub.client.gui;

import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.StateManager;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The config screen (YACL). Opened from Mod Menu or the picker's Settings button. */
public final class HearNoDubConfigScreen {
	private HearNoDubConfigScreen() {}

	public static Screen create(Screen parent) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		HearNoDubConfig def = new HearNoDubConfig();

		ConfigCategory general = ConfigCategory.createBuilder()
				.name(tr("category.general"))
				.option(bool("enabled", def.enabled, () -> cfg.enabled, v -> cfg.enabled = v))
				.option(bool("auto_search", def.autoSearch, () -> cfg.autoSearch, v -> cfg.autoSearch = v))
				.option(bool("toasts", def.showToasts, () -> cfg.showToasts, v -> cfg.showToasts = v))
				.option(Option.<Integer>createBuilder()
						.name(tr("nudge"))
						.description(OptionDescription.of(tr("nudge.desc")))
						.stateManager(StateManager.createInstant(Binding.generic(def.nudgeMs, () -> cfg.nudgeMs, v -> cfg.nudgeMs = v)))
						.controller(opt -> IntegerSliderControllerBuilder.create(opt)
								.range(50, 2000).step(50)
								.formatValue(v -> Component.literal(String.format(Locale.ROOT, "%.2f s", v / 1000.0))))
						.build())
				.build();

		ConfigCategory account = ConfigCategory.createBuilder()
				.name(tr("category.account"))
				.option(LabelOption.create(tr("account.help").withStyle(ChatFormatting.GRAY)))
				.option(text("api_key", () -> cfg.apiKey, v -> cfg.apiKey = v.trim()))
				.option(text("username", () -> cfg.username, v -> cfg.username = v.trim()))
				.option(text("password", () -> cfg.password, v -> cfg.password = v))
				.build();

		ConfigCategory look = ConfigCategory.createBuilder()
				.name(tr("category.look"))
				.option(ButtonOption.createBuilder()
						.name(tr("move").withStyle(ChatFormatting.AQUA))
						.description(OptionDescription.of(tr("move.desc")))
						.text(tr("open"))
						.action((screen, opt) -> {
							HearNoDubConfig.save();
							Minecraft.getInstance().gui.setScreen(new PositionScreen(() -> create(parent)));
						})
						.build())
				.option(Option.<Float>createBuilder()
						.name(tr("scale"))
						.stateManager(StateManager.createInstant(Binding.generic(def.textScale, () -> cfg.textScale, v -> cfg.textScale = v)))
						.controller(opt -> FloatSliderControllerBuilder.create(opt)
								.range(0.5f, 3.0f).step(0.1f)
								.formatValue(v -> Component.literal(String.format(Locale.ROOT, "%.1f×", v))))
						.build())
				.option(Option.<Integer>createBuilder()
						.name(tr("opacity"))
						.stateManager(StateManager.createInstant(Binding.generic(def.backgroundOpacity, () -> cfg.backgroundOpacity, v -> cfg.backgroundOpacity = v)))
						.controller(opt -> IntegerSliderControllerBuilder.create(opt)
								.range(0, 100).step(5)
								.formatValue(v -> Component.literal(v + "%")))
						.build())
				.option(Option.<Integer>createBuilder()
						.name(tr("width"))
						.description(OptionDescription.of(tr("width.desc")))
						.stateManager(StateManager.createInstant(Binding.generic(def.maxLineWidth, () -> cfg.maxLineWidth, v -> cfg.maxLineWidth = v)))
						.controller(opt -> IntegerSliderControllerBuilder.create(opt)
								.range(100, 800).step(10)
								.formatValue(v -> Component.literal(v + " px")))
						.build())
				.build();

		return YetAnotherConfigLib.createBuilder()
				.title(tr("title"))
				.category(general)
				.category(account)
				.category(look)
				.save(HearNoDubConfig::save)
				.build()
				.generateScreen(parent);
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

	private static net.minecraft.network.chat.MutableComponent tr(String key) {
		return Component.translatable("hear-no-dub.config." + key);
	}
}

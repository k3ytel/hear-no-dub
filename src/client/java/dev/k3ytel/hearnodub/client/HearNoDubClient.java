package dev.k3ytel.hearnodub.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.gui.SubtitlePickerScreen;
import dev.k3ytel.hearnodub.client.hud.SubtitleHud;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: loads the config, starts the Windows media bridge,
 * registers keybinds and the subtitle HUD element.
 */
public class HearNoDubClient implements ClientModInitializer {
	public static final String MOD_ID = "hear-no-dub";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("main"));

	public static KeyMapping toggleKey;
	public static KeyMapping earlierKey;
	public static KeyMapping laterKey;
	public static KeyMapping pickerKey;

	@Override
	public void onInitializeClient() {
		HearNoDubConfig.load();
		SubtitleManager.get().loadState();
		MediaBridge.get().start();

		toggleKey = register("toggle", InputConstants.KEY_J);
		earlierKey = register("earlier", InputConstants.KEY_LBRACKET);
		laterKey = register("later", InputConstants.KEY_RBRACKET);
		pickerKey = register("picker", InputConstants.KEY_K);

		// Drawn just below chat, so chat stays readable on top of the subtitles
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("subtitles"), new SubtitleHud());

		ClientTickEvents.END_CLIENT_TICK.register(HearNoDubClient::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MediaBridge.get().stop());
	}

	private static KeyMapping register(String name, int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.hear-no-dub." + name, InputConstants.Type.KEYBOARD, key, CATEGORY));
	}

	private static void onEndTick(Minecraft mc) {
		SubtitleManager manager = SubtitleManager.get();
		manager.tick(mc);

		while (toggleKey.consumeClick()) {
			HearNoDubConfig cfg = HearNoDubConfig.get();
			cfg.enabled = !cfg.enabled;
			HearNoDubConfig.save();
			overlay(mc, Component.translatable(cfg.enabled ? "hear-no-dub.msg.on" : "hear-no-dub.msg.off")
					.withStyle(cfg.enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
		}
		while (earlierKey.consumeClick()) {
			manager.nudge(HearNoDubConfig.get().nudgeMs);
			overlay(mc, manager.offsetMessage());
		}
		while (laterKey.consumeClick()) {
			manager.nudge(-HearNoDubConfig.get().nudgeMs);
			overlay(mc, manager.offsetMessage());
		}
		while (pickerKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new SubtitlePickerScreen(null));
			}
		}
	}

	private static void overlay(Minecraft mc, Component msg) {
		if (mc.player != null) mc.player.sendOverlayMessage(msg);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

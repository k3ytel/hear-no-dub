package dev.k3ytel.hearnodub.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.VideoMode;
import dev.k3ytel.hearnodub.client.gui.SubtitlePickerScreen;
import dev.k3ytel.hearnodub.client.hud.MediaOsd;
import dev.k3ytel.hearnodub.client.hud.VideoHud;
import dev.k3ytel.hearnodub.client.hud.ScreenModes;
import dev.k3ytel.hearnodub.client.hud.SubtitleHud;
import dev.k3ytel.hearnodub.client.hud.WorldSubtitles;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import dev.k3ytel.hearnodub.client.media.Playback;
import dev.k3ytel.hearnodub.client.subs.IntroSkipper;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.video.VideoCapture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
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
	public static KeyMapping placeKey;
	public static KeyMapping playPauseKey;
	public static KeyMapping historyKey;
	public static KeyMapping seekBackKey;
	public static KeyMapping seekForwardKey;
	public static KeyMapping skipKey;
	public static KeyMapping volumeDownKey;
	public static KeyMapping volumeUpKey;
	public static KeyMapping videoKey;
	public static KeyMapping videoPlaceKey;
	public static KeyMapping videoModeKey;
	public static KeyMapping tvBiggerKey;
	public static KeyMapping tvSmallerKey;

	/** Auto-pause bookkeeping: was the Esc menu open last tick, and did we pause the video. */
	private static boolean wasPauseMenu;
	private static boolean pausedByUs;

	@Override
	public void onInitializeClient() {
		HearNoDubConfig.load();
		SubtitleManager.get().loadState();
		MediaBridge.get().start();

		toggleKey = register("toggle", InputConstants.KEY_J);
		earlierKey = register("earlier", InputConstants.KEY_LBRACKET);
		laterKey = register("later", InputConstants.KEY_RBRACKET);
		pickerKey = register("picker", InputConstants.KEY_K);
		placeKey = register("place", InputConstants.UNKNOWN.getValue());
		playPauseKey = register("play_pause", InputConstants.KEY_P);
		historyKey = register("history", InputConstants.KEY_H);
		seekBackKey = register("seek_back", InputConstants.KEY_LEFT);
		seekForwardKey = register("seek_forward", InputConstants.KEY_RIGHT);
		skipKey = register("skip", InputConstants.KEY_O);
		volumeDownKey = register("volume_down", InputConstants.KEY_MINUS);
		volumeUpKey = register("volume_up", InputConstants.KEY_EQUALS);
		videoKey = register("video", InputConstants.KEY_V);
		videoPlaceKey = register("video_place", InputConstants.KEY_B);
		videoModeKey = register("video_mode", InputConstants.KEY_N);
		tvBiggerKey = register("tv_bigger", InputConstants.KEY_UP);
		tvSmallerKey = register("tv_smaller", InputConstants.KEY_DOWN);

		// Drawn just below chat, so chat stays readable on top of the subtitles
		// Video under everything else, so the hotbar, subtitles and popups stay on top of it
		HudElementRegistry.addFirst(id("video"), new VideoHud());
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("subtitles"), new SubtitleHud());
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("media_popup"), new MediaOsd());
		// Video first: it tells the subtitles where an attached screen is this frame
		dev.k3ytel.hearnodub.client.hud.VideoWorld.register();
		WorldSubtitles.register();

		ClientTickEvents.END_CLIENT_TICK.register(HearNoDubClient::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			// Put the browser's volume back before the bridge goes away
			if (dev.k3ytel.hearnodub.client.media.VolumeFade.restore()) {
				try {
					Thread.sleep(300);
				} catch (InterruptedException ignored) {
				}
			}
			MediaBridge.get().stop();
			VideoCapture.get().stop();
		});
	}

	private static KeyMapping register(String name, int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.hear-no-dub." + name, InputConstants.Type.KEYBOARD, key, CATEGORY));
	}

	private static void onEndTick(Minecraft mc) {
		SubtitleManager manager = SubtitleManager.get();
		manager.tick(mc);
		IntroSkipper.get().tick(mc, manager.session());
		VideoCapture.get().tick();
		dev.k3ytel.hearnodub.client.media.VolumeFade.tick(mc);
		ScreenModes.tick(mc);
		autoPause(mc, manager);

		HearNoDubConfig config = HearNoDubConfig.get();
		while (seekBackKey.consumeClick()) {
			Playback.skipBy(mc, -config.seekStepSec * 1000L,
					Component.translatable("hear-no-dub.osd.back", config.seekStepSec));
		}
		while (seekForwardKey.consumeClick()) {
			Playback.skipBy(mc, config.seekStepSec * 1000L,
					Component.translatable("hear-no-dub.osd.forward", config.seekStepSec));
		}
		while (skipKey.consumeClick()) {
			IntroSkipper.get().skipPressed(mc);
		}
		while (volumeDownKey.consumeClick()) {
			Playback.changeVolume(mc, -1);
		}
		while (volumeUpKey.consumeClick()) {
			Playback.changeVolume(mc, 1);
		}
		while (videoPlaceKey.consumeClick()) {
			dev.k3ytel.hearnodub.client.video.TvScreen.place(mc);
		}
		while (tvBiggerKey.consumeClick()) {
			dev.k3ytel.hearnodub.client.video.TvScreen.resize(mc, 1.1f);
		}
		while (tvSmallerKey.consumeClick()) {
			dev.k3ytel.hearnodub.client.video.TvScreen.resize(mc, 1 / 1.1f);
		}
		while (videoModeKey.consumeClick()) {
			// Cycle through the video modes (and turn the video on if it was off)
			VideoMode[] modes = VideoMode.values();
			if (config.videoEnabled) config.videoMode = modes[(config.videoMode.ordinal() + 1) % modes.length];
			else VideoCapture.get().resetFailures();
			config.videoEnabled = true;
			HearNoDubConfig.save();
			overlay(mc, Component.translatable("hear-no-dub.msg.video_mode", config.videoMode.display()));
		}
		while (videoKey.consumeClick()) {
			config.videoEnabled = !config.videoEnabled;
			if (config.videoEnabled) VideoCapture.get().resetFailures();
			HearNoDubConfig.save();
			overlay(mc, Component.translatable(config.videoEnabled ? "hear-no-dub.msg.video_on" : "hear-no-dub.msg.video_off"));
		}

		while (playPauseKey.consumeClick()) {
			MediaBridge.Session s = manager.session();
			if (s == null || !MediaBridge.get().sendCommand("toggle", s.app)) {
				overlay(mc, Component.translatable("hear-no-dub.msg.nothing_playing").withStyle(ChatFormatting.GRAY));
			} else {
				pausedByUs = false; // you took over
				overlay(mc, Component.translatable(s.isPlaying() ? "hear-no-dub.msg.pausing" : "hear-no-dub.msg.playing"));
			}
		}
		while (historyKey.consumeClick()) {
			manager.toggleHistory();
		}

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
		while (placeKey.consumeClick()) {
			WorldSubtitles.placeHere();
			overlay(mc, Component.translatable("hear-no-dub.msg.placed"));
		}
		while (pickerKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new SubtitlePickerScreen(null));
			}
		}
	}

	/**
	 * Pauses the video when you open the Esc menu and resumes it when you close it, but only
	 * if the mod paused it. Alt-tabbing (which also opens the menu) is ignored, since that's
	 * usually you going to the video.
	 */
	private static void autoPause(Minecraft mc, SubtitleManager manager) {
		boolean pauseMenu = mc.gui.screen() instanceof PauseScreen;
		if (pauseMenu && !wasPauseMenu) {
			MediaBridge.Session s = manager.session();
			if (HearNoDubConfig.get().autoPause && mc.isWindowActive() && s != null && s.isPlaying()) {
				pausedByUs = MediaBridge.get().sendCommand("pause", s.app);
			}
		} else if (mc.gui.screen() == null && pausedByUs) {
			// back in the game (Options and other menus opened from the Esc menu don't count)
			MediaBridge.Session s = manager.session();
			if (s != null) MediaBridge.get().sendCommand("play", s.app);
			pausedByUs = false;
		}
		wasPauseMenu = pauseMenu;
	}

	private static void overlay(Minecraft mc, Component msg) {
		if (mc.player != null) mc.player.sendOverlayMessage(msg);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

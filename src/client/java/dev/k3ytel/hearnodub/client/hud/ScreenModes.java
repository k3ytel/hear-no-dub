package dev.k3ytel.hearnodub.client.hud;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.ScreenMode;
import dev.k3ytel.hearnodub.client.render.SubStyle;
import dev.k3ytel.hearnodub.client.render.SubText;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.List;

/** The simple screen modes: action bar (above the hotbar) and chat. Called every client tick. */
public final class ScreenModes {
	private ScreenModes() {}

	private static long lastChatKey = Long.MIN_VALUE;
	private static String lastBarText = "";
	private static int barRefresh;

	public static void tick(Minecraft mc) {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (!cfg.enabled || mc.player == null) return;
		if (cfg.screenMode != ScreenMode.ACTION_BAR && cfg.screenMode != ScreenMode.CHAT) return;

		SubtitleManager m = SubtitleManager.get();
		List<SubtitleTrack.Cue> cues = m.activeCues();
		if (cues.isEmpty()) {
			lastBarText = "";
			return;
		}

		if (cfg.screenMode == ScreenMode.CHAT) {
			long key = cues.getFirst().startMs();
			if (key == lastChatKey) return;
			lastChatKey = key;
			for (SubtitleTrack.Cue c : cues) {
				MutableComponent msg = Component.literal("▸ ").withStyle(ChatFormatting.DARK_GRAY);
				if (!c.speaker().isBlank()) {
					msg.append(Component.literal(c.speaker() + ": ").withStyle(st -> st.withColor(TextColor.fromRgb(0xFFD866))));
				}
				msg.append(Component.literal(c.text().replace('\n', ' ')));
				mc.gui.hud.getChat().addClientSystemMessage(msg);
			}
			return;
		}

		// ACTION_BAR: re-send when the text changes (karaoke/typewriter), and every second so it stays up
		SubStyle style = SubStyle.resolve(cfg, false);
		SubText.Block block = SubText.build(mc.font, cues, null, m.subtitleTimeMs(), cfg, 10_000, style.textColor());
		if (block.isEmpty()) return;
		MutableComponent bar = Component.empty();
		StringBuilder plain = new StringBuilder();
		for (int i = 0; i < block.lines().size(); i++) {
			SubText.Line line = block.lines().get(i);
			if (line.preview()) continue;
			if (i > 0) {
				bar.append("  /  ");
				plain.append(" / ");
			}
			StringBuilder sb = new StringBuilder();
			MutableComponent part = Component.empty();
			line.colored().accept((index, st, codePoint) -> {
				String ch = new String(Character.toChars(codePoint));
				sb.append(ch);
				part.append(Component.literal(ch).withStyle(st));
				return true;
			});
			bar.append(part);
			plain.append(sb);
		}
		String text = plain.toString();
		if (!text.equals(lastBarText) || ++barRefresh >= 20) {
			barRefresh = 0;
			lastBarText = text;
			mc.gui.hud.setOverlayMessage(bar, false);
		}
	}
}

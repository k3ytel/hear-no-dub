package dev.k3ytel.hearnodub.client.hud;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Draws the current subtitle lines as a caption box on the HUD. */
public class SubtitleHud implements HudElement {
	public static final int MAX_LINES = 3;
	private static final int PAD_X = 4;
	private static final int PAD_Y = 3;
	private static final int LINE_GAP = 1;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (!cfg.enabled || mc.gui.hud.isHidden()) return;

		List<SubtitleTrack.Cue> cues = SubtitleManager.get().activeCues();
		if (cues.isEmpty()) return;

		List<String> text = new ArrayList<>();
		for (SubtitleTrack.Cue c : cues) text.add(c.text());
		draw(g, mc.font, text, cfg);
	}

	/** Shared with the position screen, which draws a sample caption. */
	public static void draw(GuiGraphicsExtractor g, Font font, List<String> texts, HearNoDubConfig cfg) {
		Box box = layout(font, texts, cfg, g.guiWidth(), g.guiHeight());
		if (box == null) return;

		g.pose().pushMatrix();
		g.pose().translate(box.x, box.y);
		g.pose().scale(box.scale, box.scale);

		int alpha = Math.round(cfg.backgroundOpacity / 100f * 255f);
		if (alpha > 0) g.fill(0, 0, box.innerW, box.innerH, alpha << 24);

		int y = PAD_Y;
		for (FormattedCharSequence line : box.lines) {
			int w = font.width(line);
			g.text(font, line, (box.innerW - w) / 2, y, 0xFFFFFFFF, true);
			y += font.lineHeight + LINE_GAP;
		}
		g.pose().popMatrix();
	}

	/** Where the caption box goes, in GUI pixels. Unscaled sizes are inner*. */
	public record Box(float x, float y, float scale, int innerW, int innerH, List<FormattedCharSequence> lines) {
		public float width() { return innerW * scale; }
		public float height() { return innerH * scale; }
	}

	public static Box layout(Font font, List<String> texts, HearNoDubConfig cfg, int screenW, int screenH) {
		float scale = cfg.textScale;
		int wrapAt = Math.max(40, Math.min(cfg.maxLineWidth, (int) ((screenW - 8) / scale) - PAD_X * 2));

		List<FormattedCharSequence> lines = new ArrayList<>();
		outer:
		for (String t : texts) {
			for (String part : t.split("\n")) {
				for (FormattedCharSequence seq : font.split(Component.literal(part), wrapAt)) {
					if (lines.size() >= MAX_LINES) break outer;
					lines.add(seq);
				}
			}
		}
		if (lines.isEmpty()) return null;

		int maxW = 0;
		for (FormattedCharSequence l : lines) maxW = Math.max(maxW, font.width(l));
		int innerW = maxW + PAD_X * 2;
		int innerH = lines.size() * (font.lineHeight + LINE_GAP) - LINE_GAP + PAD_Y * 2;

		float w = innerW * scale, h = innerH * scale;
		float x = cfg.posX * screenW - w / 2f;
		float y = cfg.posY * screenH - h;
		x = Math.max(2, Math.min(screenW - w - 2, x));
		y = Math.max(2, Math.min(screenH - h - 2, y));
		return new Box(x, y, scale, innerW, innerH, lines);
	}
}

package dev.k3ytel.hearnodub.client.hud;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.config.ScreenMode;
import dev.k3ytel.hearnodub.client.render.SubStyle;
import dev.k3ytel.hearnodub.client.render.SubText;
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
import java.util.Locale;

/** Draws the HUD caption, and the "what did they say?" history panel. */
public class SubtitleHud implements HudElement {
	public static final int MAX_LINES = SubText.MAX_LINES;
	private static final int PAD_X = 4;
	private static final int PAD_Y = 3;
	private static final int[][] RING1 = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
	private static final int[][] RING2 = {{-2, 0}, {2, 0}, {0, -2}, {0, 2}, {-2, -1}, {2, -1}, {-2, 1}, {2, 1},
			{-1, -2}, {1, -2}, {-1, 2}, {1, 2}};

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (mc.gui.hud.isHidden()) return;

		SubtitleManager m = SubtitleManager.get();
		if (m.historyVisible()) drawHistory(g, mc.font, m);
		LyricsHud.drawUpcoming(g, mc.font, m, cfg);

		if (cfg.enabled && cfg.screenMode == ScreenMode.DANMAKU) {
			Danmaku.draw(g, mc.font, m, cfg);
			return;
		}
		if (!cfg.enabled || cfg.screenMode != ScreenMode.HUD) return;
		if (LyricsHud.draw(g, mc.font, m, cfg)) return;
		List<SubtitleTrack.Cue> cues = m.activeCues();
		if (cues.isEmpty()) return;

		SubStyle style = SubStyle.resolve(cfg, false);
		SubText.Block block = SubText.build(mc.font, cues, m.nextCue(), m.subtitleTimeMs(), cfg,
				wrapWidth(cfg, g.guiWidth()), style.textColor());
		drawBlock(g, mc.font, block, style, cfg);
	}

	// ---------------------------------------------------------------- caption

	private static int wrapWidth(HearNoDubConfig cfg, int screenW) {
		return Math.max(40, Math.min(cfg.maxLineWidth, (int) ((screenW - 8) / cfg.textScale) - PAD_X * 2));
	}

	/** Where the caption box goes, in GUI pixels. */
	public record Box(float x, float y, float scale, int innerW, int innerH) {
		public float width() { return innerW * scale; }
		public float height() { return innerH * scale; }
	}

	private static Box layout(SubText.Block block, HearNoDubConfig cfg, int screenW, int screenH) {
		float scale = cfg.textScale;
		int innerW = block.width() + PAD_X * 2;
		int innerH = block.height() + PAD_Y * 2;
		float w = innerW * scale, h = innerH * scale;
		float x = cfg.posX * screenW - w / 2f;
		float y = cfg.posY * screenH - h;
		x = Math.max(2, Math.min(screenW - w - 2, x));
		y = Math.max(2, Math.min(screenH - h - 2, y));
		return new Box(x, y, scale, innerW, innerH);
	}

	public static void drawBlock(GuiGraphicsExtractor g, Font font, SubText.Block block, SubStyle style, HearNoDubConfig cfg) {
		if (block.isEmpty()) return;
		Box box = layout(block, cfg, g.guiWidth(), g.guiHeight());

		g.pose().pushMatrix();
		// Scale around the bottom centre so the pop animation grows in place
		g.pose().translate(box.x() + box.width() / 2f, box.y() + box.height());
		g.pose().scale(box.scale() * block.scale(), box.scale() * block.scale());
		g.pose().translate(-box.innerW() / 2f, -box.innerH());

		float a = block.alpha();
		if (style.box()) g.fill(0, 0, box.innerW(), box.innerH(), SubStyle.fade(style.boxColor(), a));

		int y = PAD_Y;
		for (SubText.Line line : block.lines()) {
			int x = (box.innerW() - line.width()) / 2;
			drawLine(g, font, line, x, y, style, line.preview() ? a * 0.6f : a);
			y += font.lineHeight + SubText.LINE_GAP;
		}
		g.pose().popMatrix();
	}

	public static void drawLine(GuiGraphicsExtractor g, Font font, SubText.Line line, int x, int y, SubStyle style, float alpha) {
		if (style.glow() && !line.preview()) {
			int c1 = SubStyle.fade(style.glowColor(), 0.16f * alpha);
			int c2 = SubStyle.fade(style.glowColor(), 0.08f * alpha);
			for (int[] d : RING2) text(g, font, line.shape(), x + d[0], y + d[1], c2, false);
			for (int[] d : RING1) text(g, font, line.shape(), x + d[0], y + d[1], c1, false);
		}
		if (style.outline()) {
			int oc = SubStyle.fade(style.outlineColor(), alpha);
			for (int[] d : RING1) text(g, font, line.shape(), x + d[0], y + d[1], oc, false);
		}
		text(g, font, line.colored(), x, y, SubStyle.fade(0xFFFFFFFF, alpha), style.shadow());
	}

	/** Skips nearly invisible text: Minecraft draws alpha below 4 as fully opaque. */
	private static void text(GuiGraphicsExtractor g, Font font, FormattedCharSequence seq, int x, int y, int color, boolean shadow) {
		if ((color >>> 24) < 6) return;
		g.text(font, seq, x, y, color, shadow);
	}

	// ---------------------------------------------------------------- position screen helpers

	private static SubText.Block sample(Font font, List<String> texts, HearNoDubConfig cfg, int screenW) {
		List<SubtitleTrack.Cue> cues = new ArrayList<>();
		for (String t : texts) cues.add(new SubtitleTrack.Cue(0, 10_000, t));
		return SubText.build(font, cues, null, 9_000, cfg, wrapWidth(cfg, screenW), SubStyle.resolve(cfg, false).textColor());
	}

	/** Draws a sample caption (position screen). */
	public static void draw(GuiGraphicsExtractor g, Font font, List<String> texts, HearNoDubConfig cfg) {
		drawBlock(g, font, sample(font, texts, cfg, g.guiWidth()), SubStyle.resolve(cfg, false), cfg);
	}

	/** Box of the sample caption (position screen). */
	public static Box layout(Font font, List<String> texts, HearNoDubConfig cfg, int screenW, int screenH) {
		SubText.Block block = sample(font, texts, cfg, screenW);
		return block.isEmpty() ? null : layout(block, cfg, screenW, screenH);
	}

	// ---------------------------------------------------------------- history panel

	private static void drawHistory(GuiGraphicsExtractor g, Font font, SubtitleManager m) {
		List<SubtitleTrack.Cue> recent = m.recentCues(5);
		int maxW = Math.min(300, g.guiWidth() - 20);
		List<String> rows = new ArrayList<>();
		for (SubtitleTrack.Cue c : recent) {
			long s = c.startMs() / 1000;
			String row = String.format(Locale.ROOT, "%d:%02d  ", s / 60, s % 60)
					+ (c.speaker().isBlank() ? "" : c.speaker() + ": ")
					+ c.text().replace('\n', ' ');
			if (font.width(row) > maxW) row = font.plainSubstrByWidth(row, maxW - font.width("…")) + "…";
			rows.add(row);
		}
		Component title = Component.translatable("hear-no-dub.history.title");
		if (rows.isEmpty()) rows.add(Component.translatable("hear-no-dub.history.empty").getString());

		int w = font.width(title);
		for (String r : rows) w = Math.max(w, font.width(r));
		int lh = font.lineHeight + 2;
		int h = lh * (rows.size() + 1) + 4;
		int x = 6, y = 6;
		g.fill(x - 3, y - 3, x + w + 3, y + h, 0xB0000000);
		g.text(font, title, x, y, 0xFFFFD866, true);
		int ry = y + lh + 2;
		for (int i = 0; i < rows.size(); i++) {
			boolean last = i == rows.size() - 1;
			g.text(font, rows.get(i), x, ry, last ? 0xFFFFFFFF : 0xFFBBBBBB, true);
			ry += lh;
		}
	}
}

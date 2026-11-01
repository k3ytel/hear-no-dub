package dev.k3ytel.hearnodub.client.hud;

import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.render.SubText;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Danmaku (bullet comments, like Niconico / Bilibili): every subtitle line flies across the
 * screen from right to left in its own lane. Positions follow the video time, so the lines
 * freeze when you pause and rewind when you seek back.
 */
public final class Danmaku {
	private Danmaku() {}

	private static final int TOP = 8;
	private static final int GAP_PX = 24;

	/** Lane of each line, kept so lines never jump between lanes. */
	private static final Map<SubtitleTrack.Cue, Integer> lanes = new HashMap<>();
	private static final Map<SubtitleTrack.Cue, Float> widths = new HashMap<>();
	private static long lastNow = Long.MIN_VALUE;
	private static String lastTrack;

	public static void draw(GuiGraphicsExtractor g, Font font, SubtitleManager m, HearNoDubConfig cfg) {
		long now = m.subtitleTimeMs();
		if (now < 0) return;
		long dur = cfg.danmakuSeconds * 1000L;
		// Seeked back or another subtitle file: start over
		String track = m.trackKey();
		if (now < lastNow - 1000 || (track != null && !track.equals(lastTrack))) {
			lanes.clear();
			widths.clear();
		}
		lastNow = now;
		lastTrack = track;

		int screenW = g.guiWidth(), screenH = g.guiHeight();
		float scale = cfg.danmakuScale;
		float lineH = font.lineHeight * scale + 3;
		int laneCount = Math.max(1, (int) ((screenH * cfg.danmakuArea / 100f - TOP) / lineH));
		int alpha = Math.round(255 * cfg.danmakuOpacity / 100f);

		List<SubtitleTrack.Cue> visible = m.cuesStartedBetween(now - dur, now);
		// Forget lines that are long gone
		lanes.keySet().removeIf(c -> c.startMs() < now - dur * 2);
		widths.keySet().retainAll(lanes.keySet());

		for (SubtitleTrack.Cue c : visible) {
			String text = text(c);
			float w = widths.computeIfAbsent(c, k -> font.width(text) * scale);
			Integer lane = lanes.get(c);
			if (lane == null) {
				lane = pickLane(c, w, laneCount, screenW, dur);
				lanes.put(c, lane);
			}
			float speed = (screenW + w) / dur; // px per ms
			float x = screenW - (now - c.startMs()) * speed;
			if (x > screenW || x + w < 0) continue;
			float y = TOP + (lane % laneCount) * lineH;
			int rgb = SubText.colorFor(c, cfg, cfg.customTextColor) & 0xFFFFFF;
			if (!cfg.speakerColors || c.speaker().isBlank()) rgb = 0xFFFFFF;
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(scale, scale);
			g.text(font, text, 0, 0, (alpha << 24) | rgb, true);
			g.pose().popMatrix();
		}
	}

	private static String text(SubtitleTrack.Cue c) {
		return c.text().replace('\n', ' ');
	}

	/**
	 * The first lane where the line before it has moved far enough left that the two never
	 * touch; if every lane is busy, the lane whose last line started longest ago.
	 */
	private static int pickLane(SubtitleTrack.Cue cue, float w, int laneCount, int screenW, long dur) {
		List<SubtitleTrack.Cue> lastInLane = new ArrayList<>();
		for (int i = 0; i < laneCount; i++) lastInLane.add(null);
		for (Map.Entry<SubtitleTrack.Cue, Integer> e : lanes.entrySet()) {
			int l = e.getValue() % laneCount;
			SubtitleTrack.Cue prev = lastInLane.get(l);
			if (e.getKey().startMs() <= cue.startMs() && (prev == null || e.getKey().startMs() > prev.startMs())) {
				lastInLane.set(l, e.getKey());
			}
		}
		int oldest = 0;
		long oldestStart = Long.MAX_VALUE;
		float speed = (screenW + w) / dur;
		for (int l = 0; l < laneCount; l++) {
			SubtitleTrack.Cue prev = lastInLane.get(l);
			if (prev == null) return l;
			float pw = widths.getOrDefault(prev, 0f);
			float pSpeed = (screenW + pw) / dur;
			long dt = cue.startMs() - prev.startMs();
			// where the previous line's tail is when this one appears ...
			boolean clearAtStart = screenW - dt * pSpeed + pw + GAP_PX <= screenW;
			// ... and, if this one is faster, that it doesn't catch up before the other leaves
			long prevLeavesAt = prev.startMs() + dur;
			float newXThen = screenW - (prevLeavesAt - cue.startMs()) * speed;
			boolean noCatchUp = speed <= pSpeed || newXThen >= 0;
			if (clearAtStart && noCatchUp) return l;
			if (prev.startMs() < oldestStart) {
				oldestStart = prev.startMs();
				oldest = l;
			}
		}
		return oldest;
	}
}

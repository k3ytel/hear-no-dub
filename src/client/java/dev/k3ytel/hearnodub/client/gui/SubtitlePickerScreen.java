package dev.k3ytel.hearnodub.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.k3ytel.hearnodub.client.HearNoDubClient;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;
import dev.k3ytel.hearnodub.client.media.MediaBridge;
import dev.k3ytel.hearnodub.client.media.Playback;
import dev.k3ytel.hearnodub.client.subs.IntroSkipper;
import dev.k3ytel.hearnodub.client.subs.SubtitleManager;
import dev.k3ytel.hearnodub.client.subs.SubtitleTrack;
import dev.k3ytel.hearnodub.client.subs.TitleGuesser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Press K: shows what's playing (with a progress bar you can click to jump), lets you search
 * for subtitles or pick a local file, and fix the sync, either with Earlier/Later or by
 * clicking the line you just heard. The lines view can also be searched, and set to jump the
 * video to the line you click.
 */
public class SubtitlePickerScreen extends Screen {
	private static final int ROW_H = 22;
	private static final int BAR_Y = 46;
	private static final int BAR_H = 4;

	private enum View { RESULTS, LINES }

	/** Remembered between openings: does clicking a line sync or jump? */
	private static boolean jumpMode = false;

	private final Screen parent;
	private final SubtitleManager manager = SubtitleManager.get();

	private View view = View.RESULTS;

	private EditBox queryBox;
	private EditBox seasonBox;
	private EditBox episodeBox;
	private Button searchButton;
	private Button localButton;
	private EditBox lineSearch;
	private Button modeButton;
	private Button switchButton;
	private Button viewButton;

	private int listLeft, listTop, listWidth, visibleRows;
	private int scroll = 0;
	private List<SubtitleManager.Entry> lastResults = List.of();
	/** The cue list drawn last frame, so a click hits exactly what was on screen. */
	private List<SubtitleTrack.Cue> shownCues = List.of();

	// Line search results, rebuilt only when the text or the track changes
	private String filterFor = null;
	private List<SubtitleTrack.Cue> filterSource = null;
	private List<SubtitleTrack.Cue> filtered = List.of();

	public SubtitlePickerScreen(Screen parent) {
		super(Component.translatable("hear-no-dub.picker.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		TitleGuesser.Guess g = manager.guess();
		String q = queryBox != null ? queryBox.getValue() : (g != null ? g.query() : "");
		String s = seasonBox != null ? seasonBox.getValue() : (g != null && g.season() > 0 ? String.valueOf(g.season()) : "");
		String e = episodeBox != null ? episodeBox.getValue() : (g != null && g.episode() > 0 ? String.valueOf(g.episode()) : "");
		String lq = lineSearch != null ? lineSearch.getValue() : "";

		int rowW = Math.min(width - 20, 420);
		int left = (width - rowW) / 2;
		int y = 58;

		int searchW = 60, localW = 60, numW = 30, gap = 4;
		int queryW = rowW - searchW - localW - numW * 2 - gap * 4;

		queryBox = new EditBox(font, left, y, queryW, 18, Component.translatable("hear-no-dub.picker.query"));
		queryBox.setMaxLength(120);
		queryBox.setHint(Component.translatable("hear-no-dub.picker.query").withStyle(ChatFormatting.DARK_GRAY));
		queryBox.setValue(q);
		addRenderableWidget(queryBox);

		seasonBox = new EditBox(font, left + queryW + gap, y, numW, 18, Component.translatable("hear-no-dub.picker.season"));
		seasonBox.setMaxLength(2);
		seasonBox.setHint(Component.literal("S").withStyle(ChatFormatting.DARK_GRAY));
		seasonBox.setValue(s);
		addRenderableWidget(seasonBox);

		episodeBox = new EditBox(font, left + queryW + numW + gap * 2, y, numW, 18, Component.translatable("hear-no-dub.picker.episode"));
		episodeBox.setMaxLength(4);
		episodeBox.setHint(Component.literal("Ep").withStyle(ChatFormatting.DARK_GRAY));
		episodeBox.setValue(e);
		addRenderableWidget(episodeBox);

		searchButton = Button.builder(Component.translatable("hear-no-dub.picker.search"), b -> doSearch())
				.bounds(left + queryW + numW * 2 + gap * 3, y - 1, searchW, 20).build();
		addRenderableWidget(searchButton);
		localButton = Button.builder(Component.translatable("hear-no-dub.picker.local"), b -> {
			manager.showLocal();
			view = View.RESULTS;
			scroll = 0;
		}).bounds(left + rowW - localW, y - 1, localW, 20).build();
		addRenderableWidget(localButton);

		// Lines view: search box + "click: sync / jump" switch, in the same row
		int modeW = 90;
		lineSearch = new EditBox(font, left, y, rowW - modeW - gap, 18, Component.translatable("hear-no-dub.picker.line_search"));
		lineSearch.setMaxLength(100);
		lineSearch.setHint(Component.translatable("hear-no-dub.picker.line_search").withStyle(ChatFormatting.DARK_GRAY));
		lineSearch.setValue(lq);
		lineSearch.setResponder(text -> scroll = 0);
		addRenderableWidget(lineSearch);
		modeButton = Button.builder(modeLabel(), b -> {
			jumpMode = !jumpMode;
			b.setMessage(modeLabel());
		}).bounds(left + rowW - modeW, y - 1, modeW, 20).build();
		addRenderableWidget(modeButton);

		listLeft = left;
		listWidth = rowW;
		listTop = y + 34;
		int listBottom = height - 40;
		visibleRows = Math.max(1, (listBottom - listTop) / ROW_H);

		// Bottom buttons
		int count = 9;
		int by = height - 26;
		int bw = Math.min(80, (width - 20 - (count - 1) * gap) / count);
		int bx = (width - (bw * count + gap * (count - 1))) / 2;

		viewButton = Button.builder(viewLabel(), b -> {
			view = view == View.RESULTS ? View.LINES : View.RESULTS;
			scroll = 0;
			b.setMessage(viewLabel());
			updateRowWidgets();
		}).bounds(bx, by, bw, 20).build();
		addRenderableWidget(viewButton);
		bx += bw + gap;
		switchButton = Button.builder(Component.translatable("hear-no-dub.picker.switch"), b -> manager.switchSession())
				.bounds(bx, by, bw, 20).build();
		addRenderableWidget(switchButton);
		bx += bw + gap;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.picker.earlier"),
				b -> manager.nudge(HearNoDubConfig.get().nudgeMs)).bounds(bx, by, bw, 20).build());
		bx += bw + gap;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.picker.later"),
				b -> manager.nudge(-HearNoDubConfig.get().nudgeMs)).bounds(bx, by, bw, 20).build());
		bx += bw + gap;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.picker.reset"),
				b -> manager.resetShift()).bounds(bx, by, bw, 20).build());
		bx += bw + gap;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.picker.unload"),
				b -> manager.unload()).bounds(bx, by, bw, 20).build());
		bx += bw + gap;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.picker.video"),
				b -> minecraft.gui.setScreen(new VideoMenuScreen(this))).bounds(bx, by, bw, 20).build());
		bx += bw + gap;
		addRenderableWidget(Button.builder(Component.translatable("hear-no-dub.picker.settings"),
				b -> minecraft.gui.setScreen(HearNoDubConfigScreen.create(this))).bounds(bx, by, bw, 20).build());
		bx += bw + gap;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(bx, by, bw, 20).build());

		updateRowWidgets();
	}

	/** Shows the subtitle search row or the line search row, depending on the view. */
	private void updateRowWidgets() {
		boolean results = view == View.RESULTS;
		queryBox.visible = results;
		seasonBox.visible = results;
		episodeBox.visible = results;
		searchButton.visible = results;
		localButton.visible = results;
		lineSearch.visible = !results;
		modeButton.visible = !results;
		if (results) {
			lineSearch.setFocused(false);
			setInitialFocus(queryBox);
		} else {
			queryBox.setFocused(false);
			seasonBox.setFocused(false);
			episodeBox.setFocused(false);
			setInitialFocus(lineSearch);
		}
	}

	private Component viewLabel() {
		return Component.translatable(view == View.RESULTS ? "hear-no-dub.picker.view.lines" : "hear-no-dub.picker.view.results");
	}

	private static Component modeLabel() {
		return Component.translatable(jumpMode ? "hear-no-dub.picker.mode.jump" : "hear-no-dub.picker.mode.sync");
	}

	private void doSearch() {
		manager.search(minecraft, queryBox.getValue(), parseInt(seasonBox.getValue()), parseInt(episodeBox.getValue()), false);
		view = View.RESULTS;
		viewButton.setMessage(viewLabel());
		updateRowWidgets();
		scroll = 0;
	}

	private static int parseInt(String s) {
		try {
			return Math.max(0, Integer.parseInt(s.trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private boolean searchingLines() {
		return view == View.LINES && !lineSearch.getValue().isBlank();
	}

	/** Lines containing the search text (case and punctuation don't matter). */
	private List<SubtitleTrack.Cue> filteredCues() {
		String q = normalize(lineSearch.getValue());
		List<SubtitleTrack.Cue> all = manager.allCues();
		if (q.equals(filterFor) && all == filterSource) return filtered;
		List<SubtitleTrack.Cue> out = new ArrayList<>();
		for (SubtitleTrack.Cue c : all) {
			if (normalize(c.text()).contains(q) || (!c.speaker().isBlank() && normalize(c.speaker()).contains(q))) out.add(c);
		}
		filterFor = q;
		filterSource = all;
		filtered = out;
		return out;
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		switchButton.active = manager.sessionCount() > 1;
		viewButton.active = manager.hasTrack() || view == View.LINES;

		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		drawNowPlaying(g);
		drawProgressBar(g, mouseX, mouseY);

		// Status + sync
		Component status;
		if (view == View.RESULTS) {
			status = manager.status();
		} else if (searchingLines()) {
			int n = filteredCues().size();
			status = (n == 0 ? Component.translatable("hear-no-dub.picker.no_match") : Component.translatable("hear-no-dub.picker.lines_found", n))
					.withStyle(ChatFormatting.AQUA);
		} else {
			status = Component.translatable(jumpMode ? "hear-no-dub.picker.lines_hint_jump" : "hear-no-dub.picker.lines_hint")
					.withStyle(ChatFormatting.AQUA);
		}
		g.text(font, ellipsize(status.getString(), listWidth - 130), listLeft, listTop - 12, colorOf(status));
		if (manager.hasTrack()) {
			Component sync = manager.offsetText();
			g.text(font, sync, listLeft + listWidth - font.width(sync), listTop - 12, 0xFFAAAAAA);
		}

		if (view == View.RESULTS) drawResults(g, mouseX, mouseY);
		else drawLines(g, mouseX, mouseY);

		// Bottom row: skip times on the left, OpenSubtitles quota on the right
		Component quota = manager.quotaText();
		int quotaW = quota == null ? 0 : font.width(quota);
		if (quota != null) g.text(font, quota, listLeft + listWidth - quotaW, height - 37, colorOf(quota));
		Component skip = IntroSkipper.get().info();
		if (!skip.getString().isEmpty()) {
			g.text(font, ellipsize(skip.getString(), listWidth - quotaW - 10), listLeft, height - 37, 0xFF888888);
		}
	}

	private void drawNowPlaying(GuiGraphicsExtractor g) {
		MediaBridge.Session s = manager.session();
		Component playing;
		MediaBridge.Status bridge = MediaBridge.get().status();
		if (bridge == MediaBridge.Status.UNSUPPORTED) {
			playing = Component.translatable("hear-no-dub.picker.unsupported").withStyle(ChatFormatting.RED);
		} else if (bridge == MediaBridge.Status.FAILED) {
			playing = Component.translatable("hear-no-dub.picker.bridge_failed", MediaBridge.get().lastError()).withStyle(ChatFormatting.RED);
		} else if (s == null) {
			playing = Component.translatable("hear-no-dub.picker.nothing").withStyle(ChatFormatting.GRAY);
		} else {
			long now = System.currentTimeMillis();
			playing = Component.translatable("hear-no-dub.picker.playing",
					s.title, s.appName(),
					time(s.livePositionMs(now)) + " / " + time(s.end),
					Component.translatable(s.isPlaying() ? "hear-no-dub.picker.state.playing" : "hear-no-dub.picker.state.paused"));
		}
		g.centeredText(font, ellipsize(playing.getString(), listWidth + 60), width / 2, 22, s == null ? 0xFFAAAAAA : 0xFFDDDDDD);

		TitleGuesser.Guess guess = manager.guess();
		if (guess != null && !guess.tabTitle().isEmpty()) {
			g.centeredText(font, ellipsize(Component.translatable("hear-no-dub.picker.tab", guess.tabTitle()).getString(), listWidth + 60),
					width / 2, 34, 0xFF777777);
		}
	}

	/** Progress bar under "now playing": openings/endings in yellow, click anywhere to jump. */
	private void drawProgressBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		MediaBridge.Session s = manager.session();
		if (s == null || s.end <= 0) return;
		int x0 = listLeft, x1 = listLeft + listWidth, w = listWidth;
		boolean hover = overBar(mouseX, mouseY);
		int y0 = BAR_Y, y1 = BAR_Y + BAR_H;
		g.fill(x0, y0, x1, y1, hover ? 0x80FFFFFF : 0x50FFFFFF);
		for (IntroSkipper.Interval i : IntroSkipper.get().intervals()) {
			int a = x0 + (int) (w * Math.min(1.0, i.startMs() / (double) s.end));
			int b = x0 + (int) (w * Math.min(1.0, i.endMs() / (double) s.end));
			g.fill(a, y0, Math.max(a + 1, b), y1, 0xC0FFD866);
		}
		long pos = s.livePositionMs(System.currentTimeMillis());
		int px = x0 + (int) Math.round(w * Math.min(1.0, pos / (double) s.end));
		g.fill(x0, y0 + 1, px, y1 - 1, 0xFFFFFFFF);
		g.fill(px - 1, y0 - 2, px + 1, y1 + 2, 0xFFFFFFFF);

		if (hover) {
			long at = timeAt(mouseX, s);
			String label = Component.translatable("hear-no-dub.picker.seek_hint", time(at)).getString();
			int lw = font.width(label);
			int lx = Math.max(2, Math.min(width - lw - 2, mouseX - lw / 2));
			g.fill(lx - 3, y0 - 13, lx + lw + 3, y0 - 2, 0xE0000000);
			g.text(font, label, lx, y0 - 11, 0xFFFFFFFF);
		}
	}

	private boolean overBar(double x, double y) {
		return x >= listLeft && x < listLeft + listWidth && y >= BAR_Y - 3 && y < BAR_Y + BAR_H + 3;
	}

	private long timeAt(double x, MediaBridge.Session s) {
		double f = Math.max(0, Math.min(1, (x - listLeft) / listWidth));
		return Math.round(f * s.end);
	}

	private void drawResults(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<SubtitleManager.Entry> results = manager.results();
		if (results != lastResults) {
			lastResults = results;
			scroll = 0;
		}
		scroll = clamp(scroll, results.size());
		String current = manager.trackKey();
		for (int i = 0; i < visibleRows && scroll + i < results.size(); i++) {
			SubtitleManager.Entry e = results.get(scroll + i);
			int y = listTop + i * ROW_H;
			boolean selected = e.key().equals(current);
			drawRow(g, y, mouseX, mouseY, selected, ellipsize(e.label(), listWidth - 8), ellipsize(e.detail(), listWidth - 8));
		}
		if (results.isEmpty() && !manager.busy()) {
			g.centeredText(font, Component.translatable("hear-no-dub.picker.empty"), width / 2, listTop + 10, 0xFF888888);
		}
		drawScrollBar(g, results.size());
	}

	private void drawLines(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		long now = manager.subtitleTimeMs();
		List<SubtitleTrack.Cue> cues;
		if (searchingLines()) {
			// Search results: scroll freely with the mouse wheel
			cues = filteredCues();
			scroll = clamp(scroll, cues.size());
		} else {
			// Keep the line that's on screen right now in the middle of the list
			cues = manager.cuesAround();
			int activeIdx = -1;
			for (int i = 0; i < cues.size(); i++) {
				if (cues.get(i).startMs() <= now) activeIdx = i;
			}
			scroll = clamp(Math.max(0, activeIdx - visibleRows / 2), cues.size());
		}
		shownCues = cues;
		for (int i = 0; i < visibleRows && scroll + i < cues.size(); i++) {
			SubtitleTrack.Cue c = cues.get(scroll + i);
			int y = listTop + i * ROW_H;
			boolean active = c.startMs() <= now && c.endMs() > now;
			String text = (c.speaker().isBlank() ? "" : c.speaker() + ": ") + c.text().replace('\n', ' ');
			String when = time(Math.max(0, c.startMs() - manager.shiftMs())) + (active ? "   ◀ on screen now" : "");
			drawRow(g, y, mouseX, mouseY, active, ellipsize(text, listWidth - 8), when);
		}
		if (cues.isEmpty() && !searchingLines()) {
			g.centeredText(font, Component.translatable("hear-no-dub.picker.no_lines"), width / 2, listTop + 10, 0xFF888888);
		}
		if (searchingLines()) drawScrollBar(g, cues.size());
	}

	private void drawRow(GuiGraphicsExtractor g, int y, int mouseX, int mouseY, boolean selected, String top, String bottom) {
		boolean hover = mouseX >= listLeft && mouseX < listLeft + listWidth && mouseY >= y && mouseY < y + ROW_H - 2;
		int bg = selected ? 0x6033AA33 : hover ? 0x40FFFFFF : 0x30000000;
		g.fill(listLeft, y, listLeft + listWidth, y + ROW_H - 2, bg);
		if (selected) g.outline(listLeft, y, listWidth, ROW_H - 2, 0xFF55FF55);
		g.text(font, top, listLeft + 4, y + 2, 0xFFFFFFFF);
		g.text(font, bottom, listLeft + 4, y + 11, 0xFF999999);
	}

	private void drawScrollBar(GuiGraphicsExtractor g, int total) {
		if (total <= visibleRows) return;
		int barX = listLeft + listWidth + 3;
		int trackH = visibleRows * ROW_H;
		int thumbH = Math.max(10, trackH * visibleRows / total);
		int thumbY = listTop + (trackH - thumbH) * scroll / Math.max(1, total - visibleRows);
		g.fill(barX, listTop, barX + 3, listTop + trackH, 0x40FFFFFF);
		g.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xFFC0C0C0);
	}

	private static int colorOf(Component c) {
		Integer rgb = c.getStyle().getColor() == null ? null : c.getStyle().getColor().getValue();
		return rgb == null ? 0xFFDDDDDD : 0xFF000000 | rgb;
	}

	private String ellipsize(String s, int maxW) {
		if (s == null) return "";
		if (font.width(s) <= maxW) return s;
		return font.plainSubstrByWidth(s, Math.max(0, maxW - font.width("…"))) + "…";
	}

	private static String time(long ms) {
		long total = Math.max(0, ms) / 1000;
		if (total >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60);
		return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
	}

	private int clamp(int value, int total) {
		return Math.max(0, Math.min(value, Math.max(0, total - visibleRows)));
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			MediaBridge.Session s = manager.session();
			if (s != null && s.end > 0 && overBar(event.x(), event.y())) {
				long at = timeAt(event.x(), s);
				Playback.seekTo(minecraft, at, Component.translatable("hear-no-dub.osd.seek"));
				click();
				return true;
			}
			if (handleListClick(event.x(), event.y())) return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** Returns true if the click hit a row in the list. */
	private boolean handleListClick(double x, double y) {
		if (x < listLeft || x >= listLeft + listWidth || y < listTop) return false;
		int row = (int) ((y - listTop) / ROW_H);
		if (row >= visibleRows) return false;
		int index = scroll + row;

		if (view == View.RESULTS) {
			List<SubtitleManager.Entry> results = manager.results();
			if (index >= results.size()) return false;
			HearNoDubClient.LOGGER.info("Picker: picking result {} ({})", index, results.get(index).label());
			manager.pick(minecraft, results.get(index));
		} else {
			List<SubtitleTrack.Cue> cues = shownCues;
			if (index >= cues.size()) return false;
			SubtitleTrack.Cue cue = cues.get(index);
			if (jumpMode) {
				HearNoDubClient.LOGGER.info("Picker: jumping to line at {} ms", cue.startMs());
				manager.jumpTo(minecraft, cue);
			} else {
				HearNoDubClient.LOGGER.info("Picker: syncing to line at {} ms", cue.startMs());
				manager.syncTo(cue);
			}
		}
		click();
		return true;
	}

	private void click() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.25F));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && view == View.RESULTS) {
			scroll = clamp(scroll - (int) Math.signum(scrollY), manager.results().size());
			return true;
		}
		if (scrollY != 0 && searchingLines()) {
			scroll = clamp(scroll - (int) Math.signum(scrollY) * 2, filteredCues().size());
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
				&& view == View.RESULTS && (queryBox.isFocused() || seasonBox.isFocused() || episodeBox.isFocused())) {
			doSearch();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

package dev.k3ytel.hearnodub.client.subs;

import java.util.ArrayList;
import java.util.List;

/**
 * A short made-up song (written for the mod, not a real one) used by the Lyrics Studio to show
 * every effect: word timing, a shouted line, a held note, a repeating chorus, a duet with
 * background vocals, and an instrumental break. Loops every {@link #LENGTH_MS}.
 */
public final class DemoSong {
	private DemoSong() {}

	public static final long LENGTH_MS = 42_000;
	private static SubtitleTrack track;

	public static SubtitleTrack track() {
		if (track == null) track = build();
		return track;
	}

	private static SubtitleTrack build() {
		List<SubtitleTrack.Cue> cues = new ArrayList<>();
		line(cues, 800, 2600, "", "Lights on the hillside");
		line(cues, 3700, 2700, "", "Words fall down like rain");
		line(cues, 6700, 2300, "", "Sing it LOUDER now!");
		held(cues, 9300, "", "Ooooooh", 2300);
		line(cues, 12300, 2200, "", "We are the echo");
		line(cues, 14700, 2300, "", "Under the open sky");
		line(cues, 17300, 2200, "", "We are the echo");
		line(cues, 19700, 2300, "", "Under the open sky");
		line(cues, 22600, 2400, "Nova", "Can you hear me calling");
		line(cues, 25200, 2600, "Rex", "Every block, every star");
		line(cues, 26000, 1600, SubtitleTrack.Cue.BACKGROUND, "(oh-oh, oh-oh)");
		// an instrumental break from 27.8 s to 33.5 s: the breathing dots show here
		line(cues, 33500, 2200, "", "One more time");
		line(cues, 36000, 2200, "", "We are the echo");
		line(cues, 38500, 2400, "", "Under the open sky");
		return new SubtitleTrack(cues);
	}

	/** A line whose words are spread over its length by word length. */
	private static void line(List<SubtitleTrack.Cue> out, long start, long length, String speaker, String text) {
		String[] words = text.split(" ");
		int chars = 0;
		for (String w : words) chars += w.length() + 1;
		int[] starts = new int[words.length], ends = new int[words.length];
		long sung = (long) (length * 0.85);
		long at = 0;
		for (int i = 0; i < words.length; i++) {
			long d = sung * (words[i].length() + 1) / chars;
			starts[i] = (int) at;
			ends[i] = (int) (at + d);
			at += d;
		}
		boolean italic = SubtitleTrack.Cue.BACKGROUND.equals(speaker);
		out.add(new SubtitleTrack.Cue(start, start + length, text, speaker,
				List.of(new SubtitleTrack.Span(text, italic, false)), starts, ends));
	}

	private static void held(List<SubtitleTrack.Cue> out, long start, String speaker, String text, long hold) {
		out.add(new SubtitleTrack.Cue(start, start + hold + 400, text, speaker,
				List.of(new SubtitleTrack.Span(text, false, false)), new int[] {0}, new int[] {(int) hold}));
	}
}

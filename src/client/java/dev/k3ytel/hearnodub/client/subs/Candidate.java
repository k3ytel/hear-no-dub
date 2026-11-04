package dev.k3ytel.hearnodub.client.subs;

/**
 * One downloadable subtitle found by a source (OpenSubtitles, SubDL, Jimaku).
 *
 * @param source  short source id: "os", "subdl" or "jimaku"
 * @param id      id unique within the source (used for the cache file and saved choices)
 * @param label   release / file name shown in the picker
 * @param title   show title as the source knows it
 * @param season  0 when unknown
 * @param episode 0 when unknown
 * @param downloads popularity, 0 when the source doesn't say
 * @param url     download location (source-specific; may be empty when the id is enough)
 */
public record Candidate(String source, String id, String label, String title, int season, int episode,
		int downloads, boolean hearingImpaired, String url) {

	public String key() {
		return source + ":" + id;
	}

	public String sourceName() {
		return switch (source) {
			case "os" -> "OpenSubtitles";
			case "subdl" -> "SubDL";
			case "jimaku" -> "Jimaku";
			case "lrclib" -> "LRCLIB";
			case "betterlyrics" -> "Better Lyrics";
			case "netease" -> "NetEase";
			case "lyricsovh" -> "lyrics.ovh";
			default -> source;
		};
	}
}

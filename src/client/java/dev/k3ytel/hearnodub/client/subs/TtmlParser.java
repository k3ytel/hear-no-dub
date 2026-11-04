package dev.k3ytel.hearnodub.client.subs;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads TTML lyrics (the Apple Music style used by Better Lyrics): one {@code <p>} per line,
 * timed {@code <span>}s per word or syllable, {@code ttm:agent} for who sings, and
 * {@code ttm:role="x-bg"} spans for background vocals (which become their own cues).
 */
public final class TtmlParser {
	private TtmlParser() {}

	public static boolean looksLikeTtml(String content) {
		String head = content.length() > 4000 ? content.substring(0, 4000) : content;
		String lower = head.toLowerCase(Locale.ROOT);
		return lower.contains("<tt") && content.contains("<p");
	}

	/** One sung piece (word or syllable) while reading a line. */
	private record Piece(String text, long start, long end) {}

	public static List<SubtitleTrack.Cue> parse(String content) {
		List<SubtitleTrack.Cue> cues = new ArrayList<>();
		Document doc;
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			// lyrics come from the internet: no DTDs, no external entities
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setExpandEntityReferences(false);
			f.setNamespaceAware(false);
			DocumentBuilder b = f.newDocumentBuilder();
			doc = b.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return cues;
		}

		// agent id -> name
		Map<String, String> agents = new HashMap<>();
		NodeList agentNodes = doc.getElementsByTagName("ttm:agent");
		for (int i = 0; i < agentNodes.getLength(); i++) {
			Element a = (Element) agentNodes.item(i);
			String id = a.getAttribute("xml:id");
			String name = "";
			NodeList names = a.getElementsByTagName("ttm:name");
			if (names.getLength() > 0) name = names.item(0).getTextContent().strip();
			if (!id.isEmpty()) agents.put(id, name.isEmpty() ? id : name);
		}
		boolean multiSinger = agents.size() > 1;

		NodeList ps = doc.getElementsByTagName("p");
		for (int i = 0; i < ps.getLength(); i++) {
			Element p = (Element) ps.item(i);
			long begin = time(p.getAttribute("begin"));
			long end = time(p.getAttribute("end"));
			String agent = p.getAttribute("ttm:agent");
			String speaker = multiSinger && !agent.isEmpty() ? agents.getOrDefault(agent, agent) : "";

			List<List<Piece>> mainWords = new ArrayList<>(), bgWords = new ArrayList<>();
			StringBuilder untimed = new StringBuilder();
			collect(p, mainWords, bgWords, false, untimed);

			SubtitleTrack.Cue main = toCue(mainWords, begin, end, speaker);
			if (main == null) {
				String text = untimed.toString().strip().replaceAll("\\s+", " ");
				if (!text.isEmpty() && begin >= 0 && end > begin) {
					main = new SubtitleTrack.Cue(begin, end, text, speaker, List.of(new SubtitleTrack.Span(text, false, false)));
				}
			}
			if (main != null) cues.add(main);
			SubtitleTrack.Cue bg = toCue(bgWords, -1, -1, SubtitleTrack.Cue.BACKGROUND);
			if (bg != null) cues.add(bg);
		}
		return cues;
	}

	/**
	 * Walks a line, grouping timed pieces into words: whitespace between pieces starts a new
	 * word, pieces touching each other (syllables) belong to the same word.
	 */
	private static void collect(Node node, List<List<Piece>> main, List<List<Piece>> bg, boolean inBg, StringBuilder untimed) {
		NodeList kids = node.getChildNodes();
		for (int i = 0; i < kids.getLength(); i++) {
			Node n = kids.item(i);
			if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
				String t = n.getTextContent();
				untimed.append(t);
				if (!t.isBlank()) {
					// stray text inside a line: glue it onto the last word
					List<List<Piece>> words = inBg ? bg : main;
					if (!words.isEmpty()) {
						List<Piece> last = words.getLast();
						Piece lp = last.getLast();
						last.set(last.size() - 1, new Piece(lp.text() + t.strip(), lp.start(), lp.end()));
					}
				}
				if (!t.isEmpty() && Character.isWhitespace(t.charAt(t.length() - 1)) || t.isBlank() && !t.isEmpty()) {
					startNewWord(inBg ? bg : main);
				}
				continue;
			}
			if (n.getNodeType() != Node.ELEMENT_NODE) continue;
			Element e = (Element) n;
			if (!e.getTagName().endsWith("span")) {
				collect(e, main, bg, inBg, untimed);
				continue;
			}
			boolean bgSpan = inBg || "x-bg".equals(e.getAttribute("ttm:role"));
			boolean hasChildSpans = e.getElementsByTagName("span").getLength() > 0;
			String b = e.getAttribute("begin");
			if (hasChildSpans || b.isEmpty()) {
				if (bgSpan != inBg) startNewWord(main);
				collect(e, main, bg, bgSpan, untimed);
				if (bgSpan != inBg) startNewWord(bg);
				continue;
			}
			String raw = e.getTextContent();
			untimed.append(raw);
			List<List<Piece>> words = bgSpan ? bg : main;
			if (!raw.isEmpty() && Character.isWhitespace(raw.charAt(0))) startNewWord(words);
			String text = raw.strip();
			if (!text.isEmpty()) {
				if (words.isEmpty() || words.getLast() == null) words.add(new ArrayList<>());
				if (words.getLast() == null) words.set(words.size() - 1, new ArrayList<>());
				words.getLast().add(new Piece(text, time(b), time(e.getAttribute("end"))));
			}
			if (!raw.isEmpty() && Character.isWhitespace(raw.charAt(raw.length() - 1))) startNewWord(words);
		}
	}

	/** Marks that the next piece starts a new word (an empty list = "no word open"). */
	private static void startNewWord(List<List<Piece>> words) {
		if (!words.isEmpty() && words.getLast() != null && !words.getLast().isEmpty()) words.add(new ArrayList<>());
	}

	private static SubtitleTrack.Cue toCue(List<List<Piece>> rawWords, long begin, long end, String speaker) {
		List<List<Piece>> words = new ArrayList<>();
		for (List<Piece> w : rawWords) if (w != null && !w.isEmpty()) words.add(w);
		if (words.isEmpty()) return null;
		long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
		for (List<Piece> w : words) {
			for (Piece p : w) {
				if (p.start() >= 0) first = Math.min(first, p.start());
				if (p.end() >= 0) last = Math.max(last, p.end());
			}
		}
		if (first == Long.MAX_VALUE) return null;
		long start = begin >= 0 ? Math.min(begin, first) : first;
		long stop = Math.max(end >= 0 ? end : last, last);
		if (stop <= start) stop = start + 1000;
		StringBuilder text = new StringBuilder();
		int[] starts = new int[words.size()], ends = new int[words.size()];
		for (int i = 0; i < words.size(); i++) {
			List<Piece> w = words.get(i);
			if (i > 0) text.append(' ');
			long ws = Long.MAX_VALUE, we = Long.MIN_VALUE;
			for (Piece p : w) {
				text.append(p.text());
				if (p.start() >= 0) ws = Math.min(ws, p.start());
				if (p.end() >= 0) we = Math.max(we, p.end());
			}
			if (ws == Long.MAX_VALUE) ws = i > 0 ? start + starts[i - 1] : start;
			if (we == Long.MIN_VALUE) we = ws + 300;
			starts[i] = (int) (ws - start);
			ends[i] = (int) Math.max(starts[i] + 1, we - start);
		}
		String t = text.toString();
		// a word made of several pieces with a space inside ("don't stop") splits differently: drop word timing then
		if (t.split(" ").length != words.size()) {
			return new SubtitleTrack.Cue(start, stop, t, speaker, List.of(new SubtitleTrack.Span(t, speaker.equals(SubtitleTrack.Cue.BACKGROUND), false)));
		}
		boolean italic = speaker.equals(SubtitleTrack.Cue.BACKGROUND);
		return new SubtitleTrack.Cue(start, stop, t, speaker, List.of(new SubtitleTrack.Span(t, italic, false)), starts, ends);
	}

	/** "HH:MM:SS.mmm", "MM:SS.mmm", "SS.mmm", "12.5s" or "1234ms"; -1 when missing. */
	static long time(String t) {
		if (t == null || t.isBlank()) return -1;
		t = t.strip();
		try {
			if (t.endsWith("ms")) return Math.round(Double.parseDouble(t.substring(0, t.length() - 2)));
			if (t.endsWith("s")) return Math.round(Double.parseDouble(t.substring(0, t.length() - 1)) * 1000);
			String[] parts = t.split(":");
			double secs = 0;
			for (String part : parts) secs = secs * 60 + Double.parseDouble(part);
			return Math.round(secs * 1000);
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}

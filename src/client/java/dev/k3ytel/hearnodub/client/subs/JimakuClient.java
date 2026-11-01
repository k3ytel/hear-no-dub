package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Jimaku (jimaku.cc): community anime subtitle archive with a free API key.
 * Mostly Japanese files, so by default only files that look English are offered.
 */
public final class JimakuClient {
	private static final String API = "https://jimaku.cc/api";
	private static final Pattern ENGLISH = Pattern.compile(
			"(^|[^a-z])(en|eng|english)([^a-z]|$)|\\.en\\.", Pattern.CASE_INSENSITIVE);
	private static final Pattern JAPANESE = Pattern.compile(
			"(^|[^a-z])(ja|jp|jpn|japanese)([^a-z]|$)|\\.ja\\.|\\.jp\\.", Pattern.CASE_INSENSITIVE);

	public List<Candidate> search(String query, int episode) throws IOException, InterruptedException {
		JsonArray entries = getArray(API + "/entries/search?anime=true&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
		if (entries.isEmpty()) return List.of();
		JsonObject entry = entries.get(0).getAsJsonObject();
		long id = entry.get("id").getAsLong();
		String title = Http.str(entry, "english_name");
		if (title.isEmpty()) title = Http.str(entry, "name");

		String filesUrl = API + "/entries/" + id + "/files" + (episode > 0 ? "?episode=" + episode : "");
		JsonArray files = getArray(filesUrl);
		boolean includeJapanese = HearNoDubConfig.get().jimakuJapanese;
		List<Candidate> out = new ArrayList<>();
		for (JsonElement el : files) {
			JsonObject f = el.getAsJsonObject();
			String name = Http.str(f, "name");
			String url = Http.str(f, "url");
			if (url.isEmpty()) continue;
			String lower = name.toLowerCase(Locale.ROOT);
			boolean zip = lower.endsWith(".zip");
			if (!zip && !Http.isSubtitleName(name)) continue;
			boolean english = ENGLISH.matcher(name).find() && !JAPANESE.matcher(name).find();
			if (!english && !includeJapanese) continue;
			int ep = episode > 0 && (zip || Http.episodeScore(name, episode) >= 8) ? episode : 0;
			out.add(new Candidate("jimaku", id + "_" + Math.abs(url.hashCode()), name, title, 0, ep, 0, false, url));
		}
		return out;
	}

	public String download(Candidate c, int episode) throws IOException, InterruptedException {
		byte[] bytes = Http.getBytes(Http.request(c.url())
				.header("Authorization", HearNoDubConfig.get().jimakuKey.trim())
				.timeout(Duration.ofSeconds(30)).GET().build());
		if (Http.isZip(bytes)) return Http.subtitleFromZip(bytes, episode > 0 ? episode : c.episode());
		return Http.decode(bytes);
	}

	private static JsonArray getArray(String url) throws IOException, InterruptedException {
		HttpResponse<String> resp = Http.sendString(Http.request(url)
				.header("Authorization", HearNoDubConfig.get().jimakuKey.trim())
				.header("Accept", "application/json")
				.GET().build());
		if (resp.statusCode() == 404) return new JsonArray();
		if (resp.statusCode() / 100 != 2) throw new IOException("Jimaku: HTTP " + resp.statusCode());
		JsonElement el = JsonParser.parseString(resp.body());
		return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
	}
}

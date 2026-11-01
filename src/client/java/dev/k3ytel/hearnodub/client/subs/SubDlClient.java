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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SubDL (api.subdl.com): free API key, about 2000 searches a day and a few hundred downloads.
 * Search by IMDb ID or name; downloads are zips (or single files when unpacked).
 */
public final class SubDlClient {
	private static final String API = "https://api.subdl.com/api/v1/subtitles";
	private static final String DL = "https://dl.subdl.com";

	/**
	 * @param imdbId show IMDb ID like "tt2560140", or null to search by name
	 */
	public List<Candidate> search(String query, String imdbId, int season, int episode, boolean movie) throws IOException, InterruptedException {
		Map<String, String> p = new LinkedHashMap<>();
		p.put("api_key", HearNoDubConfig.get().subdlKey.trim());
		if (imdbId != null && !imdbId.isBlank()) p.put("imdb_id", imdbId);
		else p.put("film_name", query);
		p.put("type", movie ? "movie" : "tv");
		if (season > 0) p.put("season_number", String.valueOf(season));
		if (episode > 0) p.put("episode_number", String.valueOf(episode));
		p.put("languages", "EN");
		p.put("subs_per_page", "30");
		p.put("unpack", "1");
		p.put("hi", "1");

		StringBuilder url = new StringBuilder(API).append('?');
		boolean first = true;
		for (Map.Entry<String, String> e : p.entrySet()) {
			if (!first) url.append('&');
			first = false;
			url.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}

		HttpResponse<String> resp = Http.sendString(Http.request(url.toString()).header("Accept", "application/json").GET().build());
		JsonObject json;
		try {
			json = JsonParser.parseString(resp.body()).getAsJsonObject();
		} catch (Exception e) {
			throw new IOException("SubDL: bad response (HTTP " + resp.statusCode() + ")");
		}
		if (!Http.bool(json, "status")) {
			String err = Http.str(json, "error");
			// "no subtitles found" style answers are not errors for us
			String low = err.toLowerCase();
			if (low.contains("not found") || low.contains("no subtitle") || low.contains("can't find") || low.contains("cannot find")) {
				return List.of();
			}
			throw new IOException("SubDL: " + (err.isEmpty() ? "HTTP " + resp.statusCode() : err));
		}

		String title = "";
		if (json.has("results") && json.get("results").isJsonArray() && !json.getAsJsonArray("results").isEmpty()) {
			title = Http.str(json.getAsJsonArray("results").get(0).getAsJsonObject(), "name");
		}

		List<Candidate> out = new ArrayList<>();
		JsonArray subs = json.has("subtitles") && json.get("subtitles").isJsonArray() ? json.getAsJsonArray("subtitles") : new JsonArray();
		for (JsonElement el : subs) {
			JsonObject s = el.getAsJsonObject();
			String release = Http.str(s, "release_name");
			if (release.isEmpty()) release = Http.str(s, "name");
			int sSeason = Http.num(s, "season");
			int sEpisode = Http.num(s, "episode");
			boolean fullSeason = Http.bool(s, "full_season");
			boolean hi = Http.bool(s, "hi");

			// Season packs list their episodes as separate files: offer just the right one
			JsonArray files = s.has("unpack_files") && s.get("unpack_files").isJsonArray() ? s.getAsJsonArray("unpack_files") : null;
			if (files != null && !files.isEmpty() && (fullSeason || files.size() > 1)) {
				for (JsonElement fe : files) {
					JsonObject f = fe.getAsJsonObject();
					String name = Http.str(f, "name");
					if (!name.isEmpty() && !Http.isSubtitleName(name)) continue;
					int fEp = Http.num(f, "episode");
					if (fEp == 0) fEp = guessEpisode(name, episode);
					if (episode > 0 && fEp != episode) continue;
					String lang = Http.str(f, "language");
					if (!lang.isEmpty() && !lang.equalsIgnoreCase("en") && !lang.toLowerCase().startsWith("english")) continue;
					String fUrl = Http.str(f, "url");
					String id = !Http.str(f, "file_n_id").isEmpty() ? Http.str(f, "file_n_id") : fUrl;
					String label = Http.str(f, "release_name");
					if (label.isEmpty()) label = name.isEmpty() ? release : name;
					int fSeason = Http.num(f, "season");
					out.add(new Candidate("subdl", sanitize(id), label, title, fSeason > 0 ? fSeason : sSeason, fEp,
							0, hi || Http.bool(f, "hi"), fUrl));
				}
				continue;
			}
			String sUrl = Http.str(s, "url");
			if (sUrl.isEmpty()) continue;
			out.add(new Candidate("subdl", sanitize(sUrl), release, title, sSeason,
					sEpisode > 0 ? sEpisode : guessEpisode(release, episode), 0, hi, sUrl));
		}
		return out;
	}

	public String download(Candidate c, int episode) throws IOException, InterruptedException {
		String url = c.url();
		if (url == null || url.isEmpty()) throw new IOException("SubDL: no download link");
		if (url.startsWith("/")) url = DL + url;
		else if (!url.startsWith("http")) url = DL + "/" + url;
		byte[] bytes = Http.getBytes(Http.request(url).timeout(Duration.ofSeconds(30)).GET().build());
		if (Http.isZip(bytes)) return Http.subtitleFromZip(bytes, episode > 0 ? episode : c.episode());
		return Http.decode(bytes);
	}

	/** If the name clearly carries the wanted episode number, return it; otherwise 0. */
	private static int guessEpisode(String name, int wanted) {
		if (wanted <= 0 || name == null) return 0;
		return Http.episodeScore(name, wanted) >= 8 ? wanted : 0;
	}

	/** Ids end up in file names; keep them safe. */
	private static String sanitize(String id) {
		return id.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}

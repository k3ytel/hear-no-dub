package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal OpenSubtitles REST client (api.opensubtitles.com/api/v1): search, login, download.
 * Blocking calls; run them off the render thread.
 */
public final class OpenSubtitlesClient {
	private static final String DEFAULT_BASE = "https://api.opensubtitles.com/api/v1";

	private String base = DEFAULT_BASE;
	private String token;
	private String tokenUser;

	/** Downloads left today as reported by the last download, or -1 if unknown. */
	private volatile int remaining = -1;

	public int remaining() {
		return remaining;
	}

	/**
	 * Searches English subtitles. With a show IMDb number (parentImdb > 0) the show is matched
	 * exactly and the query text is ignored.
	 */
	public List<Candidate> search(String query, int season, int episode, long parentImdb) throws IOException, InterruptedException {
		// Parameter names and values lowercase and sorted, as the API asks (avoids redirects)
		Map<String, String> params = new TreeMap<>();
		if (episode > 0) params.put("episode_number", String.valueOf(episode));
		params.put("languages", "en");
		if (parentImdb > 0) params.put("parent_imdb_id", String.valueOf(parentImdb));
		else params.put("query", query.toLowerCase(Locale.ROOT).trim());
		if (season > 0) params.put("season_number", String.valueOf(season));

		StringBuilder url = new StringBuilder(base).append("/subtitles?");
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) url.append('&');
			first = false;
			url.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}

		JsonObject json = sendJson(request(url.toString()).GET().build());
		List<Candidate> out = new ArrayList<>();
		JsonArray data = json.has("data") ? json.getAsJsonArray("data") : new JsonArray();
		for (JsonElement el : data) {
			JsonObject attr = el.getAsJsonObject().getAsJsonObject("attributes");
			if (attr == null) continue;
			JsonObject feature = attr.has("feature_details") && attr.get("feature_details").isJsonObject()
					? attr.getAsJsonObject("feature_details") : new JsonObject();
			String title = Http.str(feature, "parent_title");
			if (title.isEmpty()) title = Http.str(feature, "title");
			if (title.isEmpty()) title = Http.str(feature, "movie_name");
			int s = Http.num(feature, "season_number");
			int ep = Http.num(feature, "episode_number");
			String release = Http.str(attr, "release");
			int downloads = Http.num(attr, "download_count");
			boolean hi = Http.bool(attr, "hearing_impaired");
			JsonArray files = attr.has("files") ? attr.getAsJsonArray("files") : new JsonArray();
			for (JsonElement f : files) {
				JsonObject file = f.getAsJsonObject();
				if (!file.has("file_id")) continue;
				String fileName = Http.str(file, "file_name");
				String label = !release.isBlank() ? release : (!fileName.isBlank() ? fileName : title);
				out.add(new Candidate("os", String.valueOf(file.get("file_id").getAsLong()), label, title,
						s, ep, downloads, hi, ""));
			}
		}
		return out;
	}

	/** Downloads one file as .srt text. Uses one of the account's daily downloads. */
	public String download(Candidate c) throws IOException, InterruptedException {
		ensureLogin();
		JsonObject body = new JsonObject();
		body.addProperty("file_id", Long.parseLong(c.id()));
		body.addProperty("sub_format", "srt");
		HttpRequest.Builder req = request(base + "/download")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (token != null) req.header("Authorization", "Bearer " + token);

		JsonObject json;
		try {
			json = sendJson(req.build());
		} catch (IOException e) {
			// Token may have expired: log in again once and retry
			if (token != null && e.getMessage() != null && e.getMessage().startsWith("401")) {
				token = null;
				ensureLogin();
				req.setHeader("Authorization", "Bearer " + token);
				json = sendJson(req.build());
			} else {
				throw e;
			}
		}
		if (json.has("remaining")) remaining = Http.num(json, "remaining");
		String link = Http.str(json, "link");
		if (link.isEmpty()) {
			String msg = Http.str(json, "message");
			throw new IOException(msg.isEmpty() ? "No download link" : msg);
		}
		return Http.decode(Http.getBytes(Http.request(link).timeout(Duration.ofSeconds(30)).GET().build()));
	}

	private void ensureLogin() throws IOException, InterruptedException {
		HearNoDubConfig cfg = HearNoDubConfig.get();
		if (cfg.username.isBlank() || cfg.password.isBlank()) {
			token = null;
			return; // anonymous download (lower daily limit)
		}
		if (token != null && cfg.username.equals(tokenUser)) return;

		JsonObject body = new JsonObject();
		body.addProperty("username", cfg.username);
		body.addProperty("password", cfg.password);
		JsonObject json = sendJson(request(DEFAULT_BASE + "/login")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build());
		token = Http.str(json, "token");
		tokenUser = cfg.username;
		String host = Http.str(json, "base_url");
		base = host.isEmpty() ? DEFAULT_BASE : (host.startsWith("http") ? host : "https://" + host) + "/api/v1";
		if (json.has("user") && json.get("user").isJsonObject()) {
			JsonObject user = json.getAsJsonObject("user");
			if (user.has("remaining_downloads")) remaining = Http.num(user, "remaining_downloads");
		}
		if (token.isEmpty()) {
			token = null;
			throw new IOException("Login failed");
		}
	}

	private HttpRequest.Builder request(String url) {
		return Http.request(url)
				.header("Api-Key", HearNoDubConfig.get().apiKey.trim())
				.header("Accept", "application/json");
	}

	private JsonObject sendJson(HttpRequest req) throws IOException, InterruptedException {
		HttpResponse<String> resp = Http.sendString(req);
		JsonObject json;
		try {
			JsonElement el = JsonParser.parseString(resp.body());
			json = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			json = new JsonObject();
		}
		if (resp.statusCode() / 100 != 2) {
			if (json.has("remaining")) remaining = Http.num(json, "remaining");
			if (resp.statusCode() == 406) remaining = 0; // daily quota used up
			String msg = Http.str(json, "message");
			if (msg.isEmpty()) msg = Http.str(json, "errors");
			throw new IOException(resp.statusCode() + (msg.isEmpty() ? "" : ": " + msg));
		}
		return json;
	}
}

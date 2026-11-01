package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.k3ytel.hearnodub.client.config.HearNoDubConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
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
	private static final String USER_AGENT = "HearNoDub v0.1.0";

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private String base = DEFAULT_BASE;
	private String token;
	private String tokenUser;

	/** One downloadable subtitle file from a search. */
	public record Result(long fileId, String fileName, String release, int downloads,
			String title, int season, int episode, boolean hearingImpaired) {
		public String label() {
			String r = release != null && !release.isBlank() ? release : fileName;
			return r == null || r.isBlank() ? title : r;
		}
	}

	/**
	 * Searches English subtitles. With a show IMDb number (parentImdb > 0) the show is matched
	 * exactly and the query text is ignored.
	 */
	public List<Result> search(String query, int season, int episode, long parentImdb) throws IOException, InterruptedException {
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
		List<Result> out = new ArrayList<>();
		JsonArray data = json.has("data") ? json.getAsJsonArray("data") : new JsonArray();
		for (JsonElement el : data) {
			JsonObject attr = el.getAsJsonObject().getAsJsonObject("attributes");
			if (attr == null) continue;
			JsonObject feature = attr.has("feature_details") && attr.get("feature_details").isJsonObject()
					? attr.getAsJsonObject("feature_details") : new JsonObject();
			String title = str(feature, "parent_title");
			if (title.isEmpty()) title = str(feature, "title");
			if (title.isEmpty()) title = str(feature, "movie_name");
			int s = num(feature, "season_number");
			int ep = num(feature, "episode_number");
			String release = str(attr, "release");
			int downloads = num(attr, "download_count");
			boolean hi = attr.has("hearing_impaired") && attr.get("hearing_impaired").isJsonPrimitive()
					&& attr.get("hearing_impaired").getAsBoolean();
			JsonArray files = attr.has("files") ? attr.getAsJsonArray("files") : new JsonArray();
			for (JsonElement f : files) {
				JsonObject file = f.getAsJsonObject();
				if (!file.has("file_id")) continue;
				out.add(new Result(file.get("file_id").getAsLong(), str(file, "file_name"),
						release, downloads, title, s, ep, hi));
			}
		}
		return out;
	}

	/** Downloads one file as .srt text. Uses one of the account's daily downloads. */
	public String download(long fileId) throws IOException, InterruptedException {
		ensureLogin();
		JsonObject body = new JsonObject();
		body.addProperty("file_id", fileId);
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
		String link = str(json, "link");
		if (link.isEmpty()) throw new IOException(str(json, "message").isEmpty() ? "No download link" : str(json, "message"));

		HttpResponse<byte[]> file = http.send(HttpRequest.newBuilder(URI.create(link))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofSeconds(30))
				.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		if (file.statusCode() != 200) throw new IOException("Download failed (HTTP " + file.statusCode() + ")");
		return decode(file.body());
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
		token = str(json, "token");
		tokenUser = cfg.username;
		String host = str(json, "base_url");
		base = host.isEmpty() ? DEFAULT_BASE : (host.startsWith("http") ? host : "https://" + host) + "/api/v1";
		if (token.isEmpty()) {
			token = null;
			throw new IOException("Login failed");
		}
	}

	private HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("Api-Key", HearNoDubConfig.get().apiKey.trim())
				.header("User-Agent", USER_AGENT)
				.header("Accept", "application/json");
	}

	private JsonObject sendJson(HttpRequest req) throws IOException, InterruptedException {
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonObject json;
		try {
			JsonElement el = JsonParser.parseString(resp.body());
			json = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			json = new JsonObject();
		}
		if (resp.statusCode() / 100 != 2) {
			String msg = str(json, "message");
			if (msg.isEmpty()) msg = str(json, "errors");
			throw new IOException(resp.statusCode() + (msg.isEmpty() ? "" : ": " + msg));
		}
		return json;
	}

	/** UTF-8 if valid, else Windows-1252 (common for old .srt files). */
	private static String decode(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return new String(bytes, Charset.forName("windows-1252"));
		}
	}

	private static String str(JsonObject o, String key) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
		JsonElement e = o.get(key);
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	private static int num(JsonObject o, String key) {
		try {
			return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
		} catch (Exception e) {
			return 0;
		}
	}
}

package dev.k3ytel.hearnodub.client.subs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Small shared HTTP + text helpers for the subtitle sources. */
public final class Http {
	private Http() {}

	public static final String USER_AGENT = "HearNoDub v0.5.0";

	public static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", USER_AGENT);
	}

	public static HttpResponse<String> sendString(HttpRequest req) throws IOException, InterruptedException {
		return CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	public static byte[] getBytes(HttpRequest req) throws IOException, InterruptedException {
		HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (resp.statusCode() / 100 != 2) throw new IOException("Download failed (HTTP " + resp.statusCode() + ")");
		return resp.body();
	}

	/** UTF-8 (or UTF-16 with BOM) if valid, else Windows-1252 (common for old .srt files). */
	public static String decode(byte[] bytes) {
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
			return new String(bytes, StandardCharsets.UTF_16LE).substring(1);
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
			return new String(bytes, StandardCharsets.UTF_16BE).substring(1);
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return new String(bytes, Charset.forName("windows-1252"));
		}
	}

	public static boolean isZip(byte[] bytes) {
		return bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
	}

	public static boolean isSubtitleName(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return n.endsWith(".srt") || n.endsWith(".ass") || n.endsWith(".ssa") || n.endsWith(".vtt");
	}

	/**
	 * Picks the subtitle file inside a zip: the only one, or the one whose name matches the
	 * episode number best. Returns its text.
	 */
	public static String subtitleFromZip(byte[] zip, int episode) throws IOException {
		String bestText = null;
		int bestScore = Integer.MIN_VALUE;
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry e;
			while ((e = in.getNextEntry()) != null) {
				if (e.isDirectory() || !isSubtitleName(e.getName())) continue;
				byte[] data = in.readAllBytes();
				int score = episodeScore(e.getName(), episode);
				String lower = e.getName().toLowerCase(Locale.ROOT);
				if (lower.endsWith(".srt")) score += 1; // simplest format wins ties
				if (score > bestScore) {
					bestScore = score;
					bestText = decode(data);
				}
			}
		}
		if (bestText == null) throw new IOException("No subtitle file inside the zip");
		return bestText;
	}

	/** How well a file name matches an episode number (higher is better). */
	public static int episodeScore(String fileName, int episode) {
		if (episode <= 0) return 0;
		String n = fileName.toLowerCase(Locale.ROOT);
		String two = String.format(Locale.ROOT, "%02d", episode);
		if (n.matches(".*(e|ep|episode|x)\\s*0*" + episode + "(\\D.*|$)")) return 10;
		if (n.matches(".*[\\s_\\-\\[(]0*" + episode + "[\\s_\\-\\])v.].*")) return 8;
		if (n.contains(two)) return 2;
		return -5;
	}

	public static String str(JsonObject o, String key) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
		JsonElement e = o.get(key);
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	public static int num(JsonObject o, String key) {
		try {
			return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	public static boolean bool(JsonObject o, String key) {
		try {
			return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}
}

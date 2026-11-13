package com.sunplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a skin from the premium (Mojang) account that owns the requested name.
 *
 * <p>Runs off-thread and completes with {@code null} on any failure — unknown account, no network,
 * rate limit, blocked API. {@code null} means "no textures property", which makes the client fall
 * back to the default Steve model, which is the agreed behaviour.
 */
public final class SkinResolver {

    public record Skin(String value, String signature) {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SkinResolver() {
    }

    /** Looks the skin up asynchronously; never completes exceptionally. */
    public static CompletableFuture<Skin> lookup(SunPlayerPlugin plugin, String name) {
        CompletableFuture<Skin> future = new CompletableFuture<>();
        ServerCompat.runAsync(plugin, () -> {
            Skin skin = null;
            try {
                skin = fetch(name);
            } catch (Throwable t) {
                plugin.getLogger().fine("皮肤查询失败（将使用 Steve）: " + name + " - " + t);
            }
            future.complete(skin);
        });
        return future;
    }

    private static Skin fetch(String name) throws Exception {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String idJson = get("https://api.mojang.com/users/profiles/minecraft/" + encoded);
        if (idJson == null || idJson.isBlank()) {
            return null;
        }
        JsonObject idObject = JsonParser.parseString(idJson).getAsJsonObject();
        JsonElement idElement = idObject.get("id");
        if (idElement == null) {
            return null;
        }
        String uuid = idElement.getAsString();

        String profileJson = get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
        if (profileJson == null || profileJson.isBlank()) {
            return null;
        }
        JsonObject profile = JsonParser.parseString(profileJson).getAsJsonObject();
        JsonArray properties = profile.getAsJsonArray("properties");
        if (properties == null) {
            return null;
        }
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(property.get("name").getAsString())) {
                continue;
            }
            String value = property.get("value").getAsString();
            String signature = property.has("signature") ? property.get("signature").getAsString() : null;
            return new Skin(value, signature);
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "SunPlayer/0.1 (Folia fake player plugin)")
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return response.body();
    }
}

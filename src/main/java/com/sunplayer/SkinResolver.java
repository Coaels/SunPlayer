package com.sunplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Resolves a skin from the premium (Mojang) account that owns the requested name.
 *
 * <p>Runs off-thread and completes with {@code null} on any failure — unknown account, no network,
 * rate limit, blocked API. {@code null} means "no textures property", which makes the client fall
 * back to the default Steve model, which is the agreed behaviour.
 *
 * <p>Since 0.13.2 the resolver distinguishes "this account does not exist" from "Mojang could not
 * answer": {@code api.mojang.com} answers {@code 429} (and occasionally times out) for a large share
 * of requests, and reporting that as "no such player" is simply wrong. Rate limits and server errors
 * are retried with backoff, and the two outcomes reach the caller as different values so the command
 * can say the right thing. See {@link #lookupDetailed}.
 */
public final class SkinResolver {

    public record Skin(String value, String signature) {
    }

    /** Why a lookup did not produce a skin. */
    public enum Status {
        /** A textures property was found; {@link Lookup#skin()} is non-null. */
        FOUND,
        /** Mojang says no such account (HTTP 204/404, or a profile without textures). */
        NOT_FOUND,
        /** Mojang never gave a usable answer — rate limit, timeout, 5xx, broken JSON. */
        ERROR
    }

    /** Result of {@link #lookupDetailed}: a skin, or the reason there is none. */
    public record Lookup(Skin skin, Status status, String detail) {
        static Lookup found(Skin skin) {
            return new Lookup(skin, Status.FOUND, null);
        }

        static Lookup notFound() {
            return new Lookup(null, Status.NOT_FOUND, null);
        }

        static Lookup error(String detail) {
            return new Lookup(null, Status.ERROR, detail);
        }
    }

    /** Single HTTP answer; {@code status == 0} means "no answer at all" (I/O error, timeout). */
    private record Response(int status, String body, String retryAfter, String error) {
        static Response io(String error) {
            return new Response(0, null, null, error);
        }

        boolean retryable() {
            // 429 = rate limit; 403 shows up from Mojang's front end during the same throttling
            // windows; 0 = no answer at all; 5xx = their side having a bad day.
            return this.status == 0 || this.status == 403 || this.status == 429
                    || (this.status >= 500 && this.status <= 599);
        }
    }

    /** How many times one URL is tried before the visitor is told to come back later. */
    private static final int ATTEMPTS = 4;

    /**
     * Attempts used when a human is waiting for the answer ({@code /sunplayer <name> skin}).
     *
     * <p>Mojang's front end answers {@code 429} to roughly half of the requests from one address for
     * stretches of several seconds, so a command that is allowed to wait ~14 s almost always gets
     * through. Spawning uses plain {@link #ATTEMPTS} instead: a fake should appear quickly even if
     * that means it wears Steve for a while.
     */
    public static final int ATTEMPTS_INTERACTIVE = 6;

    /** Longest backoff between two attempts, in milliseconds. */
    private static final long MAX_BACKOFF_MS = 4_000L;

    /** How long a successful skin is reused before asking Mojang again. */
    private static final long FOUND_TTL_MS = 30L * 60L * 1000L;

    /** How long "no such account" is reused; short, so a renamed player is picked up quickly. */
    private static final long NOT_FOUND_TTL_MS = 2L * 60L * 1000L;

    private record Cached(Lookup lookup, long expiresAt) {
        boolean alive() {
            return System.currentTimeMillis() < this.expiresAt;
        }
    }

    /**
     * Recent answers, keyed by lower-cased name. Failures are <em>not</em> cached: the whole point of
     * the retry work is that a 429 today says nothing about the same name a second from now.
     *
     * <p>Capped so that spamming {@code /sunplayer x skin <random name>} cannot grow it without bound;
     * clearing wholesale is fine because every entry is a cache hit at best.
     */
    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();

    private static final int CACHE_LIMIT = 512;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SkinResolver() {
    }

    /** Looks the skin up asynchronously; never completes exceptionally. */
    public static CompletableFuture<Skin> lookup(SunPlayerPlugin plugin, String name) {
        return lookupDetailed(plugin, name, ATTEMPTS).thenApply(lookup -> lookup.skin());
    }

    /**
     * Looks the skin up asynchronously and says why it failed; never completes exceptionally.
     *
     * <p>Use this where the answer is shown to a human — {@code /sunplayer <name> skin} — so a rate
     * limit is not reported as "no such player". Spawn paths can keep using {@link #lookup}, where
     * every failure simply means "wear Steve".
     *
     * <p>{@code attempts} lets a command wait longer than a spawn: a player who asked for a skin can
     * be told "hold on" for ten seconds, a spawn should not be.
     */
    public static CompletableFuture<Lookup> lookupDetailed(SunPlayerPlugin plugin, String name, int attempts) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.alive()) {
            return CompletableFuture.completedFuture(cached.lookup());
        }
        CompletableFuture<Lookup> future = new CompletableFuture<>();
        ServerCompat.runAsync(plugin, () -> {
            Lookup lookup;
            try {
                lookup = fetch(name, Math.max(1, attempts));
            } catch (Throwable t) {
                lookup = Lookup.error(t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            }
            if (lookup.status() == Status.FOUND) {
                remember(key, lookup, FOUND_TTL_MS);
            } else if (lookup.status() == Status.NOT_FOUND) {
                remember(key, lookup, NOT_FOUND_TTL_MS);
            } else {
                CACHE.remove(key);
                plugin.getLogger().info("查询 " + name + " 的皮肤失败（本次使用 Steve）: " + lookup.detail());
            }
            future.complete(lookup);
        });
        return future;
    }

    /** Stores an answer, dropping the whole cache when it grows past {@link #CACHE_LIMIT}. */
    private static void remember(String key, Lookup lookup, long ttlMillis) {
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, new Cached(lookup, System.currentTimeMillis() + ttlMillis));
    }

    private static Lookup fetch(String name, int attempts) throws Exception {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);

        Response idResponse = get("https://api.mojang.com/users/profiles/minecraft/" + encoded, attempts);
        if (idResponse.status() != 200) {
            return failure(idResponse);
        }
        if (idResponse.body() == null || idResponse.body().isBlank()) {
            // A 200 with an empty body is Mojang's "no such name" as well.
            return Lookup.notFound();
        }
        String uuid;
        try {
            JsonObject idObject = JsonParser.parseString(idResponse.body()).getAsJsonObject();
            JsonElement idElement = idObject.get("id");
            if (idElement == null) {
                return Lookup.notFound();
            }
            uuid = idElement.getAsString();
        } catch (RuntimeException brokenJson) {
            return Lookup.error("接口返回了无法解析的内容（" + brokenJson.getClass().getSimpleName() + "）");
        }

        Response profileResponse = get("https://sessionserver.mojang.com/session/minecraft/profile/"
                + uuid + "?unsigned=false", attempts);
        if (profileResponse.status() != 200) {
            return failure(profileResponse);
        }
        if (profileResponse.body() == null || profileResponse.body().isBlank()) {
            return Lookup.notFound();
        }
        try {
            JsonObject profile = JsonParser.parseString(profileResponse.body()).getAsJsonObject();
            JsonArray properties = profile.getAsJsonArray("properties");
            if (properties == null) {
                return Lookup.notFound();
            }
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(property.get("name").getAsString())) {
                    continue;
                }
                String value = property.get("value").getAsString();
                String signature = property.has("signature") ? property.get("signature").getAsString() : null;
                return Lookup.found(new Skin(value, signature));
            }
            // The account exists but carries no skin: same outcome as "unknown name" for our purpose.
            return Lookup.notFound();
        } catch (RuntimeException brokenJson) {
            return Lookup.error("皮肤数据无法解析（" + brokenJson.getClass().getSimpleName() + "）");
        }
    }

    /** Turns a non-200 answer into either "no such account" or "could not ask". */
    private static Lookup failure(Response response) {
        if (response.status() == 204 || response.status() == 404) {
            return Lookup.notFound();
        }
        if (response.status() == 0) {
            return Lookup.error("连不上 Mojang（" + response.error() + "）");
        }
        return Lookup.error("Mojang 返回 HTTP " + response.status());
    }

    /**
     * GETs a URL, retrying the answers that mean "ask again later" rather than "no".
     *
     * <p>{@code api.mojang.com} rate-limits aggressively per IP; a single 429 used to surface as
     * "找不到正版玩家". Retries honour {@code Retry-After}, otherwise back off 800/1600/3200 ms.
     */
    private static Response get(String url, int attempts) {
        Response last = Response.io("未尝试");
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                sleep(backoffMillis(last, attempt));
            }
            last = getOnce(url);
            if (!last.retryable()) {
                return last;
            }
        }
        return last;
    }

    private static Response getOnce(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "SunPlayer/0.13 (Folia fake player plugin)")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            return new Response(response.statusCode(), response.body(), retryAfter, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Response.io(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private static long backoffMillis(Response response, int attempt) {
        String retryAfter = response.retryAfter();
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0) {
                    return Math.min(seconds * 1000L, MAX_BACKOFF_MS);
                }
            } catch (NumberFormatException ignored) {
                // Retry-After may also be an HTTP date; falling back to exponential is fine.
            }
        }
        // Mojang's rate-limit window is seconds long, so 400 ms was not enough to ride one out;
        // 800/1600/3200 keeps the whole lookup under ~6.5 s while still recovering from a blip.
        long base = Math.min(800L << (attempt - 1), MAX_BACKOFF_MS);
        // Jitter keeps several fakes spawning together from retrying in lockstep.
        return base + ThreadLocalRandom.current().nextLong(0, base / 2 + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.linjian.tongpin.lyrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LyricsRepository {
    private static final String BASE = "https://lrclib.net/api";
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern COVER_COLON = Pattern.compile("(?i)(?:cover|翻唱|翻自|原唱|原曲)\\s*[:：]\\s*([^（(\\[【/|]+)");
    private static final Pattern COVER_BRACKET = Pattern.compile("(?i)[（(\\[【][^）)\\]】]*(?:cover|翻唱|翻自|原唱|原曲|live|现场|伴奏|纯音乐)[^）)\\]】]*[）)\\]】]");
    private static final Pattern COVER_SUFFIX = Pattern.compile("(?i)\\s*[-—–_/|]*\\s*(?:cover|翻唱|翻自|原唱|原曲|live|现场版|伴奏版)(?:\\s*[:：].*)?$");
    private static final int MAX_CACHE_SIZE = 32;
    private static final long NEGATIVE_CACHE_MS = 30_000L;
    private static final long SEARCH_BUDGET_MS = 5_200L;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ExecutorService requests = Executors.newFixedThreadPool(5);
    private final Object lock = new Object();
    private final AtomicLong generation = new AtomicLong(0L);
    private final Map<String, CachedLyrics> cache = new LinkedHashMap<String, CachedLyrics>(40, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedLyrics> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private String activeKey = "";
    private String loadingKey = "";
    private List<Line> lines = Collections.emptyList();
    private String status = "";
    private boolean synced;
    private Future<?> activeTask;

    public void ensure(
            String key,
            String title,
            String artist,
            String album,
            long durationMs,
            Runnable onChanged
    ) {
        if (key == null || key.isEmpty() || title == null || title.trim().isEmpty()) return;

        final long token;
        synchronized (lock) {
            activeKey = key;
            CachedLyrics cached = cache.get(key);
            if (cached != null && (cached.synced || System.currentTimeMillis() - cached.loadedAt < NEGATIVE_CACHE_MS)) {
                lines = cached.lines;
                status = cached.status;
                synced = cached.synced;
                loadingKey = "";
                return;
            }
            if (key.equals(loadingKey)) return;

            if (activeTask != null) activeTask.cancel(true);
            token = generation.incrementAndGet();
            loadingKey = key;
            lines = Collections.emptyList();
            synced = false;
            status = "歌词加载中…";
        }
        if (onChanged != null) onChanged.run();

        activeTask = workers.submit(() -> {
            LoadResult result;
            try {
                result = loadLyrics(title, artist, album, durationMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                result = new LoadResult(Collections.emptyList(), "歌词服务暂不可用", false);
            }

            synchronized (lock) {
                if (token != generation.get() || !key.equals(activeKey)) return;
                CachedLyrics value = new CachedLyrics(result.lines, result.status, result.synced, System.currentTimeMillis());
                cache.put(key, value);
                loadingKey = "";
                lines = value.lines;
                status = value.status;
                synced = value.synced;
            }
            if (onChanged != null) onChanged.run();
        });
    }

    public void invalidate(String key) {
        synchronized (lock) {
            if (key == null || key.isEmpty()) return;
            cache.remove(key);
            if (key.equals(activeKey)) {
                generation.incrementAndGet();
                if (activeTask != null) activeTask.cancel(true);
                loadingKey = "";
                lines = Collections.emptyList();
                status = "准备重新匹配歌词";
                synced = false;
            }
        }
    }

    public Snapshot at(String key, long positionMs) {
        synchronized (lock) {
            if (key == null || !key.equals(activeKey)) return new Snapshot("", "", "", false);
            if (lines.isEmpty()) return new Snapshot("", "", status, synced);

            int current = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).timeMs <= positionMs + 150L) current = i;
                else break;
            }
            String lyric = current >= 0 ? lines.get(current).text : "";
            String next = current + 1 < lines.size() ? lines.get(current + 1).text : "";
            return new Snapshot(lyric, next, "LRCLIB", true);
        }
    }

    public void shutdown() {
        generation.incrementAndGet();
        if (activeTask != null) activeTask.cancel(true);
        workers.shutdownNow();
        requests.shutdownNow();
    }

    private LoadResult loadLyrics(String rawTitle, String rawArtist, String album, long durationMs) throws Exception {
        TrackQuery query = cleanQuery(rawTitle, rawArtist);
        List<Callable<List<JSONObject>>> tasks = buildTasks(query, rawTitle, rawArtist, album, durationMs);
        CompletionService<List<JSONObject>> completion = new ExecutorCompletionService<>(requests);
        List<Future<List<JSONObject>>> futures = new ArrayList<>();
        for (Callable<List<JSONObject>> task : tasks) futures.add(completion.submit(task));

        List<JSONObject> records = new ArrayList<>();
        int completed = 0;
        int successfulRequests = 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEARCH_BUDGET_MS);
        try {
            while (completed < tasks.size()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                Future<List<JSONObject>> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) break;
                completed += 1;
                try {
                    List<JSONObject> values = future.get();
                    successfulRequests += 1;
                    if (values != null) records.addAll(values);
                } catch (Throwable ignored) {
                }
                JSONObject bestSoFar = chooseBest(records, query, durationMs);
                if (hasSyncedLyrics(bestSoFar) && confidence(bestSoFar, query, durationMs) >= 190) {
                    break;
                }
            }
        } finally {
            for (Future<List<JSONObject>> future : futures) future.cancel(true);
        }

        JSONObject best = chooseBest(records, query, durationMs);
        String lrc = best == null ? "" : best.optString("syncedLyrics", "");
        List<Line> parsed = parseLrc(lrc);
        if (!parsed.isEmpty()) return new LoadResult(parsed, "LRCLIB", true);
        if (best != null && best.optBoolean("instrumental", false)) {
            return new LoadResult(Collections.emptyList(), "纯音乐", false);
        }
        if (successfulRequests == 0) {
            return new LoadResult(Collections.emptyList(), "歌词服务暂不可用", false);
        }
        return new LoadResult(Collections.emptyList(), "暂未找到同步歌词", false);
    }

    private List<Callable<List<JSONObject>>> buildTasks(
            TrackQuery query,
            String rawTitle,
            String rawArtist,
            String album,
            long durationMs
    ) {
        List<Callable<List<JSONObject>>> tasks = new ArrayList<>();
        long seconds = Math.max(0L, Math.round(durationMs / 1000.0));
        boolean exact = album != null && !album.trim().isEmpty() && seconds > 0L;

        if (exact) {
            tasks.add(() -> single(getObject(BASE + "/get-cached?track_name=" + encode(rawTitle)
                    + "&artist_name=" + encode(rawArtist)
                    + "&album_name=" + encode(album)
                    + "&duration=" + seconds, true)));
            if (!query.title.equals(rawTitle.trim())) {
                tasks.add(() -> single(getObject(BASE + "/get-cached?track_name=" + encode(query.title)
                        + "&artist_name=" + encode(rawArtist)
                        + "&album_name=" + encode(album)
                        + "&duration=" + seconds, true)));
            }
        }

        tasks.add(() -> arrayToList(getArray(BASE + "/search?track_name=" + encode(rawTitle)
                + "&artist_name=" + encode(rawArtist))));
        tasks.add(() -> arrayToList(getArray(BASE + "/search?track_name=" + encode(query.title)
                + "&artist_name=" + encode(rawArtist))));
        if (!query.alternateArtist.isEmpty()) {
            tasks.add(() -> arrayToList(getArray(BASE + "/search?track_name=" + encode(query.title)
                    + "&artist_name=" + encode(query.alternateArtist))));
        }
        tasks.add(() -> arrayToList(getArray(BASE + "/search?track_name=" + encode(query.title))));
        return tasks;
    }

    private static TrackQuery cleanQuery(String rawTitle, String rawArtist) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String artist = rawArtist == null ? "" : rawArtist.trim();
        String alternateArtist = "";

        Matcher colon = COVER_COLON.matcher(title);
        if (colon.find()) alternateArtist = colon.group(1).trim();
        title = COVER_BRACKET.matcher(title).replaceAll(" ");
        title = COVER_COLON.matcher(title).replaceAll(" ");
        title = COVER_SUFFIX.matcher(title).replaceAll(" ");
        title = title.replaceAll("(?i)\\bcover\\b", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\-—–_/|:：]+|[\\s\\-—–_/|:：]+$", "")
                .trim();
        if (title.isEmpty()) title = rawTitle == null ? "" : rawTitle.trim();
        return new TrackQuery(title, artist, alternateArtist);
    }

    private static JSONObject chooseBest(List<JSONObject> values, TrackQuery query, long durationMs) {
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        Set<String> seen = new LinkedHashSet<>();
        for (JSONObject item : values) {
            if (item == null) continue;
            String id = item.optString("id", "") + "\u0000" + item.optString("trackName", "")
                    + "\u0000" + item.optString("artistName", "") + "\u0000" + item.optDouble("duration", 0.0);
            if (!seen.add(id)) continue;
            int score = confidence(item, query, durationMs);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore >= 125 ? best : null;
    }

    private static int confidence(JSONObject item, TrackQuery query, long durationMs) {
        if (item == null) return Integer.MIN_VALUE;
        String itemTitle = normalize(item.optString("trackName", ""));
        String itemArtist = normalize(item.optString("artistName", ""));
        String expectedTitle = normalize(query.title);
        String expectedArtist = normalize(query.artist);
        String alternateArtist = normalize(query.alternateArtist);
        long itemDuration = Math.round(item.optDouble("duration", 0.0));
        long expectedSeconds = Math.max(0L, Math.round(durationMs / 1000.0));

        int score = 0;
        if (itemTitle.equals(expectedTitle)) score += 100;
        else if (!expectedTitle.isEmpty() && (itemTitle.contains(expectedTitle) || expectedTitle.contains(itemTitle))) score += 45;
        else score -= 60;

        boolean artistMatched = false;
        if (!alternateArtist.isEmpty() && itemArtist.equals(alternateArtist)) {
            score += 75;
            artistMatched = true;
        } else if (!expectedArtist.isEmpty() && itemArtist.equals(expectedArtist)) {
            score += 70;
            artistMatched = true;
        } else if (!expectedArtist.isEmpty() && (itemArtist.contains(expectedArtist) || expectedArtist.contains(itemArtist))) {
            score += 34;
            artistMatched = true;
        } else if (!alternateArtist.isEmpty() && (itemArtist.contains(alternateArtist) || alternateArtist.contains(itemArtist))) {
            score += 38;
            artistMatched = true;
        } else if (!expectedArtist.isEmpty() && !itemArtist.isEmpty()) {
            score -= 80;
        }

        if (expectedSeconds > 0L && itemDuration > 0L) {
            long diff = Math.abs(itemDuration - expectedSeconds);
            score += diff <= 2 ? 50 : diff <= 6 ? 28 : diff <= 12 ? 10 : diff <= 25 ? -8 : -45;
        }
        if (hasSyncedLyrics(item)) score += 60;
        if (looksLikeLanguageMismatch(item.optString("syncedLyrics", ""), query)) score -= artistMatched ? 20 : 55;
        if (item.optBoolean("instrumental", false)) score += 10;
        return score;
    }

    private static boolean looksLikeLanguageMismatch(String lyrics, TrackQuery query) {
        if (lyrics == null || lyrics.trim().isEmpty()) return false;
        boolean expectedCjk = containsCjk(query.title) || containsCjk(query.artist) || containsCjk(query.alternateArtist);
        if (!expectedCjk) return false;
        int latin = 0;
        int cjk = 0;
        int letters = 0;
        for (int i = 0; i < lyrics.length(); i++) {
            char c = lyrics.charAt(i);
            if (isCjk(c)) {
                cjk += 1;
                letters += 1;
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                latin += 1;
                letters += 1;
            }
        }
        return letters >= 24 && cjk == 0 && latin >= 18;
    }

    private static boolean containsCjk(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            if (isCjk(value.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static boolean hasSyncedLyrics(JSONObject value) {
        return value != null && !value.optString("syncedLyrics", "").trim().isEmpty();
    }

    private static List<JSONObject> single(JSONObject value) {
        if (value == null) return Collections.emptyList();
        return Collections.singletonList(value);
    }

    private static List<JSONObject> arrayToList(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<JSONObject> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value != null) values.add(value);
        }
        return values;
    }

    private static List<Line> parseLrc(String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) return Collections.emptyList();
        List<Line> parsed = new ArrayList<>();
        String[] rows = lrc.replace("\r", "").split("\n");
        for (String row : rows) {
            Matcher matcher = TIMESTAMP.matcher(row);
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            while (matcher.find()) {
                long minutes = parseLong(matcher.group(1));
                long seconds = parseLong(matcher.group(2));
                String fractionText = matcher.group(3);
                long fraction = 0L;
                if (fractionText != null && !fractionText.isEmpty()) {
                    long raw = parseLong(fractionText);
                    if (fractionText.length() == 1) fraction = raw * 100L;
                    else if (fractionText.length() == 2) fraction = raw * 10L;
                    else fraction = raw;
                }
                times.add(minutes * 60_000L + seconds * 1000L + fraction);
                textStart = matcher.end();
            }
            String text = row.substring(Math.min(textStart, row.length())).trim();
            if (text.isEmpty()) continue;
            for (Long time : times) parsed.add(new Line(time, text));
        }
        parsed.sort(Comparator.comparingLong(line -> line.timeMs));
        return Collections.unmodifiableList(parsed);
    }

    private static JSONObject getObject(String url, boolean allowNotFound) throws Exception {
        Response response = request(url);
        if (response.code == 404 && allowNotFound) return null;
        if (response.code < 200 || response.code > 299) throw new IllegalStateException("HTTP " + response.code);
        return response.text.isEmpty() ? null : new JSONObject(response.text);
    }

    private static JSONArray getArray(String url) throws Exception {
        Response response = request(url);
        if (response.code == 404) return new JSONArray();
        if (response.code < 200 || response.code > 299) throw new IllegalStateException("HTTP " + response.code);
        return response.text.isEmpty() ? new JSONArray() : new JSONArray(response.text);
    }

    private static Response request(String value) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(1_800);
        connection.setReadTimeout(2_700);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "TongpinClean/1.3.1-public (Android; synced lyrics via LRCLIB)");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code <= 299
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        output.write(buffer, 0, read);
                    }
                    text = output.toString(StandardCharsets.UTF_8.name());
                }
            }
            return new Response(code, text);
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value.trim(), "UTF-8");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", "")
                .trim();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static final class Snapshot {
        public final String current;
        public final String next;
        public final String source;
        public final boolean synced;

        Snapshot(String current, String next, String source, boolean synced) {
            this.current = current == null ? "" : current;
            this.next = next == null ? "" : next;
            this.source = source == null ? "" : source;
            this.synced = synced;
        }
    }

    private static final class TrackQuery {
        final String title;
        final String artist;
        final String alternateArtist;

        TrackQuery(String title, String artist, String alternateArtist) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.alternateArtist = alternateArtist == null ? "" : alternateArtist;
        }
    }

    private static final class LoadResult {
        final List<Line> lines;
        final String status;
        final boolean synced;

        LoadResult(List<Line> lines, String status, boolean synced) {
            this.lines = lines == null ? Collections.emptyList() : lines;
            this.status = status == null ? "" : status;
            this.synced = synced;
        }
    }

    private static final class CachedLyrics {
        final List<Line> lines;
        final String status;
        final boolean synced;
        final long loadedAt;

        CachedLyrics(List<Line> lines, String status, boolean synced, long loadedAt) {
            this.lines = lines == null ? Collections.emptyList() : lines;
            this.status = status == null ? "" : status;
            this.synced = synced;
            this.loadedAt = loadedAt;
        }
    }

    private static final class Line {
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static final class Response {
        final int code;
        final String text;

        Response(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }
}

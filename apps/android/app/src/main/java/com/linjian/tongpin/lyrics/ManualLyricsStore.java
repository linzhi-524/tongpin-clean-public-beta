package com.linjian.tongpin.lyrics;

import android.content.Context;

import com.linjian.tongpin.data.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ManualLyricsStore {
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    private ManualLyricsStore() {}

    public static Snapshot at(Context context, String trackKey, long positionMs) {
        String raw = Prefs.manualLyrics(context, trackKey);
        if (raw.trim().isEmpty()) return Snapshot.empty();
        List<Line> lines = parseLrc(raw);
        if (lines.isEmpty()) {
            return new Snapshot(compactPlainText(raw), "", "手动导入歌词", true);
        }
        int current = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs + 150L) current = i;
            else break;
        }
        String lyric = current >= 0 ? lines.get(current).text : "";
        String next = current + 1 < lines.size() ? lines.get(current + 1).text : "";
        return new Snapshot(lyric, next, "手动导入歌词", true);
    }

    public static boolean has(Context context, String trackKey) {
        return Prefs.hasManualLyrics(context, trackKey);
    }

    private static String compactPlainText(String raw) {
        String[] rows = raw.replace("\r", "").split("\n");
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String row : rows) {
            String line = TIMESTAMP.matcher(row).replaceAll("").trim();
            if (line.isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(line);
            count += 1;
            if (count >= 8) break;
        }
        String value = builder.toString().trim();
        return value.isEmpty() ? "已导入歌词，但没有可显示的文本" : value;
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

        private Snapshot(String current, String next, String source, boolean synced) {
            this.current = current == null ? "" : current;
            this.next = next == null ? "" : next;
            this.source = source == null ? "" : source;
            this.synced = synced;
        }

        static Snapshot empty() {
            return new Snapshot("", "", "", false);
        }

        public boolean hasText() {
            return synced && (!current.trim().isEmpty() || !next.trim().isEmpty());
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
}

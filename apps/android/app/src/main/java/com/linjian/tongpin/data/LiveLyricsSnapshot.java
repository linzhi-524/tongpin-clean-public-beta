package com.linjian.tongpin.data;

public final class LiveLyricsSnapshot {
    public final String trackKey;
    public final String current;
    public final String next;
    public final String source;
    public final long observedAt;

    public LiveLyricsSnapshot(
            String trackKey,
            String current,
            String next,
            String source,
            long observedAt
    ) {
        this.trackKey = trackKey == null ? "" : trackKey;
        this.current = current == null ? "" : current;
        this.next = next == null ? "" : next;
        this.source = source == null ? "" : source;
        this.observedAt = observedAt;
    }

    public boolean matches(String value) {
        return value != null && !value.isEmpty() && value.equals(trackKey);
    }

    public boolean isFresh(long now, long maxAgeMs) {
        return observedAt > 0L && now - observedAt >= 0L && now - observedAt <= maxAgeMs;
    }

    public boolean hasText() {
        return !current.trim().isEmpty() || !next.trim().isEmpty();
    }
}

package com.linjian.tongpin.data;

public final class RemoteCommand {
    public final String id;
    public final String type;
    public final Long positionMs;
    public final String query;
    public final String title;
    public final String artist;

    public RemoteCommand(String id, String type, Long positionMs) {
        this(id, type, positionMs, "", "", "");
    }

    public RemoteCommand(
            String id,
            String type,
            Long positionMs,
            String query,
            String title,
            String artist
    ) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.positionMs = positionMs;
        this.query = query == null ? "" : query.trim();
        this.title = title == null ? "" : title.trim();
        this.artist = artist == null ? "" : artist.trim();
    }
}

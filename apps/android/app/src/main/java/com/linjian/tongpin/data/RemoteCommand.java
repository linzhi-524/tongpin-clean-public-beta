package com.linjian.tongpin.data;

public final class RemoteCommand {
    public final String id;
    public final String type;
    public final Long positionMs;

    public RemoteCommand(String id, String type, Long positionMs) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.positionMs = positionMs;
    }
}

package com.linjian.tongpin.data;

public final class RoomCredentials {
    public final String code;
    public final String secret;

    public RoomCredentials(String code, String secret) {
        this.code = code == null ? "" : code;
        this.secret = secret == null ? "" : secret;
    }
}

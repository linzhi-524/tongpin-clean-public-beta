package com.linjian.tongpin.media;

import android.content.Context;
import android.content.Intent;

import java.util.Locale;

public final class PlayerCatalog {
    public static final String QQ_MUSIC = "com.tencent.qqmusic";
    public static final String KUGOU_MUSIC = "com.kugou.android";
    public static final String NETEASE_CLOUD_MUSIC = "com.netease.cloudmusic";

    public static final String[] SUPPORTED_PACKAGES = new String[]{
            QQ_MUSIC,
            KUGOU_MUSIC,
            NETEASE_CLOUD_MUSIC
    };

    public static final String SUPPORTED_LABEL = "QQ 音乐 / 酷狗音乐 / 网易云音乐";
    public static final String PLAYER_PROMPT = "请先在 QQ 音乐、酷狗音乐或网易云音乐播放一首歌";

    private PlayerCatalog() {}

    public static boolean isSupported(String packageName) {
        if (packageName == null) return false;
        for (String value : SUPPORTED_PACKAGES) {
            if (value.equals(packageName)) return true;
        }
        return false;
    }

    public static boolean isQqMusic(String packageName) {
        return QQ_MUSIC.equals(packageName);
    }

    public static boolean isMusicLike(String packageName) {
        if (packageName == null) return false;
        String lower = packageName.toLowerCase(Locale.ROOT);
        return lower.contains("music") || lower.contains("kugou") || lower.contains("netease");
    }

    public static String displayName(String packageName) {
        if (QQ_MUSIC.equals(packageName)) return "QQ 音乐";
        if (KUGOU_MUSIC.equals(packageName)) return "酷狗音乐";
        if (NETEASE_CLOUD_MUSIC.equals(packageName)) return "网易云音乐";
        if (packageName == null || packageName.trim().isEmpty()) return "尚未识别";
        return packageName;
    }

    public static String firstInstalledPackage(Context context) {
        if (context == null) return QQ_MUSIC;
        for (String packageName : SUPPORTED_PACKAGES) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) return packageName;
        }
        return QQ_MUSIC;
    }
}

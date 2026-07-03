package com.linjian.tongpin.lyrics;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.linjian.tongpin.data.LiveLyricsSnapshot;
import com.linjian.tongpin.data.PlaybackSnapshot;
import com.linjian.tongpin.data.Prefs;
import com.linjian.tongpin.media.TongpinNotificationListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Optional, user-enabled QQ Music lyric reader.
 *
 * It only handles events from com.tencent.qqmusic. It first inspects the exposed
 * accessibility text nodes. If QQ Music renders lyrics as graphics and the user
 * explicitly enables OCR fallback, it takes a local screenshot, crops the likely
 * lyric area, runs on-device ML Kit OCR, and discards the bitmap immediately.
 */
public final class QQMusicLyricsAccessibilityService extends AccessibilityService {
    private static final String QQ_MUSIC = "com.tencent.qqmusic";
    private static final long SCAN_INTERVAL_MS = 850L;
    private static final long OCR_INTERVAL_MS = 1_500L;
    private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");
    private static final Pattern CONTROL_TEXT = Pattern.compile(
            "(?i)^(播放|暂停|上一首|下一首|返回|更多|评论|下载|收藏|分享|音质|标准|无损|歌词|一起听|倍速|定时关闭|相关推荐|歌曲|歌手|专辑|QQ音乐|VIP|MV|音效|桌面歌词|锁屏歌词)$"
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean screenshotInFlight = new AtomicBoolean(false);
    private TextRecognizer chineseRecognizer;
    private boolean qqMusicActive;
    private long lastOcrAt;

    private final Runnable periodicScan = new Runnable() {
        @Override
        public void run() {
            if (Prefs.qqLyricsEnabled(QQMusicLyricsAccessibilityService.this) && qqMusicActive) {
                scanCurrentWindow();
            }
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        chineseRecognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );
        handler.removeCallbacks(periodicScan);
        handler.post(periodicScan);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        qqMusicActive = QQ_MUSIC.contentEquals(event.getPackageName());
        if (!qqMusicActive || !Prefs.qqLyricsEnabled(this)) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) scanNode(source, Prefs.playback(this), true);
        handler.removeCallbacks(periodicScan);
        handler.postDelayed(periodicScan, 100L);
    }

    @Override
    public void onInterrupt() {
        // Android calls this when the service is temporarily interrupted.
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (chineseRecognizer != null) chineseRecognizer.close();
        super.onDestroy();
    }

    private void scanCurrentWindow() {
        if (!Prefs.qqLyricsEnabled(this)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !QQ_MUSIC.contentEquals(root.getPackageName())) {
            return;
        }

        PlaybackSnapshot playback = Prefs.playback(this);
        if (scanNode(root, playback, false)) return;
        if (Prefs.ocrLyricsEnabled(this)) requestOcr(playback);
    }

    private boolean scanNode(AccessibilityNodeInfo root, PlaybackSnapshot playback, boolean relaxedBounds) {
        int screenHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        List<Candidate> candidates = new ArrayList<>();
        collectCandidates(root, playback, screenHeight, candidates, new HashSet<>(), 0, relaxedBounds);
        LyricsPair pair = choosePair(candidates, screenHeight);
        if (!pair.hasText()) return false;
        publish(playback, pair.current, pair.next, "QQ音乐界面");
        return true;
    }

    private void collectCandidates(
            AccessibilityNodeInfo node,
            PlaybackSnapshot playback,
            int screenHeight,
            List<Candidate> out,
            Set<String> seen,
            int depth,
            boolean relaxedBounds
    ) {
        if (node == null || depth > 24) return;
        addCandidate(node.getText(), node, playback, screenHeight, out, seen, relaxedBounds);
        addCandidate(node.getContentDescription(), node, playback, screenHeight, out, seen, relaxedBounds);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectCandidates(node.getChild(i), playback, screenHeight, out, seen, depth + 1, relaxedBounds);
        }
    }

    private void addCandidate(
            CharSequence raw,
            AccessibilityNodeInfo node,
            PlaybackSnapshot playback,
            int screenHeight,
            List<Candidate> out,
            Set<String> seen,
            boolean relaxedBounds
    ) {
        String text = clean(raw);
        if (!looksLikeLyric(text, playback)) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;
        int centerY = bounds.centerY();
        float minY = relaxedBounds ? 0.02f : 0.18f;
        float maxY = relaxedBounds ? 0.98f : 0.92f;
        if (centerY < screenHeight * minY || centerY > screenHeight * maxY) return;
        String key = normalize(text) + "@" + centerY;
        if (!seen.add(key)) return;
        out.add(new Candidate(text, bounds, node.isSelected(), node.isFocused()));
    }

    private LyricsPair choosePair(List<Candidate> values, int screenHeight) {
        if (values.isEmpty()) return LyricsPair.EMPTY;
        float targetY = screenHeight * 0.56f;
        for (Candidate value : values) {
            float distance = Math.abs(value.bounds.centerY() - targetY);
            value.score = 1000f - distance;
            if (value.selected) value.score += 1600f;
            if (value.focused) value.score += 900f;
            if (value.bounds.width() > getResources().getDisplayMetrics().widthPixels * 0.40f) {
                value.score += 120f;
            }
        }
        Candidate current = Collections.max(values, Comparator.comparingDouble(value -> value.score));
        Candidate next = null;
        int currentY = current.bounds.centerY();
        for (Candidate value : values) {
            if (value == current || value.bounds.centerY() <= currentY + 4) continue;
            if (next == null || value.bounds.centerY() < next.bounds.centerY()) next = value;
        }
        return new LyricsPair(current.text, next == null ? "" : next.text);
    }

    private void requestOcr(PlaybackSnapshot playback) {
        if (Build.VERSION.SDK_INT < 30 || chineseRecognizer == null) return;
        long now = System.currentTimeMillis();
        if (now - lastOcrAt < OCR_INTERVAL_MS || !screenshotInFlight.compareAndSet(false, true)) return;
        lastOcrAt = now;

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshot) {
                Bitmap bitmap = null;
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                try {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardware != null) bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                } finally {
                    buffer.close();
                }
                if (bitmap == null) {
                    screenshotInFlight.set(false);
                    return;
                }
                runOcr(bitmap, playback);
            }

            @Override
            public void onFailure(int errorCode) {
                screenshotInFlight.set(false);
            }
        });
    }

    private void runOcr(Bitmap full, PlaybackSnapshot playback) {
        int left = Math.max(0, Math.round(full.getWidth() * 0.05f));
        int top = Math.max(0, Math.round(full.getHeight() * 0.24f));
        int right = Math.min(full.getWidth(), Math.round(full.getWidth() * 0.95f));
        int bottom = Math.min(full.getHeight(), Math.round(full.getHeight() * 0.88f));
        if (right <= left || bottom <= top) {
            full.recycle();
            screenshotInFlight.set(false);
            return;
        }

        Bitmap crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top);
        full.recycle();
        InputImage image = InputImage.fromBitmap(crop, 0);
        chineseRecognizer.process(image)
                .addOnSuccessListener(result -> {
                    List<Candidate> values = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    int fullHeight = getResources().getDisplayMetrics().heightPixels;
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String text = clean(line.getText());
                            if (!looksLikeLyric(text, playback) || line.getBoundingBox() == null) continue;
                            Rect rect = new Rect(line.getBoundingBox());
                            rect.offset(left, top);
                            String key = normalize(text) + "@" + rect.centerY();
                            if (seen.add(key)) values.add(new Candidate(text, rect, false, false));
                        }
                    }
                    LyricsPair pair = choosePair(values, fullHeight);
                    if (pair.hasText()) publish(playback, pair.current, pair.next, "屏幕识别");
                })
                .addOnCompleteListener(task -> {
                    crop.recycle();
                    screenshotInFlight.set(false);
                });
    }

    private void publish(PlaybackSnapshot playback, String current, String next, String source) {
        String cleanCurrent = clean(current);
        String cleanNext = clean(next);
        if (cleanCurrent.isEmpty() && cleanNext.isEmpty()) return;
        long now = System.currentTimeMillis();
        LiveLyricsSnapshot existing = Prefs.liveLyrics(this);
        if (existing.matches(playback.trackKey())
                && existing.current.equals(cleanCurrent)
                && existing.next.equals(cleanNext)
                && existing.source.equals(source)
                && now - existing.observedAt < 2_000L) {
            return;
        }
        Prefs.saveLiveLyrics(
                this,
                playback.trackKey(),
                cleanCurrent,
                cleanNext,
                source,
                now
        );
        TongpinNotificationListener.requestImmediateRefresh();
    }

    private static boolean looksLikeLyric(String text, PlaybackSnapshot playback) {
        if (text.isEmpty() || text.length() > 90 || !HAS_LETTER.matcher(text).matches()) return false;
        if (CONTROL_TEXT.matcher(text).matches()) return false;
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        if (normalized.equals(normalize(playback.title))
                || normalized.equals(normalize(playback.artist))
                || normalized.equals(normalize(playback.album))) {
            return false;
        }
        if (text.matches("^\\d{1,2}:\\d{2}(?:\\s*/\\s*\\d{1,2}:\\d{2})?$")
                || text.matches("^[·•\\-—_=]+$")) {
            return false;
        }
        return true;
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";
        return value.toString()
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", "")
                .trim();
    }

    private static final class Candidate {
        final String text;
        final Rect bounds;
        final boolean selected;
        final boolean focused;
        float score;

        Candidate(String text, Rect bounds, boolean selected, boolean focused) {
            this.text = text;
            this.bounds = bounds;
            this.selected = selected;
            this.focused = focused;
        }
    }

    private static final class LyricsPair {
        static final LyricsPair EMPTY = new LyricsPair("", "");
        final String current;
        final String next;

        LyricsPair(String current, String next) {
            this.current = current == null ? "" : current;
            this.next = next == null ? "" : next;
        }

        boolean hasText() {
            return !current.isEmpty() || !next.isEmpty();
        }
    }
}

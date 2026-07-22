package com.linjian.tongpin.lyrics;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
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
import com.linjian.tongpin.media.PlayerCatalog;
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
 * Optional, user-enabled lyric reader and QQ Music search helper.
 *
 * It supports two explicit features:
 * 1) reading visible lyrics from QQ Music, Kugou Music, and NetEase Cloud Music,
 *    with optional on-device OCR fallback;
 * 2) automatically opening QQ Music, entering a requested song and clicking the
 *    best matching result when MediaSession.playFromSearch is not supported.
 */
public final class QQMusicLyricsAccessibilityService extends AccessibilityService {
    private static final String QQ_MUSIC = PlayerCatalog.QQ_MUSIC;
    private static final long SCAN_INTERVAL_MS = 850L;
    private static final long OCR_INTERVAL_MS = 1_500L;
    private static final long AUTOMATION_INTERVAL_MS = 360L;
    private static final long AUTOMATION_TIMEOUT_MS = 22_000L;
    private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");
    private static final Pattern CONTROL_TEXT = Pattern.compile(
            "(?i)^(播放|暂停|上一首|下一首|返回|更多|评论|下载|收藏|分享|音质|标准|无损|歌词|一起听|倍速|定时关闭|相关推荐|歌曲|歌手|专辑|QQ音乐|酷狗音乐|网易云音乐|VIP|MV|音效|桌面歌词|锁屏歌词|私人FM|每日推荐|我喜欢|识曲|广告)$"
    );

    private static volatile QQMusicLyricsAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean screenshotInFlight = new AtomicBoolean(false);
    private TextRecognizer chineseRecognizer;
    private boolean supportedPlayerActive;
    private String activePackage = "";
    private long lastOcrAt;
    private SearchRequest searchRequest;

    private final Runnable periodicScan = new Runnable() {
        @Override
        public void run() {
            if (searchRequest == null
                    && Prefs.qqLyricsEnabled(QQMusicLyricsAccessibilityService.this)
                    && supportedPlayerActive) {
                scanCurrentWindow();
            }
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    private final Runnable automationTick = new Runnable() {
        @Override
        public void run() {
            if (searchRequest != null) runSearchAutomation();
            if (searchRequest != null) handler.postDelayed(this, AUTOMATION_INTERVAL_MS);
        }
    };

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean requestSearchAndPlay(
            String commandId,
            String query,
            String title,
            String artist
    ) {
        QQMusicLyricsAccessibilityService service = instance;
        if (service == null) return false;
        SearchRequest request = new SearchRequest(commandId, query, title, artist);
        service.handler.post(() -> {
            if (service.searchRequest != null
                    && service.searchRequest.commandId.equals(request.commandId)) {
                return;
            }
            service.beginSearchAutomation(request);
        });
        return true;
    }

    public static void finishSearchAndPlay(String commandId, boolean success) {
        QQMusicLyricsAccessibilityService service = instance;
        if (service == null) return;
        service.handler.post(() -> service.finishSearchAutomation(commandId, success));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        chineseRecognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );
        handler.removeCallbacks(periodicScan);
        handler.post(periodicScan);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        boolean qqMusicActive = QQ_MUSIC.equals(packageName);
        supportedPlayerActive = PlayerCatalog.isSupported(packageName);
        activePackage = supportedPlayerActive ? packageName : "";

        if (searchRequest != null && qqMusicActive) {
            handler.removeCallbacks(automationTick);
            handler.postDelayed(automationTick, 80L);
            return;
        }

        if (!supportedPlayerActive || !Prefs.qqLyricsEnabled(this)) return;
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
        if (instance == this) instance = null;
        searchRequest = null;
        handler.removeCallbacksAndMessages(null);
        if (chineseRecognizer != null) chineseRecognizer.close();
        super.onDestroy();
    }

    private void beginSearchAutomation(SearchRequest request) {
        searchRequest = request;
        Prefs.saveStatus(this, "自动点歌 · 正在打开 QQ 音乐");
        launchQqMusic();
        handler.removeCallbacks(automationTick);
        handler.postDelayed(automationTick, 180L);
    }

    private void finishSearchAutomation(String commandId, boolean success) {
        SearchRequest request = searchRequest;
        if (request == null || !request.commandId.equals(commandId)) return;
        searchRequest = null;
        handler.removeCallbacks(automationTick);
        if (request.launchedUi) {
            handler.postDelayed(() -> backUntilOutsideQqMusic(0), success ? 700L : 250L);
        }
    }

    private void backUntilOutsideQqMusic(int attempt) {
        if (attempt >= 3) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !QQ_MUSIC.contentEquals(root.getPackageName())) {
            return;
        }
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> backUntilOutsideQqMusic(attempt + 1), 600L);
    }

    private void launchQqMusic() {
        SearchRequest request = searchRequest;
        if (request == null) return;
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(QQ_MUSIC);
            if (intent == null) return;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            request.launchedUi = true;
            request.lastLaunchAt = System.currentTimeMillis();
        } catch (Throwable error) {
            Prefs.saveStatus(this, "自动点歌 · 无法打开 QQ 音乐");
        }
    }

    private void runSearchAutomation() {
        SearchRequest request = searchRequest;
        if (request == null) return;
        long now = System.currentTimeMillis();
        if (now - request.startedAt > AUTOMATION_TIMEOUT_MS) {
            Prefs.saveStatus(this, "自动点歌 · 搜索界面操作超时");
            searchRequest = null;
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !QQ_MUSIC.contentEquals(root.getPackageName())) {
            if (now - request.lastLaunchAt > 1_800L) launchQqMusic();
            return;
        }

        AccessibilityNodeInfo edit = findEditable(root);
        if (request.stage == SearchRequest.STAGE_OPEN_SEARCH) {
            if (edit != null) {
                request.stage = SearchRequest.STAGE_ENTER_QUERY;
            } else {
                AccessibilityNodeInfo searchButton = findSearchButton(root);
                if (searchButton != null && clickNode(searchButton)) {
                    request.lastActionAt = now;
                    Prefs.saveStatus(this, "自动点歌 · 已打开搜索");
                }
                return;
            }
        }

        if (request.stage == SearchRequest.STAGE_ENTER_QUERY) {
            edit = findEditable(root);
            if (edit == null) {
                request.stage = SearchRequest.STAGE_OPEN_SEARCH;
                return;
            }
            if (setNodeText(edit, request.query)) {
                request.stage = SearchRequest.STAGE_SELECT_RESULT;
                request.lastActionAt = now;
                Prefs.saveStatus(this, "自动点歌 · 正在搜索「" + request.title + "」");
                handler.postDelayed(() -> submitSearchIfPossible(), 450L);
            }
            return;
        }

        if (request.stage == SearchRequest.STAGE_SELECT_RESULT) {
            AccessibilityNodeInfo result = findBestResult(root, request.title, request.artist);
            if (result != null && clickNode(result)) {
                request.stage = SearchRequest.STAGE_WAIT_PLAYBACK;
                request.lastActionAt = now;
                Prefs.saveStatus(this, "自动点歌 · 已点击最匹配结果");
                return;
            }

            if (!request.simplified && now - request.lastActionAt > 5_500L) {
                edit = findEditable(root);
                if (edit != null && !request.title.isEmpty() && setNodeText(edit, request.title)) {
                    request.simplified = true;
                    request.lastActionAt = now;
                    Prefs.saveStatus(this, "自动点歌 · 改用歌名重新搜索");
                    handler.postDelayed(() -> submitSearchIfPossible(), 420L);
                }
            } else if (now - request.lastActionAt > 1_200L) {
                submitSearchIfPossible();
            }
        }
    }

    private void submitSearchIfPossible() {
        if (searchRequest == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo edit = findEditable(root);
        if (edit != null && Build.VERSION.SDK_INT >= 30) {
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        AccessibilityNodeInfo button = findSearchButton(root);
        if (button != null && button != edit) clickNode(button);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable()) return root;
        CharSequence className = root.getClassName();
        if (className != null && className.toString().toLowerCase(Locale.ROOT).contains("edittext")) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(root.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findSearchButton(AccessibilityNodeInfo root) {
        List<ScoredNode> values = new ArrayList<>();
        collectSearchButtons(root, values, 0);
        if (values.isEmpty()) return null;
        return Collections.max(values, Comparator.comparingInt(value -> value.score)).node;
    }

    private void collectSearchButtons(AccessibilityNodeInfo node, List<ScoredNode> out, int depth) {
        if (node == null || depth > 28) return;
        String text = clean(node.getText());
        String description = clean(node.getContentDescription());
        String viewId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
        int score = 0;
        if ("搜索".equals(text) || "搜索".equals(description)) score += 120;
        if (text.contains("搜索") || description.contains("搜索")) score += 55;
        if (viewId.contains("search")) score += 80;
        if (node.isClickable()) score += 25;
        if (!node.isEditable() && score > 0) out.add(new ScoredNode(node, score));
        for (int i = 0; i < node.getChildCount(); i++) {
            collectSearchButtons(node.getChild(i), out, depth + 1);
        }
    }

    private AccessibilityNodeInfo findBestResult(AccessibilityNodeInfo root, String title, String artist) {
        String normalizedTitle = normalize(title);
        String normalizedArtist = normalize(artist);
        if (normalizedTitle.isEmpty()) return null;
        List<ScoredNode> values = new ArrayList<>();
        collectResultCandidates(root, normalizedTitle, normalizedArtist, values, 0);
        if (values.isEmpty()) return null;
        return Collections.max(values, Comparator.comparingInt(value -> value.score)).node;
    }

    private void collectResultCandidates(
            AccessibilityNodeInfo node,
            String title,
            String artist,
            List<ScoredNode> out,
            int depth
    ) {
        if (node == null || depth > 30) return;
        if (!node.isEditable()) {
            String text = normalize(clean(node.getText()));
            String description = normalize(clean(node.getContentDescription()));
            String candidate = !text.isEmpty() ? text : description;
            int score = textMatchScore(candidate, title);
            if (score > 0) {
                AccessibilityNodeInfo clickable = clickableAncestor(node, 8);
                if (clickable != null) {
                    String family = normalize(flattenText(clickable, 3));
                    if (!artist.isEmpty() && family.contains(artist)) score += 55;
                    Rect bounds = new Rect();
                    clickable.getBoundsInScreen(bounds);
                    int screenHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
                    if (bounds.centerY() > screenHeight * 0.16f) score += 10;
                    out.add(new ScoredNode(clickable, score));
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectResultCandidates(node.getChild(i), title, artist, out, depth + 1);
        }
    }

    private static int textMatchScore(String actual, String expected) {
        if (actual.isEmpty() || expected.isEmpty()) return 0;
        if (actual.equals(expected)) return 180;
        if (actual.contains(expected)) return 135;
        if (expected.contains(actual) && actual.length() >= Math.min(4, expected.length())) return 65;
        return 0;
    }

    private static String flattenText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth < 0) return "";
        StringBuilder builder = new StringBuilder();
        appendText(builder, node.getText());
        appendText(builder, node.getContentDescription());
        if (depth > 0) {
            for (int i = 0; i < node.getChildCount(); i++) {
                String child = flattenText(node.getChild(i), depth - 1);
                if (!child.isEmpty()) builder.append(' ').append(child);
            }
        }
        return builder.toString();
    }

    private static void appendText(StringBuilder builder, CharSequence value) {
        if (value == null) return;
        String text = value.toString().trim();
        if (!text.isEmpty()) builder.append(' ').append(text);
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node, int limit) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i <= limit; i++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null || text.trim().isEmpty()) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.trim());
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo clickable = clickableAncestor(node, 8);
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;
        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 80L))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private void scanCurrentWindow() {
        if (!Prefs.qqLyricsEnabled(this)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        String packageName = root.getPackageName().toString();
        if (!PlayerCatalog.isSupported(packageName)) return;
        supportedPlayerActive = true;
        activePackage = packageName;

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
        publish(playback, pair.current, pair.next, liveSourceLabel(playback, "界面"));
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
                    if (pair.hasText()) publish(playback, pair.current, pair.next, liveSourceLabel(playback, "屏幕识别"));
                })
                .addOnCompleteListener(task -> {
                    crop.recycle();
                    screenshotInFlight.set(false);
                });
    }


    private String liveSourceLabel(PlaybackSnapshot playback, String suffix) {
        String packageName = playback == null ? "" : playback.packageName;
        if (packageName == null || packageName.isEmpty()) packageName = activePackage;
        String player = PlayerCatalog.displayName(packageName);
        if (player == null || player.trim().isEmpty() || "尚未识别".equals(player)) return suffix;
        return player + suffix;
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

    private static final class SearchRequest {
        static final int STAGE_OPEN_SEARCH = 0;
        static final int STAGE_ENTER_QUERY = 1;
        static final int STAGE_SELECT_RESULT = 2;
        static final int STAGE_WAIT_PLAYBACK = 3;

        final String commandId;
        final String query;
        final String title;
        final String artist;
        final long startedAt = System.currentTimeMillis();
        int stage = STAGE_OPEN_SEARCH;
        long lastActionAt = startedAt;
        long lastLaunchAt;
        boolean simplified;
        boolean launchedUi;

        SearchRequest(String commandId, String query, String title, String artist) {
            this.commandId = commandId == null ? "" : commandId;
            this.query = query == null ? "" : query.trim();
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
        }
    }

    private static final class ScoredNode {
        final AccessibilityNodeInfo node;
        final int score;

        ScoredNode(AccessibilityNodeInfo node, int score) {
            this.node = node;
            this.score = score;
        }
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

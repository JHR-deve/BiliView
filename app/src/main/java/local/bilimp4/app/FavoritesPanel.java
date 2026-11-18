package local.bilimp4.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textfield.TextInputLayout;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FavoritesPanel implements AutoCloseable {
    interface Actions {
        void download(String key, String title, List<BiliFavorites.Entry> entries,
                      boolean embedDanmaku, boolean audioOnly, int quality);

        void danmakuSettings();

        void signIn();
    }

    private record Tier(int id, String label) {}

    private static final Tier[] TIERS = {
            new Tier(127, "8K"), new Tier(126, "杜比视界"), new Tier(125, "HDR"),
            new Tier(120, "4K"), new Tier(116, "1080P 60帧"), new Tier(112, "1080P 高码率"),
            new Tier(80, "1080P"), new Tier(74, "720P 60帧"), new Tier(64, "720P"),
            new Tier(32, "480P"), new Tier(16, "360P")
    };
    private static final String QUALITY = "favoritesQuality";

    private static final int ROOT = 0;
    private static final int LIST = 1;
    private static final int VIDEOS = 2;
    private static final int KIND_FOLDERS = 1;
    private static final int KIND_ANIME = 2;
    private static final int KIND_DRAMA = 3;
    private static final int PAGE = 40;

    private final MainActivity activity;
    private final Actions actions;
    private final LinearLayout host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable applySearch = this::onSearch;

    private final List<BiliFavorites.Folder> folders = new ArrayList<>();
    private final List<BiliFavorites.Bangumi> anime = new ArrayList<>();
    private final List<BiliFavorites.Bangumi> drama = new ArrayList<>();
    private final List<BiliFavorites.Entry> entries = new ArrayList<>();
    private final List<MaterialCardView> cards = new ArrayList<>();
    private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();

    private int level = ROOT;
    private int kind = KIND_FOLDERS;
    private long folderId;
    private long seasonId;
    private String levelTitle = "收藏夹";
    private String subtitle = "";
    private String keyword = "";
    private String pending = "";
    private int total;
    private int rendered;
    private int renderToken;
    private int generation;
    private boolean loaded;
    private boolean loading;
    private boolean visible;
    private boolean enabled = true;
    private boolean loggedIn;
    private boolean grid = true;
    private boolean selecting;
    private boolean closed;
    private String notice;

    private LinearLayout listHolder;
    private TextView countText;
    private TextView selectionText;
    private MaterialButton multiButton;
    private MaterialButton modeButton;
    private MaterialButton loadMore;
    private MaterialButton downloadButton;
    private LinearLayout selectionRow;

    FavoritesPanel(MainActivity activity, LinearLayout panel, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        host = panel;
        grid = activity.getPreferences(0).getBoolean("favoritesGrid", true);
        showRoot();
    }

    void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) return;
        activity.setPageTitle(levelTitle);
        if (!loaded && !loading) load();
    }

    void invalidate() {
        loaded = false;
        if (visible) load();
    }

    boolean back() {
        if (level == VIDEOS) {
            showList(false);
            return true;
        }
        if (level == LIST) {
            showRoot();
            return true;
        }
        return false;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        applyEnabled();
    }

    private void applyEnabled() {
        boolean on = enabled && !loading;
        for (MaterialCardView card : cards) card.setEnabled(on);
        if (multiButton != null) multiButton.setEnabled(on);
        if (modeButton != null) modeButton.setEnabled(on);
        if (loadMore != null) loadMore.setEnabled(on);
        if (selectionText != null) updateSelection();
    }

    // ---------- 数据 ----------

    private void load() {
        int request = ++generation;
        loading = true;
        notice = null;
        showRoot();
        worker.execute(() -> {
            String cookie;
            try {
                cookie = BiliAccount.restore(activity);
            } catch (Exception error) {
                cookie = null;
            }
            if (cookie == null) {
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    loggedIn = false;
                    showRoot();
                });
                return;
            }
            try {
                List<BiliFavorites.Folder> folderList = BiliFavorites.folders(cookie, null);
                List<BiliFavorites.Bangumi> animeList = BiliFavorites.bangumi(1, cookie, null);
                List<BiliFavorites.Bangumi> dramaList = BiliFavorites.bangumi(2, cookie, null);
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    loggedIn = true;
                    loaded = true;
                    folders.clear();
                    folders.addAll(folderList);
                    anime.clear();
                    anime.addAll(animeList);
                    drama.clear();
                    drama.addAll(dramaList);
                    if (level == ROOT) showRoot();
                });
            } catch (BiliAccount.Expired expired) {
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    loggedIn = false;
                    notice = "登录已过期，请重新扫码。";
                    showRoot();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    notice = ExportQueue.errorMessage(error);
                    showRoot();
                });
            }
        });
    }

    private void openFolder(BiliFavorites.Folder folder) {
        kind = KIND_FOLDERS;
        folderId = folder.id();
        seasonId = 0;
        levelTitle = folder.title();
        subtitle = folder.count() + " 个内容 · " + (folder.secret() ? "私密" : "公开");
        keyword = "";
        showVideos();
        reloadEntries("");
    }

    private void openBangumi(BiliFavorites.Bangumi item, boolean isAnime) {
        kind = isAnime ? KIND_ANIME : KIND_DRAMA;
        folderId = 0;
        seasonId = item.seasonId();
        levelTitle = item.title();
        subtitle = item.progress().isEmpty() ? (isAnime ? "追番" : "追剧") : item.progress();
        keyword = "";
        showVideos();
        reloadEntries("");
    }

    private void reloadEntries(String text) {
        int request = ++generation;
        loading = true;
        notice = null;
        updateVideosHeader();
        worker.execute(() -> {
            String cookie;
            try {
                cookie = BiliAccount.restore(activity);
            } catch (Exception error) {
                cookie = null;
            }
            if (cookie == null) {
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    notice = "登录后可读取收藏内容。";
                    updateVideosHeader();
                    renderEntries();
                });
                return;
            }
            ExportTask task = new ExportTask(activity, null, (stage, percent) -> { });
            try {
                List<BiliFavorites.Entry> loadedEntries;
                int loadedTotal;
                if (kind == KIND_FOLDERS) {
                    BiliFavorites.Media media = BiliFavorites.media(folderId, text, cookie, task);
                    loadedEntries = media.entries();
                    loadedTotal = media.total();
                } else {
                    loadedEntries = episodes(cookie, task);
                    loadedTotal = loadedEntries.size();
                }
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    entries.clear();
                    entries.addAll(loadedEntries);
                    total = loadedTotal;
                    keyword = text;
                    updateVideosHeader();
                    renderEntries();
                });
            } catch (BiliAccount.Expired expired) {
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    notice = "登录已过期，请重新扫码。";
                    updateVideosHeader();
                    renderEntries();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!active(request)) return;
                    loading = false;
                    notice = ExportQueue.errorMessage(error);
                    updateVideosHeader();
                    renderEntries();
                });
            }
        });
    }

    private List<BiliFavorites.Entry> episodes(String cookie, ExportTask task) throws Exception {
        String link = BiliClient.SITE + "/bangumi/play/ss" + seasonId;
        BiliClient.Inspection inspection = BiliClient.inspect(link, cookie, task);
        List<BiliFavorites.Entry> items = new ArrayList<>();
        for (BiliClient.Collection collection : inspection.collections()) {
            String cover = coverFor(seasonId);
            for (BiliClient.Video video : collection.videos()) {
                items.add(new BiliFavorites.Entry(video.title(), levelTitle, video.link(), cover, 0));
            }
        }
        if (items.isEmpty() && inspection.streams() != null) {
            items.add(new BiliFavorites.Entry(inspection.streams().title(), levelTitle, link,
                    coverFor(seasonId), inspection.streams().seconds()));
        }
        if (items.isEmpty() && inspection.notice() != null) throw new Exception(inspection.notice());
        return items;
    }

    private String coverFor(long id) {
        for (BiliFavorites.Bangumi item : anime) {
            if (item.seasonId() == id) return item.cover();
        }
        for (BiliFavorites.Bangumi item : drama) {
            if (item.seasonId() == id) return item.cover();
        }
        return null;
    }

    // ---------- 第一层 ----------

    private void showRoot() {
        level = ROOT;
        levelTitle = "收藏夹";
        cards.clear();
        listHolder = null;
        multiButton = null;
        modeButton = null;
        loadMore = null;
        selectionText = null;
        selectionRow = null;
        selected.clear();
        selecting = false;

        LinearLayout page = activity.column();
        page.addView(activity.text("读取账号里的收藏夹、追番和追剧，逐层进入后可以搜索和批量下载。", 14, true));

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView account = activity.text(loggedIn ? "已登录" : "未登录", 13, true);
        bar.addView(account, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton refresh = activity.button("刷新", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        refresh.setEnabled(enabled && !loading);
        refresh.setOnClickListener(view -> load());
        bar.addView(refresh, new LinearLayout.LayoutParams(-2, -2));
        page.addView(bar, activity.spacing(10));

        if (notice != null) {
            TextView error = activity.text(notice, 13, true);
            error.setTextColor(activity.color(com.google.android.material.R.attr.colorError));
            page.addView(error, activity.spacing(8));
        } else if (loading) {
            page.addView(activity.text("正在读取收藏…", 13, true), activity.spacing(8));
        }

        page.addView(entryCard("我创建的收藏夹",
                loading ? "正在读取…" : loggedIn ? folders.size() + " 个收藏夹" : "登录后可读取",
                R.drawable.ic_folders, loggedIn && !loading, () -> {
                    kind = KIND_FOLDERS;
                    levelTitle = "我创建的收藏夹";
                    showList(true);
                }), activity.spacing(12));
        page.addView(entryCard("我的追番",
                loading ? "正在读取…" : loggedIn ? anime.size() + " 部" : "登录后可读取",
                R.drawable.ic_favorites, loggedIn && !loading, () -> {
                    kind = KIND_ANIME;
                    levelTitle = "我的追番";
                    showList(true);
                }), activity.spacing(12));
        page.addView(entryCard("我的追剧",
                loading ? "正在读取…" : loggedIn ? drama.size() + " 部" : "登录后可读取",
                R.drawable.ic_favorites, loggedIn && !loading, () -> {
                    kind = KIND_DRAMA;
                    levelTitle = "我的追剧";
                    showList(true);
                }), activity.spacing(12));

        if (!loggedIn && !loading) {
            MaterialButton login = activity.button("登录 B 站", com.google.android.material.R.attr.materialButtonStyle);
            login.setOnClickListener(view -> actions.signIn());
            page.addView(login, activity.spacing(18));
        }
        swap(page, true);
        activity.setPageTitle(levelTitle);
    }

    private MaterialCardView entryCard(String title, String info, int icon, boolean clickable, Runnable action) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setRadius(activity.dp(20));
        card.setStrokeWidth(activity.dp(1));
        card.setStrokeColor(activity.color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14));
        row.addView(iconView(icon, activity.color(com.google.android.material.R.attr.colorPrimary)),
                new LinearLayout.LayoutParams(activity.dp(26), activity.dp(26)));
        LinearLayout labels = activity.column();
        TextView name = activity.text(title, 16, false);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(name);
        labels.addView(activity.text(info, 12, true), activity.spacing(3));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -2, 1);
        labelSize.setMarginStart(activity.dp(14));
        row.addView(labels, labelSize);
        TextView more = activity.text("›", 20, true);
        row.addView(more);
        card.addView(row);
        card.setEnabled(clickable && enabled);
        card.setOnClickListener(view -> {
            if (clickable) action.run();
        });
        cards.add(card);
        return card;
    }

    // ---------- 第二层 ----------

    private void showList(boolean forward) {
        level = LIST;
        levelTitle = kindTitle();
        subtitle = kind == KIND_FOLDERS ? folders.size() + " 个收藏夹"
                : (kind == KIND_ANIME ? anime.size() : drama.size()) + " 部";
        selected.clear();
        selecting = false;
        cards.clear();
        listHolder = null;
        multiButton = null;
        selectionRow = null;
        selectionText = null;
        loadMore = null;
        keyword = "";
        pending = "";
        notice = null;

        LinearLayout page = activity.column();
        page.addView(headerRow());
        page.addView(searchField(levelListHint()), activity.spacing(12));
        LinearLayout tools = new LinearLayout(activity);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        countText = activity.text("", 13, true);
        tools.addView(countText, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(tools, activity.spacing(8));
        listHolder = activity.column();
        page.addView(listHolder, activity.spacing(4));
        swap(page, forward);
        renderLevelList();
        activity.setPageTitle(levelTitle);
    }

    private String levelListHint() {
        if (kind == KIND_FOLDERS) return "搜索收藏夹名称";
        return kind == KIND_ANIME ? "搜索追番名称" : "搜索追剧名称";
    }

    private String kindTitle() {
        if (kind == KIND_FOLDERS) return "我创建的收藏夹";
        return kind == KIND_ANIME ? "我的追番" : "我的追剧";
    }

    private void renderLevelList() {
        if (listHolder == null) return;
        listHolder.removeAllViews();
        cards.clear();
        String query = pending.isEmpty() ? keyword : pending;
        if (kind == KIND_FOLDERS) {
            List<BiliFavorites.Folder> shown = new ArrayList<>();
            for (BiliFavorites.Folder folder : folders) {
                if (matches(folder.title(), query)) shown.add(folder);
            }
            countText.setText(query.isEmpty() ? folders.size() + " 个收藏夹"
                    : "找到 " + shown.size() + " 个收藏夹");
            if (shown.isEmpty()) {
                listHolder.addView(activity.text(query.isEmpty() ? "还没有创建收藏夹" : "没有匹配的收藏夹",
                        13, true), activity.spacing(8));
            }
            for (BiliFavorites.Folder folder : shown) {
                listHolder.addView(mediaCard(folder.title(),
                        folder.count() + " 个内容 · " + (folder.secret() ? "私密" : "公开"),
                        null, () -> openFolder(folder)), activity.spacing(10));
            }
        } else {
            List<BiliFavorites.Bangumi> source = kind == KIND_ANIME ? anime : drama;
            List<BiliFavorites.Bangumi> shown = new ArrayList<>();
            for (BiliFavorites.Bangumi item : source) {
                if (matches(item.title(), query)) shown.add(item);
            }
            countText.setText(query.isEmpty() ? source.size() + (kind == KIND_ANIME ? " 部追番" : " 部追剧")
                    : "找到 " + shown.size() + " 部");
            if (shown.isEmpty()) {
                listHolder.addView(activity.text(query.isEmpty()
                        ? (kind == KIND_ANIME ? "还没有追番" : "还没有追剧") : "没有匹配的作品", 13, true),
                        activity.spacing(8));
            }
            for (BiliFavorites.Bangumi item : shown) {
                listHolder.addView(mediaCard(item.title(), item.progress().isEmpty() ? "已加入列表" : item.progress(), item.cover(), () -> openBangumi(item, kind == KIND_ANIME)), activity.spacing(10));
            }
        }
        applyEnabled();
    }

    private static boolean matches(String title, String query) {
        return query.isEmpty() || title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private MaterialCardView mediaCard(String title, String info, String cover, Runnable action) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setRadius(activity.dp(18));
        card.setStrokeWidth(activity.dp(1));
        card.setStrokeColor(activity.color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(10), activity.dp(10), activity.dp(14), activity.dp(10));
        if (cover != null) {
            row.addView(coverView(cover, activity.dp(96), activity.dp(56), 12),
                    new LinearLayout.LayoutParams(activity.dp(96), activity.dp(56)));
        }
        LinearLayout labels = activity.column();
        TextView name = activity.text(title, 15, false);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        labels.addView(activity.text(info, 12, true), activity.spacing(4));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -2, 1);
        labelSize.setMarginStart(cover == null ? 0 : activity.dp(12));
        row.addView(labels, labelSize);
        row.addView(activity.text("›", 20, true));
        card.addView(row);
        card.setOnClickListener(view -> action.run());
        return card;
    }

    // ---------- 第三层 ----------

    private void showVideos() {
        level = VIDEOS;
        keyword = "";
        pending = "";
        notice = null;
        selected.clear();
        selecting = false;
        rendered = 0;
        entries.clear();
        cards.clear();

        LinearLayout page = activity.column();
        page.addView(headerRow());
        page.addView(searchField(kind == KIND_FOLDERS ? "搜索收藏夹内的视频" : "搜索分集"),
                activity.spacing(12));

        LinearLayout tools = new LinearLayout(activity);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        countText = activity.text("", 13, true);
        tools.addView(countText, new LinearLayout.LayoutParams(0, -2, 1));
        modeButton = activity.button(grid ? "方格" : "列表",
                androidx.appcompat.R.attr.borderlessButtonStyle);
        modeButton.setIconResource(grid ? R.drawable.ic_grid : R.drawable.ic_list);
        modeButton.setOnClickListener(view -> {
            grid = !grid;
            activity.getPreferences(0).edit().putBoolean("favoritesGrid", grid).apply();
            modeButton.setText(grid ? "方格" : "列表");
            modeButton.setIconResource(grid ? R.drawable.ic_grid : R.drawable.ic_list);
            renderEntries();
        });
        tools.addView(modeButton, new LinearLayout.LayoutParams(-2, -2));
        page.addView(tools, activity.spacing(10));

        selectionRow = new LinearLayout(activity);
        selectionRow.setGravity(Gravity.CENTER_VERTICAL);
        selectionText = activity.text("", 14, false);
        selectionRow.addView(selectionText, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton all = activity.button("全选", androidx.appcompat.R.attr.borderlessButtonStyle);
        all.setOnClickListener(view -> toggleAll());
        selectionRow.addView(all, new LinearLayout.LayoutParams(-2, -2));
        MaterialButton download = activity.button("下载", com.google.android.material.R.attr.materialButtonStyle);
        download.setOnClickListener(view -> {
            List<BiliFavorites.Entry> chosen = chosen();
            if (!chosen.isEmpty()) showDownloadSheet(chosen);
        });
        downloadButton = download;
        selectionRow.addView(download, new LinearLayout.LayoutParams(-2, -2));
        selectionRow.setVisibility(View.GONE);
        page.addView(selectionRow, activity.spacing(6));

        listHolder = activity.column();
        page.addView(listHolder, activity.spacing(10));
        swap(page, true);
        activity.setPageTitle(levelTitle);
    }

    private View headerRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(activity);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(activity.color(com.google.android.material.R.attr.colorOnSurface));
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        back.setBackgroundResource(ripple.resourceId);
        back.setContentDescription("返回");
        back.setOnClickListener(view -> back());
        row.addView(back, new LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)));

        LinearLayout labels = activity.column();
        TextView title = activity.text(levelTitle, 17, false);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        TextView sub = activity.text(subtitle, 12, true);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(sub, activity.spacing(2));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -2, 1);
        labelSize.setMarginStart(activity.dp(6));
        row.addView(labels, labelSize);

        multiButton = activity.button("多选", androidx.appcompat.R.attr.borderlessButtonStyle);
        multiButton.setOnClickListener(view -> setSelecting(!selecting));
        row.addView(multiButton, new LinearLayout.LayoutParams(-2, -2));
        if (level == LIST) multiButton.setVisibility(View.GONE);
        return row;
    }

    private TextInputLayout searchField(String hint) {
        TextInputLayout field = new TextInputLayout(activity, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        field.setHint(hint);
        field.setBoxCornerRadii(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16));
        field.setStartIconDrawable(R.drawable.ic_search);
        field.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        com.google.android.material.textfield.TextInputEditText input =
                new com.google.android.material.textfield.TextInputEditText(field.getContext());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText(keyword);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable text) {
                pending = text.toString().trim();
                main.removeCallbacks(applySearch);
                main.postDelayed(applySearch, 380);
            }
        });
        field.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private void onSearch() {
        if (closed) return;
        if (level == LIST) {
            keyword = pending;
            renderLevelList();
        } else if (level == VIDEOS) {
            if (kind == KIND_FOLDERS) reloadEntries(pending);
            else {
                keyword = pending;
                renderEntries();
            }
        }
    }

    private void updateVideosHeader() {
        if (countText == null) return;
        if (notice != null) {
            countText.setText(notice);
            return;
        }
        if (loading) {
            countText.setText("正在读取…");
            return;
        }
        String base;
        if (kind == KIND_FOLDERS) base = entries.size() + " 个视频";
        else base = entries.size() + " 集";
        if (!keyword.isEmpty()) {
            base = "找到 " + entries.size() + (kind == KIND_FOLDERS ? " 个结果" : " 集");
        } else if (total > entries.size()) {
            base = "已读取 " + entries.size() + " / " + total;
        }
        countText.setText(base);
    }

    private void renderEntries() {
        if (listHolder == null) return;
        listHolder.removeAllViews();
        cards.clear();
        loadMore = null;
        selected.clear();
        rendered = 0;
        int token = ++renderToken;
        List<BiliFavorites.Entry> shown = visibleEntries();
        if (shown.isEmpty()) {
            listHolder.addView(activity.text(notice != null ? "换个关键词试试"
                    : keyword.isEmpty() ? "这里面还没有视频" : "没有匹配的视频", 13, true), activity.spacing(8));
            updateSelection();
            return;
        }
        addPage(shown, token);
        updateSelection();
    }

    private List<BiliFavorites.Entry> visibleEntries() {
        if (kind == KIND_FOLDERS || keyword.isEmpty()) return entries;
        List<BiliFavorites.Entry> shown = new ArrayList<>();
        for (BiliFavorites.Entry entry : entries) {
            if (matches(entry.title(), keyword)) shown.add(entry);
        }
        return shown;
    }

    private void addPage(List<BiliFavorites.Entry> shown, int token) {
        if (loadMore != null) {
            listHolder.removeView(loadMore);
            loadMore = null;
        }
        int until = Math.min(shown.size(), rendered + PAGE);
        LinearLayout lastRow = null;
        for (int i = rendered; i < until; i++) {
            if (grid) {
                if (i % 2 == 0) {
                    lastRow = new LinearLayout(activity);
                    lastRow.setOrientation(LinearLayout.HORIZONTAL);
                    listHolder.addView(lastRow, activity.spacing(10));
                }
                MaterialCardView card = gridCard(shown.get(i), i, token);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (i % 2 == 1) params.setMarginStart(activity.dp(10));
                lastRow.addView(card, params);
            } else {
                listHolder.addView(listCard(shown.get(i), i, token), activity.spacing(10));
            }
        }
        rendered = until;
        if (grid && lastRow != null && lastRow.getChildCount() == 1) {
            View spacer = new View(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1);
            params.setMarginStart(activity.dp(10));
            lastRow.addView(spacer, params);
        }
        if (rendered < shown.size()) {
            loadMore = activity.button("加载更多（剩余 " + (shown.size() - rendered) + "）",
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            loadMore.setOnClickListener(view -> {
                addPage(shown, token);
                updateSelection();
                applyEnabled();
            });
            listHolder.addView(loadMore, activity.spacing(12));
        }
    }

    private MaterialCardView gridCard(BiliFavorites.Entry entry, int index, int token) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setRadius(activity.dp(16));
        card.setStrokeWidth(activity.dp(1));
        card.setStrokeColor(activity.color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout body = activity.column();
        FrameLayout cover = new FrameLayout(activity);
        cover.addView(coverView(entry.cover(), ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(96), 16),
                new FrameLayout.LayoutParams(-1, activity.dp(96)));
        if (entry.seconds() > 0) cover.addView(badge(time(entry.seconds())));
        body.addView(cover, new LinearLayout.LayoutParams(-1, activity.dp(96)));
        LinearLayout labels = activity.column();
        labels.setPadding(activity.dp(10), activity.dp(9), activity.dp(10), activity.dp(11));
        TextView name = activity.text(entry.title(), 13, false);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView author = activity.text(entry.author().isEmpty() ? " " : entry.author(), 11, true);
        author.setMaxLines(1);
        author.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(author, activity.spacing(4));
        body.addView(labels);
        card.addView(body);
        bindCard(card, entry, index);
        return card;
    }

    private MaterialCardView listCard(BiliFavorites.Entry entry, int index, int token) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setRadius(activity.dp(16));
        card.setStrokeWidth(activity.dp(1));
        card.setStrokeColor(activity.color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(10), activity.dp(10), activity.dp(12), activity.dp(10));
        FrameLayout cover = new FrameLayout(activity);
        cover.addView(coverView(entry.cover(), activity.dp(120), activity.dp(68), 12),
                new FrameLayout.LayoutParams(-1, -1));
        if (entry.seconds() > 0) cover.addView(badge(time(entry.seconds())));
        row.addView(cover, new LinearLayout.LayoutParams(activity.dp(120), activity.dp(68)));
        LinearLayout labels = activity.column();
        TextView name = activity.text(entry.title(), 14, false);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        labels.addView(activity.text(entry.author().isEmpty() ? " " : entry.author(), 12, true),
                activity.spacing(4));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -2, 1);
        labelSize.setMarginStart(activity.dp(12));
        row.addView(labels, labelSize);
        card.addView(row);
        bindCard(card, entry, index);
        return card;
    }

    private void bindCard(MaterialCardView card, BiliFavorites.Entry entry, int index) {
        card.setTag(index);
        card.setCheckable(selecting);
        card.setChecked(selecting && selected.contains(index));
        card.setOnClickListener(view -> {
            if (selecting) {
                if (selected.contains(index)) selected.remove(index);
                else selected.add(index);
                card.setChecked(selected.contains(index));
                updateSelection();
            } else {
                showDownloadSheet(List.of(entry));
            }
        });
        card.setOnLongClickListener(view -> {
            if (!selecting) {
                setSelecting(true);
                selected.add(index);
                card.setChecked(true);
                updateSelection();
            }
            return true;
        });
        card.setEnabled(enabled);
        cards.add(card);
    }

    private void setSelecting(boolean on) {
        selecting = on;
        if (!on) selected.clear();
        if (multiButton != null) multiButton.setText(on ? "完成" : "多选");
        if (selectionRow != null) selectionRow.setVisibility(on ? View.VISIBLE : View.GONE);
        for (MaterialCardView card : cards) {
            Object tag = card.getTag();
            boolean checked = on && tag instanceof Integer && selected.contains((Integer) tag);
            card.setCheckable(on);
            card.setChecked(checked);
        }
        updateSelection();
    }

    private void toggleAll() {
        int count = visibleEntries().size();
        if (selected.size() >= count && count > 0) selected.clear();
        else {
            selected.clear();
            for (int i = 0; i < count; i++) selected.add(i);
        }
        for (MaterialCardView card : cards) {
            Object tag = card.getTag();
            if (tag instanceof Integer) card.setChecked(selected.contains((Integer) tag));
        }
        updateSelection();
    }

    private List<BiliFavorites.Entry> chosen() {
        List<BiliFavorites.Entry> list = visibleEntries();
        List<BiliFavorites.Entry> picked = new ArrayList<>();
        for (int index : selected) {
            if (index >= 0 && index < list.size()) picked.add(list.get(index));
        }
        return picked;
    }

    private void updateSelection() {
        if (selectionText == null) return;
        selectionText.setText(selected.isEmpty() ? "未选择" : "已选 " + selected.size() + " 项");
        if (downloadButton != null) downloadButton.setEnabled(!selected.isEmpty() && enabled);
    }

    // ---------- 下载选项 ----------

    private void showDownloadSheet(List<BiliFavorites.Entry> picked) {
        if (picked.isEmpty()) return;
        LinearLayout body = activity.column();
        body.setPadding(activity.dp(24), activity.dp(4), activity.dp(24), activity.dp(24));
        String head = picked.size() == 1 ? picked.get(0).title() : "已选 " + picked.size() + " 个视频";
        TextView title = activity.text(head, 17, false);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title);
        body.addView(activity.text(picked.size() == 1 ? "选择下载方式后加入队列"
                : "每条视频分别按上限取可用画质", 13, true), activity.spacing(6));

        int[] quality = {activity.getPreferences(0).getInt(QUALITY, 0)};
        MaterialButton pick = activity.button("画质上限 · " + qualityLabel(quality[0]),
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        pick.setOnClickListener(view -> {
            String[] labels = new String[TIERS.length + 1];
            labels[0] = "最高可用";
            for (int i = 0; i < TIERS.length; i++) labels[i + 1] = TIERS[i].label();
            int checked = 0;
            for (int i = 0; i < TIERS.length; i++) {
                if (TIERS[i].id() == quality[0]) checked = i + 1;
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("选择画质上限")
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        quality[0] = which == 0 ? 0 : TIERS[which - 1].id();
                        activity.getPreferences(0).edit().putInt(QUALITY, quality[0]).apply();
                        pick.setText("画质上限 · " + qualityLabel(quality[0]));
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        body.addView(pick, activity.spacing(16));

        MaterialSwitch danmaku = new MaterialSwitch(activity);
        danmaku.setText("将弹幕嵌入 MP4");
        danmaku.setMinHeight(activity.dp(48));
        danmaku.setChecked(false);
        body.addView(danmaku, activity.spacing(14));
        MaterialButton settings = activity.button("弹幕设置", androidx.appcompat.R.attr.borderlessButtonStyle);
        settings.setOnClickListener(view -> actions.danmakuSettings());
        body.addView(settings, new LinearLayout.LayoutParams(-2, -2));

        MaterialCheckBox audio = new MaterialCheckBox(activity);
        audio.setText("仅下载音频（M4A）");
        audio.setMinHeight(activity.dp(44));
        audio.setChecked(false);
        body.addView(audio, activity.spacing(6));
        TextView hint = activity.text("只保存音轨为 M4A，不能嵌入弹幕。", 12, true);
        body.addView(hint, activity.spacing(2));
        boolean startAudio = audio.isChecked();
        hint.setVisibility(startAudio ? View.VISIBLE : View.GONE);
        danmaku.setEnabled(!startAudio);
        audio.setOnCheckedChangeListener((view, checked) -> {
            hint.setVisibility(checked ? View.VISIBLE : View.GONE);
            danmaku.setEnabled(!checked);
        });

        body.addView(activity.text("视频没有所选档位时，自动使用不高于它的可用画质。", 12, true),
                activity.spacing(10));

        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        MaterialButton submit = activity.button("加入队列", com.google.android.material.R.attr.materialButtonStyle);
        submit.setOnClickListener(view -> {
            boolean audioOnly = audio.isChecked();
            boolean embed = danmaku.isChecked() && !audioOnly;
            sheet.dismiss();
            setSelecting(false);
            actions.download(kind == KIND_FOLDERS ? "folder:" + folderId : "season:" + seasonId,
                    levelTitle, picked, embed, audioOnly, quality[0]);
        });
        body.addView(submit, activity.spacing(16));
        sheet.setContentView(body);
        sheet.show();
    }

    // ---------- 视图工具 ----------

    private View coverView(String address, int width, int height, int radius) {
        ShapeableImageView view = new ShapeableImageView(activity);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(activity.dp(radius)).build());
        view.setBackgroundColor(activity.color(com.google.android.material.R.attr.colorSurfaceVariant));
        if (address != null && !address.isEmpty()) {
            int token = renderToken;
            view.setTag(address);
            images.execute(() -> {
                android.graphics.Bitmap bitmap = BiliImage.load(address, 320);
                if (bitmap == null) return;
                main.post(() -> {
                    if (closed || token != renderToken) return;
                    if (address.equals(view.getTag())) view.setImageBitmap(bitmap);
                });
            });
        }
        return view;
    }

    private View badge(String text) {
        TextView view = activity.text(text, 10, false);
        view.setTextColor(0xFFFFFFFF);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xB3000000);
        background.setCornerRadius(activity.dp(6));
        view.setBackground(background);
        view.setPadding(activity.dp(5), activity.dp(1), activity.dp(5), activity.dp(1));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        params.setMargins(0, 0, activity.dp(6), activity.dp(6));
        view.setLayoutParams(params);
        return view;
    }

    private View iconView(int drawable, int tint) {
        ImageView view = new ImageView(activity);
        view.setImageResource(drawable);
        view.setImageTintList(ColorStateList.valueOf(tint));
        return view;
    }

    private static String qualityLabel(int id) {
        if (id <= 0) return "最高可用";
        for (Tier tier : TIERS) {
            if (tier.id() == id) return tier.label();
        }
        return "最高可用";
    }

    private static String time(int seconds) {
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void swap(View next, boolean forward) {
        host.removeAllViews();
        next.setAlpha(0f);
        next.setTranslationX(activity.dp(forward ? 20 : -20));
        host.addView(next, new LinearLayout.LayoutParams(-1, -2));
        next.animate().alpha(1f).translationX(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        activity.resetScroll();
    }

    private boolean active(int request) {
        return !closed && request == generation;
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacks(applySearch);
        images.shutdownNow();
        worker.shutdownNow();
    }
}
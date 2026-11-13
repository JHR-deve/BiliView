package local.bilimp4.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VideoHistory extends SQLiteOpenHelper {
    enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED, INTERRUPTED }
    static final String DOWNLOAD = "链接下载";
    static final String CONVERSION = "缓存转换";
    private static final String LEGACY_CONVERSION = "本地转换";
    private static final String IMPORTED = "历史导入";
    private static final String TABLE = "videos";
    private final Context context;

    record Folder(String key, String title, String directory) {}

    record Download(String link, int quality, boolean danmaku) {}

    record Entry(long id, String title, String source, State state, String stage, int percent,
                 Uri uri, String note, boolean burned, long updated, Folder folder, Download download) {
        boolean canRetry() {
            return download != null && (state == State.FAILED || state == State.INTERRUPTED || state == State.CANCELLED);
        }
    }

    record CacheResult(Uri uri, boolean confirmed) {}

    VideoHistory(Context context) {
        super(context, "video-history.db", null, 3);
        this.context = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE videos (_id INTEGER PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL,"
                + " state TEXT NOT NULL, stage TEXT, percent INTEGER NOT NULL DEFAULT -1, uri TEXT UNIQUE,"
                + " note TEXT, burned INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL,"
                + " folder_key TEXT, folder_title TEXT, directory TEXT, cache_key TEXT,"
                + " link TEXT, quality INTEGER, danmaku INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE videos ADD COLUMN folder_key TEXT");
            db.execSQL("ALTER TABLE videos ADD COLUMN folder_title TEXT");
            db.execSQL("ALTER TABLE videos ADD COLUMN directory TEXT");
            db.execSQL("ALTER TABLE videos ADD COLUMN cache_key TEXT");
            db.execSQL("UPDATE videos SET source=? WHERE source=?", new String[]{CONVERSION, LEGACY_CONVERSION});
            db.execSQL("UPDATE videos SET source=? WHERE source=?", new String[]{IMPORTED, "已有成品"});
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE videos ADD COLUMN link TEXT");
            db.execSQL("ALTER TABLE videos ADD COLUMN quality INTEGER");
            db.execSQL("ALTER TABLE videos ADD COLUMN danmaku INTEGER");
        }
    }

    void recover() {
        ContentValues values = state(State.INTERRUPTED);
        values.put("note", "应用关闭时任务尚未完成，可重新发起。");
        getWritableDatabase().update(TABLE, values, "state IN (?,?)",
                new String[]{State.RUNNING.name(), State.QUEUED.name()});
    }

    long enqueue(String title, String source, Folder folder, String cacheKey, Download download) {
        ContentValues values = state(State.QUEUED);
        values.put("title", title);
        values.put("source", source);
        putFolder(values, folder);
        values.put("cache_key", cacheKey);
        if (download != null) {
            values.put("link", download.link());
            values.put("quality", download.quality());
            values.put("danmaku", download.danmaku());
        }
        return getWritableDatabase().insertOrThrow(TABLE, null, values);
    }

    boolean requeue(Entry entry) {
        if (!entry.canRetry()) return false;
        ContentValues values = state(State.QUEUED);
        values.putNull("note");
        values.putNull("stage");
        values.put("percent", -1);
        return getWritableDatabase().update(TABLE, values, "_id=? AND state=?",
                new String[]{Long.toString(entry.id()), entry.state().name()}) == 1;
    }

    Folder folderFor(BiliClient.Collection collection) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"directory"}, "folder_key=?",
                new String[]{collection.key()}, null, null, null, "1")) {
            if (cursor.moveToFirst()) return new Folder(collection.key(), collection.title(), cursor.getString(0));
        }
        String name = ExportTask.folderName(collection.title());
        String directory = name;
        for (int suffix = 2; directoryUsed(directory); suffix++) directory = name + " (" + suffix + ")";
        return new Folder(collection.key(), collection.title(), directory);
    }

    private boolean directoryUsed(String directory) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"_id"}, "directory=? COLLATE NOCASE",
                new String[]{directory}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    void progress(long id, String stage, int percent) {
        ContentValues values = state(State.RUNNING);
        values.put("stage", stage);
        values.put("percent", percent);
        update(id, values);
    }

    void complete(long id, ExportTask.Result result) {
        ContentValues values = state(State.DONE);
        values.put("title", result.name());
        values.put("uri", result.uri().toString());
        values.put("note", result.note());
        values.put("burned", result.burned());
        update(id, values);
    }

    void end(long id, State status, String note) {
        ContentValues values = state(status);
        values.put("note", note);
        update(id, values);
    }

    boolean deleteCompleted(Entry entry) {
        if (entry.state() != State.DONE || entry.uri() == null) return false;
        var resolver = context.getContentResolver();
        try {
            int changed;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_TRASHED, 1);
                changed = resolver.update(entry.uri(), values, null, null);
            } else {
                changed = resolver.delete(entry.uri(), null, null);
            }
            if (changed == 0) {
                try (Cursor cursor = resolver.query(entry.uri(), new String[]{"_id"}, null, null, null)) {
                    if (cursor == null || cursor.moveToFirst()) return false;
                }
            }
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
        getWritableDatabase().delete(TABLE, "_id=? AND state=?", new String[]{
                Long.toString(entry.id()), State.DONE.name()});
        return true;
    }

    List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE,
                new String[]{"_id", "title", "source", "state", "stage", "percent", "uri", "note", "burned", "updated",
                        "folder_key", "folder_title", "directory", "link", "quality", "danmaku"},
                null, null, null, null,
                "CASE state WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END, "
                        + "CASE WHEN state='QUEUED' THEN updated ELSE -updated END, "
                        + "CASE WHEN state='QUEUED' THEN _id ELSE -_id END")) {
            while (cursor.moveToNext()) {
                String uri = cursor.getString(6);
                Folder folder = cursor.isNull(10) ? null
                        : new Folder(cursor.getString(10), cursor.getString(11), cursor.getString(12));
                Download download = cursor.isNull(13) ? null
                        : new Download(cursor.getString(13), cursor.getInt(14), cursor.getInt(15) != 0);
                entries.add(new Entry(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        State.valueOf(cursor.getString(3)), cursor.getString(4), cursor.getInt(5),
                        uri == null ? null : Uri.parse(uri), cursor.getString(7), cursor.getInt(8) != 0,
                        cursor.getLong(9), folder, download));
            }
        }
        return entries;
    }

    Map<String, CacheResult> cacheResults(List<CacheEntry> caches) {
        Map<String, CacheResult> exact = new HashMap<>();
        Map<String, Uri> legacy = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (CacheEntry cache : caches) {
            keys.add(cache.key());
            String plain = cacheExportName(cache, false);
            String burned = cacheExportName(cache, true);
            counts.merge(plain, 1, Integer::sum);
            if (!burned.equals(plain)) counts.merge(burned, 1, Integer::sum);
        }
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"title", "uri", "cache_key"},
                "state=? AND uri IS NOT NULL AND folder_key IS NULL AND source IN (?,?)",
                new String[]{State.DONE.name(), CONVERSION, IMPORTED}, null, null, "updated DESC, _id DESC")) {
            while (cursor.moveToNext()) {
                Uri uri = Uri.parse(cursor.getString(1));
                String key = cursor.getString(2);
                if (key != null) {
                    if (keys.contains(key) && !exact.containsKey(key) && available(context, uri)) {
                        exact.put(key, new CacheResult(uri, true));
                    }
                } else {
                    String name = exportName(cursor.getString(0));
                    if (counts.getOrDefault(name, 0) == 1 && !legacy.containsKey(name) && available(context, uri)) {
                        legacy.put(name, uri);
                    }
                }
            }
        }
        for (CacheEntry cache : caches) {
            if (exact.containsKey(cache.key())) continue;
            Uri uri = legacy.get(cacheExportName(cache, false));
            if (uri == null) uri = legacy.get(cacheExportName(cache, true));
            if (uri != null) exact.put(cache.key(), new CacheResult(uri, false));
        }
        return exact;
    }

    private static String cacheExportName(CacheEntry cache, boolean burned) {
        return exportName(ExportTask.fileName(cache.title() + (burned ? " - 弹幕" : "")));
    }

    private static String exportName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceFirst(" \\(\\d+\\)(?=\\.mp4$)", "");
    }

    void importPrevious(Context context) {
        Uri downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String selection = "relative_path LIKE ? AND mime_type=? AND owner_package_name=? AND is_pending=0";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) selection += " AND is_trashed=0";
        try (Cursor cursor = context.getContentResolver().query(downloads,
                new String[]{"_id", "_display_name", "date_added", "relative_path"},
                selection,
                new String[]{ExportTask.PATH + "/%", "video/mp4", context.getPackageName()}, null)) {
            if (cursor == null) return;
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                while (cursor.moveToNext()) {
                    String title = cursor.getString(1);
                    ContentValues values = state(State.DONE);
                    values.put("title", title);
                    values.put("source", IMPORTED);
                    values.put("uri", Uri.withAppendedPath(downloads, cursor.getString(0)).toString());
                    values.put("burned", title != null && title.matches("(?i).* - 弹幕(?: \\(\\d+\\))?\\.mp4"));
                    values.put("updated", cursor.getLong(2) * 1000);
                    String path = cursor.getString(3);
                    String directory = path.substring(ExportTask.PATH.length() + 1);
                    if (directory.endsWith("/")) directory = directory.substring(0, directory.length() - 1);
                    if (!directory.isEmpty() && !directory.contains("/")) {
                        putFolder(values, importedFolder(db, directory));
                    }
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Some MediaStore providers restrict owner metadata.
        }
    }

    private static Folder importedFolder(SQLiteDatabase db, String directory) {
        try (Cursor cursor = db.query(TABLE, new String[]{"folder_key", "folder_title"}, "directory=?",
                new String[]{directory}, null, null, "updated DESC", "1")) {
            if (cursor.moveToFirst()) return new Folder(cursor.getString(0), cursor.getString(1), directory);
        }
        return new Folder("imported:" + directory, directory, directory);
    }

    private static void putFolder(ContentValues values, Folder folder) {
        if (folder == null) return;
        values.put("folder_key", folder.key());
        values.put("folder_title", folder.title());
        values.put("directory", folder.directory());
    }

    static boolean available(Context context, Uri uri) {
        if (uri == null) return false;
        try (var descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (FileNotFoundException | SecurityException | IllegalArgumentException error) {
            return false;
        } catch (IOException error) {
            return false;
        }
    }

    private static ContentValues state(State state) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        values.put("updated", System.currentTimeMillis());
        return values;
    }

    private void update(long id, ContentValues values) {
        getWritableDatabase().update(TABLE, values, "_id=?", new String[]{Long.toString(id)});
    }
}

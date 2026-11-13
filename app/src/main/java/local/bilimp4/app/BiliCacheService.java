package local.bilimp4.app;

import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Shizuku user service with read-only access to Bilibili's shared cache folders. */
public final class BiliCacheService extends Binder {
    private static final File[] ROOTS = {
            new File("/sdcard/Android/data/tv.danmaku.bili/download"),
            new File("/sdcard/Android/data/com.bilibili.app.blue/download")
    };

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code) {
            case INTERFACE_TRANSACTION:
                reply.writeString(CacheReader.DESCRIPTOR);
                return true;
            case CacheReader.LIST:
                data.enforceInterface(CacheReader.DESCRIPTOR);
                String result = listCaches();
                reply.writeNoException();
                reply.writeString(result);
                return true;
            case CacheReader.OPEN:
                data.enforceInterface(CacheReader.DESCRIPTOR);
                String trackFolder = data.readString();
                ParcelFileDescriptor file = openFile(trackFolder, data.readInt() != 0 ? "audio.m4s" : "video.m4s");
                reply.writeNoException();
                reply.writeTypedObject(file, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                return true;
            case CacheReader.OPEN_DANMAKU:
                data.enforceInterface(CacheReader.DESCRIPTOR);
                String commentFolder = data.readString();
                ParcelFileDescriptor comments = openFile(commentFolder,
                        data.readInt() != 0 ? CacheEntry.PROTOBUF : CacheEntry.XML);
                reply.writeNoException();
                reply.writeTypedObject(comments, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                return true;
            case CacheReader.DESTROY:
                System.exit(0);
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    private String listCaches() {
        try {
            JSONArray caches = new JSONArray();
            for (File root : ROOTS) {
                File canonical = root.getCanonicalFile();
                scan(canonical, canonical, "", new JSONObject(), null, caches, 0);
            }
            return caches.toString();
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("无法扫描 B 站缓存目录。", error);
        }
    }

    private static void scan(File root, File folder, String title, JSONObject inherited, File entryFolder,
                             JSONArray caches, int depth)
            throws IOException, JSONException {
        if (depth > 7 || !within(root, folder) || !folder.isDirectory()) return;
        JSONObject metadata = metadata(root, new File(folder, "entry.json"));
        if (!metadata.optBoolean("is_completed", true)) return;
        if (metadata.length() > 0) entryFolder = folder;
        else metadata = inherited;
        title = CacheEntry.title(metadata, title);
        File video = new File(folder, "video.m4s").getCanonicalFile();
        File audio = new File(folder, "audio.m4s").getCanonicalFile();
        if (within(root, video) && within(root, audio)
                && video.isFile() && video.length() > 0 && audio.isFile() && audio.length() > 0) {
            JSONObject cache = new JSONObject();
            cache.put("title", title.isEmpty() ? "B站缓存 " + (caches.length() + 1) : title);
            cache.put("directory", folder.getPath());
            cache.put("aid", CacheEntry.aid(metadata));
            cache.put("cid", CacheEntry.cid(metadata));
            cache.put("xmlFolder", danmakuFolder(root, folder, entryFolder, CacheEntry.XML));
            cache.put("protobufFolder", danmakuFolder(root, folder, entryFolder, CacheEntry.PROTOBUF));
            caches.put(cache);
            return;
        }
        File[] children = folder.listFiles(File::isDirectory);
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            scan(root, child.getCanonicalFile(), title, metadata, entryFolder, caches, depth + 1);
        }
    }

    private static String danmakuFolder(File root, File folder, File entryFolder, String name) throws IOException {
        if (hasFile(root, folder, name)) return folder.getPath();
        return entryFolder != null && hasFile(root, entryFolder, name) ? entryFolder.getPath() : null;
    }

    private static boolean hasFile(File root, File folder, String name) throws IOException {
        File file = new File(folder, name).getCanonicalFile();
        return within(root, file) && file.isFile() && file.length() > 0;
    }

    private static JSONObject metadata(File root, File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!within(root, canonical) || !canonical.isFile()) return new JSONObject();
        return CacheEntry.readMetadata(new FileInputStream(canonical));
    }

    private ParcelFileDescriptor openFile(String directory, String name) {
        if (directory == null) throw new IllegalArgumentException("缓存目录无效。");
        try {
            File file = new File(directory, name).getCanonicalFile();
            for (File root : ROOTS) {
                if (within(root.getCanonicalFile(), file) && file.isFile()) {
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                }
            }
            throw new IOException("缓存文件已移动或删除。");
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private static boolean within(File root, File file) {
        return file.equals(root) || file.getPath().startsWith(root.getPath() + File.separator);
    }
}

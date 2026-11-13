package local.bilimp4.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SharedCache {
    private record Child(String name, String id, String type, Uri uri) {
        boolean folder() {
            return Document.MIME_TYPE_DIR.equals(type);
        }
    }

    static List<CacheEntry> scan(ContentResolver resolver, Uri tree) throws IOException {
        String id = DocumentsContract.getTreeDocumentId(tree);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        List<CacheEntry> found = new ArrayList<>();
        scan(resolver, tree, id, ExportTask.displayName(resolver, root, "缓存"), "",
                new JSONObject(), null, null, 0, found);
        return found;
    }

    private static void scan(ContentResolver resolver, Uri tree, String id, String name,
                             String inheritedTitle, JSONObject inherited, Child inheritedXml, Child inheritedPb,
                             int depth, List<CacheEntry> found) throws IOException {
        if (depth > 7) return;
        List<Child> files = children(resolver, tree, id);
        Child metadata = named(files, "entry.json");
        JSONObject info = metadata == null ? new JSONObject()
                : CacheEntry.readMetadata(resolver.openInputStream(metadata.uri()));
        if (!info.optBoolean("is_completed", true)) return;
        Child xml = named(files, CacheEntry.XML);
        Child pb = named(files, CacheEntry.PROTOBUF);
        if (info.length() == 0) info = inherited;
        else {
            inheritedXml = xml;
            inheritedPb = pb;
        }
        String title = CacheEntry.title(info, inheritedTitle);
        Child video = named(files, "video.m4s");
        Child audio = named(files, "audio.m4s");
        if (video != null && audio != null) {
            found.add(entry(resolver, title.isEmpty() ? name : title, name, video, audio, info,
                    xml == null ? inheritedXml : xml, pb == null ? inheritedPb : pb));
        } else {
            findSplitMp4(resolver, files, name, found);
        }
        for (Child file : files) {
            if (file.folder()) {
                scan(resolver, tree, file.id(), file.name(), title, info, inheritedXml, inheritedPb, depth + 1, found);
            }
        }
    }

    private static List<Child> children(ContentResolver resolver, Uri tree, String id) throws IOException {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        List<Child> files = new ArrayList<>();
        try (Cursor rows = resolver.query(uri, new String[]{
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE
        }, null, null, null)) {
            if (rows == null) throw new IOException("无法读取已选择的文件夹。");
            while (rows.moveToNext()) {
                String childId = rows.getString(1);
                files.add(new Child(rows.getString(0), childId, rows.getString(2),
                        DocumentsContract.buildDocumentUriUsingTree(tree, childId)));
            }
        }
        return files;
    }

    private static Child named(List<Child> files, String name) {
        for (Child file : files) {
            if (!file.folder() && name.equalsIgnoreCase(file.name())) return file;
        }
        return null;
    }

    private static void findSplitMp4(ContentResolver resolver, List<Child> files, String folder,
                                     List<CacheEntry> found) {
        List<Child> videos = withExtension(files, ".mp4");
        List<Child> audios = withExtension(files, ".m4a");
        for (Child video : videos) {
            String stem = stem(video);
            List<Child> matches = new ArrayList<>();
            for (Child audio : audios) {
                if (stem.equalsIgnoreCase(stem(audio))) matches.add(audio);
            }
            boolean singlePair = videos.size() == 1 && audios.size() == 1;
            Child audio = matches.size() == 1 ? matches.get(0) : singlePair ? audios.get(0) : null;
            if (audio != null) {
                Child xml = named(files, stem + ".xml");
                Child pb = named(files, stem + ".pb");
                found.add(entry(resolver, stem, folder, video, audio, new JSONObject(),
                        xml == null && singlePair ? named(files, CacheEntry.XML) : xml,
                        pb == null && singlePair ? named(files, CacheEntry.PROTOBUF) : pb));
            }
        }
    }

    private static List<Child> withExtension(List<Child> files, String extension) {
        List<Child> matches = new ArrayList<>();
        for (Child file : files) {
            if (!file.folder() && file.name().toLowerCase(Locale.ROOT).endsWith(extension)) matches.add(file);
        }
        return matches;
    }

    private static String stem(Child file) {
        return file.name().substring(0, file.name().lastIndexOf('.')).replaceFirst("-\\d+$", "");
    }

    private static CacheEntry entry(ContentResolver resolver, String title, String folder, Child video, Child audio,
                                    JSONObject info, Child xml, Child pb) {
        return new CacheEntry(title, folder, source(resolver, video), source(resolver, audio),
                CacheEntry.aid(info), CacheEntry.cid(info), source(resolver, xml), source(resolver, pb));
    }

    private static ExportTask.Source source(ContentResolver resolver, Child file) {
        return file == null ? null : () -> resolver.openAssetFileDescriptor(file.uri(), "r");
    }
}

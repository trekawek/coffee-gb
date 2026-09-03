package eu.rekawek.coffeegb.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Creates the best ROM-picker request available without broad storage permissions. */
final class RomPickerIntents {

    static final ComponentName MIUI_FILTERED_PICKER = new ComponentName(
            "com.mi.android.globalFileexplorer",
            "com.android.fileexplorer.activity.FileActivity");
    static final String MIUI_EXTENSION_FILTER = "ext_filter";
    static final String[] SUPPORTED_EXTENSIONS = {"gb", "gbc", "rom", "zip"};

    private RomPickerIntents() {
    }

    static Intent create(Context context) {
        if (hasTrustedMiuiPicker(context.getPackageManager())) {
            return miuiFiltered();
        }
        return standardSaf();
    }

    static Intent miuiFiltered() {
        return new Intent(Intent.ACTION_PICK)
                .setComponent(MIUI_FILTERED_PICKER)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("all/*")
                // MIUI's category-picker filter is flat. FileActivity's extension filter keeps
                // directories visible while hiding non-ROM files, so normal browsing survives.
                .putExtra(MIUI_EXTENSION_FILTER, SUPPORTED_EXTENSIONS.clone())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static Intent standardSaf() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                // Some providers, including Redmi's, label valid GB/GBC files as generic binary
                // data. Keep them selectable when no trusted extension-aware picker is present;
                // RomSourceSnapshot still rejects unsupported selections before loading.
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream", "application/x-gameboy-rom",
                        "application/zip", "application/x-zip-compressed"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    /** Bootstraps the in-screen browser with a persistable scoped directory grant. */
    static Intent directoryTree(Uri initialUri) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (initialUri != null && DocumentsContract.isTreeUri(initialUri)) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        return intent;
    }

    private static boolean hasTrustedMiuiPicker(PackageManager packages) {
        try {
            ActivityInfo info = packages.getActivityInfo(MIUI_FILTERED_PICKER, 0);
            return info.exported && info.enabled && info.applicationInfo != null
                    && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException failure) {
            return false;
        }
    }
}

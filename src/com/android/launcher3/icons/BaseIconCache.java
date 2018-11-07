/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.icons;

import static com.android.launcher3.icons.BitmapInfo.LOW_RES_ICON;
import static com.android.launcher3.icons.GraphicsUtils.setColorAlphaBound;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.IconProvider;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.Provider;
import com.android.launcher3.util.SQLiteCacheHelper;

import java.util.HashMap;
import java.util.HashSet;

import androidx.annotation.NonNull;

public abstract class BaseIconCache {

    private static final String TAG = "BaseIconCache";
    private static final boolean DEBUG = false;

    private static final int INITIAL_ICON_CACHE_CAPACITY = 50;

    // Empty class name is used for storing package default entry.
    public static final String EMPTY_CLASS_NAME = ".";

    public static class CacheEntry extends BitmapInfo {
        public CharSequence title = "";
        public CharSequence contentDescription = "";
    }

    private final HashMap<UserHandle, BitmapInfo> mDefaultIcons = new HashMap<>();

    protected final Context mContext;
    protected final PackageManager mPackageManager;
    protected final IconProvider mIconProvider;

    private final HashMap<ComponentKey, CacheEntry> mCache =
            new HashMap<>(INITIAL_ICON_CACHE_CAPACITY);
    protected final Handler mWorkerHandler;

    protected int mIconDpi;
    IconDB mIconDb;

    private final String mDbFileName;
    private final BitmapFactory.Options mDecodeOptions;
    private final Looper mBgLooper;

    public BaseIconCache(Context context, String dbFileName, Looper bgLooper,
            int iconDpi, int iconPixelSize) {
        mContext = context;
        mDbFileName = dbFileName;
        mPackageManager = context.getPackageManager();
        mBgLooper = bgLooper;

        mIconProvider = IconProvider.newInstance(context);
        mWorkerHandler = new Handler(mBgLooper);

        if (BitmapRenderer.USE_HARDWARE_BITMAP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mDecodeOptions = new BitmapFactory.Options();
            mDecodeOptions.inPreferredConfig = Bitmap.Config.HARDWARE;
        } else {
            mDecodeOptions = null;
        }

        mIconDpi = iconDpi;
        mIconDb = new IconDB(context, dbFileName, iconPixelSize);
    }

    /**
     * Returns the persistable serial number for {@param user}. Subclass should implement proper
     * caching strategy to avoid making binder call every time.
     */
    protected abstract long getSerialNumberForUser(UserHandle user);

    /**
     * Return true if the given app is an instant app and should be badged appropriately.
     */
    protected abstract boolean isInstantApp(ApplicationInfo info);


    public void updateIconParams(int iconDpi, int iconPixelSize) {
        mWorkerHandler.post(() -> updateIconParamsBg(iconDpi, iconPixelSize));
    }

    private synchronized void updateIconParamsBg(int iconDpi, int iconPixelSize) {
        mIconDpi = iconDpi;
        mDefaultIcons.clear();

        mIconDb.close();
        mIconDb = new IconDB(mContext, mDbFileName, iconPixelSize);
        mCache.clear();
    }

    private Drawable getFullResDefaultActivityIcon() {
        return Resources.getSystem().getDrawableForDensity(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? android.R.drawable.sym_def_app_icon : android.R.mipmap.sym_def_app_icon,
                mIconDpi);
    }

    private Drawable getFullResIcon(Resources resources, int iconId) {
        if (resources != null && iconId != 0) {
            try {
                return resources.getDrawableForDensity(iconId, mIconDpi);
            } catch (Resources.NotFoundException e) { }
        }
        return getFullResDefaultActivityIcon();
    }

    public Drawable getFullResIcon(String packageName, int iconId) {
        try {
            return getFullResIcon(mPackageManager.getResourcesForApplication(packageName), iconId);
        } catch (PackageManager.NameNotFoundException e) { }
        return getFullResDefaultActivityIcon();
    }

    public Drawable getFullResIcon(ActivityInfo info) {
        try {
            return getFullResIcon(mPackageManager.getResourcesForApplication(info.applicationInfo),
                    info.getIconResource());
        } catch (PackageManager.NameNotFoundException e) { }
        return getFullResDefaultActivityIcon();
    }

    protected BitmapInfo makeDefaultIcon(UserHandle user) {
        try (LauncherIcons li = LauncherIcons.obtain(mContext)) {
            return li.createBadgedIconBitmap(
                    getFullResDefaultActivityIcon(), user, VERSION.SDK_INT);
        }
    }

    /**
     * Remove any records for the supplied ComponentName.
     */
    public synchronized void remove(ComponentName componentName, UserHandle user) {
        mCache.remove(new ComponentKey(componentName, user));
    }

    /**
     * Remove any records for the supplied package name from memory.
     */
    private void removeFromMemCacheLocked(String packageName, UserHandle user) {
        HashSet<ComponentKey> forDeletion = new HashSet<>();
        for (ComponentKey key: mCache.keySet()) {
            if (key.componentName.getPackageName().equals(packageName)
                    && key.user.equals(user)) {
                forDeletion.add(key);
            }
        }
        for (ComponentKey condemned: forDeletion) {
            mCache.remove(condemned);
        }
    }

    /**
     * Removes the entries related to the given package in memory and persistent DB.
     */
    public synchronized void removeIconsForPkg(String packageName, UserHandle user) {
        removeFromMemCacheLocked(packageName, user);
        long userSerial = getSerialNumberForUser(user);
        mIconDb.delete(
                IconDB.COLUMN_COMPONENT + " LIKE ? AND " + IconDB.COLUMN_USER + " = ?",
                new String[]{packageName + "/%", Long.toString(userSerial)});
    }

    public IconCacheUpdateHandler getUpdateHandler() {
        mIconProvider.updateSystemStateString(mContext);
        return new IconCacheUpdateHandler(this);
    }

    /**
     * Adds an entry into the DB and the in-memory cache.
     * @param replaceExisting if true, it will recreate the bitmap even if it already exists in
     *                        the memory. This is useful then the previous bitmap was created using
     *                        old data.
     * package private
     */
    synchronized <T> void addIconToDBAndMemCache(T object, CachingLogic<T> cachingLogic,
            PackageInfo info, long userSerial, boolean replaceExisting) {
        UserHandle user = cachingLogic.getUser(object);
        ComponentName componentName = cachingLogic.getComponent(object);

        final ComponentKey key = new ComponentKey(componentName, user);
        CacheEntry entry = null;
        if (!replaceExisting) {
            entry = mCache.get(key);
            // We can't reuse the entry if the high-res icon is not present.
            if (entry == null || entry.icon == null || entry.isLowRes()) {
                entry = null;
            }
        }
        if (entry == null) {
            entry = new CacheEntry();
            cachingLogic.loadIcon(mContext, object, entry);
        }
        entry.title = cachingLogic.getLabel(object);
        entry.contentDescription = mPackageManager.getUserBadgedLabel(entry.title, user);
        mCache.put(key, entry);

        ContentValues values = newContentValues(entry, entry.title.toString(),
                componentName.getPackageName());
        addIconToDB(values, componentName, info, userSerial);
    }

    /**
     * Updates {@param values} to contain versioning information and adds it to the DB.
     * @param values {@link ContentValues} containing icon & title
     */
    private void addIconToDB(ContentValues values, ComponentName key,
            PackageInfo info, long userSerial) {
        values.put(IconDB.COLUMN_COMPONENT, key.flattenToString());
        values.put(IconDB.COLUMN_USER, userSerial);
        values.put(IconDB.COLUMN_LAST_UPDATED, info.lastUpdateTime);
        values.put(IconDB.COLUMN_VERSION, info.versionCode);
        mIconDb.insertOrReplace(values);
    }

    public synchronized BitmapInfo getDefaultIcon(UserHandle user) {
        if (!mDefaultIcons.containsKey(user)) {
            mDefaultIcons.put(user, makeDefaultIcon(user));
        }
        return mDefaultIcons.get(user);
    }

    public boolean isDefaultIcon(Bitmap icon, UserHandle user) {
        return getDefaultIcon(user).icon == icon;
    }

    /**
     * Retrieves the entry from the cache. If the entry is not present, it creates a new entry.
     * This method is not thread safe, it must be called from a synchronized method.
     */
    protected <T> CacheEntry cacheLocked(
            @NonNull ComponentName componentName, @NonNull UserHandle user,
            @NonNull Provider<T> infoProvider, @NonNull CachingLogic<T> cachingLogic,
            boolean usePackageIcon, boolean useLowResIcon) {
        return cacheLocked(componentName, user, infoProvider, cachingLogic, usePackageIcon,
                useLowResIcon, true);
    }

    protected <T> CacheEntry cacheLocked(
            @NonNull ComponentName componentName, @NonNull UserHandle user,
            @NonNull Provider<T> infoProvider, @NonNull CachingLogic<T> cachingLogic,
            boolean usePackageIcon, boolean useLowResIcon, boolean addToMemCache) {
        Preconditions.assertWorkerThread();
        ComponentKey cacheKey = new ComponentKey(componentName, user);
        CacheEntry entry = mCache.get(cacheKey);
        if (entry == null || (entry.isLowRes() && !useLowResIcon)) {
            entry = new CacheEntry();
            if (addToMemCache) {
                mCache.put(cacheKey, entry);
            }

            // Check the DB first.
            T object = null;
            boolean providerFetchedOnce = false;

            if (!getEntryFromDB(cacheKey, entry, useLowResIcon)) {
                object = infoProvider.get();
                providerFetchedOnce = true;

                if (object != null) {
                    cachingLogic.loadIcon(mContext, object, entry);
                } else {
                    if (usePackageIcon) {
                        CacheEntry packageEntry = getEntryForPackageLocked(
                                componentName.getPackageName(), user, false);
                        if (packageEntry != null) {
                            if (DEBUG) Log.d(TAG, "using package default icon for " +
                                    componentName.toShortString());
                            packageEntry.applyTo(entry);
                            entry.title = packageEntry.title;
                            entry.contentDescription = packageEntry.contentDescription;
                        }
                    }
                    if (entry.icon == null) {
                        if (DEBUG) Log.d(TAG, "using default icon for " +
                                componentName.toShortString());
                        getDefaultIcon(user).applyTo(entry);
                    }
                }
            }

            if (TextUtils.isEmpty(entry.title)) {
                if (object == null && !providerFetchedOnce) {
                    object = infoProvider.get();
                    providerFetchedOnce = true;
                }
                if (object != null) {
                    entry.title = cachingLogic.getLabel(object);
                    entry.contentDescription = mPackageManager.getUserBadgedLabel(entry.title, user);
                }
            }
        }
        return entry;
    }

    public synchronized void clear() {
        Preconditions.assertWorkerThread();
        mIconDb.clear();
    }

    /**
     * Adds a default package entry in the cache. This entry is not persisted and will be removed
     * when the cache is flushed.
     */
    public synchronized void cachePackageInstallInfo(String packageName, UserHandle user,
            Bitmap icon, CharSequence title) {
        removeFromMemCacheLocked(packageName, user);

        ComponentKey cacheKey = getPackageKey(packageName, user);
        CacheEntry entry = mCache.get(cacheKey);

        // For icon caching, do not go through DB. Just update the in-memory entry.
        if (entry == null) {
            entry = new CacheEntry();
        }
        if (!TextUtils.isEmpty(title)) {
            entry.title = title;
        }
        if (icon != null) {
            LauncherIcons li = LauncherIcons.obtain(mContext);
            li.createIconBitmap(icon).applyTo(entry);
            li.recycle();
        }
        if (!TextUtils.isEmpty(title) && entry.icon != null) {
            mCache.put(cacheKey, entry);
        }
    }

    private static ComponentKey getPackageKey(String packageName, UserHandle user) {
        ComponentName cn = new ComponentName(packageName, packageName + EMPTY_CLASS_NAME);
        return new ComponentKey(cn, user);
    }

    /**
     * Gets an entry for the package, which can be used as a fallback entry for various components.
     * This method is not thread safe, it must be called from a synchronized method.
     */
    protected CacheEntry getEntryForPackageLocked(String packageName, UserHandle user,
            boolean useLowResIcon) {
        Preconditions.assertWorkerThread();
        ComponentKey cacheKey = getPackageKey(packageName, user);
        CacheEntry entry = mCache.get(cacheKey);

        if (entry == null || (entry.isLowRes() && !useLowResIcon)) {
            entry = new CacheEntry();
            boolean entryUpdated = true;

            // Check the DB first.
            if (!getEntryFromDB(cacheKey, entry, useLowResIcon)) {
                try {
                    int flags = Process.myUserHandle().equals(user) ? 0 :
                            PackageManager.GET_UNINSTALLED_PACKAGES;
                    PackageInfo info = mPackageManager.getPackageInfo(packageName, flags);
                    ApplicationInfo appInfo = info.applicationInfo;
                    if (appInfo == null) {
                        throw new NameNotFoundException("ApplicationInfo is null");
                    }

                    LauncherIcons li = LauncherIcons.obtain(mContext);
                    // Load the full res icon for the application, but if useLowResIcon is set, then
                    // only keep the low resolution icon instead of the larger full-sized icon
                    BitmapInfo iconInfo = li.createBadgedIconBitmap(
                            appInfo.loadIcon(mPackageManager), user, appInfo.targetSdkVersion,
                            isInstantApp(appInfo));
                    li.recycle();

                    entry.title = appInfo.loadLabel(mPackageManager);
                    entry.contentDescription = mPackageManager.getUserBadgedLabel(entry.title, user);
                    entry.icon = useLowResIcon ? LOW_RES_ICON : iconInfo.icon;
                    entry.color = iconInfo.color;

                    // Add the icon in the DB here, since these do not get written during
                    // package updates.
                    ContentValues values = newContentValues(
                            iconInfo, entry.title.toString(), packageName);
                    addIconToDB(values, cacheKey.componentName, info, getSerialNumberForUser(user));

                } catch (NameNotFoundException e) {
                    if (DEBUG) Log.d(TAG, "Application not installed " + packageName);
                    entryUpdated = false;
                }
            }

            // Only add a filled-out entry to the cache
            if (entryUpdated) {
                mCache.put(cacheKey, entry);
            }
        }
        return entry;
    }

    private boolean getEntryFromDB(ComponentKey cacheKey, CacheEntry entry, boolean lowRes) {
        Cursor c = null;
        try {
            c = mIconDb.query(
                    lowRes ? IconDB.COLUMNS_LOW_RES : IconDB.COLUMNS_HIGH_RES,
                    IconDB.COLUMN_COMPONENT + " = ? AND " + IconDB.COLUMN_USER + " = ?",
                    new String[]{
                            cacheKey.componentName.flattenToString(),
                            Long.toString(getSerialNumberForUser(cacheKey.user))});
            if (c.moveToNext()) {
                // Set the alpha to be 255, so that we never have a wrong color
                entry.color = setColorAlphaBound(c.getInt(0), 255);
                entry.title = c.getString(1);
                if (entry.title == null) {
                    entry.title = "";
                    entry.contentDescription = "";
                } else {
                    entry.contentDescription = mPackageManager.getUserBadgedLabel(
                            entry.title, cacheKey.user);
                }

                if (lowRes) {
                    entry.icon = LOW_RES_ICON;
                } else {
                    byte[] data = c.getBlob(2);
                    try {
                        entry.icon = BitmapFactory.decodeByteArray(data, 0, data.length,
                                mDecodeOptions);
                    } catch (Exception e) { }
                }
                return true;
            }
        } catch (SQLiteException e) {
            Log.d(TAG, "Error reading icon cache", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    static final class IconDB extends SQLiteCacheHelper {
        private final static int RELEASE_VERSION = 25;

        public final static String TABLE_NAME = "icons";
        public final static String COLUMN_ROWID = "rowid";
        public final static String COLUMN_COMPONENT = "componentName";
        public final static String COLUMN_USER = "profileId";
        public final static String COLUMN_LAST_UPDATED = "lastUpdated";
        public final static String COLUMN_VERSION = "version";
        public final static String COLUMN_ICON = "icon";
        public final static String COLUMN_ICON_COLOR = "icon_color";
        public final static String COLUMN_LABEL = "label";
        public final static String COLUMN_SYSTEM_STATE = "system_state";

        public final static String[] COLUMNS_HIGH_RES = new String[] {
                IconDB.COLUMN_ICON_COLOR, IconDB.COLUMN_LABEL, IconDB.COLUMN_ICON };
        public final static String[] COLUMNS_LOW_RES = new String[] {
                IconDB.COLUMN_ICON_COLOR, IconDB.COLUMN_LABEL };

        public IconDB(Context context, String dbFileName, int iconPixelSize) {
            super(context, dbFileName, (RELEASE_VERSION << 16) + iconPixelSize, TABLE_NAME);
        }

        @Override
        protected void onCreateTable(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                    COLUMN_COMPONENT + " TEXT NOT NULL, " +
                    COLUMN_USER + " INTEGER NOT NULL, " +
                    COLUMN_LAST_UPDATED + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_VERSION + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_ICON + " BLOB, " +
                    COLUMN_ICON_COLOR + " INTEGER NOT NULL DEFAULT 0, " +
                    COLUMN_LABEL + " TEXT, " +
                    COLUMN_SYSTEM_STATE + " TEXT, " +
                    "PRIMARY KEY (" + COLUMN_COMPONENT + ", " + COLUMN_USER + ") " +
                    ");");
        }
    }

    private ContentValues newContentValues(BitmapInfo bitmapInfo, String label, String packageName) {
        ContentValues values = new ContentValues();
        values.put(IconDB.COLUMN_ICON,
                bitmapInfo.isLowRes() ? null : GraphicsUtils.flattenBitmap(bitmapInfo.icon));
        values.put(IconDB.COLUMN_ICON_COLOR, bitmapInfo.color);

        values.put(IconDB.COLUMN_LABEL, label);
        values.put(IconDB.COLUMN_SYSTEM_STATE, mIconProvider.getIconSystemState(packageName));

        return values;
    }
}

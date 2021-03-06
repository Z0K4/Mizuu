package com.miz.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.miz.abstractclasses.TvShowFileSource;
import com.miz.db.DbAdapterSources;
import com.miz.db.DbAdapterTvShow;
import com.miz.db.DbAdapterTvShowEpisode;
import com.miz.filesources.FileTvShow;
import com.miz.filesources.SmbTvShow;
import com.miz.filesources.UpnpTvShow;
import com.miz.functions.FileSource;
import com.miz.functions.MizLib;
import com.miz.functions.TheTVDbObject;
import com.miz.functions.TvShowLibraryUpdateCallback;
import com.miz.mizuu.CancelLibraryUpdate;
import com.miz.mizuu.Main;
import com.miz.mizuu.MizuuApplication;
import com.miz.mizuu.R;
import com.miz.widgets.ShowBackdropWidgetProvider;
import com.miz.widgets.ShowCoverWidgetProvider;
import com.miz.widgets.ShowStackWidgetProvider;

public class TvShowsLibraryUpdate extends IntentService implements TvShowLibraryUpdateCallback {

	public static final String STOP_TVSHOW_LIBRARY_UPDATE = "mizuu-stop-tvshow-library-update";
	private boolean isDebugging = true;
	private ArrayList<FileSource> mFileSources;
	private ArrayList<TvShowFileSource<?>> mTvShowFileSources;
	private Multimap<String, String> mMap = LinkedListMultimap.create();
	private HashSet<String> mUniqueShowIds = new HashSet<String>();
	private boolean mIgnoreRemovedFiles, mClearLibrary, mSearchSubfolders, mClearUnavailable, mDisableEthernetWiFiCheck, mSyncLibraries, mStopUpdate;
	private int mTotalFiles, mShowCount, mEpisodeCount;
	private SharedPreferences mSettings;
	private Editor mEditor;
	private final int NOTIFICATION_ID = 300, POST_UPDATE_NOTIFICATION = 313;
	private NotificationManager mNotificationManager;
	private NotificationCompat.Builder mBuilder;

	public TvShowsLibraryUpdate() {
		super("TvShowsLibraryUpdate");
	}

	public TvShowsLibraryUpdate(String name) {
		super(name);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		log("onDestroy()");

		if (mNotificationManager == null)
			mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		mNotificationManager.cancel(NOTIFICATION_ID);

		showPostUpdateNotification();

		AppWidgetManager awm = AppWidgetManager.getInstance(this);
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(this, ShowStackWidgetProvider.class)), R.id.stack_view); // Update stack view widget
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(this, ShowCoverWidgetProvider.class)), R.id.widget_grid); // Update grid view widget
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(this, ShowBackdropWidgetProvider.class)), R.id.widget_grid); // Update grid view widget

		LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);

		MizLib.scheduleShowsUpdate(this);

		if (MizLib.hasTraktAccount(this) && mSyncLibraries && (mEpisodeCount > 0)) {
			startService(new Intent(getApplicationContext(), TraktTvShowsSyncService.class));
		}
	}

	@Override
	protected void onHandleIntent(Intent intent) {

		log("clear()");

		// Clear and set up all variables
		clear();

		log("setup()");

		// Set up Notification, variables, etc.
		setup();

		log("loadFileSources()");

		// Load all file sources from the database
		loadFileSources();

		log("setupTvShowsFileSources()");

		// Add the different file sources to the TvShowFileSource ArrayList
		setupTvShowsFileSources(mIgnoreRemovedFiles, mSearchSubfolders, mClearLibrary, mDisableEthernetWiFiCheck);

		if (mStopUpdate)
			return;

		log("removeUnidentifiedFiles()");

		// Remove unavailable TV show files, so we can try to identify them again
		if (!mClearLibrary)
			removeUnidentifiedFiles();

		if (mStopUpdate)
			return;

		// Check if the library should be cleared
		if (mClearLibrary) {

			// Reset the preference, so it isn't checked the next
			// time the user wants to update the library
			mEditor = mSettings.edit();
			mEditor.putBoolean("prefsClearLibraryTv", false);
			mEditor.commit();

			log("removeTvShowsFromDatabase()");

			// Remove all entries from the database
			removeTvShowsFromDatabase();
		}

		if (mStopUpdate)
			return;

		// Check if we should remove all unavailable files.
		// Note that this only makes sense if we haven't already cleared the library.
		if (!mClearLibrary && mClearUnavailable) {

			log("removeUnavailableFiles()");

			// Remove all unavailable files from the database
			removeUnavailableFiles();
		}

		log("searchFolders()");

		if (mStopUpdate)
			return;

		// Search all folders
		searchFolders();

		if (mStopUpdate)
			return;
		log("mTotalFiles > 0 check");

		// Check if we've found any files to identify
		if (mTotalFiles > 0) {
			log("updateTvShows()");

			// Start the actual TV shows update / identification task
			updateTvShows();
		}
	}

	private void loadFileSources() {
		mFileSources = new ArrayList<FileSource>();
		DbAdapterSources dbHelperSources = MizuuApplication.getSourcesAdapter();
		Cursor c = dbHelperSources.fetchAllShowSources();
		try {
			while (c.moveToNext()) {
				mFileSources.add(new FileSource(
						c.getLong(c.getColumnIndex(DbAdapterSources.KEY_ROWID)),
						c.getString(c.getColumnIndex(DbAdapterSources.KEY_FILEPATH)),
						c.getInt(c.getColumnIndex(DbAdapterSources.KEY_FILESOURCE_TYPE)),
						c.getString(c.getColumnIndex(DbAdapterSources.KEY_USER)),
						c.getString(c.getColumnIndex(DbAdapterSources.KEY_PASSWORD)),
						c.getString(c.getColumnIndex(DbAdapterSources.KEY_DOMAIN)),
						c.getString(c.getColumnIndex(DbAdapterSources.KEY_TYPE))
						));
			}
		} catch (Exception e) {
		} finally {
			c.close();
		}
	}

	private void setupTvShowsFileSources(boolean mIgnoreRemovedFiles, boolean mSearchSubfolders, boolean mClearLibrary, boolean mDisableEthernetWiFiCheck) {
		for (FileSource fileSource : mFileSources) {
			if (mStopUpdate)
				return;
			switch (fileSource.getFileSourceType()) {
			case FileSource.FILE:
				mTvShowFileSources.add(new FileTvShow(getApplicationContext(), fileSource, mIgnoreRemovedFiles, mSearchSubfolders, mClearLibrary, mDisableEthernetWiFiCheck));
				break;
			case FileSource.SMB:
				mTvShowFileSources.add(new SmbTvShow(getApplicationContext(), fileSource, mIgnoreRemovedFiles, mSearchSubfolders, mClearLibrary, mDisableEthernetWiFiCheck));
				break;
			case FileSource.UPNP:
				mTvShowFileSources.add(new UpnpTvShow(getApplicationContext(), fileSource, mIgnoreRemovedFiles, mSearchSubfolders, mClearLibrary, mDisableEthernetWiFiCheck));
				break;
			}
		}
	}

	private void removeUnidentifiedFiles() {
		for (TvShowFileSource<?> tvShowFileSource : mTvShowFileSources) {
			tvShowFileSource.removeUnidentifiedFiles();
		}
	}

	private void removeTvShowsFromDatabase() {
		// Delete all shows from the database
		DbAdapterTvShow db = MizuuApplication.getTvDbAdapter();
		db.deleteAllShowsInDatabase();

		DbAdapterTvShowEpisode dbEpisodes = MizuuApplication.getTvEpisodeDbAdapter();
		dbEpisodes.deleteAllEpisodesInDatabase();

		// Delete all downloaded images files from the device
		MizLib.deleteRecursive(MizLib.getTvShowThumbFolder(this));
		MizLib.deleteRecursive(MizLib.getTvShowEpisodeFolder(this));
		MizLib.deleteRecursive(MizLib.getTvShowBackdropFolder(this));
	}

	private void removeUnavailableFiles() {
		for (TvShowFileSource<?> tvShowFileSource : mTvShowFileSources) {
			tvShowFileSource.removeUnavailableFiles();
		}
	}

	private void searchFolders() {
		// Temporary collection
		List<String> tempList = null;
		String ignoredTags = mSettings.getString("ignoredTags", "");

		for (int j = 0; j < mTvShowFileSources.size(); j++) {
			updateTvShowScanningNotification(mTvShowFileSources.get(j).toString());
			tempList = mTvShowFileSources.get(j).searchFolder();
			for (int i = 0; i < tempList.size(); i++) {
				mMap.put(MizLib.decryptEpisode(tempList.get(i).contains("<MiZ>") ? tempList.get(i).split("<MiZ>")[0] : tempList.get(i), ignoredTags).getDecryptedFileName().toLowerCase(Locale.getDefault()), tempList.get(i));
			}
		}

		// Clean up...
		if (tempList != null)
			tempList.clear();

		mTotalFiles = mMap.size();
	}

	private void setup() {
		if (!MizLib.isOnline(this)) {
			mStopUpdate = true;
			return;
		}

		LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(mMessageReceiver, new IntentFilter(STOP_TVSHOW_LIBRARY_UPDATE));

		// Set up cancel dialog intent
		Intent notificationIntent = new Intent(this, CancelLibraryUpdate.class);
		notificationIntent.putExtra("isMovie", false);
		notificationIntent.setAction(Intent.ACTION_MAIN);
		notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
		PendingIntent contentIntent = PendingIntent.getActivity(this, NOTIFICATION_ID, notificationIntent, 0);

		// Setup up notification
		mBuilder = new NotificationCompat.Builder(getApplicationContext());
		mBuilder.setSmallIcon(R.drawable.refresh);
		mBuilder.setTicker(getString(R.string.updatingTvShows));
		mBuilder.setContentTitle(getString(R.string.updatingTvShows));
		mBuilder.setContentText(getString(R.string.gettingReady));
		mBuilder.setContentIntent(contentIntent);
		mBuilder.setOngoing(true);
		mBuilder.setOnlyAlertOnce(true);
		mBuilder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.refresh));
		mBuilder.addAction(R.drawable.remove, getString(android.R.string.cancel), contentIntent);

		// Build notification
		Notification updateNotification = mBuilder.build();

		// Show the notification
		mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.notify(NOTIFICATION_ID, updateNotification);

		// Tell the system that this is an ongoing notification, so it shouldn't be killed
		startForeground(NOTIFICATION_ID, updateNotification);

		mSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		mClearLibrary = mSettings.getBoolean("prefsClearLibraryTv", false);
		mSearchSubfolders = mSettings.getBoolean("prefsEnableSubFolderSearch", true);
		mClearUnavailable = mSettings.getBoolean("prefsRemoveUnavailableTv", false);
		mDisableEthernetWiFiCheck = mSettings.getBoolean("prefsDisableEthernetWiFiCheck", false);
		mIgnoreRemovedFiles = mSettings.getBoolean("prefsIgnoredFilesEnabled", false);
		mSyncLibraries = mSettings.getBoolean("syncLibrariesWithTrakt", true);

		mEditor = mSettings.edit();
	}

	private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			mStopUpdate = true;
		}
	};

	private void updateTvShows() {
		// Temporary StringBuilder object to re-use memory and reduce GC's
		StringBuilder sb = new StringBuilder();

		TreeSet<String> mSet = new TreeSet<String>(mMap.keySet());

		for (String tvShow : mSet) {
			if (mStopUpdate)
				return;

			sb.delete(0, sb.length());
			sb.append(tvShow);

			newTvShowObject(mMap.get(sb.toString()));
		}
	}

	/**
	 * Used in the updateTvShows() method.
	 * @param tvShow Name of the TV show we're trying to identify
	 * @param files Collection of file paths to the files related to that TV show
	 */
	private void newTvShowObject(Collection<String> files) {		
		if (MizLib.isOnline(getApplicationContext())) {
			new TheTVDbObject(this, files, "", this);
		}
	}

	private void clear() {
		// Lists
		mFileSources = new ArrayList<FileSource>();
		mTvShowFileSources = new ArrayList<TvShowFileSource<?>>();
		mMap = LinkedListMultimap.create();
		mUniqueShowIds = new HashSet<String>();

		// Booleans
		mIgnoreRemovedFiles = false;
		mClearLibrary = false;
		mSearchSubfolders = true;
		mClearUnavailable = false;
		mDisableEthernetWiFiCheck = false;
		mSyncLibraries = true;
		mStopUpdate = false;

		// Other variables
		mEditor = null;
		mSettings = null;
		mTotalFiles = 0;
		mShowCount = 0;
		mNotificationManager = null;
		mBuilder = null;
	}

	private void log(String msg) {
		if (isDebugging)
			Log.d("TvShowsLibraryUpdate", msg);
	}

	@Override
	public void onTvShowAdded(String showId, String title, Bitmap cover, Bitmap backdrop, int count) {
		if (!showId.equals("invalid")) {
			mUniqueShowIds.add(showId);
			sendUpdateBroadcast();
		}
		updateTvShowAddedNotification(showId, title, cover, backdrop, count);
	}

	@Override
	public void onEpisodeAdded(String showId, String title, Bitmap cover, Bitmap photo) {
		if (!showId.equals("invalid"))
			mEpisodeCount++;
		updateEpisodeAddedNotification(showId, title, cover, photo);
	}

	private void updateEpisodeAddedNotification(String showId, String title, Bitmap cover, Bitmap backdrop) {
		String contentText;
		if (showId.isEmpty() || showId.equalsIgnoreCase("invalid"))
			contentText = getString(R.string.unidentified) + ": " + title;
		else
			contentText = getString(R.string.stringJustAdded) + ": " + title;

		mBuilder.setLargeIcon(cover);
		mBuilder.setContentTitle(getString(R.string.updatingTvShows) + " (" + (int) ((100.0 / (double) mTotalFiles) * (double) mEpisodeCount) + "%)");
		mBuilder.setContentText(contentText);
		mBuilder.setStyle(
				new NotificationCompat.BigPictureStyle()
				.setSummaryText(contentText)
				.bigPicture(backdrop)
				);

		// Show the updated notification
		mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
	}

	private void updateTvShowAddedNotification(String showId, String title, Bitmap cover, Bitmap backdrop, int count) {
		String contentText;
		if (showId.isEmpty() || showId.equalsIgnoreCase("invalid"))
			contentText = getString(R.string.unidentified) + ": " + title + " (" + count + " " + getResources().getQuantityString(R.plurals.episodes, count, count) + ")";
		else
			contentText = getString(R.string.stringJustAdded) + ": " + title + " (" + count + " " + getResources().getQuantityString(R.plurals.episodes, count, count) + ")";

		mBuilder.setLargeIcon(cover);
		mBuilder.setContentTitle(getString(R.string.updatingTvShows) + " (" + (int) ((100.0 / (double) mTotalFiles) * (double) mEpisodeCount) + "%)");
		mBuilder.setContentText(contentText);
		mBuilder.setStyle(
				new NotificationCompat.BigPictureStyle()
				.setSummaryText(contentText)
				.bigPicture(backdrop)
				);

		// Show the updated notification
		mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
	}

	private void updateTvShowScanningNotification(String filesource) {
		mBuilder.setSmallIcon(R.drawable.refresh);
		mBuilder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.refresh));
		mBuilder.setContentTitle(getString(R.string.updatingTvShows));
		mBuilder.setContentText(getString(R.string.scanning) + ": " + filesource);

		// Show the updated notification
		mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
	}

	private void showPostUpdateNotification() {
		mShowCount = mUniqueShowIds.size();

		// Set up cancel dialog intent
		Intent notificationIntent = new Intent(this, Main.class);
		notificationIntent.putExtra("fromUpdate", true);
		notificationIntent.putExtra("startup", "2");
		notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK|Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

		// Setup up notification
		mBuilder = new NotificationCompat.Builder(getApplicationContext());
		if (!mStopUpdate) {
			mBuilder.setSmallIcon(R.drawable.done);
			mBuilder.setTicker(getString(R.string.finishedTvShowsLibraryUpdate));
			mBuilder.setContentTitle(getString(R.string.finishedTvShowsLibraryUpdate));
			mBuilder.setContentText(getString(R.string.stringJustAdded) + " " + mShowCount + " " + getResources().getQuantityString(R.plurals.showsInLibrary, mShowCount, mShowCount) + " (" + mEpisodeCount + " " + getResources().getQuantityString(R.plurals.episodes, mEpisodeCount, mEpisodeCount) + ")");
			mBuilder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.done));
		} else {
			mBuilder.setSmallIcon(R.drawable.ignoresmallfiles);
			mBuilder.setTicker(getString(R.string.stringUpdateCancelled));
			mBuilder.setContentTitle(getString(R.string.stringUpdateCancelled));
			mBuilder.setContentText(getString(R.string.stringJustAdded) + " " + mShowCount + " " + getResources().getQuantityString(R.plurals.showsInLibrary, mShowCount, mShowCount) + " (" + mEpisodeCount + " " + getResources().getQuantityString(R.plurals.episodes, mEpisodeCount, mEpisodeCount) + ")");
			mBuilder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ignoresmallfiles));
		}
		mBuilder.setContentIntent(contentIntent);
		mBuilder.setAutoCancel(true);

		// Build notification
		Notification updateNotification = mBuilder.build();

		// Show the notification
		mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		if (mEpisodeCount > 0)
			mNotificationManager.notify(POST_UPDATE_NOTIFICATION, updateNotification);
	}

	private void sendUpdateBroadcast() {
		LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("mizuu-shows-update"));
	}
}
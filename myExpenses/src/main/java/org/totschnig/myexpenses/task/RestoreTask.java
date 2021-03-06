package org.totschnig.myexpenses.task;

import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;

import com.android.calendar.CalendarContractCompat.Calendars;
import com.annimon.stream.Collectors;

import org.totschnig.myexpenses.MyApplication;
import org.totschnig.myexpenses.R;
import org.totschnig.myexpenses.activity.BackupRestoreActivity;
import org.totschnig.myexpenses.model.Account;
import org.totschnig.myexpenses.model.Template;
import org.totschnig.myexpenses.preference.PrefKey;
import org.totschnig.myexpenses.provider.DatabaseConstants;
import org.totschnig.myexpenses.provider.DbUtils;
import org.totschnig.myexpenses.provider.TransactionDatabase;
import org.totschnig.myexpenses.provider.TransactionProvider;
import org.totschnig.myexpenses.sync.GenericAccountService;
import org.totschnig.myexpenses.sync.SyncAdapter;
import org.totschnig.myexpenses.util.AcraHelper;
import org.totschnig.myexpenses.util.AppDirHelper;
import org.totschnig.myexpenses.util.BackupUtils;
import org.totschnig.myexpenses.util.FileCopyUtils;
import org.totschnig.myexpenses.util.PictureDirHelper;
import org.totschnig.myexpenses.util.Result;
import org.totschnig.myexpenses.util.ZipUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import timber.log.Timber;

import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_ROWID;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_SYNC_ACCOUNT_NAME;

public class RestoreTask extends AsyncTask<Void, Result, Result> {
  public static final String KEY_DIR_NAME_LEGACY = "dirNameLegacy";
  private final TaskExecutionFragment taskExecutionFragment;
  private int restorePlanStrategy;
  private Uri fileUri;
  private String dirNameLegacy;

  RestoreTask(TaskExecutionFragment taskExecutionFragment, Bundle b) {
    this.taskExecutionFragment = taskExecutionFragment;
    this.fileUri = b.getParcelable(TaskExecutionFragment.KEY_FILE_PATH);
    if (fileUri == null) {
      this.dirNameLegacy = b.getString(KEY_DIR_NAME_LEGACY);
    }
    this.restorePlanStrategy = b.getInt(
        BackupRestoreActivity.KEY_RESTORE_PLAN_STRATEGY);
  }

  /*
   * (non-Javadoc) shows toast about success or failure
   * 
   * @see android.os.AsyncTask#onProgressUpdate(Progress[])
   */
  @Override
  protected void onProgressUpdate(Result... values) {
    if (this.taskExecutionFragment.mCallbacks != null) {
      this.taskExecutionFragment.mCallbacks.onProgressUpdate(values[0]);
    }
  }

  /*
   * (non-Javadoc) reports on success triggering restart if needed
   * 
   * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
   */
  @Override
  protected void onPostExecute(Result result) {
    if (this.taskExecutionFragment.mCallbacks != null) {
      this.taskExecutionFragment.mCallbacks.onPostExecute(
          TaskExecutionFragment.TASK_RESTORE, result);
    }
  }

  @Override
  protected Result doInBackground(Void... ignored) {
    File workingDir;
    String currentPlannerId = null, currentPlannerPath = null;
    final MyApplication application = MyApplication.getInstance();
    ContentResolver cr = application.getContentResolver();
    if (fileUri != null) {
      workingDir = AppDirHelper.getCacheDir();
      if (workingDir == null) {
        return new Result(false, R.string.external_storage_unavailable);
      }
      try {
        InputStream is = cr.openInputStream(fileUri);
        boolean zipResult = ZipUtils.unzip(is, workingDir);
        try {
          is.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
        if (!zipResult) {
          return new Result(
              false,
              R.string.restore_backup_archive_not_valid,
              fileUri);
        }
      } catch (FileNotFoundException | SecurityException e) {
        AcraHelper.report(e);
        return new Result(
            false,
            R.string.parse_error_other_exception,
            e.getMessage());
      }
    } else {
      workingDir = new File(AppDirHelper.getAppDir(application).getUri().getPath(), dirNameLegacy);
    }
    File backupFile = BackupUtils.getBackupDbFile(workingDir);
    File backupPrefFile = BackupUtils.getBackupPrefFile(workingDir);
    if (!backupFile.exists()) {
      return new Result(
          false,
          R.string.restore_backup_file_not_found,
          BackupUtils.BACKUP_DB_FILE_NAME, workingDir);
    }
    if (!backupPrefFile.exists()) {
      return new Result(
          false,
          R.string.restore_backup_file_not_found,
          BackupUtils.BACKUP_PREF_FILE_NAME,
          workingDir);
    }

    //peek into file to inspect version
    try {
      SQLiteDatabase db = SQLiteDatabase.openDatabase(
          backupFile.getPath(),
          null,
          SQLiteDatabase.OPEN_READONLY);
      int version = db.getVersion();
      if (version > TransactionDatabase.DATABASE_VERSION) {
        db.close();
        return new Result(
            false,
            R.string.restore_cannot_downgrade,
            version, TransactionDatabase.DATABASE_VERSION);
      }
      db.close();
    } catch (SQLiteException e) {
      return new Result(false, R.string.restore_db_not_valid);
    }

    //peek into preferences to see if there is a calendar configured
    File internalAppDir = application.getFilesDir().getParentFile();
    File sharedPrefsDir = new File(internalAppDir.getPath() + "/shared_prefs/");
    sharedPrefsDir.mkdir();
    if (!sharedPrefsDir.isDirectory()) {
      AcraHelper.report(
          new Exception(String.format(Locale.US, "Could not access shared preferences directory at %s",
              sharedPrefsDir.getAbsolutePath())));
      return new Result(false, R.string.restore_preferences_failure);
    }
    File tempPrefFile = new File(sharedPrefsDir, "backup_temp.xml");
    if (!FileCopyUtils.copy(backupPrefFile, tempPrefFile)) {
      AcraHelper.report(
          new Exception("Preferences restore failed"),
          "FAILED_COPY_OPERATION",
          String.format("%s => %s",
              backupPrefFile.getAbsolutePath(),
              tempPrefFile.getAbsolutePath()));
      return new Result(false, R.string.restore_preferences_failure);
    }
    SharedPreferences backupPref =
        application.getSharedPreferences("backup_temp", 0);
    if (restorePlanStrategy == R.id.restore_calendar_handling_configured) {
      currentPlannerId = application.checkPlanner();
      currentPlannerPath = PrefKey.PLANNER_CALENDAR_PATH.getString("");
      if (currentPlannerId.equals("-1")) {
        return new Result(
            false,
            R.string.restore_not_possible_local_calendar_missing);
      }
    } else if (restorePlanStrategy == R.id.restore_calendar_handling_backup) {
      boolean found = false;
      String calendarId = backupPref
          .getString(PrefKey.PLANNER_CALENDAR_ID.getKey(), "-1");
      String calendarPath = backupPref
          .getString(PrefKey.PLANNER_CALENDAR_PATH.getKey(), "");
      if (!(calendarId.equals("-1") || calendarPath.equals(""))) {
        Cursor c = cr
            .query(
                Calendars.CONTENT_URI,
                new String[]{Calendars._ID},
                MyApplication.CALENDAR_FULL_PATH_PROJECTION + " = ?",
                new String[]{calendarPath},
                null);
        if (c != null) {
          if (c.moveToFirst()) {
            found = true;
          }
          c.close();
        }
      }
      if (!found) {
        return new Result(
            false,
            R.string.restore_not_possible_target_calendar_missing,
            calendarPath);
      }
    }

    if (DbUtils.restore(backupFile)) {
      publishProgress(new Result(true, R.string.restore_db_success));

      //since we already started reading settings, we can not just copy the file
      //unless I found a way
      //either to close the shared preferences and read it again
      //or to find out if we are on a new install without reading preferences
      //
      //we open the backup file and read every entry
      //getSharedPreferences does not allow to access file if it not in private data directory
      //hence we copy it there first
      //upon application install does not exist yet

      application.getSettings()
          .unregisterOnSharedPreferenceChangeListener(application);

      Editor edit = application.getSettings().edit();
      for(Map.Entry<String,?> entry : application.getSettings().getAll().entrySet()) {
        String key = entry.getKey();
        if (!key.equals(PrefKey.ENTER_LICENCE.getKey()) && !key.startsWith("acra")) {
          edit.remove(key);
        }
      }

      for (Map.Entry<String, ?> entry : backupPref.getAll().entrySet()) {
        String key = entry.getKey();
        Object val = entry.getValue();
        if (val.getClass() == Long.class) {
          edit.putLong(key, backupPref.getLong(key, 0));
        } else if (val.getClass() == Integer.class) {
          edit.putInt(key, backupPref.getInt(key, 0));
        } else if (val.getClass() == String.class) {
          edit.putString(key, backupPref.getString(key, ""));
        } else if (val.getClass() == Boolean.class) {
          edit.putBoolean(key, backupPref.getBoolean(key, false));
        } else {
          Timber.i("Found: %s of type %s", key, val.getClass().getName());
        }
      }

      if (restorePlanStrategy == R.id.restore_calendar_handling_configured) {
        edit.putString(PrefKey.PLANNER_CALENDAR_PATH.getKey(), currentPlannerPath);
        edit.putString(PrefKey.PLANNER_CALENDAR_ID.getKey(), currentPlannerId);
      }

      edit.apply();
      application.getSettings()
          .registerOnSharedPreferenceChangeListener(application);
      tempPrefFile.delete();
      if (fileUri != null) {
        backupFile.delete();
        backupPrefFile.delete();
      }
      publishProgress(new Result(true, R.string.restore_preferences_success));
      //if a user restores a backup we do not want past plan instances to flood the database
      PrefKey.PLANNER_LAST_EXECUTION_TIMESTAMP
          .putLong(System.currentTimeMillis());
      //now handling plans
      if (restorePlanStrategy != R.id.restore_calendar_handling_ignore) {
        publishProgress(application.restorePlanner());
      } else {
        //we remove all links to plans we did not restore
        ContentValues planValues = new ContentValues();
        planValues.putNull(DatabaseConstants.KEY_PLANID);
        cr.update(Template.CONTENT_URI,
            planValues, null, null);
      }
      Timber.i("now emptying event cache");
      cr.delete(
          TransactionProvider.EVENT_CACHE_URI, null, null);

      //now handling pictures
      //1.stale uris in the backup can be ignored1
      //delete from db
      cr.delete(
          TransactionProvider.STALE_IMAGES_URI, null, null);
      //2. all images that are left over in external and
      //internal picture dir are now stale
      registerAsStale(false);
      registerAsStale(true);

      //3. move pictures home and update uri
      File backupPictureDir = new File(workingDir, ZipUtils.PICTURES);
      Cursor c = cr.query(TransactionProvider.TRANSACTIONS_URI,
          new String[]{DatabaseConstants.KEY_ROWID, DatabaseConstants.KEY_PICTURE_URI},
          DatabaseConstants.KEY_PICTURE_URI + " IS NOT NULL", null, null);
      if (c == null)
        return new Result(false, R.string.restore_db_failure);
      if (c.moveToFirst()) {
        do {
          ContentValues uriValues = new ContentValues();
          int rowId = c.getInt(0);
          Uri fromBackup = Uri.parse(c.getString(1));
          String fileName = fromBackup.getLastPathSegment();
          File backupImage = new File(backupPictureDir, fileName);
          Uri restored = null;
          if (backupImage.exists()) {
            File restoredImage = PictureDirHelper.getOutputMediaFile(
                fileName.substring(0, fileName.lastIndexOf('.')), false, application.isProtected());
            if (restoredImage == null || !FileCopyUtils.copy(backupImage, restoredImage)) {
              Timber.e("Could not restore file %s from backup", fromBackup.toString());
            } else {
              restored = AppDirHelper.getContentUriForFile(restoredImage);
            }
          } else {
            Timber.e("Could not restore file %s from backup", fromBackup.toString());
          }
          if (restored != null) {
            uriValues.put(DatabaseConstants.KEY_PICTURE_URI, restored.toString());
          } else {
            uriValues.putNull(DatabaseConstants.KEY_PICTURE_URI);
          }
          cr.update(
              TransactionProvider.TRANSACTIONS_URI,
              uriValues,
              DatabaseConstants.KEY_ROWID + " = ?",
              new String[]{String.valueOf(rowId)});
        } while (c.moveToNext());
      }
      c.close();
      Result restoreSyncStateResult = restoreSyncState();
      if (restoreSyncStateResult != null) {
        publishProgress(restoreSyncStateResult);
      }
      return Result.SUCCESS;
    } else {
      return new Result(false, R.string.restore_db_failure);
    }
  }

  @Nullable
  private Result restoreSyncState() {
    Result result = null;
    MyApplication application = MyApplication.getInstance();
    AccountManager accountManager = AccountManager.get(application);
    List<String> accounts = GenericAccountService.getAccountsAsStream(application)
        .map(account -> account.name)
        .collect(Collectors.toList());
    ContentResolver cr = application.getContentResolver();
    String[] projection = {KEY_ROWID, KEY_SYNC_ACCOUNT_NAME};
    Cursor cursor = cr.query(TransactionProvider.ACCOUNTS_URI, projection,
        KEY_SYNC_ACCOUNT_NAME + " IS NOT null", null, null);
    SharedPreferences sharedPreferences = application.getSettings();
    Editor editor = sharedPreferences.edit();
    if (cursor != null) {
      if (cursor.moveToFirst()) {
        int restored = 0, failed = 0;
        do {
          long accountId = cursor.getLong(0);
          String accountName = cursor.getString(1);
          String localKey = SyncAdapter.KEY_LAST_SYNCED_LOCAL(accountId);
          String remoteKey = SyncAdapter.KEY_LAST_SYNCED_REMOTE(accountId);
          if (accounts.indexOf(accountName) > -1) {
            android.accounts.Account account = GenericAccountService.GetAccount(accountName);
            accountManager.setUserData(account, localKey, sharedPreferences.getString(localKey, null));
            accountManager.setUserData(account, remoteKey, sharedPreferences.getString(remoteKey, null));
            restored++;
          } else {
            failed++;
          }
          editor.remove(localKey);
          editor.remove(remoteKey);
        } while (cursor.moveToNext());
        editor.apply();
        String message = "";
        if (restored > 0) {
          message += application.getString(R.string.sync_state_restored, restored);
        }
        if (failed > 0) {
          message += application.getString(R.string.sync_state_could_not_be_restored, failed);
        }
        result = new Result(true, message);
        Account.checkSyncAccounts(application);
      }
      cursor.close();
    }
    return result;
  }

  private void registerAsStale(boolean secure) {
    File dir = PictureDirHelper.getPictureDir(secure);
    if (dir == null) return;
    ContentValues values = new ContentValues();
    for (File file : dir.listFiles()) {
      Uri uri = secure ? FileProvider.getUriForFile(MyApplication.getInstance(),
          "org.totschnig.myexpenses.fileprovider", file) :
          Uri.fromFile(file);
      values.put(DatabaseConstants.KEY_PICTURE_URI, uri.toString());
      MyApplication.getInstance().getContentResolver().insert(
          TransactionProvider.STALE_IMAGES_URI, values);
    }
  }
}

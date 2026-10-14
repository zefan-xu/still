// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

/** Android 9 and older need write access to place files in Downloads. */
final class StoragePermission {
  private StoragePermission() {}

  /** Returns true when saving can proceed now; otherwise requests access and returns false. */
  static boolean ensure(Activity activity, int requestCode) {
    if (Build.VERSION.SDK_INT > 28
        || activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) return true;
    activity.requestPermissions(
        new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
    return false;
  }

  static boolean granted(int[] results) {
    return results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
  }
}

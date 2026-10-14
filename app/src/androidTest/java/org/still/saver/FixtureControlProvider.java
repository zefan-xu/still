// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Test-APK-only controls; document access remains protected by MANAGE_DOCUMENTS and URI grants. */
public final class FixtureControlProvider extends ContentProvider {
  static final Uri BASE = Uri.parse("content://org.still.saver.test.controls");

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Bundle call(String method, String argument, Bundle extras) {
    return FixtureDocumentsProvider.instance.call(method, argument, extras);
  }

  @Override
  public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getType(Uri uri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }
}

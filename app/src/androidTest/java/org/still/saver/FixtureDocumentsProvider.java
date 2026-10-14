// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only documents with real Android URI grants and controllable asynchronous source opening.
 */
public final class FixtureDocumentsProvider extends DocumentsProvider {
  static volatile FixtureDocumentsProvider instance;
  static final String AUTHORITY = "org.still.saver.test.documents";
  static final Uri BASE = Uri.parse("content://" + AUTHORITY);
  static final Uri TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
  private volatile String gatedId = "";
  private volatile CountDownLatch gate = new CountDownLatch(0);
  private final AtomicInteger opened = new AtomicInteger();
  private File directory;

  @Override
  public boolean onCreate() {
    instance = this;
    directory = new File(getContext().getFilesDir(), "documents-fixture");
    try {
      initialize();
      return true;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private void initialize() throws Exception {
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new java.io.IOException("Fixture directory unavailable");
    try (InputStream input = getContext().getAssets().open("sample.mp4");
        OutputStream output = new java.io.FileOutputStream(new File(directory, "video"))) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
    Bitmap image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
    image.eraseColor(Color.GREEN);
    try (OutputStream output = new java.io.FileOutputStream(new File(directory, "image"))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, output);
    } finally {
      image.recycle();
    }
    try (InputStream input = getContext().getAssets().open("sample.gif");
        OutputStream output = new java.io.FileOutputStream(new File(directory, "animation"))) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
    try (OutputStream output = new java.io.FileOutputStream(new File(directory, "unsupported"))) {
      output.write(new byte[] {1, 2, 3});
    }
  }

  @Override
  public Bundle call(String method, String argument, Bundle extras) {
    Bundle result = new Bundle();
    if ("release".equals(method)) gate.countDown();
    else if ("reset".equals(method)) {
      gate.countDown();
      gatedId = "";
      opened.set(0);
      try {
        initialize();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    } else if ("block".equals(method)) {
      gate.countDown();
      gatedId = argument;
      opened.set(0);
      gate = new CountDownLatch(1);
    } else if ("state".equals(method)) result.putInt("opened", opened.get());
    else if ("grant".equals(method))
      getContext()
          .grantUriPermission(
              "org.still.saver",
              TREE,
              Intent.FLAG_GRANT_READ_URI_PERMISSION
                  | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                  | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    else if ("revoke".equals(method))
      getContext().revokeUriPermission(TREE, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    else if ("remove".equals(method))
      result.putBoolean("removed", new File(directory, argument).delete());
    else return super.call(method, argument, extras);
    return result;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    MatrixCursor cursor =
        new MatrixCursor(
            projection == null
                ? new String[] {
                  DocumentsContract.Root.COLUMN_ROOT_ID,
                  DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                  DocumentsContract.Root.COLUMN_TITLE,
                  DocumentsContract.Root.COLUMN_FLAGS,
                  DocumentsContract.Root.COLUMN_MIME_TYPES
                }
                : projection);
    MatrixCursor.RowBuilder row = cursor.newRow();
    put(cursor, row, DocumentsContract.Root.COLUMN_ROOT_ID, "fixture");
    put(cursor, row, DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root");
    put(cursor, row, DocumentsContract.Root.COLUMN_TITLE, "Still synthetic statuses");
    put(
        cursor,
        row,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
    put(cursor, row, DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*\nvideo/*");
    return cursor;
  }

  @Override
  public Cursor queryDocument(String id, String[] projection) {
    MatrixCursor cursor = documents(projection);
    add(cursor, id);
    return cursor;
  }

  @Override
  public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
    MatrixCursor cursor = documents(projection);
    if ("root".equals(parent)) {
      add(cursor, "statuses");
      add(cursor, "other");
    } else if ("statuses".equals(parent)) {
      add(cursor, "image");
      add(cursor, "video");
      add(cursor, "unsupported");
    } else if ("other".equals(parent)) add(cursor, "outside");
    return cursor;
  }

  @Override
  public boolean isChildDocument(String parent, String child) {
    if ("root".equals(parent))
      return java.util.Arrays.asList(
              "statuses", "image", "video", "animation", "unsupported", "other", "outside")
          .contains(child);
    if ("statuses".equals(parent))
      return java.util.Arrays.asList("image", "video", "unsupported").contains(child);
    return "other".equals(parent) && "outside".equals(child);
  }

  @Override
  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation)
      throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Fixture is read-only");
    if (id.equals(gatedId)) {
      opened.incrementAndGet();
      try {
        if (!gate.await(20, TimeUnit.SECONDS))
          throw new FileNotFoundException("Fixture gate timed out");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new FileNotFoundException("Fixture opening interrupted");
      }
    }
    if (cancellation != null) cancellation.throwIfCanceled();
    return ParcelFileDescriptor.open(
        new File(directory, "outside".equals(id) ? "image" : id),
        ParcelFileDescriptor.MODE_READ_ONLY);
  }

  private MatrixCursor documents(String[] projection) {
    return new MatrixCursor(
        projection == null
            ? new String[] {
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_FLAGS,
              DocumentsContract.Document.COLUMN_SIZE,
              DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }
            : projection);
  }

  private void add(MatrixCursor cursor, String id) {
    boolean folder = "root".equals(id) || "statuses".equals(id) || "other".equals(id);
    String name =
        "root".equals(id)
            ? "Media"
            : "statuses".equals(id)
                ? ".Statuses"
                : "other".equals(id)
                    ? "Vacation"
                    : "image".equals(id)
                        ? "photo.png"
                        : "video".equals(id)
                            ? "clip.mp4"
                            : "outside".equals(id) ? "not-a-status.png" : "ignore.txt";
    String mime =
        folder
            ? DocumentsContract.Document.MIME_TYPE_DIR
            : "video".equals(id)
                ? "video/mp4"
                : "animation".equals(id)
                    ? "image/gif"
                    : "unsupported".equals(id) ? "text/plain" : "image/png";
    MatrixCursor.RowBuilder row = cursor.newRow();
    put(cursor, row, DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
    put(cursor, row, DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
    put(cursor, row, DocumentsContract.Document.COLUMN_MIME_TYPE, mime);
    put(cursor, row, DocumentsContract.Document.COLUMN_FLAGS, 0);
    put(
        cursor,
        row,
        DocumentsContract.Document.COLUMN_SIZE,
        folder ? 0 : new File(directory, "outside".equals(id) ? "image" : id).length());
    put(
        cursor,
        row,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        "video".equals(id) ? 2000L : 1000L);
  }

  private static void put(
      MatrixCursor cursor, MatrixCursor.RowBuilder row, String column, Object value) {
    if (cursor.getColumnIndex(column) >= 0) row.add(column, value);
  }
}

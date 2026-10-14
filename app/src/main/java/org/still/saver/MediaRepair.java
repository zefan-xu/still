// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.still.saver.core.Mp4Repair;
import org.still.saver.ui.Thumbnails;

/** Applies {@link Mp4Repair} to finished MP4 downloads before Android's extractor reads them. */
final class MediaRepair {
  private static final String TAG = "StillMediaRepair";

  private MediaRepair() {}

  /** A transfer's local file, before it is published. */
  static void repair(java.io.File file) throws IOException {
    try (RandomAccessFile access = new RandomAccessFile(file, "rw")) {
      int repaired =
          Mp4Repair.repair(
              new Mp4Repair.File() {
                @Override
                public long size() throws IOException {
                  return access.length();
                }

                @Override
                public void read(long position, byte[] buffer) throws IOException {
                  access.seek(position);
                  access.readFully(buffer);
                }

                @Override
                public void write(long position, byte[] bytes) throws IOException {
                  access.seek(position);
                  access.write(bytes);
                }
              });
      if (repaired > 0) Log.i(TAG, "Repaired " + repaired + " composition-offset box(es)");
    }
  }

  /** A DownloadManager file that has already been published. */
  static void repair(Context context, Uri uri) throws IOException {
    try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
      if (descriptor == null) throw new IOException("Could not open the saved video");
      FileDescriptor fd = descriptor.getFileDescriptor();
      int repaired =
          Mp4Repair.repair(
              new Mp4Repair.File() {
                @Override
                public long size() {
                  return descriptor.getStatSize();
                }

                @Override
                public void read(long position, byte[] buffer) throws IOException {
                  try {
                    for (int done = 0, count; done < buffer.length; done += count) {
                      count = Os.pread(fd, buffer, done, buffer.length - done, position + done);
                      if (count <= 0) throw new IOException("The saved video ended early");
                    }
                  } catch (ErrnoException e) {
                    throw new IOException("Could not read the saved video", e);
                  }
                }

                @Override
                public void write(long position, byte[] bytes) throws IOException {
                  try {
                    for (int done = 0; done < bytes.length; )
                      done += Os.pwrite(fd, bytes, done, bytes.length - done, position + done);
                  } catch (ErrnoException e) {
                    throw new IOException("Could not repair the saved video", e);
                  }
                }
              });
      if (repaired > 0) {
        Log.i(TAG, "Repaired " + repaired + " composition-offset box(es) in " + uri);
        // A tile may have tried, and failed, to read the file before the repair.
        Thumbnails.get(context).forget(uri);
      }
    }
  }
}

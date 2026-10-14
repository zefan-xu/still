// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.FacebookEmbedParser;
import org.still.saver.core.PublicPageResolver;

/** Opt-in checks that unavailable posts and posts without videos retain accurate public errors. */
@RunWith(AndroidJUnit4.class)
public final class LiveMetaAvailabilityTest {
  @Test
  public void facebookUnavailable() throws Exception {
    verify(
        "facebook-unavailable",
        "Facebook says",
        "https://www.facebook.com/radiokicksfm/videos/3676516585958356/");
  }

  @Test
  public void threadsWithoutVideo() throws Exception {
    verify(
        "threads-no-video",
        "no downloadable photo or video",
        "https://www.threads.com/@threads/post/DWjTI0cgH5O");
  }

  @Test
  public void threadsUnavailable() throws Exception {
    verify(
        "threads-unavailable", "Threads says", "https://www.threads.com/@mosseri/post/C8H1eEbRQ0X");
  }

  private void verify(String reportName, String expected, String... sources) throws Exception {
    Assume.assumeTrue(
        "Enable with livePlatforms=true",
        "true".equals(InstrumentationRegistry.getArguments().getString("livePlatforms")));
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    JSONArray results = new JSONArray();
    try {
      for (String source : sources) {
        JSONObject result = new JSONObject().put("source", source).put("expected", expected);
        results.put(result);
        try {
          new PublicPageResolver(context, "https://api.fxtwitter.com").resolve(source);
          fail("Expected a public availability error for " + source);
        } catch (IOException error) {
          result.put("message", error.getMessage());
          assertTrue(error.getMessage(), error.getMessage().contains(expected));
          if (reportName.startsWith("facebook"))
            assertTrue(error instanceof FacebookEmbedParser.UnavailableException);
          result.put("passed", true);
        }
      }
    } finally {
      try (FileOutputStream output =
          new FileOutputStream(new File(context.getFilesDir(), "live-" + reportName + ".json"))) {
        output.write(results.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    }
  }
}

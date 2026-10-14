// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The first-run tutorial opens once from the launcher, stays dismissed, and replays on request. */
@RunWith(AndroidJUnit4.class)
public final class OnboardingTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context app = instrumentation.getTargetContext();
  private final List<Activity> opened = new ArrayList<>();

  @Test
  public void showsOnFirstLaunchSkipDismissesForeverAndSettingsReplays() throws Exception {
    SharedPreferences prefs = DownloadStore.prefs(app);
    Map<String, ?> original = prefs.getAll();
    Instrumentation.ActivityMonitor tutorial =
        instrumentation.addMonitor(OnboardingActivity.class.getName(), null, false);
    Instrumentation.ActivityMonitor home =
        instrumentation.addMonitor(MainActivity.class.getName(), null, false);
    Instrumentation.ActivityMonitor settings =
        instrumentation.addMonitor(SettingsActivity.class.getName(), null, false);
    try {
      assertTrue(prefs.edit().clear().putString("downloads", "[]").commit());
      Intent launcher =
          Intent.makeMainActivity(new ComponentName(app, MainActivity.class))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

      app.startActivity(launcher);
      Activity first = await(tutorial, "The tutorial opens on a first launcher start");
      await(home, "Home opens beneath the tutorial");
      onView(withText("Share from any app")).check(matches(isDisplayed()));
      onView(withText("Skip")).perform(click());
      awaitFinished(first);
      assertTrue(prefs.getBoolean(OnboardingActivity.SEEN, false));

      app.startActivity(launcher);
      await(home, "Home opens again");
      instrumentation.waitForIdleSync();
      SystemClock.sleep(500);
      assertEquals("Skip dismisses the tutorial for good", 1, tutorial.getHits());

      app.startActivity(
          new Intent(app, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      await(settings, "Settings opens");
      onView(withText("Show tutorial")).perform(scrollTo(), click());
      Activity replay = await(tutorial, "Settings replays the tutorial");
      onView(withText("Next")).perform(click());
      onView(withText("Or paste a link here")).check(matches(isDisplayed()));
      onView(withText("Next")).perform(click());
      onView(withText("It all lands in your library")).check(matches(isDisplayed()));
      onView(withText("Start")).perform(click());
      awaitFinished(replay);
      assertEquals(2, tutorial.getHits());
    } finally {
      instrumentation.removeMonitor(tutorial);
      instrumentation.removeMonitor(home);
      instrumentation.removeMonitor(settings);
      instrumentation.runOnMainSync(
          () -> {
            for (Activity activity : opened) activity.finish();
          });
      instrumentation.waitForIdleSync();
      TestFixtures.restorePreferences(prefs, original);
    }
  }

  private Activity await(Instrumentation.ActivityMonitor monitor, String message) {
    Activity activity = monitor.waitForActivityWithTimeout(10_000);
    assertNotNull(message, activity);
    opened.add(activity);
    instrumentation.waitForIdleSync();
    return activity;
  }

  private static void awaitFinished(Activity activity) {
    long end = SystemClock.uptimeMillis() + 5_000;
    while (!activity.isFinishing() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
    assertTrue("The tutorial closes", activity.isFinishing());
  }
}

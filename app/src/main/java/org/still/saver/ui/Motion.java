// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;

/**
 * Restrained motion after anatomy's model: time-based steps clamped to 1/30 s, exponential
 * follow, a critically damped settle, springs and one-shot envelopes. Loops stop once settled or
 * when their view leaves the window, and everything jumps to its target when animations are off.
 */
public final class Motion {
  /** The age a {@link Clip} receives once its event is over. */
  public static final float END = Float.POSITIVE_INFINITY;

  private static final float MAX_DT = 1 / 30f, FIRST_DT = 1 / 60f;
  private static final int ATTACH_FRAMES = 60;

  private Motion() {}

  public interface Tick {
    /** Advances {@code dt} seconds; returns true while anything is still moving. */
    boolean step(float dt, boolean calm);
  }

  public interface Setter {
    void set(float value);
  }

  public interface Clip {
    /** Shows the event at {@code age} seconds; the last call is at {@link #END}. */
    void at(float age);
  }

  /** Position and velocity for {@link #settle} and {@link #spring}. */
  public static final class State {
    public float x, v;

    public State(float x) {
      this.x = x;
    }
  }

  /** True when the system has animations turned off. */
  public static boolean calm(Context context) {
    if (Build.VERSION.SDK_INT >= 26) return !ValueAnimator.areAnimatorsEnabled();
    return Settings.Global.getFloat(
            context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        == 0;
  }

  /** Exponential ease toward {@code target} with time constant {@code tau} seconds. */
  public static float follow(float x, float target, float dt, float tau, boolean calm) {
    if (calm) return target;
    float next = x + (target - x) * (float) (1 - Math.exp(-dt / tau));
    return Math.abs(target - next) < 1e-4f ? target : next;
  }

  /** Critically damped spring; returns true while still moving. */
  public static boolean settle(State s, float target, float dt, float seconds, boolean calm) {
    if (!calm) {
      float w = 1 / seconds, h = dt / 4;
      for (int i = 0; i < 4; i++) {
        s.v += (w * w * (target - s.x) - 2 * w * s.v) * h;
        s.x += s.v * h;
      }
      if (Math.abs(target - s.x) >= 1e-4f || Math.abs(s.v) >= 1e-4f) return true;
    }
    s.x = target;
    s.v = 0;
    return false;
  }

  /** Damped spring sub-stepped at about 240 Hz; returns true while still moving. */
  public static boolean spring(
      State s, float target, float dt, float stiffness, float damping, boolean calm) {
    if (!calm) {
      int steps = Math.max(1, (int) Math.ceil(dt * 240));
      float h = dt / steps;
      for (int i = 0; i < steps; i++) {
        s.v += (stiffness * (target - s.x) - damping * s.v) * h;
        s.x += s.v * h;
      }
      if (Math.abs(target - s.x) >= 5e-4f || Math.abs(s.v) >= 5e-4f) return true;
    }
    s.x = target;
    s.v = 0;
    return false;
  }

  /** Smoothstep from 0 at {@code a} to 1 at {@code b}. */
  public static float smooth(float a, float b, float x) {
    float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
    return t * t * (3 - 2 * t);
  }

  /** Attack over {@code rise} seconds, then exponential decay with time constant {@code fall}. */
  public static float burst(float age, float rise, float fall) {
    if (age < 0) return 0;
    float attack = smooth(0, rise, age);
    return age <= rise ? attack : attack * (float) Math.exp(-(age - rise) / fall);
  }

  /** Plays a one-shot event for {@code seconds}, then once more at {@link #END}. */
  public static Loop play(View owner, float seconds, Clip clip) {
    float[] age = {0};
    Loop loop =
        new Loop(
            owner,
            (dt, calm) -> {
              age[0] += dt;
              if (calm || age[0] >= seconds) {
                clip.at(END);
                return false;
              }
              clip.at(age[0]);
              return true;
            });
    if (!calm(owner.getContext())) clip.at(0);
    loop.start();
    return loop;
  }

  /** One frame loop per view; the view's attachment decides how long it may run. */
  public static final class Loop implements Choreographer.FrameCallback {
    private final View owner;
    private final Tick tick;
    private boolean running, attached;
    private long last;
    private int waited;

    public Loop(View owner, Tick tick) {
      this.owner = owner;
      this.tick = tick;
    }

    public boolean running() {
      return running;
    }

    public void start() {
      if (running) return;
      if (calm(owner.getContext())) {
        tick.step(0, true);
        return;
      }
      running = true;
      attached = false;
      last = 0;
      waited = 0;
      Choreographer.getInstance().postFrameCallback(this);
    }

    /** Stops where it is. */
    public void stop() {
      if (!running) return;
      running = false;
      Choreographer.getInstance().removeFrameCallback(this);
    }

    /** Stops and jumps to the targets. */
    public void finish() {
      stop();
      tick.step(0, true);
    }

    @Override
    public void doFrame(long now) {
      if (!running) return;
      if (!owner.isAttachedToWindow()) {
        if (attached || ++waited > ATTACH_FRAMES) {
          running = false;
          tick.step(0, true);
        } else Choreographer.getInstance().postFrameCallback(this);
        return;
      }
      attached = true;
      float dt = last == 0 ? FIRST_DT : Math.min((now - last) / 1e9f, MAX_DT);
      last = now;
      boolean moving = tick.step(dt, calm(owner.getContext()));
      if (!running) return;
      if (moving) Choreographer.getInstance().postFrameCallback(this);
      else running = false;
    }
  }

  /** A value that settles toward its target and writes itself through a setter every frame. */
  public static final class Value {
    private final State state;
    private final float seconds;
    private final Setter setter;
    private final Loop loop;
    private float target;

    public Value(View owner, float initial, float seconds, Setter setter) {
      state = new State(initial);
      target = initial;
      this.seconds = seconds;
      this.setter = setter;
      loop =
          new Loop(
              owner,
              (dt, calm) -> {
                boolean moving = settle(state, target, dt, this.seconds, calm);
                setter.set(state.x);
                return moving;
              });
      setter.set(initial);
    }

    public float get() {
      return state.x;
    }

    public float target() {
      return target;
    }

    public void to(float value) {
      target = value;
      if (state.x == value && state.v == 0) return;
      loop.start();
    }

    public void jump(float value) {
      loop.stop();
      target = value;
      state.x = value;
      state.v = 0;
      setter.set(value);
    }

    /** Freezes the value where it is. */
    public void stop() {
      loop.stop();
    }
  }
}

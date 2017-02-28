/*
 * Copyright (C) 2017 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.gm2.gravitybox;

import com.ceco.gm2.gravitybox.ledcontrol.QuietHours;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.XResources;
import android.media.AudioManager;
import android.view.Surface;
import android.view.WindowManager;
import android.os.Build;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModAudio {
    private static final String TAG = "GB:ModAudio";
    private static final String CLASS_REMOTE_PLAYBACK_STATE = "android.media.AudioService$RemotePlaybackState";
    private static final String CLASS_VOLUME_STREAM_STATE = "android.media.AudioService$VolumeStreamState";
    private static final String CLASS_AUDIO_SYSTEM = "android.media.AudioSystem";
    private static final String CLASS_AUDIO_SERVICE = "android.media.AudioService";
    private static final boolean DEBUG = false;

    private static boolean mSafeMediaVolumeEnabled;
    private static boolean mVolForceMusicControl;
    private static boolean mSwapVolumeKeys;
    private static HandleChangeVolume mHandleChangeVolume;
    private static XSharedPreferences mQhPrefs;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) log("Broadcast received: " + intent.toString());
            if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_SAFE_MEDIA_VOLUME_CHANGED)) {
                mSafeMediaVolumeEnabled = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_SAFE_MEDIA_VOLUME_ENABLED, false);
                if (DEBUG) log("Safe headset media volume set to: " + mSafeMediaVolumeEnabled);
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_VOL_FORCE_MUSIC_CONTROL_CHANGED)) {
                mVolForceMusicControl = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_VOL_FORCE_MUSIC_CONTROL, false);
                if (DEBUG) log("Force music volume control set to: " + mVolForceMusicControl);
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_VOL_SWAP_KEYS_CHANGED)) {
                mSwapVolumeKeys = intent.getBooleanExtra(GravityBoxSettings.EXTRA_VOL_SWAP_KEYS, false);
                if (DEBUG) log("Swap volume keys set to: " + mSwapVolumeKeys);
            }
        }
    };

    public static void initZygote(final XSharedPreferences prefs) {
        try {
            final Class<?> classAudioService = XposedHelpers.findClass(CLASS_AUDIO_SERVICE, null);

            mQhPrefs = new XSharedPreferences(GravityBox.PACKAGE_NAME, "quiet_hours");
            mQhPrefs.makeWorldReadable();

            mSwapVolumeKeys = prefs.getBoolean(GravityBoxSettings.PREF_KEY_VOL_SWAP_KEYS, false);

            if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS, false)
                    && Utils.shouldAllowMoreVolumeSteps()) {
                initMusicStream(prefs);
            }

            XposedBridge.hookAllConstructors(classAudioService, new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    if (context != null) {
                        IntentFilter intentFilter = new IntentFilter();
                        intentFilter.addAction(GravityBoxSettings.ACTION_PREF_SAFE_MEDIA_VOLUME_CHANGED);
                        intentFilter.addAction(GravityBoxSettings.ACTION_PREF_VOL_FORCE_MUSIC_CONTROL_CHANGED);
                        intentFilter.addAction(GravityBoxSettings.ACTION_PREF_VOL_SWAP_KEYS_CHANGED);
                        context.registerReceiver(mBroadcastReceiver, intentFilter);
                        if (DEBUG) log("AudioService constructed. Broadcast receiver registered");

                        mHandleChangeVolume = new HandleChangeVolume(context);
                        XposedHelpers.findAndHookMethod(classAudioService, "adjustStreamVolume", 
                                int.class, int.class, int.class, mHandleChangeVolume);
                    }
                    if (Build.VERSION.SDK_INT > 16 &&
                            prefs.getBoolean(GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS, false)) {
                        XposedHelpers.setIntField(param.thisObject, "mSafeMediaVolumeIndex", 150);
                        if (DEBUG) log("Default mSafeMediaVolumeIndex set to 150");
                    }
                }
            });

            if (Build.VERSION.SDK_INT > 16) {
                XResources.setSystemWideReplacement("android", "bool", "config_safe_media_volume_enabled", true);
                mSafeMediaVolumeEnabled = prefs.getBoolean(GravityBoxSettings.PREF_KEY_SAFE_MEDIA_VOLUME, false);
                if (DEBUG) log("Safe headset media volume set to: " + mSafeMediaVolumeEnabled);
                XposedHelpers.findAndHookMethod(classAudioService, "enforceSafeMediaVolume", new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!mSafeMediaVolumeEnabled) {
                            param.setResult(null);
                            return;
                        }
                    }
                });

                XposedHelpers.findAndHookMethod(classAudioService, "checkSafeMediaVolume", 
                        int.class, int.class, int.class, new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!mSafeMediaVolumeEnabled) {
                            param.setResult(true);
                            return;
                        }
                    }
                });

                if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS, false)) {
                    XposedHelpers.findAndHookMethod(classAudioService, "onConfigureSafeVolume",
                            boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setObjectExtra("gbCurSafeMediaVolIndex",
                                    XposedHelpers.getIntField(param.thisObject, "mSafeMediaVolumeIndex"));
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if ((Integer) param.getObjectExtra("gbCurSafeMediaVolIndex") !=
                                    XposedHelpers.getIntField(param.thisObject, "mSafeMediaVolumeIndex")) {
                                int safeMediaVolIndex = XposedHelpers.getIntField(param.thisObject, "mSafeMediaVolumeIndex") * 2;
                                XposedHelpers.setIntField(param.thisObject, "mSafeMediaVolumeIndex", safeMediaVolIndex);
                                if (DEBUG) log("onConfigureSafeVolume: mSafeMediaVolumeIndex set to " + safeMediaVolIndex);
                            }
                        }
                    });
                }
            }

            mVolForceMusicControl = prefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_VOL_FORCE_MUSIC_CONTROL, false);
            XposedHelpers.findAndHookMethod(classAudioService, "getActiveStreamType",
                    int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mVolForceMusicControl) {
                        int activeStreamType = (int) param.getResult();
                        if (activeStreamType == AudioManager.STREAM_RING ||
                                activeStreamType == AudioManager.STREAM_NOTIFICATION) {
                            param.setResult(AudioManager.STREAM_MUSIC);
                            if (DEBUG) log("getActiveStreamType: Forcing STREAM_MUSIC");
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(AudioManager.class, "querySoundEffectsEnabled", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    mQhPrefs.reload();
                    QuietHours qh = new QuietHours(mQhPrefs);
                    if (qh.isSystemSoundMuted(QuietHours.SystemSound.TOUCH)) {
                        param.setResult(false);
                    }
                } 
            });
        } catch(Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void initMusicStream(final XSharedPreferences prefs) {
        try {
            final Class<?> classRemotePlaybackState = XposedHelpers.findClass(
                    CLASS_REMOTE_PLAYBACK_STATE, null);
            final Class<?> classVolumeStreamState = XposedHelpers.findClass(
                    CLASS_VOLUME_STREAM_STATE, null);
            final Class<?> classAudioSystem = XposedHelpers.findClass(CLASS_AUDIO_SYSTEM, null);
            final int volSteps = prefs.getInt(GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS_VALUE, 30);

            XposedBridge.hookAllConstructors(classRemotePlaybackState, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[1] = volSteps;
                    param.args[2] = volSteps;
                    if (DEBUG) log("RemotePlaybackState constructed. Music stream volume steps set to " + volSteps);
                }
            });

            XposedBridge.hookAllConstructors(classVolumeStreamState, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int streamType = XposedHelpers.getIntField(param.thisObject, "mStreamType");
                    if (streamType == AudioManager.STREAM_MUSIC) {
                        XposedHelpers.setIntField(param.thisObject, "mIndexMax", (volSteps*10));
                        XposedHelpers.callStaticMethod(
                                classAudioSystem, "initStreamVolume", AudioManager.STREAM_MUSIC, 0, volSteps);
                        XposedHelpers.callMethod(param.thisObject, "readSettings");
                        if (DEBUG) log("Volume for music stream initialized with steps set to " + volSteps);
                    }
                }
            });

        } catch(Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static class HandleChangeVolume extends XC_MethodHook {
        private WindowManager mWm;

        public HandleChangeVolume(Context context) {
            mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (mSwapVolumeKeys) {
                try {
                    if ((Integer) param.args[1] != 0) {
                        if (DEBUG) log("Original direction = " + param.args[1]);
                        int orientation = getDirectionFromOrientation();
                        param.args[1] = orientation * (Integer) param.args[1];
                        if (DEBUG) log("Modified direction = " + param.args[1]);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }

        private int getDirectionFromOrientation() {
            int rotation = mWm.getDefaultDisplay().getRotation();
            switch (rotation) {
                case Surface.ROTATION_0:
                    if (DEBUG) log("Rotation = 0");
                    return 1;
                case Surface.ROTATION_90:
                    if (DEBUG) log("Rotation = 90");
                    return -1;
                case Surface.ROTATION_180:
                    if (DEBUG) log("Rotation = 180");
                    return -1;
                case Surface.ROTATION_270:
                default:
                    if (DEBUG) log("Rotation = 270");
                    return 1;
            }
        }
    }
}

package org.telegram.messenger.utils;

import android.content.SharedPreferences;
import android.net.Uri;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.Locale;

public class ModConfig {
    private static final ModConfig allowReadPacketInstance = new ModConfig();
    public static SharedPreferences.Editor editor = null;
    public static SharedPreferences preferences = null;
    private static volatile Uri selectedAudioFileUri = null;
    private static long selectedAudioGeneration;
    private static long selectionActivityGeneration = -1;
    private static boolean selectedAudioPlaybackRequested;
    private static boolean selectedAudioPlaying;
    private final Object sync = new Object();
    private boolean val = false;
    private int resetAfter = -1;

    static {
        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("modconfig", 0);
        preferences = sharedPreferences;
        editor = sharedPreferences.edit();
        setDefaultValuesIfNeeded();
    }

    public static void setAllowReadPacket(boolean z, int i) {
        ModConfig modConfig = allowReadPacketInstance;
        synchronized (modConfig.sync) {
            modConfig.val = z;
            modConfig.resetAfter = i;
        }
    }

    public static boolean getAllowReadPacket() {
        boolean zProcess;
        ModConfig modConfig = allowReadPacketInstance;
        synchronized (modConfig.sync) {
            zProcess = modConfig.process();
        }
        return zProcess;
    }

    public static void setreadPrivatePackets(boolean z) {
        editor.putBoolean("readPrivatePackets", z);
        editor.apply();
    }

    public static boolean isreadPrivatePackets() {
        return preferences.getBoolean("readPrivatePackets", false);
    }

    public static void setreadChannelPackets(boolean z) {
        editor.putBoolean("readChannelPackets", z);
        editor.apply();
    }

    public static boolean isreadChannelPackets() {
        return preferences.getBoolean("readChannelPackets", false);
    }

    public static void setmarkReadAfterSend(boolean z) {
        editor.putBoolean("markReadAfterSend", z);
        editor.apply();
    }

    public static boolean ismarkReadAfterSend() {
        return preferences.getBoolean("markReadAfterSend", true);
    }

    public static void setsendOnlineStatus(boolean z) {
        editor.putBoolean("sendOnlineStatus", z);
        editor.apply();
    }

    public static boolean issendOnlineStatus() {
        return preferences.getBoolean("sendOnlineStatus", false);
    }

    public static void setsendTypingAction(boolean z) {
        editor.putBoolean("sendTypingAction", z);
        editor.apply();
    }

    public static boolean issendTypingAction() {
        return preferences.getBoolean("sendTypingAction", false);
    }

    public static void setreadPrivateContent(boolean z) {
        editor.putBoolean("readPrivateContent", z);
        editor.apply();
    }

    public static boolean isreadPrivateContent() {
        return preferences.getBoolean("readPrivateContent", false);
    }

    public static void setreadChannelContent(boolean z) {
        editor.putBoolean("readChannelContent", z);
        editor.apply();
    }

    public static boolean isreadChannelContent() {
        return preferences.getBoolean("readChannelContent", false);
    }

    public static void setreadStories(boolean z) {
        editor.putBoolean("readStories", z);
        editor.apply();
    }

    public static boolean isreadStories() {
        return preferences.getBoolean("readStories", false);
    }

    public static void setMicBlink(boolean z) {
        editor.putBoolean("micBlink", z);
        editor.apply();
    }

    public static boolean isMicBlink() {
        return preferences.getBoolean("micBlink", true);
    }

    public static void setUseDeviceAudio(boolean z) {
        editor.putBoolean("isUseDeviceAudio", z);
        editor.apply();
        if (!z) {
            stopSelectedAudioPlayback();
        }
    }

    public static boolean isUseDeviceAudio() {
        return preferences.getBoolean("isUseDeviceAudio", false);
    }

    public static synchronized long beginSelectedAudioSelection() {
        if (!isUseDeviceAudio() || selectedAudioPlaybackRequested || selectionActivityGeneration == selectedAudioGeneration) {
            return -1;
        }
        selectedAudioGeneration++;
        selectedAudioFileUri = null;
        selectedAudioPlaybackRequested = true;
        selectedAudioPlaying = false;
        selectionActivityGeneration = selectedAudioGeneration;
        return selectedAudioGeneration;
    }

    public static synchronized boolean completeSelectedAudioSelection(long generation, Uri uri) {
        if (generation != selectedAudioGeneration || !selectedAudioPlaybackRequested || !isUseDeviceAudio()) {
            return false;
        }
        selectedAudioFileUri = uri;
        if (uri == null) {
            selectedAudioPlaybackRequested = false;
        }
        return uri != null;
    }

    public static synchronized void closeSelectedAudioSelection(long generation) {
        if (selectionActivityGeneration == generation) {
            selectionActivityGeneration = -1;
        }
    }

    public static synchronized boolean isSelectionActivityOpen() {
        return selectionActivityGeneration == selectedAudioGeneration;
    }

    public static synchronized boolean isSelectedAudioActive() {
        return selectedAudioPlaybackRequested;
    }

    public static synchronized boolean isSelectedAudioPlaying() {
        return selectedAudioPlaying;
    }

    public static synchronized long getSelectedAudioGeneration() {
        return selectedAudioPlaybackRequested ? selectedAudioGeneration : -1;
    }

    public static synchronized Uri getSelectedAudioFileUri(long generation) {
        return generation == selectedAudioGeneration && selectedAudioPlaybackRequested ? selectedAudioFileUri : null;
    }

    public static synchronized boolean setSelectedAudioPlaying(long generation, boolean playing) {
        if (generation != selectedAudioGeneration || !selectedAudioPlaybackRequested) {
            return false;
        }
        selectedAudioPlaying = playing;
        return true;
    }

    public static synchronized boolean isSelectedAudioPlaybackRequested(long generation) {
        return generation == selectedAudioGeneration && selectedAudioPlaybackRequested && isUseDeviceAudio();
    }

    public static synchronized void finishSelectedAudioPlayback(long generation) {
        if (generation == selectedAudioGeneration) {
            selectedAudioFileUri = null;
            selectedAudioPlaybackRequested = false;
            selectedAudioPlaying = false;
            selectionActivityGeneration = -1;
        }
    }

    public static synchronized void stopSelectedAudioPlayback() {
        selectedAudioGeneration++;
        selectedAudioFileUri = null;
        selectedAudioPlaybackRequested = false;
        selectedAudioPlaying = false;
        selectionActivityGeneration = -1;
    }

    public static final int VOICE_EFFECT_NORMAL = 0;
    public static final int VOICE_EFFECT_CHILD = 1;
    public static final int VOICE_EFFECT_ADULT = 2;
    public static final int VOICE_EFFECT_ROBOT = 3;
    public static final int VOICE_EFFECT_ECHO = 4;
    public static final int VOICE_EFFECT_FEMALE = 5;
    public static final int VOICE_EFFECT_COUNT = 6;

    public static void setCurrentVoiceEffect(int effect) {
        if (effect < VOICE_EFFECT_NORMAL || effect >= VOICE_EFFECT_COUNT) {
            effect = VOICE_EFFECT_NORMAL;
        }
        editor.putInt("currentVoiceEffect", effect);
        editor.apply();
    }

    public static int getCurrentVoiceEffect() {
        int effect = preferences.getInt("currentVoiceEffect", VOICE_EFFECT_NORMAL);
        return effect >= VOICE_EFFECT_NORMAL && effect < VOICE_EFFECT_COUNT
                ? effect : VOICE_EFFECT_NORMAL;
    }

    public static String getCurrentVoiceName() {
        int currentVoiceEffect = getCurrentVoiceEffect();
        if (currentVoiceEffect == VOICE_EFFECT_CHILD) {
            return "Child";
        }
        if (currentVoiceEffect == VOICE_EFFECT_ADULT) {
            return "Adult";
        }
        if (currentVoiceEffect == VOICE_EFFECT_ROBOT) {
            return "Robot";
        }
        if (currentVoiceEffect == VOICE_EFFECT_ECHO) {
            return "Echo";
        }
        if (currentVoiceEffect == VOICE_EFFECT_FEMALE) {
            return "Female";
        }
        return "Normal";
    }

    private static void setDefaultValuesIfNeeded() {
        if (preferences.getBoolean("isFirstRun", true)) {
            editor.putInt("currentVoiceEffect", 0);
            editor.putBoolean("isUseDeviceAudio", false);
            editor.putBoolean("readPrivatePackets", false);
            editor.putBoolean("readChannelPackets", true);
            editor.putBoolean("markReadAfterSend", true);
            editor.putBoolean("sendOnlineStatus", false);
            editor.putBoolean("sendTypingAction", false);
            editor.putBoolean("readStories", false);
            editor.putBoolean("micBlink", true);
            editor.putBoolean("readPrivateContent", false);
            editor.putBoolean("readChannelContent", true);
            editor.putBoolean("isFirstRun", false);
            editor.apply();
        }
    }

    private boolean process() {
        int i = this.resetAfter;
        if (i == -1) {
            return this.val;
        }
        int i2 = i - 1;
        this.resetAfter = i2;
        boolean z = this.val;
        if (i2 == 0) {
            this.val = false;
        }
        return z;
    }

    public static String getDC(TLRPC.User user) {
        return getDC(UserConfig.selectedAccount, user, null);
    }

    public static String getDC(TLRPC.Chat chat) {
        return getDC(UserConfig.selectedAccount, null, chat);
    }

    public static String getDC(TLRPC.User user, TLRPC.Chat chat) {
        return getDC(UserConfig.selectedAccount, user, chat);
    }

    public static String getDC(int account, TLRPC.User user, TLRPC.Chat chat) {
        int currentDatacenterId = getConnectionsManager(account).getCurrentDatacenterId();
        if (user != null) {
            if (!UserObject.isUserSelf(user) || currentDatacenterId == -1) {
                TLRPC.UserProfilePhoto userProfilePhoto = user.photo;
                if (userProfilePhoto != null) {
                    currentDatacenterId = userProfilePhoto.dc_id;
                } else {
                    currentDatacenterId = -1;
                }
            }
        } else if (chat != null) {
            TLRPC.ChatPhoto chatPhoto = chat.photo;
            if (chatPhoto != null) {
                currentDatacenterId = chatPhoto.dc_id;
            } else {
                currentDatacenterId = -1;
            }
        } else {
            currentDatacenterId = 0;
        }
        return (currentDatacenterId == -1 || currentDatacenterId == 0) ? getDCName(0) : String.format(Locale.ROOT, "DC%d, %s", Integer.valueOf(currentDatacenterId), getDCName(currentDatacenterId));
    }

    public static String getDCName(int i) {
        if (i == 1) {
            return "Miami FL, USA";
        }
        if (i == 2) {
            return "Amsterdam, NL";
        }
        if (i == 3) {
            return "Miami FL, USA";
        }
        if (i == 4) {
            return "Amsterdam, NL";
        }
        if (i == 5) {
            return "Singapore";
        }
        if (i == 6) {
            return "Singapore";
        }
        if (i == 7) {
            return "Europe";
        }
        return "Unknown";
    }

    private static ConnectionsManager getConnectionsManager(int account) {
        return ConnectionsManager.getInstance(account);
    }
}

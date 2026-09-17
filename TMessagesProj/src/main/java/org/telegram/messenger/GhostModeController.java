package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.utils.ModConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_ephemeral;
import org.telegram.tgnet.tl.TL_stories;

import java.util.IdentityHashMap;

public final class GhostModeController {

    private static final String KEY_MIGRATED = "migrated_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEEN_ON_REPLY = "seen_on_reply";
    private static final long FOLLOW_UP_DELAY_MS = 1000;

    private static final GhostModeController[] instances = new GhostModeController[UserConfig.MAX_ACCOUNT_COUNT];

    public static GhostModeController getInstance(int account) {
        GhostModeController instance = instances[account];
        if (instance == null) {
            synchronized (instances) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new GhostModeController(account);
                }
            }
        }
        return instance;
    }

    private final int currentAccount;
    private final SharedPreferences preferences;
    private volatile boolean enabled;
    private volatile boolean seenOnReply;

    // Accessed only on Utilities.stageQueue. These are the exact read RPCs released by a successful reply.
    private final IdentityHashMap<TLObject, Boolean> replyReadRequests = new IdentityHashMap<>();

    // Accessed only on Utilities.stageQueue.
    private long generation;
    private long sessionUserId;
    private boolean offlinePending;
    private boolean offlineRequestInFlight;
    private int offlineRequestToken;
    private Runnable followUpRunnable;

    private GhostModeController(int account) {
        currentAccount = account;
        String name = account == 0 ? "ghostmode" : "ghostmode" + account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_MIGRATED, false)) {
            boolean legacyHideOnline = !ModConfig.issendOnlineStatus();
            boolean legacyHideTyping = !ModConfig.issendTypingAction();
            enabled = legacyHideOnline || legacyHideTyping;
            preferences.edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .putBoolean(KEY_MIGRATED, true)
                    .apply();
        } else {
            enabled = preferences.getBoolean(KEY_ENABLED, false);
        }
        seenOnReply = preferences.getBoolean(KEY_SEEN_ON_REPLY, true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldHideOnlineStatus() {
        return enabled;
    }

    public boolean shouldHideTyping() {
        return enabled;
    }

    public boolean shouldHideReadReceipts() {
        return enabled;
    }

    public boolean isSeenOnReplyEnabled() {
        return seenOnReply;
    }

    public void setSeenOnReplyEnabled(boolean value) {
        seenOnReply = value;
        preferences.edit().putBoolean(KEY_SEEN_ON_REPLY, value).apply();
    }

    public boolean shouldHideStoryViews() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        preferences.edit().putBoolean(KEY_ENABLED, value).apply();
        Utilities.stageQueue.postRunnable(() -> applyEnabledState(value));
    }

    public void applyOnlineStatusPolicy(TLObject object) {
        if (enabled && object instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) object).offline = true;
        }
    }

    public boolean shouldSuppressTyping(TLObject object) {
        return enabled && (object instanceof TLRPC.TL_messages_setTyping
                || object instanceof TLRPC.TL_messages_setEncryptedTyping);
    }

    public boolean shouldSuppressReadReceipt(TLObject object) {
        if (replyReadRequests.remove(object) != null) {
            return false;
        }
        return enabled && (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_messages_readEncryptedHistory
                || object instanceof TLRPC.TL_messages_readDiscussion
                || object instanceof TLRPC.TL_messages_readMessageContents
                || object instanceof TLRPC.TL_messages_readSavedHistory
                || object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_channels_readMessageContents);
    }

    public TLRPC.InputPeer getReplyPeer(TLObject object) {
        if (!enabled || !seenOnReply) {
            return null;
        }
        if (object instanceof TLRPC.TL_messages_sendMessage) {
            return ((TLRPC.TL_messages_sendMessage) object).peer;
        }
        if (object instanceof TLRPC.TL_messages_sendMedia) {
            return ((TLRPC.TL_messages_sendMedia) object).peer;
        }
        if (object instanceof TLRPC.TL_messages_sendMultiMedia) {
            return ((TLRPC.TL_messages_sendMultiMedia) object).peer;
        }
        return null;
    }

    public void onReplyMessageCompleted(TLRPC.InputPeer peer, TLRPC.TL_error error) {
        if (!enabled || !seenOnReply || error != null || peer == null) {
            return;
        }
        final long dialogId = getDialogId(peer);
        if (dialogId == 0) {
            return;
        }
        MessagesStorage.getInstance(currentAccount).getDialogMaxMessageId(dialogId, maxId ->
                Utilities.stageQueue.postRunnable(() -> releaseReadReceiptAfterReply(peer, maxId)));
    }

    private void releaseReadReceiptAfterReply(TLRPC.InputPeer peer, int maxId) {
        if (!enabled || !seenOnReply || maxId <= 0) {
            return;
        }
        TLRPC.TL_messages_readHistory request = new TLRPC.TL_messages_readHistory();
        request.peer = peer;
        request.max_id = maxId;
        replyReadRequests.put(request, Boolean.TRUE);
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> { });
    }

    private long getDialogId(TLRPC.InputPeer peer) {
        if (peer.user_id != 0) {
            return peer.user_id;
        }
        if (peer.chat_id != 0) {
            return -peer.chat_id;
        }
        if (peer.channel_id != 0) {
            return -peer.channel_id;
        }
        return 0;
    }

    public boolean shouldSuppressStoryView(TLObject object) {
        return enabled && (object instanceof TL_stories.TL_stories_readStories
                || object instanceof TL_stories.TL_stories_incrementStoryViews);
    }

    public void completeSuppressedRequest(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        Utilities.stageQueue.postRunnable(() -> {
            TLObject response = createSuppressedResponse(object);
            try {
                if (onComplete != null) {
                    onComplete.run(response, null);
                } else if (onCompleteTimestamp != null) {
                    onCompleteTimestamp.run(response, null, ConnectionsManager.getInstance(currentAccount).getCurrentTimeMillis());
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (response != null) {
                    response.freeResources();
                }
                object.freeResources();
            }
        });
    }

    private TLObject createSuppressedResponse(TLObject object) {
        if (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_messages_readMessageContents) {
            TLRPC.TL_messages_affectedMessages response = new TLRPC.TL_messages_affectedMessages();
            response.pts = -1;
            response.pts_count = 0;
            return response;
        }
        if (object instanceof TL_stories.TL_stories_readStories) {
            return null;
        }
        return new TLRPC.TL_boolTrue();
    }

    public boolean isOnlineProducingActivity(TLObject object) {
        return object instanceof TLRPC.TL_messages_sendMessage
                || object instanceof TLRPC.TL_messages_sendMedia
                || object instanceof TLRPC.TL_messages_sendMultiMedia
                || object instanceof TLRPC.TL_messages_forwardMessages
                || object instanceof TLRPC.TL_messages_forwardMessage
                || object instanceof TLRPC.TL_messages_sendInlineBotResult
                || object instanceof TLRPC.TL_messages_sendScheduledMessages
                || object instanceof TLRPC.TL_messages_sendQuickReplyMessages
                || object instanceof TLRPC.TL_messages_sendReaction
                || object instanceof TLRPC.TL_messages_sendPaidReaction
                || object instanceof TLRPC.TL_messages_sendVote
                || object instanceof TLRPC.TL_messages_sendWebViewData
                || object instanceof TLRPC.TL_messages_sendWebViewResultMessage
                || object instanceof TLRPC.TL_messages_sendBotRequestedPeer
                || object instanceof TLRPC.TL_messages_sendEncrypted
                || object instanceof TLRPC.TL_messages_sendEncryptedFile
                || object instanceof TLRPC.TL_messages_sendEncryptedMultiMedia
                || object instanceof TLRPC.TL_messages_sendEncryptedService
                || object instanceof TL_ephemeral.TL_sendMessage
                || object instanceof TLRPC.TL_messages_startBot;
    }

    public void onOnlineProducingActivityCompleted(TLRPC.TL_error error) {
        if (enabled && error == null) {
            requestOfflineCorrection(true);
        }
    }

    public void onSelfOnlineUpdate() {
        if (enabled) {
            requestOfflineCorrection(true);
        }
    }

    public void onForeground() {
        if (enabled) {
            requestOfflineCorrection(true);
        }
    }

    public void onConnected() {
        if (enabled) {
            requestOfflineCorrection(true);
        }
    }

    public void onCleanup() {
        Utilities.stageQueue.postRunnable(this::resetRuntimeState);
    }

    private void requestOfflineCorrection(boolean followUp) {
        Utilities.stageQueue.postRunnable(() -> requestOfflineCorrectionInternal(followUp));
    }

    private void requestOfflineCorrectionInternal(boolean followUp) {
        if (!ensureActiveSession()) {
            return;
        }
        offlinePending = true;
        sendOfflineIfPossible();
        if (followUp && followUpRunnable == null) {
            final long expectedGeneration = generation;
            final long expectedUserId = sessionUserId;
            followUpRunnable = () -> {
                followUpRunnable = null;
                if (!enabled || generation != expectedGeneration || sessionUserId != expectedUserId || !ensureActiveSession()) {
                    return;
                }
                offlinePending = true;
                sendOfflineIfPossible();
            };
            Utilities.stageQueue.postRunnable(followUpRunnable, FOLLOW_UP_DELAY_MS);
        }
    }

    private void sendOfflineIfPossible() {
        if (!offlinePending || offlineRequestInFlight || !ensureActiveSession()) {
            return;
        }
        offlinePending = false;
        offlineRequestInFlight = true;
        final long expectedGeneration = generation;
        final long expectedUserId = sessionUserId;
        TL_account.updateStatus request = new TL_account.updateStatus();
        request.offline = true;
        offlineRequestToken = ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            if (generation != expectedGeneration || sessionUserId != expectedUserId) {
                return;
            }
            offlineRequestInFlight = false;
            offlineRequestToken = 0;
            if (enabled && offlinePending) {
                sendOfflineIfPossible();
            }
        });
    }

    private void applyEnabledState(boolean expectedEnabled) {
        if (enabled != expectedEnabled) {
            return;
        }
        resetRuntimeState();
        sessionUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (enabled) {
            requestOfflineCorrectionInternal(true);
        } else if (sessionUserId != 0
                && ConnectionsManager.getInstance(currentAccount).getPauseTime() == 0
                && ApplicationLoader.isScreenOn
                && !ApplicationLoader.mainInterfacePausedStageQueue) {
            TL_account.updateStatus request = new TL_account.updateStatus();
            request.offline = false;
            ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> { });
        }
    }

    private boolean ensureActiveSession() {
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (sessionUserId != userId) {
            resetRuntimeState();
            sessionUserId = userId;
        }
        return enabled && userId != 0;
    }

    private void resetRuntimeState() {
        generation++;
        offlinePending = false;
        if (followUpRunnable != null) {
            Utilities.stageQueue.cancelRunnable(followUpRunnable);
            followUpRunnable = null;
        }
        if (offlineRequestToken != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(offlineRequestToken, true);
            offlineRequestToken = 0;
        }
        offlineRequestInFlight = false;
    }
}

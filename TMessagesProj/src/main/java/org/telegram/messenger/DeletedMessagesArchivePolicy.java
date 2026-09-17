package org.telegram.messenger;

/**
 * Pure compatibility policy for Elitegram's AyuGram-derived deleted-message archive.
 *
 * AyuGram's public save path archives received cloud messages without treating the
 * per-message {@code noforwards} bit as an archive veto. On the current Telegram
 * base that bit is not, by itself, reliable peer-protection state. We therefore
 * require current positive peer metadata for the protected-content exclusion.
 */
public final class DeletedMessagesArchivePolicy {
    private static final int SEND_STATE_SENT = 0;
    private DeletedMessagesArchivePolicy() {
    }

    public static final class HistoryWindow {
        public final int minimumId;
        public final int exclusiveMaximumId;

        private HistoryWindow(int minimumId, int exclusiveMaximumId) {
            this.minimumId = minimumId;
            this.exclusiveMaximumId = exclusiveMaximumId;
        }

        public boolean contains(int messageId) {
            return messageId > 0
                    && (minimumId == 0 || messageId >= minimumId)
                    && (exclusiveMaximumId == 0 || messageId < exclusiveMaximumId);
        }
    }

    /** AyuGram-style page bounds: newest page remains open above; older pages stop at their anchor. */
    public static HistoryWindow historyWindow(int minimumLoadedId, int maxId) {
        return new HistoryWindow(Math.max(0, minimumLoadedId), Math.max(0, maxId));
    }

    /** Returns protection only from a currently loaded peer object with a positive flag. */
    public static boolean hasPositivePeerProtection(
            long dialogId,
            boolean chatLoaded,
            boolean chatNoForwards,
            boolean userFullLoaded,
            boolean userPeerNoForwards,
            boolean userMyNoForwards) {
        if (dialogId < 0) {
            return chatLoaded && chatNoForwards;
        }
        return dialogId > 0 && userFullLoaded && (userPeerNoForwards || userMyNoForwards);
    }

    public static boolean isEligible(
            int messageId,
            int sendState,
            long ownerUserId,
            long dialogId,
            boolean encryptedDialog,
            boolean secretMessage,
            boolean serviceMessage,
            boolean hasAction,
            boolean hasPeer,
            int ttl,
            int ttlPeriod,
            int mediaTtl,
            boolean ephemeral,
            boolean protectedPeer) {
        return rejectionReason(messageId, sendState, ownerUserId, dialogId, encryptedDialog,
                secretMessage, serviceMessage, hasAction, hasPeer, ttl, ttlPeriod,
                mediaTtl, ephemeral, protectedPeer) == null;
    }

    public static String rejectionReason(
            int messageId,
            int sendState,
            long ownerUserId,
            long dialogId,
            boolean encryptedDialog,
            boolean secretMessage,
            boolean serviceMessage,
            boolean hasAction,
            boolean hasPeer,
            int ttl,
            int ttlPeriod,
            int mediaTtl,
            boolean ephemeral,
            boolean protectedPeer) {
        if (ownerUserId == 0) return "no_owner";
        if (messageId <= 0) return "local_or_unsent_id";
        if (sendState != SEND_STATE_SENT) return "not_sent_state";
        if (dialogId == 0) return "unresolved_dialog";
        if (dialogId == ownerUserId) return "self_dialog";
        if (encryptedDialog) return "encrypted_dialog";
        if (secretMessage) return "secret_message";
        if (serviceMessage || hasAction) return "service_message";
        if (!hasPeer) return "missing_peer";
        if (protectedPeer) return "protected_content";
        if (ttl != 0 || ttlPeriod != 0 || mediaTtl != 0) return "ttl_or_view_once";
        if (ephemeral) return "ephemeral";
        return null;
    }
}

package org.telegram.messenger;

public final class DeletedMessagesArchivePolicyTest {
    private static boolean eligible(
            int id, int sendState, long owner, long dialog, boolean encrypted, boolean secret,
            boolean service, boolean action, boolean peer, int ttl,
            int ttlPeriod, int mediaTtl, boolean ephemeral, boolean protectedPeer) {
        return DeletedMessagesArchivePolicy.isEligible(id, sendState, owner, dialog, encrypted, secret,
                service, action, peer, ttl, ttlPeriod, mediaTtl, ephemeral, protectedPeer);
    }

    private static void expect(String name, boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError(name + ": expected " + expected + " but was " + actual);
        }
    }

    private static boolean peerProtected(
            long dialogId, boolean chatLoaded, boolean chatNoForwards,
            boolean userFullLoaded, boolean userPeerNoForwards, boolean userMyNoForwards) {
        return DeletedMessagesArchivePolicy.hasPositivePeerProtection(
                dialogId, chatLoaded, chatNoForwards, userFullLoaded,
                userPeerNoForwards, userMyNoForwards);
    }

    public static void main(String[] args) {
        Object[][] ordinaryKinds = {
                {"text"}, {"entities"}, {"photo"}, {"video"}, {"document"}, {"voice"},
                {"audio"}, {"sticker"}, {"gif"}, {"poll"}, {"grouped"}, {"reply"},
                {"reactions"}, {"channel"}, {"topic"}
        };
        for (Object[] kind : ordinaryKinds) {
            expect((String) kind[0], true, eligible(10, 0, 100, -200, false, false,
                    false, false, true, 0, 0, 0, false, false));
        }
        expect("not delivered", false, eligible(10, 1, 100, -200, false, false, false, false, true, 0, 0, 0, false, false));
        expect("secret dialog", false, eligible(10, 0, 100, -200, true, false, false, false, true, 0, 0, 0, false, false));
        expect("secret message", false, eligible(10, 0, 100, -200, false, true, false, false, true, 0, 0, 0, false, false));
        expect("service", false, eligible(10, 0, 100, -200, false, false, true, false, true, 0, 0, 0, false, false));
        expect("action", false, eligible(10, 0, 100, -200, false, false, false, true, true, 0, 0, 0, false, false));
        expect("message ttl", false, eligible(10, 0, 100, -200, false, false, false, false, true, 5, 0, 0, false, false));
        expect("ttl period", false, eligible(10, 0, 100, -200, false, false, false, false, true, 0, 5, 0, false, false));
        expect("view once/media ttl", false, eligible(10, 0, 100, -200, false, false, false, false, true, 0, 0, Integer.MAX_VALUE, false, false));
        expect("ephemeral", false, eligible(10, 0, 100, -200, false, false, false, false, true, 0, 0, 0, true, false));
        expect("peer protected", false, eligible(10, 0, 100, -200, false, false, false, false, true, 0, 0, 0, false, true));
        expect("loaded private peer protection is positive evidence", true,
                peerProtected(200, false, false, true, true, true));
        expect("ordinary private text", true, eligible(10, 0, 100, 200, false, false,
                false, false, true, 0, 0, 0, false,
                peerProtected(200, false, false, true, false, false)));
        expect("missing UserFull private text", true, eligible(10, 0, 100, 200, false, false,
                false, false, true, 0, 0, 0, false,
                peerProtected(200, false, false, false, false, false)));
        expect("loaded private peer protection rejects", false, eligible(10, 0, 100, 200, false, false,
                false, false, true, 0, 0, 0, false,
                peerProtected(200, false, false, true, true, false)));
        expect("missing UserFull cannot invent protection", true, eligible(10, 0, 100, 200, false, false,
                false, false, true, 0, 0, 0, false,
                peerProtected(200, false, false, false, true, true)));
        expect("normal unrestricted group", false,
                peerProtected(-200, true, false, false, false, false));
        expect("protected group", true,
                peerProtected(-200, true, true, false, false, false));
        expect("missing group metadata is not protection", false,
                peerProtected(-200, false, true, false, false, false));
        expect("missing peer fails closed", false, eligible(10, 0, 100, -200, false, false, false, false, false, 0, 0, 0, false, false));
        expect("own explicit storage", false, eligible(10, 0, 100, 100, false, false, false, false, true, 0, 0, 0, false, false));
        expect("unsent/local id", false, eligible(-10, 0, 100, -200, false, false, false, false, true, 0, 0, 0, false, false));
        DeletedMessagesArchivePolicy.HistoryWindow initial = DeletedMessagesArchivePolicy.historyWindow(95, 0);
        expect("closed DM deleted top reopens", true, initial.contains(101));
        expect("initial page does not backfill older archive", false, initial.contains(94));
        DeletedMessagesArchivePolicy.HistoryWindow older = DeletedMessagesArchivePolicy.historyWindow(70, 90);
        expect("older page archived gap", true, older.contains(80));
        expect("older page stops at anchor", false, older.contains(90));
        DeletedMessagesArchivePolicy.HistoryWindow emptyInitial = DeletedMessagesArchivePolicy.historyWindow(0, 0);
        expect("all-deleted empty dialog restart", true, emptyInitial.contains(10));
        System.out.println("DeletedMessagesArchivePolicyTest PASS (policy + closed/reopen paging)");
    }
}

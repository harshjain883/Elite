package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.system.Os;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Account-isolated, local-only history for ordinary cloud messages deleted remotely.
 * Telegram's own message database and deletion semantics remain authoritative.
 *
 * The save-before-delete, identity keys, range reinjection, idempotence, grouped-message
 * loading, and local-delete behavior follow AyuGram's GPLv2 anti-delete subsystem
 * (AyuMessagesController/AyuSavePreferences/DeletedMessageDao/AyuHistoryHook contract),
 * adapted to this Telegram base. The serialized snapshot and private media store are
 * Elitegram compatibility implementations because AyuGram's mapper/history submodule
 * is not present in the public donor checkout.
 */
public final class DeletedMessagesArchive {
    private static final int DATABASE_VERSION = 1;
    private static final int MEDIA_NONE = 0;
    private static final int MEDIA_PENDING = 1;
    private static final int MEDIA_READY = 2;
    private static final int MEDIA_UNAVAILABLE = 3;
    private static final int MAX_DIAGNOSTIC_LINES = 80;

    private static final Object instancesLock = new Object();
    private static final DeletedMessagesArchive[] instances = new DeletedMessagesArchive[UserConfig.MAX_ACCOUNT_COUNT];

    public static DeletedMessagesArchive getInstance(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        synchronized (instancesLock) {
            DeletedMessagesArchive instance = instances[account];
            if (instance == null || instance.ownerUserId != userId) {
                if (instance != null) {
                    instance.helper.close();
                }
                instance = new DeletedMessagesArchive(account, userId);
                instances[account] = instance;
            }
            return instance;
        }
    }

    public static final class ArchiveResult {
        public final android.util.LongSparseArray<ArrayList<Integer>> messages = new android.util.LongSparseArray<>();

        void add(long dialogId, int messageId) {
            ArrayList<Integer> ids = messages.get(dialogId);
            if (ids == null) {
                messages.put(dialogId, ids = new ArrayList<>());
            }
            if (!ids.contains(messageId)) {
                ids.add(messageId);
            }
        }

        public boolean contains(long dialogId, int messageId) {
            ArrayList<Integer> ids = messages.get(dialogId);
            return ids != null && ids.contains(messageId);
        }

        public boolean isEmpty() {
            return messages.size() == 0;
        }

        public int size() {
            int size = 0;
            for (int i = 0; i < messages.size(); i++) {
                size += messages.valueAt(i).size();
            }
            return size;
        }
    }

    private final int account;
    private final long ownerUserId;
    private final ArchiveDatabase helper;
    private final File mediaRoot;
    private final SharedPreferences preferences;
    private final AtomicInteger diagnosticLines = new AtomicInteger();

    private DeletedMessagesArchive(int account, long ownerUserId) {
        this.account = account;
        this.ownerUserId = ownerUserId;
        Context context = ApplicationLoader.applicationContext;
        String identity = Long.toUnsignedString(ownerUserId == 0 ? -(account + 1L) : ownerUserId);
        helper = new ArchiveDatabase(context, "elitegram_deleted_messages_" + identity + ".db");
        mediaRoot = new File(ApplicationLoader.getFilesDirFixed(), "elitegram_deleted_media" + File.separator + identity);
        preferences = context.getSharedPreferences("elitegram_deleted_messages", Context.MODE_PRIVATE);
    }

    private String enabledKey() {
        return "enabled_" + ownerUserId;
    }

    public boolean isEnabled() {
        return ownerUserId != 0 && preferences.getBoolean(enabledKey(), false);
    }

    public void setEnabled(boolean enabled) {
        if (ownerUserId != 0) {
            preferences.edit().putBoolean(enabledKey(), enabled).apply();
        }
    }

    /** Called on Telegram's storage queue, immediately before stock deletion. */
    public ArchiveResult archiveFromTelegramStorage(org.telegram.SQLite.SQLiteDatabase telegramDatabase, long dialogHint, ArrayList<Integer> ids, String source) {
        ArchiveResult result = new ArchiveResult();
        boolean enabled = isEnabled();
        logDiagnostic("event source=" + source + " remote=true owner=" + ownerUserId + " enabled=" + enabled
                + " dialogHint=" + dialogHint + " ids=" + (ids == null ? "null" : TextUtils.join(",", ids)));
        if (!enabled || ids == null || ids.isEmpty()) {
            return result;
        }
        SQLiteCursor cursor = null;
        int sourceRows = 0;
        try {
            String joined = TextUtils.join(",", ids);
            if (dialogHint != 0) {
                cursor = telegramDatabase.queryFinalized(String.format(Locale.US,
                        "SELECT uid, data, send_state FROM messages_v2 WHERE uid = %d AND mid IN(%s)", dialogHint, joined));
            } else {
                cursor = telegramDatabase.queryFinalized(String.format(Locale.US,
                        "SELECT uid, data, send_state FROM messages_v2 WHERE is_channel = 0 AND mid IN(%s)", joined));
            }
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            while (cursor.next()) {
                sourceRows++;
                if (cursor.intValue(2) != MessageObject.MESSAGE_SEND_STATE_SENT) {
                    logDiagnostic("lookup reject reason=not_sent_state dialog=" + cursor.longValue(0));
                    continue;
                }
                long dialogId = cursor.longValue(0);
                org.telegram.tgnet.NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, ownerUserId);
                        message.dialog_id = dialogId;
                        messages.add(message);
                    }
                } finally {
                    data.reuse();
                }
            }
            result = archiveMessages(messages);
            logDiagnostic("lookup source=" + source + " rows=" + sourceRows + " archived=" + result.size());
        } catch (Throwable e) {
            FileLog.e("Keep Deleted Messages: pre-delete archive failed", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    /** Used for already materialized messages so an open chat can transition without flicker. */
    public ArchiveResult archiveMessages(List<TLRPC.Message> messages) {
        ArchiveResult result = new ArchiveResult();
        if (!isEnabled() || messages == null || messages.isEmpty()) {
            return result;
        }
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            db = helper.getWritableDatabase();
            db.beginTransaction();
            for (TLRPC.Message message : messages) {
                long dialogId = MessageObject.getDialogId(message);
                if (dialogId == 0) {
                    dialogId = message.dialog_id;
                }
                String rejectionReason = getRejectionReason(message, dialogId);
                if (rejectionReason != null) {
                    logDiagnostic("eligibility dialog=" + dialogId + " message=" + message.id + " eligible=false reason=" + rejectionReason);
                    continue;
                }
                byte[] snapshot = serialize(message);
                if (snapshot == null) {
                    logDiagnostic("eligibility dialog=" + dialogId + " message=" + message.id + " eligible=true snapshot=false");
                    continue;
                }
                MediaCandidate media = findMediaCandidate(message);
                ContentValues values = new ContentValues();
                values.put("owner_user_id", ownerUserId);
                values.put("dialog_id", dialogId);
                values.put("message_id", message.id);
                values.put("topic_id", getTopicId(message, dialogId));
                values.put("message_date", message.date);
                values.put("delete_date", (int) (System.currentTimeMillis() / 1000L));
                values.put("sender_id", DialogObject.getPeerDialogId(message.from_id));
                values.put("grouped_id", message.grouped_id);
                values.put("snapshot", snapshot);
                values.put("media_source", media.sourcePath);
                values.put("thumb_source", media.thumbSourcePath);
                values.put("media_state", media.sourcePath == null ? MEDIA_UNAVAILABLE : MEDIA_PENDING);
                long inserted = db.insertWithOnConflict("deleted_messages", null, values,
                        android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
                if (inserted != -1) {
                    result.add(dialogId, message.id);
                    scheduleMediaCopy(dialogId, message.id, media);
                    logDiagnostic("insert dialog=" + dialogId + " message=" + message.id + " result=inserted row=true");
                } else if (containsInternal(db, dialogId, message.id)) {
                    result.add(dialogId, message.id);
                    logDiagnostic("insert dialog=" + dialogId + " message=" + message.id + " result=duplicate row=true");
                } else {
                    logDiagnostic("insert dialog=" + dialogId + " message=" + message.id + " result=failed row=false");
                }
            }
            db.setTransactionSuccessful();
        } catch (Throwable e) {
            FileLog.e("Keep Deleted Messages: archive transaction failed", e);
            result = new ArchiveResult();
        } finally {
            if (db != null && db.inTransaction()) {
                db.endTransaction();
            }
        }
        return result;
    }

    public void mergeIntoHistory(ArrayList<TLRPC.Message> target, long dialogId, long topicId, int maxId, int count) {
        if (target == null || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        Set<Integer> existing = new HashSet<>();
        Set<Long> groupedIds = new HashSet<>();
        int minLoadedId = Integer.MAX_VALUE;
        for (TLRPC.Message message : target) {
            existing.add(message.id);
            if (message.id > 0) {
                minLoadedId = Math.min(minLoadedId, message.id);
            }
            if (message.grouped_id != 0) {
                groupedIds.add(message.grouped_id);
            }
        }
        DeletedMessagesArchivePolicy.HistoryWindow window = DeletedMessagesArchivePolicy.historyWindow(
                minLoadedId == Integer.MAX_VALUE ? 0 : minLoadedId, maxId);
        StringBuilder where = new StringBuilder("owner_user_id=? AND dialog_id=? AND topic_id=?");
        ArrayList<String> args = new ArrayList<>();
        args.add(Long.toString(ownerUserId));
        args.add(Long.toString(dialogId));
        args.add(Long.toString(topicId));
        if (window.minimumId != 0) {
            where.append(" AND message_id>=?");
            args.add(Integer.toString(window.minimumId));
        }
        if (window.exclusiveMaximumId != 0) {
            where.append(" AND message_id<?");
            args.add(Integer.toString(window.exclusiveMaximumId));
        }
        Cursor cursor = null;
        int injected = 0;
        try {
            cursor = helper.getReadableDatabase().query("deleted_messages",
                    historyProjection(),
                    where.toString(), args.toArray(new String[0]), null, null,
                    "message_id ASC", "500");
            injected += appendHistoryRows(cursor, target, existing, groupedIds, dialogId);
            cursor.close();
            cursor = null;

            // AyuGram loads related archived rows for an album as a unit. Include archived
            // members of groups already present in this page even when an ID falls just
            // outside the page boundary.
            if (!groupedIds.isEmpty()) {
                StringBuilder placeholders = new StringBuilder();
                ArrayList<String> groupedArgs = new ArrayList<>();
                groupedArgs.add(Long.toString(ownerUserId));
                groupedArgs.add(Long.toString(dialogId));
                groupedArgs.add(Long.toString(topicId));
                for (Long groupedId : groupedIds) {
                    if (placeholders.length() != 0) {
                        placeholders.append(',');
                    }
                    placeholders.append('?');
                    groupedArgs.add(Long.toString(groupedId));
                }
                cursor = helper.getReadableDatabase().query("deleted_messages", historyProjection(),
                        "owner_user_id=? AND dialog_id=? AND topic_id=? AND grouped_id IN(" + placeholders + ")",
                        groupedArgs.toArray(new String[0]), null, null, "message_id ASC", "500");
                injected += appendHistoryRows(cursor, target, existing, groupedIds, dialogId);
            }
        } catch (Throwable e) {
            FileLog.e("Keep Deleted Messages: history load failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        Collections.sort(target, (left, right) -> {
            if (left.date != right.date) {
                return Integer.compare(right.date, left.date);
            }
            return Integer.compare(right.id, left.id);
        });
        if (injected > 0) {
            logDiagnostic("history dialog=" + dialogId + " topic=" + topicId + " injected=" + injected + " size=" + target.size());
        }
    }

    private static String[] historyProjection() {
        return new String[]{"message_id", "delete_date", "snapshot", "media_path", "thumb_path", "media_source", "thumb_source", "grouped_id"};
    }

    private int appendHistoryRows(Cursor cursor, ArrayList<TLRPC.Message> target, Set<Integer> existing,
                                  Set<Long> groupedIds, long dialogId) {
        int injected = 0;
        while (cursor.moveToNext()) {
            int messageId = cursor.getInt(0);
            if (existing.contains(messageId)) {
                continue;
            }
            TLRPC.Message message = deserialize(cursor.getBlob(2));
            if (message == null) {
                continue;
            }
            message.id = messageId;
            message.dialog_id = dialogId;
            message.unread = false;
            message.media_unread = false;
            message.pinned = false;
            message.elitegramArchivedDeleted = true;
            message.elitegramDeletedAt = cursor.getInt(1);
            restoreArchivedMedia(message, cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6));
            target.add(message);
            existing.add(messageId);
            long groupedId = cursor.getLong(7);
            if (groupedId != 0) {
                groupedIds.add(groupedId);
            }
            injected++;
        }
        return injected;
    }

    /** Removes archived IDs locally and returns the IDs that must not be sent to Telegram. */
    public ArrayList<Integer> deleteArchived(long dialogId, ArrayList<Integer> ids) {
        ArrayList<Integer> removed = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return removed;
        }
        android.database.sqlite.SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (Integer id : ids) {
                Cursor cursor = db.query("deleted_messages", new String[]{"media_path", "thumb_path"},
                        "owner_user_id=? AND dialog_id=? AND message_id=?",
                        new String[]{Long.toString(ownerUserId), Long.toString(dialogId), Integer.toString(id)},
                        null, null, null, "1");
                boolean found = cursor.moveToFirst();
                String mediaPath = found ? cursor.getString(0) : null;
                String thumbPath = found ? cursor.getString(1) : null;
                cursor.close();
                if (!found) {
                    continue;
                }
                db.delete("deleted_messages", "owner_user_id=? AND dialog_id=? AND message_id=?",
                        new String[]{Long.toString(ownerUserId), Long.toString(dialogId), Integer.toString(id)});
                removed.add(id);
                scheduleDelete(mediaPath, thumbPath, messageDirectory(dialogId, id));
            }
            db.setTransactionSuccessful();
        } catch (Throwable e) {
            FileLog.e("Keep Deleted Messages: local archive delete failed", e);
            removed.clear();
        } finally {
            db.endTransaction();
        }
        if (!removed.isEmpty()) {
            logDiagnostic("local-delete remote=false dialog=" + dialogId + " removed=" + removed.size());
        }
        return removed;
    }

    public void clearAll(Runnable completion) {
        Utilities.globalQueue.postRunnable(() -> {
            android.util.LongSparseArray<ArrayList<Integer>> removed = new android.util.LongSparseArray<>();
            try {
                android.database.sqlite.SQLiteDatabase db = helper.getWritableDatabase();
                Cursor cursor = db.query("deleted_messages", new String[]{"dialog_id", "message_id"},
                        "owner_user_id=?", new String[]{Long.toString(ownerUserId)}, null, null, null);
                while (cursor.moveToNext()) {
                    long dialogId = cursor.getLong(0);
                    ArrayList<Integer> ids = removed.get(dialogId);
                    if (ids == null) {
                        removed.put(dialogId, ids = new ArrayList<>());
                    }
                    ids.add(cursor.getInt(1));
                }
                cursor.close();
                db.delete("deleted_messages", "owner_user_id=?", new String[]{Long.toString(ownerUserId)});
                deleteRecursively(mediaRoot);
            } catch (Throwable e) {
                FileLog.e("Keep Deleted Messages: clear failed", e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                for (int i = 0; i < removed.size(); i++) {
                    long dialogId = removed.keyAt(i);
                    TLRPC.Chat chat = dialogId < 0 ? MessagesController.getInstance(account).getChat(-dialogId) : null;
                    long channelId = ChatObject.isChannel(chat) ? -dialogId : 0;
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.messagesDeleted, removed.valueAt(i), channelId, false);
                }
                if (completion != null) {
                    completion.run();
                }
            });
        });
    }

    public boolean contains(long dialogId, int messageId) {
        try {
            return containsInternal(helper.getReadableDatabase(), dialogId, messageId);
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean containsInternal(android.database.sqlite.SQLiteDatabase db, long dialogId, int messageId) {
        Cursor cursor = db.rawQuery("SELECT 1 FROM deleted_messages WHERE owner_user_id=? AND dialog_id=? AND message_id=? LIMIT 1",
                new String[]{Long.toString(ownerUserId), Long.toString(dialogId), Integer.toString(messageId)});
        boolean found = cursor.moveToFirst();
        cursor.close();
        return found;
    }

    @Nullable
    private String getRejectionReason(@Nullable TLRPC.Message message, long dialogId) {
        if (message == null) {
            return "null_message";
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        MessagesController controller = MessagesController.getInstance(account);
        boolean chatLoaded = false;
        boolean chatNoForwards = false;
        boolean userFullLoaded = false;
        boolean userPeerNoForwards = false;
        boolean userMyNoForwards = false;
        boolean userLoaded = false;
        String peerType;
        if (dialogId < 0) {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            chatLoaded = chat != null;
            chatNoForwards = chat != null && controller.isChatNoForwards(chat);
            peerType = chat == null ? "unknown_chat" : ChatObject.isChannel(chat)
                    ? (chat.megagroup ? "supergroup" : "channel") : "basic_group";
        } else {
            userLoaded = controller.getUser(dialogId) != null;
            TLRPC.UserFull userFull = controller.getUserFull(dialogId);
            userFullLoaded = userFull != null;
            userPeerNoForwards = userFull != null && userFull.noforwards_peer_enabled;
            userMyNoForwards = userFull != null && userFull.noforwards_my_enabled;
            peerType = userLoaded ? "private_user" : "unknown_user";
        }
        boolean protectedPeer = DeletedMessagesArchivePolicy.hasPositivePeerProtection(
                dialogId, chatLoaded, chatNoForwards,
                userFullLoaded, userPeerNoForwards, userMyNoForwards);
        int mediaTtl = media == null ? 0 : media.ttl_seconds;
        boolean encryptedDialog = DialogObject.isEncryptedDialog(dialogId);
        boolean secretMessage = message instanceof TLRPC.TL_message_secret;
        boolean ephemeral = MessageObject.isEphemeral(message);
        String reason = DeletedMessagesArchivePolicy.rejectionReason(
                message.id, message.send_state, ownerUserId, dialogId,
                encryptedDialog, secretMessage,
                message instanceof TLRPC.TL_messageService, message.action != null, message.peer_id != null,
                message.ttl, message.ttl_period, mediaTtl,
                ephemeral, protectedPeer);
        if (reason != null) {
            logDiagnostic("reject dialog=" + dialogId + " message=" + message.id + " reason=" + reason
                    + " peerType=" + peerType + " peerProtection=" + protectedPeer);
        }
        return reason;
    }

    public void logTransition(long dialogId, int messageId, boolean retained) {
        logDiagnostic("chat-transition dialog=" + dialogId + " message=" + messageId + " archiveRow=" + retained);
    }

    private void logDiagnostic(String message) {
        if (diagnosticLines.getAndIncrement() < MAX_DIAGNOSTIC_LINES) {
            android.util.Log.i("ElitegramDeleted", "account=" + account + " " + message);
        }
    }

    private long getTopicId(TLRPC.Message message, long dialogId) {
        TLRPC.Chat chat = dialogId < 0 ? MessagesController.getInstance(account).getChat(-dialogId) : null;
        return MessageObject.getTopicId(account, message, chat != null && chat.forum ? 1 : 0);
    }

    @Nullable
    private byte[] serialize(TLRPC.Message message) {
        SerializedData data = null;
        try {
            MessageObject.normalizeFlags(message);
            data = new SerializedData(message.getObjectSize());
            message.serializeToStream(data);
            return data.toByteArray();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    @Nullable
    private TLRPC.Message deserialize(byte[] snapshot) {
        if (snapshot == null || snapshot.length < 4) {
            return null;
        }
        SerializedData data = new SerializedData(snapshot);
        try {
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (message != null) {
                message.readAttachPath(data, ownerUserId);
            }
            return message;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            data.cleanup();
        }
    }

    private static final class MediaCandidate {
        String sourcePath;
        String thumbSourcePath;
    }

    private MediaCandidate findMediaCandidate(TLRPC.Message message) {
        MediaCandidate result = new MediaCandidate();
        try {
            if (!TextUtils.isEmpty(message.attachPath)) {
                File attached = new File(message.attachPath);
                if (isUsableFile(attached)) {
                    result.sourcePath = attached.getAbsolutePath();
                }
            }
            if (result.sourcePath == null) {
                File source = FileLoader.getInstance(account).getPathToMessage(message, false);
                if (isUsableFile(source)) {
                    result.sourcePath = source.getAbsolutePath();
                }
            }
            TLRPC.PhotoSize thumb = null;
            TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media != null && media.photo != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 90);
            } else if (media != null && media.document != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, 90);
            }
            if (thumb != null && !(thumb instanceof TLRPC.TL_photoStrippedSize)) {
                File source = FileLoader.getInstance(account).getPathToAttach(thumb, true);
                if (isUsableFile(source) && !source.getAbsolutePath().equals(result.sourcePath)) {
                    result.thumbSourcePath = source.getAbsolutePath();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    private void scheduleMediaCopy(long dialogId, int messageId, MediaCandidate media) {
        if (media.sourcePath == null && media.thumbSourcePath == null) {
            return;
        }
        OpenMedia mediaInput = openMedia(media.sourcePath);
        OpenMedia thumbInput = openMedia(media.thumbSourcePath);
        Utilities.globalQueue.postRunnable(() -> {
            String mediaPath = copyToArchive(mediaInput, dialogId, messageId, "media");
            String thumbPath = copyToArchive(thumbInput, dialogId, messageId, "thumb");
            try {
                ContentValues values = new ContentValues();
                values.put("media_path", mediaPath);
                values.put("thumb_path", thumbPath);
                values.put("media_state", mediaPath != null || thumbPath != null ? MEDIA_READY : MEDIA_UNAVAILABLE);
                helper.getWritableDatabase().update("deleted_messages", values,
                        "owner_user_id=? AND dialog_id=? AND message_id=?",
                        new String[]{Long.toString(ownerUserId), Long.toString(dialogId), Integer.toString(messageId)});
            } catch (Throwable e) {
                FileLog.e("Keep Deleted Messages: media state update failed", e);
            }
        });
    }

    @Nullable
    private OpenMedia openMedia(@Nullable String sourcePath) {
        if (TextUtils.isEmpty(sourcePath)) {
            return null;
        }
        File source = new File(sourcePath);
        if (!isUsableFile(source)) {
            return null;
        }
        try {
            return new OpenMedia(new FileInputStream(source), source.getName(), source.length());
        } catch (Throwable e) {
            FileLog.e("Keep Deleted Messages: media source open failed", e);
            return null;
        }
    }

    @Nullable
    private String copyToArchive(@Nullable OpenMedia source, long dialogId, int messageId, String prefix) {
        if (source == null) {
            return null;
        }
        String extension = safeExtension(source.name);
        File directory = messageDirectory(dialogId, messageId);
        if (!directory.exists() && !directory.mkdirs()) {
            source.close();
            return null;
        }
        File destination = new File(directory, prefix + extension);
        if (isUsableFile(destination) && destination.length() == source.length) {
            source.close();
            return destination.getAbsolutePath();
        }
        File temporary = new File(directory, prefix + extension + ".tmp");
        try (FileInputStream input = source.input; FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } catch (Throwable e) {
            temporary.delete();
            FileLog.e("Keep Deleted Messages: media copy failed", e);
            return null;
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            return null;
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            return null;
        }
        return destination.getAbsolutePath();
    }

    private static final class OpenMedia {
        final FileInputStream input;
        final String name;
        final long length;

        OpenMedia(FileInputStream input, String name, long length) {
            this.input = input;
            this.name = name;
            this.length = length;
        }

        void close() {
            try {
                input.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private void restoreArchivedMedia(TLRPC.Message message, String mediaPath, String thumbPath, String originalMediaPath, String originalThumbPath) {
        if (isUsableFile(path(mediaPath))) {
            message.elitegramArchivedMediaPath = mediaPath;
            message.attachPath = mediaPath;
            restoreFileIfMissing(mediaPath, originalMediaPath);
        }
        if (isUsableFile(path(thumbPath))) {
            restoreFileIfMissing(thumbPath, originalThumbPath);
        }
    }

    private void restoreFileIfMissing(String archivePath, String originalPath) {
        File archive = path(archivePath);
        File original = path(originalPath);
        if (!isUsableFile(archive) || original == null || original.exists()) {
            return;
        }
        File parent = original.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            return;
        }
        try {
            Os.link(archive.getAbsolutePath(), original.getAbsolutePath());
            if (original.exists()) {
                return;
            }
        } catch (Throwable ignore) {
        }
        Utilities.globalQueue.postRunnable(() -> copyFile(archive, original));
    }

    private void copyFile(File source, File destination) {
        File temporary = new File(destination.getAbsolutePath() + ".elitegram.tmp");
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
            if (!destination.exists()) {
                temporary.renameTo(destination);
            }
        } catch (Throwable e) {
            FileLog.e("Keep Deleted Messages: cache restore failed", e);
        } finally {
            temporary.delete();
        }
    }

    private void scheduleDelete(String mediaPath, String thumbPath, File directory) {
        Utilities.globalQueue.postRunnable(() -> {
            if (!TextUtils.isEmpty(mediaPath)) {
                new File(mediaPath).delete();
            }
            if (!TextUtils.isEmpty(thumbPath)) {
                new File(thumbPath).delete();
            }
            deleteRecursively(directory);
        });
    }

    private File messageDirectory(long dialogId, int messageId) {
        return new File(new File(mediaRoot, Long.toString(dialogId)), Integer.toString(messageId));
    }

    private static String safeExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 12) {
            return "";
        }
        String extension = name.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    @Nullable
    private static File path(@Nullable String value) {
        return TextUtils.isEmpty(value) ? null : new File(value);
    }

    private static boolean isUsableFile(@Nullable File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static final class ArchiveDatabase extends SQLiteOpenHelper {
        ArchiveDatabase(Context context, String name) {
            super(context, name, null, DATABASE_VERSION);
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(android.database.sqlite.SQLiteDatabase db) {
            db.execSQL("CREATE TABLE deleted_messages (" +
                    "owner_user_id INTEGER NOT NULL," +
                    "dialog_id INTEGER NOT NULL," +
                    "message_id INTEGER NOT NULL," +
                    "topic_id INTEGER NOT NULL DEFAULT 0," +
                    "message_date INTEGER NOT NULL," +
                    "delete_date INTEGER NOT NULL," +
                    "sender_id INTEGER NOT NULL DEFAULT 0," +
                    "grouped_id INTEGER NOT NULL DEFAULT 0," +
                    "snapshot BLOB NOT NULL," +
                    "media_source TEXT," +
                    "media_path TEXT," +
                    "thumb_source TEXT," +
                    "thumb_path TEXT," +
                    "media_state INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(owner_user_id, dialog_id, message_id))");
            db.execSQL("CREATE INDEX deleted_messages_history_idx ON deleted_messages(owner_user_id, dialog_id, topic_id, message_date DESC, message_id DESC)");
            db.execSQL("CREATE INDEX deleted_messages_date_idx ON deleted_messages(owner_user_id, delete_date DESC)");
        }

        @Override
        public void onUpgrade(android.database.sqlite.SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the initial production schema. Future migrations must be additive.
        }

        @Override
        public void onConfigure(android.database.sqlite.SQLiteDatabase db) {
            super.onConfigure(db);
            db.setForeignKeyConstraintsEnabled(true);
        }
    }
}

package org.webrtc.voiceengine;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.appcompat.app.AppCompatActivity;

import org.telegram.messenger.utils.ModConfig;
import org.telegram.messenger.voip.VoIPService;

import java.io.File;

public class FileSelectAndPlayActivity extends AppCompatActivity {
    private static final String EXTRA_GENERATION = "selected_audio_generation";

    private ActivityResultLauncher<String> pickAudioLauncher;
    private long generation = -1;

    public static boolean open(Context context) {
        long generation = ModConfig.beginSelectedAudioSelection();
        if (generation < 0) {
            return false;
        }
        try {
            Intent intent = new Intent(context, FileSelectAndPlayActivity.class);
            intent.putExtra(EXTRA_GENERATION, generation);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            ModConfig.finishSelectedAudioPlayback(generation);
            Toast.makeText(context, "Cannot open file picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        generation = getIntent().getLongExtra(EXTRA_GENERATION, -1);
        if (generation < 0 || !ModConfig.isSelectedAudioPlaybackRequested(generation)) {
            finish();
            return;
        }
        pickAudioLauncher = registerForActivityResult(new ActivityResultContract<String, Uri>() {
            @Override
            public Intent createIntent(Context context, String input) {
                return new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(input);
            }

            @Override
            public Uri parseResult(int resultCode, Intent intent) {
                if (resultCode != RESULT_OK) {
                    intent = null;
                }
                return intent != null ? intent.getData() : null;
            }
        }, new ActivityResultCallback<Uri>() {
            @Override
            public void onActivityResult(Uri result) {
                handleFileSelection(result);
            }
        });
        pickAudioLauncher.launch("audio/*");
    }

    private void handleFileSelection(Uri uri) {
        boolean callCanSend = uri != null && ensureCallCanSendSelectedAudio();
        if (callCanSend && ModConfig.completeSelectedAudioSelection(generation, uri)) {
            String name = null;
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null) {
                    int columnIndex = cursor.getColumnIndex("_display_name");
                    if (columnIndex >= 0 && cursor.moveToFirst()) {
                        name = cursor.getString(columnIndex);
                    }
                }
            }
            if (name == null) {
                name = new File(uri.getPath()).getName();
            }
            Toast.makeText(this, "✅ Audio file selected: " + name, Toast.LENGTH_SHORT).show();
        } else {
            ModConfig.completeSelectedAudioSelection(generation, null);
            Toast.makeText(this, uri == null ? "❌ No audio file selected" : "Unable to unmute this call", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private boolean ensureCallCanSendSelectedAudio() {
        VoIPService service = VoIPService.getSharedInstance();
        if (service == null || service.mutedByAdmin()) {
            return false;
        }
        if (service.isMicMute()) {
            service.setMicMute(false, false, true);
        }
        return !service.isMicMute();
    }

    @Override
    protected void onDestroy() {
        ModConfig.closeSelectedAudioSelection(generation);
        super.onDestroy();
    }
}

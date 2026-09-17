package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.GhostModeController;
import org.telegram.messenger.DeletedMessagesArchive;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.utils.ModConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** The account-scoped home for Elitegram features. Empty future categories remain hidden. */
public class ElitegramFeaturesActivity extends BaseFragment {

    private static final int ID_GHOST_MODE = 1;
    private static final int ID_SEEN_ON_REPLY = 2;
    private static final int ID_NO_MIC_BLINK = 3;
    private static final int ID_SELECTED_AUDIO = 4;
    private static final int ID_VOICE_EFFECTS = 5;
    private static final int ID_KEEP_DELETED = 6;
    private static final int ID_CLEAR_DELETED = 7;
    private static final int ID_CHANNEL = 8;
    private static final int ID_CREATOR = 9;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ElitegramFeatures));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.ElitegramPrivacy)));
        items.add(UItem.asButtonCheck(ID_GHOST_MODE, getString(R.string.ElitegramGhostMode), getString(R.string.ElitegramGhostModeInfo))
                .setChecked(GhostModeController.getInstance(currentAccount).isEnabled()));
        if (GhostModeController.getInstance(currentAccount).isEnabled()) {
            items.add(UItem.asButtonCheck(ID_SEEN_ON_REPLY, getString(R.string.ElitegramSeenOnReply), getString(R.string.ElitegramSeenOnReplyInfo))
                    .setChecked(GhostModeController.getInstance(currentAccount).isSeenOnReplyEnabled()));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.ElitegramChatsMessages)));
        items.add(UItem.asButtonCheck(ID_KEEP_DELETED, getString(R.string.ElitegramKeepDeletedMessages), getString(R.string.ElitegramKeepDeletedMessagesInfo))
                .setChecked(DeletedMessagesArchive.getInstance(currentAccount).isEnabled()));
        items.add(UItem.asButton(ID_CLEAR_DELETED, getString(R.string.ElitegramClearDeletedMessages), null).red());
        items.add(UItem.asShadow(getString(R.string.ElitegramClearDeletedMessagesInfo)));

        items.add(UItem.asHeader(getString(R.string.ElitegramCallsVoice)));
        items.add(UItem.asButtonCheck(ID_NO_MIC_BLINK, getString(R.string.ElitegramNoMicBlink), getString(R.string.ElitegramNoMicBlinkInfo))
                .setChecked(SharedConfig.isNoMicBlink()));
        items.add(UItem.asButtonCheck(ID_SELECTED_AUDIO, getString(R.string.ElitegramSelectedAudio), getString(R.string.ElitegramSelectedAudioInfo))
                .setChecked(ModConfig.isUseDeviceAudio()));
        items.add(UItem.asButton(ID_VOICE_EFFECTS, getString(R.string.ElitegramVoiceEffects), getVoiceEffectName()));
        items.add(UItem.asShadow(getString(R.string.ElitegramVoiceEffectsInfo)));

        items.add(UItem.asHeader(getString(R.string.ElitegramAbout)));
        items.add(UItem.asButton(ID_CHANNEL, getString(R.string.ElitegramOfficialChannel), "@aboutelite"));
        items.add(UItem.asButton(ID_CREATOR, getString(R.string.ElitegramCreatedBy), getString(R.string.ElitegramCreatorValue)));
        items.add(UItem.asShadow(getString(R.string.ElitegramChannelInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_GHOST_MODE) {
            GhostModeController controller = GhostModeController.getInstance(currentAccount);
            controller.setEnabled(!controller.isEnabled());
            listView.adapter.update(true);
        } else if (item.id == ID_SEEN_ON_REPLY) {
            GhostModeController controller = GhostModeController.getInstance(currentAccount);
            controller.setSeenOnReplyEnabled(!controller.isSeenOnReplyEnabled());
            listView.adapter.update(true);
        } else if (item.id == ID_NO_MIC_BLINK) {
            SharedConfig.setNoMicBlink(!SharedConfig.isNoMicBlink());
            listView.adapter.update(true);
        } else if (item.id == ID_SELECTED_AUDIO) {
            ModConfig.setUseDeviceAudio(!ModConfig.isUseDeviceAudio());
            listView.adapter.update(true);
        } else if (item.id == ID_KEEP_DELETED) {
            DeletedMessagesArchive archive = DeletedMessagesArchive.getInstance(currentAccount);
            archive.setEnabled(!archive.isEnabled());
            listView.adapter.update(true);
        } else if (item.id == ID_CLEAR_DELETED) {
            new AlertDialog.Builder(getContext(), resourceProvider)
                    .setTitle(getString(R.string.ElitegramClearDeletedMessages))
                    .setMessage(getString(R.string.ElitegramClearDeletedMessagesConfirm))
                    .setNegativeButton(getString(R.string.CancelRound), null)
                    .setPositiveButton(getString(R.string.CacheClear), (dialog, which) ->
                            DeletedMessagesArchive.getInstance(currentAccount).clearAll(() -> listView.adapter.update(true)))
                    .show();
        } else if (item.id == ID_VOICE_EFFECTS) {
            showVoiceEffectSelector();
        } else if (item.id == ID_CHANNEL) {
            MessagesController.getInstance(currentAccount).openByUserName("aboutelite", this, 1);
        } else if (item.id == ID_CREATOR) {
            MessagesController.getInstance(currentAccount).openByUserName("iflexsid", this, 0);
        }
    }

    private void showVoiceEffectSelector() {
        CharSequence[] effects = new CharSequence[] {
                getString(R.string.ElitegramVoiceEffectNormal),
                getString(R.string.ElitegramVoiceEffectChild),
                getString(R.string.ElitegramVoiceEffectAdult),
                getString(R.string.ElitegramVoiceEffectRobot),
                getString(R.string.ElitegramVoiceEffectEcho),
                getString(R.string.ElitegramVoiceEffectFemale)
        };
        int selected = ModConfig.getCurrentVoiceEffect();
        CharSequence[] displayedEffects = effects.clone();
        displayedEffects[selected] = "\u2713 " + displayedEffects[selected];
        new AlertDialog.Builder(getContext(), resourceProvider)
                .setTitle(getString(R.string.ElitegramVoiceEffects))
                .setItems(displayedEffects, (dialog, which) -> {
                    ModConfig.setCurrentVoiceEffect(which);
                    listView.adapter.update(true);
                })
                .show();
    }

    private String getVoiceEffectName() {
        switch (ModConfig.getCurrentVoiceEffect()) {
            case ModConfig.VOICE_EFFECT_CHILD:
                return getString(R.string.ElitegramVoiceEffectChild);
            case ModConfig.VOICE_EFFECT_ADULT:
                return getString(R.string.ElitegramVoiceEffectAdult);
            case ModConfig.VOICE_EFFECT_ROBOT:
                return getString(R.string.ElitegramVoiceEffectRobot);
            case ModConfig.VOICE_EFFECT_ECHO:
                return getString(R.string.ElitegramVoiceEffectEcho);
            case ModConfig.VOICE_EFFECT_FEMALE:
                return getString(R.string.ElitegramVoiceEffectFemale);
            default:
                return getString(R.string.ElitegramVoiceEffectNormal);
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            if (listView != null) {
                listView.adapter.notifyDataSetChanged();
                listView.invalidate();
            }
        };

        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, delegate, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        return descriptions;
    }
}

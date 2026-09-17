package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** A lightweight, lifecycle-aware creator banner for the ordinary Chats surface. */
public final class ElitegramPromoBanner extends FrameLayout {

    private static final String CREATOR_USERNAME = "iflexsid";
    private static final String CHANNEL_USERNAME = "aboutelite";
    private static final String TYPEWRITER_TEXT = "Elitegram by @iflexsid...";

    private final int currentAccount;
    private final BaseFragment fragment;
    private final Theme.ResourcesProvider resourcesProvider;
    private final BackupImageView avatarView;
    private final AvatarDrawable fallbackAvatar;
    private final TextView titleView;
    private final TextView subtitleView;
    private Runnable cancelResolve;
    private boolean typewriterRunning;
    private int typedCharacterCount;
    private final Runnable typewriterRunnable = new Runnable() {
        @Override
        public void run() {
            if (!typewriterRunning || !isAttachedToWindow() || !isShown()) {
                return;
            }
            if (typedCharacterCount < TYPEWRITER_TEXT.length()) {
                typedCharacterCount++;
                subtitleView.setText(TYPEWRITER_TEXT.substring(0, typedCharacterCount));
                AndroidUtilities.runOnUIThread(this, 42);
            } else {
                subtitleView.setText("");
                typedCharacterCount = 0;
                AndroidUtilities.runOnUIThread(this, 900);
            }
        }
    };

    public ElitegramPromoBanner(Context context, int currentAccount, BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.currentAccount = currentAccount;
        this.fragment = fragment;
        this.resourcesProvider = resourcesProvider;

        setMinimumHeight(dp(64));
        setClickable(true);
        setFocusable(true);
        setBackground(Theme.getSelectorDrawable(false));
        setContentDescription("Elitegram official channel");
        setOnClickListener(v -> MessagesController.getInstance(currentAccount).openByUserName(CHANNEL_USERNAME, fragment, 1));

        fallbackAvatar = new AvatarDrawable();
        fallbackAvatar.setInfo(0, "Sid", null);
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(22));
        avatarView.setImageDrawable(fallbackAvatar);
        avatarView.setContentDescription("Sid (@iflexsid)");
        avatarView.setOnClickListener(v -> MessagesController.getInstance(currentAccount).openByUserName(CREATOR_USERNAME, fragment, 1));
        addView(avatarView, LayoutHelper.createFrame(44, 44, Gravity.START | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        titleView = new TextView(context);
        titleView.setText("Presenting");
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        titleView.setLetterSpacing(0.025f);
        titleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        titleView.setContentDescription("Presenting Elitegram");
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24, Gravity.START | Gravity.TOP, 68, 8, 12, 0));

        subtitleView = new TextView(context);
        subtitleView.setText("");
        subtitleView.setTextSize(14);
        subtitleView.setSingleLine(true);
        subtitleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24, Gravity.START | Gravity.TOP, 68, 31, 12, 0));

        updateColors();
    }

    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        setBackground(Theme.getSelectorDrawable(false));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resolveAvatar();
        startTextAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTextAnimation();
        if (cancelResolve != null) {
            cancelResolve.run();
            cancelResolve = null;
        }
        super.onDetachedFromWindow();
    }

    private void resolveAvatar() {
        if (cancelResolve != null) {
            return;
        }
        cancelResolve = MessagesController.getInstance(currentAccount).getUserNameResolver().resolve(CREATOR_USERNAME, peerId -> {
            cancelResolve = null;
            if (!isAttachedToWindow() || peerId == null || peerId == Long.MAX_VALUE) {
                return;
            }
            TLObject peer = MessagesController.getInstance(currentAccount).getUserOrChat(peerId);
            if (peer != null) {
                avatarView.setForUserOrChat(peer, fallbackAvatar);
            }
        });
    }

    private void startTextAnimation() {
        if (typewriterRunning) {
            return;
        }
        typewriterRunning = true;
        typedCharacterCount = 0;
        subtitleView.setText("");
        AndroidUtilities.runOnUIThread(typewriterRunnable, 260);
    }

    private void stopTextAnimation() {
        typewriterRunning = false;
        AndroidUtilities.cancelRunOnUIThread(typewriterRunnable);
        typedCharacterCount = 0;
        subtitleView.setText("");
    }
}

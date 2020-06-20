// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.omnibox.suggestions.base;

import android.content.Context;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.widget.AppCompatImageView;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.omnibox.suggestions.basic.SuggestionViewDelegate;
import org.chromium.chrome.browser.util.KeyNavigationUtil;
import org.chromium.components.browser_ui.widget.RoundedCornerImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Base layout for common suggestion types. Includes support for a configurable suggestion content
 * and the common suggestion patterns shared across suggestion formats.
 *
 * @param <T> The type of View being wrapped by this container.
 */
public class BaseSuggestionView<T extends View> extends SimpleHorizontalLayoutView {
    private final List<ImageView> mActionButtons;
    private final @DrawableRes int mSelectableBackgroundRes;
    private final DecoratedSuggestionView<T> mDecoratedView;
    private SuggestionViewDelegate mDelegate;

    /**
     * Constructs a new suggestion view.
     *
     * @param view The view wrapped by the suggestion containers.
     */
    public BaseSuggestionView(T view) {
        super(view.getContext());

        TypedValue themeRes = new TypedValue();
        getContext().getTheme().resolveAttribute(R.attr.selectableItemBackground, themeRes, true);
        mSelectableBackgroundRes = themeRes.resourceId;

        mDecoratedView = new DecoratedSuggestionView<>(getContext(), mSelectableBackgroundRes);
        mDecoratedView.setOnClickListener(v -> mDelegate.onSelection());
        mDecoratedView.setOnLongClickListener(v -> {
            mDelegate.onLongPress();
            return true;
        });
        mDecoratedView.setLayoutParams(LayoutParams.forDynamicView());
        addView(mDecoratedView);

        mActionButtons = new ArrayList<>();
        setContentView(view);
    }

    /**
     * Prepare (truncate or add) Action views for the Suggestion.
     *
     * @param desiredViewCount Number of action views for this suggestion.
     */
    void setActionButtonsCount(int desiredViewCount) {
        final int currentViewCount = mActionButtons.size();

        if (currentViewCount < desiredViewCount) {
            increaseActionButtonsCount(desiredViewCount);
        } else if (currentViewCount > desiredViewCount) {
            decreaseActionButtonsCount(desiredViewCount);
        }
    }

    /**
     * @return List of Action views.
     */
    List<ImageView> getActionButtons() {
        return mActionButtons;
    }

    /**
     * Create additional action buttons for the suggestion view.
     *
     * @param desiredViewCount Desired number of action buttons.
     */
    private void increaseActionButtonsCount(int desiredViewCount) {
        for (int index = mActionButtons.size(); index < desiredViewCount; index++) {
            ImageView actionView = new AppCompatImageView(getContext());
            actionView.setBackgroundResource(mSelectableBackgroundRes);
            actionView.setClickable(true);
            actionView.setFocusable(true);
            actionView.setScaleType(ImageView.ScaleType.CENTER);

            actionView.setLayoutParams(
                    new LayoutParams(getResources().getDimensionPixelSize(
                                             R.dimen.omnibox_suggestion_action_icon_width),
                            LayoutParams.MATCH_PARENT));
            mActionButtons.add(actionView);
            addView(actionView);
        }
    }

    /**
     * Remove unused action views from the suggestion view.
     *
     * @param desiredViewCount Desired target number of action buttons.
     */
    private void decreaseActionButtonsCount(int desiredViewCount) {
        for (int index = desiredViewCount; index < mActionButtons.size(); index++) {
            removeView(mActionButtons.get(index));
        }
        mActionButtons.subList(desiredViewCount, mActionButtons.size()).clear();
    }

    /**
     * Constructs a new suggestion view and inflates supplied layout as the contents view.
     *
     * @param context The context used to construct the suggestion view.
     * @param layoutId Layout ID to be inflated as the contents view.
     */
    public BaseSuggestionView(Context context, @LayoutRes int layoutId) {
        this((T) LayoutInflater.from(context).inflate(layoutId, null));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Whenever the suggestion dropdown is touched, we dispatch onGestureDown which is
        // used to let autocomplete controller know that it should stop updating suggestions.
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDelegate.onGestureDown();
        } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            mDelegate.onGestureUp(ev.getEventTime());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if ((!isRtl && KeyNavigationUtil.isGoRight(event))
                || (isRtl && KeyNavigationUtil.isGoLeft(event))) {
            // For views with exactly 1 action icon, continue to support the arrow key triggers.
            if (mActionButtons.size() == 1) {
                mActionButtons.get(0).callOnClick();
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void setSelected(boolean selected) {
        mDecoratedView.setSelected(selected);
        if (selected) {
            mDelegate.onSetUrlToSuggestion();
        }
    }

    /**
     * Set the content view to supplied view.
     *
     * @param view View to be displayed as suggestion content.
     */
    void setContentView(T view) {
        mDecoratedView.setContentView(view);
    }

    /** @return Embedded suggestion content view. */
    public T getContentView() {
        return mDecoratedView.getContentView();
    }

    /** @return Decorated suggestion view. */
    @VisibleForTesting
    public DecoratedSuggestionView<T> getDecoratedSuggestionView() {
        return mDecoratedView;
    }

    /**
     * Set the delegate for the actions on the suggestion view.
     *
     * @param delegate Delegate receiving user events.
     */
    void setDelegate(SuggestionViewDelegate delegate) {
        mDelegate = delegate;
    }

    /** @return Widget holding suggestion decoration icon. */
    RoundedCornerImageView getSuggestionImageView() {
        return mDecoratedView.getImageView();
    }
}

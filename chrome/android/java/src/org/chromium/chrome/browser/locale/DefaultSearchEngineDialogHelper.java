// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.locale;

import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.RadioGroup.OnCheckedChangeListener;

import androidx.annotation.Nullable;

import org.chromium.chrome.browser.locale.LocaleManager.SearchEnginePromoType;
import org.chromium.components.browser_ui.widget.RadioButtonLayout;
import org.chromium.components.search_engines.TemplateUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Handles user interactions with a user dialog that lets them pick a default search engine. */
public class DefaultSearchEngineDialogHelper implements OnCheckedChangeListener, OnClickListener {
    /** Handles interactions with the TemplateUrlService and LocaleManager. */
    public static class HelperDelegate {
        private final int mDialogType;

        /**
         * Basic constructor for the delegate.
         * @param dialogType {@link SearchEnginePromoType} for the dialog this delegate belongs to.
         */
        public HelperDelegate(@SearchEnginePromoType int dialogType) {
            mDialogType = dialogType;
        }

        /** Determine what search engines will be listed. */
        protected List<TemplateUrl> getSearchEngines() {
            List<TemplateUrl> templateUrls =
                    LocaleManager.getInstance().getSearchEnginesForPromoDialog(mDialogType);
            return templateUrls;
        }

        /** Called when the search engine the user selected is confirmed to be the one they want. */
        protected void onUserSeachEngineChoice(List<String> keywords, String keyword) {
            LocaleManager.getInstance().onUserSearchEngineChoiceFromPromoDialog(
                    mDialogType, keywords, keyword);
        }
    }

    private final HelperDelegate mDelegate;
    private final Runnable mFinishRunnable;
    private final Button mConfirmButton;

    /**
     * List of search engine keywords in the order shown to the user.
     */
    private final List<String> mSearchEngineKeywords;

    /**
     * Keyword for the search engine that is selected in the RadioButtonLayout.
     * This value is not locked into the TemplateUrlService until the user confirms it by clicking
     * on {@link #mConfirmButton}.
     */
    private String mCurrentlySelectedKeyword;

    /**
     * Keyword that is both selected and confirmed (with a click to {@link #mConfirmButton}).
     */
    private String mConfirmedKeyword;

    /**
     * Constructs a DefaultSearchEngineDialogHelper.
     *
     * @param dialogType     Dialog type to show.
     * @param controls       {@link RadioButtonLayout} that will contains all the engine options.
     * @param confirmButton  Button that the user clicks on to confirm their selection.
     * @param finishRunnable Runs after the user has confirmed their selection.
     */
    public DefaultSearchEngineDialogHelper(@SearchEnginePromoType int dialogType,
            RadioButtonLayout controls, Button confirmButton, Runnable finishRunnable) {
        mConfirmButton = confirmButton;
        mConfirmButton.setOnClickListener(this);
        mFinishRunnable = finishRunnable;
        mDelegate = createDelegate(dialogType);

        // Shuffle up the engines.
        List<TemplateUrl> engines = mDelegate.getSearchEngines();
        List<CharSequence> engineNames = new ArrayList<>();
        mSearchEngineKeywords = new ArrayList<>();
        Collections.shuffle(engines);
        for (int i = 0; i < engines.size(); i++) {
            TemplateUrl engine = engines.get(i);
            engineNames.add(engine.getShortName());
            mSearchEngineKeywords.add(engine.getKeyword());
        }

        // Add the search engines to the dialog without any of them being selected by default.
        controls.addOptions(engineNames, mSearchEngineKeywords);
        controls.selectChildAtIndex(RadioButtonLayout.INVALID_INDEX);
        controls.setOnCheckedChangeListener(this);

        // Disable the button until the user selects an option.
        updateButtonState();
    }

    /** @return Keyword that corresponds to the search engine that is currently selected. */
    @Nullable
    public final String getCurrentlySelectedKeyword() {
        // TODO(yusufo): All callers should check getConfirmedKeyword below.
        return mCurrentlySelectedKeyword;
    }

    /** @return Keyword that corresponds to the search engine that is selected and confirmed. */
    @Nullable
    public final String getConfirmedKeyword() {
        return mConfirmedKeyword;
    }

    @Override
    public final void onCheckedChanged(RadioGroup group, int checkedId) {
        mCurrentlySelectedKeyword = (String) group.findViewById(checkedId).getTag();
        updateButtonState();
    }

    @Override
    public final void onClick(View view) {
        if (view != mConfirmButton) {
            // Don't propagate the click to the parent to prevent circumventing the dialog.
            assert false : "Unhandled click.";
            return;
        }

        if (mCurrentlySelectedKeyword == null) {
            // The user clicked on the button, but they haven't clicked on an option, yet.
            updateButtonState();
            return;
        }

        mConfirmedKeyword = mCurrentlySelectedKeyword;

        mDelegate.onUserSeachEngineChoice(mSearchEngineKeywords, mConfirmedKeyword.toString());
        mFinishRunnable.run();
    }

    /** Creates the delegate that interacts with the TemplateUrlService. */
    protected HelperDelegate createDelegate(@SearchEnginePromoType int dialogType) {
        return new HelperDelegate(dialogType);
    }

    /** Prevent the user from moving forward until they've clicked a search engine. */
    private final void updateButtonState() {
        mConfirmButton.setEnabled(mCurrentlySelectedKeyword != null);
    }
}

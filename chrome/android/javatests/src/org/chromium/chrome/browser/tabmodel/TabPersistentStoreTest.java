// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.tabmodel;

import android.app.Activity;
import android.support.test.InstrumentationRegistry;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ActivityState;
import org.chromium.base.ContextUtils;
import org.chromium.base.task.AsyncTask;
import org.chromium.base.test.BaseJUnit4ClassRunner;
import org.chromium.base.test.util.AdvancedMockContext;
import org.chromium.base.test.util.CallbackHelper;
import org.chromium.base.test.util.DisableIf;
import org.chromium.base.test.util.Feature;
import org.chromium.base.test.util.MinAndroidSdkLevel;
import org.chromium.chrome.browser.ChromeActivity;
import org.chromium.chrome.browser.accessibility_tab_switcher.OverviewListLayout;
import org.chromium.chrome.browser.app.tabmodel.ChromeTabModelFilterFactory;
import org.chromium.chrome.browser.compositor.overlays.strip.StripLayoutHelper;
import org.chromium.chrome.browser.flags.ActivityType;
import org.chromium.chrome.browser.fullscreen.ChromeFullscreenManager;
import org.chromium.chrome.browser.preferences.ChromePreferenceKeys;
import org.chromium.chrome.browser.preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tab.TabState;
import org.chromium.chrome.browser.tabmodel.NextTabPolicy.NextTabPolicySupplier;
import org.chromium.chrome.browser.tabmodel.TabPersistentStore.TabModelSelectorMetadata;
import org.chromium.chrome.browser.tabmodel.TabPersistentStore.TabPersistentStoreObserver;
import org.chromium.chrome.browser.tabmodel.TestTabModelDirectory.TabModelMetaDataInfo;
import org.chromium.chrome.test.ChromeBrowserTestRule;
import org.chromium.chrome.test.util.browser.tabmodel.MockTabCreator;
import org.chromium.chrome.test.util.browser.tabmodel.MockTabCreatorManager;
import org.chromium.chrome.test.util.browser.tabmodel.MockTabModelSelector;
import org.chromium.content_public.browser.test.util.TestThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Tests for the TabPersistentStore. */
@RunWith(BaseJUnit4ClassRunner.class)
public class TabPersistentStoreTest {
    @Rule
    public final ChromeBrowserTestRule mBrowserTestRule = new ChromeBrowserTestRule();

    private ChromeActivity mChromeActivity;

    private static final int SELECTOR_INDEX = 0;

    private static class TabRestoredDetails {
        public int index;
        public int id;
        public String url;
        public boolean isStandardActiveIndex;
        public boolean isIncognitoActiveIndex;

        /** Store information about a Tab that's been restored. */
        TabRestoredDetails(int index, int id, String url,
                boolean isStandardActiveIndex, boolean isIncognitoActiveIndex) {
            this.index = index;
            this.id = id;
            this.url = url;
            this.isStandardActiveIndex = isStandardActiveIndex;
            this.isIncognitoActiveIndex = isIncognitoActiveIndex;
        }
    }

    /**
     * Used when testing interactions of TabPersistentStore with real {@link TabModelImpl}s.
     */
    static class TestTabModelSelector extends TabModelSelectorBase
            implements TabModelDelegate {
        final TabPersistentStore mTabPersistentStore;
        final MockTabPersistentStoreObserver mTabPersistentStoreObserver;
        private final TabModelOrderController mTabModelOrderController;

        public TestTabModelSelector() throws Exception {
            super(new MockTabCreatorManager(), new ChromeTabModelFilterFactory(), false);
            ((MockTabCreatorManager) getTabCreatorManager()).initialize(this);
            mTabPersistentStoreObserver = new MockTabPersistentStoreObserver();
            mTabPersistentStore =
                    TestThreadUtils.runOnUiThreadBlocking(new Callable<TabPersistentStore>() {
                        @Override
                        public TabPersistentStore call() {
                            TabPersistencePolicy persistencePolicy =
                                    new TabbedModeTabPersistencePolicy(0, true);
                            return new TabPersistentStore(persistencePolicy,
                                    TestTabModelSelector.this, getTabCreatorManager(),
                                    mTabPersistentStoreObserver);
                        }
                    });
            mTabModelOrderController = new TabModelOrderControllerImpl(this);

            Callable<TabModelImpl> callable = new Callable<TabModelImpl>() {
                @Override
                public TabModelImpl call() {
                    return new TabModelImpl(false, false,
                            getTabCreatorManager().getTabCreator(false),
                            getTabCreatorManager().getTabCreator(true), null,
                            mTabModelOrderController, null, mTabPersistentStore,
                            () -> NextTabPolicy.HIERARCHICAL, TestTabModelSelector.this, true);
                }
            };
            TabModelImpl regularTabModel = TestThreadUtils.runOnUiThreadBlocking(callable);
            TabModel incognitoTabModel = new IncognitoTabModel(new IncognitoTabModelImplCreator(
                    getTabCreatorManager().getTabCreator(false),
                    getTabCreatorManager().getTabCreator(true), null, mTabModelOrderController,
                    null, mTabPersistentStore, () -> NextTabPolicy.HIERARCHICAL, this));
            initialize(regularTabModel, incognitoTabModel);
        }

        @Override
        public void requestToShowTab(Tab tab, @TabSelectionType int type) {}

        @Override
        public boolean closeAllTabsRequest(boolean incognito) {
            TabModel model = getModel(incognito);
            while (model.getCount() > 0) {
                Tab tabToClose = model.getTabAt(0);
                model.closeTab(tabToClose, false, false, true);
            }
            return true;
        }

        @Override
        public boolean isSessionRestoreInProgress() {
            return false;
        }

        @Override
        public boolean isCurrentModel(TabModel model) {
            return false;
        }
    }

    static class MockTabPersistentStoreObserver extends TabPersistentStoreObserver {
        public final CallbackHelper initializedCallback = new CallbackHelper();
        public final CallbackHelper detailsReadCallback = new CallbackHelper();
        public final CallbackHelper stateLoadedCallback = new CallbackHelper();
        public final CallbackHelper stateMergedCallback = new CallbackHelper();
        public final CallbackHelper listWrittenCallback = new CallbackHelper();
        public final ArrayList<TabRestoredDetails> details = new ArrayList<>();

        public int mTabCountAtStartup = -1;

        @Override
        public void onInitialized(int tabCountAtStartup) {
            mTabCountAtStartup = tabCountAtStartup;
            initializedCallback.notifyCalled();
        }

        @Override
        public void onDetailsRead(int index, int id, String url,
                boolean isStandardActiveIndex, boolean isIncognitoActiveIndex) {
            details.add(new TabRestoredDetails(
                    index, id, url, isStandardActiveIndex, isIncognitoActiveIndex));
            detailsReadCallback.notifyCalled();
        }

        @Override
        public void onStateLoaded() {
            stateLoadedCallback.notifyCalled();
        }

        @Override
        public void onStateMerged() {
            stateMergedCallback.notifyCalled();
        }

        @Override
        public void onMetadataSavedAsynchronously(TabModelSelectorMetadata metadata) {
            listWrittenCallback.notifyCalled();
        }
    }

    private final TabWindowManager.TabModelSelectorFactory mMockTabModelSelectorFactory =
            new TabWindowManager.TabModelSelectorFactory() {
                @Override
                public TabModelSelector buildSelector(Activity activity,
                        TabCreatorManager tabCreatorManager,
                        NextTabPolicySupplier nextTabPolicySupplier, int selectorIndex) {
                    try {
                        return new TestTabModelSelector();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };

    /** Class for mocking out the directory containing all of the TabState files. */
    private TestTabModelDirectory mMockDirectory;
    private AdvancedMockContext mAppContext;
    private SharedPreferencesManager mPreferences;

    @Before
    public void setUp() {
        TestThreadUtils.runOnUiThreadBlocking(() -> {
            mChromeActivity = new ChromeActivity() {
                @Override
                protected boolean handleBackPressed() {
                    return false;
                }

                @Override
                protected Pair<? extends TabCreator, ? extends TabCreator> createTabCreators() {
                    return null;
                }

                @Override
                protected TabModelSelector createTabModelSelector() {
                    return null;
                }

                @Override
                protected ChromeFullscreenManager createFullscreenManager() {
                    return null;
                }

                @Override
                @ActivityType
                public int getActivityType() {
                    return ActivityType.TABBED;
                }
            };
        });

        // Using an AdvancedMockContext allows us to use a fresh in-memory SharedPreference.
        mAppContext = new AdvancedMockContext(InstrumentationRegistry.getInstrumentation()
                                                      .getTargetContext()
                                                      .getApplicationContext());
        ContextUtils.initApplicationContextForTests(mAppContext);
        mPreferences = SharedPreferencesManager.getInstance();
        mMockDirectory = new TestTabModelDirectory(
                mAppContext, "TabPersistentStoreTest", Integer.toString(SELECTOR_INDEX));
        TabPersistentStore.setBaseStateDirectoryForTests(mMockDirectory.getBaseDirectory());
    }

    @After
    public void tearDown() {
        TestThreadUtils.runOnUiThreadBlocking(() -> {
            TabWindowManager.getInstance().onActivityStateChange(
                    mChromeActivity, ActivityState.DESTROYED);
        });
        mMockDirectory.tearDown();
    }

    private TabPersistentStore buildTabPersistentStore(final TabPersistencePolicy persistencePolicy,
            final TabModelSelector modelSelector, final TabCreatorManager creatorManager,
            final TabPersistentStoreObserver observer) {
        return TestThreadUtils.runOnUiThreadBlockingNoException(new Callable<TabPersistentStore>() {
            @Override
            public TabPersistentStore call() {
                return new TabPersistentStore(persistencePolicy, modelSelector, creatorManager,
                        observer);
            }
        });
    }

    @Test
    @SmallTest
    @Feature("TabPersistentStore")
    public void testBasic() throws Exception {
        TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V4;
        int numExpectedTabs = info.contents.length;

        mMockDirectory.writeTabModelFiles(info, true);

        // Set up the TabPersistentStore.
        MockTabModelSelector mockSelector = new MockTabModelSelector(0, 0, null);
        MockTabCreatorManager mockManager = new MockTabCreatorManager(mockSelector);
        MockTabCreator regularCreator = mockManager.getTabCreator(false);
        MockTabPersistentStoreObserver mockObserver = new MockTabPersistentStoreObserver();
        TabPersistencePolicy persistencePolicy = new TabbedModeTabPersistencePolicy(0, false);
        final TabPersistentStore store =
                buildTabPersistentStore(persistencePolicy, mockSelector, mockManager, mockObserver);

        // Should not prefetch with no prior active tab preference stored.
        Assert.assertNull(store.mPrefetchActiveTabTask);

        // Make sure the metadata file loads properly and in order.
        store.loadState(false /* ignoreIncognitoFiles */);
        mockObserver.initializedCallback.waitForCallback(0, 1);
        Assert.assertEquals(numExpectedTabs, mockObserver.mTabCountAtStartup);

        mockObserver.detailsReadCallback.waitForCallback(0, numExpectedTabs);
        Assert.assertEquals(numExpectedTabs, mockObserver.details.size());
        for (int i = 0; i < numExpectedTabs; i++) {
            TabRestoredDetails details = mockObserver.details.get(i);
            Assert.assertEquals(i, details.index);
            Assert.assertEquals(info.contents[i].tabId, details.id);
            Assert.assertEquals(info.contents[i].url, details.url);
            Assert.assertEquals(details.id == info.selectedTabId, details.isStandardActiveIndex);
            Assert.assertEquals(false, details.isIncognitoActiveIndex);
        }

        // Restore the TabStates.  The first Tab added should be the most recently selected tab.
        TestThreadUtils.runOnUiThreadBlocking(() -> { store.restoreTabs(true); });
        regularCreator.callback.waitForCallback(0, 1);
        Assert.assertEquals(info.selectedTabId, regularCreator.idOfFirstCreatedTab);

        // Confirm that all the TabStates were read from storage (i.e. non-null).
        mockObserver.stateLoadedCallback.waitForCallback(0, 1);
        for (int i = 0; i < info.contents.length; i++) {
            int tabId = info.contents[i].tabId;
            Assert.assertNotNull(regularCreator.created.get(tabId));
        }
    }

    @Test
    @SmallTest
    @Feature({"TabPersistentStore"})
    public void testInterruptedButStillRestoresAllTabs() throws Exception {
        TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V4;
        int numExpectedTabs = info.contents.length;

        mMockDirectory.writeTabModelFiles(info, true);

        // Load up one TabPersistentStore, but don't load up the TabState files.  This prevents the
        // Tabs from being added to the TabModel.
        MockTabModelSelector firstSelector = new MockTabModelSelector(0, 0, null);
        MockTabCreatorManager firstManager = new MockTabCreatorManager(firstSelector);
        MockTabPersistentStoreObserver firstObserver = new MockTabPersistentStoreObserver();
        TabPersistencePolicy firstPersistencePolicy = new TabbedModeTabPersistencePolicy(0, false);
        final TabPersistentStore firstStore = buildTabPersistentStore(
                firstPersistencePolicy, firstSelector, firstManager, firstObserver);
        firstStore.loadState(false /* ignoreIncognitoFiles */);
        firstObserver.initializedCallback.waitForCallback(0, 1);
        Assert.assertEquals(numExpectedTabs, firstObserver.mTabCountAtStartup);
        firstObserver.detailsReadCallback.waitForCallback(0, numExpectedTabs);

        TestThreadUtils.runOnUiThreadBlocking(() -> { firstStore.saveState(); });

        // Prepare a second TabPersistentStore.
        MockTabModelSelector secondSelector = new MockTabModelSelector(0, 0, null);
        MockTabCreatorManager secondManager = new MockTabCreatorManager(secondSelector);
        MockTabCreator secondCreator = secondManager.getTabCreator(false);
        MockTabPersistentStoreObserver secondObserver = new MockTabPersistentStoreObserver();
        TabPersistencePolicy secondPersistencePolicy = new TabbedModeTabPersistencePolicy(0, false);

        final TabPersistentStore secondStore = buildTabPersistentStore(
                secondPersistencePolicy, secondSelector, secondManager, secondObserver);

        // The second TabPersistentStore reads the file written by the first TabPersistentStore.
        // Make sure that all of the Tabs appear in the new one -- even though the new file was
        // written before the first TabPersistentStore loaded any TabState files and added them to
        // the TabModels.
        secondStore.loadState(false /* ignoreIncognitoFiles */);
        secondObserver.initializedCallback.waitForCallback(0, 1);
        Assert.assertEquals(numExpectedTabs, secondObserver.mTabCountAtStartup);

        secondObserver.detailsReadCallback.waitForCallback(0, numExpectedTabs);
        Assert.assertEquals(numExpectedTabs, secondObserver.details.size());
        for (int i = 0; i < numExpectedTabs; i++) {
            TabRestoredDetails details = secondObserver.details.get(i);

            // Find the details for the current Tab ID.
            // TODO(dfalcantara): Revisit this bit when tab ordering is correctly preserved.
            TestTabModelDirectory.TabStateInfo currentInfo = null;
            for (int j = 0; j < numExpectedTabs && currentInfo == null; j++) {
                if (TestTabModelDirectory.TAB_MODEL_METADATA_V4.contents[j].tabId == details.id) {
                    currentInfo = TestTabModelDirectory.TAB_MODEL_METADATA_V4.contents[j];
                }
            }

            // TODO(dfalcantara): This won't be properly set until we have tab ordering preserved.
            // Assert.assertEquals(details.id ==
            // TestTabModelDirectory.TAB_MODEL_METADATA_V4_SELECTED_ID,
            //        details.isStandardActiveIndex);

            Assert.assertEquals(currentInfo.url, details.url);
            Assert.assertEquals(false, details.isIncognitoActiveIndex);
        }

        // Restore all of the TabStates.  Confirm that all the TabStates were read (i.e. non-null).
        TestThreadUtils.runOnUiThreadBlocking(() -> { secondStore.restoreTabs(true); });

        secondObserver.stateLoadedCallback.waitForCallback(0, 1);
        for (int i = 0; i < numExpectedTabs; i++) {
            int tabId = TestTabModelDirectory.TAB_MODEL_METADATA_V4.contents[i].tabId;
            Assert.assertNotNull(secondCreator.created.get(tabId));
        }
    }

    @Test
    @SmallTest
    @Feature({"TabPersistentStore"})
    public void testMissingTabStateButStillRestoresTab() throws Exception {
        TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V5;
        int numExpectedTabs = info.contents.length;

        // Write out info for all but the third tab (arbitrarily chosen).
        mMockDirectory.writeTabModelFiles(info, false);
        for (int i = 0; i < info.contents.length; i++) {
            if (i != 2) mMockDirectory.writeTabStateFile(info.contents[i]);
        }

        // Initialize the classes.
        MockTabModelSelector mockSelector = new MockTabModelSelector(0, 0, null);
        MockTabCreatorManager mockManager = new MockTabCreatorManager(mockSelector);
        MockTabPersistentStoreObserver mockObserver = new MockTabPersistentStoreObserver();
        TabPersistencePolicy persistencePolicy = new TabbedModeTabPersistencePolicy(0, false);
        final TabPersistentStore store =
                buildTabPersistentStore(persistencePolicy, mockSelector, mockManager, mockObserver);

        // Make sure the metadata file loads properly and in order.
        store.loadState(false /* ignoreIncognitoFiles */);
        mockObserver.initializedCallback.waitForCallback(0, 1);
        Assert.assertEquals(numExpectedTabs, mockObserver.mTabCountAtStartup);

        mockObserver.detailsReadCallback.waitForCallback(0, numExpectedTabs);
        Assert.assertEquals(numExpectedTabs, mockObserver.details.size());
        for (int i = 0; i < numExpectedTabs; i++) {
            TabRestoredDetails details = mockObserver.details.get(i);
            Assert.assertEquals(i, details.index);
            Assert.assertEquals(info.contents[i].tabId, details.id);
            Assert.assertEquals(info.contents[i].url, details.url);
            Assert.assertEquals(details.id == info.selectedTabId, details.isStandardActiveIndex);
            Assert.assertEquals(false, details.isIncognitoActiveIndex);
        }

        TestThreadUtils.runOnUiThreadBlocking(() -> {
            // Restore the TabStates, and confirm that the correct number of tabs is created
            // even with one missing.
            store.restoreTabs(true);
        });
        mockObserver.stateLoadedCallback.waitForCallback(0, 1);
        Assert.assertEquals(numExpectedTabs, mockSelector.getModel(false).getCount());
        Assert.assertEquals(0, mockSelector.getModel(true).getCount());
    }

    @Test
    @SmallTest
    @Feature({"TabPersistentStore"})
    public void testRestoresTabWithMissingTabStateWhileIgnoringIncognitoTab() throws Exception {
        TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V5_WITH_INCOGNITO;
        int numExpectedTabs = info.contents.length;

        // Write out info for all but the third tab (arbitrarily chosen).
        mMockDirectory.writeTabModelFiles(info, false);
        for (int i = 0; i < info.contents.length; i++) {
            if (i != 2) mMockDirectory.writeTabStateFile(info.contents[i]);
        }

        // Initialize the classes.
        MockTabModelSelector mockSelector = new MockTabModelSelector(0, 0, null);
        MockTabCreatorManager mockManager = new MockTabCreatorManager(mockSelector);
        MockTabPersistentStoreObserver mockObserver = new MockTabPersistentStoreObserver();
        TabPersistencePolicy persistencePolicy = new TabbedModeTabPersistencePolicy(0, false);
        final TabPersistentStore store =
                buildTabPersistentStore(persistencePolicy, mockSelector, mockManager, mockObserver);

        // Load the TabModel metadata.
        store.loadState(false /* ignoreIncognitoFiles */);
        mockObserver.initializedCallback.waitForCallback(0, 1);
        Assert.assertEquals(numExpectedTabs, mockObserver.mTabCountAtStartup);
        mockObserver.detailsReadCallback.waitForCallback(0, numExpectedTabs);
        Assert.assertEquals(numExpectedTabs, mockObserver.details.size());

        // TODO(dfalcantara): Expand MockTabModel* to support Incognito Tab decryption.

        // Restore the TabStates, and confirm that the correct number of tabs is created even with
        // one missing.  No Incognito tabs should be created because the TabState is missing.
        TestThreadUtils.runOnUiThreadBlocking(() -> { store.restoreTabs(true); });
        mockObserver.stateLoadedCallback.waitForCallback(0, 1);
        Assert.assertEquals(info.numRegularTabs, mockSelector.getModel(false).getCount());
        Assert.assertEquals(0, mockSelector.getModel(true).getCount());
    }

    @Test
    @SmallTest
    @Feature({"TabPersistentStore"})
    public void testPrefetchActiveTab() throws Exception {
        final TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V5_NO_M18;
        mMockDirectory.writeTabModelFiles(info, true);

        // Set to pre-fetch
        mPreferences.writeInt(ChromePreferenceKeys.TABMODEL_ACTIVE_TAB_ID, info.selectedTabId);

        // Initialize the classes.
        MockTabModelSelector mockSelector = new MockTabModelSelector(0, 0, null);
        MockTabCreatorManager mockManager = new MockTabCreatorManager(mockSelector);
        MockTabPersistentStoreObserver mockObserver = new MockTabPersistentStoreObserver();
        TabPersistencePolicy persistencePolicy = new TabbedModeTabPersistencePolicy(0, false);
        final TabPersistentStore store = buildTabPersistentStore(
                persistencePolicy, mockSelector, mockManager, mockObserver);
        store.waitForMigrationToFinish();

        Assert.assertNotNull(store.mPrefetchActiveTabTask);

        store.loadState(false /* ignoreIncognitoFiles */);
        TestThreadUtils.runOnUiThreadBlocking(() -> { store.restoreTabs(true); });

        TestThreadUtils.runOnUiThreadBlocking(() -> {
            // Confirm that the pre-fetched active tab state was used, must be done here on the
            // UI thread as the message to finish the task is posted here.
            Assert.assertEquals(
                    AsyncTask.Status.FINISHED, store.mPrefetchActiveTabTask.getStatus());

            // Confirm that the correct active tab ID is updated when saving state.
            mPreferences.writeInt(ChromePreferenceKeys.TABMODEL_ACTIVE_TAB_ID, -1);

            store.saveState();
        });

        Assert.assertEquals(info.selectedTabId,
                mPreferences.readInt(ChromePreferenceKeys.TABMODEL_ACTIVE_TAB_ID, -1));
    }

    /**
     * Tests that a real {@link TabModelImpl} will use the {@link TabPersistentStore} to write out
     * an updated metadata file when a closure is undone.
     */
    @Test
    @SmallTest
    @Feature({"TabPersistentStore"})
    public void testUndoSingleTabClosureWritesTabListFile() throws Exception {
        TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V5_NO_M18;
        mMockDirectory.writeTabModelFiles(info, true);

        // Start closing one tab, then undo it.  Make sure the tab list metadata is saved out.
        TestTabModelSelector selector = createAndRestoreRealTabModelImpls(info);
        MockTabPersistentStoreObserver mockObserver = selector.mTabPersistentStoreObserver;
        final TabModel regularModel = selector.getModel(false);
        int currentWrittenCallbackCount = mockObserver.listWrittenCallback.getCallCount();
        TestThreadUtils.runOnUiThreadBlocking(() -> {
            Tab tabToClose = regularModel.getTabAt(2);
            regularModel.closeTab(tabToClose, false, false, true);
            regularModel.cancelTabClosure(tabToClose.getId());
        });
        mockObserver.listWrittenCallback.waitForCallback(currentWrittenCallbackCount, 1);
    }

    /**
     * Tests that a real {@link TabModelImpl} will use the {@link TabPersistentStore} to write out
     * valid a valid metadata file and the TabModel's associated TabStates after closing and
     * canceling the closure of all the tabs simultaneously.
     */
    @Test
    @SmallTest
    @Feature({"TabPersistentStore"})
    @DisableIf.Build(sdk_is_greater_than = 25, message = "https://crbug.com/1017732")
    public void testUndoCloseAllTabsWritesTabListFile() throws Exception {
        final TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V5_NO_M18;
        mMockDirectory.writeTabModelFiles(info, true);

        for (int i = 0; i < 2; i++) {
            final TestTabModelSelector selector = createAndRestoreRealTabModelImpls(info);

            // Undoing tab closures one-by-one results in the first tab always being selected after
            // the initial restoration.
            if (i == 0) {
                Assert.assertEquals(info.selectedTabId, selector.getCurrentTab().getId());
            } else {
                Assert.assertEquals(info.contents[0].tabId, selector.getCurrentTab().getId());
            }

            TestThreadUtils.runOnUiThreadBlocking(() -> {
                closeAllTabsThenUndo(selector, info);

                // Synchronously save the data out to simulate minimizing Chrome.
                selector.mTabPersistentStore.saveState();
            });

            // Load up each TabState and confirm that values are still correct.
            for (int j = 0; j < info.numRegularTabs; j++) {
                TabState currentState = TabState.restoreTabState(
                        mMockDirectory.getDataDirectory(), info.contents[j].tabId);
                Assert.assertEquals(info.contents[j].title,
                        currentState.contentsState.getDisplayTitleFromState());
                Assert.assertEquals(
                        info.contents[j].url, currentState.contentsState.getVirtualUrlFromState());
            }
        }
    }

    @Test
    @SmallTest
    @Feature({"TabPersistentStore", "MultiWindow"})
    @MinAndroidSdkLevel(24)
    public void testDuplicateTabIdsOnColdStart() throws Exception {
        final TabModelMetaDataInfo info = TestTabModelDirectory.TAB_MODEL_METADATA_V5_NO_M18;

        // Write the same data to tab_state0 and tab_state1.
        mMockDirectory.writeTabModelFiles(info, true, 0);
        mMockDirectory.writeTabModelFiles(info, true, 1);

        // This method will check that the correct number of tabs are created.
        createAndRestoreRealTabModelImpls(info);
    }

    private TestTabModelSelector createAndRestoreRealTabModelImpls(TabModelMetaDataInfo info)
            throws Exception {
        TestTabModelSelector selector =
                TestThreadUtils.runOnUiThreadBlocking(new Callable<TestTabModelSelector>() {
                    @Override
                    public TestTabModelSelector call() {
                        TabWindowManager tabWindowManager = TabWindowManager.getInstance();
                        tabWindowManager.setTabModelSelectorFactory(mMockTabModelSelectorFactory);
                        // Clear any existing TestTabModelSelector (required when
                        // createAndRestoreRealTabModelImpls is called multiple times in one test).
                        tabWindowManager.onActivityStateChange(
                                mChromeActivity, ActivityState.DESTROYED);
                        return (TestTabModelSelector) tabWindowManager.requestSelector(
                                mChromeActivity, mChromeActivity, null, 0);
                    }
                });

        final TabPersistentStore store = selector.mTabPersistentStore;
        MockTabPersistentStoreObserver mockObserver = selector.mTabPersistentStoreObserver;

        // Load up the TabModel metadata.
        int numExpectedTabs = info.numRegularTabs + info.numIncognitoTabs;
        store.loadState(false /* ignoreIncognitoFiles */);
        mockObserver.initializedCallback.waitForCallback(0, 1);
        Assert.assertEquals(numExpectedTabs, mockObserver.mTabCountAtStartup);
        mockObserver.detailsReadCallback.waitForCallback(0, info.contents.length);
        Assert.assertEquals(numExpectedTabs, mockObserver.details.size());

        // Restore the TabStates, check that things were restored correctly, in the right tab order.
        TestThreadUtils.runOnUiThreadBlocking(() -> { store.restoreTabs(true); });
        mockObserver.stateLoadedCallback.waitForCallback(0, 1);
        Assert.assertEquals(info.numRegularTabs, selector.getModel(false).getCount());
        Assert.assertEquals(info.numIncognitoTabs, selector.getModel(true).getCount());
        for (int i = 0; i < numExpectedTabs; i++) {
            Assert.assertEquals(
                    info.contents[i].tabId, selector.getModel(false).getTabAt(i).getId());
        }

        return selector;
    }

    /**
     * Close all Tabs in the regular TabModel, then undo the operation to restore the Tabs.
     * This simulates how {@link StripLayoutHelper} and {@link UndoBarController} would close
     * all of a {@link TabModel}'s tabs on tablets, which is different from how the
     * {@link OverviewListLayout} would do it on phones.
     */
    private void closeAllTabsThenUndo(TabModelSelector selector, TabModelMetaDataInfo info) {
        // Close all the tabs, using an Observer to determine what is actually being closed.
        TabModel regularModel = selector.getModel(false);
        final List<Integer> closedTabIds = new ArrayList<>();
        TabModelObserver closeObserver = new TabModelObserver() {
            @Override
            public void multipleTabsPendingClosure(List<Tab> tabs, boolean isAllTabs) {
                for (Tab tab : tabs) closedTabIds.add(tab.getId());
            }
        };
        regularModel.addObserver(closeObserver);
        regularModel.closeAllTabs(false, false);
        Assert.assertEquals(info.numRegularTabs, closedTabIds.size());

        // Cancel closing each tab.
        for (Integer id : closedTabIds) regularModel.cancelTabClosure(id);
        Assert.assertEquals(info.numRegularTabs, regularModel.getCount());
    }
}

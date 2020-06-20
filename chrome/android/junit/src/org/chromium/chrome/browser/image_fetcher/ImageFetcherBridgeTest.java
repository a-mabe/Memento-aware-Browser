// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.image_fetcher;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.graphics.Bitmap;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.robolectric.annotation.Config;

import org.chromium.base.Callback;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.profiles.Profile;

import jp.tomorrowkey.android.gifplayer.BaseGifImage;

/**
 * Test for ImageFetcherBridge.java.
 */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ImageFetcherBridgeTest {
    private static final int WIDTH_PX = 10;
    private static final int HEIGHT_PX = 20;
    private static final int EXPIRATION_INTERVAL_MINS = 60;

    @Rule
    public ExpectedException mExpectedException = ExpectedException.none();

    @Mock
    ImageFetcherBridge.Natives mNatives;
    @Mock
    Profile mProfile;
    @Mock
    Callback<Bitmap> mBitmapCallback;
    @Mock
    Callback<BaseGifImage> mGifCallback;

    ImageFetcherBridge mBridge;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        ImageFetcherBridgeJni.TEST_HOOKS.setInstanceForTesting(mNatives);
        mBridge = new ImageFetcherBridge(mProfile);
    }

    @Test
    public void testFetchImage() {
        ArgumentCaptor<Callback<Bitmap>> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        final Bitmap bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        doAnswer((InvocationOnMock invocation) -> {
            callbackCaptor.getValue().onResult(bitmap);
            return null;
        })
                .when(mNatives)
                .fetchImage(eq(mProfile), anyInt(), anyString(), anyString(), eq(0),
                        callbackCaptor.capture());

        mBridge.fetchImage(
                -1, ImageFetcher.Params.create("", "", WIDTH_PX, HEIGHT_PX), mBitmapCallback);
        verify(mBitmapCallback).onResult(bitmap);
    }

    @Test
    public void testFetchImageWithExpirationInterval() {
        ArgumentCaptor<Callback<Bitmap>> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        final Bitmap bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        doAnswer((InvocationOnMock invocation) -> {
            callbackCaptor.getValue().onResult(bitmap);
            return null;
        })
                .when(mNatives)
                .fetchImage(eq(mProfile), anyInt(), anyString(), anyString(),
                        eq(EXPIRATION_INTERVAL_MINS), callbackCaptor.capture());

        mBridge.fetchImage(-1,
                ImageFetcher.Params.createWithExpirationInterval(
                        "url", "clientname", WIDTH_PX, HEIGHT_PX, EXPIRATION_INTERVAL_MINS),
                mBitmapCallback);
        verify(mBitmapCallback).onResult(bitmap);
    }

    @Test
    public void testFetchImage_imageResized() {
        ArgumentCaptor<Callback<Bitmap>> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        final Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        doAnswer((InvocationOnMock invocation) -> {
            callbackCaptor.getValue().onResult(bitmap);
            return null;
        })
                .when(mNatives)
                .fetchImage(eq(mProfile), anyInt(), anyString(), anyString(), eq(0),
                        callbackCaptor.capture());

        mBridge.fetchImage(-1, ImageFetcher.Params.create("", "", 100, 100), mBitmapCallback);
        ArgumentCaptor<Bitmap> bitmapCaptor = ArgumentCaptor.forClass(Bitmap.class);
        verify(mBitmapCallback).onResult(bitmapCaptor.capture());

        Bitmap actual = bitmapCaptor.getValue();
        Assert.assertNotEquals(
                "the bitmap should have been copied when it was resized", bitmap, actual);
        Assert.assertEquals(100, actual.getWidth());
        Assert.assertEquals(100, actual.getHeight());
    }

    @Test
    public void testFetchGif() {
        ArgumentCaptor<Callback<byte[]>> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        doAnswer((InvocationOnMock invocation) -> {
            callbackCaptor.getValue().onResult(new byte[] {1, 2, 3});
            return null;
        })
                .when(mNatives)
                .fetchImageData(eq(mProfile), anyInt(), anyString(), anyString(), eq(0),
                        callbackCaptor.capture());

        mBridge.fetchGif(-1, ImageFetcher.Params.create("", ""), mGifCallback);
        ArgumentCaptor<BaseGifImage> gifCaptor = ArgumentCaptor.forClass(BaseGifImage.class);
        verify(mGifCallback).onResult(gifCaptor.capture());

        Assert.assertNotNull(gifCaptor.getValue());
    }

    @Test
    public void testFetchGif_imageDataNull() {
        ArgumentCaptor<Callback<byte[]>> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        doAnswer((InvocationOnMock invocation) -> {
            callbackCaptor.getValue().onResult(new byte[] {});
            return null;
        })
                .when(mNatives)
                .fetchImageData(eq(mProfile), anyInt(), anyString(), anyString(), eq(0),
                        callbackCaptor.capture());

        mBridge.fetchGif(-1, ImageFetcher.Params.create("", ""), mGifCallback);
        verify(mGifCallback).onResult(null);
    }

    @Test
    public void testGetFilePath() {
        mBridge.getFilePath("testing is cool");
        verify(mNatives).getFilePath(mProfile, "testing is cool");
    }

    @Test
    public void testReportEvent() {
        mBridge.reportEvent("client", 10);
        verify(mNatives).reportEvent("client", 10);
    }

    @Test
    public void testReportCacheHitTime() {
        mBridge.reportCacheHitTime("client", 10L);
        verify(mNatives).reportCacheHitTime("client", 10L);
    }

    @Test
    public void testReportTotalFetchTimeFromNative() {
        mBridge.reportTotalFetchTimeFromNative("client", 10L);
        verify(mNatives).reportTotalFetchTimeFromNative("client", 10L);
    }

    @Test
    public void testSetupForTesting() {
        // Since ImageFetcherBridge creates different instance on each call of getForProfile
        // function, two instances below should not be equal.
        Assert.assertNotEquals(mBridge, ImageFetcherBridge.getForProfile(mProfile));
    }
}
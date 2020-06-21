// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CONTENT_BROWSER_FRAME_HOST_SHOULD_SWAP_BROWSING_INSTANCE_H_
#define CONTENT_BROWSER_FRAME_HOST_SHOULD_SWAP_BROWSING_INSTANCE_H_

namespace content {

// This is the enumeration of the reasons why we might not swap the
// BrowsingInstance for navigations.
// This enum is used for histograms and should not be renumbered.
// TODO(crbug.com/1026101): Remove after the investigations are complete.
enum class ShouldSwapBrowsingInstance {
  kYes_ForceSwap = 0,
  kNo_ProactiveSwapDisabled = 1,
  kNo_NotMainFrame = 2,
  kNo_HasRelatedActiveContents = 3,
  kNo_DoesNotHaveSite = 4,
  kNo_SourceURLSchemeIsNotHTTPOrHTTPS = 5,
  kNo_DestinationURLSchemeIsNotHTTPOrHTTPS = 6,
  kNo_SameSiteNavigation = 7,
  kNo_ReloadingErrorPage = 8,
  kNo_AlreadyHasMatchingBrowsingInstance = 9,
  kNo_RendererDebugURL = 10,
  kNo_NotNeededForBackForwardCache = 11,
  kYes_CrossSiteProactiveSwap = 12,
  kYes_SameSiteProactiveSwap = 13,
  kNo_SameDocumentNavigation = 14,
  kNo_SamePageNavigation = 15,
  kNo_WillReplaceEntry = 16,
  kNo_Reload = 17,
  kNo_Guest = 18,

  kMaxValue = kNo_Guest
};

}  // namespace content

#endif  // CONTENT_BROWSER_FRAME_HOST_SHOULD_SWAP_BROWSING_INSTANCE_H_

// Copyright 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "content/shell/browser/web_test/web_test_browser_main_platform_support.h"

#include <AppKit/AppKit.h>
#include <Foundation/Foundation.h>

namespace content {

namespace {

void SetDefaultsToWebTestValues(void) {
  // So we can match the Blink web tests, we want to force a bunch of
  // preferences that control appearance to match.
  // (We want to do this as early as possible in application startup so
  // the settings are in before any higher layers could cache values.)

  NSUserDefaults* defaults = [NSUserDefaults standardUserDefaults];
  // Do not set text-rendering prefs (AppleFontSmoothing,
  // AppleAntiAliasingThreshold) here: Skia picks the right settings for this
  // in web test mode, see FontSkia.cpp in WebKit and
  // SkFontHost_mac_coretext.cpp in skia.
  const NSInteger kBlueTintedAppearance = 1;
  [defaults setInteger:kBlueTintedAppearance forKey:@"AppleAquaColorVariant"];
  [defaults setObject:@"0.709800 0.835300 1.000000"
               forKey:@"AppleHighlightColor"];
  [defaults setObject:@"0.500000 0.500000 0.500000"
               forKey:@"AppleOtherHighlightColor"];
  [defaults setObject:[NSArray arrayWithObject:@"en"] forKey:@"AppleLanguages"];
  [defaults setBool:NO forKey:@"AppleScrollAnimationEnabled"];
  [defaults setBool:NO forKey:@"NSScrollAnimationEnabled"];
  [defaults setObject:@"Always" forKey:@"AppleShowScrollBars"];

  // Disable AppNap since web tests do not always meet the requirements to
  // avoid "napping" which will cause test timeouts. http://crbug.com/811525.
  [defaults setBool:YES forKey:@"NSAppSleepDisabled"];
}

}  // namespace

void WebTestBrowserPlatformInitialize() {
  SetDefaultsToWebTestValues();
}

}  // namespace content

// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "content/browser/accessibility/browser_accessibility_cocoa.h"
#include "content/browser/accessibility/browser_accessibility_mac.h"
#include "content/browser/accessibility/hit_testing_browsertest.h"
#include "content/public/test/accessibility_notification_waiter.h"
#include "content/public/test/browser_test.h"
#include "content/public/test/content_browser_test_utils.h"
#include "content/shell/browser/shell.h"
#include "ui/gfx/mac/coordinate_conversion.h"

namespace content {

#define EXPECT_ACCESSIBILITY_MAC_HIT_TEST_RESULT(css_point, expected_element, \
                                                 hit_element)                 \
  SCOPED_TRACE(GetScopedTrace(css_point));                                    \
  EXPECT_EQ([expected_element owner]->GetId(), [hit_element owner]->GetId());

class AccessibilityHitTestingMacBrowserTest
    : public AccessibilityHitTestingBrowserTest {
 public:
  BrowserAccessibilityCocoa* GetWebContentRoot() {
    return ToBrowserAccessibilityCocoa(
        GetRootBrowserAccessibilityManager()->GetRoot());
  }
};

INSTANTIATE_TEST_SUITE_P(
    All,
    AccessibilityHitTestingMacBrowserTest,
    ::testing::Combine(::testing::Values(1, 2), ::testing::Bool()),
    AccessibilityHitTestingBrowserTest::TestPassToString());

IN_PROC_BROWSER_TEST_P(AccessibilityHitTestingMacBrowserTest,
                       AccessibilityHitTest) {
  ASSERT_TRUE(embedded_test_server()->Start());

  EXPECT_TRUE(NavigateToURL(shell(), GURL(url::kAboutBlankURL)));

  AccessibilityNotificationWaiter waiter(shell()->web_contents(),
                                         ui::kAXModeComplete,
                                         ax::mojom::Event::kLoadComplete);
  GURL url(embedded_test_server()->GetURL(
      "/accessibility/hit_testing/simple_rectangles.html"));
  EXPECT_TRUE(NavigateToURL(shell(), url));
  waiter.WaitForNotification();

  WaitForAccessibilityTreeToContainNodeWithName(shell()->web_contents(),
                                                "rectA");

  BrowserAccessibilityCocoa* root = GetWebContentRoot();

  // Test a hit on a rect in the main frame.
  {
    gfx::Point rect_2_point(49, 20);
    gfx::Point rect_2_point_frame = CSSToFramePoint(rect_2_point);
    BrowserAccessibilityCocoa* hit_element =
        [root accessibilityHitTest:NSMakePoint(rect_2_point_frame.x(),
                                               rect_2_point_frame.y())];
    BrowserAccessibilityCocoa* expected_element = ToBrowserAccessibilityCocoa(
        FindNode(ax::mojom::Role::kGenericContainer, "rect2"));
    EXPECT_ACCESSIBILITY_MAC_HIT_TEST_RESULT(rect_2_point, expected_element,
                                             hit_element);
  }

  // Test a hit on a rect in the iframe.
  {
    gfx::Point rect_b_point(79, 79);
    gfx::Point rect_b_point_frame = CSSToFramePoint(rect_b_point);
    BrowserAccessibilityCocoa* hit_element =
        [root accessibilityHitTest:NSMakePoint(rect_b_point_frame.x(),
                                               rect_b_point_frame.y())];
    BrowserAccessibilityCocoa* expected_element = ToBrowserAccessibilityCocoa(
        FindNode(ax::mojom::Role::kGenericContainer, "rectB"));
    EXPECT_ACCESSIBILITY_MAC_HIT_TEST_RESULT(rect_b_point, expected_element,
                                             hit_element);
  }
}

}

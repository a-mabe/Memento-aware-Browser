// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "content/test/mock_widget_input_handler.h"

#include "base/run_loop.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "third_party/blink/public/common/input/web_gesture_event.h"
#include "third_party/blink/public/common/input/web_mouse_event.h"
#include "third_party/blink/public/common/input/web_mouse_wheel_event.h"
#include "third_party/blink/public/common/input/web_touch_event.h"

using base::TimeDelta;
using blink::WebGestureEvent;
using blink::WebInputEvent;
using blink::WebMouseEvent;
using blink::WebMouseWheelEvent;
using blink::WebTouchEvent;
using blink::WebTouchPoint;

namespace content {

MockWidgetInputHandler::MockWidgetInputHandler() = default;

MockWidgetInputHandler::MockWidgetInputHandler(
    mojo::PendingReceiver<blink::mojom::WidgetInputHandler> receiver,
    mojo::PendingRemote<blink::mojom::WidgetInputHandlerHost> host)
    : receiver_(this, std::move(receiver)), host_(std::move(host)) {}

MockWidgetInputHandler::~MockWidgetInputHandler() {
  // We explicitly close the binding before the tearing down the vector of
  // messages, as some of them may spin a RunLoop on destruction and we don't
  // want to accept more messages beyond this point.
  receiver_.reset();
}

void MockWidgetInputHandler::SetFocus(bool focused) {
  dispatched_messages_.emplace_back(
      std::make_unique<DispatchedFocusMessage>(focused));
}

void MockWidgetInputHandler::MouseCaptureLost() {
  dispatched_messages_.emplace_back(
      std::make_unique<DispatchedMessage>("MouseCaptureLost"));
}

void MockWidgetInputHandler::SetEditCommandsForNextKeyEvent(
    std::vector<blink::mojom::EditCommandPtr> commands) {
  dispatched_messages_.emplace_back(
      std::make_unique<DispatchedEditCommandMessage>(std::move(commands)));
}

void MockWidgetInputHandler::CursorVisibilityChanged(bool visible) {
  dispatched_messages_.emplace_back(
      std::make_unique<DispatchedMessage>("CursorVisibilityChanged"));
}

void MockWidgetInputHandler::ImeSetComposition(
    const base::string16& text,
    const std::vector<ui::ImeTextSpan>& ime_text_spans,
    const gfx::Range& range,
    int32_t start,
    int32_t end) {
  dispatched_messages_.emplace_back(std::make_unique<DispatchedIMEMessage>(
      "SetComposition", text, ime_text_spans, range, start, end));
}

void MockWidgetInputHandler::ImeCommitText(
    const base::string16& text,
    const std::vector<ui::ImeTextSpan>& ime_text_spans,
    const gfx::Range& range,
    int32_t relative_cursor_position,
    ImeCommitTextCallback callback) {
  dispatched_messages_.emplace_back(std::make_unique<DispatchedIMEMessage>(
      "CommitText", text, ime_text_spans, range, relative_cursor_position,
      relative_cursor_position));
  if (callback)
    std::move(callback).Run();
}

void MockWidgetInputHandler::ImeFinishComposingText(bool keep_selection) {
  dispatched_messages_.emplace_back(
      std::make_unique<DispatchedMessage>("FinishComposingText"));
}

void MockWidgetInputHandler::RequestTextInputStateUpdate() {
  dispatched_messages_.emplace_back(
      std::make_unique<DispatchedMessage>("RequestTextInputStateUpdate"));
}

void MockWidgetInputHandler::RequestCompositionUpdates(bool immediate_request,
                                                       bool monitor_request) {
  dispatched_messages_.emplace_back(
      std::make_unique<DispatchedRequestCompositionUpdatesMessage>(
          immediate_request, monitor_request));
}

void MockWidgetInputHandler::DispatchEvent(
    std::unique_ptr<blink::WebCoalescedInputEvent> event,
    DispatchEventCallback callback) {
  dispatched_messages_.emplace_back(std::make_unique<DispatchedEventMessage>(
      std::move(event), std::move(callback)));
}

void MockWidgetInputHandler::DispatchNonBlockingEvent(
    std::unique_ptr<blink::WebCoalescedInputEvent> event) {
  dispatched_messages_.emplace_back(std::make_unique<DispatchedEventMessage>(
      std::move(event), DispatchEventCallback()));
}

void MockWidgetInputHandler::WaitForInputProcessed(
    WaitForInputProcessedCallback callback) {
  NOTREACHED();
}

MockWidgetInputHandler::MessageVector
MockWidgetInputHandler::GetAndResetDispatchedMessages() {
  MessageVector dispatched_events;
  dispatched_messages_.swap(dispatched_events);
  return dispatched_events;
}

void MockWidgetInputHandler::AttachSynchronousCompositor(
    mojo::PendingRemote<blink::mojom::SynchronousCompositorControlHost>
        control_host,
    mojo::PendingAssociatedRemote<blink::mojom::SynchronousCompositorHost> host,
    mojo::PendingAssociatedReceiver<blink::mojom::SynchronousCompositor>
        compositor_request) {}

void MockWidgetInputHandler::GetFrameWidgetInputHandler(
    mojo::PendingAssociatedReceiver<blink::mojom::FrameWidgetInputHandler>
        interface_request) {}

MockWidgetInputHandler::DispatchedMessage::DispatchedMessage(
    const std::string& name)
    : name_(name) {}

MockWidgetInputHandler::DispatchedMessage::~DispatchedMessage() {}

MockWidgetInputHandler::DispatchedEditCommandMessage*
MockWidgetInputHandler::DispatchedMessage::ToEditCommand() {
  return nullptr;
}
MockWidgetInputHandler::DispatchedEventMessage*
MockWidgetInputHandler::DispatchedMessage::ToEvent() {
  return nullptr;
}
MockWidgetInputHandler::DispatchedFocusMessage*
MockWidgetInputHandler::DispatchedMessage::ToFocus() {
  return nullptr;
}
MockWidgetInputHandler::DispatchedIMEMessage*
MockWidgetInputHandler::DispatchedMessage::ToIME() {
  return nullptr;
}

MockWidgetInputHandler::DispatchedRequestCompositionUpdatesMessage*
MockWidgetInputHandler::DispatchedMessage::ToRequestCompositionUpdates() {
  return nullptr;
}

MockWidgetInputHandler::DispatchedIMEMessage::DispatchedIMEMessage(
    const std::string& name,
    const base::string16& text,
    const std::vector<ui::ImeTextSpan>& text_spans,
    const gfx::Range& range,
    int32_t start,
    int32_t end)
    : DispatchedMessage(name),
      text_(text),
      text_spans_(text_spans),
      range_(range),
      start_(start),
      end_(end) {}

MockWidgetInputHandler::DispatchedIMEMessage::~DispatchedIMEMessage() {}

MockWidgetInputHandler::DispatchedIMEMessage*
MockWidgetInputHandler::DispatchedIMEMessage::ToIME() {
  return this;
}

bool MockWidgetInputHandler::DispatchedIMEMessage::Matches(
    const base::string16& text,
    const std::vector<ui::ImeTextSpan>& ime_text_spans,
    const gfx::Range& range,
    int32_t start,
    int32_t end) const {
  return text_ == text && text_spans_ == ime_text_spans && range_ == range &&
         start_ == start && end_ == end;
}

MockWidgetInputHandler::DispatchedEditCommandMessage::
    DispatchedEditCommandMessage(
        std::vector<blink::mojom::EditCommandPtr> commands)
    : DispatchedMessage("SetEditComamnds"), commands_(std::move(commands)) {}

MockWidgetInputHandler::DispatchedEditCommandMessage::
    ~DispatchedEditCommandMessage() {}
MockWidgetInputHandler::DispatchedEditCommandMessage*
MockWidgetInputHandler::DispatchedEditCommandMessage::ToEditCommand() {
  return this;
}

const std::vector<blink::mojom::EditCommandPtr>&
MockWidgetInputHandler::DispatchedEditCommandMessage::Commands() const {
  return commands_;
}

MockWidgetInputHandler::DispatchedEventMessage::DispatchedEventMessage(
    std::unique_ptr<blink::WebCoalescedInputEvent> event,
    DispatchEventCallback callback)
    : DispatchedMessage(
          blink::WebInputEvent::GetName(event->Event().GetType())),
      event_(std::move(event)),
      callback_(std::move(callback)) {}

MockWidgetInputHandler::DispatchedEventMessage::~DispatchedEventMessage() {
  if (callback_) {
    std::move(callback_).Run(
        blink::mojom::InputEventResultSource::kUnknown, ui::LatencyInfo(),
        blink::mojom::InputEventResultState::kNotConsumed, nullptr, nullptr);
    base::RunLoop().RunUntilIdle();
  }
}

MockWidgetInputHandler::DispatchedEventMessage*
MockWidgetInputHandler::DispatchedEventMessage::ToEvent() {
  return this;
}

void MockWidgetInputHandler::DispatchedEventMessage::CallCallback(
    blink::mojom::InputEventResultState state) {
  if (callback_) {
    std::move(callback_).Run(blink::mojom::InputEventResultSource::kMainThread,
                             ui::LatencyInfo(), state, nullptr, nullptr);
    base::RunLoop().RunUntilIdle();
  }
}

void MockWidgetInputHandler::DispatchedEventMessage::CallCallback(
    blink::mojom::InputEventResultSource source,
    const ui::LatencyInfo& latency_info,
    blink::mojom::InputEventResultState state,
    blink::mojom::DidOverscrollParamsPtr overscroll,
    blink::mojom::TouchActionOptionalPtr touch_action) {
  if (callback_) {
    std::move(callback_).Run(source, latency_info, state, std::move(overscroll),
                             std::move(touch_action));
    base::RunLoop().RunUntilIdle();
  }
}

bool MockWidgetInputHandler::DispatchedEventMessage::HasCallback() const {
  return !!callback_;
}

const blink::WebCoalescedInputEvent*
MockWidgetInputHandler::DispatchedEventMessage::Event() const {
  return event_.get();
}

MockWidgetInputHandler::DispatchedRequestCompositionUpdatesMessage::
    DispatchedRequestCompositionUpdatesMessage(bool immediate_request,
                                               bool monitor_request)
    : DispatchedMessage("RequestCompositionUpdates"),
      immediate_request_(immediate_request),
      monitor_request_(monitor_request) {}

MockWidgetInputHandler::DispatchedRequestCompositionUpdatesMessage::
    ~DispatchedRequestCompositionUpdatesMessage() {}

MockWidgetInputHandler::DispatchedRequestCompositionUpdatesMessage*
MockWidgetInputHandler::DispatchedRequestCompositionUpdatesMessage::
    ToRequestCompositionUpdates() {
  return this;
}

MockWidgetInputHandler::DispatchedFocusMessage::DispatchedFocusMessage(
    bool focused)
    : DispatchedMessage("SetFocus"), focused_(focused) {}

MockWidgetInputHandler::DispatchedFocusMessage::~DispatchedFocusMessage() {}

MockWidgetInputHandler::DispatchedFocusMessage*
MockWidgetInputHandler::DispatchedFocusMessage::ToFocus() {
  return this;
}

}  // namespace content

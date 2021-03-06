// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "ui/gtk/x/gtk_event_loop_x11.h"

#include <gdk/gdk.h>
#include <gdk/gdkx.h>
#include <gtk/gtk.h>
#include <xcb/xcb.h>
#include <xcb/xproto.h>

#include "base/memory/singleton.h"
#include "ui/events/platform/x11/x11_event_source.h"
#include "ui/gfx/x/event.h"
#include "ui/gfx/x/x11.h"

namespace ui {

namespace {

int BuildXkbStateFromGdkEvent(unsigned int state, unsigned char group) {
  return state | ((group & 0x3) << 13);
}

}  // namespace

// static
GtkEventLoopX11* GtkEventLoopX11::EnsureInstance() {
  return base::Singleton<GtkEventLoopX11>::get();
}

GtkEventLoopX11::GtkEventLoopX11() {
  gdk_event_handler_set(DispatchGdkEvent, nullptr, nullptr);
}

GtkEventLoopX11::~GtkEventLoopX11() {
  gdk_event_handler_set(reinterpret_cast<GdkEventFunc>(gtk_main_do_event),
                        nullptr, nullptr);
}

// static
void GtkEventLoopX11::DispatchGdkEvent(GdkEvent* gdk_event, gpointer) {
  switch (gdk_event->type) {
    case GDK_KEY_PRESS:
    case GDK_KEY_RELEASE:
      ProcessGdkEventKey(gdk_event->key);
      break;
    default:
      break;  // Do nothing.
  }

  gtk_main_do_event(gdk_event);
}

// static
void GtkEventLoopX11::ProcessGdkEventKey(const GdkEventKey& gdk_event_key) {
  // This function translates GdkEventKeys into XKeyEvents and puts them to
  // the X event queue.
  //
  // base::MessagePumpX11 is using the X11 event queue and all key events should
  // be processed there.  However, there are cases(*1) that GdkEventKeys are
  // created instead of XKeyEvents.  In these cases, we have to translate
  // GdkEventKeys to XKeyEvents and puts them to the X event queue so our main
  // event loop can handle those key events.
  //
  // (*1) At least ibus-gtk in async mode creates a copy of user's key event and
  // pushes it back to the GDK event queue.  In this case, there is no
  // corresponding key event in the X event queue.  So we have to handle this
  // case.  ibus-gtk is used through gtk-immodule to support IMEs.

  auto* conn = x11::Connection::Get();
  XDisplay* display = conn->display();

  xcb_generic_event_t generic_event;
  memset(&generic_event, 0, sizeof(generic_event));
  auto* key_event = reinterpret_cast<xcb_key_press_event_t*>(&generic_event);
  key_event->response_type = gdk_event_key.type == GDK_KEY_PRESS
                                 ? x11::KeyEvent::Press
                                 : x11::KeyEvent::Release;
  if (gdk_event_key.send_event)
    key_event->response_type |= x11::kSendEventMask;
  key_event->event = GDK_WINDOW_XID(gdk_event_key.window);
  key_event->root = DefaultRootWindow(display);
  key_event->time = gdk_event_key.time;
  key_event->detail = gdk_event_key.hardware_keycode;
  key_event->same_screen = true;

  x11::Event event(&generic_event, conn, false);

  // The key state is 16 bits on the wire, but ibus-gtk adds additional flags
  // that may be outside this range, so set the state after conversion from
  // the wire format.
  // TODO(https://crbug.com/1066670): Add a test to ensure this subtle logic
  // doesn't regress after all X11 event code is refactored from using Xlib to
  // XProto.
  int state =
      BuildXkbStateFromGdkEvent(gdk_event_key.state, gdk_event_key.group);
  event.xlib_event().xkey.state = state;
  event.As<x11::KeyEvent>()->state = static_cast<x11::KeyButMask>(state);

  // We want to process the gtk event; mapped to an X11 event immediately
  // otherwise if we put it back on the queue we may get items out of order.
  if (ui::X11EventSource* x11_source = ui::X11EventSource::GetInstance())
    x11_source->DispatchXEvent(&event);
  else
    conn->events().push_front(std::move(event));
}

}  // namespace ui

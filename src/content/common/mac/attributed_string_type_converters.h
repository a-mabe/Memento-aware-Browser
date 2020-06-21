// Copyright (c) 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CONTENT_COMMON_MAC_ATTRIBUTED_STRING_TYPE_CONVERTERS_H_
#define CONTENT_COMMON_MAC_ATTRIBUTED_STRING_TYPE_CONVERTERS_H_

#include "base/strings/string16.h"
#include "content/common/content_export.h"
#include "ipc/ipc_message_utils.h"
#include "ui/base/mojom/attributed_string.mojom.h"
#include "ui/gfx/range/range.h"

#if __OBJC__
@class NSAttributedString;
#else
class NSAttributedString;
#endif

namespace base {
class Pickle;
class PickleIterator;
}

namespace mojo {

template <>
struct CONTENT_EXPORT
    TypeConverter<NSAttributedString*, ui::mojom::AttributedStringPtr> {
  static NSAttributedString* Convert(
      const ui::mojom::AttributedStringPtr& mojo_attributed_string);
};

template <>
struct CONTENT_EXPORT
    TypeConverter<ui::mojom::AttributedStringPtr, NSAttributedString*> {
  static ui::mojom::AttributedStringPtr Convert(
      const NSAttributedString* ns_attributed_string);
};

}  // namespace mojo

// IPC ParamTraits specialization //////////////////////////////////////////////

namespace IPC {

template <>
struct ParamTraits<ui::mojom::FontAttributePtr> {
  typedef ui::mojom::FontAttributePtr param_type;
  static void Write(base::Pickle* m, const param_type& p);
  static bool Read(const base::Pickle* m,
                   base::PickleIterator* iter,
                   param_type* r);
  static void Log(const param_type& p, std::string* l);
};

}  // namespace IPC

#endif  // CONTENT_COMMON_MAC_ATTRIBUTED_STRING_TYPE_CONVERTERS_H_

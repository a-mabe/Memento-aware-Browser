// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_CHROMEOS_INPUT_METHOD_EMOJI_SUGGESTER_H_
#define CHROME_BROWSER_CHROMEOS_INPUT_METHOD_EMOJI_SUGGESTER_H_

#include <string>

#include "chrome/browser/chromeos/input_method/input_method_engine.h"
#include "chrome/browser/chromeos/input_method/input_method_engine_base.h"
#include "chrome/browser/chromeos/input_method/suggester.h"
#include "chrome/browser/chromeos/input_method/suggestion_enums.h"

namespace chromeos {

// An agent to suggest emoji when the user types, and adopt or
// dismiss the suggestion according to the user action.
class EmojiSuggester : public Suggester {
 public:
  explicit EmojiSuggester(InputMethodEngine* engine);
  ~EmojiSuggester() override;

  // Suggester overrides:
  void OnFocus(int context_id) override;
  void OnBlur() override;
  SuggestionStatus HandleKeyEvent(
      const InputMethodEngineBase::KeyboardEvent& event) override;
  bool Suggest(const base::string16& text) override;
  void DismissSuggestion() override;
  AssistiveType GetProposeActionType() override;

  void LoadEmojiMapForTesting(const std::string& emoji_data);
  bool GetSuggestionShownForTesting() const;

 private:
  void ShowSuggestion(const std::string& text);
  void LoadEmojiMap();
  void OnEmojiDataLoaded(const std::string& emoji_data);
  void RecordAcceptanceIndex(int index);

  InputMethodEngine* const engine_;

  // ID of the focused text field, 0 if none is focused.
  int context_id_ = -1;

  // If we are showing a suggestion right now.
  bool suggestion_shown_ = false;

  // The current list of candidates.
  std::vector<base::string16> candidates_;
  AssistiveWindowProperties properties_;

  // The current candidate_id chosen.
  int candidate_id_ = -1;

  // The map holding one-word-mapping to emojis.
  std::map<std::string, std::vector<base::string16>> emoji_map_;

  // Pointer for callback, must be the last declared in the file.
  base::WeakPtrFactory<EmojiSuggester> weak_factory_{this};
};

}  // namespace chromeos

#endif  // CHROME_BROWSER_CHROMEOS_INPUT_METHOD_EMOJI_SUGGESTER_H_

// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CONTENT_COMMON_STATE_TRANSITIONS_H_
#define CONTENT_COMMON_STATE_TRANSITIONS_H_

#include <vector>

#include "base/logging.h"
#include "base/no_destructor.h"
#include "base/stl_util.h"

namespace content {

// This class represents a set of state transitions where each state is a value
// that supports copy, << and == (e.g. an enum element). It's intended to be
// used in DCHECK-enabled builds to check that only valid transitions occur. Its
// implementation favours convenience and simplicity over performance. To use it
// follow this example:

// In foo.h
// ---------
// enum class State {
//   kState1,
//   kState2,
//   kState3,
// };
//
// // This may require exporting the symbol (e.g. CONTENT_EXPORT) if it will be
// // used by any other components: one common way this can happen is if the
// // enum is logged in tests (e.g. via gtest's EXPECT_* macros).
// std::ostream& operator<<(std::ostream& o, const State& s);
// ---------
//
// In foo.cc
// ---------
// #include "base/no_destructor.h"
// #include "content/common/state_transitions.h"
//
// std::ostream& operator<<(std::ostream& o, const State& s) {
//   return o << static_cast<int>(s);
// }
//
// void DCheckStateTransition(State old_state, State new_state) {
// #if DCHECK_IS_ON()
//   static const base::NoDestructor<StateTransitions<State>>
//       transitions(
//           StateTransitions<State>(
//               {{kState1, {kState2, kState3}},
//               {kState2, {kState3}},
//               {kState3, {}}}));
//   DCHECK_STATE_TRANSITION(transitions, old_state, new_state);
// #endif  // DCHECK_IS_ON()
// }
// ---------

template <typename State>
struct StateTransitions {
 public:
  // Represents a state and all of the states that are valid transitions from
  // it.
  struct StateTransition {
    StateTransition(State source, std::vector<State> destinations)
        : source(std::move(source)), destinations(std::move(destinations)) {}

    const State source;
    const std::vector<State> destinations;
  };

  explicit StateTransitions(std::vector<StateTransition> state_transitions)
      : state_transitions(std::move(state_transitions)) {}

  // Returns a list of states that are valid to transition to from |source|.
  const std::vector<State>& GetValidTransitions(const State& source) const {
    for (const StateTransition& state_transition : state_transitions) {
      if (state_transition.source == source)
        return state_transition.destinations;
    }
    static const base::NoDestructor<std::vector<State>> no_transitions;
    return *no_transitions;
  }

  // Tests whether transitioning from |source| to |destination| is valid.
  bool IsTransitionValid(const State& source, const State& destination) const {
    return base::Contains(GetValidTransitions(source), destination);
  }

  const std::vector<StateTransition> state_transitions;
};

// DCHECK if transitioning from |old_state| to |new_state| is not valid
// according to |transitions|.
#define DCHECK_STATE_TRANSITION(transitions, old_state, new_state)   \
  DCHECK((transitions)->IsTransitionValid((old_state), (new_state))) \
      << "Invalid transition: " << old_state << " -> " << new_state

}  // namespace content

#endif  // CONTENT_COMMON_STATE_TRANSITIONS_H_

package edu.uw.ubicomp.apolloproto;

import android.content.Context;

import java.util.List;

/**
 * Something that writes Apollo's chat replies with a language model. Returns null when it can't answer right now (no key,
 * offline, a bad reply), and the chat falls back to {@link ApolloBrain}'s on-phone answers. Emergencies never reach a
 * Brain: ChatPage answers them on the phone first. GeminiBrain is the one in use; a local model (on-device Gemma through
 * MediaPipe, or Ollama on a laptop) would be another implementation.
 */
interface Brain {
    /** Runs off the main thread. `history` ends with the user's new message. */
    ApolloBrain.Msg reply(Context c, List<ApolloBrain.Msg> history, long stepsToday);

    /** A short name for the chat header and the "Based on" line. */
    String name();
}

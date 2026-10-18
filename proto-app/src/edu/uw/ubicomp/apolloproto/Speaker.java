package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Spoken cues and a buzz, for steps where the user can't look at the screen. */
final class Speaker implements TextToSpeech.OnInitListener {
    private final TextToSpeech tts;
    private final Vibrator vibrator;
    private boolean ready;
    private String pending;

    Speaker(Context c) {
        tts = new TextToSpeech(c, this);
        vibrator = c.getSystemService(Vibrator.class);
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            tts.setLanguage(Locale.US);
            if (pending != null) say(pending);
            pending = null;
        }
    }

    void say(String text) {
        if (!ready) {
            pending = text;
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "apollo");
    }

    void buzz() {
        if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    void shutdown() {
        tts.stop();
        tts.shutdown();
    }
}

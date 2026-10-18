package edu.uw.ubicomp.mictest;

import android.app.Activity;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Records mono 16-bit 48 kHz audio from one AudioSource with every pre-processing effect disabled,
 * and writes <name>.wav plus <name>.txt (source, routed mic, effect states) to the app's files dir.
 * Usage: am start -n edu.uw.ubicomp.mictest/.MicTestActivity --ei source 6 --ei seconds 30 --es name vr
 */
public class MicTestActivity extends Activity {
    private static final int RATE = 48000;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setTextSize(24);
        setContentView(view);
        int source = getIntent().getIntExtra("source", 6);
        int seconds = getIntent().getIntExtra("seconds", 30);
        String name = getIntent().getStringExtra("name");
        if (name == null) name = "source" + source;
        view.setText("Recording " + name + " for " + seconds + " s");
        String n = name;
        new Thread(() -> {
            String status;
            try {
                record(source, seconds, n);
                status = "done: " + n;
            } catch (Exception e) {
                status = "error: " + e;
                writeText(n + ".txt", "error=" + e);
            }
            String s = status;
            runOnUiThread(() -> { view.setText(s); finish(); });
        }, "recorder").start();
    }

    private void record(int source, int seconds, String name) throws IOException {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min, RATE / 5 * 2) * 2)
                .build();
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("AudioRecord not initialized");

        int session = rec.getAudioSessionId();
        StringBuilder info = new StringBuilder();
        info.append("source=").append(source).append('\n');
        info.append("sampleRate=").append(rec.getSampleRate()).append('\n');
        info.append("unprocessedProperty=").append(getSystemService(AudioManager.class)
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)).append('\n');
        info.append(effect("agc", AutomaticGainControl.isAvailable(), () -> AutomaticGainControl.create(session)));
        info.append(effect("ns", NoiseSuppressor.isAvailable(), () -> NoiseSuppressor.create(session)));
        info.append(effect("aec", AcousticEchoCanceler.isAvailable(), () -> AcousticEchoCanceler.create(session)));

        int total = RATE * seconds;
        short[] pcm = new short[total];
        rec.startRecording();
        int got = 0;
        while (got < total) {
            int r = rec.read(pcm, got, Math.min(4800, total - got));
            if (r < 0) throw new IOException("read error " + r);
            got += r;
        }
        AudioDeviceInfo dev = rec.getRoutedDevice();
        if (dev != null) {
            info.append("routedType=").append(dev.getType()).append('\n');
            info.append("routedAddress=").append(dev.getAddress()).append('\n');
        }
        rec.stop();
        rec.release();

        writeWav(new File(getExternalFilesDir(null), name + ".wav"), pcm);
        writeText(name + ".txt", info.toString());
    }

    private interface EffectFactory { android.media.audiofx.AudioEffect create(); }

    /** Creates the effect on this session if the device offers it, forces it off, and reports the result. */
    private static String effect(String label, boolean available, EffectFactory factory) {
        if (!available) return label + "=unavailable\n";
        try {
            android.media.audiofx.AudioEffect fx = factory.create();
            if (fx == null) return label + "=create_failed\n";
            boolean before = fx.getEnabled();
            fx.setEnabled(false);
            return label + "=available enabledByDefault=" + before + " nowEnabled=" + fx.getEnabled() + "\n";
        } catch (Exception e) {
            return label + "=error " + e + "\n";
        }
    }

    private void writeText(String file, String text) {
        try (PrintWriter w = new PrintWriter(new File(getExternalFilesDir(null), file))) {
            w.print(text);
        } catch (IOException ignored) {
        }
    }

    private static void writeWav(File f, short[] pcm) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length * 2);
        for (short s : pcm) b.putShort(s);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
    }
}

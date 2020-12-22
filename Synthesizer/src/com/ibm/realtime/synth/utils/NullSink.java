package com.ibm.realtime.synth.utils;

import com.ibm.realtime.synth.engine.AudioPullThread;
import com.ibm.realtime.synth.engine.AudioSink;
import com.ibm.realtime.synth.engine.AudioTime;
import com.ibm.realtime.synth.engine.Synthesizer;

import org.jsresources.utils.audio.AudioUtils;
import org.tritonus.android.sampled.AudioFormat;
import org.tritonus.share.sampled.AudioBuffer;

public class NullSink implements AudioSink {
    private long writtenSamples = 0;
    private long clockOffsetSamples = 0;
    private long stopTimeSamples = -1;
    private static int serviceIntervalMillis = 100;
    private long serviceIntervalSamples = 0;
    private long nextServiceSamples;
    private int latencyInMillis = 100;
    private AudioFormat audioFormat;

    public AudioPullThread pullThread = null;
    public Synthesizer synth = null;

    public NullSink(AudioFormat format) {
        audioFormat = format;
    }

    public void reset() {
        writtenSamples = 0;
        clockOffsetSamples = 0;
        serviceIntervalSamples =
                AudioUtils.millis2samples(serviceIntervalMillis,
                        getSampleRate());
        nextServiceSamples = serviceIntervalSamples;
    }

    public void setStopTime(float timeInSeconds) {
        stopTimeSamples =
                AudioUtils.seconds2samples(timeInSeconds, getSampleRate());
    }

    public int getBufferSize() {
        return (int) AudioUtils.millis2samples(latencyInMillis,
                getSampleRate());
    }

    public int getBufferSizeMillis() {
        return latencyInMillis;
    }

    public int getChannels() {
        return audioFormat.getChannels();
    }

    public float getSampleRate() {
        return audioFormat.getSampleRate();
    }

    public boolean isOpen() {
        return true;
    }

    public void close() {
        // nothing to do
    }

    public void write(AudioBuffer buffer) {
        writtenSamples += buffer.getSampleCount();
        // if we passed stopTime, stop the PullThread
        if (writtenSamples > stopTimeSamples) {
            if (pullThread != null) {
                // be careful with synchronization!
                pullThread.stop();
            }
        } else if (writtenSamples > nextServiceSamples) {
            if (synth != null) {
                synth.getMixer().service();
            }
            while (writtenSamples > nextServiceSamples) {
                nextServiceSamples += serviceIntervalSamples;
            }
        }
    }

    public AudioTime getWrittenTime() {
        return new AudioTime(writtenSamples, getSampleRate());
    }

    public AudioTime getAudioTime() {
        return new AudioTime(writtenSamples + clockOffsetSamples,
                getSampleRate());
    }

    public AudioTime getTimeOffset() {
        return new AudioTime(clockOffsetSamples, getSampleRate());
    }

    public void setTimeOffset(AudioTime offset) {
        this.clockOffsetSamples = offset.getSamplesTime(getSampleRate());
    }
}

/*
 * (C) Copyright IBM Corp. 2005, 2008
 * All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.ibm.realtime.synth.modules;

import android.media.AudioManager;
import android.media.AudioTrack;

import com.ibm.realtime.synth.engine.*;

import org.jsresources.utils.audio.AudioUtils;
import org.tritonus.android.sampled.AudioFormat;
import org.tritonus.share.sampled.AudioBuffer;

import static com.ibm.realtime.synth.utils.Debug.*;
import static org.jsresources.utils.audio.AudioUtils.samples2nanos;

/**
 * An AudioSink that writes to an Android AudioTrack.
 */
public class AudioTrackSink implements AudioSink, AdjustableAudioClock {

    private static boolean DEBUG_SINK = true;
    private static boolean EMULATE_TIME = true;

    /**
     * The AudioTrack used
     */
    private AudioTrack sdl;

    /**
     * The current (or previous) sample rate.
     */
    private AudioFormat audioFormat = AudioFormat.create16bitLE(2, 44100.0f);

    /**
     * A temporary byte buffer for conversion to the native format
     */
    private byte[] byteBuffer;

    private int bufferSizeSamples;

    /**
     * Flag to track if the audio device was started
     */
    private boolean started;

    /**
     * The current offset of the audio clock (interface AdjustableAudioClock) in
     * samples.
     */
    private long clockOffsetSamples;

    private long emulatedStartTimeNanos = 0;
    private long clockOffsetNanos = 0;

    /**
     * Constructor for this sink
     */
    public AudioTrackSink() {
        // nothing to do
    }

    /**
     * Open the sound device with the given format and buffer size in milliseconds.
     * The audio time (regardless of the time offset) will be reset to 0.
     */
    public synchronized void open(int bufferSizeInMillis,
                                  AudioFormat format) {
        open(format, (int) AudioUtils.millis2samples(
                bufferSizeInMillis, format.getSampleRate()));
    }

    /**
     * Open the sound device with the given format. The audio time (regardless of
     * the time offset) will be reset to 0.
     */
    public synchronized void open(AudioFormat format,
                                  int bufferSizeInSamples) {
        this.audioFormat = format;
        this.bufferSizeSamples = bufferSizeInSamples;
        sdl = new AudioTrack(AudioManager.STREAM_MUSIC,
                (int) format.getSampleRate(),
                (format.getChannels() == 1) ? android.media.AudioFormat.CHANNEL_OUT_MONO : android.media.AudioFormat.CHANNEL_OUT_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInSamples * format.getFrameSize(),
                AudioTrack.MODE_STREAM);

        // requires Android 6.0 level 23
        //bufferSizeSamples = sdl.getBufferSizeInFrames();

        if (DEBUG_SINK) {
            debug("Buffer size = "
                    + bufferSizeSamples
                    + " samples = "
                    + format2(AudioUtils.samples2micros(bufferSizeSamples, format.getSampleRate()) / 1000.0)
                    + " millis");
        }
    }

    public synchronized void close() {
        started = false;
        if (sdl != null) {
            sdl.stop();
            sdl.release();
            sdl = null;
        }
        if (DEBUG_SINK) debug("closed sound device");
    }

    public boolean isOpen() {
        return (sdl != null);
    }

    public AudioFormat getFormat() {
        return audioFormat;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.realtime.synth.engine.AudioSink#write(com.ibm.realtime.synth.engine.AudioBuffer)
     */
    public synchronized void write(AudioBuffer buffer) {
        if (!isOpen()) {
            return;
        }

        // set up the temporary buffer that receives the converted
        // samples in bytes
        int requiredSize = buffer.getByteArrayBufferSize(getFormat());
        if (byteBuffer == null || byteBuffer.length < requiredSize) {
            byteBuffer = new byte[requiredSize];
        }

        // if the device is not started, start it
        if (!started) {
            sdl.play();
            started = true;
            if (EMULATE_TIME) {
                // time of writing first buffer
                emulatedStartTimeNanos = System.nanoTime();
            }
        }

        // TODO: use write(short[], offset, length)
        int length = buffer.convertToByteArray(byteBuffer, 0, getFormat());
        // blocking call
        sdl.write(byteBuffer, 0, length);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.realtime.synth.engine.AudioClock#getClockTime()
     */
    public AudioTime getAudioTime() {
        if (sdl != null) {
            AudioTime time;
            if (EMULATE_TIME) {
                if (started) {
                    time = new AudioTime(System.nanoTime() - emulatedStartTimeNanos + clockOffsetNanos);
                } else {
                    time = new AudioTime(clockOffsetNanos);
                }
            } else {
                time = new AudioTime(sdl.getPlaybackHeadPosition()
                        + clockOffsetSamples, getSampleRate());
            }
            //if (DEBUG_SINK) {
            //    debug("AudioTrackSink: current time: " + time);
            //}
            return time;
        } else {
            return new AudioTime(clockOffsetSamples, getSampleRate());
        }
    }

    public int getBufferSizeMillis() {
        float sr = getSampleRate();
        if (sr == 0.0f) {
            return 0;
        }
        return (int) (getBufferSize() * 1000 / getSampleRate());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#getTimeOffset()
     */
    public AudioTime getTimeOffset() {
        return new AudioTime(clockOffsetSamples, getSampleRate());
    }

    /**
     * Set the clock offset. This offset is internally stored in samples, so you
     * should only call it when this sink is open, or has already been open with
     * the correct sample rate.
     *
     * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#setTimeOffset(com.ibm.realtime.synth.engine.AudioTime)
     */
    public void setTimeOffset(AudioTime offset) {
        this.clockOffsetSamples = offset.getSamplesTime(getSampleRate());
        this.clockOffsetNanos = offset.getNanoTime();
        if (DEBUG_SINK) {
            debug("AudioTrackSink: clock offset now " + offset);
        }
    }

    /*
     * (non-Javadoc) @return the buffer size of this sink in samples
     *
     * @see com.ibm.realtime.synth.engine.AudioSink#getBufferSize()
     */
    public int getBufferSize() {
        if (sdl != null) {
            return bufferSizeSamples;
        } else {
            return 1024; // something arbitrary
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.realtime.synth.engine.AudioSink#getChannels()
     */
    public int getChannels() {
        return getFormat().getChannels();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.realtime.synth.engine.AudioSink#getSampleRate()
     */
    public float getSampleRate() {
        return getFormat().getSampleRate();
    }

    /**
     * @return the name of the open device, or a generic name if not open
     */
    public String getName() {
        return "AudioTrackSink";
    }
}

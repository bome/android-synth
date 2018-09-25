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

import static com.ibm.realtime.synth.utils.Debug.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

import com.ibm.realtime.synth.engine.*;
import com.ibm.realtime.synth.engine.MidiEvent;

import org.tritonus.android.midi.*;

/**
 * A class to provide MIDI Input from a Standard MIDI File (SMF)
 * 
 * @author florian
 */
public class SMFMidiIn implements MidiIn, MetaEventListener, MidiDevice.Listener {

	public static boolean DEBUG = true;
	public static boolean DEBUG_SMF_MIDI_IN = true;

	/**
	 * If false, use the time of the incoming MIDI events, otherwise push MIDI
	 * events to the listener with time 0, i.e. "now"
	 */
	private boolean removeTimestamps = false;

	/**
	 * The Sequencer to use for dispatching
	 */
	private JavaSequencer sequencer;

	/**
	 * The Sequence containing the MIDI file
	 */
	private Sequence sequence;

	/**
	 * The currently open file
	 */
	private File file;

	/**
	 * the offset of the clock in nanoseconds (interface AdjustableAudioClock)
	 */
	private long clockOffset = 0;

	/**
	 * A listener to receive an event when playback stops
	 */
	private SMFMidiInListener stopListener;
	
	/**
	 * the device index
	 */
	private int devIndex = 0;

	/**
	 * The listeners that will receive incoming MIDI messages.
	 */
	private List<Listener> listeners = new ArrayList<Listener>();

	/**
	 * Create an SMF MidiIn instance.
	 */
	public SMFMidiIn() {
	}

	public void addListener(Listener L) {
		synchronized (listeners) {
			this.listeners.add(L);
		}
	}

	public void removeListener(Listener L) {
		synchronized (listeners) {
			this.listeners.remove(L);
		}
	}

	/**
	 * @return Returns the stopListener.
	 */
	public SMFMidiInListener getStopListener() {
		return stopListener;
	}

	/**
	 * @param stopListener The stopListener to set.
	 */
	public void setStopListener(SMFMidiInListener stopListener) {
		this.stopListener = stopListener;
	}

	/** if false, all MIDI events will have time stamp 0 */
	public void setTimestamping(boolean value) {
		removeTimestamps = !value;
	}

	/** @return the current status of time stamping MIDI events */
	public boolean isTimestamping() {
		return !removeTimestamps;
	}

	/**
	 * @return the currently loaded file, or <code>null</code>
	 */
	public File getFile() {
		return file;
	}

	public synchronized void open(File file) throws Exception {
		close();
		if (sequencer == null) {
			sequencer = new JavaSequencer();
			sequencer.addListener(this);
		}
		if (DEBUG_SMF_MIDI_IN) {
			debug("Opening MIDI file: " + file);
		}
		sequence = (new StandardMidiFileReader()).getSequence(file);
		if (DEBUG_SMF_MIDI_IN) {
			debug("Got MIDI sequence with " + sequence.getTracks().length
					+ " tracks. Duration: "
					+ format3(sequence.getMicrosecondLength() / 1000000.0)
					+ " seconds.");
		}
		sequencer.setSequence(sequence);
		// register a Meta Event listener that reacts on META event 47: End Of
		// File.
		sequencer.addMetaEventListener(this);
		sequencer.open();
		this.file = file;
		if (DEBUG_SMF_MIDI_IN) {
			debug("Sequencer opened and connected.");
		}
	}

	public synchronized void close() {
		if (sequence != null) {
			sequence = null;
		}
		if (sequencer != null) {
			sequencer.close();
			if (DEBUG_SMF_MIDI_IN) {
				debug("Closed Sequencer.");
			}
		}
		file = null;
	}

	public synchronized boolean isOpen() {
		return (sequencer != null) && (sequencer.isOpen());
	}

	public synchronized boolean isStarted() {
		return isOpen() && sequencer.isRunning();
	}

	public synchronized void start() {
		if (isOpen()) {
			// if at the end, rewind
			if (sequencer.getTickPosition() >= sequencer.getTickLength()) {
				rewind();
			}
			sequencer.start();
			if (DEBUG_SMF_MIDI_IN) {
				debug("Started sequencer. Current position: "
						+ format3(sequencer.getMicrosecondPosition() / 1000000.0));
			}
		}
	}

	public synchronized void stop() {
		if (isOpen()) {
			sequencer.stop();
			if (DEBUG_SMF_MIDI_IN) {
				debug("Stopped sequencer. Current position: "
						+ format3(sequencer.getMicrosecondPosition() / 1000000.0));
			}
		}
	}

	public synchronized void rewind() {
		if (isOpen()) {
			sequencer.setTickPosition(0);
		}
	}

	public synchronized void windSeconds(double seconds) {
		if (isOpen()) {
			long newMicroPos = sequencer.getMicrosecondPosition() + ((long) (seconds * 1000000.0));
			if (newMicroPos < 0) {
				newMicroPos = 0;
			} else if (newMicroPos > sequencer.getMicrosecondLength()) {
				newMicroPos = sequencer.getMicrosecondLength();
			}
			sequencer.setMicrosecondPosition(newMicroPos);
		}
	}

	/**
	 * Set the playback position to the percentage
	 * @param percent 0..100
	 */
	public synchronized void setPositionPercent(double percent) {
		if (isOpen()) {
			long tickLen = sequencer.getTickLength();
			long newTickPos = (long) (tickLen * percent / 100.0);
			if (newTickPos < 0) {
				newTickPos = 0;
			} else if (newTickPos > tickLen) {
				newTickPos = tickLen-1;
			}
			sequencer.setTickPosition(newTickPos);
		}
	}

	/**
	 * Get the playback position expressed as a percentage
	 * @return percent 0..100
	 */
	public synchronized double getPositionPercent() {
		if (isOpen()) {
			double tickLen = (double) sequencer.getTickLength();
			double tickPos = (double) sequencer.getTickPosition();
			double percent = (long) (tickPos * 100.0 / tickLen);
			return percent;
		}
		return 0.0;
	}

	public synchronized long getPlaybackPosMillis() {
		if (isOpen()) {
			return sequencer.getMicrosecondPosition() / 1000;
		}
		return 0;
	}

	public synchronized String getPlaybackPosBars() {
		if (isOpen()) {
			long tickPos = sequencer.getTickPosition(); 
			// last number is "frames"
			int frames = (int) tickPos % sequence.getResolution();
			// align frames to a 12 scale
			frames = ((frames * 12) / sequence.getResolution())+1;
			String sFrames;
			if (frames < 10) {
				sFrames = "0"+frames;
			} else {
				sFrames = Integer.toString(frames);
			}
			tickPos /= sequence.getResolution();
			// second number is beats
			int beat = (int) ((tickPos % 4)+1);
			// first number is bars, assume a 4/4 signature
			long bars = (tickPos / 4)+1;
			return Long.toString(bars)+":"+beat+"."+sFrames;
		}
		return "";
	}

	// interface MetaEventListener
	public void meta(MetaMessage event) {
		if (event.getType() == 47) {
			if (stopListener != null) {
				stopListener.onMidiPlaybackStop();
			}
		}
	}

	// interface AudioClock
	public AudioTime getAudioTime() {
		if (sequencer != null) {
			return new AudioTime((sequencer.getMicrosecondPosition() * 1000L)
					+ clockOffset);
		}
		return new AudioTime(0);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#getTimeOffset()
	 */
	public AudioTime getTimeOffset() {
		return new AudioTime(clockOffset);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.ibm.realtime.synth.engine.AdjustableAudioClock#setTimeOffset(com.ibm.realtime.synth.engine.AudioTime)
	 */
	public void setTimeOffset(AudioTime offset) {
		this.clockOffset = offset.getNanoTime();
	}

	public int getInstanceIndex() {
		return devIndex;
	}
	
	public void setDeviceIndex(int index) {
		this.devIndex = index;
	}

	/**
	 * Send a short message to the registered listeners.
	 *
	 * @param microTime the original device time of the event in microseconds
	 * @param status MIDI status byte
	 * @param data1 1st MIDI data byte
	 * @param data2 2nd MIDI data byte
	 */
	private void dispatchMessage(long microTime, int status, int data1,
								 int data2) {
		if (DEBUG) {
			debug("JavaSoundReceiver: time="+(microTime/1000)+"ms "
					+hexString(status, 2)+" "
					+hexString(data1, 2)+" "
					+hexString(data2, 2));
		}

		int channel;
		if (status < 0xF0) {
			// normal channel messages
			channel = status & 0x0F;
			status &= 0xF0;
		} else {
			// real time/system messages
			channel = 0;
		}
		long nanoTime;
		if (microTime == -1 || removeTimestamps) {
			if (removeTimestamps) {
				// let the receiver schedule!
				nanoTime = 0;
			} else {
				nanoTime = getAudioTime().getNanoTime();
			}
		} else {
			nanoTime =
					(microTime * 1000L) + getTimeOffset().getNanoTime();
		}
		synchronized (listeners) {
			for (MidiIn.Listener listener : listeners) {
				listener.midiInReceived(new MidiEvent(this, nanoTime, channel,
						status, data1, data2));
			}
		}
	}

	/**
	 * Send a long message to the registered listeners.
	 *
	 * @param microTime the original device time of the event in microseconds
	 * @param msg the actual message
	 */
	private void dispatchMessage(long microTime, byte[] msg) {
		if (DEBUG) {
			debug("JavaSoundReceiver: time="+(microTime/1000)+"ms "
					+" long msg, length="+msg.length);
		}
		long nanoTime;
		if (microTime == -1 || removeTimestamps) {
			if (removeTimestamps) {
				// let the receiver schedule!
				nanoTime = 0;
			} else {
				nanoTime = getAudioTime().getNanoTime();
			}
		} else {
			nanoTime =
					(microTime * 1000L) + getTimeOffset().getNanoTime();
		}
		synchronized (listeners) {
			for (MidiIn.Listener listener : listeners) {
				listener.midiInReceived(new MidiEvent(this, nanoTime, msg));
			}
		}
	}

	@Override
	public void onMIDIMessage(MidiDevice sender, MidiMessage message, long timeStamp) {
		// timestamp should be in microseconds
		if (message.getLength() <= 3) {
			if (message instanceof ShortMessage) {
				ShortMessage sm = (ShortMessage) message;
				dispatchMessage(timeStamp, sm.getStatus(), sm.getData1(),
						sm.getData2());
			} else {
				int data1 = 0;
				int data2 = 0;
				if (message.getLength() > 1) {
					byte[] msg = message.getMessage(false);
					data1 = msg[1] & 0xFF;
					if (message.getLength() > 2) {
						data2 = msg[2] & 0xFF;
					}
				}
				dispatchMessage(timeStamp, message.getStatus(), data1, data2);
			}
		} else {
			dispatchMessage(timeStamp, message.getMessage(false));
		}
	}

	public String toString() {
		if (isOpen()) {
			return "SMFMidiIn " + file;
		}
		return "SMFMidiIn";
	}


}

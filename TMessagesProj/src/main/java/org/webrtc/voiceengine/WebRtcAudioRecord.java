/*
 *  Copyright (c) 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc.voiceengine;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder.AudioSource;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;
import androidx.annotation.Nullable;
import java.lang.System;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.utils.ModConfig;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.webrtc.ContextUtils;
import org.webrtc.Logging;
import org.webrtc.ThreadUtils;

public class WebRtcAudioRecord {

  private static final String TAG = "WebRtcAudioRecord";

  // Default audio data format is PCM 16 bit per sample.
  // Guaranteed to be supported by all devices.
  private static final int BITS_PER_SAMPLE = 16;

  // Requested size of each recorded buffer provided to the client.
  private static final int CALLBACK_BUFFER_SIZE_MS = 10;

  // Average number of callbacks per second.
  private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;

  // We ask for a native buffer size of BUFFER_SIZE_FACTOR * (minimum required
  // buffer size). The extra space is allocated to guard against glitches under
  // high load.
  private static final int BUFFER_SIZE_FACTOR = 2;

  // The AudioRecordJavaThread is allowed to wait for successful call to join()
  // but the wait times out afther this amount of time.
  private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000;

  private static final int DEFAULT_AUDIO_SOURCE = getDefaultAudioSource();
  private static int audioSource = DEFAULT_AUDIO_SOURCE;

  private final long nativeAudioRecord;

  private @Nullable WebRtcAudioEffects effects;

  private ByteBuffer byteBuffer;
  private ByteBuffer deviceByteBuffer;

  private @Nullable AudioRecord audioRecord;
  private @Nullable AudioRecord deviceAudioRecord;
  private @Nullable AudioRecordThread audioThread;

  private static volatile boolean microphoneMute;
  private byte[] emptyBytes;

  private int captureType;

  private int requestedSampleRate = 48000;
  private int requestedChannels = 1;

  public static WebRtcAudioRecord Instance;

  // Audio recording error handler functions.
  public enum AudioRecordStartErrorCode {
    AUDIO_RECORD_START_EXCEPTION,
    AUDIO_RECORD_START_STATE_MISMATCH,
  }

  public interface WebRtcAudioRecordErrorCallback {
    void onWebRtcAudioRecordInitError(String errorMessage);
    void onWebRtcAudioRecordStartError(AudioRecordStartErrorCode errorCode, String errorMessage);
    void onWebRtcAudioRecordError(String errorMessage);
  }

  private static @Nullable WebRtcAudioRecordErrorCallback errorCallback;

  public static void setErrorCallback(WebRtcAudioRecordErrorCallback errorCallback) {
    Logging.d(TAG, "Set error callback");
    WebRtcAudioRecord.errorCallback = errorCallback;
  }

  /**
   * Contains audio sample information. Object is passed using {@link
   * WebRtcAudioRecord.WebRtcAudioRecordSamplesReadyCallback}
   */
  public static class AudioSamples {
    /** See {@link AudioRecord#getAudioFormat()} */
    private final int audioFormat;
    /** See {@link AudioRecord#getChannelCount()} */
    private final int channelCount;
    /** See {@link AudioRecord#getSampleRate()} */
    private final int sampleRate;

    private final byte[] data;

    private AudioSamples(AudioRecord audioRecord, byte[] data) {
      this.audioFormat = audioRecord.getAudioFormat();
      this.channelCount = audioRecord.getChannelCount();
      this.sampleRate = audioRecord.getSampleRate();
      this.data = data;
    }

    public int getAudioFormat() {
      return audioFormat;
    }

    public int getChannelCount() {
      return channelCount;
    }

    public int getSampleRate() {
      return sampleRate;
    }

    public byte[] getData() {
      return data;
    }
  }

  /** Called when new audio samples are ready. This should only be set for debug purposes */
  public interface WebRtcAudioRecordSamplesReadyCallback {
    void onWebRtcAudioRecordSamplesReady(AudioSamples samples);
  }

  private static @Nullable WebRtcAudioRecordSamplesReadyCallback audioSamplesReadyCallback;

  public static void setOnAudioSamplesReady(WebRtcAudioRecordSamplesReadyCallback callback) {
    audioSamplesReadyCallback = callback;
  }

  /**
   * Audio thread which keeps calling ByteBuffer.read() waiting for audio
   * to be recorded. Feeds recorded data to the native counterpart as a
   * periodic sequence of callbacks using DataIsRecorded().
   * This thread uses a Process.THREAD_PRIORITY_URGENT_AUDIO priority.
   */
  private class AudioRecordThread extends Thread {
    private static final int SELECTED_AUDIO_SAMPLE_RATE = 48000;
    private static final int SELECTED_AUDIO_CHUNK_BYTES = 960;
    private static final long CODEC_TIMEOUT_US = 10000;
    private volatile boolean keepAlive = true;
    private boolean selectedAudioFormatWarningShown;
    private final VoiceEffectsProcessor voiceEffectsProcessor = new VoiceEffectsProcessor();

    public AudioRecordThread(String name) {
      super(name);
    }

    // TODO(titovartem) make correct fix during webrtc:9175
    @SuppressWarnings("ByteBufferBackingArray")
    @Override
    public void run() {
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
      Logging.d(TAG, "AudioRecordThread" + WebRtcAudioUtils.getThreadInfo());

      long lastTime = System.nanoTime();
      while (keepAlive) {
        if (runSelectedAudioIfRequested()) {
          voiceEffectsProcessor.reset();
          continue;
        }
        int bytesRead = audioRecord.read(byteBuffer, byteBuffer.capacity());
        int deviceBytesRead;
        if (deviceAudioRecord != null) {
          deviceBytesRead = deviceAudioRecord.read(deviceByteBuffer, deviceByteBuffer.capacity());
        } else {
          deviceBytesRead = 0;
        }
        if (bytesRead == byteBuffer.capacity()) {
          if (microphoneMute) {
            voiceEffectsProcessor.reset();
            byteBuffer.clear();
            byteBuffer.put(emptyBytes);
          // Types 0 and 2 both contain live microphone PCM; type 1 is screen playback only.
          } else if (captureType != 1) {
            voiceEffectsProcessor.process(byteBuffer, bytesRead, ModConfig.getCurrentVoiceEffect());
          }
          if (bytesRead == deviceBytesRead) {
            deviceByteBuffer.position(0);
            byteBuffer.position(0);
            for (int a = 0; a < bytesRead / 2; a++) {
              int mixed = byteBuffer.getShort(a * 2) + deviceByteBuffer.getShort(a * 2) / 10;
              if (mixed > 32767) {
                mixed = 32767;
              }
              if (mixed < -32768) {
                mixed = -32768;
              }
              byteBuffer.putShort(a * 2, (short) mixed);
            }
          }
          // It's possible we've been shut down during the read, and stopRecording() tried and
          // failed to join this thread. To be a bit safer, try to avoid calling any native methods
          // in case they've been unregistered after stopRecording() returned.
          if (keepAlive) {
            try {
              nativeDataIsRecorded(bytesRead, nativeAudioRecord);
            } catch (UnsatisfiedLinkError unsatisfiedLinkError) {
              FileLog.e(unsatisfiedLinkError);
              keepAlive = false;
            }
          }
          if (audioSamplesReadyCallback != null) {
            // Copy the entire byte buffer array.  Assume that the start of the byteBuffer is
            // at index 0.
            byte[] data = Arrays.copyOf(byteBuffer.array(), byteBuffer.capacity());
            audioSamplesReadyCallback.onWebRtcAudioRecordSamplesReady(
                new AudioSamples(audioRecord, data));
          }
        } else {
          String errorMessage = "AudioRecord.read failed: " + bytesRead;
          Logging.e(TAG, errorMessage);
          if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
            keepAlive = false;
            reportWebRtcAudioRecordError(errorMessage);
          }
        }
      }

      voiceEffectsProcessor.reset();
      try {
        if (audioRecord != null) {
          audioRecord.stop();
        }
      } catch (IllegalStateException e) {
        Logging.e(TAG, "AudioRecord.stop failed: " + e.getMessage());
      }
    }

    // Stops the inner thread loop and also calls AudioRecord.stop().
    // Does not block the calling thread.
    public void stopThread() {
      Logging.d(TAG, "stopThread");
      keepAlive = false;
      interrupt();
    }

    /**
     * Runs WebGram's selected-file input instead of microphone capture for this iteration.
     * The native recorder caches {@link #byteBuffer}'s address, so this path must fill that
     * exact direct buffer and invoke the native callback from this same audio thread.
     */
    private boolean runSelectedAudioIfRequested() {
      if (captureType == 1 || !ModConfig.isUseDeviceAudio() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        selectedAudioFormatWarningShown = false;
        return false;
      }

      long generation = ModConfig.getSelectedAudioGeneration();
      if (generation < 0) {
        return false;
      }

      if (byteBuffer.capacity() != SELECTED_AUDIO_CHUNK_BYTES || requestedSampleRate != SELECTED_AUDIO_SAMPLE_RATE || requestedChannels != 1) {
        if (!selectedAudioFormatWarningShown) {
          selectedAudioFormatWarningShown = true;
          showErrorPopup("Selected audio requires 48 kHz mono input");
        }
        ModConfig.finishSelectedAudioPlayback(generation);
        return false;
      }
      selectedAudioFormatWarningShown = false;

      Uri selectedUri = ModConfig.getSelectedAudioFileUri(generation);
      if (selectedUri == null) {
        return false;
      }

      playSelectedAudio(selectedUri, generation);
      return true;
    }

    private void playSelectedAudio(Uri uri, long generation) {
      MediaExtractor extractor = null;
      MediaCodec decoder = null;
      AudioTrack monitor = null;
      try {
        extractor = new MediaExtractor();
        extractor.setDataSource(ContextUtils.getApplicationContext(), uri, null);

        int audioTrackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
          MediaFormat candidate = extractor.getTrackFormat(i);
          String mime = candidate.getString(MediaFormat.KEY_MIME);
          if (mime != null && mime.startsWith("audio/")) {
            audioTrackIndex = i;
            format = candidate;
            extractor.selectTrack(i);
            break;
          }
        }
        if (audioTrackIndex < 0 || format == null) {
          showErrorPopup("No audio track found");
          return;
        }

        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null) {
          showErrorPopup("No audio track found");
          return;
        }
        int sourceSampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
            ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SELECTED_AUDIO_SAMPLE_RATE;
        int sourceChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
            ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        int pcmEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;

        decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();
        if (!ModConfig.setSelectedAudioPlaying(generation, true)) {
          return;
        }

        int minMonitorBuffer = AudioTrack.getMinBufferSize(
            SELECTED_AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        monitor = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SELECTED_AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(Math.max(minMonitorBuffer, 192000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        monitor.play();

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        Queue<byte[]> decodedPcm = new ArrayDeque<>();
        boolean inputEnded = false;
        while (keepAlive && ModConfig.isSelectedAudioPlaybackRequested(generation)) {
          if (!inputEnded) {
            int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputIndex >= 0) {
              ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
              if (inputBuffer != null) {
                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                if (sampleSize < 0) {
                  decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                  inputEnded = true;
                } else {
                  decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                  extractor.advance();
                }
              }
            }
          }

          int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);
          if (outputIndex >= 0) {
            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
            if (outputBuffer != null && bufferInfo.size > 0) {
              decodedPcm.add(convertToMono48k(
                  outputBuffer, bufferInfo.offset, bufferInfo.size,
                  pcmEncoding, sourceChannels, sourceSampleRate));
            }
            decoder.releaseOutputBuffer(outputIndex, false);
          }

          boolean outputEnded = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          if (decodedPcm.size() >= 5 || outputEnded) {
            deliverDecodedPcm(decodedPcm, monitor, generation);
          } else if (!inputEnded && !sleepWhileSelectedAudioActive(generation, 1)) {
            break;
          }

          if (outputEnded) {
            break;
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        Logging.e(TAG, "Selected audio playback failed: " + e.getMessage());
        showErrorPopup("File playback failed: " + e.getMessage());
      } finally {
        ModConfig.finishSelectedAudioPlayback(generation);
        if (decoder != null) {
          try {
            decoder.stop();
          } catch (Exception e) {
            Logging.e(TAG, "MediaCodec.stop failed: " + e.getMessage());
          }
          try {
            decoder.release();
          } catch (Exception e) {
            Logging.e(TAG, "MediaCodec.release failed: " + e.getMessage());
          }
        }
        if (extractor != null) {
          try {
            extractor.release();
          } catch (Exception e) {
            Logging.e(TAG, "MediaExtractor.release failed: " + e.getMessage());
          }
        }
        if (monitor != null) {
          try {
            monitor.stop();
          } catch (Exception e) {
            Logging.e(TAG, "AudioTrack.stop failed: " + e.getMessage());
          }
          try {
            monitor.release();
          } catch (Exception e) {
            Logging.e(TAG, "AudioTrack.release failed: " + e.getMessage());
          }
        }
      }
    }

    private byte[] convertToMono48k(ByteBuffer source, int offset, int size, int pcmEncoding,
                                    int sourceChannels, int sourceSampleRate) {
      ByteBuffer input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      input.position(offset);
      input.limit(offset + size);

      int sampleCount = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? size / 4 : size / 2;
      short[] samples = new short[sampleCount];
      for (int i = 0; i < sampleCount; i++) {
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
          float value = Math.max(-1.0f, Math.min(1.0f, input.getFloat()));
          samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (value * Short.MAX_VALUE)));
        } else {
          samples[i] = input.getShort();
        }
      }

      int channels = Math.max(1, sourceChannels);
      short[] mono;
      if (channels == 1) {
        mono = samples;
      } else {
        int frames = samples.length / channels;
        mono = new short[frames];
        for (int frame = 0; frame < frames; frame++) {
          int sum = 0;
          for (int channel = 0; channel < channels; channel++) {
            sum += samples[frame * channels + channel];
          }
          mono[frame] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum / channels));
        }
      }

      short[] resampled = mono;
      if (sourceSampleRate > 0 && sourceSampleRate != SELECTED_AUDIO_SAMPLE_RATE && mono.length > 0) {
        double ratio = (double) SELECTED_AUDIO_SAMPLE_RATE / sourceSampleRate;
        resampled = new short[Math.max(1, (int) (mono.length * ratio))];
        for (int i = 0; i < resampled.length; i++) {
          double sourcePosition = i / ratio;
          int lower = (int) sourcePosition;
          int upper = Math.min(lower + 1, mono.length - 1);
          double fraction = sourcePosition - lower;
          int value = (int) (mono[lower] + (mono[upper] - mono[lower]) * fraction);
          resampled[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        }
      }

      ByteBuffer result = ByteBuffer.allocate(resampled.length * 2).order(ByteOrder.LITTLE_ENDIAN);
      for (short sample : resampled) {
        result.putShort(sample);
      }
      return result.array();
    }

    private void deliverDecodedPcm(Queue<byte[]> decodedPcm, AudioTrack monitor, long generation) throws InterruptedException {
      byte[] chunk = new byte[SELECTED_AUDIO_CHUNK_BYTES];
      int chunkOffset = 0;
      while (!decodedPcm.isEmpty() && keepAlive && ModConfig.isSelectedAudioPlaybackRequested(generation)) {
        byte[] decoded = decodedPcm.poll();
        int decodedOffset = 0;
        while (decodedOffset < decoded.length && keepAlive && ModConfig.isSelectedAudioPlaybackRequested(generation)) {
          int length = Math.min(chunk.length - chunkOffset, decoded.length - decodedOffset);
          System.arraycopy(decoded, decodedOffset, chunk, chunkOffset, length);
          decodedOffset += length;
          chunkOffset += length;
          if (chunkOffset == chunk.length) {
            byteBuffer.clear();
            byteBuffer.put(microphoneMute ? emptyBytes : chunk);
            if (keepAlive && ModConfig.isSelectedAudioPlaybackRequested(generation)) {
              nativeDataIsRecorded(SELECTED_AUDIO_CHUNK_BYTES, nativeAudioRecord);
              monitor.write(chunk, 0, chunk.length, AudioTrack.WRITE_BLOCKING);
            }
            sleepWhileSelectedAudioActive(generation, CALLBACK_BUFFER_SIZE_MS);
            chunkOffset = 0;
          }
        }
      }
    }

    private boolean sleepWhileSelectedAudioActive(long generation, long millis) throws InterruptedException {
      if (!keepAlive || !ModConfig.isSelectedAudioPlaybackRequested(generation)) {
        return false;
      }
      Thread.sleep(millis);
      return keepAlive && ModConfig.isSelectedAudioPlaybackRequested(generation);
    }

    private void showErrorPopup(String message) {
      new Handler(Looper.getMainLooper()).post(() ->
          Toast.makeText(ContextUtils.getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
  }

  WebRtcAudioRecord(long nativeAudioRecord, int type) {
    Logging.d(TAG, "ctor" + WebRtcAudioUtils.getThreadInfo());
    this.nativeAudioRecord = nativeAudioRecord;
    effects = WebRtcAudioEffects.create();
    captureType = type;
    if (captureType == 2 && Instance == null) {
      Instance = this;
    }
  }

  private void onDestroy() {
    stopDeviceAudioRecord();
    if (Instance == this) {
      Instance = null;
    }
  }

  private boolean enableBuiltInAEC(boolean enable) {
    Logging.d(TAG, "enableBuiltInAEC(" + enable + ')');
    if (effects == null) {
      Logging.e(TAG, "Built-in AEC is not supported on this platform");
      return false;
    }
    return effects.setAEC(enable);
  }

  private boolean enableBuiltInNS(boolean enable) {
    Logging.d(TAG, "enableBuiltInNS(" + enable + ')');
    if (effects == null) {
      Logging.e(TAG, "Built-in NS is not supported on this platform");
      return false;
    }
    return effects.setNS(enable);
  }

  private int initRecording(int sampleRate, int channels) {
    if (captureType == 1 && Build.VERSION.SDK_INT < 29) {
      return -1;
    }
    requestedSampleRate = sampleRate;
    requestedChannels = channels;
    Logging.d(TAG, "initRecording(sampleRate=" + sampleRate + ", channels=" + channels + ")");
    if (audioRecord != null) {
      reportWebRtcAudioRecordInitError("InitRecording called twice without StopRecording.");
      return -1;
    }
    final int bytesPerFrame = channels * (BITS_PER_SAMPLE / 8);
    final int framesPerBuffer = sampleRate / BUFFERS_PER_SECOND;
    byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
    byteBuffer.order(ByteOrder.nativeOrder());
    Logging.d(TAG, "byteBuffer.capacity: " + byteBuffer.capacity());
    emptyBytes = new byte[byteBuffer.capacity()];
    // Rather than passing the ByteBuffer with every callback (requiring
    // the potentially expensive GetDirectBufferAddress) we simply have the
    // the native class cache the address to the memory once.
    nativeCacheDirectBufferAddress(byteBuffer, nativeAudioRecord);

    // Get the minimum buffer size required for the successful creation of
    // an AudioRecord object, in byte units.
    // Note that this size doesn't guarantee a smooth recording under load.
    final int channelConfig = channelCountToConfiguration(channels);
    int minBufferSize =
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
    if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
      reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize);
      return -1;
    }
    Logging.d(TAG, "AudioRecord.getMinBufferSize: " + minBufferSize);

    // Use a larger buffer size than the minimum required when creating the
    // AudioRecord instance to ensure smooth recording under load. It has been
    // verified that it does not increase the actual recording latency.
    int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, byteBuffer.capacity());
    Logging.d(TAG, "bufferSizeInBytes: " + bufferSizeInBytes);

    if (captureType == 1) {
      if (Build.VERSION.SDK_INT >= 29) {
        try {
          MediaProjection projection = VideoCapturerDevice.getMediaProjection();
          if (projection == null) {
            return -1;
          }
          AudioPlaybackCaptureConfiguration.Builder builder = new AudioPlaybackCaptureConfiguration.Builder(projection);
          builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
          builder.addMatchingUsage(AudioAttributes.USAGE_GAME);
          builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);

          AudioRecord.Builder audioRecordBuilder = new AudioRecord.Builder();
          audioRecordBuilder.setAudioPlaybackCaptureConfig(builder.build());
          audioRecordBuilder.setAudioFormat(new AudioFormat.Builder().setChannelMask(channelConfig).setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build());
          audioRecordBuilder.setBufferSizeInBytes(bufferSizeInBytes);
          audioRecord = audioRecordBuilder.build();
        } catch (Throwable e) {
          reportWebRtcAudioRecordInitError("AudioRecord ctor error: " + e.getMessage());
          releaseAudioResources(false);
          return -1;
        }
      }
    } else {
      try {
        audioRecord = new AudioRecord(audioSource, sampleRate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes);
      } catch (IllegalArgumentException e) {
        reportWebRtcAudioRecordInitError("AudioRecord ctor error: " + e.getMessage());
        releaseAudioResources(false);
        return -1;
      }
    }
    if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
      reportWebRtcAudioRecordInitError("Failed to create a new AudioRecord instance");
      releaseAudioResources(false);
      return -1;
    }
    if (captureType == 0 && effects != null) {
      effects.enable(audioRecord.getAudioSessionId());
    }
    logMainParameters();
    logMainParametersExtended();
    return framesPerBuffer;
  }

  @TargetApi(29)
  public void initDeviceAudioRecord(MediaProjection mediaProjection) {
    if (Build.VERSION.SDK_INT < 29) {
      return;
    }
    final int bytesPerFrame = requestedChannels * (BITS_PER_SAMPLE / 8);
    final int framesPerBuffer = requestedSampleRate / BUFFERS_PER_SECOND;
    deviceByteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
    deviceByteBuffer.order(ByteOrder.nativeOrder());

    final int channelConfig = channelCountToConfiguration(requestedChannels);
    int minBufferSize = AudioRecord.getMinBufferSize(requestedSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
    if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
      reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize);
      return;
    }

    int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, deviceByteBuffer.capacity());
    try {
      AudioPlaybackCaptureConfiguration.Builder builder = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection);
      builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
      builder.addMatchingUsage(AudioAttributes.USAGE_GAME);

      AudioRecord.Builder audioRecordBuilder = new AudioRecord.Builder();
      audioRecordBuilder.setAudioPlaybackCaptureConfig(builder.build());
      audioRecordBuilder.setAudioFormat(new AudioFormat.Builder().setChannelMask(channelConfig).setSampleRate(requestedSampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build());
      audioRecordBuilder.setBufferSizeInBytes(bufferSizeInBytes);
      deviceAudioRecord = audioRecordBuilder.build();
    } catch (Throwable e) {
      reportWebRtcAudioRecordInitError("AudioRecord ctor error: " + e.getMessage());
      releaseAudioResources(true);
      return;
    }
    if (deviceAudioRecord == null || deviceAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
      reportWebRtcAudioRecordInitError("Failed to create a new AudioRecord instance");
      releaseAudioResources(true);
      return;
    }
    try {
      deviceAudioRecord.startRecording();
    } catch (IllegalStateException e) {
      reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION, "AudioRecord.startRecording failed: " + e.getMessage());
      return;
    }
    if (deviceAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
      reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH, "AudioRecord.startRecording failed - incorrect state :" + deviceAudioRecord.getRecordingState());
    }
  }

  @TargetApi(29)
  public void stopDeviceAudioRecord() {
    if (deviceAudioRecord == null) {
      return;
    }
    try {
      deviceAudioRecord.stop();
    } catch (Throwable e) {
      FileLog.e(e);
    }
    releaseAudioResources(true);
  }

  private boolean startRecording() {
    Logging.d(TAG, "startRecording");
    assertTrue(audioRecord != null);
    assertTrue(audioThread == null);
    try {
      audioRecord.startRecording();
    } catch (IllegalStateException e) {
      reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION, "AudioRecord.startRecording failed: " + e.getMessage());
      return false;
    }
    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
      reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH, "AudioRecord.startRecording failed - incorrect state :" + audioRecord.getRecordingState());
      return false;
    }
    audioThread = new AudioRecordThread("AudioRecordJavaThread");
    audioThread.start();
    return true;
  }

  private boolean stopRecording() {
    Logging.d(TAG, "stopRecording");
    assertTrue(audioThread != null);
    audioThread.stopThread();
    if (!ThreadUtils.joinUninterruptibly(audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
      Logging.e(TAG, "Join of AudioRecordJavaThread timed out");
      WebRtcAudioUtils.logAudioState(TAG);
    }
    audioThread = null;
    if (effects != null) {
      effects.release();
    }
    try {
      audioRecord.stop();
    } catch (Throwable e) {
      FileLog.e(e);
    }
    releaseAudioResources(false);
    return true;
  }

  private void logMainParameters() {
    Logging.d(TAG, "AudioRecord: "
            + "session ID: " + audioRecord.getAudioSessionId() + ", "
            + "channels: " + audioRecord.getChannelCount() + ", "
            + "sample rate: " + audioRecord.getSampleRate());
  }

  private void logMainParametersExtended() {
    if (Build.VERSION.SDK_INT >= 23) {
      Logging.d(TAG, "AudioRecord: "
              // The frame count of the native AudioRecord buffer.
              + "buffer size in frames: " + audioRecord.getBufferSizeInFrames());
    }
  }

  // Helper method which throws an exception  when an assertion has failed.
  private static void assertTrue(boolean condition) {
    if (!condition) {
      throw new AssertionError("Expected condition to be true");
    }
  }

  private int channelCountToConfiguration(int channels) {
    return (channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO);
  }

  private native void nativeCacheDirectBufferAddress(ByteBuffer byteBuffer, long nativeAudioRecord);

  private native void nativeDataIsRecorded(int bytes, long nativeAudioRecord);

  @SuppressWarnings("NoSynchronizedMethodCheck")
  public static synchronized void setAudioSource(int source) {
    Logging.w(TAG, "Audio source is changed from: " + audioSource
            + " to " + source);
    audioSource = source;
  }

  private static int getDefaultAudioSource() {
    return AudioSource.VOICE_COMMUNICATION;
  }

  // Sets all recorded samples to zero if |mute| is true, i.e., ensures that
  // the microphone is muted.
  public static void setMicrophoneMute(boolean mute) {
    Logging.w(TAG, "setMicrophoneMute(" + mute + ")");
    microphoneMute = mute;
  }

  // Releases the native AudioRecord resources.
  private void releaseAudioResources(boolean device) {
    Logging.d(TAG, "releaseAudioResources " + device);
    if (device) {
      if (deviceAudioRecord != null) {
        deviceAudioRecord.release();
        deviceAudioRecord = null;
      }
    } else {
      if (audioRecord != null) {
        audioRecord.release();
        audioRecord = null;
      }
    }
  }

  private void reportWebRtcAudioRecordInitError(String errorMessage) {
    Logging.e(TAG, "Init recording error: " + errorMessage);
    WebRtcAudioUtils.logAudioState(TAG);
    if (errorCallback != null) {
      errorCallback.onWebRtcAudioRecordInitError(errorMessage);
    }
  }

  private void reportWebRtcAudioRecordStartError(
      AudioRecordStartErrorCode errorCode, String errorMessage) {
    Logging.e(TAG, "Start recording error: " + errorCode + ". " + errorMessage);
    WebRtcAudioUtils.logAudioState(TAG);
    if (errorCallback != null) {
      errorCallback.onWebRtcAudioRecordStartError(errorCode, errorMessage);
    }
  }

  private void reportWebRtcAudioRecordError(String errorMessage) {
    Logging.e(TAG, "Run-time recording error: " + errorMessage);
    WebRtcAudioUtils.logAudioState(TAG);
    if (errorCallback != null) {
      errorCallback.onWebRtcAudioRecordError(errorMessage);
    }
  }
}

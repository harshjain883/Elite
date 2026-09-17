package org.webrtc.voiceengine;

import org.telegram.messenger.utils.ModConfig;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded, allocation-free steady-state processing for the outgoing live microphone. */
final class VoiceEffectsProcessor {
  private static final int SAMPLE_RATE = 48000;
  private static final int EFFECT_TRANSITION_SAMPLES = 240; // 5 ms.
  private static final int ECHO_RING_SIZE = 8192;
  private static final int ECHO_RING_MASK = ECHO_RING_SIZE - 1;
  private static final int ECHO_DELAY_SAMPLES = 5280; // 110 ms.

  private final StreamingPhaseVocoder pitchShifter = new StreamingPhaseVocoder();
  private final short[] echoRing = new short[ECHO_RING_SIZE];
  private int echoWriteIndex;
  private double robotPhase;
  private float robotEnvelope;
  private float toneLowState;
  private float toneBodyState;
  private float tonePresenceState;
  private int transitionSamplesRemaining;
  private int activeEffect = ModConfig.VOICE_EFFECT_NORMAL;

  void process(ByteBuffer buffer, int byteCount, int effect) {
    if (effect < ModConfig.VOICE_EFFECT_NORMAL || effect >= ModConfig.VOICE_EFFECT_COUNT) {
      effect = ModConfig.VOICE_EFFECT_NORMAL;
    }
    if (effect != activeEffect) {
      resetState();
      activeEffect = effect;
      transitionSamplesRemaining = EFFECT_TRANSITION_SAMPLES;
    }
    // The normal path is a true in-place bypass: no sample read, copy, or write.
    if (effect == ModConfig.VOICE_EFFECT_NORMAL) {
      return;
    }

    int samples = Math.min(byteCount, buffer.capacity()) / 2;
    for (int i = 0; i < samples; i++) {
      int offset = i * 2;
      float input = buffer.getShort(offset);
      float output;
      switch (effect) {
        case ModConfig.VOICE_EFFECT_CHILD:
          output = shapeChild(pitchShifter.process(input, 1.20, 1.08)) * 1.52f;
          break;
        case ModConfig.VOICE_EFFECT_ADULT:
          output = shapeAdult(pitchShifter.process(input, 0.87, 0.95)) * 1.42f;
          break;
        case ModConfig.VOICE_EFFECT_ROBOT:
          output = shapeRobot(input) * 1.50f;
          break;
        case ModConfig.VOICE_EFFECT_ECHO:
          output = applyEcho(input);
          break;
        case ModConfig.VOICE_EFFECT_FEMALE:
          output = shapeFemale(pitchShifter.process(input, 1.16, 1.10)) * 1.55f;
          break;
        default:
          output = input;
          break;
      }
      if (transitionSamplesRemaining > 0) {
        float progress = 1.0f - transitionSamplesRemaining / (float) EFFECT_TRANSITION_SAMPLES;
        float smoothProgress = progress * progress * (3.0f - 2.0f * progress);
        output = input + (output - input) * smoothProgress;
        transitionSamplesRemaining--;
      }
      buffer.putShort(offset, softLimit(output));
    }
  }

  void reset() {
    if (activeEffect != ModConfig.VOICE_EFFECT_NORMAL) {
      resetState();
      activeEffect = ModConfig.VOICE_EFFECT_NORMAL;
    }
  }

  private float shapeChild(float sample) {
    toneBodyState += 0.145f * (sample - toneBodyState);
    float clarity = sample - toneBodyState;
    return sample * 0.92f + clarity * 0.13f;
  }

  private float shapeAdult(float sample) {
    toneLowState += 0.063f * (sample - toneLowState);
    return sample * 0.76f + toneLowState * 0.28f;
  }

  private float shapeFemale(float sample) {
    toneLowState += 0.023f * (sample - toneLowState);
    toneBodyState += 0.111f * (sample - toneBodyState);
    tonePresenceState += 0.342f * (sample - tonePresenceState);
    float highPassed = sample - toneLowState;
    float presence = tonePresenceState - toneBodyState;
    float air = sample - tonePresenceState;
    return highPassed * 0.88f + presence * 0.20f + air * 0.07f;
  }

  private float shapeRobot(float sample) {
    float carrier = (float) Math.sin(robotPhase);
    float secondHarmonic = (float) Math.sin(robotPhase * 2.0 + 0.35);
    robotPhase += 2.0 * Math.PI * 82.0 / SAMPLE_RATE;
    if (robotPhase >= 2.0 * Math.PI) {
      robotPhase -= 2.0 * Math.PI;
    }
    robotEnvelope += 0.018f * (Math.abs(sample) - robotEnvelope);
    float modulated = sample * (0.27f + 0.67f * carrier + 0.14f * secondHarmonic);
    return modulated + robotEnvelope * carrier * 0.08f;
  }

  private float applyEcho(float sample) {
    int readIndex = (echoWriteIndex - ECHO_DELAY_SAMPLES) & ECHO_RING_MASK;
    float delayed = echoRing[readIndex];
    float output = sample * 0.84f + delayed * 0.44f;
    echoRing[echoWriteIndex] = softLimit(sample + delayed * 0.24f);
    echoWriteIndex = (echoWriteIndex + 1) & ECHO_RING_MASK;
    return output;
  }

  private void resetState() {
    pitchShifter.reset();
    Arrays.fill(echoRing, (short) 0);
    echoWriteIndex = 0;
    robotPhase = 0.0;
    robotEnvelope = 0.0f;
    toneLowState = 0.0f;
    toneBodyState = 0.0f;
    tonePresenceState = 0.0f;
    transitionSamplesRemaining = 0;
  }

  private static short softLimit(float sample) {
    float absolute = Math.abs(sample);
    if (absolute <= 27000.0f) {
      return (short) Math.round(sample);
    }
    float excess = absolute - 27000.0f;
    float limited = 27000.0f + 5767.0f * excess / (excess + 5767.0f);
    return (short) Math.round(Math.copySign(limited, sample));
  }

  /**
   * A 1024-sample, 75%-overlapped phase vocoder with spectral-envelope correction.
   * It preserves stream duration while moving pitch and formants independently. All storage is
   * fixed and owned by the recording thread; the 1024-sample analysis window adds 21.33 ms.
   */
  private static final class StreamingPhaseVocoder {
    private static final int FFT_SIZE = 1024;
    private static final int FFT_MASK = FFT_SIZE - 1;
    private static final int HOP_SIZE = 256;
    private static final int BIN_COUNT = FFT_SIZE / 2 + 1;
    private static final int OUTPUT_RING_SIZE = 4096;
    private static final int OUTPUT_RING_MASK = OUTPUT_RING_SIZE - 1;
    private static final int ENVELOPE_RADIUS = 10;
    private static final int WARMUP_SAMPLES = FFT_SIZE + 128;
    private static final int WARMUP_CROSSFADE_SAMPLES = 240;
    private static final double TWO_PI = 2.0 * Math.PI;

    private final float[] inputRing = new float[FFT_SIZE];
    private final float[] outputRing = new float[OUTPUT_RING_SIZE];
    private final float[] outputWeight = new float[OUTPUT_RING_SIZE];
    private final double[] window = new double[FFT_SIZE];
    private final double[] real = new double[FFT_SIZE];
    private final double[] imaginary = new double[FFT_SIZE];
    private final double[] sourceMagnitude = new double[BIN_COUNT];
    private final double[] sourcePhase = new double[BIN_COUNT];
    private final double[] sourceFrequency = new double[BIN_COUNT];
    private final double[] previousAnalysisPhase = new double[BIN_COUNT];
    private final double[] synthesisPhase = new double[BIN_COUNT];
    private final double[] logMagnitude = new double[BIN_COUNT];
    private final double[] spectralEnvelope = new double[BIN_COUNT];
    private final double[] envelopePrefix = new double[BIN_COUNT + 1];
    private final double[] destinationMagnitude = new double[BIN_COUNT];
    private final double[] destinationFrequency = new double[BIN_COUNT];
    private final double[] destinationSeedReal = new double[BIN_COUNT];
    private final double[] destinationSeedImaginary = new double[BIN_COUNT];
    private final double[] cosine = new double[FFT_SIZE / 2];
    private final double[] sine = new double[FFT_SIZE / 2];
    private final int[] bitReverse = new int[FFT_SIZE];
    private long sampleCount;
    private int frameCount;

    StreamingPhaseVocoder() {
      for (int i = 0; i < FFT_SIZE; i++) {
        window[i] = Math.sqrt(0.5 - 0.5 * Math.cos(TWO_PI * i / (FFT_SIZE - 1)));
        bitReverse[i] = Integer.reverse(i) >>> (32 - 10);
      }
      for (int i = 0; i < FFT_SIZE / 2; i++) {
        cosine[i] = Math.cos(TWO_PI * i / FFT_SIZE);
        sine[i] = Math.sin(TWO_PI * i / FFT_SIZE);
      }
    }

    float process(float input, double pitchFactor, double formantFactor) {
      long position = sampleCount;
      inputRing[(int) position & FFT_MASK] = input;
      sampleCount = position + 1;
      if (sampleCount >= FFT_SIZE && ((sampleCount - FFT_SIZE) & (HOP_SIZE - 1)) == 0) {
        synthesizeFrame(pitchFactor, formantFactor);
      }

      int outputIndex = (int) position & OUTPUT_RING_MASK;
      float weight = outputWeight[outputIndex];
      float shifted = weight > 1.0e-5f ? outputRing[outputIndex] / weight : input;
      outputRing[outputIndex] = 0.0f;
      outputWeight[outputIndex] = 0.0f;

      if (position < WARMUP_SAMPLES) {
        return input;
      }
      long warmupProgress = position - WARMUP_SAMPLES;
      if (warmupProgress < WARMUP_CROSSFADE_SAMPLES) {
        float mix = warmupProgress / (float) WARMUP_CROSSFADE_SAMPLES;
        mix = mix * mix * (3.0f - 2.0f * mix);
        return input + (shifted - input) * mix;
      }
      return shifted;
    }

    void reset() {
      Arrays.fill(inputRing, 0.0f);
      Arrays.fill(outputRing, 0.0f);
      Arrays.fill(outputWeight, 0.0f);
      Arrays.fill(previousAnalysisPhase, 0.0);
      Arrays.fill(synthesisPhase, 0.0);
      sampleCount = 0;
      frameCount = 0;
    }

    private void synthesizeFrame(double pitchFactor, double formantFactor) {
      long frameStart = sampleCount - FFT_SIZE;
      for (int i = 0; i < FFT_SIZE; i++) {
        real[i] = inputRing[(int) (frameStart + i) & FFT_MASK] * window[i];
        imaginary[i] = 0.0;
      }
      transform(false);

      for (int bin = 0; bin < BIN_COUNT; bin++) {
        double magnitude = Math.hypot(real[bin], imaginary[bin]);
        double phase = Math.atan2(imaginary[bin], real[bin]);
        sourceMagnitude[bin] = magnitude;
        sourcePhase[bin] = phase;
        logMagnitude[bin] = Math.log1p(magnitude);
        double nominalAdvance = TWO_PI * HOP_SIZE * bin / FFT_SIZE;
        if (frameCount == 0) {
          sourceFrequency[bin] = TWO_PI * bin / FFT_SIZE;
        } else {
          double phaseDelta = wrapPhase(phase - previousAnalysisPhase[bin] - nominalAdvance);
          sourceFrequency[bin] = TWO_PI * bin / FFT_SIZE + phaseDelta / HOP_SIZE;
        }
        previousAnalysisPhase[bin] = phase;
      }

      envelopePrefix[0] = 0.0;
      for (int bin = 0; bin < BIN_COUNT; bin++) {
        envelopePrefix[bin + 1] = envelopePrefix[bin] + logMagnitude[bin];
      }
      for (int bin = 0; bin < BIN_COUNT; bin++) {
        int from = Math.max(0, bin - ENVELOPE_RADIUS);
        int to = Math.min(BIN_COUNT - 1, bin + ENVELOPE_RADIUS);
        spectralEnvelope[bin] = (envelopePrefix[to + 1] - envelopePrefix[from]) / (to - from + 1);
      }

      Arrays.fill(destinationMagnitude, 0.0);
      Arrays.fill(destinationFrequency, 0.0);
      Arrays.fill(destinationSeedReal, 0.0);
      Arrays.fill(destinationSeedImaginary, 0.0);
      for (int sourceBin = 0; sourceBin < BIN_COUNT; sourceBin++) {
        double target = sourceBin * pitchFactor;
        int lower = (int) target;
        if (lower >= BIN_COUNT) {
          break;
        }
        double fraction = target - lower;
        double desiredEnvelopeIndex = target / formantFactor;
        double desiredEnvelope = interpolateEnvelope(desiredEnvelopeIndex);
        double correction = Math.exp(clamp(desiredEnvelope - spectralEnvelope[sourceBin], -0.65, 0.65));
        double magnitude = sourceMagnitude[sourceBin] * correction;
        double shiftedFrequency = sourceFrequency[sourceBin] * pitchFactor;
        addDestination(lower, magnitude * (1.0 - fraction), shiftedFrequency, sourcePhase[sourceBin]);
        if (fraction > 0.0 && lower + 1 < BIN_COUNT) {
          addDestination(lower + 1, magnitude * fraction, shiftedFrequency, sourcePhase[sourceBin]);
        }
      }

      Arrays.fill(real, 0.0);
      Arrays.fill(imaginary, 0.0);
      for (int bin = 0; bin < BIN_COUNT; bin++) {
        double magnitude = destinationMagnitude[bin];
        if (magnitude > 1.0e-9) {
          if (frameCount == 0) {
            synthesisPhase[bin] = Math.atan2(destinationSeedImaginary[bin], destinationSeedReal[bin]);
          } else {
            synthesisPhase[bin] = wrapPhase(synthesisPhase[bin]
                + destinationFrequency[bin] / magnitude * HOP_SIZE);
          }
          real[bin] = magnitude * Math.cos(synthesisPhase[bin]);
          imaginary[bin] = magnitude * Math.sin(synthesisPhase[bin]);
        } else if (frameCount != 0) {
          synthesisPhase[bin] = wrapPhase(synthesisPhase[bin]
              + TWO_PI * HOP_SIZE * bin / FFT_SIZE);
        }
      }
      imaginary[0] = 0.0;
      imaginary[FFT_SIZE / 2] = 0.0;
      for (int bin = 1; bin < FFT_SIZE / 2; bin++) {
        real[FFT_SIZE - bin] = real[bin];
        imaginary[FFT_SIZE - bin] = -imaginary[bin];
      }
      transform(true);

      int outputStart = (int) sampleCount & OUTPUT_RING_MASK;
      for (int i = 0; i < FFT_SIZE; i++) {
        int outputIndex = (outputStart + i) & OUTPUT_RING_MASK;
        float windowValue = (float) window[i];
        outputRing[outputIndex] += (float) real[i] * windowValue;
        outputWeight[outputIndex] += windowValue * windowValue;
      }
      frameCount++;
    }

    private void addDestination(int bin, double magnitude, double frequency, double phase) {
      destinationMagnitude[bin] += magnitude;
      destinationFrequency[bin] += magnitude * frequency;
      destinationSeedReal[bin] += magnitude * Math.cos(phase);
      destinationSeedImaginary[bin] += magnitude * Math.sin(phase);
    }

    private double interpolateEnvelope(double index) {
      if (index <= 0.0) {
        return spectralEnvelope[0];
      }
      if (index >= BIN_COUNT - 1) {
        return spectralEnvelope[BIN_COUNT - 1];
      }
      int lower = (int) index;
      double fraction = index - lower;
      return spectralEnvelope[lower]
          + (spectralEnvelope[lower + 1] - spectralEnvelope[lower]) * fraction;
    }

    private void transform(boolean inverse) {
      for (int i = 0; i < FFT_SIZE; i++) {
        int reversed = bitReverse[i];
        if (reversed > i) {
          double value = real[i];
          real[i] = real[reversed];
          real[reversed] = value;
          value = imaginary[i];
          imaginary[i] = imaginary[reversed];
          imaginary[reversed] = value;
        }
      }
      for (int length = 2; length <= FFT_SIZE; length <<= 1) {
        int half = length >> 1;
        int tableStep = FFT_SIZE / length;
        for (int start = 0; start < FFT_SIZE; start += length) {
          for (int offset = 0; offset < half; offset++) {
            int tableIndex = offset * tableStep;
            double twiddleReal = cosine[tableIndex];
            double twiddleImaginary = inverse ? sine[tableIndex] : -sine[tableIndex];
            int even = start + offset;
            int odd = even + half;
            double oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary;
            double oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal;
            real[odd] = real[even] - oddReal;
            imaginary[odd] = imaginary[even] - oddImaginary;
            real[even] += oddReal;
            imaginary[even] += oddImaginary;
          }
        }
      }
      if (inverse) {
        for (int i = 0; i < FFT_SIZE; i++) {
          real[i] /= FFT_SIZE;
          imaginary[i] /= FFT_SIZE;
        }
      }
    }

    private static double wrapPhase(double phase) {
      phase -= TWO_PI * Math.floor((phase + Math.PI) / TWO_PI);
      return phase;
    }

    private static double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
    }
  }
}

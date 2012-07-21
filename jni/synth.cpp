#include <jni.h>  
#include <stdlib.h>

#include <vector>
#include <iostream>

#include <Stk.h>
#include <SineWave.h>
#include <ADSR.h>
#include <BlitSquare.h>

#define MAX_VOICES 128

struct voice {
	stk::ADSR adsr_;
	stk::BlitSquare wave_;

	voice() {
		wave_.reset();
		//wave_.setRate(1);
		wave_.setFrequency(1);
	}

	float tick() {
		return adsr_.tick() * wave_.tick();
		//return adsr_.tick();
	}
};

struct synth {
	std::vector<voice> voices_;

	synth(int num_voices = MAX_VOICES) :
			voices_(num_voices) {

		for (unsigned int index = 0; index < num_voices; ++index) {
			voices_[index] = voice();
		}

		stk::Stk::setSampleRate(44100);
	}

	float tick(int voices) {
		// std::cout << "tick" << std::endl;
		float ret = 0;
		for (unsigned int index = 0; index < voices; ++index) {
			ret += voices_[index].tick();
		}

		return ret;
	}
};

synth s;

extern "C" {
/*
 * Fills a buffer with synthesis data..
 *
 * the intensities are arrays with row major order. i.e.
 * the first bitmap_width entries are
 * the intensities of the first image row, etc..
 *
 * frequencies is an array of frequencies
 */
void Java_io_fps_camsynth_Main_synth(JNIEnv * env, jobject that,
		jshortArray array, jfloat samplerate, jfloat tempo, jint bitmap_width,
		jint bitmap_height, jfloatArray intensities_red,
		jfloatArray intensities_green, jfloatArray intensities_blue,
		jfloatArray frequencies, jfloat env_coeff, jfloat lp_coeff,
		jshort max_value) {

	static int sample_position = 0;
	static int sample_position_in_window = 0;

	jsize samples_length = env->GetArrayLength(array);
	jshort *samples = (env)->GetShortArrayElements(array, 0);

	jfloat *red = (env)->GetFloatArrayElements(intensities_red, 0);

	jfloat *freqs = (env)->GetFloatArrayElements(frequencies, 0);
	jsize freqs_length = (env)->GetArrayLength(frequencies);

	int window_length = (int) (bitmap_width * (samplerate / tempo));
	int tick_length = (int) (samplerate / tempo);

	int index;
	for (index = 0; index < samples_length; ++index) {
		short sample = 0;

		int position_in_bitmap = (int) (bitmap_width
				* (double) sample_position_in_window / window_length);

		int note;
		for (note = 0; note < bitmap_height; ++note) {

			if (sample_position % tick_length == 0) {
				s.voices_[note].wave_.setFrequency(
						freqs[note * 2
								+ (int) (5.0 * (float) rand() / (float) RAND_MAX)]);

				s.voices_[note].adsr_.keyOn();
			}
		}

		samples[index] = (short) ((float) (2 << 14) * s.tick(bitmap_height));

		++sample_position;
		++sample_position_in_window;
		sample_position_in_window %= window_length;
	}

	(env)->ReleaseShortArrayElements(array, samples, 0);
	(env)->ReleaseFloatArrayElements(intensities_red, red, 0);
	(env)->ReleaseFloatArrayElements(frequencies, freqs, 0);
}

} // extern  "C"

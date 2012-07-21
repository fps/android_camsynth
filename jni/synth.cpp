#include <jni.h>  
#include <stdlib.h>

#define MAX_VOICES 128

static float voices[MAX_VOICES];
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
	static int old_sample = 0;

	jsize samples_length = env->GetArrayLength(array);
	jshort *samples = (env)->GetShortArrayElements(array, 0);

	jfloat *red = (env)->GetFloatArrayElements(intensities_red, 0);

	jfloat *freqs = (env)->GetFloatArrayElements(frequencies, 0);
	jsize freqs_length = (env)->GetArrayLength(frequencies);

	int window_length = (int) (bitmap_width * (samplerate / tempo));
	int tick_length = (int) (samplerate / tempo);

	int index;
	float envelope = 0;
	for (index = 0; index < samples_length; ++index) {
		short sample = 0;

		if (sample_position % tick_length == 0) {
			envelope = 1.0;
		}

		envelope *= env_coeff;

		int position_in_bitmap = (int) (bitmap_width
				* (double) sample_position_in_window / window_length);

		int note;
		for (note = 0; note < bitmap_height; ++note) {

			if (sample_position % tick_length == 0) {
				voices[note] = freqs[note * 2
						+ (int) (5.0 * (float) rand() / (float) RAND_MAX)];
			}

			double gain = envelope
					* (double) red[note * bitmap_width + position_in_bitmap]
					/ 1024.0;

			int wavelength = (int) (samplerate / voices[note]);
			if (sample_position % wavelength == 0) {
				sample += ((short) (gain * (double) (2 << 15)));
			}
		}
		sample = (lp_coeff * sample + (1.0 - lp_coeff) * old_sample);

		samples[index] = sample;
		old_sample = sample;

		++sample_position;
		++sample_position_in_window;
		sample_position_in_window %= window_length;
	}

	(env)->ReleaseShortArrayElements(array, samples, 0);
	(env)->ReleaseFloatArrayElements(intensities_red, red, 0);
	(env)->ReleaseFloatArrayElements(frequencies, freqs, 0);
}

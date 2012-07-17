package io.fps.camsynth;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.os.Process;

public class Main extends Activity implements SurfaceHolder.Callback,
		Camera.PreviewCallback {
	static String logTag = Main.class.toString();

	static {
		System.loadLibrary("synth");
	}

	private native void synth(short[] array, float samplerate, float tempo,
			int bitmapWidth, int bitmapHeight, float[] intensitiesRed,
			float[] intensitiesGreen, float[] intensitiesBlue,
			float[] frequencies);

	private native void prepare();

	int bitmapWidth = 32;
	int bitmapHeight = 8;

	float bpm = 10.0f;

	float[] frequencies = new float[bitmapHeight];

	int samplingRate = 22050;

	int minBufferSize = AudioTrack.getMinBufferSize(samplingRate,
			AudioFormat.CHANNEL_CONFIGURATION_MONO,
			AudioFormat.ENCODING_PCM_16BIT);

	AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
			samplingRate, AudioFormat.CHANNEL_CONFIGURATION_MONO,
			AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 4,
			AudioTrack.MODE_STREAM);

	AudioTask audioTask = new AudioTask();

	Queue<Bitmap> bitmapQueue = new ConcurrentLinkedQueue<Bitmap>();

	Camera camera = null;

	/*
	 * This needs to be a member because it is determined in onResume and used
	 * in onWindowFocusChanges (see below)
	 */
	Camera.Size previewSize = null;

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		setContentView(R.layout.main);

		((SurfaceView) findViewById(R.id.surface)).getHolder()
				.addCallback(this);

		((SurfaceView) findViewById(R.id.surface)).getHolder().setType(
				SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		audioTrack.play();
		audioTask.execute();

		float fundamental = 220.0f;
		for (int index = 0; index < frequencies.length; ++index) {
			frequencies[index] = (float) (fundamental * Math.pow(
					Math.pow(2.0, 1.0 / 12.0), 3.0 * index));
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		Log.d(logTag, "onResume");

		if (null == camera) {
			camera = Camera.open();

			Camera.Parameters parameters = camera.getParameters();

			/*
			 * Get the list of supported preview sizes and sort them in
			 * ascending order according to their respective pixel counts
			 */
			List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
			Collections.sort(sizes, new Comparator<Camera.Size>() {

				public int compare(Camera.Size lhs, Camera.Size rhs) {
					if (lhs.width * lhs.height > rhs.width * rhs.height) {
						return 1;
					}
					if (lhs.width * lhs.height < rhs.width * rhs.height) {
						return -1;
					}
					return 0;
				}
			});

			previewSize = sizes.get(0);
			parameters.setPreviewSize(previewSize.width, previewSize.height);

			camera.setParameters(parameters);
			camera.setPreviewCallback(this);
		}
	}

	private void releaseCamera() {
		if (null != camera) {
			camera.stopPreview();
			camera.setPreviewCallback(null);
			camera.release();
			camera = null;
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		Log.d(logTag, "onPause");

		if (true == isFinishing()) {
			audioTask.cancel(true);
		}

		releaseCamera();
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		Log.d(logTag, "surfaceChanged");

		try {

			camera.setPreviewDisplay(holder);
			camera.startPreview();
		} catch (Exception e) {
			e.printStackTrace();

			/*
			 * If something went wrong, release the camera...
			 */
			releaseCamera();
		}

	}

	public void surfaceCreated(SurfaceHolder holder) {
		Log.d(logTag, "surfaceCreated");
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.d(logTag, "surfaceDestroyed");
		releaseCamera();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);

		/*
		 * If we didn't gain focus (i.e. became visible), we do nothing
		 */
		if (false == hasFocus) {
			return;
		}

		/*
		 * first is width, second is height.
		 */
		Pair<Integer, Integer> layoutSize = null;

		View layout = findViewById(R.id.layout);
		layoutSize = new Pair<Integer, Integer>(layout.getWidth(),
				layout.getHeight());

		Log.d(logTag, "onWindowFocusChanged. layout size: " + layoutSize.first
				+ " " + layoutSize.second);

		/*
		 * Resize the surface view such that it fills the layout but keeps the
		 * aspect ratio
		 */
		SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surface);
		ViewGroup.LayoutParams layoutParams = surfaceView.getLayoutParams();

		/*
		 * Calculate the two scaling factors to scale the surface up to the
		 * whole width/height of the encompassing layout
		 */
		double widthFactor = (double) layoutSize.first
				/ (double) previewSize.width;

		double heightFactor = (double) layoutSize.second
				/ (double) previewSize.height;

		/*
		 * Take the bigger of the two since we want the preview to fill the
		 * screen (this results in some parts of the preview not being visible.
		 * The alternative would be a preview that doesn't fill the whole screen
		 */
		double scaleFactor = Math.max(widthFactor, heightFactor);

		layoutParams.width = (int) Math.ceil(scaleFactor * previewSize.width);
		layoutParams.height = (int) Math.ceil(scaleFactor * previewSize.height);

		Log.d(logTag, "new size: " + layoutParams.width + " "
				+ layoutParams.height);

		surfaceView.setLayoutParams(layoutParams);

		findViewById(R.id.layout).requestLayout();
	}

	private class AudioTask extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... arg0) {
			Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

			short[] samples = new short[minBufferSize];

			Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight,
					Bitmap.Config.ARGB_8888);

			Log.d(logTag, "buffersize: " + minBufferSize);

			while (false == isCancelled()) {
				if (false == bitmapQueue.isEmpty()) {
					bitmap = bitmapQueue.remove();

					final Bitmap bmp = bitmap;
					runOnUiThread(new Runnable() {

						public void run() {
							((ImageView) findViewById(R.id.image))
									.setBackgroundDrawable(new BitmapDrawable(
											bmp));

						}
					});
					// Log.d(logTag, "bitmap");
				}

				float[] red = new float[bitmapHeight * bitmapWidth];
				float[] green = new float[bitmapHeight * bitmapWidth];
				float[] blue = new float[bitmapHeight * bitmapWidth];

				for (int height = 0; height < bitmapHeight; ++height) {
					for (int width = 0; width < bitmapWidth; ++width) {
						int c = bitmap.getPixel(width, height);
						red[height * bitmapWidth + width] = Color.red(c);
						green[height * bitmapWidth + width] = Color.green(c);
						blue[height * bitmapWidth + width] = Color.blue(c);
					}
				}

				synth(samples, (float) samplingRate, (float) bpm, bitmapWidth,
						bitmapHeight, red, green, blue, frequencies);

				audioTrack.write(samples, 0, samples.length);
			}

			return null;
		}

	}

	public void onPreviewFrame(byte[] data, Camera camera) {
		if (false == bitmapQueue.isEmpty()) {
			return;
		}

		YuvImage yuvimage = new YuvImage(data, ImageFormat.NV21,
				previewSize.width, previewSize.height, null);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		yuvimage.compressToJpeg(new Rect(0, 0, previewSize.width,
				previewSize.height), 80, baos);

		byte[] imageBytes = baos.toByteArray();
		Bitmap bitmap = Bitmap
				.createScaledBitmap(BitmapFactory.decodeByteArray(imageBytes,
						0, imageBytes.length), bitmapWidth, bitmapHeight, true);

		bitmapQueue.add(bitmap);
	}
}
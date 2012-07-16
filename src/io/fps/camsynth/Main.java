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
import android.os.Process;

public class Main extends Activity implements SurfaceHolder.Callback,
		Camera.PreviewCallback {
	static String logTag = Main.class.toString();

	int bitmapWidth = 8;
	int bitmapHeight = 2;

	double bpm = 14.0;

	int samplingRate = 22050;

	int minBufferSize = AudioTrack.getMinBufferSize(samplingRate,
			AudioFormat.CHANNEL_CONFIGURATION_MONO,
			AudioFormat.ENCODING_PCM_16BIT);

	AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
			samplingRate, AudioFormat.CHANNEL_CONFIGURATION_MONO,
			AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
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

	@Override
	public void onPause() {
		super.onPause();
		Log.d(logTag, "onPause");

		if (true == isFinishing()) {
			audioTask.cancel(true);
		}

		if (null != camera) {
			camera.stopPreview();
			camera.release();
			camera = null;
		}
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
			camera.release();
			camera = null;
		}

	}

	public void surfaceCreated(SurfaceHolder holder) {
		Log.d(logTag, "surfaceCreated");
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.d(logTag, "surfaceDestroyed");
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

			for (int index = 0; index < minBufferSize; ++index) {
				samples[index] = 0;
				// samples[index] = (short) (Short.MAX_VALUE * Math.random());
			}

			Log.d(logTag, "buffersize: " + minBufferSize);

			int samplePosition = 0;

			while (false == isCancelled()) {
				if (false == bitmapQueue.isEmpty()) {
					bitmap = bitmapQueue.remove();
					//Log.d(logTag, "bitmap");
				}

				int windowLength = (int) (bitmapWidth * (samplingRate / bpm));
				int tickLength = (int) (samplingRate / bpm);

				for (int index = 0; index < minBufferSize; ++index) {
					int positionInBitmap = (int) (bitmapWidth
							* (double) samplePosition / windowLength);
					// Log.d(logTag, "pos: " + positionInBitmap);

					samples[index] = 0;

					for (int note = 0; note < bitmapHeight; ++note) {

						double gain = (double)Color.red(bitmap.getPixel(
								positionInBitmap, note))/1024.0;
						
						int wavelength = 200 / (note + 1);
						if (samplePosition % wavelength  == 0) {
							samples[index] += (short)(gain * (double)Short.MAX_VALUE);
						}	
					}
					
					++samplePosition;
					samplePosition %= windowLength;
				}

				// Log.d(logTag, "samples");
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
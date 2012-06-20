package com.xtremelabs.imageutils;

import java.io.FileNotFoundException;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;

import com.xtremelabs.imageutils.DefaultImageDiskCacher.FileFormatException;

public class ImageCacher {
	@SuppressWarnings("unused")
	private static final String TAG = "ImageCacher";
	private static ImageCacher imageCacher;

	private ImageDiskCacherInterface diskCache;
	private ImageMemoryCacherInterface memoryCache;
	private ImageNetworkInterface networkInterface;

	private ImageCacher(Context appContext) {
		memoryCache = new DefaultImageMemoryLRUCacher();
		diskCache = new DefaultImageDiskCacher(appContext);
		networkInterface = new DefaultImageDownloader(appContext, diskCache);
	}

	public static synchronized ImageCacher getInstance(Context appContext) {
		if (imageCacher == null) {
			imageCacher = new ImageCacher(appContext);
		}
		return imageCacher;
	}

	/**
	 * Retrieves and caches the requested bitmap. The bitmap returned by this method is always the full sized image.
	 * 
	 * @param url
	 *            The location of the image on the web.
	 * @param listener
	 *            If the image is not synchronously available with this call, the bitmap will be returned at some time in the future to this listener, so long as the request is not
	 *            cancelled.
	 * @return Returns the bitmap if it is synchronously available, or null.
	 */
	public Bitmap getBitmap(String url, ImageRequestListener listener) {
		return getBitmapWithScale(url, listener, 1);
	}

	/**
	 * This method will return a (potentially) scaled down version of the requested image. If the image requested is smaller than at least one of the bounds provided, the full
	 * image will be returned. Otherwise, this method will return an image that is guaranteed to be larger than both bounds, and potentially scaled down from its original size in
	 * order to conserve memory. Enter "null" for either the width or height if you do not want the image to be scaled relative to that dimension.
	 * 
	 * Example 1:
	 * 
	 * Let the requested image be of size 640x480. The width specified is 120 pixels, and the height is null (and therefore ignored). The returned image will be 160x120 pixels.
	 * 
	 * Example 2:
	 * 
	 * Let the requested image be of size 300x300. The width specified is 50px, the height is specified at 140px. The returned image will be 150x150.
	 * 
	 * @param url
	 *            Location of the image on the web.
	 * @param listener
	 *            If the image is not synchronously available, null will be returned, and the image will eventually be sent to this listener.
	 * @param width
	 *            The width (in pixels) of the display area for the image. This is usually the width of the ImageView or ImageButton that the image will be loaded to.
	 * @param height
	 *            The height (in pixels) of the display area for the image. This is usually the height of the ImageView or ImageButton that the image will be loaded to.
	 * @return Returns a reference to the bitmap if it is synchronously available. Otherwise null is returned, and the bitmap will eventually be returned to the listener if the
	 *         request is not cancelled.
	 */
	public synchronized Bitmap getBitmapWithBounds(final String url, final ImageRequestListener listener, final Integer width, final Integer height) {
		validateUrl(url);
		
		SampleSizeFetcher fetcher = generateSampleSizeFetcher(url, width, height);
		NetworkImageRequestListener networkImageRequestListener = getNewNetworkImageRequestListener(url, listener, fetcher);
		if (!networkInterface.queueIfLoadingFromNetwork(url, networkImageRequestListener)) {
			/*
			 * We only know the sample size if the image is currently cached on disk. Otherwise we need it from the network first.
			 */
			if (diskCache.isCached(url)) {
				try {
					return getBitmapFromMemoryOrDisk(url, listener, diskCache.getSampleSize(url, width, height));
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (FileFormatException e) {
					e.printStackTrace();
				}
			}

			loadImageFromNetwork(url, listener, generateSampleSizeFetcher(url, width, height));
		}
		return null;
	}

	private SampleSizeFetcher generateSampleSizeFetcher(final String url, final Integer width, final Integer height) {
		return new SampleSizeFetcher() {
			@Override
			public int onSampleSizeRequired() throws FileNotFoundException {
				try {
					return diskCache.getSampleSize(url, width, height);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
					throw e;
				}
			}
		};
	}

	/**
	 * Retrieves and caches the bitmap for the provided url. The bitmap returned will be scaled by the provided sample size.
	 * 
	 * @param url
	 *            Location of the image on the web.
	 * @param listener
	 *            If the image is not synchronously available, null will be returned, and the image will eventually be sent to this listener.
	 * @param sampleSize
	 *            This parameter MUST BE A POWER OF 2 unless you have good reason to pick a non-power-of-two value. The width and height dimensions of the image being returned will
	 *            be divided by this sample size.
	 * @return Returns a reference to the bitmap if it is synchronously available. Otherwise null is returned, and the bitmap will eventually be returned to the listener if the
	 *         request is not cancelled.
	 */
	public synchronized Bitmap getBitmapWithScale(final String url, final ImageRequestListener listener, final int sampleSize) {
		validateUrl(url);

		NetworkImageRequestListener networkImageRequestListener = getNewNetworkImageRequestListener(url, listener, new SampleSizeFetcher() {
			@Override
			public int onSampleSizeRequired() throws FileNotFoundException {
				return sampleSize;
			}
		});
		
		if (!networkInterface.queueIfLoadingFromNetwork(url, networkImageRequestListener)) {
			Bitmap bitmap = null;
			try {
				bitmap = getBitmapFromMemoryOrDisk(url, listener, sampleSize);
			} catch (FileNotFoundException e) {
				// We get to ignore this exception safely.
			} catch (FileFormatException e) {
				// We get to ignore this exception safely.
			}

			if (bitmap != null) {
				return bitmap;
			} else {
				loadImageFromNetwork(url, listener, new SampleSizeFetcher() {
					@Override
					public int onSampleSizeRequired() {
						return sampleSize;
					}
				});
			}
		}
		return null;
	}

	// TODO: Trigger a network request for the image. We may want to move this into the database...
	// FIXME: What happens during the error condition?
	public Point getImageDimensions(String url) throws FileNotFoundException {
		return diskCache.getImageDimensions(url);
	}

	private synchronized Bitmap getBitmapFromMemoryOrDisk(String url, ImageRequestListener listener, int sampleSize) throws FileNotFoundException, FileFormatException {
		Bitmap bitmap;
		if ((bitmap = memoryCache.getBitmap(url, sampleSize)) != null) {
			diskCache.bump(url);
			return bitmap;
		} else if (diskCache.isCached(url)) {
			return loadImageFromDisk(url, listener, sampleSize);
		} else {
			throw new FileNotFoundException();
		}
	}

	/**
	 * Cancels any asynchronous requests that are currently pending for the provided url that uses the provided listener.
	 * 
	 * @param url
	 * @param listener
	 */
	public synchronized void cancelRequestForBitmap(String url, ImageRequestListener listener) {
		validateUrl(url);

		if (listener.networkRequestListener != null) {
			networkInterface.cancelRequest(url, listener.networkRequestListener);
		}
		// diskCache.cancelRequest(url, listener);
	}

	/**
	 * Caches the image at the provided url to disk. If the image is already on disk, it gets bumped on the eviction queue.
	 * 
	 * @param url
	 */
	public synchronized void precacheImage(String url) {
		validateUrl(url);

		// TODO: This is a race condition with this "isCached" call.
		if (!diskCache.isCached(url)) {
			// Request the image with a blank listener. We don't care what happens when it's done.
			networkInterface.loadImageToDisk(url, new NetworkImageRequestListener() {
				@Override
				public void onSuccess() {
				}

				@Override
				public void onFailure() {
				}
			});
		} else {
			diskCache.bump(url);
		}
	}

	private synchronized Bitmap loadImageFromDisk(final String url, final ImageRequestListener listener, final int sampleSize) throws FileNotFoundException, FileFormatException {
		if (diskCache.synchronousDiskCacheEnabled()) {
			Bitmap bitmap = diskCache.getBitmapSynchronouslyFromDisk(url, sampleSize);
			memoryCache.cacheBitmap(bitmap, url, sampleSize);
			return bitmap;
		} else {
			diskCache.getBitmapAsynchronousFromDisk(url, sampleSize, new DiskCacherListener() {
				@Override
				public void onImageDecoded(Bitmap bitmap) {
					memoryCache.cacheBitmap(bitmap, url, sampleSize);
					listener.onImageAvailable(bitmap);
				}
			});
			return null;
		}
	}

	private synchronized void loadImageFromNetwork(String url, ImageRequestListener listener, SampleSizeFetcher sampleSizeFetcher) {
		NetworkImageRequestListener networkListener = getNewNetworkImageRequestListener(url, listener, sampleSizeFetcher);
		listener.networkRequestListener = networkListener;
		networkInterface.loadImageToDisk(url, networkListener);
	}

	private NetworkImageRequestListener getNewNetworkImageRequestListener(final String url, final ImageRequestListener listener, final SampleSizeFetcher sampleSizeFetcher) {
		return new NetworkImageRequestListener() {
			@Override
			public void onSuccess() {
				onNetworkListenerSuccess(url, listener, sampleSizeFetcher);
			}

			@Override
			public void onFailure() {
				listener.onFailure("Failed to get image from the network!");
			}
		};
	}

	private synchronized void onNetworkListenerSuccess(final String url, final ImageRequestListener listener, final SampleSizeFetcher sampleSizeFetcher) {
		Bitmap bitmap = null;
		try {
			bitmap = getBitmapFromMemoryOrDisk(url, listener, sampleSizeFetcher.onSampleSizeRequired());
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			listener.onFailure("File not found after it was downloaded!");
		} catch (FileFormatException e) {
			e.printStackTrace();
			listener.onFailure("File format exception after the file was downloaded!");
		}
		if (bitmap != null) {
			listener.onImageAvailable(bitmap);
		}
	}

	private void validateUrl(String url) {
		if (url == null || url.length() == 0) {
			throw new IllegalArgumentException("Null URL passed into the image system.");
		}
	}

	private interface SampleSizeFetcher {
		public int onSampleSizeRequired() throws FileNotFoundException;
	}

	public static abstract class ImageRequestListener {
		private NetworkImageRequestListener networkRequestListener;

		public abstract void onImageAvailable(Bitmap bitmap);

		public abstract void onFailure(String message);
	}

	public void clearMemCache() {
		memoryCache.clearCache();
	}

	public void setMemCacheSize(int size) {
		memoryCache.setMaximumCacheSize(size);
	}

	public ImageMemoryCacherInterface getMemCacher() {
		return memoryCache;
	}
}

package com.fsck.k9.activity.misc;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.util.LruCache;
import android.text.TextUtils;
import android.widget.ImageView;

import com.fsck.k9.helper.Contacts;
import com.fsck.k9.mail.Address;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContactPictureLoader {
    /**
     * Resize the pictures to the following value (device-independent pixels).
     */
    private static final int PICTURE_SIZE = 40;

    /**
     * Pattern to extract the letter to be displayed as fallback image.
     */
    private static final Pattern EXTRACT_LETTER_PATTERN = Pattern.compile("[a-zA-Z]");

    /**
     * Letter to use when {@link #EXTRACT_LETTER_PATTERN} couldn't find a match.
     */
    private static final String FALLBACK_CONTACT_LETTER = "?";


    private ContentResolver mContentResolver;
    private Resources mResources;
    private Contacts mContactsHelper;
    private int mPictureSizeInPx;

    private int mDefaultBackgroundColor;

    /**
     * LRU cache of contact pictures.
     */
    private final LruCache<Address, Bitmap> mBitmapCache;

    /**
     * @see <a href="http://developer.android.com/design/style/color.html">Color palette used</a>
     */
    private final static int CONTACT_DUMMY_COLORS_ARGB[] = {
            0xffE06055,
            0xffAED581,
            0xffF6BF26,
            0xff9E9E9E,
            0xff9FA8DA,
            0xff4DB6AC,
            0xff7986CB,
            0xffBA68C8,
            0xffFF8A65,
            0xff4DD0E1,
            0xff5E97F6,
            0xff91A4AE,
            0xff9575CD,
            0xffFFA726,
            0xff57BB8A,
            0xff90A4AE
    };

    /**
     * Constructor.
     *
     * @param context
     *         A {@link Context} instance.
     * @param defaultBackgroundColor
     *         The ARGB value to be used as background color for the fallback picture. {@code 0} to
     *         use a dynamically calculated background color.
     */
    public ContactPictureLoader(Context context, int defaultBackgroundColor) {
        Context appContext = context.getApplicationContext();
        mContentResolver = appContext.getContentResolver();
        mResources = appContext.getResources();
        mContactsHelper = Contacts.getInstance(appContext);

        float scale = mResources.getDisplayMetrics().density;
        mPictureSizeInPx = (int) (PICTURE_SIZE * scale);

        mDefaultBackgroundColor = defaultBackgroundColor;

        ActivityManager activityManager =
                (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        int memClass = activityManager.getMemoryClass();

        // Use 1/16th of the available memory for this memory cache.
        final int cacheSize = 1024 * 1024 * memClass / 16;

        mBitmapCache = new LruCache<Address, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(Address key, Bitmap bitmap) {
                // The cache size will be measured in bytes rather than number of items.
                return bitmap.getByteCount();
            }
        };
    }

    /**
     * Load a contact picture and display it using the supplied {@link ImageView} instance.
     *
     * <p>
     * If a picture is found in the cache, it is displayed in the {@code QuickContactBadge}
     * immediately. Otherwise a {@link ContactPictureRetrievalTask} is started to try to load the
     * contact picture in a background thread. Depending on the result the contact picture or a
     * fallback picture is then stored in the bitmap cache.
     * </p>
     *
     * @param address
     *         The {@link Address} instance holding the email address that is used to search the
     *         contacts database.
     * @param imageView
     *         The {@code QuickContactBadge} instance to receive the picture.
     *
     * @see #mBitmapCache
     * @see #calculateFallbackBitmap(Address)
     */
    public void loadContactPicture(Address address, ImageView imageView) {
        Bitmap bitmap = getBitmapFromCache(address);
        if (bitmap != null) {
            // The picture was found in the bitmap cache
            imageView.setImageBitmap(bitmap);
        } else if (cancelPotentialWork(address, imageView)) {
            // Query the contacts database in a background thread and try to load the contact
            // picture, if there is one.
            ContactPictureRetrievalTask task = new ContactPictureRetrievalTask(imageView, address);
            AsyncDrawable asyncDrawable = new AsyncDrawable(mResources,
                    calculateFallbackBitmap(address), task);
            imageView.setImageDrawable(asyncDrawable);
            try {
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (RejectedExecutionException e) {
                // We flooded the thread pool queue... use a fallback picture
                imageView.setImageBitmap(calculateFallbackBitmap(address));
            }
        }
    }

    private int calcUnknownContactColor(Address address) {
        if (mDefaultBackgroundColor != 0) {
            return mDefaultBackgroundColor;
        }

        int val = address.hashCode();
        int colorIndex = (val & Integer.MAX_VALUE) % CONTACT_DUMMY_COLORS_ARGB.length;
        return CONTACT_DUMMY_COLORS_ARGB[colorIndex];
    }

    private String calcUnknownContactLetter(Address address) {
        String letter = null;
        String personal = address.getPersonal();
        String str = (personal != null) ? personal : address.getAddress();

        Matcher m = EXTRACT_LETTER_PATTERN.matcher(str);
        if (m.find()) {
            letter = m.group(0).toUpperCase(Locale.US);
        }

        return (TextUtils.isEmpty(letter)) ?
                FALLBACK_CONTACT_LETTER : letter.substring(0, 1);
    }

    /**
     * Calculates a bitmap with a color and a capital letter for contacts without picture.
     */
    private Bitmap calculateFallbackBitmap(Address address) {
        Bitmap result = Bitmap.createBitmap(mPictureSizeInPx, mPictureSizeInPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        int circleColor = calcUnknownContactColor(address);
        String letter = calcUnknownContactLetter(address);

        Paint circlePaint = new Paint();
        Paint textPaint = new Paint();

        textPaint.setAntiAlias(true);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(mPictureSizeInPx * 3 / 5); // just scale this down a bit
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            textPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        Rect rect = new Rect();
        textPaint.getTextBounds(letter, 0, 1, rect);
        float width = textPaint.measureText(letter);

        circlePaint.setColor(circleColor);
        circlePaint.setAntiAlias(true);
        float r = mPictureSizeInPx / 2f;

        canvas.drawCircle(r, r, r, circlePaint);
        canvas.drawText(letter,
                r - (width / 2f),
                r + (rect.height() / 2f),
                textPaint);

        return result;
    }

    private void addBitmapToCache(Address key, Bitmap bitmap) {
        if (getBitmapFromCache(key) == null) {
            mBitmapCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFromCache(Address key) {
        return mBitmapCache.get(key);
    }

    /**
     * Checks if a {@code ContactPictureRetrievalTask} was already created to load the contact
     * picture for the supplied {@code Address}.
     *
     * @param address
     *         The {@link Address} instance holding the email address that is used to search the
     *         contacts database.
     * @param imageView
     *         The {@link ImageView} instance that will receive the picture.
     *
     * @return {@code true}, if the contact picture should be loaded in a background thread.
     *         {@code false}, if another {@link ContactPictureRetrievalTask} was already scheduled
     *         to load that contact picture.
     */
    private boolean cancelPotentialWork(Address address, ImageView imageView) {
        final ContactPictureRetrievalTask task = getContactPictureRetrievalTask(imageView);

        if (task != null && address != null) {
            if (!address.equals(task.getAddress())) {
                // Cancel previous task
                task.cancel(true);
            } else {
                // The same work is already in progress
                return false;
            }
        }

        // No task associated with the QuickContactBadge, or an existing task was cancelled
        return true;
    }

    private ContactPictureRetrievalTask getContactPictureRetrievalTask(ImageView imageView) {
        if (imageView != null) {
           Drawable drawable = imageView.getDrawable();
           if (drawable instanceof AsyncDrawable) {
               AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
               return asyncDrawable.getContactPictureRetrievalTask();
           }
        }

        return null;
    }


    /**
     * Load a contact picture in a background thread.
     */
    class ContactPictureRetrievalTask extends AsyncTask<Void, Void, Bitmap> {
        private final WeakReference<ImageView> mImageViewReference;
        private final Address mAddress;

        ContactPictureRetrievalTask(ImageView imageView, Address address) {
            mImageViewReference = new WeakReference<ImageView>(imageView);
            mAddress = new Address(address);
        }

        public Address getAddress() {
            return mAddress;
        }

        @Override
        protected Bitmap doInBackground(Void... args) {
            final String email = mAddress.getAddress();
            final Uri photoUri = mContactsHelper.getPhotoUri(email);
            Bitmap bitmap = null;
            if (photoUri != null) {
                try {
                    InputStream stream = mContentResolver.openInputStream(photoUri);
                    if (stream != null) {
                        try {
                            Bitmap tempBitmap = BitmapFactory.decodeStream(stream);
                            if (tempBitmap != null) {
                                bitmap = Bitmap.createScaledBitmap(tempBitmap, mPictureSizeInPx,
                                        mPictureSizeInPx, true);
                                if (tempBitmap != bitmap) {
                                    tempBitmap.recycle();
                                }
                            }
                        } finally {
                            try { stream.close(); } catch (IOException e) { /* ignore */ }
                        }
                    }
                } catch (FileNotFoundException e) {
                    /* ignore */
                }

            }

            if (bitmap == null) {
                bitmap = calculateFallbackBitmap(mAddress);
            } else {
                bitmap = transformToCircleBitmap(bitmap);
            }

            // Save the picture of the contact with that email address in the bitmap cache
            addBitmapToCache(mAddress, bitmap);

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            ImageView imageView = mImageViewReference.get();
            if (imageView != null && getContactPictureRetrievalTask(imageView) == this) {
                imageView.setImageBitmap(bitmap);
            }
        }
    }

    /**
     * {@code Drawable} subclass that stores a reference to the {@link ContactPictureRetrievalTask}
     * that is trying to load the contact picture.
     *
     * <p>
     * The reference is used by {@link ContactPictureLoader#cancelPotentialWork(Address,
     * ImageView)} to find out if the contact picture is already being loaded by a
     * {@code ContactPictureRetrievalTask}.
     * </p>
     */
    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<ContactPictureRetrievalTask> mAsyncTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap, ContactPictureRetrievalTask task) {
            super(res, bitmap);
            mAsyncTaskReference = new WeakReference<ContactPictureRetrievalTask>(task);
        }

        public ContactPictureRetrievalTask getContactPictureRetrievalTask() {
            return mAsyncTaskReference.get();
        }
    }

    private Bitmap transformToCircleBitmap(Bitmap rectBitmap) {
        int size = rectBitmap.getWidth();

        // if bitmap is non-square first create square one
        if (size != rectBitmap.getHeight()) {
            int sizeX = size;
            int sizeY = rectBitmap.getHeight();
            size = Math.min(sizeY, sizeX);
            sizeX = (sizeX - size) / 2;
            sizeY = (sizeY - size) / 2;

            Bitmap squareSource = Bitmap.createBitmap(rectBitmap, sizeX, sizeY, size, size);

            rectBitmap.recycle();
            rectBitmap = squareSource;
        }

        Bitmap circleBitmap = Bitmap.createBitmap(size, size, rectBitmap.getConfig());
        Canvas canvas = new Canvas(circleBitmap);
        Paint paint = new Paint();
        float centerAndRadius = size / 2f;
        BitmapShader shader = new BitmapShader(rectBitmap, BitmapShader.TileMode.CLAMP, BitmapShader.TileMode.CLAMP);

        paint.setShader(shader);
        paint.setAntiAlias(true);
        canvas.drawCircle(centerAndRadius, centerAndRadius, centerAndRadius, paint);

        rectBitmap.recycle();

        return circleBitmap;
    }
}

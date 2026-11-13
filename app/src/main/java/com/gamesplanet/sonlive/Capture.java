package com.gamesplanet.sonlive;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;

public class Capture extends Service {
    static volatile byte[] frame;
    private MediaProjection projection;
    private VirtualDisplay display;
    private HandlerThread worker;
    private Handler handler;
    private ImageReader reader;
    private Bitmap pixels;
    private long lastFrame;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || projection != null) return START_NOT_STICKY;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("capture", "Screen source", NotificationManager.IMPORTANCE_LOW));
        startForeground(10, new Notification.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("SonLive is capturing your screen")
            .setOngoing(true).build());
        try {
            Intent consent = intent.getParcelableExtra("data");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", 0), consent);
            worker = new HandlerThread("studio-capture");
            worker.start();
            handler = new Handler(worker.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
                @Override public void onCapturedContentResize(int width, int height) {
                    if (display != null && width > 0 && height > 0) configure(width, height, density());
                }
            }, handler);
            DisplayMetrics metrics = new DisplayMetrics();
            getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            configure(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
        } catch (RuntimeException error) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private int density() { return getResources().getDisplayMetrics().densityDpi; }

    private synchronized void configure(int width, int height, int density) {
        double scale = Math.min(1, 960.0 / Math.max(width, height));
        int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
        if (projection == null) return;
        if (reader != null && reader.getWidth() == w && reader.getHeight() == h) return;
        ImageReader oldReader = reader;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::readFrame, handler);
        if (display == null) {
            display = projection.createVirtualDisplay("Studio screen", w, h, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        } else {
            display.resize(w, h, density);
            display.setSurface(reader.getSurface());
        }
        if (oldReader != null) oldReader.close();
    }

    private synchronized void readFrame(ImageReader source) {
        if (source != reader) return;
        try (Image image = source.acquireLatestImage()) {
            long now = SystemClock.elapsedRealtime();
            if (image == null || now - lastFrame < 80) return;
            lastFrame = now;
            Image.Plane plane = image.getPlanes()[0];
            int width = image.getWidth(), height = image.getHeight();
            int padded = plane.getRowStride() / plane.getPixelStride();
            if (pixels == null || pixels.getWidth() != padded || pixels.getHeight() != height) {
                if (pixels != null) pixels.recycle();
                pixels = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888);
            }
            pixels.copyPixelsFromBuffer(plane.getBuffer());
            Bitmap cropped = Bitmap.createBitmap(pixels, 0, 0, width, height);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 65, out);
            if (cropped != pixels) cropped.recycle();
            frame = out.toByteArray();
        } catch (RuntimeException ignored) { }
    }

    @Override public synchronized void onDestroy() {
        if (display != null) display.release();
        if (reader != null) reader.close();
        reader = null;
        if (pixels != null) pixels.recycle();
        if (projection != null) projection.stop();
        projection = null;
        display = null;
        frame = null;
        if (worker != null) worker.quitSafely();
        super.onDestroy();
    }
}

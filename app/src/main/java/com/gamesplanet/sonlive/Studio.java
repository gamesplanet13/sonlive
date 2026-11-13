package com.gamesplanet.sonlive;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.*;

public class Studio extends Activity {
    private WebView browser;
    private android.net.Uri recordingUri;
    private OutputStream recordingFile;
    private boolean recordingFailed;

    private synchronized void discardRecording() {
        if (recordingFile != null) {
            try { recordingFile.close(); } catch (IOException ignored) { }
        }
        recordingFile = null;
        if (recordingUri != null) getContentResolver().delete(recordingUri, null, null);
        recordingUri = null;
    }
    private StudioServer server;
    private PermissionRequest pending;
    private ValueCallback<android.net.Uri[]> picker;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            server = new StudioServer(this);
        } catch (IOException e) {
            setContentView(new android.widget.TextView(this) {{ setText("Studio could not open its local server."); }});
            return;
        }
        browser = new WebView(this);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setMediaPlaybackRequiresUserGesture(false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return true;
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!server.localOrigin().equals(request.getOrigin().toString().replaceAll("/$", ""))) {
                        request.deny();
                        return;
                    }
                    java.util.ArrayList<String> missing = new java.util.ArrayList<>();
                    for (String resource : request.getResources()) {
                        String permission;
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) permission = Manifest.permission.CAMERA;
                        else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) permission = Manifest.permission.RECORD_AUDIO;
                        else { request.deny(); return; }
                        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
                    }
                    if (missing.isEmpty()) request.grant(request.getResources());
                    else {
                        if (pending != null) pending.deny();
                        pending = request;
                        requestPermissions(missing.toArray(new String[0]), 7);
                    }
                });
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pending == request) pending = null;
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<android.net.Uri[]> cb, FileChooserParams params) {
                if (picker != null) picker.onReceiveValue(null);
                picker = cb;
                try { startActivityForResult(params.createIntent(), 8); }
                catch (android.content.ActivityNotFoundException e) { picker = null; cb.onReceiveValue(null); }
                return true;
            }
        });
        browser.addJavascriptInterface(new Object() {
            @JavascriptInterface public void startSave(String filename, String mime) {
                synchronized (Studio.this) {
                    discardRecording();
                    recordingFailed = false;
                    if (!filename.matches("SonLive-[0-9]+[.](webm|mp4)")) return;
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime.split(";")[0]);
                    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/SonLive");
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                    try {
                        recordingUri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (recordingUri != null) recordingFile = getContentResolver().openOutputStream(recordingUri);
                    } catch (IOException | RuntimeException error) { discardRecording(); }
                }
            }
            @JavascriptInterface public void saveChunk(String data) {
                synchronized (Studio.this) {
                    if (recordingFile == null || recordingFailed) return;
                    try { recordingFile.write(android.util.Base64.decode(data, android.util.Base64.DEFAULT)); }
                    catch (IOException | IllegalArgumentException error) { recordingFailed = true; }
                }
            }
            @JavascriptInterface public boolean finishSave() {
                synchronized (Studio.this) {
                    if (recordingFile == null || recordingFailed) { discardRecording(); return false; }
                    try {
                        recordingFile.close();
                        recordingFile = null;
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                        if (getContentResolver().update(recordingUri, values, null, null) == 0) {
                            discardRecording();
                            return false;
                        }
                        recordingUri = null;
                        return true;
                    } catch (IOException | RuntimeException error) { discardRecording(); return false; }
                }
            }
            @JavascriptInterface public void pictureInPicture() {
                runOnUiThread(() -> {
                    try {
                        if (!enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(new android.util.Rational(16, 9)).build())) {
                            Toast.makeText(Studio.this, "Picture-in-picture is unavailable.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (RuntimeException e) {
                        Toast.makeText(Studio.this, "Picture-in-picture is unavailable.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @JavascriptInterface public String remoteUrl() { return server.remoteUrl(); }
            @JavascriptInterface public void copy(String value) {
                runOnUiThread(() -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Studio remote", value));
                    Toast.makeText(Studio.this, "Remote link copied", Toast.LENGTH_SHORT).show();
                });
            }
            @JavascriptInterface public void screen(boolean enable) {
                runOnUiThread(() -> {
                    if (!enable) {
                        stopService(new Intent(Studio.this, Capture.class));
                        return;
                    }
                    startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 9);
                });
            }
        }, "StudioDevice");
        setContentView(browser);
        browser.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        browser.loadUrl(server.localOrigin() + "/studio#" + server.hostKey);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 7 || pending == null) return;
        boolean allowed = results.length > 0;
        for (int result : results) allowed &= result == PackageManager.PERMISSION_GRANTED;
        if (allowed) pending.grant(pending.getResources()); else pending.deny();
        pending = null;
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 8 && picker != null) {
            picker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            picker = null;
        }
        if (code == 9 && result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, Capture.class).putExtra("result", result).putExtra("data", data));
        }
    }

    @Override protected void onDestroy() {
        discardRecording();
        if (server != null) server.close();
        if (browser != null) browser.destroy();
        stopService(new Intent(this, Capture.class));
        super.onDestroy();
    }
}

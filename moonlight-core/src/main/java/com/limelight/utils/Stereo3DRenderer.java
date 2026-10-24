package com.limelight.utils;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import com.limelight.preferences.PreferenceConfiguration;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Stands in for Artemis' AI 2D-to-3D renderer, which GameLodge leaves out (OpenCV, LiteRT and the
 * depth model would add ~48 MB). Same API, so Artemis compiles unchanged; the render mode option is
 * hidden and reset to 2D, so the stream never constructs it.
 */
public class Stereo3DRenderer implements GLSurfaceView.Renderer {
    public static boolean isMovieMode = true;
    public static volatile float fps = 0;
    public static volatile float threeDFps = 0;
    public static volatile float drawDelay = 0.0f;
    public static Boolean isDebugMode = false;
    public static Boolean isActive = false;
    public static String renderer = "CPU";

    public interface OnSurfaceReadyListener {
        void onStereo3DSurfaceReady(Surface surface);
    }

    public Stereo3DRenderer(GLSurfaceView view, OnSurfaceReadyListener listener, Context context, PreferenceConfiguration prefConfig) {
    }

    public void setPrefConfig(PreferenceConfiguration prefConfig) {
    }

    public void onSurfaceDestroyed() {
    }

    public Surface getVideoSurface() {
        return null;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
    }

    @Override
    public void onDrawFrame(GL10 gl) {
    }
}

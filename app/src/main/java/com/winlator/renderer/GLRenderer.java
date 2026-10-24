package com.winlator.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import androidx.core.graphics.ColorUtils;

import com.winlator.R;
import com.winlator.core.Bitmask;
import com.winlator.core.Callback;
import com.winlator.core.GPUHelper;
import com.winlator.core.ImageUtils;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.material.CursorMaterial;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.WindowMaterial;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Decoration;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    public final XServerView xServerView;
    private final XServer xServer;
    protected final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private boolean forceWindowsFullscreen;
    private volatile boolean preparedWindowAspectFit;
    private volatile boolean preparedCenteredNativeSize;
    private volatile long preparedContentDimensions;
    private boolean rotatePreparedWindowsCounterClockwise;
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    protected volatile boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private float cursorScale = 1.0f;
    private int cursorBackColor = 0xffffff;
    private int cursorForeColor = 0x000000;
    private boolean screenOffsetYRelativeToCursor = false;
    private float magnifierZoom = 1.0f;
    protected short surfaceWidth;
    protected short surfaceHeight;
    public final EffectComposer effectComposer = new EffectComposer(this);

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();

        quadVertices.put(new float[]{
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUHelper.setGlobalEGLContext();

        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = (short)width;
        surfaceHeight = (short)height;
        updateViewTransformation(viewTransformation, width, height);
        viewportNeedsUpdate = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }

        if (effectComposer.hasEffects()) {
            effectComposer.render();
        }
        else drawFrame();
    }

    protected void drawFrame() {
        if (viewportNeedsUpdate) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
            else {
                updateViewTransformation(viewTransformation, surfaceWidth, surfaceHeight);
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                    viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        float pointerX = 0;
        float pointerY = 0;
        float magnifierZoom = !screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;

        if (magnifierZoom != 1.0f) {
            pointerX = Mathf.clamp(xServer.pointer.getX() * magnifierZoom - xServer.screenInfo.width * 0.5f, 0, xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
        }

        if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
            float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
            float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
            pointerY = Mathf.clamp(xServer.pointer.getY() * magnifierZoom - offsetY, 0, xServer.screenInfo.height * scaleY);
        }

        XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0);

        renderWindows();
        if (cursorVisible) renderCursor();
    }

    @Override
    public void onMapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        if (resized) {
            xServerView.queueEvent(this::updateScene);
        }
        else xServerView.queueEvent(() -> updateWindowPosition(window));
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) xServerView.requestRender();
    }

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }

    private void renderCursorDrawable(Drawable drawable, int x, int y) {
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable();

            XForm.set(tmpXForm1, x, y, drawable.width * cursorScale, drawable.height * cursorScale);
            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            cursorMaterial.setUniformColor(cursorMaterial.uniforms.backColor, cursorBackColor);
            cursorMaterial.setUniformColor(cursorMaterial.uniforms.foreColor, cursorForeColor);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            cursorMaterial.setUniformInt(cursorMaterial.uniforms.texture, 0);
            cursorMaterial.setUniformFloatArray(cursorMaterial.uniforms.xform, tmpXForm1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindowDrawable(
            Drawable drawable,
            int x,
            int y,
            boolean transparent,
            FullscreenTransformation fullscreenTransformation,
            boolean rotateCounterClockwise) {
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable();

            if (rotateCounterClockwise) {
                // The explicit portrait preset describes the upright canvas.
                // Wine can expose either a landscape or portrait backing client;
                // rotating a portrait backing must not widen the upright image.
                int portraitWidth = Math.min(drawable.width, drawable.height);
                int portraitHeight = Math.max(drawable.width, drawable.height);
                float scale = Math.min(
                    (float)xServer.screenInfo.width / portraitWidth,
                    (float)xServer.screenInfo.height / portraitHeight);
                float targetWidth = portraitWidth * scale;
                float targetHeight = portraitHeight * scale;
                float targetX = (xServer.screenInfo.width - targetWidth) * 0.5f;
                float targetY = (xServer.screenInfo.height - targetHeight) * 0.5f;

                // Rotate only the prepared game's large borderless client.
                // The X desktop and Android controls stay landscape while the
                // portrait framebuffer is presented upright and aspect-fit.
                XForm.set(
                    tmpXForm1,
                    0.0f, -targetHeight,
                    targetWidth, 0.0f,
                    targetX, targetY + targetHeight);
            }
            else if (fullscreenTransformation != null) {
                XForm.set(tmpXForm1, fullscreenTransformation.x, fullscreenTransformation.y, fullscreenTransformation.width, fullscreenTransformation.height);
            }
            else XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);

            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            windowMaterial.setUniformInt(windowMaterial.uniforms.texture, 0);
            windowMaterial.setUniformFloat(windowMaterial.uniforms.noAlpha, !transparent ? 1.0f : 0.0f);
            windowMaterial.setUniformFloatArray(windowMaterial.uniforms.xform, tmpXForm1);
            windowMaterial.setUniformBool(windowMaterial.uniforms.flipY, texture.isFlipY());

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindows() {
        windowMaterial.use();
        windowMaterial.setUniformVec2(windowMaterial.uniforms.viewSize, xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            for (RenderableWindow window : renderableWindows) {
                if (!window.content.isOffscreenStorage()) {
                    renderWindowDrawable(
                        window.content,
                        window.rootX,
                        window.rootY,
                        window.transparent,
                        window.fullscreenTransformation,
                        window.rotateCounterClockwise);
                }
            }
        }

        quadVertices.disable();
    }

    private void renderCursor() {
        cursorMaterial.use();
        cursorMaterial.setUniformVec2(cursorMaterial.uniforms.viewSize, xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            if (cursor != null) {
                if (cursor.isVisible()) renderCursorDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY);
            }
            else renderCursorDrawable(rootCursorDrawable, x, y);
        }

        quadVertices.disable();
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
            updatePreparedContentDimensions();
        }
    }

    private void updatePreparedContentDimensions() {
        int width = 0;
        int height = 0;
        if (preparedWindowAspectFit && forceWindowsFullscreen) {
            for (RenderableWindow window : renderableWindows) {
                if (!window.preparedContentCandidate || window.content.isOffscreenStorage()) continue;
                if (window.rotateCounterClockwise) {
                    int portraitWidth = Math.min(window.content.width, window.content.height);
                    int portraitHeight = Math.max(window.content.width, window.content.height);
                    float scale = Math.min(
                        (float)xServer.screenInfo.width / portraitWidth,
                        (float)xServer.screenInfo.height / portraitHeight);
                    width = Math.max(width, Math.round(portraitWidth * scale));
                    height = Math.max(height, Math.round(portraitHeight * scale));
                }
                else if (window.fullscreenTransformation != null) {
                    width = Math.max(width, window.fullscreenTransformation.width);
                    height = Math.max(height, window.fullscreenTransformation.height);
                }
                else {
                    // A full desktop-sized client has no inner transform. It
                    // must participate so another window cannot crop it.
                    width = Math.max(width, window.content.width);
                    height = Math.max(height, window.content.height);
                }
            }
        }
        if (width > 0 && height > 0) {
            float left = (xServer.screenInfo.width - width) * 0.5f;
            float top = (xServer.screenInfo.height - height) * 0.5f;
            for (RenderableWindow window : renderableWindows) {
                if (!window.preparedViewportBlocker || window.content.isOffscreenStorage()) continue;
                if (window.rootX < left - 1 || window.rootY < top - 1 ||
                    window.rootX + window.content.width > left + width + 1 ||
                    window.rootY + window.content.height > top + height + 1) {
                    // Native dialogs and decorated windows must remain whole.
                    // Resume client fitting after the overlapping UI closes.
                    width = height = 0;
                    break;
                }
            }
        }
        // Every fullscreen transformation is centered. Their union preserves
        // every visible client when a game temporarily has several windows.
        long dimensions = ((long)width << 32) | (height & 0xffffffffL);
        if (preparedContentDimensions != dimensions) {
            preparedContentDimensions = dimensions;
            viewportNeedsUpdate = true;
        }
    }

    public void updateViewTransformation(ViewTransformation target, int width, int height) {
        long dimensions = preparedContentDimensions;
        if (preparedCenteredNativeSize && !forceWindowsFullscreen && !fullscreen) {
            // Centered presentation preserves native pixels on larger panels.
            // Smaller panels still fit the complete desktop without cropping.
            target.updateCenteredAtMostNative(width, height,
                xServer.screenInfo.width, xServer.screenInfo.height);
        }
        else if (preparedWindowAspectFit && forceWindowsFullscreen && !fullscreen && dimensions != 0) {
            target.updateForContent(width, height, xServer.screenInfo.width, xServer.screenInfo.height,
                (int)(dimensions >>> 32), (int)dimensions);
        }
        else target.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
    }

    public void setPreparedWindowAspectFit(boolean enabled) {
        preparedWindowAspectFit = enabled;
        viewportNeedsUpdate = true;
    }

    public void setPreparedCenteredNativeSize(boolean enabled) {
        preparedCenteredNativeSize = enabled;
        viewportNeedsUpdate = true;
    }

    public float[] getPreparedGameViewportBounds(int width, int height) {
        if (preparedCenteredNativeSize && !forceWindowsFullscreen && !fullscreen &&
            width > 1 && height > 1) {
            ViewTransformation transformation = new ViewTransformation();
            updateViewTransformation(transformation, width, height);
            return new float[]{
                transformation.viewOffsetX, transformation.viewOffsetY,
                xServer.screenInfo.width * transformation.aspect,
                xServer.screenInfo.height * transformation.aspect
            };
        }
        long dimensions = preparedContentDimensions;
        if (!preparedWindowAspectFit || !forceWindowsFullscreen || fullscreen ||
            dimensions == 0 || width <= 1 || height <= 1)
            return null;
        int contentWidth = (int)(dimensions >>> 32);
        int contentHeight = (int)dimensions;
        ViewTransformation transformation = new ViewTransformation();
        transformation.updateForContent(width, height, xServer.screenInfo.width,
            xServer.screenInfo.height, contentWidth, contentHeight);
        return new float[]{
            transformation.viewOffsetX + (xServer.screenInfo.width - contentWidth) * 0.5f * transformation.aspect,
            transformation.viewOffsetY + (xServer.screenInfo.height - contentHeight) * 0.5f * transformation.aspect,
            contentWidth * transformation.aspect,
            contentHeight * transformation.aspect
        };
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.isRenderable()) return;
        if (window != xServer.windowManager.rootWindow && window.attributes.isViewable()) {
            Window parent = window.getParent();
            boolean transparent = window.attributes.isTransparent() || parent.attributes.isTransparent() || parent.isLayered() || window.isLayered();
            boolean preparedContentCandidate = !window.isDesktopWindow() && window.getType() == Window.Type.NORMAL &&
                window.hasNoDecorations() && window.getWidth() >= ScreenInfo.MIN_WIDTH &&
                window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            boolean preparedViewportBlocker = !window.isDesktopWindow() &&
                (window.getType() == Window.Type.DIALOG || !window.hasNoDecorations());

            if (forceWindowsFullscreen) {
                short width = window.getWidth();
                short height = window.getHeight();
                FullscreenTransformation fullscreenTransformation = null;

                boolean needsAspectFit =
                    width >= ScreenInfo.MIN_WIDTH && height >= ScreenInfo.MIN_HEIGHT &&
                    (width != xServer.screenInfo.width || height != xServer.screenInfo.height);
                if (window.getType() == Window.Type.NORMAL && needsAspectFit && window.hasNoDecorations()) {
                    fullscreenTransformation = window.getFullscreenTransformation();
                    if (fullscreenTransformation == null) window.setFullscreenTransformation(fullscreenTransformation = new FullscreenTransformation(window));
                    fullscreenTransformation.update(xServer.screenInfo, window.getWidth(), window.getHeight());

                    if (parent != xServer.windowManager.rootWindow && parent.getChildCount() == 1 && parent.hasDecoration(Decoration.BORDER) && parent.hasDecoration(Decoration.TITLE)) {
                        FullscreenTransformation parentFullscreenTransformation = parent.getFullscreenTransformation();
                        if (parentFullscreenTransformation == null) parent.setFullscreenTransformation(parentFullscreenTransformation = new FullscreenTransformation(parent));
                        parentFullscreenTransformation.update(xServer.screenInfo, parent.getWidth(), parent.getHeight());

                        removeRenderableWindow(parent);
                    }
                    else parent.setFullscreenTransformation(null);
                }
                else window.setFullscreenTransformation(null);

                renderableWindows.add(new RenderableWindow(
                    window.getContent(),
                    x,
                    y,
                    transparent,
                    fullscreenTransformation,
                    shouldRotatePreparedWindow(window), preparedContentCandidate, preparedViewportBlocker));
            }
            else renderableWindows.add(new RenderableWindow(
                window.getContent(),
                x,
                y,
                transparent,
                null,
                shouldRotatePreparedWindow(window), preparedContentCandidate, preparedViewportBlocker));
        }

        if (window.attributes.isRenderSubwindows()) {
            for (Window child : window.getChildren()) {
                collectRenderableWindows(child, child.getX() + x, child.getY() + y);
            }
        }
    }

    private boolean shouldRotatePreparedWindow(Window window) {
        return rotatePreparedWindowsCounterClockwise &&
            window.getType() == Window.Type.NORMAL &&
            window.hasNoDecorations() &&
            window.getWidth() >= ScreenInfo.MIN_WIDTH &&
            window.getHeight() >= ScreenInfo.MIN_HEIGHT;
    }

    public void setRotatePreparedWindowsCounterClockwise(boolean enabled) {
        rotatePreparedWindowsCounterClockwise = enabled;
        viewportNeedsUpdate = true;
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    private void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
        updatePreparedContentDimensions();
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public float getCursorScale() {
        return cursorScale;
    }

    public void setCursorScale(float cursorScale) {
        this.cursorScale = cursorScale;
    }

    public int getCursorColor() {
        return cursorBackColor;
    }

    public void setCursorColor(int cursorColor) {
        this.cursorBackColor = cursorColor;
        this.cursorForeColor = ColorUtils.calculateLuminance(cursorColor) < 0.5f ? 0xffffff : 0x000000;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public boolean isForceWindowsFullscreen() {
        return forceWindowsFullscreen;
    }

    public void setForceWindowsFullscreen(boolean forceWindowsFullscreen) {
        this.forceWindowsFullscreen = forceWindowsFullscreen;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public int[] getPixelsARGB(int x, int y, int width, int height, boolean flipY) {
        ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(x, y, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);

        IntBuffer colors = pixelBuffer.asIntBuffer();
        int[] result = new int[width * height];
        if (flipY) {
            for (int i = 0; i < height; i++) {
                colors.position((height - i - 1) * width);
                colors.get(result, i * width, width);
            }
        }
        else colors.get(result);

        for (int i = 0; i < result.length; i++) {
            result[i] = ((result[i] & 0xff00ff00)) | ((result[i] & 0x000000ff) << 16) | ((result[i] & 0x00ff0000) >> 16);
        }
        return result;
    }

    public void takeWindowScreenshot(final Drawable drawable, final Callback<Bitmap> callback) {
        xServerView.queueEvent(() -> {
            synchronized (drawable.renderLock) {
                Texture texture = drawable.getTexture();
                texture.updateFromDrawable();

                int[] framebufferSize = ImageUtils.getScaledSize(drawable.width, drawable.height, 0, 256);

                RenderTarget renderTarget = new RenderTarget();
                renderTarget.allocateFramebuffer(framebufferSize[0], framebufferSize[1]);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderTarget.getFramebuffer());
                GLES20.glViewport(0, 0, framebufferSize[0], framebufferSize[1]);
                viewportNeedsUpdate = true;
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                ScreenMaterial material = new ScreenMaterial();
                material.use();
                material.setUniformBool(material.uniforms.flipY, texture.isFlipY());
                quadVertices.bind(material.programId);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
                material.setUniformInt(material.uniforms.screenTexture, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                quadVertices.disable();

                int[] colors = getPixelsARGB(0, 0, framebufferSize[0], framebufferSize[1], false);
                Bitmap bitmap = Bitmap.createBitmap(colors, framebufferSize[0], framebufferSize[1], Bitmap.Config.ARGB_8888);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                renderTarget.destroy();
                material.destroy();

                callback.call(bitmap);
            }
        });
        xServerView.requestRender();
    }
}

package com.winlator.renderer;

public class ViewTransformation {
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        updateForContent(outerWidth, outerHeight, innerWidth, innerHeight, innerWidth, innerHeight);
    }

    public void updateCenteredAtMostNative(int outerWidth, int outerHeight,
                                          int innerWidth, int innerHeight) {
        updateForContent(outerWidth, outerHeight, innerWidth, innerHeight,
            innerWidth, innerHeight, 1.0f);
    }

    public void updateForContent(int outerWidth, int outerHeight, int innerWidth, int innerHeight,
                                 int contentWidth, int contentHeight) {
        updateForContent(outerWidth, outerHeight, innerWidth, innerHeight,
            contentWidth, contentHeight, Float.POSITIVE_INFINITY);
    }

    private void updateForContent(int outerWidth, int outerHeight, int innerWidth, int innerHeight,
                                  int contentWidth, int contentHeight, float maximumScale) {
        if (contentWidth <= 0 || contentHeight <= 0 ||
            contentWidth > innerWidth || contentHeight > innerHeight) {
            contentWidth = innerWidth;
            contentHeight = innerHeight;
        }
        // The complete game client has already been fitted inside the desktop.
        // Fit that client to the panel; fitting the desktop a second time adds
        // unwanted margins on panels whose aspect differs from the desktop.
        aspect = Math.min(maximumScale,
            Math.min((float)outerWidth / contentWidth, (float)outerHeight / contentHeight));
        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetY = (int)((outerHeight - innerHeight * aspect) * 0.5f);

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }
}

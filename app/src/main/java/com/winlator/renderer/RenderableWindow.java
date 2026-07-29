package com.winlator.renderer;

import com.winlator.xserver.Drawable;

class RenderableWindow {
    final Drawable content;
    short rootX;
    short rootY;
    final boolean transparent;
    final FullscreenTransformation fullscreenTransformation;
    final boolean rotateCounterClockwise;

    public RenderableWindow(
            Drawable content,
            int rootX,
            int rootY,
            boolean transparent,
            FullscreenTransformation fullscreenTransformation,
            boolean rotateCounterClockwise) {
        this.content = content;
        this.rootX = (short)rootX;
        this.rootY = (short)rootY;
        this.transparent = transparent;
        this.fullscreenTransformation = fullscreenTransformation;
        this.rotateCounterClockwise = rotateCounterClockwise;
    }
}

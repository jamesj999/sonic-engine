package com.openggf.graphics;

/** Read-only RGB palette surface consumed by the low-level texture uploader. */
public interface PaletteView {
    byte red(int colorIndex);

    byte green(int colorIndex);

    byte blue(int colorIndex);
}

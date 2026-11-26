package dev.nox.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionPaletteTest {
    @Test fun unknownWallpaperFallsBackToDark() {
        assertEquals(CaptionPalette.DARK, CaptionPalette.forWallpaper(null))
    }
    @Test fun lightWallpaperGetsDarkCaption() {
        assertEquals(CaptionPalette.DARK, CaptionPalette.forWallpaper(0xFFFFFFFF.toInt()))
        assertEquals(CaptionPalette.DARK, CaptionPalette.forWallpaper(0xFF777777.toInt()))
    }
    @Test fun darkWallpaperGetsLightCaption() {
        assertEquals(CaptionPalette.LIGHT, CaptionPalette.forWallpaper(0xFF000000.toInt()))
        assertEquals(CaptionPalette.LIGHT, CaptionPalette.forWallpaper(0xFF333333.toInt()))
    }
}

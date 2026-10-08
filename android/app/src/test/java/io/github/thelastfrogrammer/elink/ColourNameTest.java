package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** WorkshopUi.colourName: plain words for filament colours, spoken and written instead of hex. */
public class ColourNameTest {
    @Test public void commonFilamentColours() {
        assertEquals("red", WorkshopUi.colourName("#D02828"));
        assertEquals("white", WorkshopUi.colourName("#F0F0F0"));
        assertEquals("white", WorkshopUi.colourName("#FFFFFF"));
        assertEquals("black", WorkshopUi.colourName("#000000"));
        assertEquals("black", WorkshopUi.colourName("#101010"));
        assertEquals("blue", WorkshopUi.colourName("#1E5AA8"));
        assertEquals("green", WorkshopUi.colourName("#00FF00"));
        assertEquals("orange", WorkshopUi.colourName("#FFA500"));
        assertEquals("yellow", WorkshopUi.colourName("#FFFF00"));
        assertEquals("brown", WorkshopUi.colourName("#8B4513"));
        assertEquals("purple", WorkshopUi.colourName("#8000FF"));
        assertEquals("pink", WorkshopUi.colourName("#FF69B4"));
        assertEquals("cyan", WorkshopUi.colourName("#00FFFF"));
        assertEquals("dark red", WorkshopUi.colourName("#800000"));
    }

    @Test public void greysByLightness() {
        assertEquals("grey", WorkshopUi.colourName("#808080"));
        assertEquals("light grey", WorkshopUi.colourName("#B3B3B3"));
        assertEquals("dark grey", WorkshopUi.colourName("#4D4D4D"));
    }

    @Test public void acceptsLowerCaseNoHashAndAlpha() {
        assertEquals("red", WorkshopUi.colourName("d02828"));
        assertEquals("red", WorkshopUi.colourName("#ffD02828"));
    }

    @Test public void rejectsNonColours() {
        assertNull(WorkshopUi.colourName(null));
        assertNull(WorkshopUi.colourName(""));
        assertNull(WorkshopUi.colourName("#12"));
        assertNull(WorkshopUi.colourName("#GGGGGG"));
    }

    @Test public void everyPaletteColourOfTheViewerHasAName() {
        for (String colour : GcodeViewerActivity.PALETTE) assertEquals(colour, false, WorkshopUi.colourName(colour) == null);
    }
}

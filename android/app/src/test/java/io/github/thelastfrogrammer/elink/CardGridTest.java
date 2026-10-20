package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Picture cards go two to a row, after whatever heading the grid starts with (a heading once crashed the Files tab). */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class CardGridTest {
    @Test public void aHeadingBeforeTheCardsIsLeftAlone() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        LinearLayout grid = new LinearLayout(context); grid.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(context); heading.setText("Recent slices"); grid.addView(heading);
        for (int i = 0; i < 3; i++) WorkshopUi.addToGrid(grid, new View(context), 8);
        assertEquals("heading, a full row and a half row", 3, grid.getChildCount());
        assertEquals(2, ((LinearLayout) grid.getChildAt(1)).getChildCount());
        assertEquals(1, ((LinearLayout) grid.getChildAt(2)).getChildCount());
    }
}

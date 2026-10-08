package io.github.thelastfrogrammer.elink;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.view.ViewCompat;

/** Small accessibility helpers shared by the screens that are built in code (headings, live regions, names, states, roles). */
final class A11y {
    private A11y() { }

    /** Marks a title so TalkBack's "headings" navigation finds it. */
    static <T extends View> T heading(T view) { ViewCompat.setAccessibilityHeading(view, true); return view; }

    /** Announces changes of this view's text: polite waits for the current speech, assertive interrupts it. */
    static <T extends View> T polite(T view) { view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); return view; }
    static <T extends View> T assertive(T view) { view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE); return view; }

    /** Gives a view a spoken role ("tab", "button") without changing its looks. */
    static <T extends View> T role(T view, String role) { ViewCompat.setAccessibilityDelegate(view, new RoleDelegate(role, null, null)); return view; }

    /** A clickable view that is not a Button (a swatch, a text link): announced as a button. */
    static <T extends View> T button(T view) { return role(view, "Button"); }

    /** A tab of a tab bar: announced as "tab", "selected" when it is. */
    static <T extends View> T tab(T view, boolean selected) { view.setSelected(selected); return role(view, "Tab"); }

    /** Collapsible header: speaks "expanded" or "collapsed" and offers the matching action; the visible glyph stays out of the spoken text. */
    static void expandable(View view, boolean expanded) {
        ViewCompat.setAccessibilityDelegate(view, new RoleDelegate("Button", expanded ? "Expanded" : "Collapsed", expanded));
    }

    /** Names a spinner: TalkBack reads "label, selected value". The label is read when asked, so it can change. */
    static void name(Spinner spinner, java.util.function.Supplier<CharSequence> label) {
        spinner.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                Object item = spinner.getSelectedItem();
                CharSequence name = label.get();
                info.setContentDescription((name == null || name.length() == 0 ? "" : name + ", ") + (item == null ? "" : item.toString()));
            }
        });
    }
    static void name(Spinner spinner, CharSequence label) { name(spinner, () -> label); }

    /** Names a spinner after the text view just above it in the same parent (the app's "caption, then control" layout), if there is one. */
    static void nameFromCaption(Spinner spinner, ViewGroup parent) {
        int index = parent.indexOfChild(spinner) - 1;
        if (index >= 0 && parent.getChildAt(index) instanceof TextView) {
            TextView caption = (TextView) parent.getChildAt(index);
            name(spinner, caption::getText);
        }
    }

    /** Makes `label` the spoken name of `target` (an input, whose own hint may be a value rather than a name). */
    static void labelFor(TextView label, View target) {
        if (target.getId() == View.NO_ID) target.setId(View.generateViewId());
        label.setLabelFor(target.getId());
    }

    /** Replaces the spoken name of a view's click action ("Double tap to dismiss message"). */
    static void clickLabel(View view, String label) {
        ViewCompat.replaceAccessibilityAction(view, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, label, null);
    }

    /** Sets a state description such as "Layer 20 of 32" where the platform supports it (API 30+); older versions read the percentage. */
    static void state(View view, CharSequence text) { if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(text); }

    /** A button that looks disabled by more than hue: half transparent while disabled. */
    static final class DimButton extends android.widget.Button {
        DimButton(android.content.Context context) { super(context); }
        @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); setAlpha(enabled ? 1f : 0.5f); }
    }

    private static final class RoleDelegate extends androidx.core.view.AccessibilityDelegateCompat {
        private final String role, state; private final Boolean expanded;
        RoleDelegate(String role, String state, Boolean expanded) { this.role = role; this.state = state; this.expanded = expanded; }
        @Override public void onInitializeAccessibilityNodeInfo(View host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setRoleDescription(role.toLowerCase(java.util.Locale.ROOT));
            if (state != null && Build.VERSION.SDK_INT >= 30) info.unwrap().setStateDescription(state);
            if (expanded != null) info.addAction(expanded ? androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                : androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND);
            if (state == null && host.isSelected() && Build.VERSION.SDK_INT >= 30) info.unwrap().setStateDescription("Selected");
        }
    }
}

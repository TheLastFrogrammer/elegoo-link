package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

/**
 * The model-site browser's answers to what a page asks for: its alerts, confirmations, text questions and drop-down menus
 * are shown as the app's dialogs. Pop-up windows, file uploads and site passwords in browser dialogs are declined (sign-in
 * forms on the page itself still work). Any dialog left open counts as cancelled.
 */
final class BrowserPrompts implements PromptDelegate {
    private final Activity activity;
    BrowserPrompts(Activity activity) { this.activity = activity; }

    @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt prompt) {
        Answer result = new Answer();
        if (!show(new AlertDialog.Builder(activity).setTitle(title(prompt.title)).setMessage(prompt.message)
            .setPositiveButton("OK", null).setOnDismissListener(d -> result.give(() -> prompt.dismiss())))) result.give(prompt::dismiss);
        return result;
    }

    @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt prompt) {
        Answer result = new Answer();
        if (!show(new AlertDialog.Builder(activity).setTitle(title(prompt.title)).setMessage(prompt.message)
            .setPositiveButton("OK", (d, w) -> result.give(() -> prompt.confirm(ButtonPrompt.Type.POSITIVE)))
            .setNegativeButton("Cancel", (d, w) -> result.give(() -> prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
            .setOnDismissListener(d -> result.give(() -> prompt.dismiss())))) result.give(prompt::dismiss);
        return result;
    }

    @Override public GeckoResult<PromptResponse> onTextPrompt(GeckoSession session, TextPrompt prompt) {
        Answer result = new Answer();
        EditText field = new EditText(activity); field.setInputType(InputType.TYPE_CLASS_TEXT); field.setSingleLine(true);
        if (prompt.defaultValue != null) field.setText(prompt.defaultValue);
        if (prompt.message != null) field.setContentDescription(prompt.message);
        if (!show(new AlertDialog.Builder(activity).setTitle(title(prompt.title)).setMessage(prompt.message).setView(field)
            .setPositiveButton("OK", (d, w) -> result.give(() -> prompt.confirm(field.getText().toString())))
            .setNegativeButton("Cancel", null).setOnDismissListener(d -> result.give(() -> prompt.dismiss())))) result.give(prompt::dismiss);
        return result;
    }

    /** A page's drop-down menu (sort order, file type, …) or list: one choice, or several for a multiple list. */
    @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
        Answer result = new Answer();
        List<ChoicePrompt.Choice> choices = new ArrayList<>(); flatten(prompt.choices, choices);
        String[] labels = new String[choices.size()]; boolean[] checked = new boolean[choices.size()]; int selected = -1;
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label == null ? "" : choices.get(i).label; checked[i] = choices.get(i).selected;
            if (checked[i] && selected < 0) selected = i;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(title(prompt.title != null && !prompt.title.isEmpty() ? prompt.title : prompt.message));
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            builder.setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("OK", (d, w) -> {
                    List<ChoicePrompt.Choice> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i] && !choices.get(i).disabled) picked.add(choices.get(i));
                    result.give(() -> prompt.confirm(picked.toArray(new ChoicePrompt.Choice[0])));
                }).setNegativeButton("Cancel", null);
        } else {
            builder.setSingleChoiceItems(labels, selected, (d, which) -> {
                if (choices.get(which).disabled) return;
                result.give(() -> prompt.confirm(choices.get(which))); d.dismiss();
            });
        }
        if (!show(builder.setOnDismissListener(d -> result.give(() -> prompt.dismiss())))) result.give(prompt::dismiss);
        return result;
    }
    private static void flatten(ChoicePrompt.Choice[] items, List<ChoicePrompt.Choice> out) {
        if (items == null) return;
        for (ChoicePrompt.Choice c : items) {
            if (c.items != null) flatten(c.items, out);        // an option group: its options
            else if (!c.separator) out.add(c);
        }
    }

    // Declined: new windows (the browser opens links in place instead), file uploads, browser password dialogs.
    @Override public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession session, PopupPrompt prompt) { return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY)); }
    @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt prompt) { return GeckoResult.fromValue(prompt.dismiss()); }
    @Override public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession session, AuthPrompt prompt) { return GeckoResult.fromValue(prompt.dismiss()); }
    @Override public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession session, BeforeUnloadPrompt prompt) { return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW)); }
    @Override public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session, RepostConfirmPrompt prompt) {
        Answer result = new Answer();
        if (!show(new AlertDialog.Builder(activity).setTitle("Send the form again?").setMessage("Reloading this page sends what you entered on it again.")
            .setPositiveButton("Send again", (d, w) -> result.give(() -> prompt.confirm(AllowOrDeny.ALLOW)))
            .setNegativeButton("Cancel", null).setOnDismissListener(d -> result.give(() -> prompt.confirm(AllowOrDeny.DENY))))) result.give(() -> prompt.confirm(AllowOrDeny.DENY));
        return result;
    }

    private boolean show(AlertDialog.Builder builder) {
        if (activity.isFinishing() || activity.isDestroyed()) return false;
        builder.show(); return true;
    }
    /** Completes once: the first answer wins (a button, then the dismissal that follows it, is one answer). */
    private static final class Answer extends GeckoResult<PromptResponse> {
        private boolean given;
        void give(java.util.function.Supplier<PromptResponse> response) { if (!given) { given = true; complete(response.get()); } }
    }
    private static String title(String title) { return title == null || title.isEmpty() ? "The page says" : title; }
}

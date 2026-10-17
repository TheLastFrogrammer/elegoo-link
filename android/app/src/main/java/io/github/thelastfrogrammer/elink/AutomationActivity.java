package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Automation workshop: the person's rules (when this happens → do these), ready-made and their own templates, Test, and
 * sharing as text. Imported rules start turned off. Turning on a rule that changes the printer (its light) asks first.
 */
public final class AutomationActivity extends Activity {
    private static final int IMPORT = 1;
    private boolean dark;
    private int ink, muted, surface, accent, chipOff, error;
    private LinearLayout content;
    private List<Automation.Rule> rules;
    private PrinterService printer; private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { if (binder instanceof PrinterService.LocalBinder) printer = ((PrinterService.LocalBinder) binder).service(); }
        @Override public void onServiceDisconnected(ComponentName name) { printer = null; }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; surface = dark ? 0xff182227 : Color.WHITE;
        accent = dark ? 0xff5fd4c4 : 0xff00796b; chipOff = dark ? 0xff21343a : 0xffe2efed; error = dark ? 0xffffb4ab : 0xffba1a1a;
        Diagnostics.init(getFilesDir());
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(dark ? 0xff0e1417 : 0xfff2f5f6); scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        setContentView(scroll);
        bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
        rules = AutomationRun.rules(this);
        show();
    }
    @Override protected void onDestroy() { if (bound) { unbindService(connection); bound = false; } super.onDestroy(); }

    private void show() {
        content.removeAllViews();
        A11y.heading(text(content, "Automation workshop", 24, ink, true));
        text(content, "Rules run while the app watches the printer: when something happens, the app tells you, other apps or Home Assistant, or turns the printer's light on or off. "
            + "The light is the only thing a rule can change on the printer. It is sent once per event and never repeated; the log says whether the printer reported the change.", 13, muted, false);
        LinearLayout top = row(content);
        action(top, "New rule", () -> edit(newRule(), true));
        action(top, "From a template…", this::chooseTemplate);
        LinearLayout more = row(content);
        action(more, "Import…", this::importDialog);
        action(more, "Share all…", () -> share(rules, "Link Workshop automations"));
        action(more, "Values…", this::valuesDialog);
        action(more, "Log", () -> startActivity(new Intent(this, LogsActivity.class).putExtra(LogsActivity.EXTRA_AREA, Diagnostics.AUTOMATIONS)));

        A11y.heading(text(content, "Your rules", 17, ink, true)).setPadding(0, dp(16), 0, dp(4));
        if (rules.isEmpty()) text(content, "No rules yet. Start from a template, or make a new one.", 13, muted, false);
        for (Automation.Rule rule : rules) ruleCard(rule);

        List<Automation.Rule> mine = AutomationRun.myTemplates(this);
        A11y.heading(text(content, "My templates", 17, ink, true)).setPadding(0, dp(16), 0, dp(4));
        text(content, mine.isEmpty() ? "Save a rule as a template to reuse or share it, or import one someone shared with you." : "Your own templates and imported ones. Use makes a rule from one; Share hands it on as text.", 13, muted, false);
        for (Automation.Rule t : mine) templateCard(t, mine);
        text(content, "Values… lists everything a rule can send or watch, with what the printer reports now. Notifications and webhooks can also attach the latest camera picture and a chart of the print. Webhook header values (tokens) stay on this phone and are never shared.", 12, muted, false).setPadding(0, dp(16), 0, 0);
    }

    private void ruleCard(Automation.Rule rule) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(android.view.Gravity.CENTER_VERTICAL); card.addView(head);
        TextView name = new TextView(this); name.setText(rule.name); name.setTextSize(16); name.setTextColor(ink); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Switch on = new Switch(this); on.setChecked(rule.enabled); on.setContentDescription("Rule " + rule.name + " on"); on.setMinHeight(dp(48));
        on.setOnCheckedChangeListener((v, checked) -> {
            if (checked && rule.changesPrinter()) {
                new AlertDialog.Builder(this).setTitle("Let this rule change the printer?")
                    .setMessage("“" + rule.name + "” turns the printer's light " + lightWords(rule) + " by itself. It sends the command once per event, only when the app has a live connection (through the cloud only after you agreed to cloud control), and never repeats it.")
                    .setNegativeButton("Cancel", (d, w) -> v.setChecked(false)).setOnCancelListener(d -> v.setChecked(false))
                    .setPositiveButton("Turn on", (d, w) -> { rule.enabled = true; saveRules(); Diagnostics.note(Diagnostics.AUTOMATIONS, "\"" + rule.name + "\" turned on"); }).show();
                return;
            }
            rule.enabled = checked; saveRules(); Diagnostics.note(Diagnostics.AUTOMATIONS, "\"" + rule.name + "\" turned " + (checked ? "on" : "off"));
        });
        head.addView(on, new LinearLayout.LayoutParams(-2, -2));
        text(card, rule.summary(), 13, muted, false);
        LinearLayout buttons = row(card);
        action(buttons, "Edit", () -> edit(rule, false));
        action(buttons, "Test", () -> test(rule));
        action(buttons, "More…", () -> {
            List<String> choices = new ArrayList<>(java.util.Arrays.asList("Share…", "Save as a template", "Duplicate"));
            if (!rule.builtIn) choices.add("Delete");
            new AlertDialog.Builder(this).setTitle(rule.name).setItems(choices.toArray(new String[0]), (d, which) -> {
                String chosen = choices.get(which);
                try {
                    if (chosen.equals("Share…")) share(Collections.singletonList(rule), rule.name);
                    else if (chosen.equals("Save as a template")) { List<Automation.Rule> mine = AutomationRun.myTemplates(this); mine.add(rule.copy()); AutomationRun.saveTemplates(this, mine); toast("Saved to My templates."); show(); }
                    else if (chosen.equals("Duplicate")) { Automation.Rule copy = rule.copy(); copy.name = rule.name + " (copy)"; copy.enabled = false; rules.add(copy); saveRules(); show(); }
                    else new AlertDialog.Builder(this).setTitle("Delete “" + rule.name + "”?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d2, w2) -> { rules.remove(rule); saveRules(); show(); }).show();
                } catch (Exception failed) { toast("Could not do that: " + failed.getMessage()); }
            }).show();
        });
    }

    private void templateCard(Automation.Rule t, List<Automation.Rule> mine) {
        LinearLayout card = card();
        text(card, t.name, 16, ink, true); text(card, t.summary(), 13, muted, false);
        LinearLayout buttons = row(card);
        action(buttons, "Use", () -> use(t));
        action(buttons, "Share…", () -> share(Collections.singletonList(t), t.name));
        action(buttons, "Delete", () -> { mine.remove(t); AutomationRun.saveTemplates(this, mine); show(); });
    }

    private void chooseTemplate() {
        List<Automation.Rule> all = new ArrayList<>(Automation.templates()); all.addAll(AutomationRun.myTemplates(this));
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) names[i] = all.get(i).name;
        new AlertDialog.Builder(this).setTitle("Start from a template").setItems(names, (d, which) -> use(all.get(which))).setNegativeButton("Cancel", null).show();
    }
    private void use(Automation.Rule template) {
        try { Automation.Rule r = template.copy(); r.enabled = !r.changesPrinter(); edit(r, true); }
        catch (Exception failed) { toast("Could not use the template: " + failed.getMessage()); }
    }
    private Automation.Rule newRule() { Automation.Rule r = new Automation.Rule(); r.name = ""; Automation.Action a = new Automation.Action(); a.title = "{event_name}"; a.text = "{file}"; r.actions.add(a); return r; }

    /** The rule editor: a form in a dialog; Save checks it and keeps the rule (a new rule is added to the list). */
    private void edit(Automation.Rule rule, boolean adding) {
        ScrollView scroll = new ScrollView(this); LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0); scroll.addView(form);
        EditText name = field(form, "Name", rule.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text(form, "When", 13, muted, true);
        List<String> eventIds = new ArrayList<>(Automation.EVENTS.keySet());
        Spinner event = new Spinner(this); event.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>(Automation.EVENTS.values())));
        event.setSelection(Math.max(0, eventIds.indexOf(rule.event))); event.setMinimumHeight(dp(48)); form.addView(event);
        EditText layer = field(form, "Layer number", rule.layer > 0 ? String.valueOf(rule.layer) : "", InputType.TYPE_CLASS_NUMBER);
        // A value rule: which value, and for "crosses a limit" the direction and the limit.
        String[] watched = {rule.field};
        Button watchedButton = new Button(this); watchedButton.setAllCaps(false); watchedButton.setMinHeight(dp(48)); form.addView(watchedButton, new LinearLayout.LayoutParams(-1, -2));
        Runnable watchedLabel = () -> watchedButton.setText(watched[0].isEmpty() ? "Choose the value…" : "Value: {" + watched[0] + "}");
        watchedLabel.run();
        RadioGroup direction = new RadioGroup(this); RadioButton reaches = new RadioButton(this), below = new RadioButton(this);
        reaches.setText("reaches or goes above"); below.setText("goes below"); reaches.setTextColor(ink); below.setTextColor(ink); direction.addView(reaches); direction.addView(below); form.addView(direction);
        (rule.above ? reaches : below).setChecked(true);
        EditText limit = field(form, "Limit (a number)", rule.field.isEmpty() ? "" : Automation.number(rule.limit), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        Runnable layerShown = () -> {
            String id = eventIds.get(event.getSelectedItemPosition());
            layer.setVisibility(id.equals(Automation.LAYER) ? View.VISIBLE : View.GONE);
            boolean value = id.equals(Automation.VALUE), change = id.equals(Automation.CHANGE);
            watchedButton.setVisibility(value || change ? View.VISIBLE : View.GONE);
            direction.setVisibility(value ? View.VISIBLE : View.GONE); limit.setVisibility(value ? View.VISIBLE : View.GONE);
        };
        watchedButton.setOnClickListener(v -> pickValue(eventIds.get(event.getSelectedItemPosition()).equals(Automation.VALUE), false, key -> { watched[0] = key; watchedLabel.run(); }));
        event.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { layerShown.run(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        layerShown.run();
        text(form, "Only if", 13, muted, true);
        EditText from = field(form, "From time, e.g. 22:00 (blank: any)", rule.fromMinute >= 0 ? Automation.clock(rule.fromMinute) : "", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        EditText to = field(form, "Until time, e.g. 07:00 (blank: any)", rule.toMinute >= 0 ? Automation.clock(rule.toMinute) : "", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        CheckBox last = new CheckBox(this); last.setText("Only for a print marked as the last of the day (Print tab, Automations…)"); last.setTextColor(ink); last.setChecked(rule.lastPrintOnly); last.setMinHeight(dp(48)); form.addView(last);
        EditText delay = field(form, "Wait minutes before acting (0–240)", rule.delayMinutes > 0 ? String.valueOf(rule.delayMinutes) : "", InputType.TYPE_CLASS_NUMBER);
        String[] condition = {rule.ifField};
        Button conditionButton = new Button(this); conditionButton.setAllCaps(false); conditionButton.setMinHeight(dp(48)); form.addView(conditionButton, new LinearLayout.LayoutParams(-1, -2));
        RadioGroup ifDirection = new RadioGroup(this); RadioButton ifAbove = new RadioButton(this), ifBelow = new RadioButton(this);
        ifAbove.setText("is at or above"); ifBelow.setText("is below"); ifAbove.setTextColor(ink); ifBelow.setTextColor(ink); ifDirection.addView(ifAbove); ifDirection.addView(ifBelow); form.addView(ifDirection);
        (rule.ifAbove ? ifAbove : ifBelow).setChecked(true);
        EditText ifLimit = field(form, "Number", rule.ifField.isEmpty() ? "" : Automation.number(rule.ifLimit), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        Runnable conditionShown = () -> {
            conditionButton.setText(condition[0].isEmpty() ? "And only while a value… (none)" : "And only while {" + condition[0] + "}");
            ifDirection.setVisibility(condition[0].isEmpty() ? View.GONE : View.VISIBLE); ifLimit.setVisibility(condition[0].isEmpty() ? View.GONE : View.VISIBLE);
        };
        conditionShown.run();
        conditionButton.setOnClickListener(v -> pickValue(true, true, key -> { condition[0] = key; conditionShown.run(); }));
        text(form, "Do", 13, muted, true);
        List<Automation.Action> actions = new ArrayList<>();   // copies, so Cancel leaves the rule as it was
        for (Automation.Action a : rule.actions) try { actions.add(Automation.Action.fromJson(a.toJson(false))); } catch (Exception unreadable) { actions.add(a); }
        LinearLayout actionList = new LinearLayout(this); actionList.setOrientation(LinearLayout.VERTICAL); form.addView(actionList);
        Runnable[] listActions = new Runnable[1];
        listActions[0] = () -> {
            actionList.removeAllViews();
            for (Automation.Action a : actions) {
                LinearLayout line = row(actionList);
                TextView what = new TextView(this); what.setText(a.describe()); what.setTextColor(ink); what.setTextSize(14); what.setPadding(0, dp(12), 0, 0);
                line.addView(what, new LinearLayout.LayoutParams(0, -2, 2));
                action(line, "Edit", () -> editAction(a, listActions[0]));
                action(line, "Remove", () -> { actions.remove(a); listActions[0].run(); });
            }
            LinearLayout add = row(actionList);
            action(add, "Add an action…", () -> {
                List<String> kinds = new ArrayList<>(Automation.ACTIONS.keySet());
                new AlertDialog.Builder(this).setTitle("Add an action").setItems(Automation.ACTIONS.values().toArray(new String[0]), (d, which) -> {
                    if (actions.size() >= 8) { toast("At most 8 actions per rule."); return; }
                    Automation.Action a = new Automation.Action(); a.kind = kinds.get(which);
                    if (a.kind.equals(Automation.NOTIFY)) { a.title = "{event_name}"; a.text = "{file}"; }
                    actions.add(a); listActions[0].run(); editAction(a, listActions[0]);
                }).show();
            });
        };
        listActions[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(adding ? "New rule" : "Edit rule").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String eventId = eventIds.get(event.getSelectedItemPosition());
            int layerNumber = number(layer.getText().toString(), 0), wait = number(delay.getText().toString(), 0);
            int fromMinute = clock(from.getText().toString()), toMinute = clock(to.getText().toString());
            String problem = actions.isEmpty() ? "Add at least one action."
                : eventId.equals(Automation.LAYER) && layerNumber < 1 ? "Enter the layer number."
                : fromMinute == -2 || toMinute == -2 ? "Write times as hours:minutes, such as 22:00."
                : wait > 240 ? "Wait at most 240 minutes." : "";
            boolean valueRule = eventId.equals(Automation.VALUE), changeRule = eventId.equals(Automation.CHANGE);
            double limitValue = decimal(limit.getText().toString()), ifLimitValue = decimal(ifLimit.getText().toString());
            if ((valueRule || changeRule) && watched[0].isEmpty()) problem = "Choose the value to watch.";
            else if (valueRule && Double.isNaN(limitValue)) problem = "Enter the limit as a number.";
            else if (!condition[0].isEmpty() && Double.isNaN(ifLimitValue)) problem = "Enter the number for “and only while”.";
            for (Automation.Action a : actions) if (a.kind.equals(Automation.WEBHOOK) && !Automation.webhookUrlOk(a.url)) problem = "A webhook needs an address starting with https:// or http://.";
            if (!problem.isEmpty()) { toast(problem); return; }
            boolean wasChanging = rule.changesPrinter();
            String typed = Automation.cap(name.getText().toString().trim(), 80);
            rule.name = Automation.cap(!typed.isEmpty() ? typed : (eventId.equals(Automation.LAYER) ? "Layer " + layerNumber : valueRule || changeRule ? "{" + watched[0] + "} " + (changeRule ? "changes" : (reaches.isChecked() ? "reaches " : "below ") + limit.getText().toString().trim()) : Automation.EVENTS.get(eventId)), 80);
            rule.event = eventId; rule.layer = eventId.equals(Automation.LAYER) ? layerNumber : 0; rule.fromMinute = fromMinute; rule.toMinute = toMinute;
            rule.lastPrintOnly = last.isChecked(); rule.delayMinutes = wait; rule.actions.clear(); rule.actions.addAll(actions);
            rule.field = valueRule || changeRule ? watched[0] : ""; rule.above = reaches.isChecked(); rule.limit = valueRule ? limitValue : 0;
            rule.ifField = condition[0]; rule.ifAbove = ifAbove.isChecked(); rule.ifLimit = condition[0].isEmpty() ? 0 : ifLimitValue;
            if (!wasChanging && rule.changesPrinter() && rule.enabled && !adding) rule.enabled = false;   // a new light action is turned on deliberately, with the question
            if (adding) { if (rule.changesPrinter()) rule.enabled = false; rules.add(rule); }
            saveRules(); Diagnostics.note(Diagnostics.AUTOMATIONS, "\"" + rule.name + "\" " + (adding ? "added" : "changed"));
            dialog.dismiss(); show();
            if (rule.changesPrinter() && !rule.enabled) toast("Saved. Rules that change the printer start off: turn this one on in the list.");
        }));
        dialog.show();
    }

    private void editAction(Automation.Action a, Runnable done) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        EditText title = null, body = null, url = null, header = null, secret = null; RadioButton lightOn = null;
        switch (a.kind) {
            case Automation.NOTIFY:
                title = field(form, "Title", a.title, InputType.TYPE_CLASS_TEXT); body = field(form, "Text", a.text, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                insertButton(form, title, body);
                text(form, "Type a value's name in braces, such as {nozzle}, or use Insert a value. A notification shows one picture: the camera picture when both are chosen.", 12, muted, false); break;
            case Automation.LIGHT: {
                RadioGroup group = new RadioGroup(this); lightOn = new RadioButton(this); lightOn.setText("Turn the light on"); RadioButton off = new RadioButton(this); off.setText("Turn the light off");
                lightOn.setTextColor(ink); off.setTextColor(ink); group.addView(lightOn); group.addView(off); form.addView(group); (a.on ? lightOn : off).setChecked(true);
                text(form, "Sent once per event while the app has a live connection; never repeated.", 12, muted, false); break;
            }
            case Automation.WEBHOOK: {
                url = field(form, "Address (https://…)", a.url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                body = field(form, "Body (blank: all values as JSON)", a.body, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                header = field(form, "Header name for a token (optional), e.g. Authorization", a.header, InputType.TYPE_CLASS_TEXT);
                boolean kept = false; try { kept = new CredentialStore(this).remembers("automation:" + a.secretId); } catch (Exception unreadable) { }
                secret = field(form, kept ? "Header value (kept; blank keeps it)" : "Header value, e.g. Bearer …", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                insertButton(form, body);
                text(form, "Sent as a POST. A body starting with { is sent as JSON with each value escaped. With a picture or chart it is sent as a form: the body as payload_json (JSON) or content (text), the images as files[0] and files[1], as Discord takes them. The header value is kept encrypted on this phone and never shared.", 12, muted, false);
                break;
            }
            case Automation.HA_EVENT:
                text(form, "Sends the event to the “Print event” entity of the Home Assistant link (Settings, Home Assistant), for automations there. Nothing comes back to the printer.", 13, ink, false); break;
            default:
                text(form, "Sends the Android broadcast " + AutomationRun.BROADCAST_ACTION + " with each fill-in as a text extra (event, file, layer, …). In Tasker: Event → System → Intent Received, action as written here. Any app on the phone can receive it.", 13, ink, false);
        }
        CheckBox picture = null, chart = null, raw = null; EditText messageHeader = null;
        if (!a.kind.equals(Automation.LIGHT) && !a.kind.equals(Automation.BROADCAST)) {
            if (!a.kind.equals(Automation.HA_EVENT)) { picture = new CheckBox(this); picture.setText("Attach the latest camera picture"); picture.setChecked(a.picture); picture.setTextColor(ink); picture.setMinHeight(dp(48)); form.addView(picture); }
            chart = new CheckBox(this); chart.setText(a.kind.equals(Automation.HA_EVENT) ? "Also send a chart of the print to the “Print chart” camera" : "Attach a chart of this print (temperatures and progress)");
            chart.setChecked(a.chart); chart.setTextColor(ink); chart.setMinHeight(dp(48)); form.addView(chart);
        }
        if (a.kind.equals(Automation.WEBHOOK)) {
            raw = new CheckBox(this); raw.setText("Send one picture as the whole body, the text in a header (ntfy and similar)"); raw.setChecked(a.rawImage); raw.setTextColor(ink); raw.setMinHeight(dp(48)); form.addView(raw);
            messageHeader = field(form, "Header for the text (ntfy: Message)", a.messageHeader, InputType.TYPE_CLASS_TEXT);
        }
        CheckBox fPicture = picture, fChart = chart, fRaw = raw; EditText fMessage = messageHeader;
        EditText fTitle = title, fBody = body, fUrl = url, fHeader = header, fSecret = secret; RadioButton fOn = lightOn;
        new AlertDialog.Builder(this).setTitle(Automation.ACTIONS.get(a.kind)).setView(scroll).setPositiveButton("Done", (d, w) -> {
            if (fTitle != null) a.title = Automation.cap(fTitle.getText().toString(), 200);
            if (fBody != null && a.kind.equals(Automation.NOTIFY)) a.text = Automation.cap(fBody.getText().toString(), 2000);
            if (fOn != null) a.on = fOn.isChecked();
            if (fPicture != null) a.picture = fPicture.isChecked(); if (fChart != null) a.chart = fChart.isChecked();
            if (fRaw != null) { a.rawImage = fRaw.isChecked(); String h = fMessage.getText().toString().trim(); a.messageHeader = h.matches("[A-Za-z0-9-]{1,40}") ? h : "Message"; }
            if (fUrl != null) {
                a.url = Automation.cap(fUrl.getText().toString().trim(), 2000); a.body = Automation.cap(fBody.getText().toString(), 8000); a.header = fHeader.getText().toString().trim();
                if (!a.header.isEmpty() && !a.header.matches("[A-Za-z0-9-]{1,60}")) { toast("A header name has only letters, digits and dashes (for example Authorization). It was left out."); a.header = ""; }
                String value = fSecret.getText().toString();
                try { CredentialStore store = new CredentialStore(this);
                    if (a.header.isEmpty()) store.forget("automation:" + a.secretId); else if (!value.isEmpty()) store.saveSecret("automation:" + a.secretId, value); }
                catch (Exception failed) { toast("Could not keep the header value safely."); }
                if (!Automation.webhookUrlOk(a.url)) toast("The address must start with https:// or http://.");
            }
            done.run();
        }).show();
    }

    private void test(Automation.Rule rule) {
        if (printer == null || printer.automations == null) { toast("The app's printer service is not running yet. Try again in a moment."); return; }
        Automation.Event sample = AutomationRun.sample(rule.event, printer.liveStatus(), "Test");
        Runnable run = () -> { printer.automations.run(rule, sample, true); toast("Ran “" + rule.name + "” once with sample values. The log has the results."); };
        if (rule.changesPrinter()) new AlertDialog.Builder(this).setTitle("Test with the printer?").setMessage("This sends “Light " + lightWords(rule) + "” to the printer now, once.")
            .setNegativeButton("Cancel", null).setPositiveButton("Send", (d, w) -> run.run()).show();
        else run.run();
    }

    private void importDialog() {
        EditText paste = new EditText(this); paste.setHint("Paste a shared automation here"); paste.setMinLines(4); paste.setTextColor(ink);
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(paste, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Import automations").setView(box)
            .setNeutralButton("From a file…", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT))
            .setNegativeButton("Cancel", null).setPositiveButton("Import", (d, w) -> imported(paste.getText().toString())).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            byte[] bytes = new byte[256 * 1024]; int n = 0, r;
            while (in != null && n < bytes.length && (r = in.read(bytes, n, bytes.length - n)) > 0) n += r;
            imported(new String(bytes, 0, n, StandardCharsets.UTF_8));
        } catch (Exception failed) { toast("Could not read the file."); }
    }
    private void imported(String text) {
        List<Automation.Rule> found;
        try { found = Automation.parseShared(text); } catch (Exception bad) { toast("Not an automation: " + bad.getMessage()); return; }
        StringBuilder list = new StringBuilder();
        for (Automation.Rule r : found) list.append("• ").append(r.name).append("\n   ").append(r.summary()).append("\n");
        new AlertDialog.Builder(this).setTitle("Import " + found.size() + (found.size() == 1 ? " automation?" : " automations?"))
            .setMessage(list + "\nImported rules start turned off: read what each does, then turn it on. Webhooks send print details to the address shown.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Keep as templates", (d, w) -> { List<Automation.Rule> mine = AutomationRun.myTemplates(this); mine.addAll(found); AutomationRun.saveTemplates(this, mine); show(); })
            .setPositiveButton("Add as rules", (d, w) -> { rules.addAll(found); saveRules(); Diagnostics.note(Diagnostics.AUTOMATIONS, "imported " + found.size() + " rules (off)"); show(); }).show();
    }
    private void share(List<Automation.Rule> list, String subject) {
        try {
            String text = Automation.share(list);
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, "Link Workshop automation. Import it in Settings → Automation workshop → Import.\n\n" + text), "Share automations"));
        } catch (Exception failed) { toast("Could not share."); }
    }

    private void saveRules() { AutomationRun.save(this, rules); }
    private static String lightWords(Automation.Rule rule) {
        boolean on = false, off = false; for (Automation.Action a : rule.actions) if (a.kind.equals(Automation.LIGHT)) { if (a.on) on = true; else off = true; }
        return on && off ? "on and off" : on ? "on" : "off";
    }
    private static double decimal(String text) {
        try { double v = Double.parseDouble(text.trim().replace(',', '.')); return Double.isInfinite(v) || Math.abs(v) > 1e9 ? Double.NaN : v; } catch (NumberFormatException blank) { return Double.NaN; }
    }

    /** Values now (named and raw), when the printer service is reachable. */
    private java.util.Map<String, String> currentValues() {
        if (printer == null || printer.automations == null) return new java.util.LinkedHashMap<>();
        return printer.automations.values(printer.liveStatus());
    }
    /** Every value, by group, with what it is now; tapping one copies {name}. Then every raw field the printer reported. */
    private void valuesDialog() {
        java.util.Map<String, String> now = currentValues();
        ScrollView scroll = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(20), dp(8), dp(20), dp(8)); scroll.addView(list);
        text(list, now.isEmpty() ? "No printer status yet: connect to see the values now. Tap a value to copy its name." : "With the values the printer reports now. Tap one to copy its name, then paste it into a text or body.", 12, muted, false);
        String group = "";
        for (AutomationValues.Value v : AutomationValues.CATALOGUE) {
            if (!v.group.equals(group)) { group = v.group; text(list, group, 15, ink, true).setPadding(0, dp(12), 0, dp(2)); }
            valueRow(list, v.key, v.description, now.get(v.key));
        }
        text(list, "Attachments", 15, ink, true).setPadding(0, dp(12), 0, dp(2));
        for (String[] a : AutomationValues.ATTACHMENTS) text(list, a[0] + ": " + a[1] + ". Turn it on in a notification or webhook action.", 13, muted, false);
        text(list, "Everything the printer reports", 15, ink, true).setPadding(0, dp(12), 0, dp(2));
        boolean any = false;
        for (java.util.Map.Entry<String, String> e : now.entrySet()) if (e.getKey().startsWith("status.")) { any = true; valueRow(list, e.getKey(), null, e.getValue()); }
        if (!any) text(list, "Shown here once the printer has reported its status.", 13, muted, false);
        new AlertDialog.Builder(this).setTitle("Values").setView(scroll).setPositiveButton("Close", null).show();
    }
    private void valueRow(LinearLayout list, String key, String description, String value) {
        TextView row = new TextView(this); row.setTextColor(ink); row.setTextSize(14); row.setPadding(0, dp(6), 0, dp(6)); row.setMinHeight(dp(48));
        row.setText("{" + key + "}" + (value == null ? "" : "  = " + (value.length() > 60 ? value.substring(0, 60) + "…" : value)) + (description == null ? "" : "\n" + description));
        row.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("value", "{" + key + "}"));
            toast("Copied {" + key + "}");
        });
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    /** Chooses a value: the named ones (numbers only when `numbers`), then the printer's raw fields; `none` offers no value. */
    private void pickValue(boolean numbers, boolean none, java.util.function.Consumer<String> chosen) {
        java.util.Map<String, String> now = currentValues();
        List<String> keys = new ArrayList<>(), labels = new ArrayList<>();
        if (none) { keys.add(""); labels.add("No condition"); }
        for (AutomationValues.Value v : AutomationValues.CATALOGUE) {
            if (numbers && !v.number || v.group.equals("Event")) continue;
            keys.add(v.key); labels.add(v.group + " · {" + v.key + "}" + (now.containsKey(v.key) ? " = " + now.get(v.key) : "") + "\n" + v.description);
        }
        for (java.util.Map.Entry<String, String> e : now.entrySet()) {
            if (!e.getKey().startsWith("status.") || numbers && Double.isNaN(AutomationValues.number(now, e.getKey()))) continue;
            keys.add(e.getKey()); labels.add("Printer · {" + e.getKey() + "} = " + e.getValue());
        }
        new AlertDialog.Builder(this).setTitle(numbers ? "Choose a number value" : "Choose a value").setItems(labels.toArray(new String[0]), (d, which) -> chosen.accept(keys.get(which)))
            .setNegativeButton("Cancel", null).show();
    }
    /** "Insert a value…": puts {name} at the cursor of whichever of `fields` was last focused (the last one by default). */
    private void insertButton(LinearLayout form, EditText... fields) {
        EditText[] target = {fields[fields.length - 1]};
        for (EditText f : fields) f.setOnFocusChangeListener((v, has) -> { if (has) target[0] = (EditText) v; });
        LinearLayout line = row(form);
        action(line, "Insert a value…", () -> pickValue(false, false, key -> {
            EditText f = target[0]; int at = Math.max(0, f.getSelectionStart());
            f.getText().insert(at, "{" + key + "}");
        }));
    }
    /** Minutes after midnight for "HH:MM", -1 for blank, -2 for unreadable. */
    static int clock(String text) {
        String t = text.trim(); if (t.isEmpty()) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})(?::(\\d{2}))?").matcher(t);
        if (!m.matches()) return -2;
        int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return h > 23 || min > 59 ? -2 : h * 60 + min;
    }
    private static int number(String text, int fallback) { try { return Integer.parseInt(text.trim()); } catch (NumberFormatException blank) { return fallback; } }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(surface); background.setCornerRadius(dp(20)); card.setBackground(background);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2); at.topMargin = dp(10); content.addView(card, at); return card;
    }
    private EditText field(LinearLayout parent, String hint, String value, int type) {
        EditText field = new EditText(this); field.setHint(hint); field.setText(value); field.setInputType(type); field.setTextColor(ink); field.setHintTextColor(muted);
        field.setBackgroundTintList(ColorStateList.valueOf(accent)); field.setMinHeight(dp(48)); field.setContentDescription(hint);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2)); return field;
    }
    private LinearLayout row(LinearLayout parent) { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row; }
    private void action(LinearLayout row, String label, Runnable run) {
        Button button = new A11y.DimButton(this); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setTextColor(accent);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(chipOff); shape.setCornerRadius(dp(12));
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -2, 1); at.topMargin = dp(8); if (row.getChildCount() > 0) at.leftMargin = dp(8); row.addView(button, at);
    }
    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(4), 0, dp(4)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

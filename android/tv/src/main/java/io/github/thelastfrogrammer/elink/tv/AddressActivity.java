package io.github.thelastfrogrammer.elink.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;

/** The phone's address typed with the remote, for a network where discovery does not get through. */
public final class AddressActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private PhoneStore store;
    private EditText host, port;
    private TextView problem;
    private Button connect;
    private boolean checking, destroyed;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PhoneStore(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL); root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, Ui.SIDE + 16), Ui.dp(this, Ui.TOP + 16), Ui.dp(this, Ui.SIDE + 16), Ui.dp(this, Ui.TOP + 16));

        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL); about.setGravity(Gravity.CENTER_VERTICAL);
        about.addView(Ui.label(this, "Printer on TV"));
        about.addView(Ui.heading(this, "Enter the phone's address", Ui.DISPLAY), Ui.gap(Ui.wide(), this, 10));
        about.addView(Ui.text(this, "Link Workshop shows it on the phone, under Settings › Automations & integrations › Other apps. It looks like 192.168.1.20:8765.", Ui.BODY, Ui.MUTED), Ui.gap(Ui.wide(), this, 18));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        left.rightMargin = Ui.dp(this, 56);
        root.addView(about, left);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL); form.setGravity(Gravity.CENTER_VERTICAL);
        String base = store.base();
        host = field("Address", base == null ? "" : PhoneAddress.host(base), "192.168.1.20", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT, form);
        port = field("Port", base == null ? String.valueOf(PhoneAddress.DEFAULT_PORT) : PhoneAddress.port(base), String.valueOf(PhoneAddress.DEFAULT_PORT), InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_GO, form);
        port.setOnEditorActionListener((v, action, e) -> { if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) { connect(); return true; } return false; });
        problem = Ui.text(this, "", Ui.SMALL + 1, Ui.WARN); problem.setVisibility(View.GONE);
        problem.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(problem, Ui.gap(Ui.wide(), this, 14));
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        connect = Ui.button(this, "Connect", v -> connect());
        buttons.addView(connect, Ui.wrap());
        LinearLayout.LayoutParams cancelParams = Ui.wrap(); cancelParams.leftMargin = Ui.dp(this, 20);
        buttons.addView(Ui.button(this, "Cancel", v -> finish()), cancelParams);
        form.addView(buttons, Ui.gap(Ui.wrap(), this, 24));
        root.addView(form, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));
        setContentView(root);
        (base == null ? host : connect).requestFocus();
    }
    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); }

    private EditText field(String label, String value, String hint, int type, int action, LinearLayout into) {
        TextView name = Ui.text(this, label, Ui.SMALL, Ui.MUTED);
        into.addView(name, Ui.gap(Ui.wide(), this, into.getChildCount() == 0 ? 0 : 18));
        EditText e = new EditText(this);
        e.setText(value); e.setHint(hint); e.setHintTextColor(0xFF5E7178); e.setTextColor(Ui.INK);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26); e.setSingleLine(); e.setInputType(type); e.setImeOptions(action | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setBackground(Ui.field(this)); e.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 14));
        e.setId(View.generateViewId()); name.setLabelFor(e.getId());   // a screen reader says "Address" for the field
        into.addView(e, Ui.gap(Ui.wide(), this, 8));
        return e;
    }

    private void connect() {
        if (checking) return;
        PhoneAddress.Parsed typed = PhoneAddress.parse(host.getText().toString(), port.getText().toString());
        if (typed.problem != null) { say(typed.problem); host.requestFocus(); return; }
        checking = true; say(null); connect.setText("Checking…");
        Thread t = new Thread(() -> {
            String trouble = null;
            try { new PhoneApi(typed.base).info(); }
            catch (PhoneApi.Refused r) { trouble = r.code == 200 ? r.getMessage() : "Something answers at " + PhoneAddress.shown(typed.base) + ", but not Link Workshop (" + r.code + ")."; }
            catch (IOException unreachable) { trouble = "Nothing answers at " + PhoneAddress.shown(typed.base) + ". Check the address, that Link Workshop is open on the phone and that Other apps is turned on."; }
            String said = trouble;
            main.post(() -> {
                if (destroyed) return;
                checking = false; connect.setText("Connect");
                if (said != null) { say(said); return; }
                store.phone(typed.base);
                startActivity(new Intent(this, store.token() != null ? WatchActivity.class : PairActivity.class));
                finish();
            });
        }, "tv-check");
        t.setDaemon(true); t.start();
    }
    private void say(String words) { problem.setText(words == null ? "" : words); problem.setVisibility(words == null ? View.GONE : View.VISIBLE); }
}

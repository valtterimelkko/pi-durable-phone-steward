package com.example.phonesteward.screen;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A plain screen in the instrumentation package with the buttons the phone-use device proof needs: an ordinary
 * one, "Clear cache" (allowed), "Delete all" (asks the owner) and "Clear data" (never). It counts real presses and
 * shows them as text ("pressed: Delete all=1"). On a Test Lab device this activity runs in the test APK's own
 * process, without the Kotlin runtime, so it is written in Java and the test reads the counts through the
 * accessibility tree, as the agent would.
 */
public class ProofActivity extends Activity {

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private TextView shown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(48, 96, 48, 48);
        TextView title = new TextView(this);
        title.setText("Proof screen");
        title.setTextSize(24f);
        column.addView(title);
        shown = new TextView(this);
        shown.setText("pressed: none");
        addButton(column, "Storage overview");
        addButton(column, "Clear cache");
        addButton(column, "Delete all");
        addButton(column, "Clear data");
        column.addView(shown);
        EditText search = new EditText(this);
        search.setHint("Search");
        column.addView(search);
        EditText password = new EditText(this);
        password.setHint("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        column.addView(password);
        setContentView(column);
    }

    private void addButton(LinearLayout column, final String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> {
            Integer old = counts.get(label);
            counts.put(label, old == null ? 1 : old + 1);
            StringBuilder sb = new StringBuilder("pressed: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (!first) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            shown.setText(sb.toString());
        });
        column.addView(button, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}

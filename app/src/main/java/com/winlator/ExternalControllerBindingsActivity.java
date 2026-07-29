package com.winlator;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.core.AppUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.ExternalControllerBinding;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.widget.InputControlsView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExternalControllerBindingsActivity extends AppCompatActivity {
    private TextView emptyTextView;
    private ControlsProfile profile;
    private ExternalController controller;
    private RecyclerView recyclerView;
    private ControllerBindingsAdapter adapter;
    private boolean teknoParrotNativeControls;
    private final ArrayList<Binding> arcadeActionBindings = new ArrayList<>();
    private final ArrayList<String> arcadeActionLabels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.external_controller_bindings_activity);

        Intent intent = getIntent();
        int profileId = intent.getIntExtra("profile_id", 0);
        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        String controllerId = intent.getStringExtra("controller_id");
        teknoParrotNativeControls =
                intent.getBooleanExtra("teknoparrot_native_controls", false);

        controller = profile.getController(controllerId);
        if (controller == null) {
            controller = profile.addController(controllerId);
            profile.save();
        }
        if (teknoParrotNativeControls)
            loadTeknoParrotActions();

        Toolbar toolbar = findViewById(R.id.Toolbar);
        toolbar.setTitle(controller.getName());
        if (teknoParrotNativeControls)
            toolbar.setSubtitle(profile.getName());
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);

        emptyTextView = findViewById(R.id.TVEmptyText);
        recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        DividerItemDecoration itemDecoration = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        itemDecoration.setDrawable(ContextCompat.getDrawable(this, R.drawable.list_item_divider));
        recyclerView.addItemDecoration(itemDecoration);
        recyclerView.setAdapter(adapter = new ControllerBindingsAdapter());
        updateEmptyTextView();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    private void updateControllerBinding(int keyCode, Binding binding) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return;

        ExternalControllerBinding controllerBinding = controller.getControllerBinding(keyCode);
        int position;
        if (controllerBinding == null) {
            controllerBinding = new ExternalControllerBinding();
            controllerBinding.setKeyCode(keyCode);
            controllerBinding.setBinding(binding);

            controller.addControllerBinding(controllerBinding);
            profile.save();
            adapter.notifyDataSetChanged();
            updateEmptyTextView();
            position = controller.getPosition(controllerBinding);
        }
        else animateItemView(position = controller.getPosition(controllerBinding));
        recyclerView.scrollToPosition(position);
    }

    /**
     * Builds the editable cabinet-action list from the selected TeknoParrot
     * overlay.  The overlay profile is already title-specific and contains the
     * same semantic targets used by the forwarded-input bridge (for example
     * ATTACK -> GAMEPAD_BUTTON_A, COIN -> GAMEPAD_BUTTON_SELECT).  Presenting
     * these labels keeps controller setup game-specific without exposing Wine
     * keyboard keys to players.
     */
    private void loadTeknoParrotActions() {
        // Keep the editable profile metadata-only. Loading elements into that
        // instance would make ControlsProfile.save() rewrite their normalized
        // positions using this non-displayed helper view.
        ControlsProfile actionProfile = InputControlsManager.loadProfile(
                this,
                ControlsProfile.getProfileFile(this, profile.id));

        LinkedHashMap<Binding, String> actions = new LinkedHashMap<>();
        actions.put(Binding.NONE, "Not assigned");
        HashSet<String> usedLabels = new HashSet<>();
        usedLabels.add("Not assigned");

        if (actionProfile != null)
            actionProfile.loadElements(new InputControlsView(this));
        for (ControlElement element : actionProfile != null
                ? actionProfile.getElements()
                : new ArrayList<ControlElement>()) {
            String baseLabel = element.getText() != null
                    ? element.getText().trim()
                    : "";
            boolean directional =
                    element.getType() == ControlElement.Type.D_PAD ||
                    element.getType() == ControlElement.Type.STICK ||
                    element.getType() == ControlElement.Type.TRACKPAD;
            String[] directions = {"Up", "Right", "Down", "Left"};

            for (int index = 0; index < element.getBindingCount(); index++) {
                Binding binding = element.getBindingAt(index);
                if (!isForwardedArcadeBinding(binding) ||
                        actions.containsKey(binding))
                    continue;

                String label = baseLabel.isEmpty()
                        ? binding.toString()
                        : directional && index < directions.length
                            ? baseLabel + " " + directions[index]
                            : baseLabel;
                if (!usedLabels.add(label))
                    label = label + " (" + binding.toString() + ")";
                actions.put(binding, label);
            }
        }

        // Corrupt or user-created overlays may not contain semantic actions.
        // Keep the editor usable with the bridge's safe standard arcade set.
        if (actions.size() == 1) {
            addFallbackAction(actions, Binding.GAMEPAD_DPAD_UP, "Move Up");
            addFallbackAction(actions, Binding.GAMEPAD_DPAD_RIGHT, "Move Right");
            addFallbackAction(actions, Binding.GAMEPAD_DPAD_DOWN, "Move Down");
            addFallbackAction(actions, Binding.GAMEPAD_DPAD_LEFT, "Move Left");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_A, "Button 1");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_B, "Button 2");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_X, "Button 3");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_Y, "Button 4");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_L1, "Button 5");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_R1, "Button 6");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_START, "Start");
            addFallbackAction(actions, Binding.GAMEPAD_BUTTON_SELECT, "Coin");
            addFallbackAction(actions, Binding.KEY_F2, "Service");
            addFallbackAction(actions, Binding.KEY_F1, "Test");
        }

        for (Map.Entry<Binding, String> action : actions.entrySet()) {
            arcadeActionBindings.add(action.getKey());
            arcadeActionLabels.add(action.getValue());
        }
    }

    private static void addFallbackAction(
            LinkedHashMap<Binding, String> actions,
            Binding binding,
            String label) {
        actions.put(binding, label);
    }

    private static boolean isForwardedArcadeBinding(Binding binding) {
        switch (binding) {
            case KEY_UP:
            case KEY_DOWN:
            case KEY_LEFT:
            case KEY_RIGHT:
            case KEY_ENTER:
            case KEY_1:
            case KEY_5:
            case KEY_F1:
            case KEY_F2:
            case GAMEPAD_DPAD_UP:
            case GAMEPAD_DPAD_DOWN:
            case GAMEPAD_DPAD_LEFT:
            case GAMEPAD_DPAD_RIGHT:
            case GAMEPAD_BUTTON_A:
            case GAMEPAD_BUTTON_B:
            case GAMEPAD_BUTTON_X:
            case GAMEPAD_BUTTON_Y:
            case GAMEPAD_BUTTON_L1:
            case GAMEPAD_BUTTON_R1:
            case GAMEPAD_BUTTON_L2:
            case GAMEPAD_BUTTON_R2:
            case GAMEPAD_BUTTON_START:
            case GAMEPAD_BUTTON_SELECT:
            case GAMEPAD_LEFT_THUMB_UP:
            case GAMEPAD_LEFT_THUMB_RIGHT:
            case GAMEPAD_LEFT_THUMB_DOWN:
            case GAMEPAD_LEFT_THUMB_LEFT:
            case GAMEPAD_RIGHT_THUMB_UP:
            case GAMEPAD_RIGHT_THUMB_RIGHT:
            case GAMEPAD_RIGHT_THUMB_DOWN:
            case GAMEPAD_RIGHT_THUMB_LEFT:
                return true;
            default:
                return false;
        }
    }

    private void processJoystickInput() {
        int keyCode = KeyEvent.KEYCODE_UNKNOWN;
        Binding binding = Binding.NONE;
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        GamepadState state = controller.getGamepadState();
        final float[] values = {state.thumbLX, state.thumbLY, state.thumbRX, state.thumbRY, state.getDPadX(), state.getDPadY()};

        byte sign;
        for (int i = 0; i < axes.length; i++) {
            if ((sign = Mathf.sign(values[i])) != 0) {
                if (axes[i] == MotionEvent.AXIS_X || axes[i] == MotionEvent.AXIS_Z) {
                    binding = sign > 0 ? Binding.MOUSE_MOVE_RIGHT : Binding.MOUSE_MOVE_LEFT;
                }
                else if (axes[i] == MotionEvent.AXIS_Y || axes[i] == MotionEvent.AXIS_RZ) {
                    binding = sign > 0 ? Binding.MOUSE_MOVE_DOWN : Binding.MOUSE_MOVE_UP;
                }
                else if (axes[i] == MotionEvent.AXIS_HAT_X) {
                    binding = sign > 0 ? Binding.KEY_D : Binding.KEY_A;
                }
                else if (axes[i] == MotionEvent.AXIS_HAT_Y) {
                    binding = sign > 0 ? Binding.KEY_S : Binding.KEY_W;
                }

                keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                break;
            }
        }

        updateControllerBinding(keyCode, binding);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getDeviceId() == controller.getDeviceId() && controller.updateStateFromMotionEvent(event)) {
            GamepadState state = controller.getGamepadState();
            if (state.isPressed(ExternalController.IDX_BUTTON_L2)) updateControllerBinding(KeyEvent.KEYCODE_BUTTON_L2, Binding.NONE);
            if (state.isPressed(ExternalController.IDX_BUTTON_R2)) updateControllerBinding(KeyEvent.KEYCODE_BUTTON_R2, Binding.NONE);
            processJoystickInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getDeviceId() == controller.getDeviceId() && event.getRepeatCount() == 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) updateControllerBinding(event.getKeyCode(), Binding.NONE);
            return true;
        }
        else return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        finish();
        return true;
    }

    private class ControllerBindingsAdapter extends RecyclerView.Adapter<ControllerBindingsAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton removeButton;
            private final TextView title;
            private final Spinner bindingType;
            private final Spinner binding;

            private ViewHolder(View view) {
                super(view);
                this.title = view.findViewById(R.id.TVTitle);
                this.bindingType = view.findViewById(R.id.SBindingType);
                this.binding = view.findViewById(R.id.SBinding);
                this.removeButton = view.findViewById(R.id.BTRemove);
            }
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.external_controller_binding_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ExternalControllerBinding item = controller.getControllerBindingAt(position);
            holder.title.setText(item.toString());
            loadBindingSpinner(holder, item);
            holder.removeButton.setOnClickListener((view) -> {
                controller.removeControllerBinding(item);
                profile.save();
                notifyDataSetChanged();
                updateEmptyTextView();
            });
        }

        @Override
        public final int getItemCount() {
            return controller.getControllerBindingCount();
        }

        private void loadBindingSpinner(ViewHolder holder, final ExternalControllerBinding item) {
            if (teknoParrotNativeControls) {
                loadTeknoParrotBindingSpinner(holder, item);
                return;
            }

            final Context $this = ExternalControllerBindingsActivity.this;

            Runnable update = () -> {
                String[] bindingEntries = null;
                switch (holder.bindingType.getSelectedItemPosition()) {
                    case 0:
                        bindingEntries = Binding.keyboardBindingLabels();
                        break;
                    case 1:
                        bindingEntries = Binding.mouseBindingLabels();
                        break;
                    case 2:
                        bindingEntries = Binding.gamepadBindingLabels();
                        break;
                }

                holder.binding.setAdapter(new ArrayAdapter<>($this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
                AppUtils.setSpinnerSelectionFromValue(holder.binding, item.getBinding().toString());
            };

            holder.bindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    update.run();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            Binding selectedBinding = item.getBinding();
            if (selectedBinding.isKeyboard()) {
                holder.bindingType.setSelection(0, false);
            }
            else if (selectedBinding.isMouse()) {
                holder.bindingType.setSelection(1, false);
            }
            else if (selectedBinding.isGamepad()) {
                holder.bindingType.setSelection(2, false);
            }

            holder.binding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    Binding binding = Binding.NONE;
                    switch (holder.bindingType.getSelectedItemPosition()) {
                        case 0:
                            binding = Binding.keyboardBindingValues()[position];
                            break;
                        case 1:
                            binding = Binding.mouseBindingValues()[position];
                            break;
                        case 2:
                            binding = Binding.gamepadBindingValues()[position];
                            break;
                    }

                    if (binding != item.getBinding()) {
                        item.setBinding(binding);
                        profile.save();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            update.run();
        }

        private void loadTeknoParrotBindingSpinner(
                ViewHolder holder,
                final ExternalControllerBinding item) {
            holder.bindingType.setAdapter(new ArrayAdapter<>(
                    ExternalControllerBindingsActivity.this,
                    android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"Arcade action"}));
            holder.bindingType.setSelection(0, false);
            holder.bindingType.setEnabled(false);

            holder.binding.setAdapter(new ArrayAdapter<>(
                    ExternalControllerBindingsActivity.this,
                    android.R.layout.simple_spinner_dropdown_item,
                    arcadeActionLabels));
            int selected = arcadeActionBindings.indexOf(item.getBinding());
            holder.binding.setSelection(selected >= 0 ? selected : 0, false);
            holder.binding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                        AdapterView<?> parent,
                        View view,
                        int position,
                        long id) {
                    if (position < 0 || position >= arcadeActionBindings.size())
                        return;
                    Binding binding = arcadeActionBindings.get(position);
                    if (binding != item.getBinding()) {
                        item.setBinding(binding);
                        profile.save();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    private void updateEmptyTextView() {
        emptyTextView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void animateItemView(int position) {
        final ControllerBindingsAdapter.ViewHolder holder = (ControllerBindingsAdapter.ViewHolder)recyclerView.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            final int color = AppUtils.getThemeColor(this, R.attr.colorAccent);
            final ValueAnimator animator = ValueAnimator.ofFloat(0.4f, 0.0f);
            animator.setDuration(200);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.addUpdateListener((animation) -> {
                float alpha = (float)animation.getAnimatedValue();
                holder.itemView.setBackgroundColor(Color.argb((int)(alpha * 255), Color.red(color), Color.green(color), Color.blue(color)));
            });
            animator.start();
        }
    }
}

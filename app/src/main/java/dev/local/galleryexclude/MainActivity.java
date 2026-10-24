package dev.local.galleryexclude;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Consumer;
import dev.local.galleryexclude.config.ConfigCodec;
import dev.local.galleryexclude.config.DevicePaths;
import dev.local.galleryexclude.config.RuleRepository;
import dev.local.galleryexclude.config.SharedConfig;
import dev.local.galleryexclude.config.StorageDocuments;
import dev.local.galleryexclude.config.GalleryRestarter;
import dev.local.galleryexclude.rules.PathRules;
import dev.local.galleryexclude.rules.RuleConfiguration;

public final class MainActivity extends Activity {
    private final ArrayList<String> draft = new ArrayList<>();
    private PathRules.Normalizer normalizer;
    private RuleRepository repository;
    private EditText input;
    private TextView status;
    private LinearLayout list;
    private Button save;
    private String failure;
    private boolean justSaved;
    private static final int PICK_DIRECTORY=300;
    private GalleryRestarter restarter;
    private Consumer<GalleryRestarter.Phase> restartListener;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        normalizer = DevicePaths.normalizer();
        input = findViewById(R.id.path_input);
        status = findViewById(R.id.config_status);
        list = findViewById(R.id.rule_list);
        save = findViewById(R.id.save_rules);
        showCompatibilityNotice();
        openConfiguration();
        if (state != null && state.containsKey("draft")) {
            draft.clear();
            draft.addAll(state.getStringArrayList("draft"));
            justSaved = state.getBoolean("just_saved");
            findViewById(R.id.manual_fields).setVisibility(state.getBoolean("manual_visible")?View.VISIBLE:View.GONE);
            render();
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderStatus(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        findViewById(R.id.add_rule).setOnClickListener(view -> addInput());
        findViewById(R.id.choose_directory).setOnClickListener(view -> chooseDirectory());
        findViewById(R.id.manual_toggle).setOnClickListener(view -> {
            View fields=findViewById(R.id.manual_fields);
            fields.setVisibility(fields.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
            updateManualToggle();
            hideKeyboard();
        });
        findViewById(R.id.retry_config).setOnClickListener(view -> openConfiguration());
        save.setOnClickListener(view -> saveDraft());
        Object retained=getLastNonConfigurationInstance();
        restarter=retained instanceof GalleryRestarter?(GalleryRestarter)retained:new GalleryRestarter(this);
        restartListener=phase->runOnUiThread(()->{
            if(isDestroyed())return;
            if(phase!=GalleryRestarter.Phase.IDLE)Log.i(ModuleInfo.LOG_TAG,"event=gallery_restart phase="+phase+" reason="+restarter.failure()+" diagnostic="+restarter.diagnostic());
            render();
        });
        restarter.listen(restartListener);
    }

    private void openConfiguration() {
        try {
            repository = SharedConfig.openWriter(this);
            failure = null;
            draft.clear();
            draft.addAll(repository.current().rules.saved());
            Log.i(ModuleInfo.LOG_TAG, "event=config_ui_loaded revision=" + repository.current().revision
                    + " rule_count=" + draft.size() + " filtering=false");
        } catch (SecurityException error) {
            repository = null;
            failure = getString(R.string.shared_unavailable);
            Log.i(ModuleInfo.LOG_TAG, "event=config_ui_unavailable reason=SHARED_CAPABILITY_UNAVAILABLE filtering=false");
        } catch (RuntimeException error) {
            repository = null;
            String reason = reason(error);
            failure = getString(R.string.config_invalid);
            Log.i(ModuleInfo.LOG_TAG, "event=config_ui_unavailable reason=" + reason + " filtering=false");
        }
        render();
    }

    private void addInput() {
        offerPath(input.getText().toString(),true);
    }

    private void offerPath(String raw,boolean manual) {
        final String path;
        try {
            path = normalizer.normalize(raw); // Deliberately no trim or second URI decoding.
        } catch (PathRules.InvalidPath error) {
            if(manual)input.setError(getString(pathError(error.reason)));
            else Toast.makeText(this,pathError(error.reason),Toast.LENGTH_LONG).show();
            return;
        }
        if (draft.contains(path)) {
            if(manual)input.setError(getString(R.string.duplicate_rule));
            else Toast.makeText(this,R.string.duplicate_rule,Toast.LENGTH_SHORT).show();
            return;
        }
        if (path.equals("/")) {
            new AlertDialog.Builder(this).setTitle(R.string.root_title).setMessage(R.string.root_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.confirm_add, (dialog, which) -> addPath(path,manual)).show();
        } else addPath(path,manual);
    }

    private void addPath(String path,boolean manual) {
        draft.add(path);
        if(manual)input.setText("");
        hideKeyboard();
        failure = null;
        justSaved = false;
        render();
    }

    private void chooseDirectory(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String initial=!draft.isEmpty()?draft.get(0):repository.current().rules.saved().isEmpty()?null:repository.current().rules.saved().get(0);
        Uri tree=StorageDocuments.initialDirectory(this,initial);
        if(tree!=null)intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,tree);
        try{startActivityForResult(intent,PICK_DIRECTORY);}
        catch(ActivityNotFoundException error){Toast.makeText(this,R.string.directory_picker_unavailable,Toast.LENGTH_LONG).show();}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_DIRECTORY || result!=RESULT_OK || data==null)return;
        try{
            String path=StorageDocuments.selectedTree(this,data.getData());
            offerPath(path,false);
            Log.i(ModuleInfo.LOG_TAG,"event=directory_picker_result status=LOCAL_PATH rule_count="+draft.size());
        }catch(RuntimeException error){
            Toast.makeText(this,R.string.directory_provider_unsupported,Toast.LENGTH_LONG).show();
            Log.i(ModuleInfo.LOG_TAG,"event=directory_picker_result status=PATH_UNAVAILABLE");
        }
    }

    private void saveDraft() {
        if (repository == null || busy()) return;
        if (input.length() != 0) {
            findViewById(R.id.manual_fields).setVisibility(View.VISIBLE);
            updateManualToggle();
            input.setError(getString(R.string.pending_input));
            input.requestFocus();
            return;
        }
        try {
            RuleConfiguration saved = repository.save(draft);
            draft.clear();
            draft.addAll(saved.rules.saved());
            failure = null;
            justSaved = true;
            hideKeyboard();
            Log.i(ModuleInfo.LOG_TAG, "event=config_saved module_version=" + BuildConfig.VERSION_NAME
                    + " revision=" + saved.revision + " rule_count=" + saved.rules.saved().size()
                    + " effective_count=" + saved.rules.effective().size() + " filtering=false");
            restarter.start(saved.revision);
        } catch (RuntimeException error) {
            String reason = reason(error);
            failure = getString(reason.equals("SAVE_FAILED_RECOVERY_REQUIRED")
                    ? R.string.save_recovery_required : R.string.save_failed);
            Log.i(ModuleInfo.LOG_TAG, "event=config_save_failed reason=" + reason + " filtering=false");
        }
        render();
    }

    private void render() {
        boolean available = repository != null;
        boolean editable=available && !busy();
        input.setEnabled(editable);
        findViewById(R.id.add_rule).setEnabled(editable);
        findViewById(R.id.choose_directory).setEnabled(editable);
        findViewById(R.id.manual_toggle).setEnabled(editable);
        updateManualToggle();
        save.setEnabled(editable);
        findViewById(R.id.retry_config).setVisibility(available ? View.GONE : View.VISIBLE);
        list.removeAllViews();
        ((TextView) findViewById(R.id.rules_heading)).setText(getString(R.string.rules_count, draft.size()));
        if (!available || draft.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(available ? R.string.empty_list : R.string.list_unavailable);
            empty.setTextSize(16);
            empty.setTextColor(getColor(R.color.text_secondary));
            int padding=Math.round(20*getResources().getDisplayMetrics().density);
            empty.setPadding(padding,padding,padding,padding);
            empty.setBackgroundResource(R.drawable.directory_background);
            list.addView(empty);
        }
        PathRules rules = new PathRules(normalizer, draft);
        if (available) for (String path : rules.saved()) {
            View row = getLayoutInflater().inflate(R.layout.rule_row, list, false);
            ((TextView) row.findViewById(R.id.rule_name)).setText(path.equals("/")?getString(R.string.root_name):new File(path).getName());
            ((TextView) row.findViewById(R.id.rule_path)).setText(path);
            TextView note = row.findViewById(R.id.rule_note);
            String parent = rules.shadowedBy().get(path);
            if (path.equals("/")) note.setText(R.string.root_note);
            else if (parent != null) note.setText(getString(R.string.shadowed_rule, parent));
            else note.setVisibility(View.GONE);
            View delete = row.findViewById(R.id.delete_rule);
            delete.setEnabled(editable);
            delete.setContentDescription(getString(R.string.delete_description, path));
            delete.setOnClickListener(view -> {
                draft.remove(path);
                failure = null;
                justSaved = false;
                render();
            });
            list.addView(row);
        }
        renderStatus();
    }

    private boolean dirty() {
        return repository != null && !new HashSet<>(draft).equals(new HashSet<>(repository.current().rules.saved()));
    }

    private boolean busy(){return restarter!=null && restarter.phase()==GalleryRestarter.Phase.RUNNING;}

    private void updateManualToggle(){
        ((Button)findViewById(R.id.manual_toggle)).setText(findViewById(R.id.manual_fields).getVisibility()==View.VISIBLE
                ?R.string.manual_collapse:R.string.manual_toggle);
    }

    private void renderStatus() {
        int color=R.color.text_secondary;
        boolean attention=false;
        if (failure != null) { showStatus(failure,R.color.error,true); return; }
        if (repository == null) return;
        RuleConfiguration current = repository.current();
        String message;
        if (dirty() || input.length() != 0) {
            message=getString(R.string.dirty_status);color=R.color.warning;attention=true;
        }
        else {
            message = getString(current.rules.saved().isEmpty()?R.string.empty_saved:R.string.rules_saved);
            if(justSaved && restarter!=null){
                if(restarter.phase()==GalleryRestarter.Phase.RUNNING){message=getString(R.string.restart_running);attention=true;}
                else if(restarter.phase()==GalleryRestarter.Phase.SUCCEEDED){message=getString(R.string.restart_succeeded);color=R.color.accent;}
                else if(restarter.phase()==GalleryRestarter.Phase.FAILED){
                    message=getString(restarter.failure()==GalleryRestarter.Failure.CHANNEL_UNAVAILABLE?R.string.restart_channel_unavailable:R.string.restart_failed);
                    color=R.color.error;attention=true;
                }
            }
        }
        showStatus(message,color,attention);
    }

    private void showStatus(String message,int color,boolean attention){
        status.setText(message);status.setTextColor(getColor(color));
        ImageView icon=findViewById(R.id.status_icon);
        icon.setImageResource(attention?R.drawable.ic_info:R.drawable.ic_check);
        icon.setColorFilter(getColor(color));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putStringArrayList("draft", new ArrayList<>(draft));
        state.putBoolean("just_saved", justSaved);
        state.putBoolean("manual_visible",findViewById(R.id.manual_fields).getVisibility()==View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    @Override public Object onRetainNonConfigurationInstance(){return restarter;}
    @Override protected void onDestroy(){
        if(restarter!=null)restarter.stopListening(restartListener);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (dirty() || input.length() != 0) {
            new AlertDialog.Builder(this).setTitle(R.string.discard_title).setMessage(R.string.discard_body)
                    .setNegativeButton(R.string.keep_editing, null)
                    .setPositiveButton(R.string.discard, (dialog, which) -> finish()).show();
        } else super.onBackPressed();
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void showCompatibilityNotice() {
        TextView notice=findViewById(R.id.compatibility_notice);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(ModuleInfo.TARGET_PACKAGE, 0);
            boolean supported = info.getLongVersionCode() == ModuleInfo.BASELINE_VERSION_CODE
                    && ModuleInfo.BASELINE_VERSION_NAME.equals(info.versionName);
            if(!supported){
                notice.setText(getString(R.string.gallery_unsupported,info.versionName));
                notice.setVisibility(View.VISIBLE);
            }
        } catch (PackageManager.NameNotFoundException error) {
            notice.setText(R.string.gallery_not_found);notice.setVisibility(View.VISIBLE);
        }
    }

    private static String reason(RuntimeException error) {
        return error instanceof ConfigCodec.InvalidConfig ? ((ConfigCodec.InvalidConfig) error).reason
                : error instanceof PathRules.InvalidPath ? ((PathRules.InvalidPath) error).reason
                : error.getClass().getSimpleName();
    }

    private static int pathError(String reason) {
        switch (reason) {
            case "EMPTY_PATH": return R.string.empty_path;
            case "NOT_ABSOLUTE": return R.string.not_absolute;
            case "DOT_SEGMENT": return R.string.dot_segment;
            default: return R.string.nul_character;
        }
    }
}

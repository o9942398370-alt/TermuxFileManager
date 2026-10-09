package dev.termux.filemanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A monochrome file manager that operates through Termux's own user context. */
public class MainActivity extends Activity {
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String GITHUB_TERMUX = "https://github.com/termux/termux-app/releases";
    private static final String FDROID_TERMUX = "https://f-droid.org/packages/com.termux/";
    private static final int REQUEST_TERMUX_PERMISSION = 501;

    private LinearLayout page;
    private String currentPath = "home"; // Relative to /data/data/com.termux/files.
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean showHidden = true;
    private boolean selectionMode = false;
    private boolean busy = false;
    private boolean connectedOnce = false;
    private String sortMode = "name";
    private String lastError = "";
    private String pendingKind = "";
    private int activeCommandId = -1;
    private int pendingZipOrRootState = 0;
    private String rootStatus = "";
    private android.app.AlertDialog settingsDialog;

    private android.content.SharedPreferences preferences() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int id = intent.getIntExtra(PluginResultsService.EXTRA_EXECUTION_ID, 0);
            if (id != activeCommandId) return;
            busy = false;
            String stdout = intent.getStringExtra(PluginResultsService.EXTRA_STDOUT);
            String stderr = intent.getStringExtra(PluginResultsService.EXTRA_STDERR);
            String errorMessage = intent.getStringExtra(PluginResultsService.EXTRA_ERRMSG);
            int exitCode = intent.getIntExtra(PluginResultsService.EXTRA_EXIT_CODE, -1);
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
            errorMessage = errorMessage == null ? "" : errorMessage;

            if ("list".equals(pendingKind)) {
                if (exitCode == 0) {
                    connectedOnce = true;
                    lastError = "";
                    parseListing(stdout);
                    renderBrowser();
                } else {
                    lastError = !stderr.trim().isEmpty() ? stderr.trim() : (!errorMessage.trim().isEmpty() ? errorMessage.trim() : "Termux command failed (" + exitCode + ")");
                    renderConnection(true);
                }
            } else if ("rootProbe".equals(pendingKind)) {
                rootStatus = (exitCode == 0 && stdout.trim().equals("0"))
                        ? getString(R.string.root_available) : getString(R.string.root_unavailable);
                showSettings();
            } else if ("installZip".equals(pendingKind)) {
                if (exitCode == 0) {
                    toast(getString(R.string.action_success));
                    refreshDirectory();
                } else {
                    showErrorToast(stderr.isEmpty() ? errorMessage : stderr);
                }
            } else {
                if (exitCode == 0) {
                    toast(getString(R.string.action_success));
                    selected.clear();
                    selectionMode = false;
                    refreshDirectory();
                } else if (exitCode == 127 || stderr.toLowerCase(Locale.ROOT).contains("command not found") || stderr.contains("__MISSING_ZIP__")) {
                    askInstallZipTools();
                } else {
                    showErrorToast(stderr.isEmpty() ? errorMessage : stderr);
                    refreshDirectory();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        showHidden = preferences().getBoolean("showHidden", true);
        sortMode = preferences().getString("sortMode", "name");
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PluginResultsService.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(resultReceiver, filter);
        if (isTermuxInstalled() && hasRunCommandPermission() && entries.isEmpty() && !busy) {
            refreshDirectory();
        }
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(resultReceiver); } catch (IllegalArgumentException ignored) { }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            selectionMode = false;
            selected.clear();
            renderBrowser();
            return;
        }
        if (!".".equals(currentPath)) {
            currentPath = parentOf(currentPath);
            selected.clear();
            refreshDirectory();
            return;
        }
        super.onBackPressed();
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean hasRunCommandPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void render() {
        if (!isTermuxInstalled()) renderNoTermux();
        else if (!hasRunCommandPermission() || !connectedOnce && !lastError.isEmpty()) renderConnection(!lastError.isEmpty());
        else if (!connectedOnce) renderConnection(false);
        else renderBrowser();
    }

    private void renderNoTermux() {
        beginPage();
        addHeader(getString(R.string.app_name), getString(R.string.subtitle), false);
        addDivider();
        addText(getString(R.string.termux_missing_title), 23, Color.WHITE, true, 0, 0, 0, 10);
        addText(getString(R.string.termux_missing_body), 15, Color.LTGRAY, false, 0, 0, 0, 22);
        addButton(getString(R.string.download_github), v -> openUrl(GITHUB_TERMUX));
        addButton(getString(R.string.download_fdroid), v -> openUrl(FDROID_TERMUX));
    }

    private void renderConnection(boolean error) {
        beginPage();
        addHeader(getString(R.string.app_name), getString(R.string.subtitle), false);
        addDivider();
        addText(getString(R.string.connection_title), 23, Color.WHITE, true, 0, 8, 0, 10);
        addText(getString(R.string.connection_body), 15, Color.LTGRAY, false, 0, 0, 0, 16);
        if (error && !lastError.isEmpty()) {
            addCardText(getString(R.string.error_title) + "\n\n" + lastError, Color.WHITE);
        }
        addButton(getString(R.string.connect_button), v -> requestConnection());
        addButton(getString(R.string.open_termux), v -> openTermux());
        addButton(getString(R.string.copy_setup), v -> copyText("allow-external-apps=true"));
        addText(getString(R.string.permission_steps), 14, Color.LTGRAY, false, 0, 18, 0, 10);
        addDivider();
        addText(getString(R.string.scope_note), 13, Color.GRAY, false, 0, 14, 0, 10);
    }

    private void requestConnection() {
        if (!isTermuxInstalled()) { renderNoTermux(); return; }
        if (!hasRunCommandPermission()) {
            try {
                requestPermissions(new String[]{RUN_COMMAND_PERMISSION}, REQUEST_TERMUX_PERMISSION);
            } catch (Exception e) {
                showAppPermissionSettings();
            }
            toast(getString(R.string.permission_steps));
            return;
        }
        lastError = "";
        refreshDirectory();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_TERMUX_PERMISSION) {
            if (hasRunCommandPermission()) refreshDirectory();
            else showAppPermissionSettings();
        }
    }

    private void showAppPermissionSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast(getString(R.string.permission_steps));
        }
    }

    private void renderBrowser() {
        beginPage();
        addHeader(getString(R.string.app_name), getString(R.string.subtitle), true);
        addDivider();

        LinearLayout pathRow = horizontalRow();
        Button up = button(getString(R.string.up));
        up.setEnabled(!".".equals(currentPath) && !busy);
        up.setOnClickListener(v -> {
            currentPath = parentOf(currentPath);
            selected.clear();
            refreshDirectory();
        });
        pathRow.addView(up, weightedParams());
        TextView path = text(pathLabel(), 13, Color.LTGRAY, false);
        path.setGravity(Gravity.CENTER_VERTICAL);
        path.setPadding(dp(10), dp(7), dp(10), dp(7));
        path.setSingleLine(false);
        pathRow.addView(path, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        page.addView(pathRow);

        LinearLayout actions = horizontalRow();
        actions.addView(button(getString(R.string.refresh)).withClick(v -> refreshDirectory()), weightedParams());
        actions.addView(button(getString(R.string.new_folder)).withClick(v -> askNewName(true)), weightedParams());
        actions.addView(button(getString(R.string.new_file)).withClick(v -> askNewName(false)), weightedParams());
        actions.addView(button(selectionMode ? getString(R.string.cancel_selection) : getString(R.string.select)).withClick(v -> {
            selectionMode = !selectionMode; selected.clear(); renderBrowser();
        }), weightedParams());
        page.addView(actions);

        if (selectionMode) {
            LinearLayout selectActions = horizontalRow();
            selectActions.addView(button(getString(R.string.select_all)).withClick(v -> {
                for (Entry e : visibleEntries()) if (!isProtectedRootItem(e.name)) selected.add(e.name);
                renderBrowser();
            }), weightedParams());
            selectActions.addView(button(getString(R.string.delete_selected)).withClick(v -> deleteSelected()), weightedParams());
            selectActions.addView(button(getString(R.string.zip_selected)).withClick(v -> askZipSelected()), weightedParams());
            page.addView(selectActions);
            addText(selected.size() + " selected", 13, Color.LTGRAY, false, 0, 3, 0, 5);
        }

        if (busy) addText(getString(R.string.loading), 15, Color.WHITE, false, 0, 14, 0, 14);
        if (!lastError.isEmpty()) addCardText(lastError, Color.WHITE);

        List<Entry> visible = visibleEntries();
        if (visible.isEmpty() && !busy) {
            addText(getString(R.string.empty_folder), 15, Color.LTGRAY, false, 0, 18, 0, 18);
        } else {
            for (Entry e : visible) addEntryRow(e);
        }
        addDivider();
        addText(getString(R.string.scope_note), 12, Color.GRAY, false, 0, 10, 0, 12);
    }

    private void addEntryRow(Entry entry) {
        LinearLayout row = horizontalRow();
        row.setPadding(dp(7), dp(9), dp(7), dp(9));
        row.setBackground(roundBg(selected.contains(entry.name) ? "#252525" : "#101010", "#333333", 10));
        TextView icon = text(entry.symlink ? "↗" : entry.directory ? "▰" : "□", 22, Color.WHITE, true);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(42)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, dp(8), 0);
        TextView name = text((selected.contains(entry.name) ? "✓  " : "") + entry.name, 15, Color.WHITE, true);
        name.setMaxLines(3);
        TextView details = text(entry.directory ? getString(R.string.type_folder) : formatSize(entry.size) + " · " + formatDate(entry.modified), 12, Color.GRAY, false);
        labels.addView(name);
        labels.addView(details);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView menu = text("⋮", 24, Color.WHITE, false);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(12), 0, dp(8), 0);
        row.addView(menu, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        menu.setOnClickListener(v -> showEntryActions(entry));
        row.setOnClickListener(v -> {
            if (selectionMode) {
                if (!selected.contains(entry.name) && isProtectedRootItem(entry.name)) {
                    toast(getString(R.string.scope_note));
                    return;
                }
                if (selected.contains(entry.name)) selected.remove(entry.name); else selected.add(entry.name);
                renderBrowser();
            } else if (entry.directory && !entry.symlink) {
                currentPath = join(currentPath, entry.name);
                selected.clear();
                refreshDirectory();
            } else {
                showEntryActions(entry);
            }
        });
        row.setOnLongClickListener(v -> { showEntryActions(entry); return true; });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(4), 0, dp(4));
        page.addView(row, p);
    }

    private void showEntryActions(Entry entry) {
        List<String> choices = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (entry.directory && !entry.symlink) { choices.add(getString(R.string.open)); actions.add("open"); }
        boolean protectedRootItem = isProtectedRootItem(entry.name);
        if (!protectedRootItem) {
            choices.add(getString(R.string.rename)); actions.add("rename");
            choices.add(getString(R.string.copy)); actions.add("copy");
            choices.add(getString(R.string.delete)); actions.add("delete");
            if (!entry.symlink) { choices.add(getString(R.string.compress)); actions.add("zip"); }
        }
        choices.add(getString(R.string.properties)); actions.add("properties");
        if (!entry.directory && entry.name.toLowerCase(Locale.ROOT).endsWith(".zip")) { choices.add(getString(R.string.extract)); actions.add("extract"); }
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(choices.toArray(new String[0]), (d, which) -> {
            String action = actions.get(which);
            switch (action) {
                case "open": currentPath = join(currentPath, entry.name); selected.clear(); refreshDirectory(); break;
                case "rename": askRename(entry.name); break;
                case "copy": askCopy(entry.name); break;
                case "delete": confirmDeleteOne(entry.name); break;
                case "properties": showProperties(entry); break;
                case "zip": askZipOne(entry.name); break;
                case "extract": askExtract(entry.name); break;
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showProperties(Entry e) {
        String path = pathLabel() + "/" + e.name;
        String msg = path + "\n\n" + getString(R.string.size_label) + ": " + formatSize(e.size)
                + "\n" + getString(R.string.modified_label) + ": " + formatDate(e.modified)
                + "\n" + (e.directory ? getString(R.string.type_folder) : getString(R.string.type_file));
        new AlertDialog.Builder(this).setTitle(e.name).setMessage(msg).setPositiveButton(android.R.string.ok, null).show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, getString(R.string.menu_settings));
        menu.getMenu().add(0, 2, 1, getString(R.string.menu_search));
        menu.getMenu().add(0, 3, 2, getString(R.string.menu_sort));
        menu.getMenu().add(0, 4, 3, getString(R.string.menu_hidden)).setCheckable(true).setChecked(showHidden);
        menu.getMenu().add(0, 5, 4, getString(R.string.menu_about));
        menu.getMenu().add(0, 6, 5, getString(R.string.install_zip_tools));
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showSettings(); return true;
                case 2: showSearch(); return true;
                case 3: showSort(); return true;
                case 4:
                    showHidden = !showHidden;
                    preferences().edit().putBoolean("showHidden", showHidden).apply();
                    renderBrowser(); return true;
                case 5: new AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setMessage(getString(R.string.about_body)).setPositiveButton(android.R.string.ok, null).show(); return true;
                case 6: askInstallZipTools(); return true;
            }
            return false;
        });
        menu.show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        CheckBox rootMode = new CheckBox(this);
        rootMode.setText(getString(R.string.root_enabled_label));
        rootMode.setTextColor(Color.WHITE);
        rootMode.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        rootMode.setChecked(preferences().getBoolean("rootMode", false));
        box.addView(rootMode);
        box.addView(text(getString(R.string.root_body), 14, Color.LTGRAY, false));
        TextView rootLine = text(rootStatus.isEmpty() ? getString(R.string.root_status_unknown) : rootStatus, 14, Color.WHITE, true);
        rootLine.setPadding(0, dp(8), 0, dp(8));
        box.addView(rootLine);
        box.addView(button(getString(R.string.check_root)).withClick(v -> {
            if (settingsDialog != null) settingsDialog.dismiss();
            if (!hasRunCommandPermission()) { renderConnection(false); return; }
            String script = "cd -- \"$HOME/../$1\" || exit 4; if command -v su >/dev/null 2>&1; then su -c 'id -u'; else echo no-su >&2; exit 127; fi";
            executeCommand("rootProbe", script, currentPath);
        }));
        addLocalGap(box, 12);
        box.addView(text(getString(R.string.shizuku_title), 16, Color.WHITE, true));
        boolean shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api");
        box.addView(text(getString(shizukuInstalled ? R.string.shizuku_installed : R.string.shizuku_missing), 14, Color.LTGRAY, false));
        box.addView(button(getString(R.string.open_shizuku)).withClick(v -> openShizuku()));
        addLocalGap(box, 12);
        box.addView(text(getString(R.string.advanced_settings), 13, Color.GRAY, false));
        box.addView(text(getString(R.string.scope_note), 13, Color.GRAY, false));
        rootMode.setOnCheckedChangeListener((buttonView, checked) -> {
            preferences().edit().putBoolean("rootMode", checked).apply();
            toast(getString(R.string.root_enabled_saved));
        });
        settingsDialog = new AlertDialog.Builder(this).setTitle(getString(R.string.settings_title))
                .setView(box).setPositiveButton(android.R.string.ok, null).create();
        settingsDialog.show();
    }

    private void openShizuku() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch == null) throw new IllegalStateException("no launch intent");
            startActivity(launch);
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle(getString(R.string.shizuku_title)).setMessage(getString(R.string.shizuku_missing)).setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void showSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(getString(R.string.search_hint));
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle(getString(R.string.search)).setView(input)
                .setPositiveButton(getString(R.string.search), (d, w) -> {
                    String q = input.getText().toString().trim().toLowerCase(Locale.ROOT);
                    if (q.isEmpty()) { renderBrowser(); return; }
                    List<Entry> matches = new ArrayList<>();
                    for (Entry e : visibleEntries()) if (e.name.toLowerCase(Locale.ROOT).contains(q)) matches.add(e);
                    renderBrowserWithFilter(matches);
                }).setNeutralButton(getString(R.string.cancel_selection), (d, w) -> renderBrowser()).show();
    }

    private void renderBrowserWithFilter(List<Entry> matches) {
        beginPage();
        addHeader(getString(R.string.app_name), getString(R.string.subtitle), true);
        addDivider();
        addText(pathLabel(), 13, Color.LTGRAY, false, 0, 6, 0, 8);
        addButton(getString(R.string.refresh), v -> refreshDirectory());
        if (matches.isEmpty()) addText(getString(R.string.no_results), 15, Color.LTGRAY, false, 0, 18, 0, 10);
        else for (Entry e : matches) addEntryRow(e);
    }

    private void showSort() {
        String[] choices = {getString(R.string.sort_name), getString(R.string.sort_size), getString(R.string.sort_modified)};
        int checked = sortMode.equals("size") ? 1 : sortMode.equals("modified") ? 2 : 0;
        new AlertDialog.Builder(this).setTitle(getString(R.string.menu_sort)).setSingleChoiceItems(choices, checked, (d, which) -> {
            sortMode = which == 1 ? "size" : which == 2 ? "modified" : "name";
            preferences().edit().putString("sortMode", sortMode).apply();
            d.dismiss(); renderBrowser();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askNewName(boolean folder) {
        EditText input = nameInput(getString(R.string.name_hint));
        new AlertDialog.Builder(this).setTitle(folder ? getString(R.string.new_folder) : getString(R.string.new_file))
                .setView(input).setPositiveButton(getString(R.string.create), (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name)) { toast(getString(R.string.enter_name)); return; }
                    String script = folder
                            ? "cd -- \"$HOME/../$1\" || exit 4; [ ! -e \"$2\" ] && [ ! -L \"$2\" ] || exit 10; mkdir -- \"$2\""
                            : "cd -- \"$HOME/../$1\" || exit 4; [ ! -e \"$2\" ] && [ ! -L \"$2\" ] || exit 10; touch -- \"$2\"";
                    executeCommand(folder ? "mkdir" : "touch", script, currentPath, name);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askRename(String oldName) {
        EditText input = nameInput(getString(R.string.name_hint)); input.setText(oldName); input.setSelection(input.length());
        new AlertDialog.Builder(this).setTitle(getString(R.string.rename)).setView(input)
                .setPositiveButton(getString(R.string.rename), (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!validName(newName)) { toast(getString(R.string.enter_name)); return; }
                    String script = "cd -- \"$HOME/../$1\" || exit 4; [ \"$2\" = \"$3\" ] && exit 0; [ ! -e \"$3\" ] && [ ! -L \"$3\" ] || exit 10; mv -- \"$2\" \"$3\"";
                    executeCommand("rename", script, currentPath, oldName, newName);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askCopy(String oldName) {
        String suggested = oldName + " copy";
        EditText input = nameInput(getString(R.string.name_hint)); input.setText(suggested); input.setSelection(input.length());
        new AlertDialog.Builder(this).setTitle(getString(R.string.copy)).setView(input)
                .setPositiveButton(getString(R.string.copy), (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!validName(newName)) { toast(getString(R.string.enter_name)); return; }
                    String script = "cd -- \"$HOME/../$1\" || exit 4; [ ! -e \"$3\" ] && [ ! -L \"$3\" ] || exit 10; cp -a -- \"$2\" \"$3\"";
                    executeCommand("copy", script, currentPath, oldName, newName);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void confirmDeleteOne(String name) {
        new AlertDialog.Builder(this).setTitle(getString(R.string.delete)).setMessage(getString(R.string.delete_one_confirm, name))
                .setPositiveButton(getString(R.string.delete), (d, w) -> executeCommand("delete", "cd -- \"$HOME/../$1\" || exit 4; rm -rf -- \"$2\"", currentPath, name))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle(getString(R.string.delete)).setMessage(getString(R.string.delete_confirm))
                .setPositiveButton(getString(R.string.delete), (d, w) -> {
                    List<String> args = new ArrayList<>(); args.add(currentPath); args.addAll(selected);
                    executeCommand("deleteSelected", "p=$1; shift; cd -- \"$HOME/../$p\" || exit 4; [ \"$#\" -gt 0 ] || exit 5; rm -rf -- \"$@\"", args.toArray(new String[0]));
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askZipOne(String name) { askZip(Collections.singletonList(name)); }

    private void askZipSelected() {
        if (selected.isEmpty()) { toast(getString(R.string.select)); return; }
        askZip(new ArrayList<>(selected));
    }

    private void askZip(List<String> names) {
        EditText input = nameInput(getString(R.string.zip_name_hint));
        String fallback = names.size() == 1 ? names.get(0) + ".zip" : "selected-files.zip";
        input.setText(fallback); input.setSelection(input.length());
        new AlertDialog.Builder(this).setTitle(getString(R.string.compress)).setView(input)
                .setPositiveButton(getString(R.string.create), (d, w) -> {
                    String archive = input.getText().toString().trim();
                    if (!validName(archive) || !archive.toLowerCase(Locale.ROOT).endsWith(".zip")) { toast(getString(R.string.enter_name)); return; }
                    List<String> args = new ArrayList<>(); args.add(currentPath); args.add(archive); args.addAll(names);
                    String script = "cd -- \"$HOME/../$1\" || exit 4; command -v zip >/dev/null 2>&1 || { echo __MISSING_ZIP__ >&2; exit 127; }; [ ! -e \"$2\" ] && [ ! -L \"$2\" ] || exit 10; archive=$2; shift; shift; items=(); for n in \"$@\"; do items+=(\"./$n\"); done; zip -r \"./$archive\" \"${items[@]}\"";
                    executeCommand("zip", script, args.toArray(new String[0]));
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askExtract(String zipName) {
        String folder = zipName.substring(0, zipName.length() - 4);
        EditText input = nameInput(getString(R.string.name_hint)); input.setText(folder); input.setSelection(input.length());
        new AlertDialog.Builder(this).setTitle(getString(R.string.extract)).setView(input)
                .setPositiveButton(getString(R.string.create), (d, w) -> {
                    String dest = input.getText().toString().trim();
                    if (!validName(dest)) { toast(getString(R.string.enter_name)); return; }
                    String script = "cd -- \"$HOME/../$1\" || exit 4; command -v unzip >/dev/null 2>&1 || { echo __MISSING_ZIP__ >&2; exit 127; }; [ ! -e \"$3\" ] && [ ! -L \"$3\" ] || exit 10; mkdir -- \"$3\" && unzip -o \"./$2\" -d \"./$3\"";
                    executeCommand("extract", script, currentPath, zipName, dest);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askInstallZipTools() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.install_zip_tools)).setMessage(getString(R.string.zip_tools_missing))
                .setPositiveButton(getString(R.string.install_zip_tools), (d, w) -> {
                    toast(getString(R.string.zip_installing));
                    executeCommand("installZip", "cd -- \"$HOME\" && pkg install -y zip unzip", currentPath);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void refreshDirectory() {
        if (!isTermuxInstalled()) { renderNoTermux(); return; }
        if (!hasRunCommandPermission()) { renderConnection(false); return; }
        busy = true;
        pendingKind = "list";
        renderBrowser();
        String script = "cd -- \"$HOME/../$1\" || exit 4; shopt -s dotglob nullglob; for p in *; do [ -e \"$p\" ] || [ -L \"$p\" ] || continue; if [ -L \"$p\" ]; then t=l; elif [ -d \"$p\" ]; then t=d; else t=f; fi; s=$(stat -c '%s' -- \"$p\" 2>/dev/null); [ -n \"$s\" ] || s=0; m=$(stat -c '%Y' -- \"$p\" 2>/dev/null); [ -n \"$m\" ] || m=0; n=$(printf %s \"$p\" | base64 | tr -d '\\n'); printf '%s\\t%s\\t%s\\t%s\\n' \"$t\" \"$s\" \"$m\" \"$n\"; done";
        executeCommand("list", script, currentPath);
    }

    private void executeCommand(String kind, String script, String... args) {
        if (busy) { toast(getString(R.string.loading)); return; }
        if (!isTermuxInstalled() || !hasRunCommandPermission()) { renderConnection(false); return; }
        try {
            int id = PluginResultsService.getNextExecutionId();
            activeCommandId = id;
            pendingKind = kind;
            busy = true;

            Intent callback = new Intent(this, PluginResultsService.class);
            callback.putExtra(PluginResultsService.EXTRA_EXECUTION_ID, id);
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent resultPendingIntent = PendingIntent.getService(this, id, callback, flags);

            List<String> cmdArgs = new ArrayList<>();
            cmdArgs.add("--noprofile");
            cmdArgs.add("--norc");
            cmdArgs.add("-c");
            cmdArgs.add(script);
            cmdArgs.add("tfm");
            Collections.addAll(cmdArgs, args);

            Intent command = new Intent("com.termux.RUN_COMMAND");
            command.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
            command.putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash");
            command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", cmdArgs.toArray(new String[0]));
            command.putExtra("com.termux.RUN_COMMAND_WORKDIR", "~");
            command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPendingIntent);
            command.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", getString(R.string.app_name));
            command.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "File operation requested from Termux File Manager");
            startService(command);
        } catch (SecurityException e) {
            busy = false;
            lastError = "Android or Termux blocked the request. Enable the Run Command permission and allow-external-apps=true.";
            renderConnection(true);
        } catch (Exception e) {
            busy = false;
            lastError = e.toString();
            renderConnection(true);
        }
    }

    private void parseListing(String stdout) {
        entries.clear();
        String[] lines = stdout.split("\\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length != 4) continue;
            try {
                String name = new String(Base64.decode(parts[3], Base64.DEFAULT), StandardCharsets.UTF_8);
                long size = parseLong(parts[1]);
                long modified = parseLong(parts[2]) * 1000L;
                String type = parts[0];
                if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
                if (!showHidden && name.startsWith(".")) continue;
                entries.add(new Entry(name, "d".equals(type), "l".equals(type), size, modified));
            } catch (Exception ignored) { }
        }
        selected.retainAll(existingNames());
        Collections.sort(entries, comparator());
    }

    private Set<String> existingNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Entry e : entries) names.add(e.name);
        return names;
    }

    private List<Entry> visibleEntries() {
        List<Entry> visible = new ArrayList<>();
        for (Entry e : entries) if (showHidden || !e.name.startsWith(".")) visible.add(e);
        Collections.sort(visible, comparator());
        return visible;
    }

    private Comparator<Entry> comparator() {
        return (a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            if ("size".equals(sortMode)) return Long.compare(b.size, a.size);
            if ("modified".equals(sortMode)) return Long.compare(b.modified, a.modified);
            return a.name.compareToIgnoreCase(b.name);
        };
    }

    private boolean isProtectedRootItem(String name) {
        return ".".equals(currentPath) && ("home".equals(name) || "usr".equals(name) || "usr-staging".equals(name) || "apps".equals(name));
    }

    private boolean validName(String name) {
        return name != null && !name.trim().isEmpty() && !name.equals(".") && !name.equals("..") && !name.contains("/") && !name.contains("\u0000");
    }

    private String pathLabel() {
        return "Termux files / " + (".".equals(currentPath) ? "" : currentPath);
    }

    private String join(String parent, String child) {
        if (".".equals(parent) || parent.isEmpty()) return child;
        return parent + "/" + child;
    }

    private String parentOf(String path) {
        if (path == null || path.isEmpty() || path.equals(".")) return ".";
        int cut = path.lastIndexOf('/');
        if (cut < 0) return ".";
        String p = path.substring(0, cut);
        return p.isEmpty() ? "." : p;
    }

    private long parseLong(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return 0; }
    }

    private String formatDate(long millis) {
        if (millis <= 0) return "—";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(millis));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024.0; unit++; }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    private EditText nameInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(roundBg("#101010", "#555555", 8));
        return input;
    }

    private void beginPage() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            scroller.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(12), dp(14), dp(18));
        page.setBackgroundColor(Color.BLACK);
        scroller.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
    }

    private void addHeader(String title, String caption, boolean showMenu) {
        LinearLayout row = horizontalRow();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 19, Color.WHITE, true);
        TextView captionView = text(caption, 10, Color.GRAY, true);
        labels.addView(titleView);
        labels.addView(captionView);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (showMenu) {
            Button menu = button("⋮");
            menu.setTextSize(23);
            menu.setMinWidth(dp(48));
            menu.setOnClickListener(v -> showMenu(menu));
            row.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(48)));
        }
        page.addView(row);
    }

    private void addButton(String label, View.OnClickListener listener) {
        UiButton b = button(label);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        page.addView(b, lp);
    }

    private TextView text(String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return tv;
    }

    private void addText(String s, int sp, int color, boolean bold, int left, int top, int right, int bottom) {
        TextView tv = text(s, sp, color, bold);
        tv.setPadding(dp(left), dp(top), dp(right), dp(bottom));
        page.addView(tv);
    }

    private void addCardText(String s, int color) {
        TextView tv = text(s, 13, color, false);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setBackground(roundBg("#171717", "#444444", 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(10));
        page.addView(tv, lp);
    }

    private void addDivider() {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(54, 54, 54));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(10), 0, dp(10));
        page.addView(line, lp);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        return row;
    }

    private LinearLayout.LayoutParams weightedParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        return lp;
    }

    private UiButton button(String label) {
        UiButton b = new UiButton();
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setMinHeight(dp(42));
        b.setPadding(dp(8), dp(5), dp(8), dp(5));
        b.setBackground(roundBg("#111111", "#505050", 9));
        return b;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = button(label);
        b.setOnClickListener(listener);
        return b;
    }


    private final class UiButton extends Button {
        UiButton() { super(MainActivity.this); }
        UiButton withClick(View.OnClickListener listener) { setOnClickListener(listener); return this; }
    }

    private GradientDrawable roundBg(String fill, String stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), Color.parseColor(stroke));
        return drawable;
    }

    private void addLocalGap(LinearLayout target, int height) {
        View gap = new View(this);
        target.addView(gap, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void copyText(String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Termux setting", value));
        toast(getString(R.string.copied));
    }

    private void openTermux() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
            if (launch != null) startActivity(launch); else openUrl(GITHUB_TERMUX);
        } catch (Exception e) { openUrl(GITHUB_TERMUX); }
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast(getString(R.string.no_app_to_open)); }
    }

    private void showErrorToast(String error) {
        String e = (error == null || error.trim().isEmpty()) ? "Operation failed" : error.trim();
        if (e.length() > 240) e = e.substring(0, 240) + "…";
        new AlertDialog.Builder(this).setTitle(getString(R.string.error_title)).setMessage(e).setPositiveButton(android.R.string.ok, null).show();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private static final class Entry {
        final String name;
        final boolean directory;
        final boolean symlink;
        final long size;
        final long modified;
        Entry(String name, boolean directory, boolean symlink, long size, long modified) {
            this.name = name; this.directory = directory; this.symlink = symlink; this.size = size; this.modified = modified;
        }
    }
}

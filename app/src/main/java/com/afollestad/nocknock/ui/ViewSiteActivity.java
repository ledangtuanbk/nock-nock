package com.afollestad.nocknock.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.Patterns;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.afollestad.bridge.Bridge;
import com.afollestad.nocknock.R;
import com.afollestad.nocknock.api.ServerModel;
import com.afollestad.nocknock.api.ServerStatus;
import com.afollestad.nocknock.services.CheckService;
import com.afollestad.nocknock.util.TimeUtil;
import com.afollestad.nocknock.views.StatusImageView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * @author Aidan Follestad (afollestad)
 */
public class ViewSiteActivity extends AppCompatActivity implements View.OnClickListener, Toolbar.OnMenuItemClickListener {

    private StatusImageView iconStatus;
    private EditText inputName;
    private EditText inputUrl;
    private EditText inputCheckInterval;
    private Spinner checkIntervalSpinner;
    private TextView textLastCheckResult;
    private TextView textNextCheck;

    private ServerModel mModel;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v("ViewSiteActivity", "Received " + intent.getAction());
            final ServerModel model = (ServerModel) intent.getSerializableExtra("model");
            if (model != null) {
                mModel = model;
                update();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewsite);

        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        toolbar.inflateMenu(R.menu.menu_viewsite);
        toolbar.setOnMenuItemClickListener(this);

        iconStatus = (StatusImageView) findViewById(R.id.iconStatus);
        inputName = (EditText) findViewById(R.id.inputName);
        inputUrl = (EditText) findViewById(R.id.inputUrl);
        inputCheckInterval = (EditText) findViewById(R.id.checkIntervalInput);
        checkIntervalSpinner = (Spinner) findViewById(R.id.checkIntervalSpinner);
        textLastCheckResult = (TextView) findViewById(R.id.textLastCheckResult);
        textNextCheck = (TextView) findViewById(R.id.textNextCheck);

        ArrayAdapter<String> intervalOptionsAdapter = new ArrayAdapter<>(this, R.layout.list_item_spinner,
                getResources().getStringArray(R.array.interval_options));
        checkIntervalSpinner.setAdapter(intervalOptionsAdapter);

        mModel = (ServerModel) getIntent().getSerializableExtra("model");
        update();

        Bridge.config()
                .defaultHeader("User-Agent", getString(R.string.app_name) + " (Android)");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra("model")) {
            mModel = (ServerModel) intent.getSerializableExtra("model");
            update();
        }
    }

    @SuppressLint("SetTextI18n")
    private void update() {
        final SimpleDateFormat df = new SimpleDateFormat("MMMM dd, hh:mm:ss a", Locale.getDefault());

        iconStatus.setStatus(mModel.status);
        inputName.setText(mModel.name);
        inputUrl.setText(mModel.url);

        if (mModel.lastCheck == 0) {
            textLastCheckResult.setText(R.string.none);
        } else {
            switch (mModel.status) {
                case ServerStatus.CHECKING:
                    textLastCheckResult.setText(R.string.checking_status);
                    break;
                case ServerStatus.ERROR:
                    textLastCheckResult.setText(mModel.reason);
                    break;
                case ServerStatus.OK:
                    textLastCheckResult.setText(R.string.everything_checks_out);
                    break;
                case ServerStatus.WAITING:
                    textLastCheckResult.setText(R.string.waiting);
                    break;
            }
        }

        if (mModel.checkInterval == 0) {
            textNextCheck.setText(R.string.none_turned_off);
            inputCheckInterval.setText("");
            checkIntervalSpinner.setSelection(0);
        } else {
            long lastCheck = mModel.lastCheck;
            if (lastCheck == 0) lastCheck = System.currentTimeMillis();
            textNextCheck.setText(df.format(new Date(lastCheck + mModel.checkInterval)));

            if (mModel.checkInterval >= TimeUtil.WEEK) {
                inputCheckInterval.setText(Integer.toString((int) Math.ceil(((float) mModel.checkInterval / (float) TimeUtil.WEEK))));
                checkIntervalSpinner.setSelection(3);
            } else if (mModel.checkInterval >= TimeUtil.DAY) {
                inputCheckInterval.setText(Integer.toString((int) Math.ceil(((float) mModel.checkInterval / (float) TimeUtil.DAY))));
                checkIntervalSpinner.setSelection(2);
            } else if (mModel.checkInterval >= TimeUtil.HOUR) {
                inputCheckInterval.setText(Integer.toString((int) Math.ceil(((float) mModel.checkInterval / (float) TimeUtil.HOUR))));
                checkIntervalSpinner.setSelection(1);
            } else if (mModel.checkInterval >= TimeUtil.MINUTE) {
                inputCheckInterval.setText(Integer.toString((int) Math.ceil(((float) mModel.checkInterval / (float) TimeUtil.MINUTE))));
                checkIntervalSpinner.setSelection(0);
            } else {
                inputCheckInterval.setText("0");
                checkIntervalSpinner.setSelection(0);
            }
        }

        findViewById(R.id.doneBtn).setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(CheckService.ACTION_CHECK_UPDATE);
            // filter.addAction(CheckService.ACTION_RUNNING);
            registerReceiver(mReceiver, filter);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(mReceiver);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // Save button
    @Override
    public void onClick(View view) {
        mModel.name = inputName.getText().toString().trim();
        mModel.url = inputUrl.getText().toString().trim();
        mModel.status = ServerStatus.WAITING;

        if (mModel.name.isEmpty()) {
            inputName.setError(getString(R.string.please_enter_name));
            return;
        } else {
            inputName.setError(null);
        }

        if (mModel.url.isEmpty()) {
            inputUrl.setError(getString(R.string.please_enter_url));
            return;
        } else {
            inputUrl.setError(null);
            if (!Patterns.WEB_URL.matcher(mModel.url).find()) {
                inputUrl.setError(getString(R.string.please_enter_valid_url));
                return;
            }
        }

        String intervalStr = inputCheckInterval.getText().toString().trim();
        if (intervalStr.isEmpty()) intervalStr = "0";
        mModel.checkInterval = Integer.parseInt(intervalStr);

        switch (checkIntervalSpinner.getSelectedItemPosition()) {
            case 0: // minutes
                mModel.checkInterval *= (60 * 1000);
                break;
            case 1: // hours
                mModel.checkInterval *= (60 * 60 * 1000);
                break;
            case 2: // days
                mModel.checkInterval *= (60 * 60 * 24 * 1000);
                break;
            default: // weeks
                mModel.checkInterval *= (60 * 60 * 24 * 7 * 1000);
                break;
        }

        mModel.lastCheck = System.currentTimeMillis() - mModel.checkInterval;

        setResult(RESULT_OK, new Intent().putExtra("model", mModel));
        finish();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                MainActivity.checkSite(this, mModel);
                return true;
            case R.id.remove:
                MainActivity.removeSite(this, mModel, () -> finish());
                return true;
        }
        return false;
    }
}
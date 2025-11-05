package com.team4.hackathon;

import android.Manifest;
import android.content.Context;
import org.jtransforms.fft.DoubleFFT_1D;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import gtec.java.unicorn.Unicorn;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private List<float[]> calibrationCollection = new ArrayList<>();
    private List<float[]> testCollection = new ArrayList<>();
    private boolean isCalibrating = false, isTesting = false;
    private static final long CALIBRATION_DURATION_MS = 30_000;

    private String _btnConStr = "Connect", _btnDisconStr = "Disconnect";
    private Button _btnConnect, _btnCalibrate, _btnTest;
    private Spinner _spnDevices;
    private TextView _tvState;
    private Unicorn _unicorn;
    private Thread _receiver;
    private boolean _receiverRunning = false;
    private Context _context = null;

    // Baseline PSD channels separated
    // Ch8-16 for this project is a toss, as our team did not use the accelometer, and gyroscope
    // Battery information can and will be implemented

    private double[] baselineDelta, baselineAlpha, baselineBeta;

    private static final int PermissionRequestCode = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            _context = this.getApplicationContext();
            _spnDevices = findViewById(R.id.spnDevices);
            _btnConnect = findViewById(R.id.btnConnect);
            _btnCalibrate = findViewById(R.id.btnCalibrate);
            _btnTest = findViewById(R.id.btnTest);
            _tvState = findViewById(R.id.textView);

            _btnConnect.setText(_btnConStr);
            _btnConnect.setOnClickListener(this);
            _btnCalibrate.setOnClickListener(this);
            _btnTest.setOnClickListener(this);

        } catch (Exception ex) {
            Toast.makeText(_context, "UI error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            Toast.makeText(this, "Bluetooth not supported.", Toast.LENGTH_SHORT).show();
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_SCAN}, PermissionRequestCode);
            } else {
                GetAvailableDevices();
            }
        } catch (Exception ex) {
            Toast.makeText(_context, "Permission error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PermissionRequestCode && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            GetAvailableDevices();
        }
    }

    private void GetAvailableDevices() {
        try {
            List<String> devices = Unicorn.GetAvailableDevices();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, devices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            _spnDevices.setAdapter(adapter);
        } catch (Exception ex) {
            Toast.makeText(_context, "Device error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void StartReceiver() {
        _receiverRunning = true;
        _receiver = new Thread(() -> {
            while (_receiverRunning) {
                try {
                    float[] data = _unicorn.GetData();
                    if (isCalibrating) calibrationCollection.add(data);
                    if (isTesting) testCollection.add(data);
                } catch (Exception ex) {
                    runOnUiThread(() -> {
                        _tvState.append("\nAcquisition failed. " + ex.getMessage());
                        Disconnect();
                    });
                }
            }
        });
        _receiver.setPriority(Thread.MIN_PRIORITY);
        _receiver.setDaemon(false);
        _receiver.start();
    }

    private void StopReceiver() throws Exception {
        _receiverRunning = false;
        _receiver.join(500);
    }

    private void startCalibration() {
        calibrationCollection.clear();
        isCalibrating = true;
        _tvState.append("\nCalibration started...");
        if (!_receiverRunning) StartReceiver();
        new Handler(getMainLooper()).postDelayed(() -> {
            isCalibrating = false;
            _tvState.append("\nCalibration finished. Samples: " + calibrationCollection.size());
            storeBaselinePSD(calibrationCollection);
        }, CALIBRATION_DURATION_MS);
    }

    private void startTest() {
        testCollection.clear();
        isTesting = true;
        _tvState.append("\nTest started...");
        new Handler(getMainLooper()).postDelayed(() -> {
            isTesting = false;
            _tvState.append("\nTest finished. Samples: " + testCollection.size());
            compareToBaseline(testCollection);
        }, CALIBRATION_DURATION_MS);
    }

    private void storeBaselinePSD(List<float[]> calibData) {
        if (calibData == null || calibData.isEmpty()) {
            Log.d("BASE", "Nincs baseline sample!");
            _tvState.append("\nNo samples at calibration.");
            return;
        }
        int channels = calibData.get(0).length;
        int n = calibData.size();
        double Fs = 256.0;
        double[][] eeg = new double[channels][n];
        for (int ch = 0; ch < channels; ch++)
            for (int t = 0; t < n; t++) eeg[ch][t] = calibData.get(t)[ch];
        for (int ch = 0; ch < channels; ch++)
            for (int t = 0; t < n; t++)
                eeg[ch][t] *= (0.54 - 0.46 * Math.cos(2*Math.PI*t/(n-1)));
        double[] delta = new double[channels], alpha = new double[channels], beta = new double[channels];
        for (int ch = 0; ch < channels; ch++) {
            DoubleFFT_1D fft = new DoubleFFT_1D(n);
            fft.realForward(eeg[ch]);
            int dc=0, ac=0, bc=0;
            for (int k=0; k<n/2; k++) {
                double re = eeg[ch][2*k], im = eeg[ch][2*k+1];
                double psd = (re*re+im*im)/(n*Fs);
                double f = k*Fs/n;
                if (f>=0.5 && f<=4) {delta[ch] += psd; dc++;}
                else if (f>=8 && f<=13) {alpha[ch] += psd; ac++;}
                else if (f>13 && f<=30) {beta[ch] += psd; bc++;}
            }
            if (dc>0) delta[ch] /= dc;
            if (ac>0) alpha[ch] /= ac;
            if (bc>0) beta[ch] /= bc;
            Log.d("BASE", "CH"+ch+" Δ="+delta[ch]+", α="+alpha[ch]+", β="+beta[ch]);
        }
        baselineDelta = delta; baselineAlpha = alpha; baselineBeta = beta;
        Log.d("CHECK", "Baseline calculated: delta[0]=" + baselineDelta[0]);
        _tvState.append("\nBaseline stored.");
    }

    private void compareToBaseline(List<float[]> testData) {
        if (testData == null || testData.isEmpty() || baselineDelta==null) {
            _tvState.append("\nNo baseline or test data!");
            return;
        }
        int channels = testData.get(0).length, n = testData.size();
        double Fs = 256.0;
        double[][] eeg = new double[channels][n];
        for (int ch = 0; ch < channels; ch++)
            for (int t = 0; t < n; t++) eeg[ch][t] = testData.get(t)[ch];
        for (int ch = 0; ch < channels; ch++)
            for (int t = 0; t < n; t++)
                eeg[ch][t] *= (0.54 - 0.46 * Math.cos(2*Math.PI*t/(n-1)));
        for (int ch = 0; ch < channels; ch++) {
            DoubleFFT_1D fft = new DoubleFFT_1D(n);
            fft.realForward(eeg[ch]);
            double delta=0, alpha=0, beta=0; int dc=0,ac=0,bc=0;
            for (int k=0; k<n/2; k++) {
                double re=eeg[ch][2*k], im=eeg[ch][2*k+1];
                double psd = (re*re+im*im)/(n*Fs);
                double f = k*Fs/n;
                if (f>=0.5&&f<=4){delta+=psd;dc++;}
                else if(f>=8&&f<=13){alpha+=psd;ac++;}
                else if(f>13&&f<=30){beta+=psd;bc++;}
            }
            if(dc>0) delta/=dc;
            if(ac>0) alpha/=ac;
            if(bc>0) beta/=bc;
            double relDelta = delta / baselineDelta[ch];
            double relAlpha = alpha / baselineAlpha[ch];
            double relBeta = beta / baselineBeta[ch];
            Log.d("EEG_REL", "CH"+ch+" Δrel="+relDelta+" αrel="+relAlpha+" βrel="+relBeta);
            _tvState.append("\nCH"+ch+" Δrel="+String.format("%.2f",relDelta)+", αrel="+String.format("%.2f",relAlpha)+", βrel="+String.format("%.2f",relBeta));
        }
    }

    private void Connect() {
        _btnConnect.setEnabled(false);
        _spnDevices.setEnabled(false);
        String device = (String)_spnDevices.getSelectedItem();
        try {
            _tvState.setText("Connecting to " + device +"...");
            _unicorn = new Unicorn(device);
            _btnConnect.setText(_btnDisconStr);
            _unicorn.StartAcquisition();
            _tvState.append("\nConnected.\nAcquisition running.");
            StartReceiver();
        } catch (Exception ex) {
            _unicorn = null;
            System.gc();
            System.runFinalization();
            _btnConnect.setText(_btnConStr);
            _spnDevices.setEnabled(true);
            _tvState.append("\nConnect failed: "+ ex.getMessage());
        }
        _btnConnect.setEnabled(true);
    }

    private void Disconnect() {
        _btnConnect.setEnabled(false);
        try {
            _receiverRunning = false;
            try { StopReceiver(); } catch(Exception e){ Log.e("Disconnect","StopReceiver fail",e);}
            if (_unicorn != null) {
                try { _unicorn.StopAcquisition(); } catch(Exception e){ Log.e("Disconnect","StopAcq fail",e);}
                _unicorn = null;
            }
            _tvState.append("\nDisconnected");
        } catch (Exception ex) {
            _unicorn = null;
            System.gc(); System.runFinalization();
            _btnConnect.setText(_btnConStr);
            _tvState.append("\nDisconnect error: "+ ex.getMessage());
        }
        _spnDevices.setEnabled(true);
        _btnConnect.setEnabled(true);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btnConnect) {
            if (_btnConnect.getText().equals(_btnConStr)) Connect(); else Disconnect();
        }
        if (v.getId() == R.id.btnCalibrate) startCalibration();
        if (v.getId() == R.id.btnTest) startTest();
    }
}

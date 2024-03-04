package com.example.sockettester;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static Handler mainLooperHandler = new Handler(Looper.getMainLooper());


    private Context context;

    Button sendBtn;
    Button clearBtn;
    EditText messageTextInput;
    EditText hostInput;
    EditText portInput;
    EditText ssidInput;
    EditText passInput;
    TextView resultText;
    Switch switchCR;
    Switch switchLF;
    EditText hbInput;
    TextView hbTextView;

    Socket socket;
    OutputStream out;
    InputStream in;
    InputStreamReader isr;
    Thread readerThread;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        context = this.getApplicationContext();

        sendBtn = findViewById(R.id.sendBtn);
        clearBtn = findViewById(R.id.clearBtn);
        messageTextInput = findViewById(R.id.messageTextInput);
        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        ssidInput = findViewById(R.id.ssidInput);
        passInput = findViewById(R.id.passInput);
        resultText = findViewById(R.id.resultText);
        switchCR = findViewById(R.id.switchCR);
        switchLF = findViewById(R.id.switchLF);
        hbInput = findViewById(R.id.hbInput);
        hbTextView = findViewById(R.id.hbTextView);

        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.requestNetwork(requestBuilder.build(), new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cm.bindProcessToNetwork(network);
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
            }
        });

        resultText.setMovementMethod(new ScrollingMovementMethod());

        sendBtn.setOnClickListener(view -> {
            Thread writerThread = new Thread(() -> handleSendClick());
            writerThread.start();
        });
        clearBtn.setOnClickListener(view -> {
            resultText.setText("cleared\n");
        });


        readerThread = new Thread(() -> socketScanner());
        readerThread.start();

        runUI(() -> {
            SharedPreferences preferences = getPreferences(MODE_PRIVATE);
            if (messageTextInput.getText().toString().isEmpty())
                messageTextInput.setText(preferences.getString("command", ""));
            if (hostInput.getText().toString().isEmpty())
                hostInput.setText(preferences.getString("host", ""));
            if (portInput.getText().toString().isEmpty())
                portInput.setText(preferences.getString("port", ""));
            if (ssidInput.getText().toString().isEmpty())
                ssidInput.setText(preferences.getString("ssid", ""));
            if (passInput.getText().toString().isEmpty())
                passInput.setText(preferences.getString("pass", ""));
            if (hbInput.getText().toString().isEmpty())
                hbInput.setText(preferences.getString("hb", ""));
        });
    }

    void log(String arg, String... args) {
        runUI(() -> {
            resultText.append(arg);
            for (String more : args) {
                resultText.append(" ");
                resultText.append(more == null ? "null" : more);
            }
            resultText.append("\n");
        });
    }

    void logRaw(String arg, String... args) {
        runUI(() -> {
            resultText.append(arg);
            for (String more : args) {
                resultText.append(" ");
                resultText.append(more == null ? "null" : more);
            }
        });
    }

    void runUI(Runnable r) {
        mainLooperHandler.post(r);
    }

    void handleSendClick() {
        runUI(() -> {
            SharedPreferences preferences = getPreferences(MODE_PRIVATE);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("command", messageTextInput.getText().toString());
            editor.putString("host", hostInput.getText().toString());
            editor.putString("port", portInput.getText().toString());
            editor.putString("ssid", ssidInput.getText().toString());
            editor.putString("pass", passInput.getText().toString());
            editor.putString("hb", hbInput.getText().toString());
            editor.commit();
        });

        try {
            String networkSSID = ssidInput.getText().toString();
            String networkPass = passInput.getText().toString();

            if (networkSSID.isEmpty()) {
                log("ssid is empty");
                return;
            }

            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

            if (wifiManager.getConnectionInfo().getSupplicantState() == SupplicantState.DISCONNECTED) {
                log("Connecting to " + networkSSID);

                WifiConfiguration conf = new WifiConfiguration();
                conf.SSID = "\"" + networkSSID + "\"";
                conf.preSharedKey = "\"" + networkPass + "\"";
                wifiManager.addNetwork(conf);
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                    return;
                }
                List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
                for(WifiConfiguration i : list) {
                    if(i.SSID != null && i.SSID.equals("\"" + networkSSID + "\"")) {
                        wifiManager.disconnect();
                        wifiManager.enableNetwork(i.networkId, true);
                        wifiManager.reconnect();
                        break;
                    }
                }
            }

            String message = messageTextInput.getText().toString()
                + (switchCR.isChecked() ? "\r" : "")
                + (switchLF.isChecked() ? "\n" : "");
            String host = hostInput.getText().toString();
            if (host.isEmpty()) {
                log("host is empty");
                return;
            }
            int port = Integer.parseInt(portInput.getText().toString());

            SocketAddress addr = new InetSocketAddress(host, port);

            if (socket == null || !socket.isConnected()) {
                log("Connecting to", host, Integer.toString(port));
                socket = new Socket();
                socket.connect(addr, 15000);
                socket.setSoTimeout(15000);
            }

            try {
                out = socket.getOutputStream();
                out.write(message.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception exc) {
                log("write_err:", exc.getClass().getSimpleName(), exc.getMessage());
            }

        } catch (Exception exc) {
            log("setup_err:", exc.getClass().getSimpleName(), exc.getMessage());
        }
    }

    void socketScanner() {

        while (true) {
            try {
                if (socket != null && socket.isConnected()) {
                    in = socket.getInputStream();
                    isr = new InputStreamReader(in);
                } else {
                    continue;
                }

                String hbMessage = hbInput.getText().toString()
                        + (switchCR.isChecked() ? "\r" : "")
                        + (switchLF.isChecked() ? "\n" : "");

                StringBuilder sb = new StringBuilder();
                for (int ch = isr.read(); ch != -1; ch = isr.read()) {
                    sb.append((char) ch);
                    if (ch == '\n') {
                        String acced = sb.toString();
                        if (!hbMessage.equals(acced)) {
                            logRaw(acced);
                        } else {
                            runUI(() -> {
                                String v = hbTextView.getText().toString();
                                if (v.equals("/"))
                                    hbTextView.setText("-");
                                else if (v.equals("-"))
                                    hbTextView.setText("\\");
                                else if (v.equals("\\"))
                                    hbTextView.setText("|");
                                else if (v.equals("|"))
                                    hbTextView.setText("/");
                                else
                                    hbTextView.setText("/");
                            });
                        }
                        sb.setLength(0);
                    }
                }
            } catch (Exception exc) {
                log("read_err:", exc.getClass().getSimpleName(), exc.getMessage());
            }
        }
    }
}
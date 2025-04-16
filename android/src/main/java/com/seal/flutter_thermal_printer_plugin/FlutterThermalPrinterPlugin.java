package com.seal.flutter_thermal_printer_plugin;

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/** FlutterThermalPrinterPlugin */
public class FlutterThermalPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler {

  private static final String TAG = "BThermalPrinterPlugin";
  private static final String NAMESPACE = "blue_thermal_printer";
  //private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
  private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  private static ConnectedThread THREAD = null;
  private BluetoothAdapter mBluetoothAdapter;

  final int REQUEST_ENABLE_BT = 110;
  final int REQUEST_ENABLE_BT_FOR_BONED_DEVICE = 111;
  final int SELECT_DEVICE_REQUEST_CODE = 112;
  final int ANDROID_12_PLUS_REQUEST_CODE = 113;
  final int REQUEST_LOCATION_ON = 114;

  private Result pendingResult;

  //private EventSink readSink;
  private EventChannel.EventSink statusSink;

  private FlutterPluginBinding pluginBinding;
  private ActivityPluginBinding activityBinding;
  private Object initializationLock = new Object();
  private Context context;
  private MethodChannel channel;

  private EventChannel stateChannel;
  //private EventChannel readChannel;
  private BluetoothManager mBluetoothManager;

  private Application application;
  private Activity activity;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    pluginBinding = binding;
    channel = new MethodChannel(binding.getBinaryMessenger(), NAMESPACE + "/methods");
    channel.setMethodCallHandler(this);
    stateChannel = new EventChannel(binding.getBinaryMessenger(), NAMESPACE + "/state");
    stateChannel.setStreamHandler(stateStreamHandler);
    context = binding.getApplicationContext();
    mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = mBluetoothManager.getAdapter();
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    channel = null;
    stateChannel.setStreamHandler(null);
    stateChannel = null;
    context = null;
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activityBinding = binding;
    activity = binding.getActivity();
    binding.addActivityResultListener((requestCode, resultCode, data) -> {
      boolean handled = false;
      switch(requestCode) {
        case REQUEST_ENABLE_BT_FOR_BONED_DEVICE:
          if(resultCode==RESULT_OK){
            getBondedDevices(pendingResult);
          }
          else{
            pendingResult.error("no_permissions", "this plugin requires bluetooth on", null);
            pendingResult = null;
          }
          handled = true;
          break;
        case REQUEST_ENABLE_BT:
          if(resultCode==RESULT_OK){
            pendingResult.success(true);
          }
          else{
            pendingResult.error("no_permissions", "this plugin requires bluetooth on", null);
            pendingResult = null;
          }
          handled = true;
          break;
        case SELECT_DEVICE_REQUEST_CODE:
          if (resultCode == RESULT_OK && data != null) {
            BluetoothDevice deviceToPair = data.getParcelableExtra(
                    CompanionDeviceManager.EXTRA_DEVICE
            );

            if (deviceToPair != null) {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                deviceToPair.createBond();
              }
            }
          }
          handled = true;
          break;
        case REQUEST_LOCATION_ON:
          if(resultCode==RESULT_OK){
            pendingResult.success(true);
          }
          else{
            pendingResult.success(false);
            pendingResult = null;
          }
          handled = true;
          break;
      }
      return handled;
    });

    binding.addRequestPermissionsResultListener((requestCode, permissions, grantResults) -> {
      if(requestCode == ANDROID_12_PLUS_REQUEST_CODE){
        boolean accepted = false;
        for(int res : grantResults){
          accepted = res == PackageManager.PERMISSION_GRANTED;
        }
        if(accepted){
          pendingResult.success(true);
        }
        else{
          pendingResult.success(false);
        }
        pendingResult = null;
        return true;
      }
      return false;
    });
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    if (activityBinding != null) {
      activityBinding.removeActivityResultListener(null);
      activityBinding.removeRequestPermissionsResultListener(null);
      activityBinding = null;
    }
    activity = null;
  }

  // MethodChannel.Result wrapper that responds on the platform thread.
  private static class MethodResultWrapper implements Result {
    private Result methodResult;
    private Handler handler;

    MethodResultWrapper(Result result) {
      methodResult = result;
      handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void success(final Object result) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          methodResult.success(result);
        }
      });
    }

    @Override
    public void error(final String errorCode, final String errorMessage, final Object errorDetails) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          methodResult.error(errorCode, errorMessage, errorDetails);
        }
      });
    }

    @Override
    public void notImplemented() {
      handler.post(new Runnable() {
        @Override
        public void run() {
          methodResult.notImplemented();
        }
      });
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  @Override
  public void onMethodCall(MethodCall call, Result rawResult) {
    Result result = new MethodResultWrapper(rawResult);

    if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
      result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
      return;
    }

    final Map<String, Object> arguments = call.arguments();

    switch (call.method) {

      case "isAvailable":
        result.success(mBluetoothAdapter != null);
        break;

      case "isOn":
        try {
          assert mBluetoothAdapter != null;
          result.success(mBluetoothAdapter.isEnabled());
        } catch (Exception ex) {
          result.error("Error", ex.getMessage(), exceptionToString(ex));
        }
        break;

      case "isConnected":
        result.success(THREAD != null);
        break;
      case "checkPermission12":
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
          result.success(true);
        }
        else{
          int result_bluetooth = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT);
          int result_bluetooth_scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN);
          if (result_bluetooth == PackageManager.PERMISSION_GRANTED && result_bluetooth_scan == PackageManager.PERMISSION_GRANTED) {
            result.success(true);
          }
          else{
            pendingResult = result;
            String[] permissions;
            if(result_bluetooth != PackageManager.PERMISSION_GRANTED && result_bluetooth_scan == PackageManager.PERMISSION_GRANTED){
              permissions = new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN};
            }
            else if(result_bluetooth != PackageManager.PERMISSION_GRANTED){
              permissions = new String[]{Manifest.permission.BLUETOOTH_CONNECT};
            }
            else{
              permissions = new String[]{Manifest.permission.BLUETOOTH_SCAN};
            }
            ActivityCompat.requestPermissions(activity, permissions, ANDROID_12_PLUS_REQUEST_CODE);
          }
        }
        /*val requiredPermissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
      } else {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
      }

        val missingPermissions = requiredPermissions.filter { permission ->
              checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
      }
      if (missingPermissions.isEmpty()) {

      } else {
        requestPermissions(missingPermissions.toTypedArray(), BLUETOOTH_PERMISSION_REQUEST_CODE)
      }*/
        break;

      case "openSettings":
        ContextCompat.startActivity(context, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                null);
        result.success(true);
        break;
      case "On":
        if (!mBluetoothAdapter.isEnabled()) {
          Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
          pendingResult = result;
          activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        else{
          result.success(true);
        }
        break;

      case "getBondedDevices":
        //BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
          // Device doesn't support Bluetooth
        }
        else {
          if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            pendingResult = result;
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT_FOR_BONED_DEVICE);
          }
          else{
            getBondedDevices(result);
          }
        }
        /*try {

          if (ContextCompat.checkSelfPermission(activity,
                  Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(activity,
                    new String[] { Manifest.permission.ACCESS_COARSE_LOCATION }, REQUEST_COARSE_LOCATION_PERMISSIONS);

            pendingResult = result;
            break;
          }

          getBondedDevices(result);

        } catch (Exception ex) {
          result.error("Error", ex.getMessage(), exceptionToString(ex));
        }*/

        break;
      case "onLocation":
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);
        Task<LocationSettingsResponse> taskSettingsClient = LocationServices.getSettingsClient(activity)
                .checkLocationSettings(builder.build());
        taskSettingsClient.addOnSuccessListener(locationSettingsResponse -> {
          if(locationSettingsResponse.getLocationSettingsStates().isLocationPresent()){
            result.success(true);
          }
        });
        taskSettingsClient.addOnFailureListener(e -> {
          if (e instanceof ResolvableApiException) {
            try {
              // Handle result in onActivityResult()
              pendingResult = result;
              ((ResolvableApiException) e).startResolutionForResult(activity,
                      REQUEST_LOCATION_ON);
            } catch (IntentSender.SendIntentException exception) {
              result.success(false);
            }
          }
        });
        

/*
        activity?.let {
        val locationRequest = LocationRequest.create()
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        val builder = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)

        val task = LocationServices.getSettingsClient(it)
                .checkLocationSettings(builder.build())

        task.addOnSuccessListener { response ->
                val states = response.locationSettingsStates
          if (states.isLocationPresent) {
            //Do something
          }
        }
        task.addOnFailureListener { e ->
          if (e is ResolvableApiException) {
            try {
              // Handle result in onActivityResult()
              e.startResolutionForResult(it,
                      MainActivity.LOCATION_SETTING_REQUEST)
            } catch (sendEx: IntentSender.SendIntentException) { }
          }
        }
      }*/
        break;

      case "connect":
        if (arguments.containsKey("address")) {
          String address = (String) arguments.get("address");
          connect(result, address);
        } else {
          result.error("invalid_argument", "argument 'address' not found", null);
        }
        break;
      case "tcpconnect":
        if (arguments.containsKey("address")) {
          String address = (String) arguments.get("address");
          int port = (Integer) arguments.get("port");
          tcpConnect(result, address,port);
        } else {
          result.error("invalid_argument", "argument 'address' not found", null);
        }
        break;

      case "disconnect":
        disconnect(result);
        break;

      case "write":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          write(result, message);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "writeBytes":
        if (arguments.containsKey("message")) {
          byte[] message = (byte[]) arguments.get("message");
          writeBytes(result, message);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "printCustom":
        if (arguments.containsKey("message")) {
          String message = (String) arguments.get("message");
          int size = (int) arguments.get("size");
          int align = (int) arguments.get("align");
          String charset = (String) arguments.get("charset");
          printCustom(result, message, size, align, charset);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;

      case "printNewLine":
        if (arguments.containsKey("size")) {
          int size = (int) arguments.get("size");
          printNewLine(result, (byte) size);
        }
        break;

      case "paperCut":
        paperCut(result);
        break;

      case "printImage":
        if (arguments.containsKey("pathImage")) {
          String pathImage = (String) arguments.get("pathImage");
          printImage(result, pathImage);
        } else {
          result.error("invalid_argument", "argument 'pathImage' not found", null);
        }
        break;

      case "printImageBytes":
        if (arguments.containsKey("bytes")) {
          byte[] bytes = (byte[]) arguments.get("bytes");
          printImageBytes(result, bytes);
        } else {
          result.error("invalid_argument", "argument 'bytes' not found", null);
        }
        break;

      case "printQRcode":
        if (arguments.containsKey("textToQR")) {
          String textToQR = (String) arguments.get("textToQR");
          int width = (int) arguments.get("width");
          int height = (int) arguments.get("height");
          int align = (int) arguments.get("align");
          printQRcode(result, textToQR, width, height, align);
        } else {
          result.error("invalid_argument", "argument 'textToQR' not found", null);
        }
        break;
      case "printLeftRight":
        if (arguments.containsKey("string1")) {
          String string1 = (String) arguments.get("string1");
          String string2 = (String) arguments.get("string2");
          int size = (int) arguments.get("size");
          String charset = (String) arguments.get("charset");
          String format = (String) arguments.get("format");
          String isBold = (String) arguments.get("bold");
          printLeftRight(result, string1, string2, size, charset,format,isBold.equals("yes")?true:false);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;
      case "print3Column":
        if (arguments.containsKey("string1")) {
          String string1 = (String) arguments.get("string1");
          String string2 = (String) arguments.get("string2");
          String string3 = (String) arguments.get("string3");
          int size = (int) arguments.get("size");
          String charset = (String) arguments.get("charset");
          String format = (String) arguments.get("format");
          print3Column(result, string1, string2,string3, size, charset,format);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;
      case "print4Column":
        if (arguments.containsKey("string1")) {
          String string1 = (String) arguments.get("string1");
          String string2 = (String) arguments.get("string2");
          String string3 = (String) arguments.get("string3");
          String string4 = (String) arguments.get("string4");
          int size = (int) arguments.get("size");
          String charset = (String) arguments.get("charset");
          String format = (String) arguments.get("format");
          print4Column(result, string1, string2,string3,string4, size, charset,format);
        } else {
          result.error("invalid_argument", "argument 'message' not found", null);
        }
        break;
      case "newpair":
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          startParingDevice();
        }
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  private boolean isLocationEnabled(Context context) {
    LocationManager locationManager =
            (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    return LocationManagerCompat.isLocationEnabled(locationManager);
  }

  // Remove the @Override annotation from onRequestPermissionsResult since it's no longer implementing an interface
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if(requestCode == ANDROID_12_PLUS_REQUEST_CODE){
      boolean accepted = false;
      for(int res : grantResults){
        accepted = res == PackageManager.PERMISSION_GRANTED;
      }
      if(accepted){
        pendingResult.success(true);
      }
      else{
        pendingResult.success(false);
      }
      pendingResult = null;
      return true;
    }
    return false;
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  public void startParingDevice(){
    CompanionDeviceManager deviceManager =
            (CompanionDeviceManager) activity.getSystemService(
                    Context.COMPANION_DEVICE_SERVICE
            );

    // To skip filtering based on name and supported feature flags,
    // don't include calls to setNamePattern() and addServiceUuid(),
    // respectively. This example uses Bluetooth.
    BluetoothDeviceFilter deviceFilter =
            new BluetoothDeviceFilter.Builder()
                    // Match only Bluetooth devices whose name matches the pattern.
                    //.setNamePattern(Pattern.compile("Print"))
                    // Match only Bluetooth devices whose service UUID matches this pattern.
                    //.addServiceUuid(new ParcelUuid(new UUID(0x123abcL, -1L)), null)
                    .build();

    // The argument provided in setSingleDevice() determines whether a single
    // device name or a list of device names is presented to the user as
    // pairing options.
    AssociationRequest pairingRequest = new AssociationRequest.Builder()
            // Find only devices that match this request filter.
            .addDeviceFilter(deviceFilter)
            // true = Stop scanning as soon as one device matching the filter is found.
            .setSingleDevice(false)
            .build();

    // When the app tries to pair with the Bluetooth device, show the
    // appropriate pairing request dialog to the user.
    deviceManager.associate(pairingRequest,
            new CompanionDeviceManager.Callback() {
              @Override
              public void onDeviceFound(IntentSender chooserLauncher) {
                Log.v("onDeviceFound","1");
                try {
                  activity.startIntentSenderForResult(chooserLauncher,
                          SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                  Log.v("onDeviceFound",e.toString());
                  // failed to send the intent
                }
              }

              @Override
              public void onFailure(CharSequence error) {
                // handle failure to find the companion device
                Log.v("onFailure",error.toString());
              }
            }, null);
  }

  /**
   * @param result result
   */
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  private void getBondedDevices(Result result) {

    List<Map<String, Object>> list = new ArrayList<>();

    for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
      /*int majDeviceCl = device.getBluetoothClass().getMajorDeviceClass(),
              deviceCl = device.getBluetoothClass().getDeviceClass();
      if (majDeviceCl == BluetoothClass.Device.Major.IMAGING && (deviceCl == 1664 || deviceCl == BluetoothClass.Device.Major.IMAGING)) {
        Map<String, Object> ret = new HashMap<>();
        ret.put("address", device.getAddress());
        ret.put("name", device.getName());
        ret.put("type", device.getType());
        list.add(ret);
      }*/
      //if(device.getName().contains("Printer")){
        Map<String, Object> ret = new HashMap<>();
        ret.put("address", device.getAddress());
        ret.put("name", device.getName());
        ret.put("type", device.getType());
        list.add(ret);
      //}
      /*if(device.getUuids()[0].getUuid()== UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")){

      }*/
    }

    result.success(list);
  }

  private String exceptionToString(Exception ex) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    ex.printStackTrace(pw);
    return sw.toString();
  }

  /**
   * @param result  result
   * @param address address
   */
  private void connect(Result result, String address) {

    if (THREAD != null) {
      result.error("connect_error", "already connected", null);
      return;
    }
    AsyncTask.execute(() -> {
      try {
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

        if (device == null) {
          result.error("connect_error", "device not found", null);
          return;
        }

        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(MY_UUID);

        if (socket == null) {
          result.error("connect_error", "socket connection not established", null);
          return;
        }

        // Cancel bt discovery, even though we didn't start it
        mBluetoothAdapter.cancelDiscovery();

        try {
          socket.connect();
          THREAD = new ConnectedThread(socket);
          THREAD.start();
          result.success(true);
        } catch (Exception ex) {
          Log.e(TAG, ex.getMessage(), ex);
          result.error("connect_error", ex.getMessage(), exceptionToString(ex));
        }
      } catch (Exception ex) {
        Log.e(TAG, ex.getMessage(), ex);
        result.error("connect_error", ex.getMessage(), exceptionToString(ex));
      }
    });
  }

  private void tcpConnect(Result result, String address, int port) {

    if (THREAD != null) {
      result.error("connect_error", "already connected", null);
      return;
    }
    AsyncTask.execute(() -> {
        try {
          Socket socket = new Socket();
          socket.connect(new InetSocketAddress(InetAddress.getByName(address), port), 30);

          THREAD = new ConnectedThread(socket);
          THREAD.start();
          result.success(true);
        } catch (Exception ex) {
          Log.e(TAG, ex.getMessage(), ex);
          result.error("connect_error", ex.getMessage(), exceptionToString(ex));
        }
    });
  }

  /**
   * @param result result
   */
  private void disconnect(Result result) {

    if (THREAD == null) {
      result.error("disconnection_error", "not connected", null);
      return;
    }
    AsyncTask.execute(() -> {
      try {
        THREAD.cancel();
        THREAD = null;
        result.success(true);
      } catch (Exception ex) {
        Log.e(TAG, ex.getMessage(), ex);
        result.error("disconnection_error", ex.getMessage(), exceptionToString(ex));
      }
    });
  }

  /**
   * @param result  result
   * @param message message
   */
  private void write(Result result, String message) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }

    try {
      THREAD.write(message.getBytes());
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void writeBytes(Result result, byte[] message) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }

    try {
      THREAD.write(message);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printCustom(Result result, String message, int size, int align, String charset) {
    //boolean isBold = false;
    // Print config "mode"
    byte[] cc = new byte[] { 0x1B, 0x21, 0x03 }; // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    byte[] bb = new byte[] { 0x1B, 0x21, 0x08 }; // 1- only bold text
    byte[] bb2 = new byte[] { 0x1B, 0x21, 0x20 }; // 2- bold with medium text
    byte[] bb3 = new byte[] { 0x1B, 0x21, 0x10 }; // 3- bold with large text
    byte[] bb4 = new byte[] { 0x1B, 0x21, 0x30 }; // 4- strong text
    byte[] bb5 = new byte[] { 0x1B, 0x21, 0x10 }; // 5 - bold of size 3
    byte[] bb6 = new byte[] { 0x1B, 0x21, 0x08 }; // 6 - bold of size 1

    byte[] TXT_BOLD_OFF    = {0x1b,0x45,0x00}; // Bold font OFF
    byte[] TXT_BOLD_ON     = {0x1b,0x45,0x01}; // Bold font ON
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }

    try {
      switch (size) {
        case 0:
          THREAD.write(cc);
          break;
        case 1:
          THREAD.write(bb);
          break;
        case 2:
          THREAD.write(bb2);
          break;
        case 3:
          THREAD.write(bb3);
          break;
        case 4:
          THREAD.write(bb4);
          break;
        case 5:
          THREAD.write(bb5);
          THREAD.write(TXT_BOLD_ON);
          break;
        case 6:
          THREAD.write(bb6);
          THREAD.write(TXT_BOLD_ON);
          break;
      }

      switch (align) {
        case 0:
          // left align
          THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
          break;
        case 1:
          // center align
          THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
          break;
        case 2:
          // right align
          THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
          break;
      }
      /*if(isBold){
        THREAD.write(TXT_BOLD_ON);
      }
      else{
        THREAD.write(TXT_BOLD_OFF);
      }*/
      if(charset != null) {
        THREAD.write(message.getBytes(charset));
      } else {
        THREAD.write(message.getBytes());
      }
      THREAD.write(PrinterCommands.FEED_LINE);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printLeftRight(Result result, String msg1, String msg2, int size ,String charset,String format,boolean isBold) {
    byte[] cc = new byte[] { 0x1B, 0x21, 0x03 }; // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    byte[] bb = new byte[] { 0x1B, 0x21, 0x08 }; // 1- only bold text
    byte[] bb2 = new byte[] { 0x1B, 0x21, 0x20 }; // 2- bold with medium text
    byte[] bb3 = new byte[] { 0x1B, 0x21, 0x10 }; // 3- bold with large text
    byte[] bb4 = new byte[] { 0x1B, 0x21, 0x30 }; // 4- strong text
    byte[] bb5 = new byte[] { 0x1B, 0x21, 0x10 }; // 5 - bold of size 3
    byte[] bb6 = new byte[] { 0x1B, 0x21, 0x08 }; // 6 - bold of size 1


    /*byte[] cc = new byte[] { 0x1B, 0x21, 0x03 }; // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    byte[] bb = new byte[] { 0x1B, 0x21, 0x08 }; // 1- only bold text
    byte[] bb2 = new byte[] { 0x1B, 0x21, 0x20 }; // 2- bold with medium text
    //byte[] bb3 = new byte[] { 0x1B, 0x21, 0x10 }; // 3- bold with large text
    byte[] bb3 = new byte[] { 0x1B, 0x21, 0x30 }; // 3- bold with large text
    byte[] bb4 = new byte[] { 0x1B, 0x21, 0x30 }; // 4- strong text
    byte[] cc1 = new byte[]{0x1B,0x21,0x00}; //5- small size text*/


    byte[] TXT_BOLD_OFF    = {0x1b,0x45,0x00}; // Bold font OFF
    byte[] TXT_BOLD_ON     = {0x1b,0x45,0x01}; // Bold font ON
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      switch (size) {
        case 0:
          THREAD.write(cc);
          break;
        case 1:
          THREAD.write(bb);
          break;
        case 2:
          THREAD.write(bb2);
          break;
        case 3:
          THREAD.write(bb3);
          break;
        case 4:
          THREAD.write(bb4);
          break;
        case 5:
          THREAD.write(bb5);
          isBold = true;
          break;
        case 6:
          THREAD.write(bb6);
          isBold = true;
          break;
      }
      if(isBold){
        THREAD.write(TXT_BOLD_ON);
      }
      else{
        THREAD.write(TXT_BOLD_OFF);
      }
      //THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
      String line = String.format("%-15s %15s %n", msg1, msg2);
      if(format != null) {
        line = String.format(format, msg1, msg2);
      }
      if(charset != null) {
        THREAD.write(line.getBytes(charset));
      } else {
        THREAD.write(line.getBytes());
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }

  }

  private void print3Column(Result result, String msg1, String msg2, String msg3, int size ,String charset, String format) {
    byte[] cc = new byte[] { 0x1B, 0x21, 0x03 }; // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    byte[] bb = new byte[] { 0x1B, 0x21, 0x08 }; // 1- only bold text
    byte[] bb2 = new byte[] { 0x1B, 0x21, 0x20 }; // 2- bold with medium text
    byte[] bb3 = new byte[] { 0x1B, 0x21, 0x10 }; // 3- bold with large text
    byte[] bb4 = new byte[] { 0x1B, 0x21, 0x30 }; // 4- strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      switch (size) {
        case 0:
          THREAD.write(cc);
          break;
        case 1:
          THREAD.write(bb);
          break;
        case 2:
          THREAD.write(bb2);
          break;
        case 3:
          THREAD.write(bb3);
          break;
        case 4:
          THREAD.write(bb4);
          break;
      }
      THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
      String line = String.format("%-10s %10s %10s %n", msg1, msg2  , msg3);
      if(format != null) {
        line = String.format(format, msg1, msg2, msg3);
      }
      if(charset != null) {
        THREAD.write(line.getBytes(charset));
      } else {
        THREAD.write(line.getBytes());
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }

  }

  private void print4Column(Result result, String msg1, String msg2,String msg3,String msg4, int size, String charset, String format) {
    byte[] cc = new byte[] { 0x1B, 0x21, 0x03 }; // 0- normal size text
    // byte[] cc1 = new byte[]{0x1B,0x21,0x00}; // 0- normal size text
    byte[] bb = new byte[] { 0x1B, 0x21, 0x08 }; // 1- only bold text
    byte[] bb2 = new byte[] { 0x1B, 0x21, 0x20 }; // 2- bold with medium text
    byte[] bb3 = new byte[] { 0x1B, 0x21, 0x10 }; // 3- bold with large text
    byte[] bb4 = new byte[] { 0x1B, 0x21, 0x30 }; // 4- strong text
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      switch (size) {
        case 0:
          THREAD.write(cc);
          break;
        case 1:
          THREAD.write(bb);
          break;
        case 2:
          THREAD.write(bb2);
          break;
        case 3:
          THREAD.write(bb3);
          break;
        case 4:
          THREAD.write(bb4);
          break;
      }
      THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
      String line = String.format("%-8s %7s %7s %7s %n", msg1, msg2,msg3,msg4);
      if(format != null) {
        line = String.format(format, msg1, msg2,msg3,msg4);
      }
      if(charset != null) {
        THREAD.write(line.getBytes(charset));
      } else {
        THREAD.write(line.getBytes());
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }

  }

  private void printNewLine(Result result, byte size) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      //THREAD.write(PrinterCommands.FEED_LINE);
      byte[] FEED_LINE = {size};
      THREAD.write(FEED_LINE);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void paperCut(Result result) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      THREAD.write(PrinterCommands.FEED_PAPER_AND_CUT);
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printImage(Result result, String pathImage) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      Bitmap bmp = BitmapFactory.decodeFile(pathImage);
      if (bmp != null) {
        byte[] command = Utils.decodeBitmap(bmp);
        THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
        THREAD.write(command);
      } else {
        Log.e("Print Photo error", "the file isn't exists");
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printImageBytes(Result result, byte[] bytes) {
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      if (bmp != null) {
        byte[] command = Utils.decodeBitmap(bmp);
        THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
        THREAD.write(command);
      } else {
        Log.e("Print Photo error", "the file isn't exists");
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private void printQRcode(Result result, String textToQR, int width, int height, int align) {
    MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
    if (THREAD == null) {
      result.error("write_error", "not connected", null);
      return;
    }
    try {
      switch (align) {
        case 0:
          // left align
          THREAD.write(PrinterCommands.ESC_ALIGN_LEFT);
          break;
        case 1:
          // center align
          THREAD.write(PrinterCommands.ESC_ALIGN_CENTER);
          break;
        case 2:
          // right align
          THREAD.write(PrinterCommands.ESC_ALIGN_RIGHT);
          break;
      }
      BitMatrix bitMatrix = multiFormatWriter.encode(textToQR, BarcodeFormat.QR_CODE, width, height);
      BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
      Bitmap bmp = barcodeEncoder.createBitmap(bitMatrix);

      if (bmp != null) {
        byte[] command = Utils.decodeBitmap(bmp);
        THREAD.write(command);
      } else {
        Log.e("Print Photo error", "the file isn't exists");
      }
      result.success(true);
    } catch (Exception ex) {
      Log.e(TAG, ex.getMessage(), ex);
      result.error("write_error", ex.getMessage(), exceptionToString(ex));
    }
  }

  private class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final Socket eSocket;
    private  boolean exit = false;

    ConnectedThread(BluetoothSocket socket) {
      mmSocket = socket;
      eSocket = null;
      exit = false;
      InputStream tmpIn = null;
      OutputStream tmpOut = null;

      try {
        tmpIn = socket.getInputStream();
        tmpOut = socket.getOutputStream();
      } catch (IOException e) {
        e.printStackTrace();
      }
      inputStream = tmpIn;
      outputStream = tmpOut;
    }
    ConnectedThread(Socket socket){
      eSocket = socket;
      mmSocket = null;
      exit = false;
      InputStream tmpIn = null;
      OutputStream tmpOut = null;

      try {
        tmpIn = eSocket.getInputStream();
        tmpOut = eSocket.getOutputStream();
      } catch (IOException e) {
        e.printStackTrace();
      }
      inputStream = tmpIn;
      outputStream = tmpOut;
    }

    public void run() {
      byte[] buffer = new byte[1024];
      int bytes;
      while (!exit) {
        try {
          bytes = inputStream.read(buffer);
          //readSink.success(new String(buffer, 0, bytes));
        } catch (NullPointerException e) {
          break;
        } catch (IOException e) {
          break;
        }
      }
    }

    public void write(byte[] bytes) {
      try {
        outputStream.write(bytes);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    public void cancel() {
      try {
        outputStream.flush();
        outputStream.close();

        inputStream.close();

        if(mmSocket!=null){
          mmSocket.close();
        }
        if(eSocket!=null){
          eSocket.close();
        }
        exit = true;
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private final EventChannel.StreamHandler stateStreamHandler = new EventChannel.StreamHandler() {

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        Log.d(TAG, action);

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
          //statusSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
          if(!mBluetoothAdapter.isEnabled()){
            THREAD = null;
            statusSink.success(0);
          }
        }
        else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
          statusSink.success(1);
        }
        else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
          THREAD = null;
          statusSink.success(0);
        }
        else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
          THREAD = null;
          final int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
          final int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
          if (state == BluetoothDevice.BOND_BONDED && prevState == BluetoothDevice.BOND_BONDING) {
            Log.d(TAG, "BONDED");
            statusSink.success(2); // new paired
          }
        }

      }
    };

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
      Log.d(TAG, "onListen");
      statusSink = eventSink;
      context.registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

      context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));

      context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));

      context.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    @Override
    public void onCancel(Object o) {
      Log.d(TAG, "onCancel");
      statusSink = null;
      context.unregisterReceiver(mReceiver);
    }
  };

  /*private final StreamHandler readResultsHandler = new StreamHandler() {
    @Override
    public void onListen(Object o, EventSink eventSink) {
      readSink = eventSink;
    }

    @Override
    public void onCancel(Object o) {
      readSink = null;
    }
  };*/
}

import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'dart:async';
import 'package:flutter_thermal_printer_plugin/flutter_thermal_printer_plugin.dart';
import 'package:flutter/services.dart';
import 'package:flutter_thermal_printer_plugin_example/testprint.dart';
import 'package:path_provider/path_provider.dart';

void main() => runApp(new MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => new _MyAppState();
}

class _MyAppState extends State<MyApp> {
  FlutterThermalPrinterPlugin bluetooth = FlutterThermalPrinterPlugin.instance;

  List<BluetoothDevice> _devices = [];
  BluetoothDevice? _device = null;
  bool _connected = false;
  late String pathImage;
  late TestPrint testPrint;

  @override
  void initState() {
    super.initState();
    //initPlatformState();
    initSavetoPath();
    testPrint = TestPrint();
  }

  initSavetoPath() async {
    //read and write
    //image max 300px X 300px
    final filename = 'yourlogo.png';
    var bytes = await rootBundle.load("assets/images/yourlogo.png");
    String dir = (await getApplicationDocumentsDirectory()).path;
    writeToFile(bytes, '$dir/$filename');
    setState(() {
      pathImage = '$dir/$filename';
    });
  }

  Future<bool> onBluetooth() async {
    bool on = false;
    try {
      on = await bluetooth.onBluetooth();
    } on PlatformException {
      on = false;
    }
    return on;
  }

  Future<void> initPlatformState() async {
    bool? isConnected = await bluetooth.isConnected;
    List<BluetoothDevice> devices = [];
    try {
      devices = await bluetooth.getBondedDevices();
    } on PlatformException {
      print("######PlatformException#####");
    }

    bluetooth.onStateChanged().listen((state) {
      print("######onStateChanged#####");
      switch (state) {
        case FlutterThermalPrinterPlugin.CONNECTED:
          setState(() {
            _connected = true;
          });
          break;
        case FlutterThermalPrinterPlugin.DISCONNECTED:
          setState(() {
            _connected = false;
          });
          break;
        case FlutterThermalPrinterPlugin.NEWPAIR:
          initPlatformState();
          break;
        default:
          print(state);
          break;
      }
    });

    if (!mounted) return;
    setState(() {
      _devices = devices;
    });

    if (isConnected == true) {
      setState(() {
        _connected = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: Text('Blue Thermal Printer'),
        ),
        body: Container(
          child: Padding(
            padding: const EdgeInsets.all(8.0),
            child: ListView(
              children: <Widget>[
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  mainAxisAlignment: MainAxisAlignment.start,
                  children: <Widget>[
                    SizedBox(
                      width: 10,
                    ),
                    Text(
                      'Device:',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    SizedBox(
                      width: 30,
                    ),
                    Expanded(
                      child: DropdownButton(
                        items: _getDeviceItems(),
                        onChanged: (dynamic value) => setState(() => _device = value),
                        value: _device,
                      ),
                    ),
                  ],
                ),
                SizedBox(
                  height: 10,
                ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: <Widget>[
                    ElevatedButton(
                      // style: ElevatedButton.styleFrom(primary: Colors.brown),
                      onPressed: () async{
                        final permissions = await bluetooth.checkPermission12();
                        if(permissions){
                          initPlatformState();
                        }
                      },
                      child: Text(
                        'Refresh',
                        style: TextStyle(color: Colors.white),
                      ),
                    ),
                    SizedBox(
                      width: 20,
                    ),
                    ElevatedButton(
                      // style: ElevatedButton.styleFrom(
                      //     primary: _connected ? Colors.red : Colors.green),
                      onPressed: _connected ? _disconnect : _connect,
                      child: Text(
                        _connected ? 'Disconnect' : 'Connect',
                        style: TextStyle(color: Colors.white),
                      ),
                    ),
                  ],
                ),
                SizedBox(
                  height: 10,
                ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: <Widget>[
                    ElevatedButton(
                      // style: ElevatedButton.styleFrom(primary: Colors.brown),
                      onPressed: () async {
                        final onLocation = await bluetooth.onLocation();
                        if(onLocation){
                          final permissions = await bluetooth.checkPermission12();
                          if(permissions){
                            final on = await bluetooth.onBluetooth();
                            if(on){
                              bluetooth.newPair();
                            }
                          }
                        }
                      },
                      child: Text(
                        'Pair New',
                        style: TextStyle(color: Colors.white),
                      ),
                    ),
                  ],
                ),
                Padding(
                  padding:
                  const EdgeInsets.only(left: 10.0, right: 10.0, top: 50),
                  child: ElevatedButton(
                    // style: ElevatedButton.styleFrom(primary: Colors.brown),
                    onPressed: () {
                      testPrint.sample(pathImage);
                    },
                    child: Text('PRINT TEST',
                        style: TextStyle(color: Colors.white)),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  List<DropdownMenuItem<BluetoothDevice>> _getDeviceItems() {
    List<DropdownMenuItem<BluetoothDevice>> items = [];
    if (_devices.isEmpty) {
      items.add(DropdownMenuItem(
        child: Text('NONE'),
      ));
    } else {
      _devices.forEach((device) {
        items.add(DropdownMenuItem(
          child: Text(device.name!),
          value: device,
        ));
      });
    }
    return items;
  }

  void _connect() async{
    final on = await bluetooth.onBluetooth();
    if(on){
      if (_device == null) {
        show('No device selected.');
      } else {
        bluetooth.isConnected.then((isConnected) {
          if (isConnected==false) {
            bluetooth.connect(_device!).catchError((error) {
              setState(() => _connected = false);
            });
            setState(() => _connected = true);
          }
        });
      }
    }
  }

  void _disconnect() {
    bluetooth.disconnect().catchError((error) {
      setState(() => _connected = false);
    });
    setState(() => _connected = false);
  }

//write to app path
  Future<void> writeToFile(ByteData data, String path) {
    final buffer = data.buffer;
    return new File(path).writeAsBytes(
        buffer.asUint8List(data.offsetInBytes, data.lengthInBytes));
  }

  Future show(
      String message, {
        Duration duration: const Duration(seconds: 3),
      }) async {
    await new Future.delayed(new Duration(milliseconds: 100));
    ScaffoldMessenger.of(context).showSnackBar(
      new SnackBar(
        content: new Text(
          message,
          style: new TextStyle(
            color: Colors.white,
          ),
        ),
        duration: duration,
      ),
    );
  }
}

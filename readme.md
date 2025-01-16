
# Sensors Module

This guide demonstrates how to initialize and use the sensors available in this module.




### List of modules

- [Accelerometer Sensor](#accelerometer-sensor)
- [Gyroscope Sensor](#gyroscope-sensor)
- [Light Sensor](#light-sensor)
- [Magnetometer Sensor](#magnetometer-sensor)
- [Proximity Sensor](#proximity-sensor)
- [Vibration Sensor](#vibration-sensor)

### Tests
- [Screen Test](#screen-test)
- [MultiTouch Test](#multiTouch-test)
- [Broken Pixels Test](#broken-pixels-test)

# Accelerometer Sensor


### Initialization

Before using the sensor, initialize it in the `Activity` section. Make sure the sensor is available before initializing

```kotlin
if (AccelerometerSensor.isSensorAvailable()) {
    AccelerometerSensor.initializeSensor(this@MainActivity)
} else {
    // Handle the absence of the sensor (e.g., show a message to the user)
    Toast.makeText(this, "Accelerometer sensor is not available", Toast.LENGTH_SHORT).show()
}
```

### Setting the Calibration Listener

To receive updates about the calibration process, set the `AccelerometerCalibrationListener`:

```kotlin
AccelerometerSensor.setCalibrationListener(object : AccelerometerSensor.AccelerometerCalibrationListener {
    override fun onCalibrationProgressUpdated(progress: Int) {
        //Calibration progress change
    }

    override fun onCalibrationCompleted() {
        //Calibration finished
    }

    override fun onCalibrationStarted() {
        //Calibration started
    }

    override fun onCalibrationStopped() {
       //Calibration stopped
    }

    override fun onCalibrationTimeout() {
       //Calibration timeout
    }
})
```

### Starting and Stopping Calibration

It's recommended to start the calibration process when the activity resumes and stop it when the activity pauses:

```kotlin
override fun onResume() {
    super.onResume()
    AccelerometerSensor.startCalibration()
}

override fun onPause() {
    super.onPause()
    AccelerometerSensor.stopCalibration()
}
```

# Gyroscope Sensor


### Initialization

Before using the sensor, initialize it in the `Activity` section. Make sure the sensor is available before initializing

```kotlin
if (GyroscopeSensor.isSensorAvailable()) {
    GyroscopeSensor.initializeSensor(this@MainActivity)
} else {
    Toast.makeText(this, "Gyroscope sensor is not available", Toast.LENGTH_SHORT).show()
}
```

### Setting the Calibration Listener

To receive updates about the calibration process, set the `GyroscopeCalibrationListener`:

```kotlin
GyroscopeSensor.setCalibrationListener(object : GyroscopeSensor.GyroscopeCalibrationListener {
    override fun onCalibrationProgressChanged(
        xProgress: Int,
        yProgress: Int,
        zProgress: Int
    ) {
        println("Gyroscope progress: ${xProgress + yProgress + zProgress}")
    }

    override fun onCalibrationFinished() {
        //Calibration finished
    }

    override fun onCalibrationStarted() {
        //Calibration started
    }

    override fun onCalibrationCanceled() {

    }

    override fun onCalibrationTimeout() {

    }

})
```

### Starting and Stopping Calibration

It's recommended to start the calibration process when the activity resumes and stop it when the activity pauses:

```kotlin
override fun onResume() {
    super.onResume()
    GyroscopeSensor.startCalibration()
}

override fun onPause() {
    super.onPause()
    GyroscopeSensor.stopCalibration()
}
```

# Light Sensor


### Initialization

Before using the sensor, initialize it in the `Activity` section. Make sure the sensor is available before initializing

```kotlin
if (LightSensor.isSensorAvailable()) {
    LightSensor.initializeSensor(this@MainActivity)
} else {
    Toast.makeText(this, "Light sensor is not available", Toast.LENGTH_SHORT).show()
}
```

### Setting the Calibration Listener

To receive updates about the calibration process, set the `LightSensorCalibrationListener`:

```kotlin
LightSensor.setCalibrationListener(object : LightSensor.LightSensorCalibrationListener {
    override fun onLightChanged(light: Int) {

    }

    override fun onCalibrationProgressChanged(progress: Int) {
        //Calibration progress changed
    }

    override fun onCalibrationFinished() {
        //Calibration finished
    }

    override fun onCalibrationStarted() {
        //Calibration started
    }

    override fun onCalibrationCanceled() {

    }

})
```

### Starting and Stopping Calibration

It's recommended to start the calibration process when the activity resumes and stop it when the activity pauses:

```kotlin
override fun onResume() {
    super.onResume()
    LightSensor.startCalibration()
}

override fun onPause() {
    super.onPause()
    LightSensor.stopCalibration()
}
```


# Magnetometer Sensor

### Initialization

Before using the sensor, initialize it in the `Activity` section. Make sure the sensor is available before initializing

```kotlin
if (MagnetometerSensor.isSensorAvailable()) {
    MagnetometerSensor.initializeSensor(this@MainActivity)
} else {
    Toast.makeText(this, "Magnetometer sensor is not available", Toast.LENGTH_SHORT).show()
}
```

### Setting the Calibration Listener

To receive updates about the calibration process, set the `MagnetometerCalibrationListener`:

```kotlin
MagnetometerSensor.initializeSensor(this@MainActivity)
        MagnetometerSensor.setCalibrationListener(object : MagnetometerSensor.MagnetometerCalibrationListener {
    override fun onRotationUpdate(rotation: Int) {
        //Rotation value
    }

    override fun onProgressUpdate(progress: Int) {
        //Rotation progress
    }

    override fun onCompletion() {
        //Calibration finished
    }

    override fun onStart() {
        //Calibration started
    }

    override fun onCancel() {

    }

})
```

### Starting and Stopping Calibration

It's recommended to start the calibration process when the activity resumes and stop it when the activity pauses:

```kotlin
override fun onResume() {
    super.onResume()
    MagnetometerSensor.startCalibration()
}

override fun onPause() {
    super.onPause()
    MagnetometerSensor.stopCalibration()
}
```

# Proximity Sensor

### Initialization

Before using the sensor, initialize it in the `Activity` section. Make sure the sensor is available before initializing

```kotlin
if (ProximitySensor.isSensorAvailable()) {
    ProximitySensor.initializeSensor(this@MainActivity)
} else {
    Toast.makeText(this, "Proximity sensor is not available", Toast.LENGTH_SHORT).show()
}
```

### Setting the Calibration Listener

To receive updates about the calibration process, set the `ProximitySensor.CalibrationCallback`:

```kotlin
ProximitySensor.initializeSensor(this@MainActivity)
        ProximitySensor.setCallback(object : ProximitySensor.CalibrationCallback {
    override fun onCalibrationStarted() {
        //Proximity calibration started
    }

    override fun onProgressUpdated(progress: Int) {
        //Proximity calibration progress
        println("Progress: ${progress}")
    }

    override fun onCalibrationCompleted() {
        //Proximity calibration finished
    }

    override fun onCalibrationStopped() {

    }

})
```

### Starting and Stopping Calibration

It's recommended to start the calibration process when the activity resumes and stop it when the activity pauses:

```kotlin
override fun onResume() {
    super.onResume()
    ProximitySensor.startCalibration()
}

override fun onPause() {
    super.onPause()
    ProximitySensor.stopCalibration()
}
```

# Step Detector Sensor

### Permission

Include the following line in your app's `AndroidManifest.xml` file:

```xml
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

### Initialization

Before `startCalibration` sensor make sure it is available

```kotlin
private val SESSION_ID = "step_session" //create session id for sensor

//Create session
StepDetectorSensor.createSession(
    sessionId = SESSION_ID,
    context = this@MainActivity,
    listener = object : StepDetectorSensor.StepDetectorCalibrationListener {
        override fun onStepCountChanged(count: Int) {
            println("Step count: ${count}")
        }

        override fun onCalibrationProgressChanged(progress: Int) {
            println("Progress: ${progress}")
        }

        override fun onCalibrationFinished() {
            //Calibration finished
        }

        override fun onCalibrationStarted() {
            //Calibration started
        }

        override fun onCalibrationCanceled() {

        }

        override fun onCalibrationTimeout() {

        }
    }
)
//Start calibration
StepDetectorSensor.getSession(SESSION_ID)?.let { session ->
    if (session.isSensorAvailable()) {
        session.updatePermissionIfNeed(this)
        session.startCalibration()
    } else {
        Toast.makeText(this, "Sensor not available!", Toast.LENGTH_SHORT).show()
    }
}
```

Method `session.updatePermissionIfNeed(this)` checks if the `ACTIVITY_RECOGNITION` permission is granted. If the permission is not granted, it automatically requests the permission from the user.

### Setting the Calibration Listener

To receive updates about the calibration process, set the `ProximitySensor.CalibrationCallback`:

```kotlin
ProximitySensor.initializeSensor(this@MainActivity)
        ProximitySensor.setCallback(object : ProximitySensor.CalibrationCallback {
    override fun onCalibrationStarted() {
        //Proximity calibration started
    }

    override fun onProgressUpdated(progress: Int) {
        //Proximity calibration progress
        println("Progress: ${progress}")
    }

    override fun onCalibrationCompleted() {
        //Proximity calibration finished
    }

    override fun onCalibrationStopped() {

    }

})
```

### Starting and Stopping Calibration

It's recommended to start the calibration process when the activity resumes and stop it when the activity pauses:

```kotlin
override fun onResume() {
    super.onResume()
    StepDetectorSensor.getSession(SESSION_ID)?.let { session ->
        if (session.isSensorAvailable()) {
            session.updatePermissionIfNeed(this)
            session.startCalibration()
        } else {
            Toast.makeText(this, "Sensor not available!", Toast.LENGTH_SHORT).show()
        }
    }
}

override fun onPause() {
    super.onPause()
    StepDetectorCalibration.removeSession(SESSION_ID)
}
```




# Vibration Sensor

This guide explains how to integrate and configure the **VibrationSensor** component for detecting vibrations, handling calibration events, and adjusting thresholds dynamically.


## 1. Initialize Vibration Sensor

### Permission

Include the following line in your app's `AndroidManifest.xml` file:

```xml
<uses-permission android:name="android.permission.VIBRATE" />
```

Before using the sensor, initialize it in the Activity section. Make sure the sensor is available before initializing

### Code Example:

```kotlin
if (VibrationSensor.isVibrationAvailable()) {
    VibrationSensor.initializeSensor(this@MainActivity)
} else {
    Toast.makeText(this, "Vibration sensor is not available", Toast.LENGTH_SHORT).show()
}
```

This step sets up the vibration sensor with the required context.

---

## 2. Add a Vibration Listener

Use the `addVibrationListener` method to monitor vibration events and handle calibration.

### Code Example:

```kotlin
// Add a listener to handle vibration events
VibrationSensor.addVibrationListener(object : VibrationSensor.VibrationCalibrationListener {
    override fun onVibrationDetected(speed: Float) {
        // Handle vibration detection
        Log.d("VibrationCalibration", "Vibration detected with speed: $speed")
    }

    override fun onCalibrationStarted() {
        // Handle calibration start
        Toast.makeText(this@MainActivity, "Calibration started", Toast.LENGTH_SHORT).show()
    }

    override fun onCalibrationCanceled() {
        // Handle calibration cancellation
        Toast.makeText(this@MainActivity, "Calibration canceled", Toast.LENGTH_SHORT).show()
    }
})
```

### Listener Methods:

- `onVibrationDetected(speed: Float)`: Called when a vibration is detected. The `speed` parameter indicates the vibration intensity.

- `onCalibrationStarted()`: Called when the calibration process starts.

- `onCalibrationCanceled()`: Called when the calibration process is canceled.

---

## 3. Start and Cancel Calibration

You can use buttons or other UI components to start and cancel the calibration process.

### Code Example:

```kotlin
// Start calibration
viewBinding.start.setOnClickListener {
    VibrationSensor.startCalibration()
}

// Cancel calibration
viewBinding.end.setOnClickListener {
    VibrationSensor.cancelCalibration()
}
```

---

## 4. Adjust Threshold Speed

The **VibrationSensor** allows dynamic adjustment of the vibration threshold using a slider or similar UI element.

### Code Example:

```kotlin
// Configure the vibration threshold using a slider
viewBinding.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        // Set the threshold speed
        VibrationSensor.setThresholdSpeed(progress.toFloat())
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        // Optional: Handle the start of slider interaction
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        // Optional: Handle the end of slider interaction
    }
})
```

---

## 5. Summary

By following these steps, you can integrate **VibrationSensor** into your app to detect and handle vibrations, calibrate thresholds, and dynamically adjust settings based on user input or application needs.

### Key Methods:

- `initializeSensor(context: Context)`: Initializes the vibration sensor.
- `addVibrationListener(listener: VibrationCalibrationListener)`: Adds a listener for vibration events.
- `startCalibration()`: Starts the calibration process.
- `cancelCalibration()`: Cancels the calibration process.
- `setThresholdSpeed(speed: Float)`: Sets the vibration detection threshold.


# Screen Test

This guide will help you integrate and configure the **ScreenTestView** component for drawing on the screen, tracking drawing progress, and handling drawing completion.

## 1. Add ScreenTestView to Your Layout

To use the **ScreenTestView**, include it in your XML layout file (`activity_main.xml` or your desired layout file):

```xml
<com.sensors.view_test.ScreenTestView
    android:id="@+id/screenView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"/>
```

This creates a `ScreenTestView` that will occupy the full screen (`match_parent` for both width and height).

## 2. Set Up ScreenTestView in Activity

In your activity (`MainActivity` or similar), retrieve the view using `findViewById` and configure it by setting a line color, width, and a drawing listener.

### Code Example:

```kotlin
// Get reference to ScreenTestView
val screenTest = findViewById<ScreenTestView>(R.id.screenView)

// Set line color for drawing
screenTest.setLineColor(Color.BLACK)

// Set line width for drawing
screenTest.setLineWidth(100f)

// Set up a listener to track drawing progress and completion
screenTest.setDrawingListener(object : ScreenTestView.DrawingListener {
    override fun onDrawingProgressChanged(progress: Int) {
        // Handle drawing progress
        println("Progress: $progress")
    }

    override fun onDrawingCompleted() {
        // Show a message when drawing is completed
        Toast.makeText(this@MainActivity, "Finished!", Toast.LENGTH_SHORT).show()
    }
})
```

### Methods:

- `setLineColor(color: Int)`: Sets the color of the line being drawn. You can pass any color using the `Color` class (e.g., `Color.BLACK`).
  
- `setLineWidth(width: Float)`: Sets the width of the line being drawn. Pass a float value to adjust the thickness of the line (e.g., `100f`).

- `setDrawingListener(listener: DrawingListener)`: Sets a listener to track the drawing progress and completion.
  
### DrawingListener Methods:
  
- `onDrawingProgressChanged(progress: Int)`: Called whenever the drawing progress changes. You can use this to update UI elements or log the progress.
  
- `onDrawingCompleted()`: Called when the drawing is completed. You can display a message, such as a toast, to indicate the drawing has finished.

## 3. Example Usage

Once the **ScreenTestView** is set up, it will allow users to draw on the screen. The drawing progress and completion events will be logged, and upon completion, a `Toast` message will appear notifying the user that drawing is finished.

## 4. Summary

By following these steps, you can easily integrate **ScreenTestView** into your app to enable users to draw on the screen, track their progress, and receive feedback upon completion.



# MultiTouch Test

This guide explains how to integrate and configure the **MultiTouchView** component for detecting multi-touch events, tracking touch count, and customizing touch behavior.

## 1. Add MultiTouchView to Your Layout

To use the **MultiTouchView**, include it in your XML layout file (`activity_main.xml` or your desired layout file):

```xml
<com.sensors.view_test.MultiTouchView
    android:id="@+id/multiTouchView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"/>
```

This creates a `MultiTouchView` that will occupy the full screen (`match_parent` for both width and height).

## 2. Set Up MultiTouchView in Activity

In your activity (`MainActivity` or similar), retrieve the view using `findViewById` and configure it by setting a touch count listener and touch color.

### Code Example:

```kotlin
// Get reference to MultiTouchView
val multiTouchView = findViewById<MultiTouchView>(R.id.multiTouchView)

// Set up a listener to track the touch count
multiTouchView.setListener(object : MultiTouchView.DrawingListener {
    override fun onTouchCountChanged(touchCount: Int) {
        // Handle the number of touches on the screen
        println("Touches: $touchCount")
    }
})

// Set the touch color for drawing or touch interaction
multiTouchView.setTouchColor(Color.GREEN)
```

### Methods:

- `setListener(listener: DrawingListener)`: Sets a listener to track the number of touches on the screen.
  
- `setTouchColor(color: Int)`: Sets the color to be used for touch interactions. You can pass any color using the `Color` class (e.g., `Color.GREEN`).

### DrawingListener Methods:
  
- `onTouchCountChanged(touchCount: Int)`: Called whenever the number of active touches on the screen changes. This method will provide the updated touch count.

## 3. Example Usage

Once the **MultiTouchView** is set up, it will detect touch events and update the touch count. You can customize the touch color and listen for changes in the number of touches.

---

## 4. Summary

By following these steps, you can easily integrate **MultiTouchView** into your app to detect multiple touches, track touch count, and customize the touch interaction behavior.

# Broken Pixels Test

This guide explains how to integrate and configure the **TestBrokenPixelsView** component for testing display pixel issues, tracking test progress, and handling test results.

## 1. Add TestBrokenPixelsView to Your Layout

To use the **TestBrokenPixelsView**, include it in your XML layout file (`activity_main.xml` or your desired layout file):

```xml
<com.sensors.view_test.TestBrokenPixelsView
    android:id="@+id/testPixelView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"/>
```

This creates a `TestBrokenPixelsView` that will occupy the full screen (`match_parent` for both width and height).

## 2. Set Up TestBrokenPixelsView in Activity

In your activity (`MainActivity` or similar), retrieve the view using `findViewById` and configure it by setting a test listener and starting/stopping the test.

### Code Example:

```kotlin
// Get reference to TestBrokenPixelsView
val testColorView = findViewById<TestBrokenPixelsView>(R.id.testPixelView)

// Set up a listener to handle test events
testColorView.setTestColorViewListener(object : TestBrokenPixelsView.TestColorViewListener {
    override fun onTestStarted() {
        // Handle when the test starts
        Toast.makeText(this@MainActivity, "Test started", Toast.LENGTH_SHORT).show()
    }

    override fun onTestFinished(success: Boolean) {
        // Handle when the test finishes
        val message = if (success) "Display is normal" else "Display has errors"
        Toast.makeText(this@MainActivity, "Test finished: $message", Toast.LENGTH_SHORT).show()
    }
})

// Start the test
testColorView.startTest()

// Stop the test (example: after some condition)
testColorView.stopTest(true) // true if display is normal, false if errors are found
```

### Methods:

- `setTestColorViewListener(listener: TestColorViewListener)`: Sets a listener to handle test start and finish events.
  
- `startTest()`: Starts the pixel test.

- `stopTest(success: Boolean)`: Stops the test and accepts a `success` flag indicating whether the test passed.

### TestColorViewListener Methods:

- `onTestStarted()`: Called when the test starts.

- `onTestFinished(success: Boolean)`: Called when the test ends. The `success` parameter indicates whether the display passed the test.

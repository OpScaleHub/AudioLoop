# AudioLoop Widget-First App: TODO List

This document outlines the steps to transform the AudioLoop application into a floating widget-first experience.

## Phase 1: Core Floating Widget Infrastructure

-   [ ] **Create `FloatingWidgetService.kt`:**
    -   [ ] Inherit from `android.app.Service`.
    -   [ ] Implement `onCreate()`, `onStartCommand()`, `onDestroy()`, `onBind()` (return null).
    -   [ ] Declare the service in `AndroidManifest.xml` with `FOREGROUND_SERVICE` permission.
-   [ ] **Implement Foreground Service Notification:**
    -   [ ] In `FloatingWidgetService.onCreate()`, call `startForeground(NOTIFICATION_ID, createNotification())`.
    -   [ ] Create a `createNotification()` method that builds and returns a `Notification` (include a channel for Android O+).
    -   [ ] Add a placeholder small icon for the notification.
-   [ ] **Design `floating_widget_layout.xml`:**
    -   [ ] Create a basic layout (e.g., a `CardView` or `LinearLayout` with a `TextView` saying "AudioLoop Widget").
    -   [ ] Keep it simple for now; UI controls will be added later.
-   [ ] **Implement Basic Widget Display:**
    -   [ ] In `FloatingWidgetService.onCreate()`:
        -   [ ] Get `WindowManager` system service.
        -   [ ] Inflate `floating_widget_layout.xml`.
        -   [ ] Create `WindowManager.LayoutParams` (use `TYPE_APPLICATION_OVERLAY` for O+, `TYPE_PHONE` otherwise, `FLAG_NOT_FOCUSABLE`).
        -   [ ] Set initial gravity and position for the widget.
        -   [ ] Call `windowManager.addView()` to display the widget.
    -   [ ] In `FloatingWidgetService.onDestroy()`:
        -   [ ] Call `windowManager.removeView()` if the widget view is not null.
-   [ ] **Create a Minimal Launcher `Activity` (e.g., `MainActivity.kt` or `SetupActivity.kt`):**
    -   [ ] This activity will be the main entry point from the app icon for now.
    -   [ ] Add a button: "Start Floating Widget".
    -   [ ] Add a button: "Stop Floating Widget" (optional, for testing).
-   [ ] **Implement "Draw Over Other Apps" Permission Handling:**
    -   [ ] In the launcher `Activity`, check for `Settings.canDrawOverlays()`.
    -   [ ] If permission is not granted, create an `Intent` with `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` and start it (e.g., `startActivityForResult`).
    -   [ ] Handle the result in `onActivityResult()` (or use the newer Activity Result APIs).
    -   [ ] Only allow starting the service if permission is granted.
-   [ ] **Start/Stop the Service from Launcher Activity:**
    -   [ ] When "Start" button is clicked (and permission granted):
        -   [ ] Create an `Intent` for `FloatingWidgetService`.
        -   [ ] Call `ContextCompat.startForegroundService(this, serviceIntent)`.
    -   [ ] When "Stop" button is clicked:
        -   [ ] Create an `Intent` for `FloatingWidgetService`.
        -   [ ] Call `stopService(serviceIntent)`.
-   [ ] **Test Basic Widget Visibility:**
    -   [ ] Run the app.
    -   [ ] Grant "Draw over other apps" permission.
    -   [ ] Click "Start Floating Widget".
    -   [ ] Verify the simple widget appears on screen and the foreground service notification is present.
    -   [ ] Verify the widget can be removed by stopping the service or from the app.

## Phase 2: Widget Interactivity and UI Controls

-   [ ] **Implement Widget Dragging:**
    -   [ ] In `FloatingWidgetService`, add an `OnTouchListener` to the root view of `floatingWidgetView`.
    -   [ ] In the listener:
        -   [ ] On `MotionEvent.ACTION_DOWN`, store initial touch coordinates and widget parameters.
        -   [ ] On `MotionEvent.ACTION_MOVE`, calculate the new widget position and call `windowManager.updateViewLayout()`.
-   [ ] **Add Basic UI Controls to `floating_widget_layout.xml`:**
    -   [ ] Button: "Record App Audio"
    -   [ ] Button: "Record Microphone"
    -   [ ] Button: "Play Mixed Audio" (for the workaround)
    -   [ ] Button: "Close Widget"
-   [ ] **Connect "Close Widget" Button:**
    -   [ ] In `FloatingWidgetService`, find the close button in `floatingWidgetView`.
    -   [ ] Set an `OnClickListener` that calls `stopSelf()` on the service.

## Phase 3: Integrating Audio Logic into the Service

-   [ ] **Refactor Microphone Recording Logic:**
    -   [ ] Move your existing `AudioRecord` setup, start, stop, and data reading logic into `FloatingWidgetService` or a helper class managed by it.
    -   [ ] Implement `RECORD_AUDIO` permission request (likely in the launcher `Activity` or when the "Record Microphone" button is first tapped).
    -   [ ] Connect the "Record Microphone" button on the widget to start/stop microphone recording in the service. Update button text/state accordingly.
-   [ ] **Refactor App Audio Capture Logic (`MediaProjection`):**
    -   [ ] The `MediaProjectionManager` and the request for screen capture permission (`createScreenCaptureIntent()`) will need to be initiated from an `Activity` context. The launcher `Activity` is a good place.
    -   [ ] Pass the `MediaProjection` result (`data` and `resultCode`) to the `FloatingWidgetService`. The service can then obtain the `MediaProjection` instance.
        -   *Consideration:* You might need to temporarily show the launcher Activity to get this permission if the service is already running. Or, ensure it's obtained before the service starts features requiring it.
    -   [ ] Move the `MediaProjection` setup (virtual display, audio playback capture configuration) and data handling into `FloatingWidgetService`.
    -   [ ] Connect the "Record App Audio" button on the widget to start/stop app audio capture in the service.
-   [ ] **Implement Audio Mixing Logic in Service:**
    -   [ ] Ensure your audio mixing algorithms can be fed with data from the service's `AudioRecord` instance and the app audio capture path.
    -   [ ] The output of the mixer will be a new audio stream.
-   [ ] **Implement Local Playback (Workaround) in Service:**
    -   [ ] Set up an `AudioTrack` instance in `FloatingWidgetService`.
    -   [ ] Feed the mixed audio data to this `AudioTrack` for playback.
    -   [ ] Connect the "Play Mixed Audio" button on the widget to start/stop this playback.

## Phase 4: State Management, Polish, and Refinements

-   [ ] **Implement Widget State Management:**
    -   [ ] Decide how to save and restore the widget's state (e.g., is recording active, mic muted, volume levels).
    -   [ ] Use `SharedPreferences` for simple states or a more robust solution if needed.
    -   [ ] The service should load this state in `onCreate()` and save it when relevant changes occur.
-   [ ] **Add UI Feedback to Widget:**
    -   [ ] Change button text/icons to reflect current state (e.g., "Stop Recording" vs. "Start Recording").
    -   [ ] Consider visual indicators for audio levels (if part of your design).
-   [ ] **Implement Widget Minimization/Expansion (Optional but Recommended):**
    -   [ ] Add a button or gesture to toggle between a minimized and expanded view of the widget.
    -   [ ] This will involve changing the visibility of certain UI elements within `floating_widget_layout.xml` and potentially updating `WindowManager.LayoutParams` if the size changes.
-   [ ] **Refine Error Handling:**
    -   [ ] Add `try-catch` blocks around `WindowManager` operations.
    -   [ ] Handle cases where permissions are revoked while the service is running.
    -   [ ] Provide user feedback for errors (e.g., `Toast` messages).
-   [ ] **Thorough Testing:**
    -   [ ] Test on different Android versions.
    -   [ ] Test on different screen sizes/densities.
    -   [ ] Test audio quality.
    -   [ ] Test battery impact.
    -   [ ] Test interactions with other apps.
-   [ ] **Update `README.md`:**
    -   [ ] Add screenshots/GIFs of the widget in action.
    -   [ ] Detail the new architecture if necessary for contributors.
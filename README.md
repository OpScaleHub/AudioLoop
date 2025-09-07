# Android Audio Mixer: Project Specification

## 1. Project Overview

### 1.1 Project Title
AudioLoop: The Android Audio Mixer

### 1.2 Goal & Vision
The goal is to create a user-friendly, open-source Android application that enables users to **stream audio from one app** as if it were their microphone, mixing it with their live voice. The app is for collaboration platforms like Twitter Spaces and Discord, letting users easily share media playback during a live call or broadcast.

### 1.3 Target Audience
Android users who participate in live audio conversations, podcasts, or online collaboration sessions and want to share media content without a complex hardware setup.

---

## 2. Core Challenge & Technical Feasibility

The project's main challenge is Android's strong security and privacy model, which prevents a standard app from directly "hijacking" another app's microphone input.

-   **Audio Capture:** Android's **MediaProjection API** (Android 10+) lets an app capture audio playback from other applications. This requires user consent and the source app must allow audio capture.
-   **Audio Mixing:** There is **no public API** to inject an audio stream into another app's microphone input.
-   **Real-Time Behavior:** The app must handle a complex, real-time audio signal chain, including internal audio capture, microphone input, and playback to a third-party app.

Due to these constraints, a direct "virtual microphone" is not feasible without a highly privileged, system-level app (e.g., a rooted device). The proposed solution uses a less direct but viable method.

---

## 3. Proposed Solution: "The Loopback & Mixing" Method

Instead of a virtual microphone, the app will capture the desired audio, mix it with the user's live voice from the microphone, and then **play the combined audio back through the device's loudspeaker**. The collaboration app's microphone will then pick up this mixed audio, achieving the desired effect.

### 3.1 High-Level Technical Requirements

-   **Android API Level:** Target **Android 10 (API level 29)** or higher to use the **AudioPlaybackCapture API**.
-   **Permissions:** Request `RECORD_AUDIO` and `FOREGROUND_SERVICE` permissions.
-   **Audio Capture:** Use `MediaProjection` to capture the audio stream.
-   **Audio Processing:** Use **AudioRecord** to capture the microphone and **AudioTrack** to play back the mixed audio.
-   **Foreground Service:** The audio processing must run as a **Foreground Service** to prevent the system from terminating it.
-   **Dependency Management:** Use Gradle and a dependency injection framework like Dagger/Hilt.

---

## 4. UI/UX Guidelines

The app's user interface must be simple and intuitive, hiding the complexity of the underlying audio routing.

-   **Single-Screen Interface:** The main screen should have clear controls to start and stop the audio loopback.
-   **App Selection:** A button should trigger a system dialog for the user to select the app they want to capture audio from.
-   **Mixing Controls:** Provide an easy-to-use interface to control the volume levels of the "internal audio" and the "microphone audio."
-   **Status Indicator:** A clear, visible indicator should show whether the loopback service is active and a visual representation of the audio levels.
-   **Consent Prompt:** When a user first starts the loopback, the app must clearly explain why a system permission dialog will appear (e.g., "Your phone will ask you for permission to capture all on-screen audio. This is required for this app to work.").

---

## 5. Open-Source Project Guidelines

This project will be developed as an open-source initiative to ensure transparency and community collaboration.

-   **Licensing:** MIT License.
-   **Code Style:** Follow standard Android Kotlin/Java style guides.
-   **Documentation:** All major components, functions, and public APIs must be well-documented.
-   **Contribution Process:** Use standard Git flow with pull requests reviewed by maintainers.
-   **Issue Tracking:** Use a GitHub Issues page to track bugs and feature requests.

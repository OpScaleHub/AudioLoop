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


### Project Definition & Roadmap Extension: CI/CD Pipeline

To ensure a streamlined, automated, and professional development process, we'll implement a **CI/CD pipeline** using **GitHub Actions**. This will automate versioning, building, and publishing a release with each new commit to the main branch.

---

### 6. Continuous Integration & Deployment (CI/CD)

The CI/CD pipeline will be built using GitHub Actions and will automate the full lifecycle from code commit to a published, versioned APK. This will be triggered on every push to the `main` branch. 

#### 6.1 Versioning

An automated versioning system will be used to generate a unique **Semantic Version (SemVer)** for each release. This ensures that every build has a clear, machine-readable version number.

-   **semver-based on commits:** We'll use a GitHub Action that inspects the commit messages to determine the next version number.
    -   Commits with a `fix:` prefix will trigger a **patch** version increment (e.g., `1.0.0` -> `1.0.1`).
    -   Commits with a `feat:` prefix will trigger a **minor** version increment (e.g., `1.0.0` -> `1.1.0`).
    -   Commits with a `BREAKING CHANGE:` in the body will trigger a **major** version increment (e.g., `1.0.0` -> `2.0.0`).
    -   This is based on the **Conventional Commits** specification.

#### 6.2 Building & Signing

The pipeline will automatically build a production-ready APK after the version number is generated.

-   **Build command:** The pipeline will run the standard Gradle build command, specifically `./gradlew assembleRelease`, to create a release-ready APK.
-   **Signing:** The build will be signed with a production keystore. We will securely store the signing key and its password in **GitHub Secrets** to prevent exposure in the repository.

#### 6.3 Publishing & Releases

The final step is to publish the signed APK to a new GitHub Release page.

-   **Generate a Release:** A new release will be automatically created on the GitHub repository.
-   **Release Naming:** The release name and tag will be the newly generated SemVer version (e.g., `v1.2.3`).
-   **APK as an Asset:** The signed APK file will be attached as a downloadable asset to this release page, making it easy for users to find and install the latest stable version of the app.
-   **Release Notes:** The release notes will be automatically generated from the commit messages since the last release, providing a clear and transparent changelog.

---

### 7. Proposed GitHub Action Workflow

This is a high-level overview of the GitHub Actions workflow file (`.github/workflows/ci.yml`).

1.  **Trigger:** The workflow will be triggered on `push` events to the `main` branch.
2.  **Setup:** Checkout the code, set up the Java environment, and cache Gradle dependencies.
3.  **Version Generation:** Run the action to determine the new SemVer version based on recent commits. Store this version in an environment variable.
4.  **Build:** Run the Gradle `assembleRelease` task. The build will be configured to use the generated version number.
5.  **Sign:** Use a dedicated action to sign the built APK with the keystore credentials from GitHub Secrets.
6.  **Create Release:** Use a GitHub Action to create a new release on the repository.
7.  **Upload Artifacts:** Use a final action to upload the signed APK as a release asset, making it available for public download.
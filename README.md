# AudioLoop: A Floating Audio Mixer Widget for Android

AudioLoop is an open-source Android application that operates entirely as a **persistent, on-screen floating widget** for capturing, mixing, and sharing audio between apps. Imagine seamlessly piping a YouTube video's audio into a live conference call or a social audio room, all controlled from a discreet, movable widget on your screen. The entire application interface *is* the floating widget.

## The Vision

Our goal is to create a simple, intuitive, and movable floating widget that puts powerful audio mixing controls at your fingertips. This widget will allow you to:

1.  **Capture App Audio:** Stream the sound output from any application, like a podcast player or a video, directly from the widget.
2.  **Mix with Microphone:** Simultaneously record your voice from the device's microphone, controlled via the widget.
3.  **Share Everywhere:** Use this combined audio stream as your input in other applications, such as meeting apps (Zoom, Google Meet) or social audio platforms (Twitter Spaces), managed and initiated from the floating interface.

## How It Works & The Core Challenge

The user experience is centered around a **persistent floating widget** that stays on top of other apps. This widget is not just a controller; it *is* the main interface for AudioLoop. While this offers a streamlined user experience, Android's security model presents a significant challenge to our sharing goal.

### What's Achievable

*   **Floating UI as the Main App:** The entire application interface will be implemented as a floating widget, requiring the "Draw over other apps" permission. There will be no traditional full-screen app activity for core functions.
*   **App Audio Capture:** We can successfully capture the audio output from other applications, controlled through the widget.
*   **Microphone Input:** We can add functionality to record from the microphone at the same time, with controls directly on the widget.
*   **Real-time Mixing:** The captured app audio and microphone audio can be mixed together into a single, real-time stream within AudioLoop, managed by the widget's UI.

### The Problem: The "Virtual Microphone"

Directly feeding this mixed audio into another app (like Twitter Spaces) as a "virtual microphone" is not possible for a standard Android app due to OS security and sandboxing limitations. Apps like Zoom or Twitter Spaces are hard-wired to use the physical microphone and don't allow selecting a different audio source.

**The Workaround (Initiated from the Widget):**
The only viable method for a standard app to share audio with a meeting app is indirectly:
1.  AudioLoop, through its floating widget controls, plays the mixed audio (app sound + your voice) out loud through the phone's speaker.
2.  The other app (e.g., Twitter Spaces) listens with the physical microphone and picks up the sound from the speaker.

**Limitations of this approach:**
*   **Echo/Feedback:** Can occur if you aren't using headphones to listen to the meeting audio.
*   **Reduced Quality:** The sound is being played and re-recorded, which naturally degrades its quality.

## Project Status & Next Steps

This project is in active development, focusing on building a robust local audio mixing tool entirely contained within a polished floating user interface.

Our immediate next steps are:
1.  **Build the Core Floating Widget UI:** Implement the floating widget functionality, including the ability to move, resize (if applicable), and expand/collapse sections. This widget will house all primary controls.
2.  **Add Microphone Recording to Widget:** Integrate real-time audio capture from the device microphone, with controls accessible on the widget.
3.  **Implement Audio Mixing in Widget:** Create a mixer to combine the app and microphone audio streams in real-time, managed through the widget's interface.
4.  **Local Playback Controls on Widget:** Allow the user to hear the final mixed audio through their headphones or speakers, controlled directly from the widget, which is key for the workaround.
5.  **(Potentially) A Minimal Settings Activity:** While the core app is a widget, a separate, traditional Android activity might be needed for initial setup, permissions handling, or advanced settings not suitable for the widget UI. This should be minimized.

This will provide a solid foundation and a useful tool for local recording and mixing, as we continue to explore the best ways to share the audio with other apps while navigating platform constraints, all from a convenient floating interface.

## AudioLoop Floating Widget UI Description

*(This section seems well-aligned with the widget-first approach and likely doesn't need major changes, but ensure it consistently refers to the widget as the primary interface.)*

**Key Considerations for the Widget-First Approach:**

*   **State Management:** How will the widget's state be saved and restored?
*   **Lifecycle:** The widget will run as a service. Consider its lifecycle carefully.
*   **Permissions:** Requesting "Draw over other apps" and microphone permissions will be crucial and needs a smooth UX, potentially involving a one-time setup screen.
*   **Minimization/Expansion:** Think about how the widget can be minimized to take up less space and expanded to show full controls.
*   **Accessibility:** Ensure the floating widget is accessible.

*(The "Contributing" and "License" sections can remain as they are.)*

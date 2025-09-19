## Updated `README.md`


# AudioLoop: A Floating Audio Mixer for Android

AudioLoop is an open-source Android application that operates as a **floating, on-screen gadget** for capturing, mixing, and sharing audio between apps. Imagine seamlessly piping a YouTube video's audio into a live conference call or a social audio room, all from a discreet, movable widget on your screen.

## The Vision

Our goal is to create a simple, intuitive, and movable floating widget that puts powerful audio mixing controls at your fingertips. This gadget will allow you to:

1.  **Capture App Audio:** Stream the sound output from any application, like a podcast player or a video.
2.  **Mix with Microphone:** Simultaneously record your voice from the device's microphone.
3.  **Share Everywhere:** Use this combined audio stream as your input in other applications, such as meeting apps (Zoom, Google Meet) or social audio platforms (Twitter Spaces).

## How It Works & The Core Challenge

The user experience is now centered around a **persistent floating widget** that stays on top of other apps. While the user-facing design is simple, Android's security model presents a significant challenge to our sharing goal.

### What's Achievable

* **Floating UI:** The app is now a floating gadget, thanks to the "Draw over other apps" permission.
* **App Audio Capture:** We can successfully capture the audio output from other applications.
* **Microphone Input:** We can add functionality to record from the microphone at the same time.
* **Real-time Mixing:** The captured app audio and microphone audio can be mixed together into a single, real-time stream within AudioLoop.

### The Problem: The "Virtual Microphone"

Directly feeding this mixed audio into another app (like Twitter Spaces) as a "virtual microphone" is not possible for a standard Android app due to OS security and sandboxing limitations. Apps like Zoom or Twitter Spaces are hard-wired to use the physical microphone and don't allow selecting a different audio source.

**The Workaround:**
The only viable method for a standard app to share audio with a meeting app is indirectly:
1.  AudioLoop plays the mixed audio (app sound + your voice) out loud through the phone's speaker.
2.  The other app (e.g., Twitter Spaces) listens with the physical microphone and picks up the sound from the speaker.

**Limitations of this approach:**
* **Echo/Feedback:** Can occur if you aren't using headphones to listen to the meeting audio.
* **Reduced Quality:** The sound is being played and re-recorded, which naturally degrades its quality.

## Project Status & Next Steps

This project is in active development, focusing on building a robust local audio mixing tool with a polished floating user interface.

Our immediate next steps are:
1.  **Build the Floating UI:** Implement the floating widget functionality, including the ability to move and expand it.
2.  **Add Microphone Recording:** Integrate real-time audio capture from the device microphone.
3.  **Implement Audio Mixing:** Create a mixer to combine the app and microphone audio streams in real-time.
4.  **Local Playback:** Allow the user to hear the final mixed audio through their headphones or speakers, a key feature for the workaround.

This will provide a solid foundation and a useful tool for local recording and mixing, as we continue to explore the best ways to share the audio with other apps while navigating platform constraints.

## AudioLoop Floating Widget UI Description

**Overall Aesthetic:** Luxury, elegant, and modern with a focus on intuitive controls. Dark theme with rose gold accents and glowing elements for visual feedback. The widget maintains a soft, rounded rectangular shape.

**Core Structure:**

The widget is primarily a horizontally oriented, rounded rectangle with a dark, brushed metallic background. It features a prominent central circular element and two main input channels (Microphone and App Audio) on either side, along with a master output.

**Key Elements (from left to right, top to bottom):**

1.  **Outer Border:** A thin, polished rose gold metallic border frames the entire widget, adding a touch of luxury.

2.  **Background:** A dark, charcoal gray brushed metal texture provides a sophisticated base.

3.  **Top Input Channel (Left - Microphone):**
    * **Icon:** A subtle, light gray microphone icon (similar to a classic studio mic).
    * **Text:** "MIC" in a clean, sans-serif font below the icon.
    * **Gain Indicator:** "+3 dB" (or a dynamic value) in a smaller, lighter font, suggesting current gain.
    * **Volume Slider/Knob (Implicit):** While not explicitly shown, the design implies a control for this channel, perhaps a vertical fader or rotary knob.

4.  **Top Input Channel (Right - App Audio):**
    * **Icon:** A subtle, light gray speaker or sound wave icon.
    * **Text:** "APP" in a clean, sans-serif font below the icon.
    * **Gain Indicator:** "+8 dB" (or a dynamic value) in a smaller, lighter font, suggesting current gain.
    * **Volume Slider/Knob (Implicit):** Similar to the mic channel, implies a control.

5.  **Central AudioLoop Logo & Waveform Display:**
    * **Circular Frame:** A prominent, glowing neon blue-to-cyan gradient circle forms the centerpiece. This circle acts as both a visual focus and potentially a real-time audio visualization.
    * **AudioLoop Text:** "AudioLoop" in a stylish, modern sans-serif font, slightly translucent white, is centered within the glowing circle.
    * **Real-time Waveform:** A dynamic, glowing orange and blue waveform is superimposed over the "AudioLoop" text and extends slightly beyond it, visually representing the mixed audio in real-time. The orange represents one channel (e.g., mic) and blue the other (e.g., app audio), or perhaps the combined mixed output.

6.  **Bottom Input Channel (Left - Microphone Toggle):**
    * **Icon:** A subtle, light purple microphone icon, slightly more stylized than the top one, indicating a toggle or mute function.
    * **Text:** "MIC" in a clean, sans-serif font below the icon.
    * **Gain Indicator:** "+3 dB" (or a dynamic value) in a smaller, lighter font.
    * **Mute/Active Indicator (Implicit):** The purple glow around this icon could indicate activity or an active state, changing color when muted.

7.  **Master Output Section (Center Bottom):**
    * **Label:** "MASTER" in a small, clean, sans-serif font, centered below the central glowing circle.
    * **Master Volume Slider:** A horizontal, flat, dark gray slider track with a small, round rose gold knob in the middle. This knob can be dragged left/right to adjust master volume.

8.  **Bottom Output Channel (Right - Headphones):**
    * **Icon:** A stylized light purple headphones icon.
    * **Text:** "OUTPUT" in a clean, sans-serif font below the icon.
    * **Indicator Line:** A thin, light purple horizontal line extends from the output section towards the right, visually connecting to the implied output.

**Floating Share Button:**

* **Location:** Discreetly placed, floating independently from the main widget, likely in the bottom right corner of the screen.
* **Shape:** A horizontal, elongated rounded rectangle with the same dark brushed metallic background.
* **Icon:** A white share icon (three connected circles).
* **Border:** A subtle rose gold outline, matching the main widget.
* **Functionality:** Designed to quickly initiate the sharing function.

**Interactive Elements/States (Implied):**

* **Glowing Feedback:** Icons and elements could glow (e.g., blue for app audio, purple for mic) to indicate active status, audio levels, or when touched.
* **Slider/Knob Interaction:** Sliders and knobs are draggable.
* **Toggle Functionality:** Icons like the bottom microphone icon could act as mute/unmute toggles, with visual feedback (e.g., changing color, dimming).

This detailed description should give your Android design team a strong foundation for recreating the elegant and functional UI.




## Contributing

This is an open-source project, and contributions are welcome! Whether it's code, design ideas, or documentation, feel free to open an issue or submit a pull request. Please follow standard Android Kotlin/Java style guides.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

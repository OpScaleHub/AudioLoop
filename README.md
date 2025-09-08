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

## Contributing

This is an open-source project, and contributions are welcome! Whether it's code, design ideas, or documentation, feel free to open an issue or submit a pull request. Please follow standard Android Kotlin/Java style guides.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

# AudioLoop: Mix and Share Audio on Android

AudioLoop is an open-source Android application designed to capture, mix, and share audio between apps. Imagine playing a YouTube video's audio into a live Twitter Space or a conference call, right from your phone. This project aims to make that possible through an intuitive floating widget.

## The Goal

Our vision is a simple, movable, on-screen widget that lets you:

1.  **Capture App Audio:** Stream the sound output from an app like YouTube or a music player.
2.  **Mix with Microphone:** Simultaneously capture your voice from the device's microphone.
3.  **Share Everywhere:** Use this combined audio stream as your input in other applications, such as meeting apps (Zoom, Google Meet) or social audio platforms (Twitter Spaces).

## How It Works & The Challenges

While the goal is simple, Android's security model presents a significant challenge.

### What's Achievable Now

*   **App Audio Capture:** We can successfully capture the audio output from other applications.
*   **Microphone Input:** We can add functionality to record from the microphone at the same time.
*   **Audio Mixing:** The captured app audio and microphone audio can be mixed together into a single stream within AudioLoop.

### The Core Challenge: Sharing the Mix

Directly feeding this mixed audio into another app (like Twitter Spaces) as a "virtual microphone" is not possible for a standard Android app due to OS security and sandboxing limitations. Apps like Zoom or Twitter Spaces are hard-wired to use the physical microphone and don't allow selecting a different audio source.

**The Workaround:**
The only way for a standard app to achieve a similar result is indirectly:
1.  AudioLoop plays the mixed audio (app sound + your voice) out loud through the phone's speaker.
2.  The other app (e.g., Twitter Spaces) listens with the physical microphone and picks up the sound from the speaker.

**Limitations of this approach:**
*   **Echo/Feedback:** Can occur if you aren't using headphones for the meeting audio.
*   **Reduced Quality:** The audio is being played and re-recorded, which degrades its quality.

## Project Status & Next Steps

This project is currently in active development. Given the platform constraints, we are focusing on building a powerful local audio mixing tool first.

Our immediate next steps are:
1.  **Add Microphone Recording:** Implement simultaneous recording from the device microphone.
2.  **Implement Audio Mixing:** Create a mixer to combine the app and microphone audio streams in real-time.
3.  **Playback the Mix:** Allow the user to hear the final mixed audio through their headphones or speakers.

This will provide a solid foundation and a useful tool for local recording and mixing, as we continue to explore the best ways to share the audio with other apps.

## Contributing

This is an open-source project, and contributions are welcome! Whether it's code, design ideas, or documentation, feel free to open an issue or submit a pull request. Please follow standard Android Kotlin/Java style guides.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

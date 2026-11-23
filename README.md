<div align="center">
  <img src="icon.png" width="128" height="128" alt="AuraHear Icon" style="border-radius: 28px;" />
  <h1>AuraHear • Bluetooth Hearing Aid</h1>
  <p>Real-Time Dual-Ear Balance &amp; Voice Amplifier for Android</p>
</div>

An open-source Android application that transforms your smartphone and Bluetooth headset / earbuds into a personalized, low-latency hearing aid. Features independent volume calibration for each ear, acoustic hearing safety protection, in-app OTA auto-updates, and background screen-off operation.

Available live at: **[https://rudhrahas-dev.github.io/aurahear/](https://rudhrahas-dev.github.io/aurahear/)**  
Direct Android APK Download: **[`aurahear.apk`](aurahear.apk)**  
Alternative Raw Download: **[Download `aurahear.apk` (Direct GitHub Link)](https://github.com/rudhrahas-dev/aurahear/raw/main/aurahear.apk)**

---

## ⚡ Key Features

* **🔄 In-App OTA Auto-Updater:**
  * Checks GitHub Releases automatically on launch and notifies you when an update is available.
  * One-tap download with live progress and automatic installation via Android `FileProvider`.
  * Manual "Check for Updates" trigger in the app footer.

* **🦻 Independent Ear Volume & Balance Calibration:**
  * **Per-Ear Sliders:** Calibrate Left and Right ears independently from **0% up to 300%** (`-60 dB` to `+9.5 dB`) to accommodate asymmetric hearing thresholds.
  * **Real-Time dB Readout:** Displays exact decibel gain (`0.0 dB`, `+3.5 dB`, etc.) alongside percentage.
  * **Link / Unlink Balance:** Switch between independent balance and synchronized stereo adjustments with one tap.
  * **Instant Mute:** One-tap mute for Left or Right ear individually.
  * **Master Boost:** Overall gain scaling (0.5x to 2.5x).

* **🛡️ Acoustic Shock Limiter (Hearing Protection):**
  * Soft-knee dynamic compression using hyperbolic tangent (`tanh`) curves.
  * Protects eardrums from sudden acoustic trauma (dropped cutlery, slammed doors, emergency sirens) by gently saturating signals above safe levels instead of harsh square-wave digital clipping.

* **🎙️ Background Pocket Mode (Foreground Service):**
  * Continuous low-latency audio processing even when the screen is locked or while multitasking in other apps.
  * Built to Android 14+ specifications (`FOREGROUND_SERVICE_TYPE_MICROPHONE` & `MEDIA_PLAYBACK`).
  * Persistent notification with quick-action **Stop** and **Mute** buttons and live L/R volume display.
  * Partial CPU `WakeLock` ensures zero audio dropouts while walking or carrying the phone in your pocket.

* **🎚️ Speech Clarity & Background Noise Reduction:**
  * **Speech Clarity (120 Hz Low-Cut High-Pass Filter):** Cuts handling rumblings, wind noise, and pocket friction while preserving speech fundamentals.
  * **Acoustic Noise Suppressor:** Integrates with Android's `NoiseSuppressor` to filter out background hums (AC, fans, engines).
  * **Acoustic Echo Canceler (AEC):** Prevents acoustic feedback loops between microphone and headset.

* **📊 Live Stereo VU Visualizer:**
  * Hardware-accelerated custom dual VU meter displaying real-time Left and Right decibel levels so you can visually verify audio capture.

* **🎧 Dual Microphone Input Routing:**
  * **Phone Microphone (Recommended):** Rest your phone on a table pointing toward conversation partners or TV for crystal-clear, high-fidelity capture.
  * **Bluetooth Headset Mic:** Allows fully wearable, hands-free operation.

---

## 📖 How to Install & Use

### 🤖 Android Users
1. **Download APK:** Tap **[`aurahear.apk`](aurahear.apk)** (or download from [https://rudhrahas-dev.github.io/aurahear/](https://rudhrahas-dev.github.io/aurahear/)).
2. **Install:** Open the downloaded `.apk` file and tap **Install** (allow *"Install unknown apps"* if prompted).
3. **Connect Headset:** Pair and connect your Bluetooth headset or earbuds.
4. **Calibrate:**
   * Open **AuraHear** and tap **Start Hearing Aid**.
   * Unlock **Independent** ear control.
   * Adjust the Left and Right sliders to match your personal hearing preference in each ear.
   * Lock the phone and place it on a table or in your pocket — the hearing aid remains active in the background!

---

## 🛠️ Build from Source

Requirements: Android SDK 34, JDK 17, Gradle 8.7+

```bash
git clone https://github.com/rudhrahas-dev/aurahear.git
cd aurahear
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```
The output APK will be placed in `app/build/outputs/apk/debug/app-debug.apk`.

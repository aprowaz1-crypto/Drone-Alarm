# Drone Alarm

Cloud-first Android starter project for detecting potential drone activity by combining RF, audio, and vibration signals.

## What is included
- Codespaces bootstrap with Android SDK setup: [.devcontainer/devcontainer.json](.devcontainer/devcontainer.json)
- Android app module with Kotlin + ViewBinding: [app/build.gradle.kts](app/build.gradle.kts)
- Foreground monitoring service:
	- [app/src/main/java/com/dronealarm/ua/service/MonitorForegroundService.kt](app/src/main/java/com/dronealarm/ua/service/MonitorForegroundService.kt)
	- [app/src/main/java/com/dronealarm/ua/service/SensorCollector.kt](app/src/main/java/com/dronealarm/ua/service/SensorCollector.kt)
- Detection and calibration engine:
	- [app/src/main/java/com/dronealarm/ua/engine/DetectionEngine.kt](app/src/main/java/com/dronealarm/ua/engine/DetectionEngine.kt)
	- [app/src/main/java/com/dronealarm/ua/engine/CalibrationManager.kt](app/src/main/java/com/dronealarm/ua/engine/CalibrationManager.kt)
	- [app/src/main/java/com/dronealarm/ua/engine/CapGenerator.kt](app/src/main/java/com/dronealarm/ua/engine/CapGenerator.kt)
- MQTT publisher: [app/src/main/java/com/dronealarm/ua/network/MqttPublisher.kt](app/src/main/java/com/dronealarm/ua/network/MqttPublisher.kt)
- CI build workflow: [.github/workflows/build.yml](.github/workflows/build.yml)
- Privacy draft: [PRIVACY.md](PRIVACY.md)

## Quick start in Codespaces
1. Open repository in GitHub Codespaces.
2. Wait until post-create setup finishes (Android SDK install).
3. Build debug APK:
	 - `chmod +x gradlew`
	 - `./gradlew assembleDebug`

## Current package structure
```text
app/src/main/java/com/dronealarm/ua/
├── BootCompletedReceiver.kt
├── DroneAlarmApp.kt
├── MainActivity.kt
├── engine/
│   ├── CalibrationManager.kt
│   ├── CapGenerator.kt
│   └── DetectionEngine.kt
├── network/
│   └── MqttPublisher.kt
└── service/
		├── MonitorForegroundService.kt
		└── SensorCollector.kt
```

## Notes
- Runtime permissions are required for audio and location.
- Foreground service type is declared as `dataSync` for Android 14+.
- Default MQTT broker is `tcp://test.mosquitto.org:1883` for smoke testing.
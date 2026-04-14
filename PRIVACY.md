# Privacy Policy (Draft)

Drone Alarm processes sensor data on-device to estimate possible drone activity.

## Data Sources
- Microphone power features (no raw audio upload by default)
- Motion sensor vibration metrics
- Radio signal variability (Wi-Fi/cellular)
- Approximate location for CAP message context

## Network Transmission
- If MQTT is enabled, CAP 1.2 alert XML is sent to the configured broker topic.
- By default, no continuous raw sensor stream is uploaded.

## User Control
- Monitoring starts only after user action.
- Users can disable MQTT publishing at any time.
- Users can revoke permissions in Android settings.

## Security Notes
- Use secure MQTT transport in production (`ssl://` + auth).
- Rotate broker credentials and audit access logs.

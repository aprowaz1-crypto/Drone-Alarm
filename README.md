# AEGIS-F6 (Map + Trajectory UI)

Android starter app that implements the UI and map logic requested:
- OpenStreetMap via osmdroid
- Smart source switch selector (Auto / Phone Solo / Multi-Array)
- User marker, target marker, and trajectory line
- Live telemetry panel (demo simulation)

## Build
```bash
chmod +x ./gradlew
./gradlew assembleDebug
```

## Notes
This version contains map/UX + simulation wiring. Sensor fusion and acoustic detection engine can be connected later as a separate module.

## Diagnostics Log
Application now emits structured Android logcat entries via tag `AegisDiagnostics`:
- `BUG:` runtime behavior that is inconsistent and should be treated as a defect.
- `TO_FIX:` technical debt or integration gaps that should be fixed next.
- `MISSING:` functionality that is intentionally not implemented yet.

## Current Gaps
- Real microphone DSP/ML detection pipeline is not connected; telemetry is simulation-driven.
- If runtime permissions are denied (`RECORD_AUDIO`, `BLUETOOTH_CONNECT`), detection quality degrades and corresponding `TO_FIX` logs are emitted.
- Bluetooth device counting is profile-based and may not represent true microphone array topology.

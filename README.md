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

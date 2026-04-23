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

---

## 🛡️ iPad Web Connection Setup

### 🎯 Concept
Connect an iPad to your POCO F6 detection system via a web browser. The iPad's camera becomes part of your monitoring array without requiring any app installation—just open a link in Safari.

### How It Works
1. Your POCO app generates a unique link (e.g., `https://example.com/connect?room=ABC123`)
2. Open that link in Safari on your iPad
3. Camera access is granted automatically via browser
4. System sends photo capture commands via WebSocket
5. iPad sends photos back in real-time
6. All processed at 1–3 second cycle time

### What You Need
- **POCO F6** running this application
- **iPad** with Safari browser (latest version)
- **Simple hosting** for the web server (Render, Railway, Vercel, or your own computer)
- **Internet connection** (or local WiFi for same-network operation)

### Setup Steps

#### Step 1: Set Up a Relay Server
Create a lightweight WebSocket server that:
- Accepts connections from both POCO and iPad
- Relays messages between them (no storage, no processing)
- Manages rooms by unique ID codes

#### Step 2: Deploy a Web Page
Host a single HTML file that:
- Requests camera permission
- Connects to the relay server via WebSocket
- Waits for photo commands
- Captures frames and sends them back

#### Step 3: Add to POCO App
Implement:
- A method to generate unique room codes (e.g., 6-character alphanumeric)
- WebSocket connection to relay server
- Command to request photo: `CAPTURE room=ABC123 azimuth=245 zoom=2x`
- Receive and display incoming photos

#### Step 4: Create Connection Link
When you want to connect iPad:
1. Tap "Generate Link" in POCO app
2. System creates: `https://your-server.com/connect?room=ABC123`
3. Copy the link

#### Step 5: Open Link on iPad
1. Open Safari on iPad
2. Paste link in address bar
3. Tap "Go"
4. Grant camera permission when prompted
5. See "Connected ✓" status on both devices

#### Step 6: Test
- Tap "Capture Test" on POCO app
- iPad should take a photo and send it back within 2 seconds
- Photo appears on POCO screen with timestamp

### iPad Configuration (One-Time)

After opening the link for the first time, grant permissions:

1. **Camera Access**: Settings → Privacy → Camera → Safari → Toggle ON
2. **Local Network** (if using WiFi only): Settings → Privacy → Local Network → Safari → Toggle ON
3. **Optional – Add to Home Screen** (for quick access):
   - Open website in Safari
   - Tap Share button (box with arrow)
   - Select "Add to Home Screen"
   - Now appears as a standalone app icon

### How Detection & Photo Capture Works

1. **POCO detects target** via acoustic analysis (frequency/azimuth/elevation)
2. **POCO sends command** to relay server: `"CAPTURE room=ABC123 azimuth=245 zoom=2x"`
3. **Relay server forwards** command to iPad over WebSocket
4. **iPad captures 5 frames** rapidly via camera API (for reliability)
5. **iPad compresses** and sends frames back to relay server
6. **Relay server routes** frames to POCO
7. **POCO displays photo**, runs object recognition (if AI enabled), updates telemetry & map

Full cycle: **1–3 seconds**

### Security & Privacy

- **Room codes** are random and unique—only someone with the link can join
- **Relay server stores nothing**—all data passes through in real-time and vanishes after session ends
- **Optional password** security: `?room=ABC123&pass=SECRET`
- **HTTPS required** for camera access in Safari (automatic if you use a proper hosting provider)

### Important Limitations

⚠️ **Camera only works while tab is active**: If you close Safari on iPad, camera stops. Keep the tab open during monitoring.

⚠️ **Background mode is limited**: If iPad locks, browser may pause. Keep screen on for reliability.

⚠️ **Photo quality depends on network**: Weak WiFi = slower transfers or lower resolution. Use 5GHz WiFi when possible.

⚠️ **First-time permission required**: Safari will prompt for camera access—you must allow it.

### Quick Checklist

```
[ ] Relay server deployed and running
[ ] Web page hosted and accessible via link
[ ] POCO app generates unique room codes
[ ] POCO app sends WebSocket commands
[ ] iPad Safari has camera permission enabled
[ ] Test link opens successfully on iPad
[ ] "Connected ✓" status appears on both POCO and iPad
[ ] Test photo captured and received in < 3 seconds
```

### Final Rule

This system is a helper, not a guarantee. It gives you extra information and reaction time. But if you hear a siren, see an explosion, or spot a target with your own eyes—**act immediately**. Technology assists; your attention saves lives.

---

## Diagnostics Log
Application now emits structured Android logcat entries via tag `AegisDiagnostics`:
- `BUG:` runtime behavior that is inconsistent and should be treated as a defect.
- `TO_FIX:` technical debt or integration gaps that should be fixed next.
- `MISSING:` functionality that is intentionally not implemented yet.

## Current Gaps
- Real microphone DSP/ML detection pipeline is not connected; telemetry is simulation-driven.
- If runtime permissions are denied (`RECORD_AUDIO`, `BLUETOOTH_CONNECT`), detection quality degrades and corresponding `TO_FIX` logs are emitted.
- Bluetooth device counting is profile-based and may not represent true microphone array topology.

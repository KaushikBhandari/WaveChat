# WaveChat — Offline Mesh Chat Application

> Chat with nearby devices **without internet, without SIM, without Wi-Fi**.  
> Works in disasters, remote areas, network blackouts, and crowded events.

---

## What is WaveChat?

WaveChat is an Android app that lets smartphones talk to each other directly using Bluetooth — no internet required. It works like a walkie-talkie but for text messages. If someone is too far away, the message automatically hops through other phones in between to reach them. This is called **mesh networking**.

---

## How It Works (Simple Version)

```
Phone A  ──────►  Phone B  ──────►  Phone C
(you)           (in middle)       (far away)

Phone A and Phone C cannot reach each other directly.
Phone B is in the middle and automatically passes the message along.
Phone C receives the message even though it is out of range of Phone A.
```

---

## Features

| Feature | Description |
|---|---|
| **No internet needed** | Works completely offline using Bluetooth |
| **Private chat** | You choose exactly who to connect to |
| **Group chat** | Connect to multiple people at once |
| **Mesh relay** | Messages hop across multiple phones automatically |
| **Store & forward** | If no one is nearby, message is saved and sent when someone connects |
| **Duplicate filtering** | Same message never shows twice even if relayed multiple times |
| **Signal strength** | Shows how close each device is (dBm bars) |
| **Relayed indicator** | Purple bubble shows when a message came through another phone |

---

## How to Use the App

### Step 1 — Open the app
Open WaveChat on your phone. Allow all permissions when asked (Bluetooth and Location). Turn Bluetooth ON if asked.

### Step 2 — See nearby devices
The app automatically scans for other phones running WaveChat nearby. You will see them listed as **Device XXXX** (last 4 characters of their Bluetooth address).

### Step 3 — Select who to chat with
Tap the checkbox next to the device(s) you want to chat with. You can select one person for private chat or multiple people for group chat. Devices you do NOT select cannot see your messages.

### Step 4 — Start chatting
Tap **"Start Private Chat"** or **"Start Group Chat"** at the bottom. Type your message and tap the send button.

### Step 5 — Go back
Tap the **← back arrow** to return to the device list. This disconnects everyone and you can select new people.

---

## Understanding the Screen

### Scan Screen (device list)
```
┌─────────────────────────────────┐
│ WaveChat                        │
│ Select who to chat with         │
├─────────────────────────────────┤
│ ● Scanning… select devices      │  ← green dot = working
├─────────────────────────────────┤
│ NEARBY DEVICES                  │
│                                 │
│ ☑ 📱 Device 8A19   ▂▄█ -63dBm │  ← selected (checkmark)
│ ☐ 📱 Device F3C2   ▂▄  -78dBm │  ← not selected
│                                 │
│  [Start Private Chat ➤]         │  ← tap to begin
└─────────────────────────────────┘
```

### Chat Screen
```
┌─────────────────────────────────┐
│ ← Device 8A19          📱      │
│ 1/1 connected                   │
├─────────────────────────────────┤
│           Hello!          [blue]│  ← your message
│ [purple] Hi there!              │  ← received message
│          relayed ×1             │  ← came through another phone
├─────────────────────────────────┤
│ [Type a message…]        [  ➤] │
└─────────────────────────────────┘
```

### Message colours
| Colour | Meaning |
|---|---|
| 🔵 Blue bubble (right) | Message you sent |
| ⬜ Dark bubble (left) | Message received directly |
| 🟣 Purple bubble (left) | Message relayed through another phone |

---

## Testing Mesh Connection (3 Phones Required)

To prove mesh networking works, you need 3 phones.

**Setup:**
- Place **Phone A** and **Phone C** far apart (15+ metres, different rooms)
- Place **Phone B** in the middle, in range of both

**Steps:**
1. Install WaveChat on all 3 phones
2. On Phone B — select both Phone A and Phone C → Start Group Chat
3. On Phone A — select Phone B → Start Chat
4. On Phone C — select Phone B → Start Chat
5. Send a message from Phone A

**Result if mesh is working:**
- Message appears on Phone C even though Phone A is NOT in Phone C's nearby devices list
- Message shows **purple bubble** with `relayed ×1` — confirming it hopped through Phone B

---

## Technical Details

| Item | Value |
|---|---|
| Technology | Bluetooth Low Energy (BLE) |
| Routing algorithm | Epidemic routing |
| Message delivery | Store-and-Forward (DTN) |
| Max hops per message | 5 |
| Message lifetime | 5 minutes |
| Max pending queue | 100 messages |
| BLE range per hop | ~10 metres indoors |
| Max connected peers | ~7 (Bluetooth hardware limit) |
| Min Android version | Android 8.0 (API 26) |

---

## File Structure

```
app/src/main/
├── java/com/example/wavechat/
│   ├── MainActivity.kt     — UI, BLE scanning, connecting, sending
│   ├── MeshMessage.kt      — Message model (UUID, hop count, TTL, JSON)
│   ├── MeshRouter.kt       — Mesh relay engine (routing, store-forward)
│   └── BleDevice.kt        — Nearby device model (name, address, RSSI)
├── res/
│   ├── mipmap-*/           — App icons (all screen densities)
│   └── values/             — Themes and colours
└── AndroidManifest.xml     — Permissions and app configuration
```

---

## Permissions Required

| Permission | Why needed |
|---|---|
| Bluetooth | To connect to nearby devices |
| Bluetooth Scan | To find nearby WaveChat devices |
| Bluetooth Advertise | So other phones can find you |
| Location (Fine) | Required by Android for BLE scanning |

> **Privacy note:** Location permission is required by Android OS for Bluetooth scanning. The app does NOT access your GPS location or track where you are.

---

## Known Limitations

- All phones must have WaveChat installed and open
- Bluetooth must be ON
- Range is approximately 10 metres indoors per hop (more outdoors)
- Battery usage increases with continuous Bluetooth scanning
- No media sharing in this version (text only)
- Messages are not stored after app is closed (no database yet)

---

## Future Improvements (Roadmap)

- [ ] AES-256 encryption + Diffie-Hellman key exchange
- [ ] Persistent message storage (SQLite database)
- [ ] Image and file sharing
- [ ] Voice messages
- [ ] User profiles and custom names
- [ ] Wi-Fi Direct support for longer range
- [ ] Notification for new messages when app is in background

---

## Support

If the app is not working, check:
1. Bluetooth is turned ON
2. Location permission is granted (Settings → Apps → WaveChat → Permissions)
3. Both phones have the app open at the same time
4. Phones are within 10 metres of each other

---

*WaveChat — Communication without boundaries.*
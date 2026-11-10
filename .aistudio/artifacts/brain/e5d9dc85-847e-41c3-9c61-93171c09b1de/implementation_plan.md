# RB DIALER — UI Polish, Onboarding Overhaul, Account Privacy & Update Animation

A comprehensive polish and hardening update transforming the application into **RB DIALER**, delivering an industry-grade, uncluttered user experience comparable to WhatsApp and Telegram with zero visual gimmicks.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The following product decisions were confirmed during Phase 1:
> - **Account Screen Privacy**: The internal `Hardware ID (DEV-AND-13)` is completely removed from the user interface. It is replaced with a clean, trustworthy **"End-to-End Encrypted Verified"** badge with a subtle cyber-mint shield lock icon (`🔒 E2EE VERIFIED • OPUS VOIP`).
> - **App Open & Update Flow**: When the app opens, an illuminated **RB DIALER** splash animation smoothly reveals the interface. If an app update is available (or triggered via settings), an animated glassmorphism update dialog notifies the user with release notes, download progress simulation (MB/s and bytes counter), and instant install readiness.
> - **App Branding & Logo**: App name updated to **RB DIALER** across all headers, launcher string resources, metadata, and settings. A custom adaptive launcher icon featuring the generated 3D titanium & neon cyan **RB** emblem is installed.
> - **Onboarding Experience**: All dancing cartoon canvas characters and pose buttons are permanently removed. The onboarding carousel showcases high-definition still 3D studio renders for **VoIP Voice Calling**, **4K Encrypted Video Telephony**, and **Secure Instant Messaging**.

---

## 1. Overview & Core Concept

- **What It Delivers**:
  - **Account & Privacy Screen**: Modern profile view with avatar, phone number copy action, E2EE verification pill, editable status message, and organized settings categories without technical jargon or device ID leaks.
  - **Onboarding Carousel**: 3 pristine feature slides with still 3D studio artwork, glowing badges, clean pill tags, and smooth Swiggy/Telegram-style pagination.
  - **Animated App Launch & Update Center**: Smooth launch transition with the glowing RB monogram, plus an interactive in-app update modal with live download progress simulation, release notes, and install triggers.
  - **Video Call Controls**: Z-index aware (`zIndex(30f)`) glassmorphism dock (`Color(0xD90F172A)`) with pin/auto-hide controls toggle, ensuring buttons remain visible and responsive across all aspect ratios and camera resolutions.

---

## 2. User Experience & Visual Design

### Key User Flows

```
[ App Launch ]
      │
      ▼
┌──────────────────────────────┐
│  RB DIALER Splash Reveal     │  (Glowing RB Emblem, 1.2s smooth fade)
└──────────────┬───────────────┘
               │
               ├─────────────────────────┐
               ▼ (First Launch)          ▼ (Returning User)
┌──────────────────────────────┐   ┌──────────────────────────────┐
│  3-Slide Onboarding Carousel │   │  Dialer Main Screen          │
│  • Ultra HD Opus VoIP (3D)   │   │  • Top Bar: "RB DIALER"      │
│  • 4K Encrypted Video (3D)   │   │  • Ready / DND Status Pill   │
│  • P2P Secure Chat (3D)      │   │  • Update Available Banner   │
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │                                  │
               └──────────────►◄──────────────────┘
                                │
                                ▼
                   ┌──────────────────────────┐
                   │  Account & Settings      │
                   │  • ASDF (+91 555...)     │
                   │  • 🔒 E2EE Verified      │
                   │  • Status Message        │
                   │  • Streamlined Categories│
                   └──────────────────────────┘
```

### Visual Identity & Theme
- **Dominant Colors**: Deep midnight slate (`#0B1120`), cyber mint (`#10B981`), signal amber (`#F59E0B`), and vibrant neon cyan (`#06B6D4`).
- **Typography**: Space Grotesk for bold headers, Inter / Roboto for clean body copy, JetBrains Mono for cryptographic indicators and duration counters.
- **Component Styling**: Clean glassmorphism cards, 24dp rounded corners, subtle translucent borders (`BorderStroke(1.dp, Color.White.copy(0.20f))`), and minimum 48dp touch targets.

---

## 3. Key Product Decisions & Trade-Offs

- **Still 3D Studio Renders vs. Animated Canvas Characters**:
  - *Decision*: Fully switch to still 3D studio artwork assets (`img_rb_voip_call`, `img_rb_video_call`, `img_rb_secure_chat`).
  - *Rationale*: Eliminates cartoonish dancing animations, reducing CPU/GPU overhead during onboarding and presenting an executive, professional look.
- **Hardware ID Privacy Abstraction**:
  - *Decision*: Retain the device ID in backend WebRTC authentication and registration, but replace it in the UI with a user-friendly security badge (`🔒 E2EE VERIFIED`).
  - *Rationale*: Protects internal identifiers from leaking while building user trust in the app's cryptographic integrity.
- **In-App Update Experience**:
  - *Decision*: Build a rich animated dialog with progress bar, download speed meter, and changelog highlights, with non-intrusive dismiss options.
  - *Rationale*: Keeps users informed about new releases without breaking call readiness or blocking navigation.

---

## 4. Technical Architecture & Component Structure

```
┌────────────────────────────────────────────────────────┐
│                      MainActivity                      │
│   • App Launch Reveal Animation (RB Emblem Glow)       │
│   • Update Dialog & Live Download State Manager        │
│   • Navigation State (ONBOARDING, DIALER, ACCOUNT...)  │
└───────────────────────────┬────────────────────────────┘
                            │
       ┌────────────────────┼────────────────────┐
       ▼                    ▼                    ▼
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Onboarding  │     │ DialerScreen │     │ Account /    │
│  Screen      │     │  & Call HUD  │     │ Settings     │
│ • Still 3D   │     │ • RB Branding│     │ • E2EE Badge │
│   Artwork    │     │ • Z-Index    │     │ • Clean M3   │
│ • Swiggy/    │     │   Glass Dock │     │   Categories │
│   Telegram UI│     │ • Auto-Hide  │     │ • No HW ID   │
└──────────────┘     └──────────────┘     └──────────────┘
```

### Implementation Checklist
1. **OnboardingScreen**: Replace character canvas and pose picker with `Image` components rendering `img_rb_voip_call`, `img_rb_video_call`, and `img_rb_secure_chat` inside glassmorphism cards.
2. **Account / Settings**: In `SettingsScreen.kt` and `ProfileScreen.kt`, remove `DEV-AND-13` / hardware ID row and replace with `🔒 E2EE VERIFIED • OPUS VOIP`. Update all "LKS DIALER" strings to "RB DIALER".
3. **App Open Animation**: Add smooth splash reveal in `MainActivity.kt` with pulsing RB emblem.
4. **Update Dialog**: Enhance `UpdateDialog.kt` with modern glass styling, rocket badge, animated progress bar, speed meter, and celebration toast.
5. **Compilation & Testing**: Run `compile_applet` and local JVM tests to verify stability.

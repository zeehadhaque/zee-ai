<div align="center">

<!-- HERO BANNER: Pure SVG Keyframe Animation, Obsidian / Electric Blue Auteur Aesthetic -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 920 300" width="100%" height="auto" style="border-radius: 12px; border: 1px solid #1E293B; background: #060913;">
  <defs>
    <!-- Background Gradients -->
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#060913" />
      <stop offset="45%" stop-color="#090E1A" />
      <stop offset="100%" stop-color="#04060C" />
    </linearGradient>

    <!-- Obsidian Blue Accent Gradient -->
    <linearGradient id="neonBlue" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="#1D4ED8" />
      <stop offset="50%" stop-color="#2F80FF" />
      <stop offset="100%" stop-color="#60A5FA" />
    </linearGradient>

    <!-- Persona Dual Wave Gradient -->
    <linearGradient id="dualAura" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#2F80FF" stop-opacity="0.85" />
      <stop offset="100%" stop-color="#8B5CF6" stop-opacity="0.65" />
    </linearGradient>

    <!-- Glow Filter -->
    <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur stdDeviation="6" result="blur" />
      <feComposite in="SourceGraphic" in2="blur" operator="over" />
    </filter>

    <!-- Subtle Scanline Pattern -->
    <pattern id="scanlines" width="100" height="4" patternUnits="userSpaceOnUse">
      <line x1="0" y1="0" x2="100" y2="0" stroke="#000000" stroke-width="1.2" opacity="0.32" />
    </pattern>
  </defs>

  <!-- Frame Background -->
  <rect width="920" height="300" rx="12" fill="url(#bgGrad)" />

  <!-- Grid Reticle Matrix -->
  <g opacity="0.08" stroke="#60A5FA" stroke-width="0.75">
    <line x1="60" y1="0" x2="60" y2="300" />
    <line x1="180" y1="0" x2="180" y2="300" />
    <line x1="300" y1="0" x2="300" y2="300" />
    <line x1="420" y1="0" x2="420" y2="300" />
    <line x1="540" y1="0" x2="540" y2="300" />
    <line x1="660" y1="0" x2="660" y2="300" />
    <line x1="780" y1="0" x2="780" y2="300" />
    <line x1="0" y1="60" x2="920" y2="60" />
    <line x1="0" y1="120" x2="920" y2="120" />
    <line x1="0" y1="180" x2="920" y2="180" />
    <line x1="0" y1="240" x2="920" y2="240" />
  </g>

  <!-- Scanline Overlay -->
  <rect width="920" height="300" fill="url(#scanlines)" pointer-events="none" />

  <!-- Radar / Horizon Pulse Sweep -->
  <g opacity="0.4">
    <ellipse cx="460" cy="290" rx="360" ry="60" fill="none" stroke="#2F80FF" stroke-width="1.2" stroke-dasharray="8 8">
      <animateTransform attributeName="transform" type="rotate" from="0 460 290" to="360 460 290" dur="24s" repeatCount="indefinite" />
    </ellipse>
    <ellipse cx="460" cy="290" rx="220" ry="38" fill="none" stroke="#8B5CF6" stroke-width="0.8" opacity="0.6" />
  </g>

  <!-- Animated Neural Aperture / Crest (Left side) -->
  <g transform="translate(100, 145)">
    <!-- Ambient Pulsing Core -->
    <circle cx="0" cy="0" r="44" fill="#0B132B" stroke="#1E293B" stroke-width="2">
      <animate attributeName="r" values="42;47;42" dur="3.6s" repeatCount="indefinite" />
    </circle>
    <circle cx="0" cy="0" r="32" fill="none" stroke="#2F80FF" stroke-width="1.5" opacity="0.8" filter="url(#glow)">
      <animate attributeName="stroke-dasharray" values="4 8; 16 4; 4 8" dur="4s" repeatCount="indefinite" />
      <animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="16s" repeatCount="indefinite" />
    </circle>
    <circle cx="0" cy="0" r="20" fill="none" stroke="#8B5CF6" stroke-width="1.5" opacity="0.7">
      <animateTransform attributeName="transform" type="rotate" from="360" to="0" dur="11s" repeatCount="indefinite" />
    </circle>
    <!-- Geometric 'Z' Monogram Glyph -->
    <path d="M -10 -11 L 10 -11 L -10 11 L 10 11" fill="none" stroke="#F1F5F9" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" filter="url(#glow)">
      <animate attributeName="stroke" values="#F1F5F9;#60A5FA;#E2E8F0;#F1F5F9" dur="5s" repeatCount="indefinite" />
    </path>
    <!-- Orbiting Synapse Node -->
    <circle cx="0" cy="0" r="3" fill="#60A5FA">
      <animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="3.2s" repeatCount="indefinite" />
      <animate attributeName="cx" values="32;36;32" dur="3.2s" repeatCount="indefinite" />
    </circle>
  </g>

  <!-- Typography & Telemetry Header -->
  <g transform="translate(185, 0)">
    <!-- Header Meta Tag -->
    <text x="0" y="72" fill="#2F80FF" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace" font-size="11" font-weight="700" letter-spacing="3.5">
      PURE NATIVE SYSTEM ARCHITECTURE • ZERO WEBVIEW
    </text>

    <!-- Main Title: ZEE AI -->
    <text x="0" y="132" fill="#F8FAFC" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="52" font-weight="900" letter-spacing="2">
      ZEE AI
    </text>
    <text x="210" y="132" fill="#64748B" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="20" font-weight="300" letter-spacing="4">
      / COMPANION
    </text>

    <!-- Subtitle / Mission Stance -->
    <text x="0" y="168" fill="#94A3B8" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="14.5" font-weight="400" letter-spacing="0.4">
      Autonomous Android Agent &amp; Dual-Persona Voice Core (ZEE &amp; ZHEE).
    </text>
    <text x="0" y="190" fill="#64748B" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif" font-size="12.5" font-weight="400">
      Built with 100% Kotlin • Jetpack Compose • AccessibilityService • Room • MediaPipe &amp; Gemini.
    </text>

    <!-- System Spec Telemetry Strip -->
    <g transform="translate(0, 222)">
      <!-- Panel Badge 1 -->
      <rect x="0" y="0" width="138" height="28" rx="5" fill="#0B1120" stroke="#1E293B" stroke-width="1" />
      <circle cx="12" cy="14" r="3.5" fill="#10B981">
        <animate attributeName="opacity" values="1;0.35;1" dur="2s" repeatCount="indefinite" />
      </circle>
      <text x="24" y="18" fill="#CBD5E1" font-family="monospace" font-size="10.5" font-weight="600">AOSP KERNEL ACC</text>

      <!-- Panel Badge 2 -->
      <rect x="148" y="0" width="144" height="28" rx="5" fill="#0B1120" stroke="#1E293B" stroke-width="1" />
      <circle cx="160" cy="14" r="3.5" fill="#2F80FF" />
      <text x="172" y="18" fill="#CBD5E1" font-family="monospace" font-size="10.5" font-weight="600">ZERO LOCALHOST</text>

      <!-- Panel Badge 3 -->
      <rect x="302" y="0" width="142" height="28" rx="5" fill="#0B1120" stroke="#1E293B" stroke-width="1" />
      <circle cx="314" cy="14" r="3.5" fill="#8B5CF6">
        <animate attributeName="opacity" values="0.4;1;0.4" dur="2.4s" repeatCount="indefinite" />
      </circle>
      <text x="326" y="18" fill="#CBD5E1" font-family="monospace" font-size="10.5" font-weight="600">MEDIAPIPE OFFLINE</text>

      <!-- Panel Badge 4 -->
      <rect x="454" y="0" width="168" height="28" rx="5" fill="#0B1120" stroke="#1E293B" stroke-width="1" />
      <circle cx="466" cy="14" r="3.5" fill="#F59E0B" />
      <text x="478" y="18" fill="#CBD5E1" font-family="monospace" font-size="10.5" font-weight="600">SEARCH GROUNDING</text>
    </g>
  </g>

  <!-- Live Pulse Waveform (Right Corner) -->
  <g transform="translate(800, 80)" opacity="0.8">
    <path d="M 0 40 Q 15 10 30 40 T 60 40 T 90 40" fill="none" stroke="#2F80FF" stroke-width="1.8" filter="url(#glow)">
      <animate attributeName="d" 
        values="M 0 40 Q 15 5 30 40 T 60 40 T 90 40;
                M 0 40 Q 15 45 30 40 T 60 20 T 90 40;
                M 0 40 Q 15 10 30 40 T 60 40 T 90 40" 
        dur="2.5s" repeatCount="indefinite" />
    </path>
    <text x="32" y="70" fill="#475569" font-family="monospace" font-size="9" text-anchor="middle" letter-spacing="1">AUDIO FLUX</text>
  </g>
</svg>

<br/>

<!-- TECHNICAL BADGES -->
<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_14%2B_(API_34)-060913?style=for-the-badge&logo=android&logoColor=3DDC84&labelColor=060913" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin_2.0-060913?style=for-the-badge&logo=kotlin&logoColor=7F52FF&labelColor=060913" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-060913?style=for-the-badge&logo=jetpackcompose&logoColor=4285F4&labelColor=060913" alt="Compose" />
  <img src="https://img.shields.io/badge/Storage-Room_DB_v2.6-060913?style=for-the-badge&logo=sqlite&logoColor=003B57&labelColor=060913" alt="Room" />
  <img src="https://img.shields.io/badge/On--Device-MediaPipe_LLM-060913?style=for-the-badge&logo=google&logoColor=FBBC05&labelColor=060913" alt="MediaPipe" />
  <img src="https://img.shields.io/badge/Cloud_Engine-Gemini_Live_%2B_Search-060913?style=for-the-badge&logo=googlegemini&logoColor=2F80FF&labelColor=060913" alt="Gemini" />
  <img src="https://img.shields.io/badge/CI%2FCD-GitHub_Actions_Cloud_APK-060913?style=for-the-badge&logo=githubactions&logoColor=2088FF&labelColor=060913" alt="Actions" />
</p>

</div>

---

### Dual Persona Switcher & Runtime State

<div align="center">

<!-- PERSONA SWITCHER STRIP SVG -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 920 100" width="100%" height="auto" style="border-radius: 8px; border: 1px solid #1E293B; background: #080D1A;">
  <defs>
    <linearGradient id="linkBeam" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="#2F80FF" />
      <stop offset="50%" stop-color="#60A5FA" />
      <stop offset="100%" stop-color="#A855F7" />
    </linearGradient>
  </defs>

  <!-- Left: Persona ZEE -->
  <g transform="translate(30, 20)">
    <rect width="260" height="60" rx="8" fill="#0B1120" stroke="#2F80FF" stroke-width="1.2" />
    <circle cx="28" cy="30" r="14" fill="#1E293B" stroke="#2F80FF" stroke-width="1.5" />
    <text x="28" y="34" fill="#60A5FA" font-family="-apple-system, sans-serif" font-weight="900" font-size="12" text-anchor="middle">Z</text>
    <text x="54" y="27" fill="#F8FAFC" font-family="-apple-system, sans-serif" font-weight="700" font-size="14" letter-spacing="1">ZEE</text>
    <text x="86" y="26" fill="#2F80FF" font-family="monospace" font-size="9.5" font-weight="600">[OPERATIONAL]</text>
    <text x="54" y="44" fill="#94A3B8" font-family="-apple-system, sans-serif" font-size="11">Analytical • Phone Ops • Code &amp; Logic</text>
  </g>

  <!-- Central Bridge Link -->
  <g transform="translate(305, 30)">
    <line x1="0" y1="20" x2="310" y2="20" stroke="#1E293B" stroke-width="2" />
    <line x1="0" y1="20" x2="310" y2="20" stroke="url(#linkBeam)" stroke-width="2" stroke-dasharray="14 10">
      <animate attributeName="stroke-dashoffset" from="48" to="0" dur="2s" repeatCount="indefinite" />
    </line>
    <rect x="85" y="6" width="140" height="26" rx="13" fill="#060913" stroke="#334155" stroke-width="1" />
    <text x="155" y="23" fill="#C8D1E0" font-family="monospace" font-size="10" font-weight="700" text-anchor="middle" letter-spacing="1">
      SHARED ROOM STATE
    </text>
  </g>

  <!-- Right: Persona ZHEE -->
  <g transform="translate(630, 20)">
    <rect width="260" height="60" rx="8" fill="#0B1120" stroke="#8B5CF6" stroke-width="1.2" />
    <circle cx="28" cy="30" r="14" fill="#1E293B" stroke="#8B5CF6" stroke-width="1.5" />
    <text x="28" y="34" fill="#C084FC" font-family="-apple-system, sans-serif" font-weight="900" font-size="12" text-anchor="middle">ZH</text>
    <text x="54" y="27" fill="#F8FAFC" font-family="-apple-system, sans-serif" font-weight="700" font-size="14" letter-spacing="1">ZHEE</text>
    <text x="96" y="26" fill="#A855F7" font-family="monospace" font-size="9.5" font-weight="600">[CREATIVE]</text>
    <text x="54" y="44" fill="#94A3B8" font-family="-apple-system, sans-serif" font-size="11">Auteur Cinema • Narrative • YouTube Studio</text>
  </g>
</svg>

</div>

---

## Architectural Manifesto

Most modern "AI mobile apps" are thin web wrappers: React Native bundles, Electron ports, or localhost Node/Python servers silently draining battery in the background while routing sandboxed DOM clicks.

**ZEE AI is engineered on the opposite philosophy: 100% pure native Android.**
- **Zero WebViews:** Every pixel is rendered by Jetpack Compose into hardware-accelerated Skia surfaces.
- **Zero Localhost Runtimes:** No internal HTTP servers running on `127.0.0.1`. All inter-component messaging uses standard Android IPC, Kotlin Coroutines `Flow`, and Jetpack Architecture primitives.
- **Deep Android Integration:** Operates directly inside the operating system via a custom `AccessibilityService` engine, interacting with apps, notifications, and settings natively.
- **Dual-Brain Hybrid Engine:** Streams high-tier Gemini intelligence with real-time Google Search Grounding when connected; seamlessly switches down to on-device MediaPipe LLM inference when offline.

---

## Core Feature Matrix & Native Architecture

| Module / System | Native Android API & Engine | Operational Capability | Redundancy & Security Boundary |
| :--- | :--- | :--- | :--- |
| **Voice & Wake Word** | `AudioRecord` • Low-Latency ASR • `android.speech.tts.TextToSpeech` | Real-time wake detection, streaming speech recognition, and instant persona-conditioned vocal responses. | Runs entirely within background foreground service; zero audio egress unless conversational query is dispatched. |
| **Autonomous Phone Control** | `AccessibilityService` • `GestureDescription` • Node Tree Inspection | Inspects live AOSP UI hierarchies, executes programmatic taps, scrolls, text insertion, app launching, and automated workflows. | Destructive actions require explicit on-screen visual confirmation or biometric authentication (`BiometricPrompt`). |
| **Online Intelligence** | Official Google GenAI SDK (Gemini 1.5 Pro / Flash) | Streaming tokens, multimodality, dynamic context caching, and real-time Google Search Grounding for verified factual synthesis. | Enforces strict payload sanitization; API keys isolated in encrypted Keystore-backed `EncryptedSharedPreferences`. |
| **On-Device Offline Brain** | Google MediaPipe LLM Inference Engine (`Gemma-2B` / `Phi-2` .bin) | Zero-network fallback. Instant local creative drafts, shell commands, device diagnostics, and emergency voice responses. | 100% air-gapped on-device compute. Zero telemetry or external network calls made while operating in offline mode. |
| **Filmmaker Studio** | Native Canvas • MediaPipe Image Embedder • Room DB | Cinematographic storyboard drafting, scene-by-scene script breakdown, shot deck lighting presets, and camera angle directors. | Local SQLite database persistence via Room; cached assets saved to app-scoped isolated media sandboxes. |
| **YouTube Studio Engine** | Direct Google OAuth 2.0 PKCE • YouTube Data API v3 | Upload queue management, automated title/tag/description drafting, engagement analytics digestion, and thumbnail verification. | Scoped OAuth permissions; refresh tokens held exclusively inside hardware-backed Android Keystore. |
| **Universal SAF Reader** | Storage Access Framework (`Intent.ACTION_OPEN_DOCUMENT_TREE`) | Scoped read access to device logs, scripts, text corpora, raw PDFs, and project directories without legacy file permission hazards. | Operates under strict Android 14+ Scoped Storage guidelines; never requests legacy `MANAGE_EXTERNAL_STORAGE`. |
| **The AI Council** | Multi-Agent Synthetic Deliberation Matrix | ZEE and ZHEE cross-examine arguments, debate engineering or artistic trade-offs, and generate arbitrated decisions. | Synthesized in local memory queues; verdicts logged to persistent Room DB tables with full rationale history. |

---

## Project Structure (`com.zeeai.companion`)

```text
zee-ai/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml              # AccessibilityService, Audio & Service declarations
│   │   │   ├── res/                             # Vector drawables, raw configs & theme values
│   │   │   └── java/com/zeeai/companion/
│   │   │       ├── accessibility/               # Native OS Accessibility Control
│   │   │       │   ├── ZeeAccessibilityService.kt   # System event observer & Node tree traverser
│   │   │       │   ├── ActionExecutor.kt            # Dispatcher for taps, swipes & gestures
│   │   │       │   └── UIHierarchyScanner.kt        # AccessibilityNodeInfo extractor & parser
│   │   │       ├── ai/                          # Dual Brain Orchestration
│   │   │       │   ├── online/                  # Gemini streaming & Search Grounding
│   │   │       │   │   ├── GeminiClient.kt
│   │   │       │   │   └── SearchGroundingWrapper.kt
│   │   │       ├── offline/                 # On-device MediaPipe & LiteRT inference
│   │   │       │   ├── MediaPipeEngine.kt
│   │   │       │   └── ModelDownloader.kt
│   │   │       └── council/                 # Multi-persona debate arbitration
│   │   │           └── CouncilOrchestrator.kt
│   │   │       ├── data/                        # Persistence & System Repositories
│   │   │       │   ├── local/                   # Room DB entities & DAOs
│   │   │       │   ├── ZeeDatabase.kt
│   │   │       │   ├── MemoryDao.kt
│   │   │       │   └── ConversationEntity.kt
│   │   │       └── repository/              # Unified AI & Device Repository bindings
│   │   │       ├── persona/                     # Identity, Voices & Prompt Profiles
│   │   │       │   ├── PersonaManager.kt        # ZEE vs. ZHEE voice/behavior switch engine
│   │   │       │   ├── ZeePersona.kt            # System Architect persona definition
│   │   │       │   └── ZheePersona.kt           # Cinematic Auteur persona definition
│   │   │       ├── studio/                      # Creative & Integration Tooling
│   │   │       │   ├── filmmaker/               # Storyboarding, scene decks & scripts
│   │   │       │   ├── youtube/                 # YouTube Data API client & metrics
│   │   │       │   └── saf/                     # Storage Access Framework document reader
│   │   │       ├── voice/                       # Audio Capture & Text-to-Speech
│   │   │       │   ├── HotwordDetector.kt       # Continuous low-power listening
│   │   │       │   ├── VoiceRecorder.kt         # AudioRecord PCM stream processor
│   │   │       │   └── SpeechSynthesizer.kt     # Low-latency persona TTS engine
│   │   │       └── ui/                          # 100% Jetpack Compose / M3 Interface
│   │   │           ├── theme/                   # Noir Obsidian color palette & typography
│   │   │           ├── components/              # Animated waveforms, radar reticles, meters
│   │   │           ├── screens/                 # Chat, Council, Studio & Accessibility consoles
│   │   │           └── MainActivity.kt          # Single-Activity compose root
│   │   └── test/                                # Unit tests (JUnit 5 + MockK)
│   └── build.gradle.kts                         # Android build config & dependency tree
├── .github/
│   └── workflows/
│       └── build-apk.yml                        # Cloud automated APK build pipeline
├── build.gradle.kts                             # Top-level Gradle script
└── settings.gradle.kts
```

---

## Automated Cloud APK Build & Pipeline

No Android Studio or local toolchain required to compile release builds. The repository includes a GitHub Actions continuous integration pipeline that compiles, signs, and packages release APKs directly in the cloud.

```
                  ┌────────────────────────────────────────┐
                  │ Push tag / Trigger 'build-apk.yml'     │
                  └──────────────────┬─────────────────────┘
                                     │
                                     ▼
                  ┌────────────────────────────────────────┐
                  │ Ubuntu VM: JDK 17 + Android SDK Setup  │
                  └──────────────────┬─────────────────────┘
                                     │
                                     ▼
                  ┌────────────────────────────────────────┐
                  │ ./gradlew assembleRelease              │
                  └──────────────────┬─────────────────────┘
                                     │
                                     ▼
                  ┌────────────────────────────────────────┐
                  │ Decrypt Keystore & Sign Release APK    │
                  └──────────────────┬─────────────────────┘
                                     │
                                     ▼
                  ┌────────────────────────────────────────┐
                  │ Upload to GitHub Artifacts / Releases  │
                  └──────────────────┬─────────────────────┘
                                     │
                                     ▼
                  ┌────────────────────────────────────────┐
                  │ Direct Phone Download & Install        │
                  └────────────────────────────────────────┘
```

### GitHub Actions Workflow: `.github/workflows/build-apk.yml`

```yaml
name: Build & Package Zee AI APK

on:
  push:
    branches: [ "main" ]
    tags: [ "v*" ]
  workflow_dispatch:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Assemble & Sign Android APK
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up Java Development Kit (JDK 17)
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
          cache: 'gradle'

      - name: Grant Execute Permission to Gradle Wrapper
        run: chmod +x gradlew

      - name: Compile Release APK
        run: ./gradlew assembleRelease --stacktrace --no-daemon

      - name: Sign APK (Production / Debug Fallback)
        id: sign_apk
        env:
          KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
          KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          if [ -n "$KEYSTORE_BASE64" ]; then
            echo "[*] Decoding production signing keystore..."
            echo "$KEYSTORE_BASE64" | base64 --decode > app/keystore.jks
            $ANDROID_HOME/build-tools/34.0.0/apksigner sign \
              --ks app/keystore.jks \
              --ks-key-alias "$KEY_ALIAS" \
              --ks-pass pass:"$KEYSTORE_PASSWORD" \
              --key-pass pass:"$KEY_PASSWORD" \
              --out app/build/outputs/apk/release/zee-ai-release-signed.apk \
              app/build/outputs/apk/release/app-release-unsigned.apk
            echo "apk_path=app/build/outputs/apk/release/zee-ai-release-signed.apk" >> $GITHUB_OUTPUT
          else
            echo "[!] No signing keystore secret detected. Outputting unsigned build artifact."
            cp app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/zee-ai-release-unsigned.apk
            echo "apk_path=app/build/outputs/apk/release/zee-ai-release-unsigned.apk" >> $GITHUB_OUTPUT
          fi

      - name: Upload APK to Artifacts
        uses: actions/upload-artifact@v4
        with:
          name: zee-ai-${{ github.sha }}
          path: ${{ steps.sign_apk.outputs.apk_path }}
          retention-days: 14

      - name: Create GitHub Release (Git Tag Only)
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: ${{ steps.sign_apk.outputs.apk_path }}
          draft: false
          prerelease: false
          generate_release_notes: true
```

### Direct Phone Installation Guide

1. **Trigger Cloud Build:** Push a new commit to `main`, push a release tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`), or tap **Run workflow** under the **Actions** tab on GitHub.
2. **Download Artifact:**
   - On your Android phone, open Chrome and navigate to `https://github.com/zeehadhaque/zee-ai/actions`.
   - Tap the completed workflow run.
   - Under the **Artifacts** section at the bottom, tap `zee-ai-<sha>` to download the compressed `.zip` containing the APK.
3. **Sideload & Permissions:**
   - Extract the `.zip` archive using your device file manager and tap `zee-ai-release.apk`.
   - Allow installation from unknown sources if prompted by Android package installer.
   - On first launch:
     1. Grant **Microphone** permission (required for hotword recognition and speech streaming).
     2. Open **Android Settings → Accessibility → Downloaded Apps → Enable 'ZEE AI System Service'** (required for native phone control).
     3. Insert your Gemini API Key in **Settings → Neural Engine** (or let the app default to the on-device MediaPipe model).

---

## Security & Sovereignty Principles

- **Zero Telemetry:** ZEE AI contains no third-party tracking libraries, Firebase Analytics, crash loggers, or advertisement SDKs.
- **Biometric Action Interceptors:** Any AccessibilityService action that accesses payment gateways, changes lock screen settings, or uninstalls applications triggers an obligatory hardware `BiometricPrompt` before the gesture is executed.
- **Physical Mute:** The audio recording pipeline uses standard Android audio notification icons; when paused, the foreground service releases the hardware microphone immediately.

---

<div align="center">

**`zeehadhaque/zee-ai`** • Native Android Intelligence System  
Designed with Kotlin &amp; Material 3 • Distributed under the Apache 2.0 License.

</div>

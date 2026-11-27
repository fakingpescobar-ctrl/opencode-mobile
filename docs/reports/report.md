# DeepSeek APK Reverse Engineering: Chat Input Bar Resources

## 1. Resources.arsc strings matching chat input bar terms

Exact resource names found in `resources.arsc`:

| Search term | Exact names found |
|---|---|
| `chat_input` | `chat_input_placeholder_chat`, `chat_input_placeholder_voice` |
| `hold_to_speak` | — not found |
| `switch_to_text` | `switch_to_text` |
| `switch_to_voice` | `switch_to_voice` |
| `attach_files` | `attach_files` |
| `recording_label` | `recording_label` |
| `record_swiped_up` | `record_swiped_up_label` |
| `voice` | `voice_input_button`, plus multiple "Voice" entries |
| `waveform` | — not found |
| `input_bar` | — not found (related: `chat_input_placeholder_*`) |
| `composer` | — not found |
| `send` | `Send`, `Send Photo to Ask`, `Send code`, `Send failed`, `Sending`, `send_code_ok_toast`, `send_verification_code_button`, `session_send_tip_new_chat` |
| `plus` | `ic_plus_outline_20` (resource reference) |
| `mic` | `ic_mic_filled_20`, `ic_call_live_update_mic`, `voice_input_button` |
| `keyboard` | `ic_keyboard_outline_26`, `ic_keyboard_voice_24` |

## 2. Input-related drawables (from `unzip -l base.apk | grep res/drawable`)

| Drawable file | Size (bytes) |
|---|---|
| res/drawable/ic_keyboard_outline_26.xml | 640 |
| res/drawable/ic_voice_outline_26.xml | 1328 |
| res/drawable/ic_plus_outline_20.xml | 640 |
| res/drawable/ic_mic_filled_20.xml | 1104 |
| res/drawable/ic_send_fill_20.xml | 1132 |
| res/drawable/ic_add_outline_26.xml | 1052 |
| res/drawable/ic_stop_fill_20.xml | 740 |

## 3. Vector XML content via `unzip -p` — viewport/color analysis

The `unzip -p` output contains AAPT binary vector drawable format. The following attribute names were identified in the binary data:

- `viewportWidth` (13-byte string, length byte 0x0d)
- `viewportHeight` (14-byte string, length byte 0x0e)
- `fillColor` (9-byte string, length byte 0x09)
- `pathData` (8-byte string, length byte 0x08)
- `strokeColor` (11-byte string, length byte 0x0b)
- `strokeWidth` (11-byte string followed by path commands)

### fillColor values

The color `ffffffff` (opaque white, ARGB: A=255, R=255, G=255, B=255) appears **66 times** in the `ic_keyboard_outline_26.xml` binary data, corresponding to per-path fill colors in the vector drawable. Each path in the vector has its own fill color entry.

### viewport dimensions

The viewportWidth and viewportHeight float values could not be reliably extracted without aapt2 parsing. The binary data contains the attribute name strings but the float values (4-byte IEEE 754) are embedded in the AAPT entry format following each attribute. Typical viewport sizes for 24dp icons are 24.0 or 64.0, but the exact values require proper AAPT decompression.

## 4. Color resources from resources.arsc (names containing input/send/voice/primary)

| Color resource name | Notes |
|---|---|
| `colorPrimary` | Primary theme color |
| `colorPrimaryDark` | Darker variant for status bar |
| `colorAccent` | Accent color for UI highlights |
| `colorBackgroundFloating` | Floating element background |
| `colorButtonNormal` | Button background color |
| `colorControlActivated` | Active text field control |
| `colorControlHighlight` | Highlighted text field |
| `colorControlNormal` | Normal text field control |
| `colorError` | Error state color |
| `colorSwitchThumbNormal` | Switch thumb default color |
| `editTextColor` | Edit text text color |
| `editTextBackground` | Edit text background color |
| `app_color_scheme` | Custom color scheme (light) |
| `app_color_scheme_dark` | Custom color scheme (dark) |
| `app_color_scheme_light` | Custom light scheme |
| `app_color_scheme_system` | System-based scheme |
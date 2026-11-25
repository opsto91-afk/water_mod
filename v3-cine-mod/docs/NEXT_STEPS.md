# Aage ka kaam (tumhara hissa) — camera ko game se jodna

Sab kuch `src/GameBridge.cpp` me jud-ta hai. Baaki mod (UI, timeline, engine) ko chhedne ki zarurat nahi.
Editor ye 3 cheezein pehle se sahi jagah bula raha hai:

| Hook point | Kab bulata hai | Tumhe kya karna hai |
|---|---|---|
| `game::readPlayerPose(Pose&)` | har frame LIVE mode me | player ki asli position + pitch/yaw `out` me bharo, `true` return karo. Tab `Add Key` asli POV se key banayega. |
| `game::setCameraOverride(const Pose*)` | har frame playback / scrub me | jab `cameraOverride(p)` true ho, game ke camera ko `p` dikhao. `Live` dabane par `nullptr` aata hai. |
| `game::link()` (`CameraLink`) | override ke saath | (optional) wahi pose `/camera ...` commands me. Sirf tab jab command executor mile. |

## Step 0 — Game version
Tum **60.4** par ho. Do a Barrel Roll ke signatures **26.52** ke hain. Pehle `docs/CODEX_PROMPT_1_camera_60.4.txt`
apne Ghidra agent ko do. Wo bataega ki 18 patterns 60.4 me ab bhi match karte hain ya nayi signature chahiye, aur camera ki **position** kahan hai.
Jab tak ye nahi, neeche ke offsets sirf **26.52 ke** hain.

## Step 1 — Player pose padhna (`readPlayerPose`)
Idea (reference mod se, 26.52):
1. Per-frame client update function hook karo (`bool update(void* client, bool initialized)`).
2. `client` ke vtable slot **32** se local player milta hai: `player = ((void*(**)(void*))(*(void***)client))[32](client)`.
3. Rotation: `player + 0x218` ek pointer hai → us par `{float pitch; float yaw;}`.
4. Position: `player + 0x208` (ActorState pointer). Velocity `+0x18` par hai, isliye mera **andaza** hai ki position `+0x0` par hai
   (pos, prevPos, velocity ek ke baad ek, 12 byte har ek). **Ye verify nahi hua.**
   Verify kaise: probe me position print karo aur game ke "Position: x, y, z" (coordinates dikhane wali setting) se milao.
   Dhyan do: y eye height par ho sakta hai (feet + 1.62). Agar aisa hai to key me `y` se 1.62 ghatao ya camera me jodo.
5. Un values ko mutex ke saath ek `Pose` me rakho, `readPlayerPose()` wahi lauta de.
Yaha ek bug ka dhyan: update hook game thread par chalta hai, `readPlayerPose()` render thread par — isliye mutex.

Log dekhne ke liye: `adb logcat -s CinematicMod`.

## Step 2 — Camera ka rotation (pitch/yaw/roll)
Reference mod ka tareeka:
1. Camera-setup function hook karo (`void setupCamera(void* renderer, void* camera, float partialTick)`).
2. Game ek **camera component** deta hai (client ke liye ek function `void* cameraComponent(client)`), jisme orientation ek **quaternion** hai `+0x28` par.
3. Hook me original se **pehle** quaternion ko tumhare pitch/yaw/roll wale quaternion se likho, original call karo, aur render ke baad **purana wapas likho** (restore).
4. Basis ka convention reference me: `forward = (-sin y cos p, -sin p, cos y cos p)`, `right = (-cos y, 0, -sin y)`, `up = right x forward`,
   matrix ke columns `(right, up, -forward)` → quaternion. Ye hamare `Core.cpp` ke `Pose` (pitch + = neeche dekhna, yaw 0 = south) se mel khata hai. Screen par dekh kar ulta lage to sign badlo.
5. Isse camera ghoomega (rotation ka flow), player nahi.

## Step 3 — Camera ki position (sabse mushkil)
Reference mod position nahi badalta, isliye yaha RE chahiye. Teen raaste (achhe se bure):
1. **Camera position field:** `setupCamera` ke andar camera ka eye position / view matrix kahan banta hai, wo field dhundo (Prompt 1, item 3).
   Quaternion ki tarah likho aur restore karo. Sabse saaf.
2. **Player ki position likhna:** `ActorState` ki pos aur prevPos har frame likho (prevPos bhi, warna interpolation se jhatke aayenge).
   Experimental: singleplayer me server wapas kheench sakta hai. Spectator mode me try karo.
3. **`/camera` commands:** `CameraLink` pehle se `camera @s set minecraft:free ease ... pos ... rot ...` banata hai. Sirf command executor chahiye
   (`docs/CODEX_PROMPT_2_commands.txt`). Position bhi usi se aa jayegi.

## Step 4 — Spectator, HUD, controls
- Spectator / creative-fly: abhi chat me khud `/gamemode spectator` likh do (koi hook nahi chahiye).
- HUD chhupana: `/hud @s hide all` chat me, ya `CameraLink::enter()` jab executor ho.
- Chalna / ghumna: launcher ka **Pojav Controls** on karo (game ke apne keyboard & mouse controls). Hamari taraf se koi joystick nahi.
  Jo touch hamare panels par shuru nahi hota wo seedha game / Pojav overlay ko jata hai.

## Step 5 — Phone par test list (V3.1 me jo abhi test nahi hua)
- Mod on → editor khulta hai, game full screen. Panels toggle ho rahe hain?
- Panel band hai tab game par touch / Pojav controls chal rahe hain? Panel khula hai tab panel ke bahar ka touch game ko ja raha hai?
- Divider drag, panel scroll, `+ Add Key`, strips, `+` transition popup, `+ Pin`.
- `Live` dabane par kuch crash to nahi.

## UI ke chhote kaam (jo reh gaye)
- **Black pattiyan:** preview game ke aspect me fit hota hai. Options: image ko box bhar kar dikhana (crop), ya box ko game ke aspect me rakhna. `Editor::drawPreview` me `iw/ih` ka hisaab.
- Divider ki 22ui khaali strip patli karni ho to `gap` (Editor.cpp, `draw()`).
- Tools me baaki mcaddon options (Lock On, Shake, Wall Collision, Drift, Preset Shots, More POV) abhi nahi — camera chalne ke baad `Segment` struct (Core.hpp) me jodna.
- Night vision / full bright asli game lighting nahi, sirf preview post-process.

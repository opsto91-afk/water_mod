# Mera idea, aur ab tak kya hua

## 1. Idea (jaisa tumne bataya)
- **Cinematic mod**, replay mod nahi. Movies / web series ki recording ke liye. Naam me replay sirf mcaddon ki wajah se.
- Mod Menu se **cinematic mode** on karo → menu band karo → screen par **ImGui editor** aaye.
- Game ka window editor ke andar dikhe, aur **tum game me normal controls se chal sako** (spectator jaisa POV),
  koi nakli joystick nahi. Game ke apne keyboard & mouse controls / Pojav controls.
- HeroCine (tumhara mcaddon) ka engine native me chale: **keys → segments → camera flow**. Key tumhare asli POV se bane.
- **Panels:** `Editor` (left), `Tools` (right), `Timeline` (bottom). Har ek ka chhota toggle button. Poora game full screen
  rahe jab panel band ho. Video editor jaisa: divider kheench kar panel bada/chhota.
- **Presets:** 3 slots, `Create New` (naam + category), editor me `Add Key / Delete Key / Back / Save`.
- **Timeline:** line ki jagah **rangeen strips** (white / green / blue), har segment ek strip.
  Key 1→2 = Segment 1, phir aur keys = aur segments (mcaddon jaisa).
- **Segments ke beech `+`** = transition (Fade / Instant / Hold) seconds ke saath, jaise HeroCine me.
- **Key points (pins):** timeline par reminder pin, jaise "yaha firework add karna", scene 1–5s, 5–10s...
- UI me **XYZ coordinates nahi** (creator coords nahi dekhta).
- **Moon** = night vision, **Sun** = full brightness.
- Camera **HeroCine ki tarah chale**: key 1 se key 2 tak flow.
- Aage (V4): tumhare aur idea (tumne kaha tha ki baad me bataoge).

## 2. Kya hua (version by version)
**V1** — pehla zip. ImGui game ke upar, game ka frame capture karke preview box me, Presets menu, editor, timeline.
Phone par chala: editor khula, game box me dikha (FPS ~90). Pehla bug: `[X]`/`[TLS]` launcher ke floating button ke neeche chhup gaye → andar kiye.

**V2** — pose text ke peeche dark background, buttons inset.

**V3** — panels + toggle buttons + divider resize, rangeen segment strips, 32 ease types, transitions `+`, key points, XYZ hata diya,
`CameraLink` (commands banata hai), V3 Cine Mod naam. Phone par chala (tumhare screenshots: Segment 1 / Hold / Segment 2 / pins).

**V3.1** (ye zip) — nakli joystick / up-down / look **hata diye**. Touch routing badli: jo touch hamare UI par shuru hota hai wo
consume, baaki **seedha game ko**. `Live` button (camera wapas player ko). `build.sh` ab flat `.levipack` banata hai.
GameBridge me hook points (`readPlayerPose`, `setCameraOverride`).

## 3. Dikkatein jo aayi aur unki asli wajah
| Dikkat | Wajah |
|---|---|
| Window me game me move nahi ho raha tha | Window game nahi, game ke frame ki **copy** hai. Aur humne editor ke time saare touch game se le liye the (galti meri thi). V3.1 me touch game ko jaate hain. |
| Black pattiyan window ke side me / neeche gap | Preview game ke shape (aspect) me fit hota hai → bars. Divider ki 22ui strip bhi khaali dikhti hai. (Abhi theek nahi kiya, `NEXT_STEPS.md` me.) |
| Camera key lagane par nahi chala, keys galat jagah ki | Engine pose banata hai par game tak pahunchane wala hissa nahi. Aur key hamare andar ke alag camera se bani, asli player se nahi (padhne ka hook nahi). |
| Box ke andar touch galat jagah lagta | Box chhota hai, game poori screen ke hisaab se touch padhta hai. Transform ke liye game ka input hook ya touch injection chahiye. Isliye ab controls (keys/mouse) injection se, coordinate pe nirbhar nahi. |

## 4. Jo cheezein mili (aur kaam ki hain)
- **Ghidra analysis (4 summary files):** game `eglSwapBuffers` use karta hai (bgfx + Swappy), GLES2/3 context. Risk: Vulkan ho to hamara hook nahi chalega — tumhare phone par chal raha hai, to GLES hai.
- **Pojav Controls Fixed (LeviLauncher plugin):** launcher ke andar pehle se **input injection** hai (keys, mouse, look, gyro). Game ko keyboard+mouse mode me chalata hai.
  MCPE ka apna Keyboard & Mouse settings page wahi use hota hai (tumhare screenshots).
- **Do a Barrel Roll (GPL-3):** Minecraft 26.52 ke liye camera aur player ke hooks, signatures, offsets. Detail: `REFERENCES.md`.
- `.levipack` flat zip hota hai (manifest.json root me).

## 5. Ab kya baaki (tumhara kaam)
`NEXT_STEPS.md` dekho. Short me: **game 60.4** ke liye signatures verify/nayi banana (Ghidra agent), phir `GameBridge.cpp` me
`readPlayerPose()` aur camera override bharna. Tab keys asli POV se banengi aur camera segment 1→2 flow karega.

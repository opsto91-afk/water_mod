# Pocket Bridge — DeepSeek chat + Termux, bina AI API key

Ye **Termux ke andar chalne wala manual launcher** hai, DeepSeek ka cloned APK ya naya AI model nahi. Wahi DeepSeek app/login use karo. Bridge kisi AI service ko call nahi karta. Files par kaam offline hota hai; chat ko pehle ki tarah internet chahiye aur uski normal limits lagti hain.

## Phone par shuru karo

Sirf **`pocket_bridge.py`** chahiye, poora Water Mod repo nahi. File phone ke Downloads mein save karo. Termux mein:

```sh
pkg install python coreutils
termux-setup-storage  # sirf agar Downloads se file copy karne ke liye access chahiye
cp ~/storage/downloads/pocket_bridge.py ~/pocket_bridge.py
python ~/pocket_bridge.py
```

Agar file already Termux home mein hai, `termux-setup-storage` aur `cp` skip karo. Repo checkout se bhi `python pocket_bridge/pocket_bridge.py` chala sakte ho. Koi `pip install`, API key, root ya background service nahi.

Default project folder **`~/deepseek-work`** hai. Koi aur chhota project select karna ho toh:

```sh
python ~/pocket_bridge.py --workspace "$HOME/my-notes-project"
```

Folder nahi hai toh ban jayega. Termux ko jis folder ka access pehle se hai, wahi use ho sakta hai. Apna poora home, Downloads ya sensitive files wala folder mat chuno.

## Pehla task: “Bhai, notes naam ka folder bana”

1. Menu **2** ka setup prompt copy karo. Menu **1** se installed DeepSeek app kholne ki koshish hogi; nahi khul paya toh official web chat ya manually kholne ka option hai. Prompt chat mein paste karo, phir apna task Hindi/English mein bolo.
2. DeepSeek ke reply se **ek JSON action** copy karo. Is example ko bina DeepSeek ke bhi try kar sakte ho:
   ```json
   {"action":"mkdir","path":"notes"}
   ```
3. Termux mein wapas aao. **3** choose karne **ke baad** paste karo, normal shell prompt par **nahi**. Nayi line par screen pe diya hua `end-…` type karo. Ye marker pasted text aur tere approval ko alag rakhta hai.
4. Folder/path aur action check karo. **`RUN`** type karne par hi action hoga; khaali Enter se cancel. Example result: `Created folder: notes`.
5. **4** se result dekho/copy karo. DeepSeek ke reply box mein paste karo, zaroori ho toh private details hatao, phir khud **Send** dabao. Chat ko automatically kuch nahi bheja jata.

Android par terminal text long-press karke copy karna kaafi hai. Agar free **Termux:API** clipboard utility `termux-clipboard-set` pehle se configured hai toh optional copy shortcut bhi milega; ye AI API nahi hai aur iski zaroorat nahi hai. Bridge clipboard ko padhta ya monitor nahi karta.

### Kya kar sakta hai?

| Action | Example | Behaviour |
| --- | --- | --- |
| `ls` | `{"action":"ls","path":"."}` | Visible files/folders ki listing, max 80 entries |
| `mkdir` | `{"action":"mkdir","path":"notes"}` | Ek naya folder; parent pehle se hona chahiye |
| `write` | `{"action":"write","path":"notes/todo.txt","text":"Kal doodh lana\n"}` | Nayi text file; existing file par overwrite nahi |
| `read` | `{"action":"read","path":"notes/todo.txt"}` | File ka text **terminal mein**, AI ko nahi |
| `wc` | `{"action":"wc","path":"notes/todo.txt"}` | Asli local `wc` se lines, words aur bytes |

Ek baar mein ek action. Text limit 16 KiB. Absolute/parent paths, hidden files, links se traversal aur arbitrary shell scripts supported nahi hain. Generated code ko `write` se text ki tarah save kar sakte ho; woh execute nahi hota. Bridge chat history ya result log nahi banata. Banayi hui files, terminal scrollback aur jo text tum copy karo woh reh sakte hain.

### Optional: home screen shortcut

Agar **Termux:Widget** installed hai (Termux ke same source/signature se), `~/pocket_bridge.py` ke liye ye shortcut ek baar khud banao:

```sh
mkdir -p ~/.shortcuts
printf '%s\n' '#!/data/data/com.termux/files/usr/bin/sh' 'exec "$PREFIX/bin/python" "$HOME/pocket_bridge.py"' > ~/.shortcuts/Pocket-Bridge
chmod 700 ~/.shortcuts/Pocket-Bridge
```

Home screen par Termux:Widget add/refresh karke **Pocket-Bridge** choose karo. Widget optional hai; bina widget bhi poora flow chalta hai.

## Tests (Linux / Termux, no network)

Repo root se: `python3 -B -m unittest discover -s pocket_bridge -v`. Android par menu 1 ka app launch aur clipboard alag se check karo; ye device configuration par depend karte hain.

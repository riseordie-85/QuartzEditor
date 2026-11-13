# Quartz Online

Not linked from the main README. Quartz Online is a second build of Quartz Editor with ComfyUI.

**Two apps from one project.** In Android Studio, pick the build variant (Build → Select Build Variant):
- **offlineRelease / offlineDebug** — Quartz Editor, as always: no internet at all.
- **onlineRelease / onlineDebug** — **Quartz Online**: everything above plus **ComfyUI** (your own AI
  server, below). It's a separate app (`com.tim.quartzeditor.online`), so both can be installed side by
  side; each has its own projects and settings (move work between them with project files). It talks
  only to the ComfyUI server you give it.

## ComfyUI
**The ComfyUI bar** floats at the bottom middle of the picture: **ComfyUI** opens the full window, and
the rest are **your favorite runs** as buttons under names of your own (Remove background, say). Each
button is a workflow with its prompt and settings saved with it, so a tap runs it straight away (the
result comes in as a new smart layer, as usual). Make them in the ComfyUI window under **Bar buttons**: set
a workflow up, then **Add this as a bar button…** (up to 4); there you can also rename them, move them up,
remove them, or **Update** one with what's set on the page. Hover with the pen to see a button's prompt.
View → ComfyUI bar shows or hides the bar. ComfyUI is also first in the Filter menu.

Menu → **ComfyUI…** opens a full-screen page that runs one of your ComfyUI workflows on your own
computer (over Tailscale, say) and brings back what it made as a new **smart layer** above the active
one, while you keep editing. A chip
at the bottom of the picture shows what it's doing (loading models, step 12 / 25…) with ComfyUI's live
preview, and **Cancel** (which stops it on the server too). If something goes wrong (the computer's
off, a model is missing) the chip says what ComfyUI said.
- **FLUX 3 box edits** (workflows with Create Bounding Boxes, like FLUX 3 Klein image edit): select the
  part to change first. The whole picture is sent, the selection's box becomes `<obj_1>` in the prompt
  ("change the dress <obj_1> to red"), and the result is kept to the selection (the rest stays at full
  resolution). With nothing selected, the box the workflow was saved with is taken out (it was drawn on
  another picture), so leave `<obj_1>` out of the prompt.
- **Improve the prompt first** also works for workflows whose If/Else switch is wired the other way round
  (the language model on its "false" side, as in FLUX 3's), and through Preview Any nodes on the way.
- **Upscales**: "A bigger result makes the image bigger" (for workflows that send a picture; on at first
  for ones named "upscale") enlarges the whole image to the result's size when it comes back bigger, so
  the new layer keeps every pixel (Image Size, one more undo step). Only when the whole picture was sent
  (not one small layer or a selection). It waits for anything still working; with the Crop or Transform
  tool just open (nothing changed) it goes ahead and opens them again after; if you're really in the
  middle of something (a crop you set, an adjustment being tried, text) it asks first. Off, it's fitted to the canvas (the
  full size stays inside the smart layer). Saved per workflow, in presets and bar buttons.
- The window's settings sit on a card; the whole width scrolls, not just the middle.
- **Server**: like `http://my-pc.tailnet.ts.net:8188` (**Test** shows the graphics card). ComfyUI has
  to listen on the network: start it with `--listen`. The app comes with Tim's server filled in.
- **My server | Comfy Cloud** (at the top) picks where it runs. Each has its own workflows (and presets);
  workflows added on a side belong to that side. **Comfy Cloud** (cloud.comfy.org) runs with your API key
  (from platform.comfy.org; kept on the tablet only, shown as dots unless you tap Show; **Test** checks it)
  and uses your Comfy Cloud credits. Jobs go through Comfy API v2 (submitted, then followed: its place in
  Cloud's queue, the node and step it's on); the pictures come back from Cloud's signed links; pictures sent
  to a workflow (edits) are uploaded first; the model lists come from Cloud. Live previews are for your own
  server only. One Cloud workflow comes with the app: Krea 2 with three LoRAs.
- **Switches**: a workflow's own on/off Boolean nodes (like "Enable LoRA 2?") and If/Else switches set right
  on the node (like "Use External Model?") show as switches.
- **The page**, top to bottom: the **workflow** (a drop-down list), **presets**, what it works on, the
  **prompt**, the **size** (for a new picture: 0.5 to 4 megapixels, the canvas's shape; it starts at the
  workflow's own Resolution Selector megapixels when it has one), **how many** (1 to 8 pictures, each coming in as
  its own layer. For a workflow making a new picture from an empty latent there's a choice: **One batch**
  sets the latent's batch size so a single run makes them all (the default on Comfy Cloud, where it costs
  less than separate runs), or **Separate runs** queues one run after another, each with new seeds (the
  default on your server, which then only needs the memory for one picture at a time). Edits always run
  once per picture), the **models**, the **LoRAs**, and the server. **Run** is at
  the top.
- **Models**: every loader in the workflow (diffusion model, checkpoint, text encoders, VAE, LoRA loader,
  upscale model) shows the file it uses; tap it for the list of what your ComfyUI has (with a search box)
  to pick another. Weight type, device and the like are under More options. LoRA loaders' strengths are
  sliders. Changed ones are tinted and have Reset.
- **Node settings** (folded away under the sampler, with how many you've changed): every other node's own
  numbers, on/off switches and short choices, custom nodes included (Krea 2 Edit's source patch: ref boost,
  fit mode; grounded encode's grounding size; skip-layer guidance's layers and range…). Choices come from
  your ComfyUI's list when it has one. Prompts, models, LoRAs, the sampler, switches, seeds and the batch
  size have their own places and aren't repeated here.
- **Sampler**: each KSampler's settings: **seed** (new each run by default; switch it off to keep one seed and
  type it), **steps**, **CFG** and **denoise** as sliders, and **sampler** and **scheduler** picked from your
  ComfyUI's lists. Changed ones have Reset, and they're kept per workflow like the models.
- **LoRA Manager**: its Lora Loader's list, with the ones switched on shown first (On only switches to
  all of them; Find searches): switch each on or off, set its strength and its **CLIP strength** (the text
  encoder's; it follows the strength until set apart), take it out (×), or **Add…** any LoRA your ComfyUI has.
  **CLIP strength 0 for every LoRA** (under the list) sets them all for models like Moss that want it at 0;
  LoRAs added after that get 0 too. Its trigger words node gets the trigger words as LoRA Manager works them out.
  When LoRAs are switched on but the loader only reaches the picture through an If/Else switch set the other
  way (a "Use External Model?" / "Use External CLIP?" left off), that switch is turned to the loader so the
  LoRAs apply (shown under Switches; switch it off there to send it as saved).
- **Image to prompt** workflows (a language model describing the uploaded picture): its instructions, and a
  second model rewriting that description, are left as they are; the prompt box only appears if the
  workflow also takes a typed prompt.
- **Saved prompts** (under the prompt): **Save prompt…** keeps the text under a name (its start, unless
  you give it one); **Saved prompts…** lists them (newest first, with a Find box): tap one to use it, **Add**
  puts it after what's typed (handy for style bits), × deletes it. They're shared by every workflow.
  **Workflow's own** puts back the prompt the workflow was saved with.
- What you change is remembered for each workflow (**Back to the workflow's own models and LoRAs** clears
  it). **Save as preset…** keeps the whole setup — workflow, prompt, enhancer, size, how many, what it
  works on, models and LoRAs — under a name; pick it from Presets to get it all back (× deletes one).
- **Workflows**: five come with the app (Qwen Image text to image, Moss text to image, Qwen Image
  edit, Qwen background remover, Z-image upscale). **Add workflow…** adds any other: in ComfyUI, save it with
  Workflow → **Export (API)** (the normal save format isn't accepted). Added ones can be removed.
- What a workflow does is worked out from its nodes:
  - **No Load Image** — makes a new picture the shape of the canvas (at the size picked, sides in
    multiples of 16, put into its Empty Latent Image), placed filling the canvas.
  - **A Load Image** — changes the **active layer** or **everything you see** (your pick), or just the
    selected part of it; the result goes where that was (a selection's shape becomes the new layer's mask).
  - **A Load Image whose mask is used** (an inpainting workflow) — **fills in the selection**: everything
    you see around the selection is sent, with the selection see-through (Load Image turns that into
    its mask), and the result comes back masked to the selection.
  - **The prompt** goes where the workflow keeps it: the text side of an If/Else prompt switch and its
    prompt enhancer (Text Generate), or the text node they both take it from, else the CLIP Text Encode titled Positive (or the first that isn't
    titled Negative), else a node's own prompt field. It starts as what you last typed for that
    workflow (or the workflow's own).
  - **Improve the prompt first** turns the workflow's prompt enhancer (the If/Else switch) on or off.
    Off, the enhancer is left out of what's sent, so ComfyUI doesn't run it and its model doesn't have
    to be installed.
  - A model the workflow names that your ComfyUI doesn't have is said plainly (which file, which node).
  - Every **seed** is new each run. Compare Images nodes are left out. The picture brought back is the
    one from its Save Image node.
- Pictures are sent at up to about 2 megapixels, sides in multiples of 16 (so the result lines up
  exactly), with see-through parts on white. Changing a layer that's partly see-through (a cutout)
  gives the result a mask, so it's see-through in the same places. The result keeps its full size
  inside the smart layer.
- A dropped connection now and then doesn't end a run (it keeps asking for up to half a minute); if
  ComfyUI is restarted or its queue cleared, the chip says it lost the job. Cancel takes it out of
  ComfyUI's queue, or stops it if it's running. Cropping or resizing the canvas while it works means
  the result isn't added (it would land in the wrong place).

## Code
- `app/src/offline/` and `app/src/online/` — what's different in the two apps: the offline one removes
  internet access; the online one adds it (plain `http://` allowed), its name, and the built-in
  ComfyUI workflows and server address (`assets/comfy/`).
- `engine/Comfy` — talking to ComfyUI and reading its workflows.
- `ui/ComfyActions` — ComfyUI runs, its window and status chip.
- `ui/ComfyBar` — the ComfyUI bar on the picture.

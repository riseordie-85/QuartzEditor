# Quartz Editor

A Photoshop-light photo editor for Android 13+, built from Quartz Gallery's editor. Kotlin + Jetpack Compose.

**Private by design:** no ads, no accounts, no tracking. The app has no internet access (it's removed
from the build). Background removal's model is fetched by Google Play services in its own process.

**Two apps from one project.** In Android Studio, pick the build variant (Build → Select Build Variant):
- **offlineRelease / offlineDebug** — Quartz Editor, as always: no internet at all.
- **onlineRelease / onlineDebug** — **Quartz Online**: everything above plus **ComfyUI** (your own AI
  server, below). It's a separate app (`com.tim.quartzeditor.online`), so both can be installed side by
  side; each has its own projects and settings (move work between them with project files). It talks
  only to the ComfyUI server you give it.

## Launch screen
- **Recent Photos in Gallery** (newest first; only pictures Android can show, not Photoshop files), **Projects** (your saved work, with all layers) or **Drafts** (shown when there are any: unsaved changes kept when the app closed; tap to open, hold to throw away). Tap to open.
  Long-press a project to delete it.
- **New image**: name, width and height in pixels (a menu of sizes: screen, square, story, HD, 4K, A4),
  portrait / landscape, background White, Black or Transparent. Up to 4096 px a side.
- **Open File Picker**: any image, a `.qedit` project file, a **Photoshop file** (`.psd`, `.psb`) or a
  **TIFF** (`.tif`, `.tiff`).
- **TIFFs** open whatever saved them: no compression, LZW, ZIP, PackBits or JPEG; 1 to 32 bits (including
  floating point); black and white, gray, palette, RGB, CMYK, YCbCr or Lab; with transparency; in strips
  or tiles; BigTIFF. A **layered TIFF from Photoshop** opens with all its layers, like a Photoshop file
  (everything below applies). Smaller previews saved inside the file are skipped. Pictures bigger than
  4096 px are shrunk to fit. TIFFs also work for Place image, pattern and sky photos.
- **Photoshop files** open with their layers: pixel layers, folders at any depth (Pass Through or with
  their own blend mode, open or closed, with their own layer styles), layer masks (and folder masks),
  vector masks (with their path operations), every blend mode, opacity and fill, clipping masks (to
  layers or folders), hidden layers, transparency locks and names. **Shape layers stay shapes**:
  rectangles, rounded rectangles and ellipses as those, other outlines as pen shapes (edit their points)
  or custom shapes, with their fill (color, gradient or pattern) and outline (solid, dashed or dotted;
  gradient or pattern outlines come in as pixels). Fill layers are drawn from their color, gradient
  (including Angle and Diamond) or pattern; a **book color** (Pantone and the like, whose recipe isn't in
  the file) is read from the layer's own pixels. **Adjustment layers** come in as ours: Levels, Curves,
  Brightness/Contrast, Exposure, Vibrance, Hue/Saturation, Color Balance, Black & White, Photo Filter,
  Gradient Map, Invert, Channel Mixer, Selective Color, Color Lookup (when the file keeps its lookup
  table), Posterize and Threshold (with their masks, opacity and clipping). Layer styles come
  across as ours: Drop Shadow, Inner Shadow, Outer Glow, Inner Glow, Satin, Stroke (color or gradient),
  Color Overlay, Gradient Overlay, Pattern Overlay (the file's patterns are added to your patterns) and
  Bevel & Emboss.
  **Text layers stay text** (like Photoshop, they show the file's own pixels until you edit them): the
  words, size, color, alignment, tracking, leading, caps and faux bold/italic, point or box text (box text
  wraps to its box), and **warped text** (all 15 of Photoshop's warp styles, with bend and distortion). Text with **several styles** (a word in another font, size or color)
  keeps them; typing carries the style of the letter before, and changing the font, size or color in
  Properties changes all of it (sizes keep their proportions). The font is the same Google Font when it is one (any weight), a font
  you imported with that name, or a close free match for common ones (Arial → Arimo, Helvetica Neue →
  Inter, Gotham/Proxima Nova → Montserrat, Futura → Jost…); editing says when it isn't the original.
  **Smart objects stay smart**: embedded pictures (PNG, JPEG, …) and embedded Photoshop files become the
  smart layer's contents, placed where they were (edit them in their own tab); so do embedded TIFFs,
  with their layers when they have them. **Copies of one smart
  object stay linked**: editing one changes them all. **Warps stay warps** (a named one like Arc or Flag,
  a custom curve grid, or a split grid) and **smart filters stay smart filters** (Gaussian Blur, Blur,
  Unsharp Mask, Smart Sharpen, Sharpen, Add Noise, with their strength), both still editable. A smart
  object linked to a file outside the .psd (or with a filter we don't have) keeps what you see as its
  contents; **Replace contents** puts the original file back in. 8 and 16 bits; RGB, grayscale, CMYK
  (roughly), Lab, Indexed, Bitmap (black and white) and Duotone (as its gray). Files bigger than 4096
  px are shrunk to fit. Afterwards a short list says what didn't come across exactly (blend modes on
  effects that only layers have, filters we don't have, a Duotone's inks…).
- **Very big Photoshop files and TIFFs** (over 300 MB) ask first: they can take minutes and may close the app on a
  phone or tablet. **Open anyway** goes ahead.
- Other apps can **Open with**, **Edit with** or **Share to** Quartz Editor.

- If the app closed unexpectedly, the next launch says why (a crash, or Android closing it for memory),
  with **Copy report** for sending the details.

## Workspace
Tools on the left, the picture in the middle, panels on the right. On phones the panels slide in from the
right (the arrow button at the bottom right); on tablets and the unfolded Fold they sit beside the picture
and the same button hides them.

### Menu bar layout (big screens)
On a big screen (a tablet in landscape, 900 dp wide or more) the editor uses a desktop-style layout made
for a pen; Settings → Appearance → Layout switches it (Automatic, Menu bar, Classic).
- **Menu bar** at the top: **Quartz** (Home, Quick export, Settings), **File**, **Edit** (undo/redo,
  cut/copy/paste, fill, free transform), **Image** (size, crop, rotate, flip, Enhance, flatten), **Layer**
  (new, folders, adjustment layers, duplicate, delete, rename, merge, clip, layer style, mask, fill/clear,
  convert, place, flatten), **Select** (all, deselect, invert, subject, background, modify edge, to layers
  and shapes, mask, pattern, sky, color range, the layer's pixels, crop), **Filter** (ComfyUI first in the
  online app, then the adjustments and filters on the layer, Enhance),
  **View** (fit, 100 %, zoom, canvas turning, fingers, snap, grid, rulers and guides) and **Window** (show
  or hide the panels and each section, keep sections open). The image's name and undo/redo are at the right.
  A menu's pages open as **columns beside it** in the same menu (tap, or hover with the pen): nothing
  covers the menu you came from, and menus are as tall as the screen allows.
- **Options bar** under it: the tool in use and its settings.
  - Brushes: tip, size, opacity (strength), softness, smoothing, taper in/out, the pen's pressure
    switches, and **Side sliders** (turns the tall sliders beside the tools on or off; also in View).
  - Select: its bar (modes, select all, deselect, invert, ⋮), with **Subject**, **Background**, **Sky** and
    **Color range** always at the right.
  - Transform: flips, turns, Free / Skew / Perspective / Distort, then **Warp** and **Puppet Warp** (the
    transform so far is applied, then the warp starts).
  - Shape: **fill** (color, on/off), **stroke** (color, on/off, width in px, solid / dashed / dotted) and a
    rectangle's **corner radius** (all corners), for the shape layer you're on or new shapes.
  - Text: **font**, **weight** (a downloaded font's own weights and italic; other fonts get bold and
    italic), **size**, **left / center / right**, **color**, and a text layer's **warp**.
  - Eyedropper: **sample size** (point, 3×3, 5×5, 11×11, 31×31, 51×51, 101×101, averaged) and **All
    layers** (off: just the colors on the layer you're on). Also in Properties.
  - Crop, warp and pen show their bars here; the text field stays over the picture.
- **Toolbar**: as before, with tall **size and opacity** sliders beside it for the brushes (can be turned off).
- **Panels**: drag their left edge to make them wider or narrower (remembered). **History** is a panel
  section of its own (on phones too): tap a step to go back to it.
- **Status bar** at the bottom: zoom (tap for Fit and 25–400 %), how far the canvas is turned (tap to
  straighten) and the turning lock, the canvas size, the layer, the finger-painting switch, and show/hide panels.
- **Open files in tabs**: the menu bar lists every open file, each followed by its smart layers' tabs
  (a smart layer's contents being edited). Tap one to show it; × closes it (with unsaved changes it asks: Save,
  Don't save or Cancel; a smart layer's edits go into the layer). **+** (or File → New or open…, or Quartz →
  Home) shows the start screen with your files still open: what you open or start there joins them as a
  new tab, opening a project that's already open just shows it, and "Open:" at the top (or Back) goes
  back (× on a chip closes that file). Each file keeps its own undo, view, workspace color and tabs; the
  changes of the one you leave are autosaved to Drafts. Up to 5 files at once (opening a sixth closes the one left
  longest ago; its changes are in Drafts). Open files share one undo memory budget, most of it for the one shown.
  File → Close project asks about unsaved changes and closes the one shown (the next one comes up). Home and Back (on phones too) go
  to the start screen with the files kept open. Deleting a project that's open closes it.
- **Squarer look**: the bars, toolbar, panels and picture have only slightly rounded corners and sit
  closer together.
- **Pen niceties** (everywhere): hover over a button for a moment to see its name; the pen's side button
  (or a mouse's right button) on a layer opens its menu, like a long press.

### Toolbar
Top to bottom: ☰ Menu, Move, Select, Transform (and the warps), Crop (and Perspective crop), Healing, Fill, Toning, Pen, Shape (hold for Custom shapes), Text, Brush/Eraser,
Eyedropper, then the colors. Buttons with a dot in the corner hold more than one tool: **long-press** for
the list; the button shows the one you used last.
- **☰ Menu** — Home, then pages: **File** (New…, Open file…, Open image…, Save project, Export image…,
  **Quick export** (the big colored one), Rename, Save a copy as a project file…, Close project), **Image** (its size in pixels, **Image size…**, Rotate, Flip, Flatten), **Enhance** (see
  below), **View** (Snap, Checkerboard, Modifier button, **Grid** and
  **Rulers and guides** pages), and Settings. Tap a page to open it; ← goes back.
- **Move** — drag the selected layer (a folder moves everything in it); tap the picture to select the top
  layer under your finger. Tap Move again to switch to **Pan** (one finger moves the view). While moving
  (and moving a Transform box), the layer's edges and middle **snap** to the canvas's edges and middle,
  with a pink line showing where. Hold ⇧ to move straight across (or up and down): then only the layer's
  middle snaps, to the canvas's middle. Menu → View → "Snap to center, edges and guides" turns it off.
- **Select** — tap again to switch shape: Rectangle → Ellipse → Lasso → Polygonal lasso → Magic wand →
  **Object (AI)**: tap an object, or drag a box around it, and the on-device Segment Anything model
  (MobileSAM) outlines it (the first time on a picture it takes a moment to look; after that it's quick).
  **Expand / Shrink** (Properties, or −10…+10 in the bar's ⋮ → Modify edge) grows or shrinks the selection by pixels. Drag
  to select (polygonal: tap corners, tap the first one or Close to finish; two-finger tap removes the last
  corner). **Magic wand**: tap a color; Properties has tolerance, "only touching pixels" and "look at all
  layers". **Select subject** / **Select background** / **Invert** (Properties, ⋮ on the bar, or hold
  the Select tool) find the subject on the
  device: a first look at the whole picture, a second, closer look at the area around the subject, then
  the edge is snapped to the picture's own edges (hair and fine outlines follow what's really there). Remove
  background uses the same. The bar on the picture: New / Add / Subtract / Intersect, Select all, Deselect, Invert, and ⋮ with
  pages for Modify edge (Feather, Expand / Shrink), Select (subject, background, sky, color range, the
  layer's pixels), New layer (Copy or Cut, or
  **Shape from selection**), Fill and remove (Fill, Delete selected pixels, AI Remove selected), then Mask
  from selection, Define pattern and Crop to selection.
  - **Select sky** finds the sky with the on-device depth model (like Replace sky), soft at its edges.
  - **Color range…** (like Photoshop's): a sampled color (the foreground color, or "Pick on the picture"
    and touch it) with **Fuzziness**, or Reds / Yellows / Greens / Cyans / Blues / Magentas, Highlights /
    Midtones / Shadows, or Skin tones; every layer or just this one; Invert. A black-and-white preview
    shows what it picks out (white is selected), softly at the edges; it combines by the selection mode.
  - **Layer's pixels** selects what's on the layer you're on (like Ctrl-clicking its thumbnail).
  - **Shape from selection** traces the selection's edge (where it's at least half selected; any kind of
    selection, Select subject and the magic wand too) into a shape layer filled with the foreground color.
    Straight runs stay straight, sharp turns stay corners, the rest become smooth curves. One outline makes
    a pen shape (Edit points works on it); several, or one with holes, make one shape of all of them.
  Brushes, fills, gradients and adjustments only change what's selected. Tap without dragging to deselect.
  While something is selected, two round buttons show at the bottom right of the picture (beside the panels
  button): **delete** deletes the selected pixels on the layer you're on (on a mask, it shows them again),
  and **deselect** (the dotted square) drops the selection, whatever tool you're on.
- **Crop** (its own button; hold it for Perspective crop) — drag the corners, edges or the box; pick a shape (Free, Original,
  1:1, 4:3, 3:4, 16:9, 9:16). Dragging past the canvas adds space. Cropped-off pixels stay on their layers.
- **Image size** (Menu → Image → Image size…) — shows the picture's size, layer count and memory, and
  scales everything to a new size: width and height (linked or not), 25/50/75% or a long side of
  2048/2560/3000 px. Every layer, mask, shape, text (still editable), smart layer and guide scales; "Scale
  layer styles" keeps stroke widths, shadows and glows looking the same. Shrinking a lot is done in
  halving steps, so it stays smooth. One undo step.
- **Perspective crop** (hold the Crop button) — drag the four corners onto the corners of something
  slanted (a sign, a window, a screen, a photo of a flyer); the grid follows. Apply straightens it into a flat,
  square-on picture (its size is the average of opposite sides). Shapes and text stay editable; guides and
  the selection are cleared.
- **Transform** — a box around the selected layer: drag inside to move, corners to scale (proportions
  locked unless you unlink them; ⇧ always keeps them), edges to stretch, outside the box or the top handle
  to rotate (snaps to straight angles; ⇧ in 15° steps). The bar on the picture has flip, rotate 90°, **Skew** (slide a side or corner along
  itself), **Perspective** (drag a corner; the other end of that side moves the opposite way), **Distort**
  (corners anywhere) and Apply. With several layers picked, they transform together.
- **Warp / Puppet Warp / Liquify** — hold the Transform button to pick one (it works on the selected pixel
  layer; shapes and text ask to be rasterized). The bar on top has Cancel, Undo (steps back one change; so do
  Ctrl+Z and a two-finger tap), Reset and Apply. All three are shown on a smaller copy while you work and
  applied at full size (the mask bends too) as one undo step.
  - **Warp:** a 4×4 grid of curve points. Drag a point, or anywhere on the picture to bend it right there.
    Ready-made shapes (Arc, Bulge, Flag, Wave, Squeeze, Inflate) with a Bend slider; dragging after one
    carries on from it.
  - **Puppet Warp:** tap to add pins, drag a pin to bend the picture around the others (as rigidly as it
    can). Tap a pin to pick it; the bin deletes it. Stiffness sets how far each pin's pull reaches.
  - **Liquify** (Ctrl+Shift+X): Push, Bloat, Pucker, Twirl both ways, Smooth and Restore brushes. Size
    ([ and ]) and Strength are in the brush column beside the tools, like the other brushes. Bloat, Pucker and Twirl keep working while you hold still. The picture's edges
    stay put, so an edge-to-edge photo never pulls in empty space.
    **Face-aware**: "Find faces" looks for faces on the device (through Google Play services), then sliders
    reshape each face: Eyes (size, height, width, tilt, distance), Nose (height, width), Mouth (smile,
    upper and lower lip, width, height) and Face shape (forehead, chin height, jawline, face width). With
    several faces, pick which one; the brushes and the sliders work together.
- **Eyedropper** — touch and drag; lifting picks the color into the swatch you're editing.
- **Paint bucket / Pattern fill** — tap to fill matching pixels with the foreground color, or with a
  pattern (tap the tool again, or hold it, to switch). Tolerance, "only touching pixels" and "look at all
  layers" are in Properties. With Pattern picked, Properties shows every pattern: 12 ready-made ones drawn
  in your foreground and background colors, and your own (hold one to delete it; **+** adds one from your
  photos). Size goes from 12% to 400%, and patterns line up with the canvas's corner on any layer.
  "Make from selection" saves what you see inside the selection (or the whole picture) as a new pattern;
  "Fill selection" / "Fill layer" fills with the pattern straight away (also in the Select ⋮ and
  Layers ⋮ menus). On a mask, light parts of the pattern show and dark parts hide.
- **Gradient** — drag on the picture: the gradient runs from where you start to where you let go. Presets,
  Linear / Radial / Reflected / Angle / Diamond, reverse and opacity are in Properties. **+** makes your own gradient
  (starting from the one picked): tap the bar to add a color, drag the markers to move them, tap one to
  change its color, location or opacity (see-through stops work), Reverse, Even spacing, a name. Hold one of
  yours to edit or delete it. Your gradients are also in Layer Style → Gradient Overlay (with its own **+**).
- **Shape** — drag to draw a Rectangle, Ellipse, Triangle, Polygon (number of sides), Star or Arrow (from
  where you start dragging to where you stop) on a new shape layer; tap the tool again for the next shape,
  or hold it for the list. In Properties: fill (a color, a gradient with its type, angle and scale, or a
  pattern) and outline (on/off, color, width, inside/center/outside, solid / dashed / dotted with dash and
  gap), corner radius,
  the star's points and depth (no depth makes a polygon), **Flatten points** (flat tips and valleys: cogs
  and gears, square teeth at 100%), and "same width and height". New shapes always
  start filled without an outline (colors carry over). Tap a shape with the tool to pick it; with a shape
  layer selected, Properties changes that shape.
  **3D** (shape and text layers, in Properties): pushes the layer back into a solid and turns it in
  space. Looks: Classic, Deep, From above, From below, Side on, Flat block; then Depth, Tilt, Turn,
  Perspective, Light angle and height, Shading, and the sides in the layer's own colors or one color.
  It stays editable (change the shape, the text or the font and the solid follows); layer styles (a
  drop shadow, say) go around the solid, and Transform scales its depth along.
- **Custom shapes** (hold the Shape tool → Custom shapes; tap Shape again to go back) — the shape library in Properties: hearts, stars, speech and
  thought bubbles, clouds, arrows, check marks, rings and frames, pins, tags, ribbons, badges and bursts,
  cogs and more. Pick one and drag on the picture ("Keep its proportions" keeps its shape). **Add an
  .svg…** adds your own (paths, rectangles, circles, polygons, groups and transforms; its colors aren't
  used, and line drawings come in as outlines); hold one of yours to remove it. With a custom shape layer
  selected, tapping a shape changes it.
- **Shape pen** — tap to place corners; drag as you place a point to bend the line into a curve. Tap the first
  point (or Close shape) to finish, or Open line for an unclosed line. Two-finger tap or the bar's undo
  removes the last point. **Edit points** comes on after finishing (or tap the pen again, or Edit points in
  Properties): drag points and curve handles, tap the outline to add a point, tap a point to pick it
  (then Corner / Curve or Delete), or double-tap a point to switch it between a corner and a curve. Done (or the pen again) goes back to drawing.
- **Curvature pen** (hold the pen button, or the Pen / Curvature pen switch in Properties; Shift+P) — tap to
  place points and the line curves smoothly through all of them, reshaping as you go. Double-tap any point
  to make it a sharp corner (a diamond), again for smooth; drag a point to move it (or hold, then drag). Tap
  the first point to close the shape (so it's the one point that can't be switched while drawing; carrying a
  shape on, its own end stays as it is). Undo (two-finger tap) takes back the last change: a point, a move
  or a switch.
- With the **Pen** too, while drawing: double-tap a point to switch it between a corner and a curve, and drag
  a point to move it (dragging on the first point still closes the shape with a curve there).
- **Adding to a shape** with either pen (while drawing, with a pen shape selected): tap its outline to add a
  point there (the shape stays the same; point editing comes on so you can drag it), or tap one of an open
  shape's ends to carry it on — the new points join that shape, and tapping its other end closes it.
- **Text** — tap the picture and type (the bar at the top has the text box, Cancel and Done). Tap existing
  text to change it; drag with the Text tool to move it. Properties: font (Android's, Google Fonts, or your
  own .ttf/.otf), size, color, alignment, faux bold/italic, underline, ALL CAPS, letter and line spacing,
  and **Warp text**: Arc, Arc Lower, Arc Upper, Arch, Bulge, Shell Lower, Shell Upper, Flag, Wave, Fish,
  Rise, Fisheye, Inflate, Squeeze, Twist, with Bend, Horizontal and Vertical distortion, bent along the
  width or the height (the text stays editable).
  Outline, shadow and glow come from Layer Style (fx). **3D** works on text too (see Shape).
- **Gradient / Paint bucket / Pattern fill** share a button (tap again for the next one). It shows the one a
  tap picks: the one in use, or the last one you used.
- **Brush** and **Eraser** (one button) — tap it to switch between the brush and the eraser. While either is
  picked, a second column shows the tips (Round, Square, Triangle, Grunge) and the Softness, Size and
  Opacity sliders; any other tool folds it away.
- **Healing** (Spot healing, AI Remove, Clone stamp) and **Toning** (Blur, Sharpen, Smudge, Dodge, Burn)
  are two buttons (hold for the list; Size, Softness and Strength in the brush column):
  - **Spot healing** — paint over a spot (up to about 250 px across; a message says when a stroke grows past
    that); when you lift your finger it's filled with texture from the best-matching place nearby and its
    color and light are evened out to blend in.
  - **AI Remove** — paint over something (include its shadow); the on-device MI-GAN model fills in what
    would be behind it. Also Select ⋮ → AI Remove selected.
  - **Clone stamp** — hold ⇧ and tap where to copy from (or Pick source), then paint; slide out to Alt for
    a straight line. Aligned keeps the source moving with each new stroke. A crosshair shows where it copies
    from.
  - **Dodge / Burn** (Shadows / Midtones / Highlights), **Smudge**, **Blur**, **Sharpen**. Smudge drags paint
    into empty parts of the layer too (not with the transparency lock on).
  - "Look at all layers" (healing, AI Remove, clone): read the picture as you see it and paint onto the
    layer you're on — an empty layer keeps the photo untouched.
- The toolbar's buttons shrink to fit the screen's height (it only scrolls on very short screens).
- **Colors** — foreground (top) and background. Tap one for a color picker beside the toolbar; swipe up or
  down on them to swap.

### On the picture
- **Cut, copy, paste** — the button at the bottom left (or Ctrl+X / C / V). Copy takes what's selected on
  the layer (or the whole layer with nothing selected) as you see it on that layer; **Copy merged** (Ctrl+Shift+C)
  takes everything you see. **Paste** puts it on a new layer right where it came from (in the middle if
  that's off the canvas) and picks Move to place it; it pastes pictures copied in other apps too. Cut takes
  the selected pixels away (or the whole layer). Also New layer via copy / cut, Select all, Deselect.
- One finger: the tool. Two fingers: move, pinch-zoom and **twist to turn the canvas** (it snaps straight near
  0°/90°/180°/270°; a quick pinch in fits it on the screen, straight). While it's turned, a chip at the top
  right shows the angle (tap: straighten). View → Lock canvas turning (or Settings → Pen) stops two fingers
  turning it. Rulers hide while it's turned. **Two-finger tap: undo. Three-finger tap: redo.**
- **Brush library** (the brush's name in the options bar, the **Brushes** panel section, or "Brushes and
  Brush Studio…" in the brush column's sliders button): ready-made brushes, each shown as a sample
  stroke. Basics (Hard round, Soft round, Airbrush), Sketching (Pencil HB, 6B pencil, Charcoal), Inking
  (Ink pen, Fineliner, Brush pen, Marker), Painting (Soft paint, Gouache, Chalk, Dry brush) and Texture
  (Fur, Grass, Spatter, Grunge), then **Your brushes**. Picking one gives the brush (or the eraser) all its
  settings; "•" by the name means you've changed it since. Hold a brush (or use the pen's side button)
  to edit it in Brush Studio, duplicate it, or rename / delete one of yours.
- **Import Photoshop brushes (.abr)**: File → Import brushes (.abr)…, or Import .abr… in the brush
  picker and the Brushes panel. Each sampled tip in the file becomes a brush of yours (in a group named
  after the file, under "Imported"), with its own tip picture, its size (up to 300 px) and its spacing;
  edit them in Brush Studio like any other. (Older v1/v2 files and the usual v6+ files; brushes that are
  only computed shapes, with no picture, are skipped.)
- **Brush Studio**: every setting of the brush in use, with a sample stroke that follows, on pages:
  **Basics** (tip: Round, Square, Triangle, Grunge, Chalk, Spatter, Blade, Bristles; size, opacity,
  **flow**, softness), **Stroke** (spacing, smoothing, tapers), **Shape** (angle, roundness for chisel
  tips, turns with the stroke, size and angle jitter, scatter), **Grain** (Paper, Canvas, Charcoal or
  Noise, its depth and scale: the texture stays put on the canvas like paper's tooth) and **Pen**
  (pressure on size / opacity / flow; tilt: nothing, **stretch** like a marker on its side, or **shade**:
  bigger and lighter like the side of a pencil). Changes apply straight away; **Save as new brush…**
  puts them in your library, **Update** saves them into your brush, **Back to** undoes them.
  Flow is how much each dab adds: low flow builds up within a stroke (never past its opacity).
- **Smoothing and taper** (the sliders button under a brush's sliders, for the brush, eraser and retouch
  brushes each): Smoothing makes the line trail the pen or finger on a "string" so wobbles smooth out (it
  catches up when you lift). Taper at the start / end makes strokes grow from a point and thin out to one,
  with a pen or a finger (the end thins out when you lift; short strokes taper over their second half).
- **A pen (stylus)** works like Photoshop on a tablet. **Pressure** changes the brush's and eraser's size
  (down to a smallest size you set) and, if you want, its opacity; for the retouch brushes it changes the strength.
  Each brush has small pen switches under its Size and Opacity sliders; they appear once you've used a pen.
  **Tilt** the pen to stretch the brush along the way it leans, like the side of a pencil. The **eraser
  end** erases with any tool, using the eraser's settings. The **side button** picks a color while held, by
  default; it can erase or undo instead. Hovering shows the brush's outline. Once a pen's been used,
  one finger moves the view and two zoom, instead of painting, and **holding a finger on the picture picks
  its color**. The hand button under the brush sliders (or View → Fingers paint) lets fingers paint again. Touches are ignored while the pen is near
  the screen, so your hand can rest on it, and a pen stroke can't be turned into a pinch. All in Settings →
  Pen and drawing, including the **pressure curve**.
- **Draw and hold (QuickShape)**: draw a line, a few straight lines, an arc, an ellipse, a rectangle or a
  triangle and keep the pen (or finger) still at the end for a moment: the stroke snaps to that shape, drawn
  again with the same brush at its usual pressure. Keep holding and move to size and turn it (a line's end
  follows the pen). Once it's snapped, **Shift**, the Modifier button or **a finger put on the screen** makes it perfect: a circle, a square,
  an even triangle, lines in 45° steps (shapes then turn in 15° steps). Lift to keep it; undo takes it away.
  Settings → Pen and drawing turns it off and sets how long to hold.
- **ColorDrop**: drag the foreground (or background) color sideways off its swatch onto the picture and let
  go: the area there fills with it, like the paint bucket (on the layer you're on). A bar at the bottom then
  shows the **tolerance**: slide it and the fill is done again taking in more or fewer colors; Done (or doing
  anything else) puts it away.
- **Modifier button** (the floating emblem button; drag its grip to move it; ☰ → Modifier button turns it off): hold it
  for **Shift** (the button fills with your accent color), slide out past its edge for **Alt** (the emblem turns the accent color), double-tap to keep Shift on. A keyboard's Shift and
  Alt do the same. Brushes: straight lines at 0°/45°/90° (tap: a line from the last stroke). Shapes and
  rectangle/ellipse selections: square or circle (Alt: from the middle). Transform: keep proportions,
  rotation in 15° steps (Alt: resize from the middle). Move: straight across or up and down, snapping only
  middle to middle (Alt: move a copy). Lasso, wand, Object: add (Alt: take away). Gradient: 45° steps.
  Crop: keep the shape (Alt: from the middle). Clone stamp: ⇧ + tap sets the source, Alt draws straight.
- **Rulers and guides** (☰ → View → Rulers and guides, Ctrl+R): drag out of the top ruler for a horizontal
  guide, the left one for a vertical guide. While dragging, a guide snaps to the canvas's edges and middle
  and to the selected layer's edges and middle. With **Move or Pan**, drag a guide to move it, or off the
  picture to remove it. The same page has Show guides (Ctrl+;), Lock guides, New guide… (exact pixels or
  a percent), **Edit guides…** (every guide with its position: type a new one or remove it) and Clear
  guides. Things snap to guides when snapping is on; guides save with the project and follow crop, rotate
  and flip.
- **History** (the page button next to undo/redo): every step, oldest first; tap one to go back (or
  forward) to it.
- **Zoomed out** (below about 60%), the picture is drawn from half- or quarter-size copies of each layer,
  made in the background and kept up to date, so big pictures with many layers stay smooth; exports,
  merges and everything else always use full size. The picture is kept as a finished image between
  changes, so scrolling the layers or sliding panels doesn't draw every layer again, and layer
  thumbnails are made in the background.
- **Quick pinch inward** fits the picture to the screen (or use the fit button at the top right).
- **Grid** (menu → Show grid, or Ctrl+'): evenly spaced lines from the canvas's top-left corner. Grid
  settings: spacing in pixels (or split the canvas into 2–12 equal parts), subdivisions, color, and Snap to
  grid — moving layers and Transform boxes, shape corners and pen points snap to its lines while it's shown.
- **Long press beside the picture** to change the workspace color. Menu → "Checkerboard for see-through"
  switches the transparency checkerboard off (see-through areas show white).
- Undo, redo, history and fit buttons at the top right; the ☰ menu is at the top of the toolbar.
- **Sliders** have their number in a box beside them: tap it and type an exact value (90°, 50%, 12 px).
- **Panels hidden** (the arrow at the bottom right, or Tab): the layers show as a short column at the right
  edge, like Photoshop on phones: three at a time, scroll for the rest. Tap one to pick it, hold one to hide
  or show it; + adds a layer, a picture or a folder. Panels slide in and out.

### Smart layers
- **Placing an image** (Menu → Place image, or + on the layer stack) makes a smart layer at the image's full
  size and starts **Transform** on it: size and place it, then Apply. Scaling it down and back up later
  loses nothing, because it's always drawn again from the full-size picture inside.
- Any layers can become one: hold a layer (or pick several) → **Convert to smart layer**.
- **Double-tap** a smart layer (or hold → Edit contents) to edit what's inside in a **tab** of its own at
  the top; it has its own layers and undo. Tap the picture's tab to go back: the smart layer updates (one
  undo step there). ✕ on a tab closes it the same way. Smart layers inside smart layers open more tabs.
- Transform, rotate/flip image, masks, clipping and layer styles work on smart layers as they are.
- **Smart filters**: adjustments and filters (any of the windows under "On this layer", Gaussian Blur,
  Filters) on a smart layer are kept as smart filters instead of changing pixels (they change the whole
  layer; a selection doesn't limit them). Hold the layer →
  **Smart filters** lists them (last applied first): Edit (opens its window as it was), Hide / Show,
  Strength (100, 75, 50, 25%) and Delete; Hide all / Delete all. Editing the contents keeps them.
- **Warp** on a smart layer bends it without touching the contents (still editable: Warp again carries
  on from it; hold → Convert → Remove warp). A named or split warp from Photoshop shows as the nearest
  single curve grid when you Warp it again, and changing it replaces the original. Puppet Warp, Liquify and painting ask to **rasterize** first.
- **Replace contents…** (hold → Convert) swaps what's inside for a picture, Photoshop file or TIFF, at the
  same scale and place, keeping its warp and smart filters; linked copies change too.

### Panels
The column holds **Color**, **Properties**, **Brushes**, **History** and **Layers**, and you arrange it:
- **Move** a section: drag its header up or down; a line shows where it will go.
- **Stack** sections as tabs: drag a header onto the middle of another's header (it's outlined; near a
  header's top or bottom edge it moves instead). Tap a tab to show it; a section that opens by itself
  (Properties when you pick a tool) comes to the front of its tabs;
  drag a tab out (to a line between sections) to give it a place of its own again.
- **Sizes**: drag a line between sections (it has a small grip when it can be dragged) to give the open
  section above it more room and the open one below less (folded ones in between don't count); each keeps
  the height you gave it. Double-tap the line: back to automatic (as tall as needed).
  The section showing Layers always takes whatever room is left.
- It's all remembered; **Window → Reset panel layout** puts everything back.
- **Color** and **Properties** fold away by themselves: picking a tool opens Properties with that tool's
  settings (selection shape and mode, crop ratio, transform numbers, bucket tolerance, gradient presets
  and type, brush tips and opacity), and tapping a color swatch opens Color. The **pin** on a section's
  header keeps it open. With Move, Properties shows the selected layer (name, position, mask).
- **Color** — saturation/brightness square, hue bar, hex, RGB, recent colors and basics. **Swatches**: every
  color picker (here and in shapes, text and layer styles) has a Picker | Swatches switch. Swatches has
  ready-made sets (Basics, Grays, Vivid, Pastels, Earth tones, Skin tones, Web) and yours: **+** adds the
  current color to My swatches (hold one to remove it), and **Import** reads Photoshop's .aco and Adobe's
  .ase swatch files (RGB, CMYK, Lab and gray colors; each group becomes a set).
- **Layers** — like Photoshop, the settings on top (blend mode, opacity, locks, fill) and the buttons at the
  bottom stay put; only the list between them scrolls. It fills the room the other open sections leave
  (keeping enough for a few layers; the buttons always stay).
  Tap its header to fold it too.
  - All of Photoshop's blend modes (Normal, Dissolve, Darken, Multiply, Color Burn, Linear Burn, Darker
    Color, Lighten, Screen, Color Dodge, Linear Dodge, Lighter Color, Overlay, Soft Light, Hard Light, Vivid
    Light, Linear Light, Pin Light, Hard Mix, Difference, Exclusion, Subtract, Divide, Hue, Saturation,
    Color, Luminosity), Opacity and Fill. (Effects and adjustment layers offer the ones Android draws itself.)
  - Lock transparent pixels and lock the whole layer.
  - Each layer: show/hide, thumbnail, mask thumbnail, name, drag handle to reorder (drag near the top or
    bottom of the list and it scrolls; only the rows on screen are built, so hundreds of layers stay
    quick, and each picture keeps its place in the list when you come back from a smart layer's tab). **Long-press** a layer
    for Rename, **Select pixels** (its pixels as a selection, like Ctrl-clicking a thumbnail in Photoshop; with ⇧
    added to the selection, with Alt taken away), Duplicate, Merge down, Clip to layer below, and pages for **Layer style** (Copy / Paste,
    Save as preset, Apply / Clear) and **Convert** (smart layer, Edit contents, Unlink from copies, Rasterize).
    Duplicating a smart layer makes a **linked copy** (like Photoshop): editing either changes both; the
    row says "Linked to n others", and **Unlink from copies** gives one its own contents.
  - **Pick several layers**: swipe right on a row (again to take it out). Move, Transform, Delete, put in a
    folder (Ctrl+G, or the folder button), merge (Ctrl+E) and pasting styles work on all of them. Tap any layer (or "n picked ✕")
    to go back to one.
  - **Folders**: the arrow folds/unfolds them. Drag a layer onto a folder's row (it lights up) to put it
    inside, or just under the folder's row; drag it below the folder's contents to take it out.
    Folders are **Pass Through** by default (top of the blend list): Multiply, Overlay and adjustment
    layers inside them reach the layers below the folder too. Pick any other blend mode to keep what's
    inside to itself (the folder is drawn as one picture, then blended). A Pass Through folder with
    less than 100% opacity or a mask is drawn as one picture (blended Normally).
  - **Clipping masks**: ⋮ → **Clip to layer below** (or Ctrl+Alt+G) shows a layer only where the layer
    under it has pixels: a texture or photo inside text, a color only on one object, an adjustment that
    only changes one layer. The base can be a **folder** (clipped to everything in it). Clipped layers show
    an arrow; stack several on one base. The base's opacity and blend mode apply to the whole group. Merge
    down a clipped layer to bake it in.
  - **Folders can have layer styles** (fx with the folder picked): everything in it gets them as one
    picture, like Photoshop.
  - **Adjustment layers**: change everything below them without touching pixels; paint their mask to limit
    them. With a selection, the new layer's mask shows only what's selected (like Photoshop). Tap one's icon
    to edit it.
  - **Shape and text layers** stay editable: change them in Properties, scale, rotate or distort them with
    Transform (they stay sharp), add masks and layer styles. Painting, fills, filters and adjustments ask to
    **rasterize** them first (turn them into pixels); ⋮ → Rasterize layer does it any time. Text layers
    show a T; tap it on the selected layer to edit the text.
  - Bottom buttons: **fx** (Layer Style), mask, **adjustments** (two pages: New adjustment layer, or On
    this layer, then the window for the one you pick), new folder, new layer, delete, and ⋮ (Duplicate,
    Merge, Folder, Clip, Rename, and pages for Fill and clear, Mask and background, Convert). Adjusting On
    this layer with a selection only changes inside it (a blur doesn't spread past its edge); the window
    says so.
  - **Hold a mask's thumbnail** for: Mask as selection, Replace with selection, Invert, Enable/Disable,
    Clear, Apply and Delete.

### Floating windows
**Layer Style**, **Adjustments**, **Levels**, **Curves**, **Gaussian Blur** and **Filters** open in a window
(from the bottom of the Layers panel). With the panels docked on a tablet, the window takes the panels'
place, so the picture keeps its room; closing it brings the panels back as you left them.
- **Layer Style** — **Presets** at the top (ready-made ones like Soft shadow, Sticker, Neon, Gold, Chrome,
  and your own: "+ Save" saves the current style; hold one of yours to delete it).
  **Blending Options → Blend If** (gray), like Photoshop's: **This Layer** hides the layer where it's darker
  or lighter than its black and white ends; **Underlying Layer** hides it where what's below is. Each end
  is a pair of values: the same value cuts sharply, spread apart it fades between them (Photoshop's split
  sliders). Reset Blend If puts it back.
  Then the effects, in Photoshop's order: Bevel & Emboss (inner/outer bevel, emboss, pillow; up/down, depth,
  size, soften, light angle and altitude, gloss contour, highlight and shadow colors), Stroke (outside,
  inside, center; a color or a gradient), Inner Shadow, Inner Glow (from the edge or the center, size,
  choke), Satin (angle, distance, size, invert), Color Overlay, Gradient Overlay (any gradient,
  linear/radial/reflected, angle, scale, reverse), Pattern Overlay (any pattern, size, opacity; it moves
  with the layer), Outer Glow, Drop Shadow, and Long Shadow (angle, length, fade).
  Colors: tap the swatch or the rainbow wheel for the full picker (the layer changes as you drag), or
  tap white, black or a rainbow color; below are your last 4 colors and the 4 main colors in the
  picture.
  They follow the layer and never change its pixels. With effects on, Opacity fades everything and Fill
  fades only the layer's own pixels. Merge down, flatten and export bake them in. **Apply layer style**
  (Layers ⋮, next to Rasterize layer, or hold the layer) bakes them into the layer's own pixels and clears
  the style; shapes and text become pixel layers. Opacity, blend mode and clipping stay as they are. A mask
  that's on is applied too (so the result looks exactly the same); a mask that's off is kept.
- **Adjustments** — exposure, brightness, contrast, highlights, shadows, saturation, vibrance, warmth, tint,
  sharpness, vignette, grain. On a pixel layer: live preview, then Apply (closing the window cancels). On an
  adjustment layer they stay editable.
- **Levels** — shadows, midtones and highlights in, darkest and lightest out, for RGB or each channel,
  over a histogram; Auto.
- **Curves** — drag points on the curve (tap to add, drag off the box to remove) for RGB or each channel,
  with presets.
- **Gaussian Blur** — radius up to 250 px. As an adjustment layer it blurs everything below it; paint its
  mask black where things should stay sharp.
- **Filters** — Quartz Gallery's 13 looks (including Invert and Duotone) with strength.
- **Hue/Saturation** — Master, or just Reds, Yellows, Greens, Cyans, Blues or Magentas (each blends into
  its neighbors); Hue, Saturation, Lightness; Colorize.
- **Color Balance** — Cyan–Red, Magenta–Green, Yellow–Blue for Shadows, Midtones and Highlights;
  Preserve luminosity.
- **Vibrance** — Vibrance and Saturation.
- **Black & White** — how bright each color becomes, Photoshop's presets, and a tint.
- **Photo Filter** — Photoshop's filters (Warming, Cooling, Sepia, Underwater…) or your color, density,
  Preserve luminosity.
- **Gradient Map** — any gradient (dark → light), reverse.
- **Channel Mixer** — each output channel as a mix of red, green and blue plus a constant, Monochrome, and
  Photoshop's presets (Black & White with filters, infrared…).
- **Selective Color** — cyan, magenta, yellow and black for Reds, Yellows, Greens, Cyans, Blues, Magentas,
  Whites, Neutrals and Blacks; Relative or Absolute.
- **Color Lookup** — 10 ready-made looks, or load a **.cube** or **.3dl** lookup table file.
- **Posterize** (levels) and **Threshold** (level).
- In every adjustment layer's window: **Clip to the layer below**.
- Layer Style effects each have a **Blend mode** (Multiply shadows darken what's below; Bevel's highlight
  and shadow start at Screen and Multiply).

### Enhance
Menu → **Enhance** works on the selected layer (with a selection, only on what's selected):
- **Auto enhance** — adds a Levels adjustment layer, clipped to the photo, that stretches each color
  channel between its darkest and lightest parts (evening out a color cast) and sets a natural brightness.
  With a selection, its mask shows only what's selected. Fine-tune it like any Levels layer.
- **Upscale…** — the whole picture 2× or 4× bigger (up to 4096 px). Photo layers get new detail from the
  on-device Real-ESRGAN model (only what's on the canvas: pixels a crop left outside it are trimmed);
  shapes, text and masks are redrawn sharp as with Image size. It takes a while on big pictures; the
  spinner shows how far along it is.
- **Denoise** (Strength, Color speckles) and **Sharpen** (Amount, Radius, Leave smooth areas) — previewed
  live on the picture, then Apply changes the layer's pixels (one undo step).
- From **depth** (the on-device Depth Anything V2 model looks at the photo once, then everything is live):
  - **Depth blur** — tap what should stay sharp; nearer and farther things blur like a wide-open lens
    (Blur, Focus depth, In focus). The sharp parts don't smear into the blur.
  - **Depth fog** — haze that thickens with distance; tap where it should start (Starts, Fade in,
    Thickness, Color).
  - **Relight** — drag the light around the picture: Strength, Height, Reach, Shadows, Relief, Color, and
    whether it blends as Soft Light, Overlay or Linear Light.
  - **Replace sky** — the sky is the farthest part reaching the top edge, plus pockets of sky the clouds
    or peaks cut off, and a short way on into the sky's own colors (never into far mountains); pick a ready-made sky (Clear
    blue, Deep blue, Golden hour, Sunset, Dusk, Overcast, Storm, with Clouds) or one of your photos, and how
    far the sky reaches and how soft its edge is.
  Each of these is added as **its own layer** above the photo, clipped to it, with a mask (Relight's light
  is a Soft Light layer), so you can paint the mask, fade it or delete it later.
- The settings open in a window with Cancel and Apply; anything else you do applies it first; Undo or
  closing the window cancels it.

### Masks
A mask hides parts of its layer without erasing them: paint black to hide, white to show, gray for
in between. With a selection, the mask button makes a mask that shows only what's selected. Tap the mask thumbnail to paint on the mask, the layer thumbnail to paint pixels.
**Remove background** makes a mask from the subject (on-device), which you can touch up with the brush.

### Saving: Projects and Drafts
- **Projects** are what you've saved: **File → Save project** (Ctrl+S), or **Save** when you close one.
  They keep every layer, mask and setting (smart layers keep their full-size contents inside).
- **Drafts** keep what you're doing safe as you go: changes are autosaved into a draft when you switch
  files, go Home or switch apps. A project you opened is **never changed by the autosave** — only by
  Save project.
- **Closing** (× on its tab, File → Close project) with unsaved changes asks: **Save** (into the
  project; it closes once the save is done, and stays open if saving fails), **Don't save** (the draft is thrown away; the project stays as it was last
  saved) or **Cancel**.
- If the app closes first (you swipe it away, it crashes, the tablet restarts), the changes wait in
  **Drafts** on the start screen. Opening a project that has a draft asks: **Open the changes** or
  **Open saved** (the changes are thrown away).
- **File menu**: **New…** (the canvas size screen: presets, your own size, orientation, background),
  **Open file…** (pictures, projects, Photoshop and TIFF files), **Open image…** (your recent photos, or
  Browse…), **Save project**, **Export image…**, **Quick export** (the big colored button), **Rename
  image…**, **Save a copy as a project file…**, **Close project**. What's opened or made joins the open
  files in a tab of its own. (Place image is in the Layer menu; Import brushes in the brush picker and
  Brushes panel.)
- **Quick export (.png)** (Menu, under Home) saves a PNG of the whole picture straight to
  `Pictures/Quartz Editor`, named for when it was saved (`Quartz 10-20-26 19-30-05.png`), with no questions.
- **Export image** (File menu) opens the Export screen: a **preview** of exactly what will be saved
  (compression and all; Fit, 100% or 200% to look closely; hold "hold here to compare" to see it before
  compression) beside the settings:
  - **File name** (the image takes it too) and **format**: PNG, JPG, WebP, TIFF, Photoshop, TIFF with layers.
  - **Quality** for JPG and WebP (1–100%; WebP can also be **Lossless**). Each format remembers its own.
  - **Size**: width and height in pixels (linked, or not), or 100 / 75 / 50 / 33 / 25%. Only the saved file
    is resized (in halves first, so it stays sharp); the image stays as it is.
  - The **file size** it comes to, worked out as you change things.
  - **Save** or **Save & share**, to `Pictures/Quartz Editor` (it shows up in your gallery). Photoshop and
    layered TIFF files are saved whole, at full size.
- **Photoshop file**: Export image → Photoshop saves a `.psd` with its layers to `Download/Quartz Editor`:
  - every layer's pixels, mask (on or off), blend mode, opacity, fill, clipping, visibility, locks and
    Blend If (Photoshop files' Blend If comes in too);
    folders (Pass Through or their own blend mode, open or closed, with masks and styles)
  - **layer styles** as Photoshop's (Drop Shadow, Inner Shadow, Outer and Inner Glow, Bevel & Emboss,
    Satin, Color, Gradient Overlay, Stroke)
  - **adjustment layers** as Photoshop's own (Levels, Curves, Brightness/Contrast, Exposure, Vibrance,
    Hue/Saturation, Color Balance, Black & White, Photo Filter, Gradient Map, Channel Mixer, Selective
    Color, Color Lookup, Posterize, Threshold); Adjustments and Filters with no Photoshop twin become a
    Color Lookup layer that changes colors the same way
  - **shapes stay shapes** (their outline, color or gradient fill, outline width, position and dashes)
    and **text stays text** (font by name, size, color, alignment, spacing, styled parts, box text, warp)
  - **smart layers stay smart objects**, each with its contents inside (a Photoshop file of its own when
    it has layers; linked copies share one), placement, warp and smart filters (Gaussian Blur, sharpen,
    noise)
  - the flattened picture, as the app draws it

  Afterwards a short list says what Photoshop gets differently (3D and pattern-filled layers as their
  pixels, Long Shadow and Pattern Overlay only in the flattened picture, Gaussian Blur adjustment layers…).
- **TIFF with layers**: Export image → TIFF with layers saves a TIFF to `Pictures/Quartz Editor` that any
  app shows as the flattened picture, with everything above kept inside it the way Photoshop keeps its
  own layered TIFFs (Photoshop and Quartz Editor open it with its layers). Plain **TIFF** stays one
  flattened picture.
- The menu also rotates the whole image 90° or flips it.
- **Save project file** writes a `.qedit` file anywhere (Drive, Downloads...). Projects live inside the app,
  so uninstalling removes them — keep a project file of anything important.

## Keyboard shortcuts
With a keyboard (tablet or Chromebook), Photoshop's shortcuts work (Ctrl or Cmd):
- Tools: **V** move, **H** pan (or hold **Space** with any tool), **M** rectangle/ellipse select
  (Shift+M switches), **L** lasso/polygonal (Shift+L), **W** magic wand, **C** crop, **Ctrl+T** transform, **Ctrl+Shift+X** liquify,
  **I** eyedropper, **G** gradient/bucket (Shift+G), **U** shape (Shift+U next shape), **P** pen, **A** edit
  points, **T** text, **B** brush, **E** eraser.
- Brush: **[ ]** size, **Shift+[ ]** softer/harder, **1…0** opacity 10…100% (with Move: the layer's opacity).
- Colors: **X** swap, **D** black and white.
- Undo **Ctrl+Z**, redo **Ctrl+Shift+Z** or **Ctrl+Y**. **Enter** applies and **Esc** cancels a transform, warp or crop.
- Selections: **Ctrl+A** all, **Ctrl+D** deselect, **Ctrl+Shift+I** invert, **Delete** delete selected
  pixels (no selection: delete the layer), **Alt+Delete** fill foreground, **Ctrl+Delete** fill background.
- Layers: **Ctrl+J** duplicate (copy the selection to a new layer; **Ctrl+Shift+J** cut it),
  **Ctrl+Shift+N** new layer, **Ctrl+G** put in a folder, **Ctrl+Alt+G** clip to the layer below,
  **Ctrl+E** merge down.
- **Ctrl+L** Levels, **Ctrl+M** Curves, **Ctrl+U** Adjustments.
- View: **Ctrl+0** fit, **Ctrl+1** 100%, **Ctrl++ / Ctrl+−** zoom, **Tab** show/hide panels, **Ctrl+'** grid,
  **Ctrl+R** rulers, **Ctrl+;** guides (**Ctrl+Alt+;** lock them). **Shift** and **Alt** work like the
  modifier button.
- **Enter** applies (transform, crop, pen shape, adjustments), **Esc** cancels. **Ctrl+S** saves the project.

## How it works
- Layers are stored as 512 px tiles that only exist where there are pixels, so empty layers cost nothing
  and only the tiles a brush touches are redrawn.
- Brush strokes build up in a separate buffer and are applied when you lift your finger, so a stroke's
  opacity stays even (like Photoshop).
- Undo keeps only the tiles that changed, within a memory budget.
- The same drawing code draws the screen and exports, so what you see is what you save.
- `.qedit` files are zips: `doc.json` (settings), raw tile pixels, and a thumbnail.

- Shape and text layers keep what they are (the shape, the words, the font) and redraw their pixels from
  it whenever it changes, so blend modes, masks, styles, merging and export treat them like any layer.

## On-device AI
Select Object (MobileSAM: its image encoder plus the Segment Anything mask decoder), AI Remove (MI-GAN),
the depth effects (Depth Anything V2 Small) and Upscale (Real-ESRGAN general x4v3) run on the tablet
through ONNX Runtime; the app has no internet permission, so nothing leaves the device.
The model files (about 176 MB) are downloaded by Gradle the first time the app is built on a computer, into
`ai-models/` (ignored by Git; Clean leaves it), and checked against their SHA-256. Built without internet,
the app still works; those features say the model isn't included. Licenses: MobileSAM Apache-2.0
(its mask decoder is the original Segment Anything decoder, Apache-2.0), MI-GAN MIT, Depth Anything V2
Small Apache-2.0, Real-ESRGAN BSD-3-Clause, ONNX Runtime MIT.

The Photoshop file writer follows ag-psd (MIT, by Agamnentzar) for how Photoshop lays out
descriptors, text engine data and smart objects.

## ComfyUI (Quartz Online)
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
- **Switches**: a workflow's own on/off Boolean nodes (like "Enable LoRA 2?") show as switches.
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
- **LoRA Manager**: its Lora Loader's list, with the ones switched on shown first (On only switches to
  all of them; Find searches): switch each on or off, set its strength, take it out (×), or **Add…** any
  LoRA your ComfyUI has. Its trigger words node gets the trigger words as LoRA Manager works them out.
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

## Where the code is
Everything is under `app/src/main/java/com/tim/quartzeditor/`.
- `engine/` — the picture itself, with no screens: layers and tiles (`Doc`, `TileGrid`), drawing
  (`DocRenderer`), brushes, selections, fills and gradients (`FillTools`), patterns, layer styles
  (`Styles`), adjustments and filters (`Fx`, `Tone`), transforms, warps (`Warp`), face finding
  (`FaceAware`), background removal (`Cutout`), shapes and text (`Vector`, `Fonts`), undo (`History`),
  project files (`ProjectIO`), color adjustments (`ColorAdjust`), retouch brushes and healing (`Retouch`),
  selection expand/shrink (`SelectionOps`), the AI models (`Ai`), Photoshop files (`Psd` reads them,
  `PsdImport` turns them into a document, `PsdFonts` picks fonts, `PsdExport` turns a document into one,
  `PsdWriter` writes it), TIFF files (`Tiff`: reading and writing), the blend modes Android doesn't have
  (`Blends`), .svg files (`Svg`), the custom shape library (`ShapeLibrary`), the Enhance effects
  (`Enhance`), swatch sets and files (`Swatches`), text warps (`TextWarp`), smart layers with their
  warps and smart filters (`Smart`), and talking to ComfyUI and reading its workflows (`Comfy`).
- `ui/EditorViewModel.kt` — what's open and picked, plus the core every action uses: undo,
  "apply what's pending first", the busy overlay, the view's zoom and position, picking tools.
- `ui/*Actions.kt` — what you can do, one file per area, each adding functions to the view model:
  `ProjectActions` (open, save, export), `PaintActions` (brush and eraser), `MoveActions`,
  `LayerActions`, `MaskActions`, `FxActions` (adjustments and filters), `StyleActions`, `ApplyStyle`,
  `TransformActions`, `ToolActions` (selections, crop, bucket, gradients), `VectorActions` (shapes, pen,
  text), `PatternActions`, `WarpActions`, plus `MultiSelect`, `SelectionTools`, `PenEdit`, `Snap`,
  `Grid`, `Shortcuts`, `ToolPicks`, `StylePresets`, `GradientEditor`, `RetouchActions`, `AiActions`,
  `ShapeLibraryActions`, `EnhanceActions`, `ComfyActions` (ComfyUI runs, its window and status chip),
  `PenInput` (the pen's pressure, tilt, eraser end and button, read from each touch event, and the
  pressure curve), `PressureCurveEditor`, `QuickShape` (draw and hold; the shape fitting is
  `engine/ShapeFit`), `ColorDrop`, `ComfyBar`, `BrushLibrary` and `BrushStudio` (with `.abr` import:
  `engine/Abr`),
  `PanelDock` (the panel column: sections, tabs, moving and sizing them), `MenuContent` (what each menu holds, shared by the menu bar and the ☰ menu), `DesktopChrome` (the menu
  bar, options bar, status bar, History list, hover tips and the pen's right click).
- `app/src/offline/` and `app/src/online/` — what's different in the two apps: the offline one removes
  internet access; the online one adds it (plain `http://` allowed), its name, and the built-in
  ComfyUI workflows and server address (`assets/comfy/`).
- The other `ui/` files are screens and panels: `Workspace`, `EditorCanvas` (touches and what's drawn
  over the picture), `Toolbar`, `LayersPanel`, `ToolPanels` (Properties), `StylePanel`, `TonePanels`,
  `VectorPanels`, `PatternPanel`, `WarpPanel`, `TransformControls`, `LaunchScreen`, `ColorPanels`,
  `Rulers`, `ModifierButton`, `LayerStrip`, `ShapePanel` (the shape library), `ImageSize`, `EnhancePanel`.

## Settings
Menu → Settings (or the gear on the home screen).
- **Appearance**: theme (Dark, Midnight, Medium gray, Light), accent color, **Layout** (Automatic, Menu
  bar, Classic; see Menu bar layout), and how solid the toolbar,
  panels and windows are (lower lets the picture show through). **Compact interface** (under Panels, off
  by default) trims margins and padding: a narrower toolbar with tighter spacing, slimmer workspace edges,
  tighter panel sections, buttons and fields, and shorter layer rows — more room for the picture.
- **Pen and drawing**: whether fingers move the view once a pen's been used, the **pressure curve** (drag
  its two handles, or pick Light hand, Normal, Firm hand or Soft start; press the strip under it with the
  pen to see where your touch lands), **draw and hold** (QuickShape) on or off and how long to hold, the
  smallest size at the lightest touch, tilt on or off, the eraser end on or off, what the
  side button does (eyedropper, eraser, undo or nothing), and the brush outline while hovering.
- **Your library**: your patterns (hold to delete, + to add), gradients (hold to edit or delete, + for a
  new one), layer style presets (delete) and imported fonts (delete, import).

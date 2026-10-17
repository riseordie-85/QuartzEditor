# Quartz Editor

A Photoshop-light photo editor for Android 13+, built from Quartz Gallery's editor. Kotlin + Jetpack Compose.

**Private by design:** no ads, no accounts, no tracking. The app has no internet access (it's removed
from the build). Background removal's model is fetched by Google Play services in its own process.

## Launch screen
- **Recent Photos in Gallery** (newest first) or **Projects** (your saved work, with all layers). Tap to open.
  Long-press a project to delete it.
- **New image**: name, width and height in pixels (a menu of sizes: screen, square, story, HD, 4K, A4),
  portrait / landscape, background White, Black or Transparent. Up to 4096 px a side.
- **Open File Picker**: any image, or a `.qedit` project file.
- Other apps can **Open with**, **Edit with** or **Share to** Quartz Editor.

## Workspace
Tools on the left, the picture in the middle, panels on the right. On phones the panels slide in from the
right (the arrow button at the bottom right); on tablets and the unfolded Fold they sit beside the picture
and the same button hides them.

### Toolbar
Top to bottom: ☰ Menu, Move, Select, Transform/Crop, Healing, Fill, Toning, Pen, Shape, Text, Brush/Eraser,
Eyedropper, then the colors. Buttons with a dot in the corner hold more than one tool: **long-press** for
the list; the button shows the one you used last.
- **☰ Menu** — Home, then pages: **File** (Rename, Export, Save project file, Place image, Close without
  saving), **Image** (Rotate, Flip, Flatten), **View** (Snap, Checkerboard, Modifier button, **Grid** and
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
  pages for Modify edge (Feather, Expand / Shrink), Select (subject, background), New layer (Copy or Cut),
  Fill and remove (Fill, Delete selected pixels, AI Remove selected), then Mask from selection, Define
  pattern and Crop to selection.
  Brushes, fills, gradients and adjustments only change what's selected. Tap without dragging to deselect.
- **Crop** (hold the Transform button) — drag the corners, edges or the box; pick a shape (Free, Original,
  1:1, 4:3, 3:4, 16:9, 9:16). Dragging past the canvas adds space. Cropped-off pixels stay on their layers.
- **Perspective crop** (hold the Transform button) — drag the four corners onto the corners of something
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
  - **Liquify** (Ctrl+Shift+X): Push, Bloat, Pucker, Twirl both ways, Smooth and Restore brushes, with size
    ([ and ]) and strength. Bloat, Pucker and Twirl keep working while you hold still. The picture's edges
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
  Linear / Radial / Reflected, reverse and opacity are in Properties. **+** makes your own gradient
  (starting from the one picked): tap the bar to add a color, drag the markers to move them, tap one to
  change its color, location or opacity (see-through stops work), Reverse, Even spacing, a name. Hold one of
  yours to edit or delete it. Your gradients are also in Layer Style → Gradient Fill (with its own **+**).
- **Shape** — drag to draw a Rectangle, Ellipse, Triangle, Polygon (number of sides), Star or Arrow (from
  where you start dragging to where you stop) on a new shape layer; tap the tool again for the next shape,
  or hold it for the list. In Properties: fill and outline (on/off, color, width, inside/center/outside), corner radius,
  the star's points and depth (no depth makes a polygon), and "same width and height". New shapes always
  start filled without an outline (colors carry over). Tap a shape with the tool to pick it; with a shape
  layer selected, Properties changes that shape.
- **Shape pen** — tap to place corners; drag as you place a point to bend the line into a curve. Tap the first
  point (or Close shape) to finish, or Open line for an unclosed line. Two-finger tap or the bar's undo
  removes the last point. **Edit points** comes on after finishing (or tap the pen again, or Edit points in
  Properties): drag points and curve handles, tap the outline to add a point, tap a point to make it a
  corner or a curve or delete it. Done (or the pen again) goes back to drawing.
- **Text** — tap the picture and type (the bar at the top has the text box, Cancel and Done). Tap existing
  text to change it; drag with the Text tool to move it. Properties: font (Android's, Google Fonts, or your
  own .ttf/.otf), size, color, alignment, faux bold/italic, underline, ALL CAPS, letter and line spacing,
  and Arc.
  Outline, shadow and glow come from Layer Style (fx).
- **Gradient / Paint bucket / Pattern fill** share a button (tap again for the next one).
- **Brush** and **Eraser** (one button; hold for the other) — open a second column with the tips (Round, Square, Triangle, Grunge) and the
  Softness, Size and Opacity sliders; any other tool folds it away (tap Brush again to fold it yourself).
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
  - **Dodge / Burn** (Shadows / Midtones / Highlights), **Smudge**, **Blur**, **Sharpen**.
  - "Look at all layers" (healing, AI Remove, clone): read the picture as you see it and paint onto the
    layer you're on — an empty layer keeps the photo untouched.
- The toolbar's buttons shrink to fit the screen's height (it only scrolls on very short screens).
- **Colors** — foreground (top) and background. Tap one for a color picker beside the toolbar; swipe up or
  down on them to swap.

### On the picture
- One finger: the tool. Two fingers: move and pinch-zoom. **Two-finger tap: undo. Three-finger tap: redo.**
- **Modifier button** (the floating ⇧; drag its grip to move it; ☰ → Modifier button turns it off): hold it
  for **Shift**, slide out past its edge for **Alt**, double-tap to keep Shift on. A keyboard's Shift and
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
- Transform, rotate/flip image, masks, clipping and layer styles work on smart layers as they are; painting,
  filters, adjustments and warps ask to **rasterize** them first.

### Panels
Only **Color**, **Properties** and **Layers** live in the column.
- **Color** and **Properties** fold away by themselves: picking a tool opens Properties with that tool's
  settings (selection shape and mode, crop ratio, transform numbers, bucket tolerance, gradient presets
  and type, brush tips and opacity), and tapping a color swatch opens Color. The **pin** on a section's
  header keeps it open. With Move, Properties shows the selected layer (name, position, mask).
- **Color** — saturation/brightness square, hue bar, hex, RGB, recent colors and basics.
- **Layers** — like Photoshop, the settings on top (blend mode, opacity, locks, fill) and the buttons at the
  bottom stay put; only the list between them scrolls. It shows up to four layers while Color or Properties
  is open (fewer if the screen is short; the buttons always stay), and fills the column when they're folded.
  Tap its header to fold it too.
  - Blend modes (Normal, Darken, Multiply, Color Burn, Lighten, Screen, Color Dodge, Linear Dodge, Overlay,
    Soft Light, Hard Light, Difference, Exclusion, Hue, Saturation, Color, Luminosity), Opacity and Fill.
  - Lock transparent pixels and lock the whole layer.
  - Each layer: show/hide, thumbnail, mask thumbnail, name, drag handle to reorder. **Long-press** a layer
    for Rename, Duplicate, Merge down, Clip to layer below, and pages for **Layer style** (Copy / Paste,
    Save as preset, Apply / Clear) and **Convert** (smart layer, Edit contents, Rasterize).
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
    only changes one layer. Clipped layers show an arrow; stack several on one base. The base's opacity
    and blend mode apply to the whole group. Merge down a clipped layer to bake it in.
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
  and your own: "+ Save" saves the current style; hold one of yours to delete it). Gradient Fill (any
  gradient, linear/radial/reflected, angle, scale, reverse), Long Shadow (angle, length, fade), Bevel & Emboss (inner/outer bevel, emboss, pillow; up/down, depth, size, soften, light
  angle and altitude, gloss contour, highlight and shadow colors), Pattern Overlay (any pattern, size,
  opacity; it moves with the layer), Stroke (outside, inside, center; a color or a gradient), Drop Shadow, Inner
  Shadow, Outer Glow, Color Overlay.
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
- In every adjustment layer's window: **Clip to the layer below**.
- Layer Style effects each have a **Blend mode** (Multiply shadows darken what's below; Bevel's highlight
  and shadow start at Screen and Multiply).

### Masks
A mask hides parts of its layer without erasing them: paint black to hide, white to show, gray for
in between. With a selection, the mask button makes a mask that shows only what's selected. Tap the mask thumbnail to paint on the mask, the layer thumbnail to paint pixels.
**Remove background** makes a mask from the subject (on-device), which you can touch up with the brush.

### Saving
- Your work is saved to **Projects** automatically when you go Home or switch apps, with every layer,
  mask and setting (smart layers keep their full-size contents inside the project).
- **Export image** saves a flattened PNG, JPG or WebP to `Pictures/Quartz Editor` (shows up in Quartz
  Gallery), optionally sharing it.
- **Close without saving** (menu) throws away changes since the last save.
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
- **Enter** applies (transform, crop, pen shape, adjustments), **Esc** cancels. **Ctrl+S** saves to Projects.

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
Select Object (MobileSAM: its image encoder plus the Segment Anything mask decoder) and AI Remove (MI-GAN)
run on the tablet through ONNX Runtime; the app has no internet permission, so nothing leaves the device.
The model files (about 72 MB) are downloaded by Gradle the first time the app is built on a computer, into
`ai-models/` (ignored by Git; Clean leaves it), and checked against their SHA-256. Built without internet,
the app still works; those two features say the model isn't included. Licenses: MobileSAM Apache-2.0
(its mask decoder is the original Segment Anything decoder, Apache-2.0), MI-GAN MIT, ONNX Runtime MIT.

## Where the code is
Everything is under `app/src/main/java/com/tim/quartzeditor/`.
- `engine/` — the picture itself, with no screens: layers and tiles (`Doc`, `TileGrid`), drawing
  (`DocRenderer`), brushes, selections, fills and gradients (`FillTools`), patterns, layer styles
  (`Styles`), adjustments and filters (`Fx`, `Tone`), transforms, warps (`Warp`), face finding
  (`FaceAware`), background removal (`Cutout`), shapes and text (`Vector`, `Fonts`), undo (`History`),
  project files (`ProjectIO`), color adjustments (`ColorAdjust`), retouch brushes and healing (`Retouch`),
  selection expand/shrink (`SelectionOps`) and the AI models (`Ai`).
- `ui/EditorViewModel.kt` — what's open and picked, plus the core every action uses: undo,
  "apply what's pending first", the busy overlay, the view's zoom and position, picking tools.
- `ui/*Actions.kt` — what you can do, one file per area, each adding functions to the view model:
  `ProjectActions` (open, save, export), `PaintActions` (brush and eraser), `MoveActions`,
  `LayerActions`, `MaskActions`, `FxActions` (adjustments and filters), `StyleActions`, `ApplyStyle`,
  `TransformActions`, `ToolActions` (selections, crop, bucket, gradients), `VectorActions` (shapes, pen,
  text), `PatternActions`, `WarpActions`, plus `MultiSelect`, `SelectionTools`, `PenEdit`, `Snap`,
  `Grid`, `Shortcuts`, `ToolPicks`, `StylePresets`, `GradientEditor`, `RetouchActions`, `AiActions`.
- The other `ui/` files are screens and panels: `Workspace`, `EditorCanvas` (touches and what's drawn
  over the picture), `Toolbar`, `LayersPanel`, `ToolPanels` (Properties), `StylePanel`, `TonePanels`,
  `VectorPanels`, `PatternPanel`, `WarpPanel`, `TransformControls`, `LaunchScreen`, `ColorPanels`,
  `Rulers`, `ModifierButton`, `LayerStrip`.

## Settings
Menu → Settings (or the gear on the home screen).
- **Appearance**: theme (Dark, Midnight, Medium gray, Light), accent color, and how solid the toolbar,
  panels and windows are (lower lets the picture show through).
- **Your library**: your patterns (hold to delete, + to add), gradients (hold to edit or delete, + for a
  new one), layer style presets (delete) and imported fonts (delete, import).

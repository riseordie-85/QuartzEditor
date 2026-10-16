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
Buttons with a dot in the corner hold more than one tool: **long-press** for the list (Move/Pan, the
selection tools, the shapes, pen drawing/point editing).
- **Move** — drag the selected layer (a folder moves everything in it); tap the picture to select the top
  layer under your finger. Tap Move again to switch to **Pan** (one finger moves the view). While moving
  (and moving a Transform box), the layer's edges and middle **snap** to the canvas's edges and middle,
  with a pink line showing where. Menu → "Snap to center and edges" turns it off.
- **Select** — tap again to switch shape: Rectangle → Ellipse → Lasso → Polygonal lasso → Magic wand. Drag
  to select (polygonal: tap corners, tap the first one or Close to finish; two-finger tap removes the last
  corner). **Magic wand**: tap a color; Properties has tolerance, "only touching pixels" and "look at all
  layers". **Select subject** / **Select background** (Properties, or ⋮ on the bar) find the subject on the
  device. The bar on the picture: New / Add / Subtract / Intersect, Select all, Deselect, Invert, and ⋮ for
  Feather, Copy or Cut to a new layer, Delete selected pixels, Fill, Mask from selection, Crop to selection.
  Brushes, fills, gradients and adjustments only change what's selected. Tap without dragging to deselect.
- **Crop** — drag the corners, edges or the box; pick a shape (Free, Original, 1:1, 4:3, 3:4, 16:9, 9:16).
  Dragging past the canvas adds space. Cropped-off pixels stay on their layers.
- **Transform** — a box around the selected layer: drag inside to move, corners to scale (proportions
  locked unless you unlink them), edges to stretch, outside the box or the top handle to rotate (snaps to
  straight angles). The bar on the picture has flip, rotate 90°, **Skew** (slide a side or corner along
  itself), **Perspective** (drag a corner; the other end of that side moves the opposite way), **Distort**
  (corners anywhere) and Apply. With several layers picked, they transform together.
- **Eyedropper** — touch and drag; lifting picks the color into the swatch you're editing.
- **Paint bucket** — tap to fill matching pixels with the foreground color. Tolerance, "only touching
  pixels" and "look at all layers" are in Properties.
- **Gradient** — drag on the picture: the gradient runs from where you start to where you let go. Presets,
  Linear / Radial / Reflected, reverse and opacity are in Properties.
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
- **Brush** and **Eraser** — open a second column with the tips (Round, Square, Triangle, Grunge) and the
  Softness, Size and Opacity sliders; any other tool folds it away (tap Brush again to fold it yourself).
- The toolbar's buttons shrink to fit the screen's height (it only scrolls on very short screens).
- **Colors** — foreground (top) and background. Tap one for a color picker beside the toolbar; swipe up or
  down on them to swap.

### On the picture
- One finger: the tool. Two fingers: move and pinch-zoom. **Two-finger tap: undo. Three-finger tap: redo.**
- **Quick pinch inward** fits the picture to the screen (or use the fit button at the top right).
- **Grid** (menu → Show grid, or Ctrl+'): evenly spaced lines from the canvas's top-left corner. Grid
  settings: spacing in pixels (or split the canvas into 2–12 equal parts), subdivisions, color, and Snap to
  grid — moving layers and Transform boxes, shape corners and pen points snap to its lines while it's shown.
- **Long press beside the picture** to change the workspace color. Menu → "Checkerboard for see-through"
  switches the transparency checkerboard off (see-through areas show white).
- Undo, redo and fit buttons at the top right; the menu at the bottom left has Home, Export image,
  Save project file, Place image as layer and Flatten.

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
    for Rename, Copy / Paste layer style, Save layer style as preset, Clear style, Merge down, Duplicate and
    Clip to layer below.
  - **Pick several layers**: swipe right on a row (again to take it out). Move, Transform, Delete, put in a
    folder (Ctrl+G), merge (Ctrl+E) and pasting styles work on all of them. Tap any layer (or "n picked ✕")
    to go back to one.
  - **Folders**: the arrow folds/unfolds them. Drag a layer onto a folder's row (it lights up) to put it
    inside, or just under the folder's row; drag it below the folder's contents to take it out.
  - **Clipping masks**: ⋮ → **Clip to layer below** (or Ctrl+Alt+G) shows a layer only where the layer
    under it has pixels: a texture or photo inside text, a color only on one object, an adjustment that
    only changes one layer. Clipped layers show an arrow; stack several on one base. The base's opacity
    and blend mode apply to the whole group. Merge down a clipped layer to bake it in.
  - **Adjustment layers**: change everything below them without touching pixels; paint their mask to limit
    them. Tap one's icon to edit it.
  - **Shape and text layers** stay editable: change them in Properties, scale, rotate or distort them with
    Transform (they stay sharp), add masks and layer styles. Painting, fills, filters and adjustments ask to
    **rasterize** them first (turn them into pixels); ⋮ → Rasterize layer does it any time. Text layers
    show a T; tap it on the selected layer to edit the text.
  - Bottom buttons: **fx** (Layer Style), mask, **adjustments** (a new Adjustments, Levels, Curves or
    Gaussian Blur adjustment layer; or Adjustments…, Levels…, Curves…, Gaussian Blur…, Filters… on the
    layer), new folder, new layer, delete, and ⋮ for everything else.

### Floating windows
**Layer Style**, **Adjustments**, **Levels**, **Curves**, **Gaussian Blur** and **Filters** open in a window
over the picture (from the bottom of the Layers panel), so you can see the result while you change them.
- **Layer Style** — **Presets** at the top (ready-made ones like Soft shadow, Sticker, Neon, Gold, Chrome,
  and your own: "+ Save" saves the current style; hold one of yours to delete it). Gradient Fill (any
  gradient, linear/radial/reflected, angle, scale, reverse), Long Shadow (angle, length, fade), Bevel & Emboss (inner/outer bevel, emboss, pillow; up/down, depth, size, soften, light
  angle and altitude, highlight and shadow colors), Stroke (outside, inside, center), Drop Shadow, Inner
  Shadow, Outer Glow, Color Overlay.
  They follow the layer and never change its pixels. With effects on, Opacity fades everything and Fill
  fades only the layer's own pixels. Merge down, flatten and export bake them in.
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

### Masks
A mask hides parts of its layer without erasing them: paint black to hide, white to show, gray for
in between. With a selection, the mask button makes a mask that shows only what's selected. Tap the mask thumbnail to paint on the mask, the layer thumbnail to paint pixels.
**Remove background** makes a mask from the subject (on-device), which you can touch up with the brush.

### Saving
- Your work is saved to **Projects** automatically when you go Home or switch apps, with every layer,
  mask and setting.
- **Export image** saves a flattened PNG, JPG or WebP to `Pictures/Quartz Editor` (shows up in Quartz
  Gallery), optionally sharing it.
- **Close without saving** (menu) throws away changes since the last save.
- The menu also rotates the whole image 90° or flips it.
- **Save project file** writes a `.qedit` file anywhere (Drive, Downloads...). Projects live inside the app,
  so uninstalling removes them — keep a project file of anything important.

## Keyboard shortcuts
With a keyboard (tablet or Chromebook), Photoshop's shortcuts work (Ctrl or Cmd):
- Tools: **V** move, **H** pan (or hold **Space** with any tool), **M** rectangle/ellipse select
  (Shift+M switches), **L** lasso/polygonal (Shift+L), **W** magic wand, **C** crop, **Ctrl+T** transform,
  **I** eyedropper, **G** gradient/bucket (Shift+G), **U** shape (Shift+U next shape), **P** pen, **A** edit
  points, **T** text, **B** brush, **E** eraser.
- Brush: **[ ]** size, **Shift+[ ]** softer/harder, **1…0** opacity 10…100% (with Move: the layer's opacity).
- Colors: **X** swap, **D** black and white.
- Undo **Ctrl+Z**, redo **Ctrl+Shift+Z** or **Ctrl+Y**.
- Selections: **Ctrl+A** all, **Ctrl+D** deselect, **Ctrl+Shift+I** invert, **Delete** delete selected
  pixels (no selection: delete the layer), **Alt+Delete** fill foreground, **Ctrl+Delete** fill background.
- Layers: **Ctrl+J** duplicate (copy the selection to a new layer; **Ctrl+Shift+J** cut it),
  **Ctrl+Shift+N** new layer, **Ctrl+G** put in a folder, **Ctrl+Alt+G** clip to the layer below,
  **Ctrl+E** merge down.
- **Ctrl+L** Levels, **Ctrl+M** Curves, **Ctrl+U** Adjustments.
- View: **Ctrl+0** fit, **Ctrl+1** 100%, **Ctrl++ / Ctrl+−** zoom, **Tab** show/hide panels, **Ctrl+'** grid.
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

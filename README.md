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
- **Move** — drag the selected layer (a folder moves everything in it); tap the picture to select the top
  layer under your finger. Tap Move again to switch to **Pan** (one finger moves the view).
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
  straight angles). The bar on the picture has flip, rotate 90°, **Distort** and Apply.
- **Eyedropper** — touch and drag; lifting picks the color into the swatch you're editing.
- **Paint bucket** — tap to fill matching pixels with the foreground color. Tolerance, "only touching
  pixels" and "look at all layers" are in Properties.
- **Gradient** — drag on the picture: the gradient runs from where you start to where you let go. Presets,
  Linear / Radial / Reflected, reverse and opacity are in Properties.
- **Shape** — drag to draw a Rectangle, Ellipse or Star on a new shape layer; tap the tool again for the
  next shape. In Properties: fill and outline (on/off, color, width, inside/center/outside), corner radius,
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
- **Colors** — foreground (top) and background. Tap one to edit it in the Color panel; swipe up or down on
  them to swap.

### On the picture
- One finger: the tool. Two fingers: move and pinch-zoom. **Two-finger tap: undo. Three-finger tap: redo.**
- **Quick pinch inward** fits the picture to the screen (or use the fit button at the top right).
- **Long press beside the picture** to change the workspace color.
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
  - Each layer: show/hide, thumbnail, mask thumbnail, name (long-press to rename), drag handle to reorder.
  - **Folders**: the arrow folds/unfolds them. Drag a layer just under a folder's row to put it inside;
    drag it below the folder's contents to take it out.
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
- **Layer Style** — Bevel & Emboss (inner/outer bevel, emboss, pillow; up/down, depth, size, soften, light
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
in between. Tap the mask thumbnail to paint on the mask, the layer thumbnail to paint pixels.
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

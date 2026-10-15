# Quartz Editor

A Photoshop-light photo editor for Android 13+, built from Quartz Gallery's editor. Kotlin + Jetpack Compose.

**Private by design:** no ads, no accounts, no tracking. The app has no internet access (it's removed
from the build). Background removal's model is fetched by Google Play services in its own process.

## Launch screen
- **Recent Photos in Gallery** (newest first) or **Projects** (your saved work, with all layers). Tap to open.
  Long-press a project to delete it.
- **New image**: name, width and height in pixels (presets: screen, square, story, HD, 4K, A4), portrait /
  landscape, background White, Black or Transparent. Up to 4096 px a side.
- **Open File Picker**: any image, or a `.qedit` project file.
- Other apps can **Open with**, **Edit with** or **Share to** Quartz Editor.

## Workspace
Tools on the left, the picture in the middle, panels on the right. On phones the panels slide in from the
right (the arrow button at the bottom right); on tablets and the unfolded Fold they sit beside the picture
and the same button hides them.

### Toolbar
- **Move** — drag the selected layer (a folder moves everything in it); tap the picture to select the top
  layer under your finger. Tap Move again to switch to **Pan** (one finger moves the view).
- **Select** — tap again to switch shape: Rectangle → Ellipse → Lasso → Polygonal lasso. Drag to select
  (polygonal: tap corners, tap the first one or Close to finish; two-finger tap removes the last corner).
  The bar on the picture: New / Add / Subtract / Intersect, Select all, Deselect, Invert, and ⋮ for
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
- **Gradient** — drag on the picture: the gradient (from the Gradients panel) runs from where you start to
  where you let go. Linear, Radial or Reflected; reverse and opacity.
- **Brush** and **Eraser** — open a second column with the tips (Round, Square, Triangle, Grunge) and the
  Softness, Size and Opacity sliders; any other tool folds it away (tap Brush again to fold it yourself).
- **Colors** — foreground (top) and background. Tap one to edit it in the Color panel; swipe up or down on
  them to swap.

### On the picture
- One finger: the tool. Two fingers: move and pinch-zoom. **Two-finger tap: undo. Three-finger tap: redo.**
- **Quick pinch inward** fits the picture to the screen (or use the fit button at the top right).
- **Long press beside the picture** to change the workspace color.
- Undo, redo and fit buttons at the top right; the menu at the bottom left has Home, Export image,
  Save project file, Place image as layer and Flatten.

### Panels
- **Color** — saturation/brightness square, hue bar, hex, RGB, recent colors and basics.
- **Gradients** — presets (including foreground→background and foreground→transparent), type, reverse,
  opacity. Picking one switches to the Gradient tool.
- **Adjustments** — exposure, brightness, contrast, highlights, shadows, saturation, vibrance, warmth, tint,
  sharpness, vignette, grain. On a pixel layer: live preview, then Apply or Cancel. On an **adjustment
  layer** the same sliders (and Filters) stay editable forever and change everything below it.
- **Filters** — Quartz Gallery's 13 looks (including Invert and Duotone) with strength, previewed on the layer.
- **Properties** — transform numbers; brush/eraser tip, opacity and exact size; the layer's name and X/Y
  position; mask controls.
- **Layer Style** (or the **fx** button under the layers) — Stroke (outside, inside, center), Drop Shadow,
  Inner Shadow, Outer Glow and Color Overlay. They follow the layer, never change its pixels, and can be
  changed or switched off any time. With effects on, Opacity fades everything and Fill fades only the
  layer's own pixels, like Photoshop. Merge down, flatten and export bake them in.
- **Layers**
  - Blend mode (Normal, Darken, Multiply, Color Burn, Lighten, Screen, Color Dodge, Linear Dodge, Overlay,
    Soft Light, Hard Light, Difference, Exclusion, Hue, Saturation, Color, Luminosity), Opacity and Fill.
  - Lock transparent pixels (painting only changes what's already there; the eraser paints the background
    color) and lock the whole layer.
  - Each layer: show/hide, thumbnail, mask thumbnail, name (long-press to rename), drag handle to reorder.
  - **Folders**: the arrow folds/unfolds them. Drag a layer just under a folder's row to put it inside;
    drag it below the folder's contents to take it out. A folder's opacity, blend mode and mask apply to
    everything in it.
  - **Adjustment layers**: change everything below them (inside their folder) without touching pixels;
    paint their mask to limit them.
  - Bottom bar: fx, add mask, new adjustment layer, new folder, new layer, delete, and ⋮ for duplicate,
    merge down / merge folder, put in a new folder, flatten, place image, remove background, fill, clear,
    and mask options (enable/disable, invert, apply, delete).

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

## Coming next
- **Phase 3**: shapes (rectangle, ellipse, star with points and depth), shape pen, text, and the matching
  Properties options.

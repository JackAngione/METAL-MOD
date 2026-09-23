# Lunar surface assets

Credit: **NASA's Scientific Visualization Studio**.

Source: [CGI Moon Kit](https://svs.gsfc.nasa.gov/4720/), Ernie Wright,
NASA/Goddard, using Lunar Reconnaissance Orbiter Camera (LROC) and Lunar Orbiter
Laser Altimeter (LOLA) data. Downloaded 2026-09-22.

- `moon-albedo.jpg`: unmodified 1024×512
  [lroc_color_poles_1k.jpg](https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_poles_1k.jpg).
- `moon-height.jpg`: unmodified 1024×512
  [ldem_3_8bit.jpg](https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/ldem_3_8bit.jpg).

NASA SVS [publishes these materials in the public domain](https://svs.gsfc.nasa.gov/help/)
unless otherwise noted; these maps have no separate restriction on the source page.
NASA does not endorse this project.

At pack load the renderer decodes albedo from sRGB, packs linear RGB and raw height
into one RGBA8 Metal texture, and builds linear-light mip levels. Rendering applies
artistic exposure and modestly exaggerated bump relief to an analytic sphere.
The sun, atmosphere, and clouds use original procedural Metal shader assets.

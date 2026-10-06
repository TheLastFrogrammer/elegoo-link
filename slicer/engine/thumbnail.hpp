// Software rendering of G-code preview thumbnails, in place of ElegooSlicer's OpenGL renderer (GUI only).
#pragma once

#include "libslic3r/GCode/ThumbnailData.hpp"

namespace Slic3r { class Model; class DynamicPrintConfig; }

namespace linkslicer {

// Renders the printable model parts as ElegooSlicer's desktop thumbnails look: orthographic "iso" view (45 degrees up,
// from the front-left), zoomed to the parts, filament-colored with the desktop's gouraud lighting, on a transparent
// background. One image per requested size; rows bottom-up, as libslic3r's encoders expect from glReadPixels.
Slic3r::ThumbnailsList render_thumbnails(const Slic3r::Model& model, const Slic3r::DynamicPrintConfig& config,
                                         const Slic3r::ThumbnailsParams& params);

} // namespace linkslicer

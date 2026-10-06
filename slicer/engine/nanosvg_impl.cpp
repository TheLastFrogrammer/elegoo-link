// nanosvg's implementation, which libslic3r's SVG import calls. ElegooSlicer compiles it inside its GUI
// (src/slic3r/GUI/BitmapCache.cpp), which the engine does not build.
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"

// Software thumbnail renderer: a z-buffered, 4x supersampled rasterizer that reproduces ElegooSlicer's desktop
// thumbnail (GLCanvas3D::render_thumbnail_internal with Camera::EType::Ortho, ViewAngleType::Iso and the gouraud_light
// shader) without OpenGL.
#include "thumbnail.hpp"

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <vector>

#include "libslic3r/libslic3r.h"
#include "libslic3r/Color.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PrintConfig.hpp"

namespace linkslicer {

using namespace Slic3r;

namespace {

constexpr int SUPERSAMPLE = 4;

// gouraud_light.vs, in eye space.
const Vec3d LIGHT_TOP_DIR(-0.4574957, 0.4574957, 0.7624929);
const Vec3d LIGHT_FRONT_DIR(0.6985074, 0.1397015, 0.6985074);
constexpr double INTENSITY_AMBIENT = 0.3, LIGHT_TOP_DIFFUSE = 0.8 * 0.6, LIGHT_TOP_SPECULAR = 0.125 * 0.6,
                 LIGHT_TOP_SHININESS = 20.0, LIGHT_FRONT_DIFFUSE = 0.3 * 0.6;

struct Triangle { Vec3d a, b, c; ColorRGB color; };

// The colour of filament `id` (1-based), as the desktop colours each part by its extruder.
ColorRGB filament_color(const DynamicPrintConfig& config, int id)
{
    ColorRGB color(0xF2 / 255.f, 0x75 / 255.f, 0x4E / 255.f); // ElegooSlicer's default filament colour
    if (const auto* colours = config.option<ConfigOptionStrings>("filament_colour"); colours != nullptr && !colours->values.empty())
        decode_color(colours->values[std::clamp(id - 1, 0, int(colours->values.size()) - 1)], color);
    return color;
}

} // namespace

ThumbnailsList render_thumbnails(const Model& model, const DynamicPrintConfig& config, const ThumbnailsParams& params)
{
    // Camera::set_default_orientation(): zenith 45 degrees, azimuth 45 degrees, i.e. from the front-left, above.
    const double theta = -PI / 4, phi = PI / 4;
    const Eigen::Matrix3d view = (Eigen::AngleAxisd(theta, Vec3d::UnitX()) * Eigen::AngleAxisd(phi, Vec3d::UnitZ())).toRotationMatrix();

    // World-space triangles of the printable model parts.
    std::vector<Triangle> triangles;
    BoundingBoxf3 box;
    for (const ModelObject* object : model.objects)
        for (const ModelInstance* instance : object->instances) {
            if (params.printable_only && !instance->printable)
                continue;
            for (const ModelVolume* volume : object->volumes) {
                if (params.parts_only && !volume->is_model_part())
                    continue;
                const Transform3d matrix = instance->get_matrix() * volume->get_matrix();
                const ColorRGB color = filament_color(config, volume->extruder_id());
                const indexed_triangle_set& its = volume->mesh().its;
                for (const stl_triangle_vertex_indices& face : its.indices) {
                    Triangle t{matrix * its.vertices[face[0]].cast<double>(), matrix * its.vertices[face[1]].cast<double>(), matrix * its.vertices[face[2]].cast<double>(), color};
                    box.merge(t.a); box.merge(t.b); box.merge(t.c);
                    triangles.push_back(t);
                }
            }
        }

    ThumbnailsList thumbnails;
    if (triangles.empty() || !box.defined)
        return thumbnails;

    // The box the desktop zooms to: the parts' box from the bed up, with 1% / 2% margins.
    BoundingBoxf3 frame = box;
    frame.min.z() = 0;
    const Vec3d size = frame.size();
    frame.min -= Vec3d(size.x() * 0.01, size.y() * 0.01, size.z() * 0.02);
    frame.max += Vec3d(size.x() * 0.01, size.y() * 0.01, size.z() * 0.02);
    double left = DBL_MAX, right = -DBL_MAX, bottom = DBL_MAX, top = -DBL_MAX;
    for (int corner = 0; corner < 8; ++corner) {
        const Vec3d p(corner & 1 ? frame.max.x() : frame.min.x(), corner & 2 ? frame.max.y() : frame.min.y(), corner & 4 ? frame.max.z() : frame.min.z());
        const Vec3d e = view * p;
        left = std::min(left, e.x()); right = std::max(right, e.x()); bottom = std::min(bottom, e.y()); top = std::max(top, e.y());
    }

    for (const Vec2d& requested : params.sizes) {
        const int width = int(std::lround(requested.x())), height = int(std::lround(requested.y()));
        if (width <= 0 || height <= 0)
            continue;
        const int W = width * SUPERSAMPLE, H = height * SUPERSAMPLE;
        // Camera::zoom_to_box's default margin factor.
        const double scale = std::min(W / ((right - left) * 1.025), H / ((top - bottom) * 1.025));
        const double cx = (left + right) / 2, cy = (bottom + top) / 2;
        std::vector<float> depth(size_t(W) * H, -FLT_MAX);
        std::vector<float> rgb(size_t(W) * H * 3, 0.f);
        std::vector<unsigned char> covered(size_t(W) * H, 0);

        for (const Triangle& t : triangles) {
            const Vec3d a = view * t.a, b = view * t.b, c = view * t.c;
            Vec3d normal = (b - a).cross(c - a);
            const double length = normal.norm();
            if (length <= 0)
                continue;
            normal /= length;
            if (normal.z() <= 0) // back face, culled as on the desktop
                continue;
            // gouraud_light: ambient, top light with specular, front light. Flat per face, as the desktop's meshes are.
            double intensity = INTENSITY_AMBIENT + LIGHT_TOP_DIFFUSE * std::max(normal.dot(LIGHT_TOP_DIR), 0.0)
                             + LIGHT_FRONT_DIFFUSE * std::max(normal.dot(LIGHT_FRONT_DIR), 0.0);
            const Vec3d reflected = 2 * normal.dot(LIGHT_TOP_DIR) * normal - LIGHT_TOP_DIR;
            const double specular = LIGHT_TOP_SPECULAR * std::pow(std::max(reflected.z(), 0.0), LIGHT_TOP_SHININESS);
            const ColorRGB& base = t.color;
            const float shade[3] = {float(std::min(1.0, base.r() * intensity + specular)), float(std::min(1.0, base.g() * intensity + specular)),
                                    float(std::min(1.0, base.b() * intensity + specular))};

            // Pixel coordinates; y grows upwards, so row 0 is the bottom row, as glReadPixels returns.
            const double ax = (a.x() - cx) * scale + W / 2.0, ay = (a.y() - cy) * scale + H / 2.0;
            const double bx = (b.x() - cx) * scale + W / 2.0, by = (b.y() - cy) * scale + H / 2.0;
            const double qx = (c.x() - cx) * scale + W / 2.0, qy = (c.y() - cy) * scale + H / 2.0;
            const double area = (bx - ax) * (qy - ay) - (by - ay) * (qx - ax);
            if (std::abs(area) < 1e-12)
                continue;
            const int x0 = std::max(0, int(std::floor(std::min({ax, bx, qx})))), x1 = std::min(W - 1, int(std::ceil(std::max({ax, bx, qx}))));
            const int y0 = std::max(0, int(std::floor(std::min({ay, by, qy})))), y1 = std::min(H - 1, int(std::ceil(std::max({ay, by, qy}))));
            for (int y = y0; y <= y1; ++y)
                for (int x = x0; x <= x1; ++x) {
                    const double px = x + 0.5, py = y + 0.5;
                    const double w0 = ((bx - px) * (qy - py) - (by - py) * (qx - px)) / area;
                    const double w1 = ((qx - px) * (ay - py) - (qy - py) * (ax - px)) / area;
                    const double w2 = 1 - w0 - w1;
                    if (w0 < 0 || w1 < 0 || w2 < 0)
                        continue;
                    const float z = float(w0 * a.z() + w1 * b.z() + w2 * c.z());
                    const size_t i = size_t(y) * W + x;
                    if (z <= depth[i])
                        continue;
                    depth[i] = z; covered[i] = 1;
                    rgb[i * 3] = shade[0]; rgb[i * 3 + 1] = shade[1]; rgb[i * 3 + 2] = shade[2];
                }
        }

        // Downsample: average the covered samples' colour, alpha from coverage (transparent background).
        ThumbnailData& thumbnail = thumbnails.emplace_back();
        thumbnail.set(unsigned(width), unsigned(height));
        for (int y = 0; y < height; ++y)
            for (int x = 0; x < width; ++x) {
                float sum[3] = {0, 0, 0}; int count = 0;
                for (int sy = 0; sy < SUPERSAMPLE; ++sy)
                    for (int sx = 0; sx < SUPERSAMPLE; ++sx) {
                        const size_t i = size_t(y * SUPERSAMPLE + sy) * W + (x * SUPERSAMPLE + sx);
                        if (!covered[i]) continue;
                        sum[0] += rgb[i * 3]; sum[1] += rgb[i * 3 + 1]; sum[2] += rgb[i * 3 + 2]; ++count;
                    }
                unsigned char* pixel = &thumbnail.pixels[(size_t(y) * width + x) * 4];
                if (count == 0) { pixel[0] = pixel[1] = pixel[2] = pixel[3] = 0; continue; }
                for (int k = 0; k < 3; ++k) pixel[k] = (unsigned char) std::lround(std::clamp(sum[k] / count, 0.f, 1.f) * 255);
                pixel[3] = (unsigned char) std::lround(255.0 * count / (SUPERSAMPLE * SUPERSAMPLE));
            }
    }
    return thumbnails;
}

} // namespace linkslicer

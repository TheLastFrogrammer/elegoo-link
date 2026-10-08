// Link Workshop plate view: the models on the printer's bed, to move, turn, scale, copy and remove before slicing.
// The app serves /data/scene.json (bed outline, excluded area, prime tower, printable height, objects with their
// simplified meshes, filament colours and the placements) and /data/mesh/N (the engine's "LKM1" mesh files:
// "LKM1", uint32 triangle count, float32 xyz per corner, uint8 filament per triangle; centered on X/Y, base at Z 0).
// A placement is {file, object, x, y, rotation, scale}: the copy's footprint center on the bed, degrees about Z and
// a uniform scale, as the engine applies them. The app drives the page through window.plate and hears back through
// Android.onReady(), onSelect(index), onChanged(json) and onError(text). onChanged's json is
// {placements, problems, advice, selected}: problems[i] lists the issue keys of copy i ("off the bed", ...) and
// advice[i] the same issues as sentences saying what to do. The page also draws its own labels over the models
// (name of the selected one, what is wrong with a problem one) and a short how-to hint on the first runs.
"use strict";
(function () {
  const canvas = document.getElementById("view");
  const message = document.getElementById("message");
  const android = window.Android || null;
  const gl = canvas.getContext("webgl2", { antialias: true, alpha: false, preserveDrawingBuffer: true });
  function fail(text) {
    message.textContent = text; message.style.display = "flex";
    if (android && android.onError) android.onError(String(text));
  }
  if (!gl) { fail("This device's WebView has no WebGL 2, which the plate view needs."); return; }

  const theme = { background: [0.95, 0.96, 0.965], plate: [0.86, 0.89, 0.9, 1], grid: [0.7, 0.75, 0.77, 1],
    excluded: [0.85, 0.3, 0.25, 0.35], tower: [0.4, 0.45, 0.5, 0.45], selected: [0.0, 0.47, 0.42], problem: [0.86, 0.2, 0.16] };
  const camera = { yaw: -60, pitch: 38, distance: 420, target: [128, 128, 0], fov: 35 };
  const scene = { bed: null, excluded: null, tower: null, height: 256, objects: [], placements: [], colours: [] };
  let selected = -1, dirty = false;

  // ---------------------------------------------------------------- shaders
  function compile(type, source) {
    const shader = gl.createShader(type); gl.shaderSource(shader, source); gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
    return shader;
  }
  function program(vertex, fragment) {
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, vertex)); gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fragment));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const u = {};
    for (let i = 0; i < gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); i++) {
      const name = gl.getActiveUniform(p, i).name.replace(/\[0\]$/, ""); u[name] = gl.getUniformLocation(p, name);
    }
    return { p, u };
  }
  const flat = program(`#version 300 es
    layout(location=0) in vec3 aPosition;
    uniform mat4 uViewProj;
    void main() { gl_Position = uViewProj * vec4(aPosition, 1.0); }`, `#version 300 es
    precision mediump float; uniform vec4 uColor; out vec4 fragment;
    void main() { fragment = uColor; }`);
  const mesh = program(`#version 300 es
    layout(location=0) in vec3 aPosition;
    layout(location=1) in vec3 aNormal;
    layout(location=2) in float aFilament;
    uniform mat4 uViewProj, uModel, uView;
    uniform vec3 uColors[16];
    uniform int uSlot;   // the filament slot the whole file prints with, or 0 for the file's own per-part filaments
    out vec3 vNormal; out vec3 vColor;
    void main() {
      vNormal = mat3(uView) * mat3(uModel) * aNormal;
      vColor = uColors[uSlot > 0 ? min(uSlot - 1, 15) : int(clamp(aFilament - 1.0, 0.0, 15.0) + 0.5)];
      gl_Position = uViewProj * uModel * vec4(aPosition, 1.0);
    }`, `#version 300 es
    precision mediump float;
    in vec3 vNormal; in vec3 vColor;
    uniform vec3 uTint; uniform float uTintAmount; uniform float uPeriod;
    out vec4 fragment;
    void main() {
      vec3 n = normalize(vNormal);
      vec3 key = normalize(vec3(0.35, 0.55, 0.75)), fill = normalize(vec3(-0.6, -0.2, 0.5));
      float light = 0.38 + 0.55 * max(dot(n, key), 0.0) + 0.2 * max(dot(n, fill), 0.0);
      // A model with a problem is striped, so it never reads as just "a red filament": the stripe is the problem colour,
      // or, where the filament itself is close to that colour, white (dark red on a light filament).
      vec3 stripe = distance(vColor, uTint) < 0.4 ? (dot(vColor, vec3(0.3, 0.6, 0.1)) > 0.5 ? vec3(0.5, 0.05, 0.05) : vec3(1.0)) : uTint;
      float band = uTintAmount > 0.0 ? step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / uPeriod)) : 0.0;
      vec3 base = mix(vColor, stripe, band * 0.9);
      fragment = vec4(min(base * light, vec3(1.0)), 1.0);
    }`);

  // ---------------------------------------------------------------- math
  function perspective(fov, aspect, near, far) {
    const f = 1 / Math.tan(fov * Math.PI / 360), nf = 1 / (near - far);
    return [f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) * nf, -1, 0, 0, 2 * far * near * nf, 0];
  }
  function lookAt(eye, target, up) {
    let zx = eye[0] - target[0], zy = eye[1] - target[1], zz = eye[2] - target[2];
    let l = Math.hypot(zx, zy, zz); zx /= l; zy /= l; zz /= l;
    let xx = up[1] * zz - up[2] * zy, xy = up[2] * zx - up[0] * zz, xz = up[0] * zy - up[1] * zx;
    l = Math.hypot(xx, xy, xz); xx /= l; xy /= l; xz /= l;
    const yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
    return [xx, yx, zx, 0, xy, yy, zy, 0, xz, yz, zz, 0,
      -(xx * eye[0] + xy * eye[1] + xz * eye[2]), -(yx * eye[0] + yy * eye[1] + yz * eye[2]), -(zx * eye[0] + zy * eye[1] + zz * eye[2]), 1];
  }
  function multiply(a, b) {
    const out = new Array(16);
    for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) { let s = 0; for (let k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k]; out[c * 4 + r] = s; }
    return out;
  }
  function invert(m) {
    const inv = new Array(16);
    inv[0] = m[5]*m[10]*m[15]-m[5]*m[11]*m[14]-m[9]*m[6]*m[15]+m[9]*m[7]*m[14]+m[13]*m[6]*m[11]-m[13]*m[7]*m[10];
    inv[4] = -m[4]*m[10]*m[15]+m[4]*m[11]*m[14]+m[8]*m[6]*m[15]-m[8]*m[7]*m[14]-m[12]*m[6]*m[11]+m[12]*m[7]*m[10];
    inv[8] = m[4]*m[9]*m[15]-m[4]*m[11]*m[13]-m[8]*m[5]*m[15]+m[8]*m[7]*m[13]+m[12]*m[5]*m[11]-m[12]*m[7]*m[9];
    inv[12] = -m[4]*m[9]*m[14]+m[4]*m[10]*m[13]+m[8]*m[5]*m[14]-m[8]*m[6]*m[13]-m[12]*m[5]*m[10]+m[12]*m[6]*m[9];
    inv[1] = -m[1]*m[10]*m[15]+m[1]*m[11]*m[14]+m[9]*m[2]*m[15]-m[9]*m[3]*m[14]-m[13]*m[2]*m[11]+m[13]*m[3]*m[10];
    inv[5] = m[0]*m[10]*m[15]-m[0]*m[11]*m[14]-m[8]*m[2]*m[15]+m[8]*m[3]*m[14]+m[12]*m[2]*m[11]-m[12]*m[3]*m[10];
    inv[9] = -m[0]*m[9]*m[15]+m[0]*m[11]*m[13]+m[8]*m[1]*m[15]-m[8]*m[3]*m[13]-m[12]*m[1]*m[11]+m[12]*m[3]*m[9];
    inv[13] = m[0]*m[9]*m[14]-m[0]*m[10]*m[13]-m[8]*m[1]*m[14]+m[8]*m[2]*m[13]+m[12]*m[1]*m[10]-m[12]*m[2]*m[9];
    inv[2] = m[1]*m[6]*m[15]-m[1]*m[7]*m[14]-m[5]*m[2]*m[15]+m[5]*m[3]*m[14]+m[13]*m[2]*m[7]-m[13]*m[3]*m[6];
    inv[6] = -m[0]*m[6]*m[15]+m[0]*m[7]*m[14]+m[4]*m[2]*m[15]-m[4]*m[3]*m[14]-m[12]*m[2]*m[7]+m[12]*m[3]*m[6];
    inv[10] = m[0]*m[5]*m[15]-m[0]*m[7]*m[13]-m[4]*m[1]*m[15]+m[4]*m[3]*m[13]+m[12]*m[1]*m[7]-m[12]*m[3]*m[5];
    inv[14] = -m[0]*m[5]*m[14]+m[0]*m[6]*m[13]+m[4]*m[1]*m[14]-m[4]*m[2]*m[13]-m[12]*m[1]*m[6]+m[12]*m[2]*m[5];
    inv[3] = -m[1]*m[6]*m[11]+m[1]*m[7]*m[10]+m[5]*m[2]*m[11]-m[5]*m[3]*m[10]-m[9]*m[2]*m[7]+m[9]*m[3]*m[6];
    inv[7] = m[0]*m[6]*m[11]-m[0]*m[7]*m[10]-m[4]*m[2]*m[11]+m[4]*m[3]*m[10]+m[8]*m[2]*m[7]-m[8]*m[3]*m[6];
    inv[11] = -m[0]*m[5]*m[11]+m[0]*m[7]*m[9]+m[4]*m[1]*m[11]-m[4]*m[3]*m[9]-m[8]*m[1]*m[7]+m[8]*m[3]*m[5];
    inv[15] = m[0]*m[5]*m[10]-m[0]*m[6]*m[9]-m[4]*m[1]*m[10]+m[4]*m[2]*m[9]+m[8]*m[1]*m[6]-m[8]*m[2]*m[5];
    const det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
    return inv.map((v) => v / det);
  }
  function transform(m, p, w) {
    return [m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12] * w, m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13] * w,
            m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14] * w, m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15] * w];
  }
  function eye() {
    const yaw = camera.yaw * Math.PI / 180, pitch = camera.pitch * Math.PI / 180;
    return [camera.target[0] + camera.distance * Math.cos(pitch) * Math.cos(yaw),
      camera.target[1] + camera.distance * Math.cos(pitch) * Math.sin(yaw), camera.target[2] + camera.distance * Math.sin(pitch)];
  }
  function matrices() {
    const width = canvas.width, height = canvas.height;
    const view = lookAt(eye(), camera.target, [0, 0, 1]);
    const projection = perspective(camera.fov, width / Math.max(1, height), Math.max(0.5, camera.distance / 500), camera.distance * 20);
    return { view, viewProj: multiply(projection, view) };
  }

  // ---------------------------------------------------------------- placements
  // The footprint of a copy turned by `rotation` and scaled: the bounding box of its mesh, relative to the mesh
  // origin. The engine centers that box on (x, y), so the mesh origin goes to (x, y) minus the box center.
  // The turn laying the face with outward normal `down` on the bed, as a row-major 3x3 (the engine's lay_down).
  function layDown(down) {
    if (!down) return [1, 0, 0, 0, 1, 0, 0, 0, 1];
    let [x, y, z] = down; const l = Math.hypot(x, y, z);
    if (l < 1e-9) return [1, 0, 0, 0, 1, 0, 0, 0, 1];
    x /= l; y /= l; z /= l;
    const c = Math.max(-1, Math.min(1, -z));               // n · (0, 0, -1)
    if (c > 1 - 1e-9) return [1, 0, 0, 0, 1, 0, 0, 0, 1];
    if (c < -1 + 1e-9) return [1, 0, 0, 0, -1, 0, 0, 0, -1];  // half a turn about X
    let ax = -y, ay = x, az = 0;                            // n × (0, 0, -1)
    const al = Math.hypot(ax, ay, az); ax /= al; ay /= al; az /= al;
    const angle = Math.acos(c), s = Math.sin(angle), t = 1 - Math.cos(angle), k = Math.cos(angle);
    return [t * ax * ax + k, t * ax * ay - s * az, t * ax * az + s * ay,
            t * ax * ay + s * az, t * ay * ay + k, t * ay * az - s * ax,
            t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + k];
  }
  // The copy's linear part: turned about Z and scaled, after laying a face down (row-major 3x3).
  function linear(p) {
    const L = layDown(p.down), c = Math.cos(p.rotation * Math.PI / 180) * p.scale, s = Math.sin(p.rotation * Math.PI / 180) * p.scale;
    const R = [c, -s, 0, s, c, 0, 0, 0, p.scale];
    const out = new Array(9);
    for (let r = 0; r < 3; r++) for (let q = 0; q < 3; q++) out[r * 3 + q] = R[r * 3] * L[q] + R[r * 3 + 1] * L[3 + q] + R[r * 3 + 2] * L[6 + q];
    return out;
  }
  // The bounding box of the copy's mesh under its turn and scale, relative to the mesh origin. The engine centers the
  // box's footprint on (x, y) and rests its bottom on the bed.
  function footprint(object, p) {
    const key = p.rotation.toFixed(3) + "/" + p.scale.toFixed(4) + "/" + (p.down ? p.down.map((v) => v.toFixed(4)).join(",") : "");
    if (object.footprints[key]) return object.footprints[key];
    const m = linear(p), v = object.positions;
    let minX = Infinity, minY = Infinity, minZ = Infinity, maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
    for (let i = 0; i < v.length; i += 3) {
      const x = m[0] * v[i] + m[1] * v[i + 1] + m[2] * v[i + 2], y = m[3] * v[i] + m[4] * v[i + 1] + m[5] * v[i + 2], z = m[6] * v[i] + m[7] * v[i + 1] + m[8] * v[i + 2];
      if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
    }
    if (!isFinite(minX)) { minX = minY = minZ = maxX = maxY = maxZ = 0; }
    const result = { cx: (minX + maxX) / 2, cy: (minY + maxY) / 2, w: maxX - minX, d: maxY - minY, h: maxZ - minZ, z: minZ };
    if (Object.keys(object.footprints).length > 64) object.footprints = {};
    object.footprints[key] = result;
    return result;
  }
  function objectOf(p) { return scene.objects.find((o) => o.file === p.file && o.object === p.object); }
  function modelMatrix(p) {
    const box = footprint(objectOf(p), p), m = linear(p);
    return [m[0], m[3], m[6], 0, m[1], m[4], m[7], 0, m[2], m[5], m[8], 0, p.x - box.cx, p.y - box.cy, -box.z, 1];
  }
  function rect(p) {
    const box = footprint(objectOf(p), p);
    return [p.x - box.w / 2, p.y - box.d / 2, p.x + box.w / 2, p.y + box.d / 2, box.h];
  }
  function overlaps(a, b) { return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]; }
  function boxOf(points) {
    if (!points || points.length < 3) return null;
    const xs = points.map((p) => p[0]), ys = points.map((p) => p[1]);
    return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
  }
  // What is wrong with each copy: off the bed, in the excluded area or the prime tower, too tall, touching another.
  function problems() {
    const bed = boxOf(scene.bed), excluded = boxOf(scene.excluded), rects = scene.placements.map(rect);
    return rects.map((r, i) => {
      const issues = [];
      if (bed && (r[0] < bed[0] - 0.01 || r[1] < bed[1] - 0.01 || r[2] > bed[2] + 0.01 || r[3] > bed[3] + 0.01)) issues.push("off the bed");
      if (excluded && overlaps(r, excluded)) issues.push("in the excluded area");
      if (scene.tower && overlaps(r, scene.tower)) issues.push("on the prime tower");
      if (r[4] > scene.height + 0.01) issues.push("taller than the printer");
      for (let j = 0; j < rects.length; j++) if (j !== i && overlaps(r, rects[j])) { issues.push("touching another copy"); break; }
      return issues;
    });
  }
  // Each issue as a short title and what to do about it, for the labels over the models and the panel's status.
  function advice(issue) {
    switch (issue) {
      case "off the bed": return ["Off the bed", "drag it back or tap Arrange all"];
      case "in the excluded area": return ["In a no-print zone (red)", "drag it clear"];
      case "on the prime tower": return ["On the prime tower", "drag it clear or tap Arrange all"];
      case "taller than the printer": return ["Too tall (max " + Math.round(scene.height) + " mm)", "scale it down, or More… > Lay flat"];
      case "touching another copy": return ["Overlapping another model", "drag them apart or tap Arrange all"];
      default: return [issue, ""];
    }
  }
  function changed() {
    redraw();
    const found = problems();
    if (android && android.onChanged) android.onChanged(JSON.stringify({ placements: scene.placements, problems: found,
      advice: found.map((list) => list.map((issue) => { const a = advice(issue); return a[1] ? a[0] + ": " + a[1] : a[0]; })), selected }));
  }

  // ---------------------------------------------------------------- labels over the models
  const tags = document.getElementById("tags"), modeBanner = document.getElementById("mode"), hint = document.getElementById("hint");
  function readable(rgb) { return 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2] > 0.6 ? "#10181c" : "#ffffff"; }
  function css(rgb) { return "rgb(" + rgb.slice(0, 3).map((c) => Math.round(c * 255)).join(",") + ")"; }
  function updateTags(issues, viewProj) {
    const width = canvas.clientWidth, height = canvas.clientHeight; let used = 0; const said = new Set();
    scene.placements.forEach((p, i) => {
      const bad = issues[i].length > 0; if (!bad && i !== selected) return;
      const key = bad ? issues[i].join() : ""; if (bad && said.has(key)) return; said.add(key);
      const object = objectOf(p); if (!object) return;
      const r = rect(p), clip = transform(viewProj, [p.x, p.y, r[4]], 1); if (clip[3] <= 0) return;
      let el = tags.children[used]; if (!el) { el = document.createElement("div"); el.className = "tag"; tags.appendChild(el); }
      used++;
      const parts = issues[i].map(advice);
      el.textContent = bad ? "\u26A0 " + parts[0][0] + (parts[0][1] ? ": " + parts[0][1] : "") + (parts.length > 1 ? " (+" + (parts.length - 1) + " more)" : "") : (object.name || "Model");
      const colour = bad ? theme.problem : theme.selected;
      el.style.background = css(colour); el.style.color = readable(colour); el.style.display = "block";
      const half = el.offsetWidth / 2;
      el.style.left = Math.max(half + 6, Math.min(width - half - 6, (clip[0] / clip[3] * 0.5 + 0.5) * width)) + "px";
      el.style.top = Math.max(el.offsetHeight + 6, (0.5 - clip[1] / clip[3] * 0.5) * height - 8) + "px";
    });
    while (tags.children.length > used) tags.removeChild(tags.lastChild);
  }
  function showMode(text) { modeBanner.textContent = text || ""; modeBanner.style.display = text ? "block" : "none"; }
  // A short how-to over the plate for the first few visits, gone at the first touch.
  function showHint() {
    let n = 0; try { n = +localStorage.getItem("plateHints") || 0; } catch (e) { }
    if (n >= 3) return;
    try { localStorage.setItem("plateHints", String(n + 1)); } catch (e) { }
    hint.style.whiteSpace = "pre-line";
    hint.textContent = "Drag a model to move it\nDrag empty space to turn the view \u00B7 two fingers to move and zoom \u00B7 double-tap to reset";
    hint.style.display = "block";
  }
  function hideHint() { hint.style.display = "none"; }

  // ---------------------------------------------------------------- static geometry
  let plateVao, gridVao, gridCount, excludedVao, towerVao;
  function quad(box, z) { return [box[0], box[1], z, box[2], box[1], z, box[2], box[3], z, box[0], box[1], z, box[2], box[3], z, box[0], box[3], z]; }
  function vao(data) {
    const v = gl.createVertexArray(); gl.bindVertexArray(v);
    const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0); gl.bindVertexArray(null);
    return v;
  }
  function setupBed() {
    const bed = boxOf(scene.bed) || [0, 0, 256, 256];
    plateVao = vao(quad(bed, -0.05));
    const lines = [];
    for (let x = Math.ceil(bed[0] / 10) * 10; x <= bed[2]; x += 10) lines.push(x, bed[1], 0, x, bed[3], 0);
    for (let y = Math.ceil(bed[1] / 10) * 10; y <= bed[3]; y += 10) lines.push(bed[0], y, 0, bed[2], y, 0);
    gridVao = vao(lines); gridCount = lines.length / 3;
    const excluded = boxOf(scene.excluded); excludedVao = excluded ? vao(quad(excluded, 0.02)) : null;
    towerVao = scene.tower ? vao(quad(scene.tower, 0.03)) : null;
    resetView();
  }
  function resetView() {
    const bed = boxOf(scene.bed) || [0, 0, 256, 256];
    camera.yaw = -60; camera.pitch = 38;
    camera.target = [(bed[0] + bed[2]) / 2, (bed[1] + bed[3]) / 2, 0];
    camera.distance = Math.max(bed[2] - bed[0], bed[3] - bed[1]) * 1.25 / Math.tan(camera.fov * Math.PI / 360) / 2;
  }
  function setupMesh(object, buffer) {
    const bytes = new DataView(buffer);
    if (buffer.byteLength < 8 || String.fromCharCode(bytes.getUint8(0), bytes.getUint8(1), bytes.getUint8(2), bytes.getUint8(3)) !== "LKM1")
      throw new Error("Unexpected mesh data");
    const count = bytes.getUint32(4, true);
    const positions = new Float32Array(buffer, 8, count * 9), filament = new Uint8Array(buffer, 8 + count * 36, count);
    const normals = new Float32Array(count * 9), filaments = new Float32Array(count * 3);
    for (let t = 0; t < count; t++) {
      const o = t * 9;
      const ax = positions[o + 3] - positions[o], ay = positions[o + 4] - positions[o + 1], az = positions[o + 5] - positions[o + 2];
      const bx = positions[o + 6] - positions[o], by = positions[o + 7] - positions[o + 1], bz = positions[o + 8] - positions[o + 2];
      let nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx; const l = Math.hypot(nx, ny, nz) || 1;
      nx /= l; ny /= l; nz /= l;
      for (let k = 0; k < 3; k++) { normals[o + k * 3] = nx; normals[o + k * 3 + 1] = ny; normals[o + k * 3 + 2] = nz; filaments[t * 3 + k] = filament[t]; }
    }
    object.positions = positions; object.count = count * 3; object.footprints = {};
    let min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
    for (let i = 0; i < positions.length; i += 3) for (let k = 0; k < 3; k++) { min[k] = Math.min(min[k], positions[i + k]); max[k] = Math.max(max[k], positions[i + k]); }
    if (!isFinite(min[0])) { min = [0, 0, 0]; max = [0, 0, 0]; }
    object.box = [min, max];
    object.vao = gl.createVertexArray(); gl.bindVertexArray(object.vao);
    [[positions, 0, 3], [normals, 1, 3], [filaments, 2, 1]].forEach(([data, location, size]) => {
      const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(location); gl.vertexAttribPointer(location, size, gl.FLOAT, false, 0, 0);
    });
    gl.bindVertexArray(null);
  }

  // ---------------------------------------------------------------- drawing
  function colours() {
    const out = new Float32Array(48);
    for (let i = 0; i < 16; i++) {
      const hex = scene.colours[i] || scene.colours[0] || "#F2754E";
      for (let c = 0; c < 3; c++) out[i * 3 + c] = parseInt(hex.substr(1 + 2 * c, 2), 16) / 255;
    }
    return out;
  }
  function render() {
    dirty = false;
    const width = Math.round(canvas.clientWidth * devicePixelRatio), height = Math.round(canvas.clientHeight * devicePixelRatio);
    if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
    gl.viewport(0, 0, width, height);
    gl.clearColor(theme.background[0], theme.background[1], theme.background[2], 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    if (!plateVao) return;
    const { view, viewProj } = matrices();
    gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL); gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(flat.p); gl.uniformMatrix4fv(flat.u.uViewProj, false, viewProj);
    gl.uniform4fv(flat.u.uColor, theme.plate); gl.bindVertexArray(plateVao); gl.drawArrays(gl.TRIANGLES, 0, 6);
    gl.uniform4fv(flat.u.uColor, theme.grid); gl.bindVertexArray(gridVao); gl.drawArrays(gl.LINES, 0, gridCount);
    if (excludedVao) { gl.uniform4fv(flat.u.uColor, theme.excluded); gl.bindVertexArray(excludedVao); gl.drawArrays(gl.TRIANGLES, 0, 6); }
    if (towerVao) { gl.uniform4fv(flat.u.uColor, theme.tower); gl.bindVertexArray(towerVao); gl.drawArrays(gl.TRIANGLES, 0, 6); }
    gl.useProgram(mesh.p); gl.uniformMatrix4fv(mesh.u.uViewProj, false, viewProj); gl.uniformMatrix4fv(mesh.u.uView, false, view);
    gl.uniform3fv(mesh.u.uColors, colours());
    const issues = problems();
    scene.placements.forEach((p, i) => {
      const object = objectOf(p); if (!object || !object.vao) return;
      gl.uniformMatrix4fv(mesh.u.uModel, false, modelMatrix(p));
      gl.uniform1i(mesh.u.uSlot, object.slot || 0);
      const bad = issues[i].length > 0;
      gl.uniform3fv(mesh.u.uTint, theme.problem);
      gl.uniform1f(mesh.u.uTintAmount, bad ? 1 : 0);
      gl.uniform1f(mesh.u.uPeriod, 18 * devicePixelRatio);
      gl.bindVertexArray(object.vao); gl.drawArrays(gl.TRIANGLES, 0, object.count);
    });
    // Rings on the bed around the footprints: the selected model in the selection colour, one with a problem in the problem
    // colour (wider), so neither depends on the filament colour of the model itself.
    gl.useProgram(flat.p); gl.uniformMatrix4fv(flat.u.uViewProj, false, viewProj);
    scene.placements.forEach((p, i) => {
      if (!objectOf(p)) return;
      const r = rect(p);
      if (issues[i].length > 0) ring(r, 3.5, 1.8, theme.problem);
      if (i === selected) ring(r, 1.2, 1.8, theme.selected);
    });
    gl.bindVertexArray(null);
    updateTags(issues, viewProj);
  }
  let ringVao, ringBuffer;
  function ring(r, gap, width, colour) {
    if (!ringVao) {
      ringVao = gl.createVertexArray(); gl.bindVertexArray(ringVao);
      ringBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, ringBuffer); gl.bufferData(gl.ARRAY_BUFFER, 24 * 3 * 4, gl.DYNAMIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    }
    const x0 = r[0] - gap, y0 = r[1] - gap, x1 = r[2] + gap, y1 = r[3] + gap, w = width, z = 0.08, v = [];
    [[x0 - w, y0 - w, x1 + w, y0], [x0 - w, y1, x1 + w, y1 + w], [x0 - w, y0, x0, y1], [x1, y0, x1 + w, y1]].forEach((q) => v.push(...quad(q, z)));
    gl.bindVertexArray(ringVao); gl.bindBuffer(gl.ARRAY_BUFFER, ringBuffer); gl.bufferSubData(gl.ARRAY_BUFFER, 0, new Float32Array(v));
    gl.uniform4f(flat.u.uColor, colour[0], colour[1], colour[2], 1);
    gl.disable(gl.DEPTH_TEST); gl.drawArrays(gl.TRIANGLES, 0, 24); gl.enable(gl.DEPTH_TEST);
  }
  function redraw() { if (!dirty) { dirty = true; requestAnimationFrame(render); } }

  // ---------------------------------------------------------------- picking and dragging
  function ray(clientX, clientY) {
    const rectangle = canvas.getBoundingClientRect();
    const x = (clientX - rectangle.left) / rectangle.width * 2 - 1, y = 1 - (clientY - rectangle.top) / rectangle.height * 2;
    const inverse = invert(matrices().viewProj);
    const near = transform(inverse, [x, y, -1], 1), far = transform(inverse, [x, y, 1], 1);
    const a = near.slice(0, 3).map((v) => v / near[3]), b = far.slice(0, 3).map((v) => v / far[3]);
    return { origin: a, direction: [b[0] - a[0], b[1] - a[1], b[2] - a[2]] };
  }
  // The nearest copy whose (object-space) box the ray passes through.
  function pick(clientX, clientY) {
    const r = ray(clientX, clientY); let best = -1, bestT = Infinity;
    scene.placements.forEach((p, i) => {
      const object = objectOf(p); if (!object || !object.box) return;
      const inverse = invert(modelMatrix(p));
      const o = transform(inverse, r.origin, 1), d = transform(inverse, r.direction, 0);
      let t0 = -Infinity, t1 = Infinity;
      for (let k = 0; k < 3; k++) {
        const lo = object.box[0][k], hi = object.box[1][k];
        if (Math.abs(d[k]) < 1e-12) { if (o[k] < lo || o[k] > hi) return; continue; }
        let a = (lo - o[k]) / d[k], b = (hi - o[k]) / d[k]; if (a > b) [a, b] = [b, a];
        t0 = Math.max(t0, a); t1 = Math.min(t1, b); if (t0 > t1) return;
      }
      if (t1 >= 0 && t0 < bestT) { bestT = t0; best = i; }
    });
    return best;
  }
  // The outward normal, in the mesh's own frame, of the triangle of copy `index` under the screen point, or null.
  function faceAt(clientX, clientY, index) {
    const p = scene.placements[index], object = p && objectOf(p); if (!object) return null;
    const r = ray(clientX, clientY), inverse = invert(modelMatrix(p));
    const o = transform(inverse, r.origin, 1), d = transform(inverse, r.direction, 0), v = object.positions;
    let best = Infinity, normal = null;
    for (let i = 0; i < v.length; i += 9) {
      const e1 = [v[i + 3] - v[i], v[i + 4] - v[i + 1], v[i + 5] - v[i + 2]], e2 = [v[i + 6] - v[i], v[i + 7] - v[i + 1], v[i + 8] - v[i + 2]];
      const h = [d[1] * e2[2] - d[2] * e2[1], d[2] * e2[0] - d[0] * e2[2], d[0] * e2[1] - d[1] * e2[0]];
      const a = e1[0] * h[0] + e1[1] * h[1] + e1[2] * h[2]; if (Math.abs(a) < 1e-12) continue;
      const f = 1 / a, sv = [o[0] - v[i], o[1] - v[i + 1], o[2] - v[i + 2]];
      const u = f * (sv[0] * h[0] + sv[1] * h[1] + sv[2] * h[2]); if (u < 0 || u > 1) continue;
      const q = [sv[1] * e1[2] - sv[2] * e1[1], sv[2] * e1[0] - sv[0] * e1[2], sv[0] * e1[1] - sv[1] * e1[0]];
      const w = f * (d[0] * q[0] + d[1] * q[1] + d[2] * q[2]); if (w < 0 || u + w > 1) continue;
      const t = f * (e2[0] * q[0] + e2[1] * q[1] + e2[2] * q[2]);
      if (t > 0 && t < best) {
        best = t;
        const n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]], l = Math.hypot(...n) || 1;
        normal = n.map((x) => Math.round(x / l * 1e5) / 1e5);
      }
    }
    return normal;
  }
  let layMode = false;
  function onBed(clientX, clientY) {
    const r = ray(clientX, clientY);
    if (Math.abs(r.direction[2]) < 1e-9) return null;
    const t = -r.origin[2] / r.direction[2];
    return t > 0 ? [r.origin[0] + r.direction[0] * t, r.origin[1] + r.direction[1] * t] : null;
  }
  function select(index) {
    selected = index; redraw();
    if (android && android.onSelect) android.onSelect(index);
  }

  const pointers = new Map();
  let drag = null, tapStart = null, moved = false, lastTap = 0;
  canvas.addEventListener("pointerdown", (e) => {
    canvas.setPointerCapture(e.pointerId); pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    moved = false; hideHint();
    if (pointers.size === 1) {
      tapStart = { x: e.clientX, y: e.clientY, t: Date.now() };
      const hit = pick(e.clientX, e.clientY), point = onBed(e.clientX, e.clientY);
      if (hit >= 0 && point) {
        if (hit !== selected) select(hit);
        drag = { index: hit, dx: scene.placements[hit].x - point[0], dy: scene.placements[hit].y - point[1] };
      } else drag = null;
    } else { drag = null; tapStart = null; }
  });
  canvas.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    const before = [...pointers.values()].map((p) => ({ ...p }));
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const after = [...pointers.values()];
    if (tapStart && Math.hypot(e.clientX - tapStart.x, e.clientY - tapStart.y) > 8) moved = true;
    if (after.length === 1 && drag) {
      if (!moved) return;
      const point = onBed(e.clientX, e.clientY); if (!point) return;
      const p = scene.placements[drag.index];
      p.x = Math.round((point[0] + drag.dx) * 10) / 10; p.y = Math.round((point[1] + drag.dy) * 10) / 10;
      redraw(); return;
    }
    if (after.length === 1) {
      const dx = after[0].x - before[0].x, dy = after[0].y - before[0].y;
      camera.yaw -= dx * 0.35; camera.pitch = Math.max(5, Math.min(89, camera.pitch + dy * 0.35));
    } else if (after.length >= 2) {
      const span = (ps) => Math.hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y), mid = (ps) => [(ps[0].x + ps[1].x) / 2, (ps[0].y + ps[1].y) / 2];
      const s0 = span(before), s1 = span(after);
      if (s0 > 0 && s1 > 0) camera.distance = Math.max(20, Math.min(3000, camera.distance * s0 / s1));
      const [mx0, my0] = mid(before), [mx1, my1] = mid(after); pan(mx1 - mx0, my1 - my0);
    }
    redraw();
  });
  function pan(dx, dy) {
    const yaw = camera.yaw * Math.PI / 180, pitch = camera.pitch * Math.PI / 180;
    const right = [-Math.sin(yaw), Math.cos(yaw), 0];
    const up = [-Math.sin(pitch) * Math.cos(yaw), -Math.sin(pitch) * Math.sin(yaw), Math.cos(pitch)];
    const scale = camera.distance * 2 * Math.tan(camera.fov * Math.PI / 360) / Math.max(1, canvas.clientHeight);
    for (let i = 0; i < 3; i++) camera.target[i] += (-dx * right[i] + dy * up[i]) * scale;
  }
  function release(e) {
    pointers.delete(e.pointerId);
    if (drag && moved) changed();
    else if (tapStart && !moved && Date.now() - tapStart.t < 400) {
      const hit = pick(e.clientX, e.clientY);
      if (layMode && hit >= 0) {
        const normal = faceAt(e.clientX, e.clientY, hit);
        if (normal) { scene.placements[hit].down = normal; scene.placements[hit].rotation = 0; layMode = false; showMode(""); select(hit); changed();
          if (android && android.onLayDone) android.onLayDone(true); }
      } else if (hit < 0) {
        select(-1);
        const now = Date.now(); if (now - lastTap < 350) resetView(); lastTap = now; redraw();
      }
    }
    drag = null; tapStart = null;
  }
  canvas.addEventListener("pointerup", release);
  canvas.addEventListener("pointercancel", release);
  canvas.addEventListener("wheel", (e) => { e.preventDefault(); camera.distance = Math.max(20, Math.min(3000, camera.distance * Math.exp(e.deltaY * 0.001))); redraw(); }, { passive: false });
  window.addEventListener("resize", redraw);

  // ---------------------------------------------------------------- API for the app
  async function load() {
    try {
      const data = await fetch("/data/scene.json").then((r) => r.json());
      Object.assign(scene, { bed: data.bed, excluded: data.excluded, tower: data.tower || null, height: data.height || 256,
        colours: data.colours || [], placements: data.placements || [] });
      scene.objects = data.objects.map((o) => Object.assign({}, o, { footprints: {} }));
      await Promise.all(scene.objects.map((o, i) => fetch("/data/mesh/" + i).then((r) => r.arrayBuffer()).then((b) => setupMesh(o, b))));
      setupBed(); select(scene.placements.length === 1 ? 0 : -1); changed(); showHint();
      if (android && android.onLoaded) android.onLoaded(scene.objects.length);
    } catch (error) { fail("The plate could not be shown: " + error.message); }
  }
  function current() { return selected >= 0 && selected < scene.placements.length ? scene.placements[selected] : null; }
  const api = {
    load,
    setTheme(next) { for (const key of Object.keys(next)) if (key in theme) theme[key] = next[key];
      document.body.style.setProperty("--bg", "rgb(" + theme.background.map((c) => Math.round(c * 255)).join(",") + ")"); redraw(); },
    setPlacements(list) { scene.placements = list; if (selected >= list.length) selected = -1; select(selected); changed(); },
    setColours(list) { scene.colours = list; redraw(); },
    select(index) { select(index); },
    rotate(degrees) { const p = current(); if (!p) return; p.rotation = ((p.rotation + degrees) % 360 + 540) % 360 - 180; changed(); },
    setScale(scale) { const p = current(); if (!p || !(scale > 0.001)) return; p.scale = scale; changed(); },
    duplicate() {
      const p = current(); if (!p) return;
      const r = rect(p), copy = Object.assign({}, p, { x: p.x + (r[2] - r[0]) + 5 });
      const bed = boxOf(scene.bed);
      if (bed && copy.x + (r[2] - r[0]) / 2 > bed[2]) { copy.x = p.x; copy.y = p.y - (r[3] - r[1]) - 5; }
      scene.placements.push(copy); select(scene.placements.length - 1); changed();
    },
    remove() { if (!current()) return; scene.placements.splice(selected, 1); select(-1); changed(); },
    setLayMode(on) { layMode = !!on; showMode(layMode ? "Tap the face of the model that should lie flat on the bed" : ""); },
    upright() { const p = current(); if (!p) return; delete p.down; p.rotation = 0; changed(); },
    resetCamera() { resetView(); redraw(); },
    redraw, camera, scene,
  };
  window.plate = api;
  if (android && android.onReady) android.onReady();
})();

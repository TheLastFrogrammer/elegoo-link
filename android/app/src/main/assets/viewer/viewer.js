// Link Workshop G-code viewer: WebGL2, one instanced box per extrusion segment (width and height from the G-code),
// shaded as a rounded bead and colored by feature type. Data comes from the app (GcodeToolpath) at /data/*:
//   meta.json      layer starts, travel starts, layer Z, bed outline, feature names
//   segments.bin   float32 x0 y0 z0 x1 y1 z1 width height type, per segment
//   travels.bin    float32 x0 y0 z0 x1 y1 z1, per travel move
// The app drives it through window.viewer: load(), update(state), setTheme(theme), resetCamera(), setView("top"|"iso").
"use strict";
(function () {
  const STRIDE = 9 * 4;
  // Feature colors in GcodeToolpath.FEATURES order, close to ElegooSlicer's/OrcaSlicer's preview.
  const PALETTE = ["#ff7d38", "#ffd24d", "#3366ff", "#b03029", "#9654cc", "#f04848", "#5f9e5f", "#4d80ba", "#e8e8e8",
    "#00876e", "#00876e", "#45c445", "#1e7b3b", "#6a8f6a", "#b3b3b3", "#ffb3b3", "#5ed9c7", "#9a9a9a"];
  const canvas = document.getElementById("view");
  const message = document.getElementById("message");
  const android = window.Android || null;
  const gl = canvas.getContext("webgl2", { antialias: true, alpha: false, preserveDrawingBuffer: true });

  function fail(text) {
    message.textContent = text; message.style.display = "flex";
    if (android && android.onError) android.onError(String(text));
  }
  if (!gl) { fail("This device's WebView has no WebGL 2, which the toolpath viewer needs."); return; }

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
    const uniforms = {};
    for (let i = 0; i < gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); i++) {
      const name = gl.getActiveUniform(p, i).name.replace(/\[0\]$/, "");
      uniforms[name] = gl.getUniformLocation(p, name);
    }
    return { p, u: uniforms };
  }

  const bead = program(`#version 300 es
    layout(location=0) in vec3 aCorner;   // along (0..1), side (-0.5..0.5), up (-1..0)
    layout(location=1) in vec3 aNormal;   // normal in the segment's frame: along, side, up
    layout(location=2) in vec3 aStart;
    layout(location=3) in vec3 aEnd;
    layout(location=4) in vec2 aSize;     // width, height
    layout(location=5) in float aType;
    uniform mat4 uViewProj, uView;
    uniform vec3 uColors[18];
    uniform int uHidden;
    uniform int uBase, uDimBelow;   // index of the first instance drawn; instances before uDimBelow are dimmed
    uniform vec3 uDimColor;
    out vec3 vNormal; out vec3 vColor;
    void main() {
      int type = int(aType + 0.5);
      if (((uHidden >> type) & 1) == 1) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
      vec3 dir = aEnd - aStart; float len = length(dir);
      vec3 d = len > 1e-6 ? dir / len : vec3(1.0, 0.0, 0.0);
      vec3 side = cross(vec3(0.0, 0.0, 1.0), d); float sideLength = length(side);
      side = sideLength > 1e-4 ? side / sideLength : vec3(0.0, 1.0, 0.0);
      vec3 up = cross(d, side);
      float w = aSize.x, h = aSize.y;
      // Each bead reaches half a width past its end points, so corners close like a real extrusion.
      vec3 p = aStart + d * (aCorner.x * len + (aCorner.x * 2.0 - 1.0) * w * 0.5) + side * (aCorner.y * w) + up * (aCorner.z * h);
      vNormal = mat3(uView) * normalize(d * aNormal.x + side * aNormal.y + up * aNormal.z);
      vColor = uColors[type];
      if (gl_InstanceID + uBase < uDimBelow) vColor = mix(vColor, uDimColor, 0.55);
      gl_Position = uViewProj * vec4(p, 1.0);
    }`, `#version 300 es
    precision mediump float;
    in vec3 vNormal; in vec3 vColor;
    uniform float uGhost; uniform vec3 uGhostColor;
    out vec4 fragment;
    void main() {
      vec3 n = normalize(vNormal);
      vec3 key = normalize(vec3(0.35, 0.55, 0.75)), fill = normalize(vec3(-0.6, -0.2, 0.5));
      float diffuse = max(dot(n, key), 0.0), back = max(dot(n, fill), 0.0);
      float shine = pow(max(dot(reflect(-key, n), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);
      vec3 color = vColor * (0.36 + 0.56 * diffuse + 0.16 * back) + vec3(0.14) * shine;
      fragment = uGhost > 0.5 ? vec4(mix(uGhostColor, color, 0.2), 0.3) : vec4(color, 1.0);
    }`);

  const lines = program(`#version 300 es
    layout(location=0) in vec3 aPosition;
    uniform mat4 uViewProj;
    void main() { gl_Position = uViewProj * vec4(aPosition, 1.0); }`, `#version 300 es
    precision mediump float;
    uniform vec4 uColor; out vec4 fragment;
    void main() { fragment = uColor; }`);

  const marker = program(`#version 300 es
    layout(location=0) in vec3 aPosition;
    uniform mat4 uViewProj; uniform float uSize;
    void main() { gl_Position = uViewProj * vec4(aPosition, 1.0); gl_PointSize = uSize; }`, `#version 300 es
    precision mediump float;
    uniform vec4 uColor; uniform vec4 uRing; out vec4 fragment;
    void main() {
      float r = length(gl_PointCoord - vec2(0.5)) * 2.0;
      if (r > 1.0) discard;
      fragment = r > 0.62 ? uRing : uColor;
    }`);

  // Bead geometry: top, bottom and two rounded sides (side normals lean up or down so the bead looks round).
  function beadGeometry() {
    const v = [];
    const quad = (a, b, c, d, n) => { for (const p of [a, b, c, a, c, d]) v.push(...p, ...(typeof n === "function" ? n(p) : n)); };
    quad([0, -0.5, 0], [1, -0.5, 0], [1, 0.5, 0], [0, 0.5, 0], [0, 0, 1]);           // top
    quad([0, 0.5, -1], [1, 0.5, -1], [1, -0.5, -1], [0, -0.5, -1], [0, 0, -1]);      // bottom
    const round = (s) => (p) => { const up = (p[2] + 0.5) * 1.3, l = Math.hypot(1, up); return [0, s / l, up / l]; };
    quad([0, 0.5, -1], [0, 0.5, 0], [1, 0.5, 0], [1, 0.5, -1], round(1));            // left side (+side)
    quad([0, -0.5, -1], [1, -0.5, -1], [1, -0.5, 0], [0, -0.5, 0], round(-1));       // right side (-side)
    return new Float32Array(v);
  }

  // ---------------------------------------------------------------- state
  const data = { meta: null, segments: null, count: 0, travels: null, travelCount: 0, box: null };
  const view = { start: 0, end: 0, ghostEnd: 0, travelStart: 0, travelEnd: 0, showTravel: false, hidden: 0, nozzle: null, dimBelow: 0 };
  const theme = { background: [0.949, 0.961, 0.965], grid: [0.75, 0.8, 0.8, 1], plate: [0.88, 0.91, 0.91, 1], ghost: [0.6, 0.65, 0.67],
    travel: [0.2, 0.45, 0.9, 0.55], nozzle: [0, 0.62, 0.56, 1], ring: [1, 1, 1, 1], dim: [0.62, 0.66, 0.68] };
  const camera = { yaw: -55, pitch: 32, distance: 300, target: [128, 128, 10], fov: 35 };
  let dirty = false;
  let beadVao, beadVertices, instanceBuffer, travelVao, travelBuffer, bedVao, bedLineCount = 0, plateVao, markerVao, markerBuffer;
  const colors = new Float32Array(PALETTE.length * 3);

  function setupStatic() {
    beadVertices = beadGeometry();
    beadVao = gl.createVertexArray(); gl.bindVertexArray(beadVao);
    const corner = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, corner); gl.bufferData(gl.ARRAY_BUFFER, beadVertices, gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 24, 0);
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 24, 12);
    instanceBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuffer);
    for (const [location, size] of [[2, 3], [3, 3], [4, 2], [5, 1]]) { gl.enableVertexAttribArray(location); gl.vertexAttribDivisor(location, 1); }
    travelVao = gl.createVertexArray(); gl.bindVertexArray(travelVao);
    travelBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, travelBuffer);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    markerVao = gl.createVertexArray(); gl.bindVertexArray(markerVao);
    markerBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, markerBuffer); gl.bufferData(gl.ARRAY_BUFFER, 12, gl.DYNAMIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    gl.bindVertexArray(null);
  }

  function setupBed(outline) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (let i = 0; i < outline.length; i += 2) { minX = Math.min(minX, outline[i]); maxX = Math.max(maxX, outline[i]); minY = Math.min(minY, outline[i + 1]); maxY = Math.max(maxY, outline[i + 1]); }
    const v = [];
    for (let x = Math.ceil(minX / 10) * 10; x <= maxX; x += 10) v.push(x, minY, 0, x, maxY, 0);
    for (let y = Math.ceil(minY / 10) * 10; y <= maxY; y += 10) v.push(minX, y, 0, maxX, y, 0);
    for (let i = 0; i < outline.length; i += 2) { const j = (i + 2) % outline.length; v.push(outline[i], outline[i + 1], 0, outline[j], outline[j + 1], 0); }
    bedLineCount = v.length / 3;
    bedVao = gl.createVertexArray(); gl.bindVertexArray(bedVao);
    const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(v), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    plateVao = gl.createVertexArray(); gl.bindVertexArray(plateVao);
    const plate = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, plate);
    const z = -0.02;
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([minX, minY, z, maxX, minY, z, maxX, maxY, z, minX, minY, z, maxX, maxY, z, minX, maxY, z]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    gl.bindVertexArray(null);
    return [minX, minY, maxX, maxY];
  }

  // ---------------------------------------------------------------- math
  function perspective(fovDegrees, aspect, near, far) {
    const f = 1 / Math.tan(fovDegrees * Math.PI / 360), nf = 1 / (near - far);
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
    for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) {
      let s = 0; for (let k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k]; out[c * 4 + r] = s;
    }
    return out;
  }
  function eye() {
    const yaw = camera.yaw * Math.PI / 180, pitch = camera.pitch * Math.PI / 180;
    return [camera.target[0] + camera.distance * Math.cos(pitch) * Math.cos(yaw),
      camera.target[1] + camera.distance * Math.cos(pitch) * Math.sin(yaw),
      camera.target[2] + camera.distance * Math.sin(pitch)];
  }

  // ---------------------------------------------------------------- drawing
  function bindInstances(first) {
    gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuffer);
    const base = first * STRIDE;
    gl.vertexAttribPointer(2, 3, gl.FLOAT, false, STRIDE, base);
    gl.vertexAttribPointer(3, 3, gl.FLOAT, false, STRIDE, base + 12);
    gl.vertexAttribPointer(4, 2, gl.FLOAT, false, STRIDE, base + 24);
    gl.vertexAttribPointer(5, 1, gl.FLOAT, false, STRIDE, base + 32);
  }
  function drawBeads(first, end, ghost, viewProj, viewMatrix) {
    if (end <= first) return;
    gl.useProgram(bead.p);
    gl.uniformMatrix4fv(bead.u.uViewProj, false, viewProj); gl.uniformMatrix4fv(bead.u.uView, false, viewMatrix);
    gl.uniform3fv(bead.u.uColors, colors); gl.uniform1i(bead.u.uHidden, view.hidden);
    gl.uniform1f(bead.u.uGhost, ghost ? 1 : 0); gl.uniform3fv(bead.u.uGhostColor, theme.ghost);
    gl.uniform1i(bead.u.uBase, first); gl.uniform1i(bead.u.uDimBelow, ghost ? 0 : view.dimBelow); gl.uniform3fv(bead.u.uDimColor, theme.dim);
    gl.bindVertexArray(beadVao); bindInstances(first);
    gl.drawArraysInstanced(gl.TRIANGLES, 0, beadVertices.length / 6, end - first);
  }

  function render() {
    dirty = false;
    const width = Math.round(canvas.clientWidth * devicePixelRatio), height = Math.round(canvas.clientHeight * devicePixelRatio);
    if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
    gl.viewport(0, 0, width, height);
    gl.clearColor(theme.background[0], theme.background[1], theme.background[2], 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    if (!data.meta) return;
    const viewMatrix = lookAt(eye(), camera.target, [0, 0, 1]);
    const viewProj = multiply(perspective(camera.fov, width / Math.max(1, height), Math.max(0.5, camera.distance / 500), camera.distance * 20), viewMatrix);
    gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL);
    gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    // Plate and grid.
    gl.useProgram(lines.p); gl.uniformMatrix4fv(lines.u.uViewProj, false, viewProj);
    gl.uniform4fv(lines.u.uColor, theme.plate); gl.bindVertexArray(plateVao); gl.drawArrays(gl.TRIANGLES, 0, 6);
    gl.uniform4fv(lines.u.uColor, theme.grid); gl.bindVertexArray(bedVao); gl.drawArrays(gl.LINES, 0, bedLineCount);
    // Printed / visible beads, then travels, then the rest of the current layer as a translucent ghost.
    drawBeads(view.start, view.end, false, viewProj, viewMatrix);
    if (view.showTravel && view.travelEnd > view.travelStart) {
      gl.useProgram(lines.p); gl.uniformMatrix4fv(lines.u.uViewProj, false, viewProj); gl.uniform4fv(lines.u.uColor, theme.travel);
      gl.bindVertexArray(travelVao); gl.drawArrays(gl.LINES, view.travelStart * 2, (view.travelEnd - view.travelStart) * 2);
    }
    if (view.ghostEnd > view.end) { gl.depthMask(false); drawBeads(view.end, view.ghostEnd, true, viewProj, viewMatrix); gl.depthMask(true); }
    if (view.nozzle) {
      gl.disable(gl.DEPTH_TEST);
      gl.useProgram(marker.p); gl.uniformMatrix4fv(marker.u.uViewProj, false, viewProj);
      gl.uniform1f(marker.u.uSize, 16 * devicePixelRatio); gl.uniform4fv(marker.u.uColor, theme.nozzle); gl.uniform4fv(marker.u.uRing, theme.ring);
      gl.bindVertexArray(markerVao); gl.bindBuffer(gl.ARRAY_BUFFER, markerBuffer); gl.bufferSubData(gl.ARRAY_BUFFER, 0, new Float32Array(view.nozzle));
      gl.drawArrays(gl.POINTS, 0, 1);
    }
    gl.bindVertexArray(null);
  }
  function redraw() { if (!dirty) { dirty = true; requestAnimationFrame(render); } }

  // ---------------------------------------------------------------- camera control
  // Frames the whole model: the distance at which all eight corners of its bounds sit inside the view for the
  // current angle and screen shape (with a margin), so a long model is not cut off at the edge.
  function fit() {
    const box = data.box || [0, 0, 0, 256, 256, 10];
    camera.target = [(box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2];
    camera.yaw = -55; camera.pitch = 32; camera.distance = 100;
    const m = lookAt(eye(), camera.target, [0, 0, 1]);
    const f = 1 / Math.tan(camera.fov * Math.PI / 360), aspect = Math.max(0.2, canvas.clientWidth / Math.max(1, canvas.clientHeight)), k = 0.9;
    let d = 20;
    for (let i = 0; i < 8; i++) {
      const o = [(i & 1 ? box[3] : box[0]) - camera.target[0], (i & 2 ? box[4] : box[1]) - camera.target[1], (i & 4 ? box[5] : box[2]) - camera.target[2]];
      const cx = o[0] * m[0] + o[1] * m[4] + o[2] * m[8], cy = o[0] * m[1] + o[1] * m[5] + o[2] * m[9], cz = o[0] * m[2] + o[1] * m[6] + o[2] * m[10];
      d = Math.max(d, Math.abs(cx) * f / (aspect * k) + cz, Math.abs(cy) * f / k + cz);
    }
    camera.distance = d;
    redraw();
  }
  const pointers = new Map();
  let lastTap = 0, tapStart = null;
  canvas.addEventListener("pointerdown", (e) => {
    canvas.setPointerCapture(e.pointerId); pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    tapStart = pointers.size === 1 ? { x: e.clientX, y: e.clientY, t: Date.now() } : null;
  });
  canvas.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    const before = [...pointers.values()].map((p) => ({ ...p }));
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const after = [...pointers.values()];
    if (after.length === 1) {
      const dx = after[0].x - before[0].x, dy = after[0].y - before[0].y;
      camera.yaw -= dx * 0.35; camera.pitch = Math.max(-89, Math.min(89, camera.pitch + dy * 0.35));
    } else if (after.length >= 2) {
      const span = (ps) => Math.hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y), mid = (ps) => [(ps[0].x + ps[1].x) / 2, (ps[0].y + ps[1].y) / 2];
      const s0 = span(before), s1 = span(after);
      if (s0 > 0 && s1 > 0) camera.distance = Math.max(5, Math.min(5000, camera.distance * s0 / s1));
      const [mx0, my0] = mid(before), [mx1, my1] = mid(after);
      pan(mx1 - mx0, my1 - my0);
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
    if (tapStart && Math.hypot(e.clientX - tapStart.x, e.clientY - tapStart.y) < 10 && Date.now() - tapStart.t < 250) {
      if (Date.now() - lastTap < 350) { fit(); lastTap = 0; } else lastTap = Date.now();
    }
    tapStart = null;
  }
  canvas.addEventListener("pointerup", release);
  canvas.addEventListener("pointercancel", release);
  canvas.addEventListener("wheel", (e) => { e.preventDefault(); camera.distance = Math.max(5, Math.min(5000, camera.distance * Math.exp(e.deltaY * 0.001))); redraw(); }, { passive: false });
  window.addEventListener("resize", redraw);

  // ---------------------------------------------------------------- API for the app
  async function load() {
    try {
      const [meta, segments, travels] = await Promise.all([
        fetch("/data/meta.json").then((r) => r.json()),
        fetch("/data/segments.bin").then((r) => r.arrayBuffer()),
        fetch("/data/travels.bin").then((r) => r.arrayBuffer())]);
      data.meta = meta; data.count = segments.byteLength / STRIDE; data.travelCount = travels.byteLength / 24;
      gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuffer); gl.bufferData(gl.ARRAY_BUFFER, segments, gl.STATIC_DRAW);
      gl.bindBuffer(gl.ARRAY_BUFFER, travelBuffer); gl.bufferData(gl.ARRAY_BUFFER, travels, gl.STATIC_DRAW);
      // Model bounds for the camera. Layer 1 also holds the start G-code's purge line, so with more than one layer
      // the bounds come from layer 2 up, plus layer 1's Z.
      const f = new Float32Array(segments); let box = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
      const from = meta.starts && meta.starts.length > 1 ? meta.starts[1] * 9 : 0;
      if (from > 0) box[2] = 0;
      for (let i = from; i < f.length; i += 9) for (const o of [0, 3]) {
        box[0] = Math.min(box[0], f[i + o]); box[1] = Math.min(box[1], f[i + o + 1]); box[2] = Math.min(box[2], f[i + o + 2] - f[i + 7]);
        box[3] = Math.max(box[3], f[i + o]); box[4] = Math.max(box[4], f[i + o + 1]); box[5] = Math.max(box[5], f[i + o + 2]);
      }
      if (!isFinite(box[0])) box = null;
      data.box = box;
      setupBed(meta.bed && meta.bed.length >= 6 ? meta.bed : [0, 0, 256, 0, 256, 256, 0, 256]);
      view.end = data.count; view.ghostEnd = data.count; view.travelEnd = data.travelCount;
      fit();
      if (android && android.onLoaded) android.onLoaded(data.count);
    } catch (error) { fail("The toolpath could not be shown: " + error.message); }
  }
  function update(state) {
    Object.assign(view, state); redraw();
  }
  function setPalette(hexes) {
    hexes.forEach((hex, i) => { if (i < PALETTE.length) for (let c = 0; c < 3; c++) colors[i * 3 + c] = parseInt(hex.substr(1 + 2 * c, 2), 16) / 255; });
  }
  function setTheme(next) {
    if (next.palette) setPalette(next.palette);
    for (const key of Object.keys(next)) if (key in theme) theme[key] = next[key];
    document.body.style.setProperty("--bg", "rgb(" + theme.background.map((c) => Math.round(c * 255)).join(",") + ")");
    redraw();
  }
  setPalette(PALETTE);
  try { setupStatic(); } catch (error) { fail("WebGL setup failed: " + error.message); return; }
  // setView("top") looks straight down, to check a layer's lines; anything else is the usual three-quarter view.
  function setView(name) { fit(); if (name === "top") { camera.yaw = -90; camera.pitch = 88; redraw(); } }
  window.viewer = { load, update, setTheme, resetCamera: fit, setView, redraw, camera };
  if (android && android.onReady) android.onReady(); else if (location.search.indexOf("autoload") >= 0) load();
})();

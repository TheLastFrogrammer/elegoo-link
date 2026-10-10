// Link Workshop G-code viewer: WebGL2, one instanced box per extrusion segment (width and height from the G-code),
// shaded as a rounded bead and colored by feature type. Data comes from the app (GcodeToolpath) at /data/*:
//   meta.json      layer starts, travel starts, layer Z, bed outline, feature names
//   segments.bin   float32 x0 y0 z0 x1 y1 z1 width height type, per segment
//   travels.bin    float32 x0 y0 z0 x1 y1 z1, per travel move
// The app drives it through window.viewer: load(), update(state), setTheme(theme), resetCamera(), setView("top"|"iso"|"printer").
// The printer's camera can be shown in the scene (a camera model, its view cone and a screen with the live picture):
// showPrinterCamera(pose|null), cameraFrame(url) for JPEG frames the app serves, cameraCloud(...)/cameraStop() for Elegoo's cloud video.
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
    uniform int uLayerStart; uniform float uAlpha, uBelowAlpha;   // opacity of the current layer, and of the layers below it
    out vec3 vNormal; out vec3 vColor; out float vAlpha;
    // Wide-angle lens: seen from the printer's camera, the drawing bends like the camera's picture (barrel curve, division model,
    // radius measured against the picture's half-diagonal) so the bed outline can be matched to a curved bed edge.
    uniform float uLens, uScreenAspect, uPictureAspect, uFit;
    uniform vec3 uZoom;   // seen from the camera: picture zoom (scale, then shift in -1..1 units), after the lens
    vec4 zoomed(vec4 p) { return vec4(p.xy * uZoom.x + uZoom.yz * p.w, p.zw); }
    vec4 lens(vec4 p) {
      if (uLens <= 0.0 || p.w <= 0.0) return p;
      vec2 n = p.xy / p.w / uFit, c = vec2(n.x * uScreenAspect, n.y) / sqrt(uPictureAspect * uPictureAspect + 1.0);
      float r2 = dot(c, c);
      if (uLens * r2 > 1.0) return vec4(2.0, 2.0, 2.0, 1.0);   // far outside the picture: would fold back in
      return vec4(n / (1.0 + uLens * r2) * uFit * p.w, p.zw);
    }
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
      vAlpha = gl_InstanceID + uBase < uLayerStart ? uBelowAlpha : uAlpha;
      gl_Position = zoomed(lens(uViewProj * vec4(p, 1.0)));
    }`, `#version 300 es
    precision mediump float;
    in vec3 vNormal; in vec3 vColor; in float vAlpha;
    uniform float uGhost; uniform vec3 uGhostColor;
    out vec4 fragment;
    void main() {
      vec3 n = normalize(vNormal);
      vec3 key = normalize(vec3(0.35, 0.55, 0.75)), fill = normalize(vec3(-0.6, -0.2, 0.5));
      float diffuse = max(dot(n, key), 0.0), back = max(dot(n, fill), 0.0);
      float shine = pow(max(dot(reflect(-key, n), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);
      vec3 color = vColor * (0.36 + 0.56 * diffuse + 0.16 * back) + vec3(0.14) * shine;
      fragment = uGhost > 0.5 ? vec4(mix(uGhostColor, color, 0.2), 0.3 * vAlpha) : vec4(color, vAlpha);
    }`);

  const lines = program(`#version 300 es
    layout(location=0) in vec3 aPosition;
    uniform mat4 uViewProj;
    // Wide-angle lens: seen from the printer's camera, the drawing bends like the camera's picture (barrel curve, division model,
    // radius measured against the picture's half-diagonal) so the bed outline can be matched to a curved bed edge.
    uniform float uLens, uScreenAspect, uPictureAspect, uFit;
    uniform vec3 uZoom;   // seen from the camera: picture zoom (scale, then shift in -1..1 units), after the lens
    vec4 zoomed(vec4 p) { return vec4(p.xy * uZoom.x + uZoom.yz * p.w, p.zw); }
    vec4 lens(vec4 p) {
      if (uLens <= 0.0 || p.w <= 0.0) return p;
      vec2 n = p.xy / p.w / uFit, c = vec2(n.x * uScreenAspect, n.y) / sqrt(uPictureAspect * uPictureAspect + 1.0);
      float r2 = dot(c, c);
      if (uLens * r2 > 1.0) return vec4(2.0, 2.0, 2.0, 1.0);   // far outside the picture: would fold back in
      return vec4(n / (1.0 + uLens * r2) * uFit * p.w, p.zw);
    }
    void main() { gl_Position = zoomed(lens(uViewProj * vec4(aPosition, 1.0))); }`, `#version 300 es
    precision mediump float;
    uniform vec4 uColor; out vec4 fragment;
    void main() { fragment = uColor; }`);

  const marker = program(`#version 300 es
    layout(location=0) in vec3 aPosition;
    uniform mat4 uViewProj; uniform float uSize;
    // Wide-angle lens: seen from the printer's camera, the drawing bends like the camera's picture (barrel curve, division model,
    // radius measured against the picture's half-diagonal) so the bed outline can be matched to a curved bed edge.
    uniform float uLens, uScreenAspect, uPictureAspect, uFit;
    uniform vec3 uZoom;   // seen from the camera: picture zoom (scale, then shift in -1..1 units), after the lens
    vec4 zoomed(vec4 p) { return vec4(p.xy * uZoom.x + uZoom.yz * p.w, p.zw); }
    vec4 lens(vec4 p) {
      if (uLens <= 0.0 || p.w <= 0.0) return p;
      vec2 n = p.xy / p.w / uFit, c = vec2(n.x * uScreenAspect, n.y) / sqrt(uPictureAspect * uPictureAspect + 1.0);
      float r2 = dot(c, c);
      if (uLens * r2 > 1.0) return vec4(2.0, 2.0, 2.0, 1.0);   // far outside the picture: would fold back in
      return vec4(n / (1.0 + uLens * r2) * uFit * p.w, p.zw);
    }
    void main() { gl_Position = zoomed(lens(uViewProj * vec4(aPosition, 1.0))); gl_PointSize = uSize; }`, `#version 300 es
    precision mediump float;
    uniform vec4 uColor; uniform vec4 uRing; out vec4 fragment;
    void main() {
      float r = length(gl_PointCoord - vec2(0.5)) * 2.0;
      if (r > 1.0) discard;
      fragment = r > 0.62 ? uRing : uColor;
    }`);

  // A flat picture in the scene: the printer camera's live image.
  const picture = program(`#version 300 es
    layout(location=0) in vec3 aPosition; layout(location=1) in vec2 aUv;
    uniform mat4 uViewProj; uniform float uMirror; out vec2 vUv;
    void main() { vUv = vec2(uMirror > 0.5 ? 1.0 - aUv.x : aUv.x, aUv.y); gl_Position = uViewProj * vec4(aPosition, 1.0); }`, `#version 300 es
    precision mediump float;
    in vec2 vUv; uniform sampler2D uImage; uniform float uHas; uniform vec4 uEmpty; out vec4 fragment;
    void main() { fragment = uHas > 0.5 ? vec4(texture(uImage, vUv).rgb, 0.96) : uEmpty; }`);

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
  const view = { start: 0, end: 0, ghostEnd: 0, travelStart: 0, travelEnd: 0, showTravel: false, hidden: 0, nozzle: null, dimBelow: 0,
    nozzleSize: 16, nozzleAlpha: 1, nozzleFlat: false, layerStart: 0, alpha: 1, belowAlpha: 1, head: false,
    headSize: { w: 70, d: 80, h: 80, block: 24, offset: 0 }, headShow: false, headLook: "mask" };
  const theme = { background: [0.949, 0.961, 0.965], grid: [0.75, 0.8, 0.8, 1], plate: [0.88, 0.91, 0.91, 1], ghost: [0.6, 0.65, 0.67],
    travel: [0.2, 0.45, 0.9, 0.55], nozzle: [0, 0.62, 0.56, 1], ring: [1, 1, 1, 1], dim: [0.62, 0.66, 0.68],
    camera: [0.16, 0.22, 0.25, 1], cone: [0.16, 0.22, 0.25, 0.35], screen: [0.1, 0.12, 0.13, 0.85], align: [1, 0.8, 0.2, 0.9] };
  const camera = { yaw: -55, pitch: 32, distance: 300, target: [128, 128, 10], fov: 35 };
  let dirty = false;
  let gridLineCount = 0;
  // plate: how far the plate reaches past the print area (left, right, front, back, mm); its edges are what the taps mark.
  const alignStyle = { grid: "all", alpha: 0.9, dot: 12, plate: [0, 0, 0, 0] };
  let bedBounds = [0, 0, 256, 256], plateEdgeVao = null, plateEdgeCount = 0;
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

  // ---------------------------------------------------------------- printer camera
  // pose: { position: [x, y, z], target: [x, y, z], fov: vertical degrees, screen: mm from the lens to the picture }.
  const printerCam = { pose: null, aspect: 16 / 9, texture: null, has: false, lineVao: null, lineBuffer: null, lineCount: 0,
    quadVao: null, quadBuffer: null, loading: false, video: null, client: null, track: null, playing: false,
    looking: false, backdropVao: null, backdropBuffer: null };
  function camBasis(pose) {
    const p = pose.position, t = pose.target;
    let f = [t[0] - p[0], t[1] - p[1], t[2] - p[2]]; let l = Math.hypot(...f); f = f.map((v) => v / l);
    let r = [f[1], -f[0], 0]; l = Math.hypot(...r) || 1; r = r.map((v) => v / l);   // f × z
    const u0 = [r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]];
    // Roll about the view axis, as CameraFit.project: the camera's own right and up turn by -roll.
    const a = (pose.roll || 0) * Math.PI / 180, cos = Math.cos(a), sin = Math.sin(a);
    const rr = [0, 1, 2].map((i) => r[i] * cos - u0[i] * sin), u = [0, 1, 2].map((i) => r[i] * sin + u0[i] * cos);
    return { f, r: rr, u };
  }
  function buildPrinterCamera() {
    const pose = printerCam.pose; if (!pose) return;
    const { f, r, u } = camBasis(pose), p = pose.position;
    const at = (a, b, c) => [p[0] + f[0] * a + r[0] * b + u[0] * c, p[1] + f[1] * a + r[1] * b + u[1] * c, p[2] + f[2] * a + r[2] * b + u[2] * c];
    const d = pose.screen || 80, hh = d * Math.tan((pose.fov || 50) * Math.PI / 360), hw = hh * printerCam.aspect;
    const tl = at(d, -hw, hh), tr = at(d, hw, hh), br = at(d, hw, -hh), bl = at(d, -hw, -hh);
    const v = [];
    const edge = (a, b) => v.push(...a, ...b);
    // Body: a small box behind the lens, and a lens ring.
    const box = []; for (let i = 0; i < 8; i++) box.push(at(i & 1 ? -2 : -26, i & 2 ? 9 : -9, i & 4 ? 7 : -7));
    for (const [a, b] of [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]]) edge(box[a], box[b]);
    for (let i = 0; i < 16; i++) { const a = i / 16 * 2 * Math.PI, b = (i + 1) / 16 * 2 * Math.PI; edge(at(0, 5 * Math.cos(a), 5 * Math.sin(a)), at(0, 5 * Math.cos(b), 5 * Math.sin(b))); }
    const bodyCount = v.length / 3;
    for (const c of [tl, tr, br, bl]) edge(p, c);
    edge(tl, tr); edge(tr, br); edge(br, bl); edge(bl, tl);
    printerCam.bodyCount = bodyCount; printerCam.lineCount = v.length / 3;
    if (!printerCam.lineVao) {
      printerCam.lineVao = gl.createVertexArray(); gl.bindVertexArray(printerCam.lineVao);
      printerCam.lineBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, printerCam.lineBuffer);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
      printerCam.quadVao = gl.createVertexArray(); gl.bindVertexArray(printerCam.quadVao);
      printerCam.quadBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, printerCam.quadBuffer);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 20, 0);
      gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 20, 12);
      printerCam.texture = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, printerCam.texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, printerCam.lineBuffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(v), gl.DYNAMIC_DRAW);
    const q = [];
    for (const [c, uv] of [[tl, [0, 0]], [tr, [1, 0]], [br, [1, 1]], [tl, [0, 0]], [br, [1, 1]], [bl, [0, 1]]]) q.push(...c, ...uv);
    gl.bindBuffer(gl.ARRAY_BUFFER, printerCam.quadBuffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(q), gl.DYNAMIC_DRAW);
    gl.bindVertexArray(null);
  }
  // Seen from the camera: its picture fills the view behind the toolpath, scaled so its height spans the camera's field of view,
  // which is the view's field of view too; where the estimate of the camera's place is right, the print lines up with the picture.
  function drawBackdrop(width, height) {
    if (!printerCam.has) return;
    if (!printerCam.backdropVao) {
      printerCam.backdropVao = gl.createVertexArray(); gl.bindVertexArray(printerCam.backdropVao);
      printerCam.backdropBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, printerCam.backdropBuffer);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 20, 0);
      gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 20, 12);
    }
    const k = fitScale(width, height), w = printerCam.aspect / (width / Math.max(1, height)) * k;
    const q = [-w, k, 0, 0, 0, w, k, 0, 1, 0, w, -k, 0, 1, 1, -w, k, 0, 0, 0, w, -k, 0, 1, 1, -w, -k, 0, 0, 1];
    gl.bindVertexArray(printerCam.backdropVao); gl.bindBuffer(gl.ARRAY_BUFFER, printerCam.backdropBuffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(q), gl.DYNAMIC_DRAW);
    gl.disable(gl.DEPTH_TEST); gl.depthMask(false);
    gl.useProgram(picture.p); gl.uniformMatrix4fv(picture.u.uViewProj, false, [zoom2d.s, 0, 0, 0, 0, zoom2d.s, 0, 0, 0, 0, 1, 0, zoom2d.x, zoom2d.y, 0, 1]);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, printerCam.texture); gl.uniform1i(picture.u.uImage, 0); gl.uniform1f(picture.u.uHas, 1); gl.uniform1f(picture.u.uMirror, 0);
    gl.drawArrays(gl.TRIANGLES, 0, 6);
    gl.depthMask(true); gl.enable(gl.DEPTH_TEST);
  }
  // Seen from the camera, the whole picture fits in the view (a squarer view would otherwise cut its sides off); the
  // drawing shrinks by the same factor so it stays on the picture.
  function fitScale(width, height) {
    if (!printerCam.looking) return 1;
    return Math.min(1, (width / Math.max(1, height)) / printerCam.aspect);
  }
  function leaveCameraView() { if (printerCam.looking) { printerCam.looking = false; camera.fov = 35; resetZoom(); } }
  // Zooming into the camera's picture (from the camera): everything drawn over it zooms the same way, so it stays lined up.
  const zoom2d = { s: 1, x: 0, y: 0 };
  function resetZoom() { zoom2d.s = 1; zoom2d.x = 0; zoom2d.y = 0; drawMarks(); redraw(); }
  function zoomBy(factor, cx, cy) {   // about (cx, cy) in -1..1 view units
    const s = Math.max(1, Math.min(8, zoom2d.s * factor)), f = s / zoom2d.s;
    zoom2d.x = cx - (cx - zoom2d.x) * f; zoom2d.y = cy - (cy - zoom2d.y) * f; zoom2d.s = s; clampZoom();
  }
  function zoomPicture(factor) { if (!printerCam.looking) return; zoomBy(factor, 0, 0); drawMarks(); redraw(); }
  function clampZoom() { const m = zoom2d.s - 1; zoom2d.x = Math.max(-m, Math.min(m, zoom2d.x)); zoom2d.y = Math.max(-m, Math.min(m, zoom2d.y)); }
  // With rotation locked, dragging moves the view instead of turning it (and from the camera, never leaves it).
  let rotationLocked = false;
  function setRotationLock(on) { rotationLocked = !!on; }
  function drawPrinterCamera(viewProj) {
    if (!printerCam.pose || !printerCam.lineVao || printerCam.looking) return;
    gl.useProgram(picture.p); gl.uniformMatrix4fv(picture.u.uViewProj, false, viewProj);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, printerCam.texture); gl.uniform1i(picture.u.uImage, 0);
    gl.uniform1f(picture.u.uHas, printerCam.has ? 1 : 0); gl.uniform4fv(picture.u.uEmpty, theme.screen);
    // Seen from the bed side the picture would read back to front: show it the way round it reads from where you look.
    const { f } = camBasis(printerCam.pose), e = eye(), p = printerCam.pose.position;
    gl.uniform1f(picture.u.uMirror, (e[0] - p[0]) * f[0] + (e[1] - p[1]) * f[1] + (e[2] - p[2]) * f[2] > (printerCam.pose.screen || 80) ? 1 : 0);
    gl.bindVertexArray(printerCam.quadVao); gl.drawArrays(gl.TRIANGLES, 0, 6);
    gl.useProgram(lines.p); gl.uniformMatrix4fv(lines.u.uViewProj, false, viewProj);
    gl.bindVertexArray(printerCam.lineVao);
    gl.uniform4fv(lines.u.uColor, theme.camera); gl.drawArrays(gl.LINES, 0, printerCam.bodyCount);
    gl.uniform4fv(lines.u.uColor, theme.cone); gl.drawArrays(gl.LINES, printerCam.bodyCount, printerCam.lineCount - printerCam.bodyCount);
  }
  function upload(source, width, height) {
    if (!printerCam.texture || !width || !height) return;
    gl.bindTexture(gl.TEXTURE_2D, printerCam.texture);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, source);
    const aspect = width / height;
    if (Math.abs(aspect - printerCam.aspect) > 0.01) { printerCam.aspect = aspect; buildPrinterCamera(); }
    printerCam.has = true; redraw();
  }
  function showPrinterCamera(pose) {
    const already = !!printerCam.pose;
    printerCam.pose = pose || null;
    if (!pose) { cameraStop(); printerCam.has = false; fit(); return; }
    buildPrinterCamera();
    // Turning the camera on frames the scene with it; the same camera coming back (the app returning to the screen, the
    // stream restarting) keeps the view as the user left it, from the camera too.
    if (!already) { fit(); return; }
    if (printerCam.looking) { const keep = { ...zoom2d }; lookFromCamera(); Object.assign(zoom2d, keep); drawMarks(); }
    redraw();
  }
  // A JPEG the app serves (local camera): fetched only when the previous one is on screen, so frames never queue up.
  async function cameraFrame(url) {
    if (printerCam.loading || !printerCam.pose) return;
    printerCam.loading = true;
    try {
      const blob = await (await fetch(url, { cache: "no-store" })).blob();
      const image = await createImageBitmap(blob);
      upload(image, image.width, image.height); image.close();
    } catch (e) { /* the next frame will try again */ } finally { printerCam.loading = false; }
  }
  // Elegoo's cloud video (Agora), joined as Elegoo's printer page does; the video element is drawn into the texture each frame.
  function cameraStatus(text) { if (android && android.onCamera) android.onCamera(String(text)); }
  function loadAgora() {
    if (window.AgoraRTC) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const script = document.createElement("script"); script.src = "/assets/camera/AgoraRTC_N-production.js";
      script.onload = resolve; script.onerror = () => reject(new Error("camera library missing")); document.head.appendChild(script);
    });
  }
  async function cameraCloud(appId, channel, token, uid) {
    try {
      await cameraStop(); cameraStatus("Connecting to the camera…");
      await loadAgora();
      AgoraRTC.setLogLevel(3); try { AgoraRTC.disableLogUpload(); } catch (e) { }
      const client = AgoraRTC.createClient({ mode: "live", codec: "vp8" }); printerCam.client = client;
      const holder = document.getElementById("camera-video");
      const play = async (user) => {
        await client.subscribe(user, "video"); printerCam.track = user.videoTrack;
        holder.innerHTML = ""; user.videoTrack.play(holder, { fit: "contain" });
        printerCam.video = holder.querySelector("video"); printerCam.playing = true; cameraStatus("playing"); pump();
      };
      client.on("user-published", (user, type) => { if (type === "video") play(user); });
      client.on("user-unpublished", () => { printerCam.playing = false; cameraStatus("The printer stopped sending video."); });
      await client.join(appId, channel, token, Number(uid));
      await client.setClientRole("host");
      for (const user of client.remoteUsers) if (user.hasVideo) { await play(user); break; }
      if (!printerCam.playing) cameraStatus("Connected. Waiting for the printer to send video…");
    } catch (e) { cameraStatus("The camera could not start (" + ((e && (e.code || e.message)) || e) + ")."); }
  }
  function pump() {
    if (!printerCam.playing || !printerCam.pose) return;
    const video = printerCam.video;
    if (video && video.readyState >= 2 && video.videoWidth) upload(video, video.videoWidth, video.videoHeight);
    setTimeout(() => requestAnimationFrame(pump), 100);   // about ten pictures a second is plenty for a print
  }
  async function cameraStop() {
    printerCam.playing = false; printerCam.video = null; printerCam.track = null;
    const client = printerCam.client; printerCam.client = null;
    if (client) try { await client.leave(); } catch (e) { }
    const holder = document.getElementById("camera-video"); if (holder) holder.innerHTML = "";
  }

  function setupBed(outline) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (let i = 0; i < outline.length; i += 2) { minX = Math.min(minX, outline[i]); maxX = Math.max(maxX, outline[i]); minY = Math.min(minY, outline[i + 1]); maxY = Math.max(maxY, outline[i + 1]); }
    const v = [];
    // Lines in 8 mm pieces, so they can bend with the camera's lens curve.
    const line = (x0, y0, x1, y1) => {
      const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0) / 8));
      for (let k = 0; k < n; k++) v.push(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n, 0, x0 + (x1 - x0) * (k + 1) / n, y0 + (y1 - y0) * (k + 1) / n, 0);
    };
    for (let x = Math.ceil(minX / 10) * 10; x <= maxX; x += 10) line(x, minY, x, maxY);
    for (let y = Math.ceil(minY / 10) * 10; y <= maxY; y += 10) line(minX, y, maxX, y);
    gridLineCount = v.length / 3;   // the grid comes first, then the bed's outline
    for (let i = 0; i < outline.length; i += 2) { const j = (i + 2) % outline.length; line(outline[i], outline[i + 1], outline[j], outline[j + 1]); }
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
    bedBounds = [minX, minY, maxX, maxY]; setupPlateEdges();
    return [minX, minY, maxX, maxY];
  }
  // The plate's own edges, when it is larger than the print area: a rectangle around it, in 8 mm pieces for the lens curve.
  function setupPlateEdges() {
    const [l, r, f, b] = alignStyle.plate; plateEdgeCount = 0;
    if (!(l || r || f || b)) return;
    const x0 = bedBounds[0] - l, x1 = bedBounds[2] + r, y0 = bedBounds[1] - f, y1 = bedBounds[3] + b, v = [];
    const line = (ax, ay, bx, by) => { const n = Math.max(1, Math.ceil(Math.hypot(bx - ax, by - ay) / 8)); for (let k = 0; k < n; k++) v.push(ax + (bx - ax) * k / n, ay + (by - ay) * k / n, 0, ax + (bx - ax) * (k + 1) / n, ay + (by - ay) * (k + 1) / n, 0); };
    line(x0, y0, x1, y0); line(x1, y0, x1, y1); line(x1, y1, x0, y1); line(x0, y1, x0, y0);
    if (!plateEdgeVao) plateEdgeVao = gl.createVertexArray();
    gl.bindVertexArray(plateEdgeVao);
    const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(v), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0); gl.bindVertexArray(null);
    plateEdgeCount = v.length / 3;
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
    const a = Math.max(0.05, Math.min(1, view.alpha)), b = Math.max(0.05, Math.min(1, view.belowAlpha));
    gl.uniform1i(bead.u.uLayerStart, view.layerStart); gl.uniform1f(bead.u.uAlpha, a); gl.uniform1f(bead.u.uBelowAlpha, b);
    // See-through lines must not hide the ones behind them (nor the camera picture), so they do not write depth.
    const solid = a > 0.99 && b > 0.99;
    if (!solid) gl.depthMask(false);
    gl.bindVertexArray(beadVao); bindInstances(first);
    gl.drawArraysInstanced(gl.TRIANGLES, 0, beadVertices.length / 6, end - first);
    if (!solid) gl.depthMask(true);
  }

  function render() {
    dirty = false;
    const width = Math.round(canvas.clientWidth * devicePixelRatio), height = Math.round(canvas.clientHeight * devicePixelRatio);
    if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
    gl.viewport(0, 0, width, height);
    gl.clearColor(theme.background[0], theme.background[1], theme.background[2], 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    if (!data.meta && !printerCam.pose) return;   // without a toolpath, the bed and the camera can still be shown
    const viewMatrix = lookAt(eye(), camera.target, [0, 0, 1]);
    const fitK = fitScale(width, height), aspect = width / Math.max(1, height);
    // Seen from the camera, its roll turns the drawing about the view's centre (in screen-shaped units, so it stays square).
    const roll = printerCam.looking && printerCam.pose ? (printerCam.pose.roll || 0) * Math.PI / 180 : 0, rc = Math.cos(roll), rs = Math.sin(roll);
    const viewProj = multiply([fitK * rc, fitK * aspect * rs, 0, 0, -fitK * rs / aspect, fitK * rc, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
      multiply(perspective(camera.fov, width / Math.max(1, height), Math.max(0.5, camera.distance / 500), camera.distance * 20), viewMatrix));
    gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL);
    gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    const overlay = printerCam.looking && printerCam.has;
    const bend = printerCam.looking && printerCam.pose ? (printerCam.pose.lens || 0) : 0;
    for (const prog of [bead, lines, marker]) {
      gl.useProgram(prog.p); gl.uniform1f(prog.u.uLens, bend);
      gl.uniform1f(prog.u.uScreenAspect, width / Math.max(1, height)); gl.uniform1f(prog.u.uPictureAspect, printerCam.aspect); gl.uniform1f(prog.u.uFit, fitK);
      gl.uniform3f(prog.u.uZoom, printerCam.looking ? zoom2d.s : 1, printerCam.looking ? zoom2d.x : 0, printerCam.looking ? zoom2d.y : 0);
    }
    if (overlay) drawBackdrop(width, height);
    // From the camera the head is written to depth before anything else, so it hides the bed grid and outline too, as the
    // real head hides the bed in the picture. (In the 3D view it is written after the bed, so the bed stays whole.)
    if (view.head && view.nozzle && overlay) { gl.enable(gl.DEPTH_TEST); gl.depthMask(true); gl.colorMask(false, false, false, false); drawHead(viewProj, null); gl.colorMask(true, true, true, true); }
    // Plate and grid (over the camera picture, the grid only).
    gl.useProgram(lines.p); gl.uniformMatrix4fv(lines.u.uViewProj, false, viewProj);
    if (!overlay) { gl.uniform4fv(lines.u.uColor, theme.plate); gl.bindVertexArray(plateVao); gl.drawArrays(gl.TRIANGLES, 0, 6); }
    gl.bindVertexArray(bedVao);
    if (!overlay) { gl.uniform4fv(lines.u.uColor, theme.grid); gl.drawArrays(gl.LINES, 0, bedLineCount); }
    else {
      // Over the camera picture: the grid can be hidden or kept to the bed's edges, at the chosen strength.
      const colour = [theme.align[0], theme.align[1], theme.align[2], alignStyle.alpha];
      gl.uniform4fv(lines.u.uColor, colour);
      // With a larger plate, its own edges are what lines up with the picture; the print area's outline shows with the full grid.
      if (alignStyle.grid === "all") gl.drawArrays(gl.LINES, 0, bedLineCount);
      else if (alignStyle.grid === "edges" && !plateEdgeCount) gl.drawArrays(gl.LINES, gridLineCount, bedLineCount - gridLineCount);
      if (alignStyle.grid !== "none" && plateEdgeCount) { gl.bindVertexArray(plateEdgeVao); gl.drawArrays(gl.LINES, 0, plateEdgeCount); }
    }
    // The printhead as an invisible occluder: it writes depth only, so lines behind it are hidden as the real head hides
    // them in the camera's picture.
    if (view.head && view.nozzle && !overlay) { gl.enable(gl.DEPTH_TEST); gl.depthMask(true); gl.colorMask(false, false, false, false); drawHead(viewProj, null); gl.colorMask(true, true, true, true); }
    // Printed / visible beads, then travels, then the rest of the current layer as a translucent ghost.
    drawBeads(view.start, view.end, false, viewProj, viewMatrix);
    if (view.showTravel && view.travelEnd > view.travelStart) {
      gl.useProgram(lines.p); gl.uniformMatrix4fv(lines.u.uViewProj, false, viewProj); gl.uniform4fv(lines.u.uColor, theme.travel);
      gl.bindVertexArray(travelVao); gl.drawArrays(gl.LINES, view.travelStart * 2, (view.travelEnd - view.travelStart) * 2);
    }
    if (view.ghostEnd > view.end) { gl.depthMask(false); drawBeads(view.end, view.ghostEnd, true, viewProj, viewMatrix); gl.depthMask(true); }
    drawPrinterCamera(viewProj);
    // Outside the camera view the stand-in head shows faintly, so it is clear what hides the lines.
    // How the head shows: tinted while it is being sized; otherwise as chosen ("mask" hides lines but is not drawn from the
    // camera, where the real head is in the picture; "outline" draws its edges; "tint" shades it). Outside the camera view a
    // mask still shows faintly, so it is clear what hides the lines.
    if (view.head && view.nozzle) {
      const accent = (a) => [theme.nozzle[0], theme.nozzle[1], theme.nozzle[2], a], look = view.headShow ? "tint" : view.headLook;
      gl.depthMask(false);
      if (look === "tint") drawHead(viewProj, accent(view.headShow ? 0.35 : 0.25));
      else if (look === "outline") { gl.disable(gl.DEPTH_TEST); drawHead(viewProj, accent(0.9), true); gl.enable(gl.DEPTH_TEST); }
      else if (!printerCam.looking) drawHead(viewProj, [theme.ghost[0], theme.ghost[1], theme.ghost[2], 0.18]);
      gl.depthMask(true);
    }
    if (view.nozzle) {
      gl.disable(gl.DEPTH_TEST);
      const a = Math.max(0.1, Math.min(1, view.nozzleAlpha)), size = Math.max(4, Math.min(48, view.nozzleSize));
      const colour = [theme.nozzle[0], theme.nozzle[1], theme.nozzle[2], theme.nozzle[3] * a], ring = [theme.ring[0], theme.ring[1], theme.ring[2], theme.ring[3] * a];
      if (view.nozzleFlat) {
        // A disc lying on the layer, drawn in the scene: it shrinks with distance, flattens with the view and bends with the
        // lens curve exactly like the bed grid. Size: 16 px on the slider is a 4 mm disc.
        const [x, y, z] = view.nozzle, r = size / 4, disc = (radius) => { const v = [x, y, z]; for (let i = 0; i <= 32; i++) { const t = i / 32 * Math.PI * 2; v.push(x + radius * Math.cos(t), y + radius * Math.sin(t), z); } return new Float32Array(v); };
        gl.useProgram(lines.p); gl.uniformMatrix4fv(lines.u.uViewProj, false, viewProj);
        gl.bindVertexArray(markerVao); gl.bindBuffer(gl.ARRAY_BUFFER, markerBuffer);
        gl.uniform4fv(lines.u.uColor, ring); gl.bufferData(gl.ARRAY_BUFFER, disc(r), gl.DYNAMIC_DRAW); gl.drawArrays(gl.TRIANGLE_FAN, 0, 34);
        gl.uniform4fv(lines.u.uColor, colour); gl.bufferData(gl.ARRAY_BUFFER, disc(r * 0.62), gl.DYNAMIC_DRAW); gl.drawArrays(gl.TRIANGLE_FAN, 0, 34);
      } else {
        gl.useProgram(marker.p); gl.uniformMatrix4fv(marker.u.uViewProj, false, viewProj);
        gl.uniform1f(marker.u.uSize, size * devicePixelRatio); gl.uniform4fv(marker.u.uColor, colour); gl.uniform4fv(marker.u.uRing, ring);
        gl.bindVertexArray(markerVao); gl.bindBuffer(gl.ARRAY_BUFFER, markerBuffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(view.nozzle), gl.DYNAMIC_DRAW);
        gl.drawArrays(gl.POINTS, 0, 1);
      }
    }
    gl.bindVertexArray(null);
  }
  function redraw() { if (!dirty) { dirty = true; requestAnimationFrame(render); } }

  // ---------------------------------------------------------------- camera control
  // Frames the whole model: the distance at which all eight corners of its bounds sit inside the view for the
  // current angle and screen shape (with a margin), so a long model is not cut off at the edge.
  function fit() {
    leaveCameraView();
    const box = (data.box || [0, 0, 0, 256, 256, 10]).slice();
    if (printerCam.pose) for (const p of [printerCam.pose.position, printerCam.pose.target])
      for (let i = 0; i < 3; i++) { box[i] = Math.min(box[i], p[i]); box[i + 3] = Math.max(box[i + 3], p[i]); }
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
    if (printerCam.looking && (marking || rotationLocked)) {
      // From the camera while marking or locked: drag pans and pinch zooms the picture; a short tap still marks.
      const w = canvas.clientWidth, h = canvas.clientHeight;
      if (tapStart && Math.hypot(after[0].x - tapStart.x, after[0].y - tapStart.y) < 12 && after.length === 1) return;
      if (after.length === 1) { zoom2d.x += (after[0].x - before[0].x) / w * 2; zoom2d.y -= (after[0].y - before[0].y) / h * 2; clampZoom(); }
      else {
        const span = (ps) => Math.hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y), s0 = span(before), s1 = span(after);
        const mx = (after[0].x + after[1].x) / 2, my = (after[0].y + after[1].y) / 2;
        if (s0 > 0 && s1 > 0) zoomBy(s1 / s0, mx / w * 2 - 1, 1 - my / h * 2);
        zoom2d.x += ((after[0].x + after[1].x) - (before[0].x + before[1].x)) / 2 / w * 2;
        zoom2d.y -= ((after[0].y + after[1].y) - (before[0].y + before[1].y)) / 2 / h * 2; clampZoom();
      }
      drawMarks(); redraw(); return;
    }
    if (marking) return;
    leaveCameraView();
    if (after.length === 1 && rotationLocked) { pan(after[0].x - before[0].x, after[0].y - before[0].y); redraw(); return; }
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
    if (marking && tapStart && Math.hypot(e.clientX - tapStart.x, e.clientY - tapStart.y) < 12) {
      const uv = pictureAt(e.clientX, e.clientY);
      if (uv && android && android.onMark) android.onMark(uv[0], uv[1], printerCam.aspect);
      tapStart = null; return;
    }
    if (tapStart && Math.hypot(e.clientX - tapStart.x, e.clientY - tapStart.y) < 10 && Date.now() - tapStart.t < 250) {
      if (Date.now() - lastTap < 350) { if (printerCam.looking && rotationLocked) resetZoom(); else fit(); lastTap = 0; } else lastTap = Date.now();
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
      const f = new Float32Array(segments); data.f = f; cum = null; glide.on = false; glide.lastTarget = -1; let box = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
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
      // A file that arrives while looking from the camera (a live print, or while lining up) keeps that view and its zoom.
      const keep = printerCam.looking ? { ...zoom2d } : null;
      fit();
      if (keep) { lookFromCamera(); Object.assign(zoom2d, keep); drawMarks(); redraw(); }
      if (android && android.onLoaded) android.onLoaded(data.count);
    } catch (error) { fail("The toolpath could not be shown: " + error.message); }
  }
  // Live updates arrive every second or two, irregularly. With smoothMs the drawing moves continuously along the toolpath's
  // own length (mm, so long walls and short infill zigzags pass at the same pace) at the speed recent updates show, a little
  // faster when it falls behind and slower as it catches up, about one update behind the printer. Small backward corrections
  // are held rather than jumped back to; a layer change or a scrub still jumps.
  let cum = null;   // cum[i]: toolpath length (mm) before segment i
  function lengths() {
    const f = data.f; cum = new Float64Array(data.count + 1);
    for (let i = 0, o = 0; i < data.count; i++, o += 9) cum[i + 1] = cum[i] + Math.hypot(f[o + 3] - f[o], f[o + 4] - f[o + 1], f[o + 5] - f[o + 2]);
  }
  const glide = { on: false, pos: 0, target: 0, end: 0, speed: 0, vel: 0, lastTarget: -1, lastTime: 0, frame: 0 };
  function update(state) {
    const smooth = (state.smoothMs || 0) > 0; delete state.smoothMs;
    if (glide.on && !smooth && state.end === glide.end) {   // the same position again (other settings changed): keep moving
      delete state.end; delete state.nozzle; Object.assign(view, state); return;
    }
    if (smooth && data.f && state.end <= data.count) {
      if (!cum || cum.length !== data.count + 1) lengths();
      const now = performance.now(), endIndex = state.end, target = cum[endIndex];
      const shown = glide.on ? glide.pos : cum[Math.min(view.end, data.count)];
      delete state.end; delete state.nozzle; Object.assign(view, state);
      if (glide.lastTarget >= 0 && target > glide.lastTarget) {
        const v = (target - glide.lastTarget) / Math.max(0.25, (now - glide.lastTime) / 1000);
        glide.speed = glide.speed > 0 ? glide.speed * 0.6 + v * 0.4 : v;
      }
      glide.lastTarget = target; glide.lastTime = now; glide.end = endIndex;
      glide.pos = target < shown - 5 ? target : shown;      // a real step back is shown; a few mm of noise is not
      glide.target = Math.max(target, glide.pos);
      if (!glide.on) { glide.on = true; glide.frame = now; requestAnimationFrame(move); }
      return;
    }
    glide.on = false; glide.lastTarget = -1; glide.speed = 0; glide.vel = 0;
    Object.assign(view, state); redraw();
  }
  function indexAt(d) {   // [segment, fraction along it] at toolpath length d
    let lo = 0, hi = data.count;
    while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (cum[mid] <= d) lo = mid; else hi = mid - 1; }
    const k = Math.min(lo, data.count - 1), span = cum[k + 1] - cum[k];
    return [k, span > 0 ? Math.max(0, Math.min(1, (d - cum[k]) / span)) : 1];
  }
  function move(now) {
    if (!glide.on) return;
    const dt = Math.min(0.1, Math.max(0, (now - glide.frame) / 1000)); glide.frame = now;
    const behind = glide.target - glide.pos;
    if (behind > 0) {
      const speed = glide.speed > 0 ? glide.speed : behind;
      // Aim to stay about 1.2 s of travel behind: faster when further back, slower when nearly there.
      const factor = Math.max(0.35, Math.min(3, behind / Math.max(0.5, speed * 1.2)));
      // Speed eases toward that (about half a second), so the motion never lurches.
      glide.vel += (speed * factor - glide.vel) * (1 - Math.exp(-dt / 0.5));
      glide.pos = Math.min(glide.target, glide.pos + Math.max(0, glide.vel) * dt);
      const [k, frac] = indexAt(glide.pos), f = data.f, o = k * 9;
      view.end = Math.max(view.start, k);
      view.nozzle = [f[o] + (f[o + 3] - f[o]) * frac, f[o + 1] + (f[o + 4] - f[o + 1]) * frac, f[o + 2] + (f[o + 5] - f[o + 2]) * frac];
      redraw();
    }
    requestAnimationFrame(move);
  }
  // A low-poly stand-in for the CC2's printhead, in mm from the nozzle tip: the heater block and nozzle, then the body with
  // its fan duct. Close enough to hide what the real head hides; not a measured model.
  // Sized by the user against the camera picture (mm): body width (X) and depth (Y), its height above the heater block,
  // the heater block's width, and how far the body sits forward (+Y) of the nozzle.
  function headBoxes() {
    const z = view.headSize || {}, w = z.w || 70, d = z.d || 80, h = z.h || 80, b = z.block || 24, off = z.offset || 0, top = 1.2 + b * 0.5;
    const boxes = [[-b / 2, b / 2, -b / 2, b / 2, 1.2, top], [-w / 2, w / 2, -d / 2 + off, d / 2 + off, top, top + h]];
    // The part-cooling fan on the head's side, placed by its centre (x, y from the nozzle) and its bottom (z above the tip).
    const fan = z.fan;
    if (fan && fan.on) boxes.push([fan.x - fan.w / 2, fan.x + fan.w / 2, fan.y - fan.d / 2, fan.y + fan.d / 2, fan.z, fan.z + fan.h]);
    return boxes;
  }
  let headVao = null, headBuffer = null;
  function drawHead(viewProj, colour, edges) {
    if (!headVao) {
      headVao = gl.createVertexArray(); gl.bindVertexArray(headVao);
      headBuffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, headBuffer);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    }
    const [x, y, z] = view.nozzle, v = [];
    for (const [x0, x1, y0, y1, z0, z1] of headBoxes()) {
      const c = [[x + x0, y + y0, z + z0], [x + x1, y + y0, z + z0], [x + x1, y + y1, z + z0], [x + x0, y + y1, z + z0],
        [x + x0, y + y0, z + z1], [x + x1, y + y0, z + z1], [x + x1, y + y1, z + z1], [x + x0, y + y1, z + z1]];
      if (edges) for (const [a, b] of [[0, 1], [1, 2], [2, 3], [3, 0], [4, 5], [5, 6], [6, 7], [7, 4], [0, 4], [1, 5], [2, 6], [3, 7]]) v.push(...c[a], ...c[b]);
      else for (const [a, b, d, e] of [[0, 1, 2, 3], [4, 5, 6, 7], [0, 1, 5, 4], [1, 2, 6, 5], [2, 3, 7, 6], [3, 0, 4, 7]])
        v.push(...c[a], ...c[b], ...c[d], ...c[a], ...c[d], ...c[e]);
    }
    gl.useProgram(lines.p); gl.uniformMatrix4fv(lines.u.uViewProj, false, viewProj);
    gl.uniform4fv(lines.u.uColor, colour || [0, 0, 0, 0]);
    gl.bindVertexArray(headVao); gl.bindBuffer(gl.ARRAY_BUFFER, headBuffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(v), gl.DYNAMIC_DRAW);
    gl.drawArrays(edges ? gl.LINES : gl.TRIANGLES, 0, v.length / 3);
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
  try { setupStatic(); setupBed([0, 0, 256, 0, 256, 256, 0, 256]); } catch (error) { fail("WebGL setup failed: " + error.message); return; }
  // setView("top") looks straight down, to check a layer's lines; anything else is the usual three-quarter view.
  // ---------------------------------------------------------------- marking the bed in the picture
  // While lining the camera up from taps, a tap on the picture reports where it is (u, v from the picture's top-left), and
  // the taps so far show as coloured dots, one colour per edge of the bed.
  let marking = false, marks = [];
  const EDGE_COLOURS = ["#ff5252", "#40c4ff", "#69f0ae", "#ff4dd2"];
  function pictureFrame() {
    const width = canvas.clientWidth, height = canvas.clientHeight, k = fitScale(width, height);
    const w = printerCam.aspect / (width / Math.max(1, height)) * k;   // picture's half-width and half-height in the view's -1..1 units
    return { width, height, w, k };
  }
  function pictureAt(x, y) {
    const box = canvas.getBoundingClientRect(), f = pictureFrame();
    const nx = ((x - box.left) / f.width * 2 - 1 - zoom2d.x) / zoom2d.s, ny = (1 - (y - box.top) / f.height * 2 - zoom2d.y) / zoom2d.s;
    const u = (nx / f.w + 1) / 2, v = (1 - ny / f.k) / 2;
    return u < 0 || u > 1 || v < 0 || v > 1 ? null : [u, v];
  }
  function drawMarks() {
    let layer = document.getElementById("marks");
    if (!layer) { layer = document.createElement("div"); layer.id = "marks"; layer.style.cssText = "position:absolute;inset:0;pointer-events:none"; document.body.appendChild(layer); }
    layer.innerHTML = "";
    if (!marking && !marks.length) return;
    const f = pictureFrame();
    for (const m of marks) {
      const nx = (m.u * 2 - 1) * f.w * zoom2d.s + zoom2d.x, ny = (1 - m.v * 2) * f.k * zoom2d.s + zoom2d.y;
      const dot = document.createElement("div");
      const size = alignStyle.dot, edge = Math.max(1, Math.round(size / 6));
      dot.style.cssText = "position:absolute;border-radius:50%;box-shadow:0 0 2px #000;border:" + edge + "px solid #fff;background:" + EDGE_COLOURS[m.edge % 4]
        + ";width:" + size + "px;height:" + size + "px;margin:" + (-size / 2 - edge) + "px 0 0 " + (-size / 2 - edge) + "px";
      dot.style.left = ((nx + 1) / 2 * f.width) + "px"; dot.style.top = ((1 - ny) / 2 * f.height) + "px";
      layer.appendChild(dot);
    }
  }
  function setMarking(on, list) { marking = !!on; marks = list || []; drawMarks(); }
  // How the line-up looks: grid "all" | "edges" | "none", its strength (0..1) and the tap dots' size in CSS pixels.
  function setAlignStyle(style) {
    if (style.grid === "all" || style.grid === "edges" || style.grid === "none") alignStyle.grid = style.grid;
    if (style.alpha >= 0 && style.alpha <= 1) alignStyle.alpha = style.alpha;
    if (style.dot >= 4 && style.dot <= 40) alignStyle.dot = style.dot;
    if (Array.isArray(style.plate) && style.plate.length === 4 && style.plate.every((m) => m >= 0 && m <= 60)) { alignStyle.plate = style.plate.map(Number); setupPlateEdges(); }
    drawMarks(); redraw();
  }
  window.addEventListener("resize", drawMarks);

  // "printer" looks from where the printer's camera is, at what it looks at.
  function setView(name) {
    fit();
    if (name === "top") { camera.yaw = -90; camera.pitch = 88; redraw(); }
    if (name === "printer") lookFromCamera();
  }
  function lookFromCamera() {
    if (!printerCam.pose) return;
    const p = printerCam.pose.position, t = printerCam.pose.target, d = [p[0] - t[0], p[1] - t[1], p[2] - t[2]];
    camera.target = t.slice(); camera.distance = Math.hypot(...d); camera.fov = printerCam.pose.fov || 50; printerCam.looking = true;
    camera.yaw = Math.atan2(d[1], d[0]) * 180 / Math.PI; camera.pitch = Math.asin(d[2] / Math.hypot(...d)) * 180 / Math.PI; redraw();
  }
  // The bed moved (the CC2's bed goes down as a print grows; the camera stays on the frame): moves the camera without
  // changing how the scene is viewed, except that a view from the camera follows it.
  function movePrinterCamera(pose) {
    printerCam.pose = pose; buildPrinterCamera();
    if (printerCam.looking) lookFromCamera(); else redraw();
  }
  // Lining the camera up by hand: moves it without resetting the view, and keeps looking from it.
  function alignPrinterCamera(pose) {
    printerCam.pose = pose; buildPrinterCamera();
    if (!printerCam.looking) fit();
    lookFromCamera();
  }
  // Read-only: what is drawn up to and where the nozzle dot is (for checks; changes nothing).
  function probe() { return { end: view.end, nozzle: view.nozzle ? Array.from(view.nozzle) : null, along: glide.on ? glide.pos : null }; }
  window.viewer = { probe, load, update, setTheme, resetCamera: fit, setView, redraw, camera, showPrinterCamera, alignPrinterCamera, movePrinterCamera, setMarking, setAlignStyle, setRotationLock, resetZoom, zoomPicture, cameraFrame, cameraCloud, cameraStop };
  if (android && android.onReady) android.onReady(); else if (location.search.indexOf("autoload") >= 0) load();
})();

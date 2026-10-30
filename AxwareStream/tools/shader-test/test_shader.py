"""Uji filter tata letak GameLayoutFilterRender di Chromium WebGL (GLSL ES 1.00 = sama dengan OpenGL ES 2).

Semua shader (VERTEX, PASS_VERTEX, DOWNSAMPLE_FRAGMENT, GAUSS_FRAGMENT, FRAGMENT) dan konstanta
(DOWNSCALE, BLUR_PASSES, BLUR_DIM) diambil langsung dari file Kotlin, jadi yang diuji adalah kode asli.
Urutan pass di sini sama dengan GameLayoutFilterRender.drawFilter():
  downsample -> (Gaussian H -> Gaussian V) x BLUR_PASSES -> komposit.

Pemeriksaan:
  1. Isi kotak game = tangkapan asli (posisi/zoom benar), mode hitam & warna tepat.
  2. Blur: warna rata-rata terjaga (sumber seragam -> latar = warna x BLUR_DIM).
  3. Blur halus: dibandingkan dengan blur lama (5x5 sampel renggang), perbedaan antar-piksel
     bertetangga di area latar jauh lebih kecil dan tidak ada tepi tajam / bayangan ganda.
Gambar hasil disimpan di folder ini (blur_lama.png, blur_baru.png, blur_banding.png).
Keluar dengan kode 0 bila semua lulus.
"""
import base64, json, os, re, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '../../app/src/main/java/com/zynnn/axwarestream/core/stream/GameLayoutFilterRender.kt')
src = open(SRC).read()
def shader(name): return re.search(r'const val %s = """(.*?)"""' % name, src, re.S).group(1)
def const(name): return re.search(r'const val %s = ([0-9.]+)f?' % name, src).group(1)
VERT, PVERT = shader('VERTEX'), shader('PASS_VERTEX')
DOWN, GAUSS, FRAG = shader('DOWNSAMPLE_FRAGMENT'), shader('GAUSS_FRAGMENT'), shader('FRAGMENT')
DOWNSCALE, PASSES, DIM = int(const('DOWNSCALE')), int(const('BLUR_PASSES')), float(const('BLUR_DIM'))

# Shader blur LAMA (versi 1.6.0 ke bawah), disalin apa adanya hanya untuk pembanding.
OLD_FRAG = """precision mediump float;
varying vec2 vTextureCoord;
uniform sampler2D uSampler;
uniform vec4 uRect;
uniform vec4 uCover;
uniform float uBgMode;
uniform vec3 uBgColor;
uniform vec2 uTexel;
vec4 sampleTop(vec2 q) { return texture2D(uSampler, vec2(q.x, 1.0 - q.y)); }
void main() {
  vec2 p = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec2 q = (p - uRect.xy) / uRect.zw;
  if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
    gl_FragColor = sampleTop(q);
    return;
  }
  if (uBgMode > 0.5 && uBgMode < 1.5) {
    vec2 b = clamp((p - uCover.xy) / uCover.zw, 0.0, 1.0);
    vec4 acc = vec4(0.0);
    float r = 0.025;
    for (int i = -2; i <= 2; i++) {
      for (int j = -2; j <= 2; j++) {
        vec2 o = vec2(float(i), float(j)) * r / 2.0;
        acc += sampleTop(clamp(b + o, 0.0, 1.0));
      }
    }
    acc /= 25.0;
    gl_FragColor = vec4(acc.rgb * 0.45, 1.0);
  } else if (uBgMode > 1.5) {
    gl_FragColor = vec4(uBgColor, 1.0);
  } else {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
  }
}"""

def content_rect(outW, outH, srcW, srcH, fit='FIT', zoom=1.0, cx=0.5, cy=0.5):
    outA = outW / outH; srcA = srcW / srcH; w = 1.0; h = outA / srcA
    s = min(1 / w, 1 / h) if fit == 'FIT' else max(1 / w, 1 / h)
    w *= s * zoom; h *= s * zoom
    return [cx - w / 2, cy - h / 2, w, h]

def cover_rect(outW, outH, srcW, srcH):
    outA = outW / outH; srcA = srcW / srcH; w = 1.0; h = outA / srcA; s = max(1 / w, 1 / h); w *= s; h *= s
    return [0.5 - w / 2, 0.5 - h / 2, w, h]

def small_size(w, h): return max(16, w // DOWNSCALE), max(16, h // DOWNSCALE)

JS = r"""
function run(args){
 const {W,H,SW,SH,PASSES,DIM,VERT,PVERT,DOWN,GAUSS,FRAG,OLD,texture,cases}=args;
 const cv=document.createElement('canvas');cv.width=W;cv.height=H;
 const gl=cv.getContext('webgl',{preserveDrawingBuffer:true,antialias:false});
 function sh(t,s){const o=gl.createShader(t);gl.shaderSource(o,s);gl.compileShader(o);if(!gl.getShaderParameter(o,gl.COMPILE_STATUS))throw gl.getShaderInfoLog(o);return o;}
 function prog(v,f){const p=gl.createProgram();gl.attachShader(p,sh(gl.VERTEX_SHADER,v));gl.attachShader(p,sh(gl.FRAGMENT_SHADER,f));gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw gl.getProgramInfoLog(p);return p;}
 const P={main:prog(VERT,FRAG),down:prog(PVERT,DOWN),gauss:prog(PVERT,GAUSS),old:prog(VERT,OLD)};
 const vb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vb);
 gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,0,0,0, 1,-1,0,1,0, -1,1,0,0,1, 1,1,0,1,1]),gl.STATIC_DRAW);
 function quad(p){const a=gl.getAttribLocation(p,'aPosition'),t=gl.getAttribLocation(p,'aTextureCoord');
  gl.vertexAttribPointer(a,3,gl.FLOAT,false,20,0);gl.enableVertexAttribArray(a);gl.vertexAttribPointer(t,2,gl.FLOAT,false,20,12);gl.enableVertexAttribArray(t);}
 function tex(w,h,data){const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,w,h,0,gl.RGBA,gl.UNSIGNED_BYTE,data);return t;}
 const fbo=[0,1].map(()=>{const t=tex(SW,SH,null);const f=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,f);
  gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,t,0);return {f,t};});
 gl.bindFramebuffer(gl.FRAMEBUFFER,null);
 const I=[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1];
 const textures={};
 for(const k in texture){const bin=atob(texture[k]);const a=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);textures[k]=tex(W,H,a);}
 const out={renderer:gl.getParameter(gl.RENDERER)};
 for(const c of cases){
  const input=textures[c.tex];
  const covers=c.rect[0]<=0&&c.rect[1]<=0&&c.rect[0]+c.rect[2]>=1&&c.rect[1]+c.rect[3]>=1;
  const needBlur=c.mode==1&&!covers&&!c.old;
  if(needBlur){ // == GameLayoutFilterRender.renderBlur()
   gl.viewport(0,0,SW,SH);
   gl.bindFramebuffer(gl.FRAMEBUFFER,fbo[0].f);gl.useProgram(P.down);quad(P.down);
   gl.uniform4f(gl.getUniformLocation(P.down,'uCover'),...c.cover);gl.uniform2f(gl.getUniformLocation(P.down,'uStep'),1/SW,1/SH);
   gl.uniform1i(gl.getUniformLocation(P.down,'uSampler'),0);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,input);
   gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
   gl.useProgram(P.gauss);quad(P.gauss);gl.uniform1i(gl.getUniformLocation(P.gauss,'uSampler'),0);
   const uDir=gl.getUniformLocation(P.gauss,'uDir');
   for(let i=0;i<PASSES;i++){
    gl.bindFramebuffer(gl.FRAMEBUFFER,fbo[1].f);gl.bindTexture(gl.TEXTURE_2D,fbo[0].t);gl.uniform2f(uDir,1/SW,0);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    gl.bindFramebuffer(gl.FRAMEBUFFER,fbo[0].f);gl.bindTexture(gl.TEXTURE_2D,fbo[1].t);gl.uniform2f(uDir,0,1/SH);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
   }
  }
  gl.bindFramebuffer(gl.FRAMEBUFFER,null);gl.viewport(0,0,W,H);
  const p=c.old?P.old:P.main;gl.useProgram(p);quad(p);
  const U=n=>gl.getUniformLocation(p,n);
  gl.uniformMatrix4fv(U('uMVPMatrix'),false,I);gl.uniformMatrix4fv(U('uSTMatrix'),false,I);
  gl.uniform4f(U('uRect'),...c.rect);gl.uniform3f(U('uBgColor'),...(c.color||[0,0,0]));
  if(c.old){gl.uniform1f(U('uBgMode'),c.mode);gl.uniform4f(U('uCover'),...c.cover);gl.uniform2f(U('uTexel'),1/W,1/H);}
  else{gl.uniform1f(U('uBgMode'),(c.mode==1&&!needBlur)?0:c.mode);gl.uniform1f(U('uDim'),DIM);gl.uniform1i(U('uBlurTex'),1);
   gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,fbo[0].t);}
  gl.uniform1i(U('uSampler'),0);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,input);
  gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
  const px=new Uint8Array(W*H*4);gl.readPixels(0,0,W,H,gl.RGBA,gl.UNSIGNED_BYTE,px);
  // Ubah ke urutan baris dari atas.
  const top=new Uint8Array(W*H*4);for(let j=0;j<H;j++)top.set(px.subarray((H-1-j)*W*4,(H-j)*W*4),j*W*4);
  let s='';for(let i=0;i<top.length;i+=8192)s+=String.fromCharCode.apply(null,top.subarray(i,i+8192));
  out[c.name]=btoa(s);
 }
 return out;
}
"""

# Ukuran output uji = 9:16 (180x320, rasio sama dengan 720x1280). Tangkapan game landscape 1280x576.
W, H = 180, 320
SW, SH = small_size(W, H)
OUT, GAME = (720, 1280), (1280, 576)

def grid():
    i = np.arange(W); jt = np.arange(H)
    u = (i + .5) / W; v = (jt + .5) / H
    return np.meshgrid(u, v)  # [baris dari atas, kolom]

def to_tex(rgb):
    """rgb [H,W,3] baris dari atas -> bytes RGBA dengan baris 0 = bawah (konvensi FBO GL), base64."""
    a = np.concatenate([np.clip(np.round(rgb), 0, 255), np.full((H, W, 1), 255)], axis=2).astype(np.uint8)[::-1]
    return base64.b64encode(a.tobytes()).decode()

def texture_uv():
    """R = u, G = v (dari atas): untuk memeriksa posisi isi kotak game."""
    U, V = grid(); return to_tex(np.stack([U * 255, V * 255, U * 0], axis=2))

def texture_game():
    """Gambar mirip game: langit gradasi, matahari, tanah, HUD tajam, pola kotak kontras tinggi."""
    U, V = grid()
    img = np.stack([40 + 60 * V, 90 + 40 * V, 200 - 80 * V], axis=2)
    img[np.hypot((U - .75) * 2.2, V - .25) < .09] = (255, 210, 80)
    img[V > .68] = (50, 140, 70)
    img[(V > .82) & (((U * 40).astype(int) + (V * 40).astype(int)) % 2 == 0)] = (250, 250, 250)
    img[(.05 < V) & (V < .1) & (.03 < U) & (U < .25)] = (255, 255, 255)
    img[(.05 < V) & (V < .1) & (.03 < U) & (U < .18)] = (254, 44, 85)
    return to_tex(img)

def texture_flat(col): return to_tex(np.broadcast_to(np.array(col, float), (H, W, 3)).copy())

cover = cover_rect(*OUT, *GAME)
fit = content_rect(*OUT, *GAME)
cases = [
    dict(name='fit_center_black', tex='uv', rect=fit, mode=0),
    dict(name='top_zoom_color', tex='uv', rect=content_rect(*OUT, *GAME, zoom=1.2, cy=0.2), mode=2, color=[0.2, 0.4, 0.8]),
    dict(name='fill_blur', tex='uv', rect=content_rect(*OUT, *GAME, fit='FILL'), mode=1),
    dict(name='small_blur', tex='uv', rect=content_rect(*OUT, *GAME, zoom=0.6, cx=0.4, cy=0.7), mode=1),
    dict(name='flat_blur', tex='flat', rect=fit, mode=1),
    dict(name='game_new', tex='game', rect=fit, mode=1),
    dict(name='game_old', tex='game', rect=fit, mode=1, old=True),
]
for c in cases: c['cover'] = cover
FLAT = (200, 120, 40)
payload = dict(W=W, H=H, SW=SW, SH=SH, PASSES=PASSES, DIM=DIM, VERT=VERT, PVERT=PVERT,
               DOWN=DOWN, GAUSS=GAUSS, FRAG=FRAG, OLD=OLD_FRAG, cases=cases,
               texture=dict(uv=texture_uv(), game=texture_game(), flat=texture_flat(FLAT)))

try:
    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        chrome = '/home/user/.cache/ms-playwright/chromium-1217/chrome-linux64/chrome'
        kw = dict(executable_path=chrome) if os.path.exists(chrome) else {}
        br = pw.chromium.launch(headless=True, args=['--use-angle=swiftshader', '--enable-unsafe-swiftshader'], **kw)
        pg = br.new_page(); pg.set_content('<html><body></body></html>')
        pg.add_script_tag(content=JS)
        res = pg.evaluate('a=>run(a)', payload)
        br.close()
except ImportError:
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.chrome.service import Service
    opts = Options()
    for arg in ['--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--use-angle=swiftshader', '--enable-unsafe-swiftshader']:
        opts.add_argument(arg)
    if os.path.exists('/usr/bin/chromium'):
        opts.binary_location = '/usr/bin/chromium'
    svc = Service('/usr/bin/chromedriver') if os.path.exists('/usr/bin/chromedriver') else Service()
    drv = webdriver.Chrome(service=svc, options=opts)
    try:
        drv.get('data:text/html,<html><body></body></html>')
        res = drv.execute_script(JS + '\nreturn run(arguments[0]);', payload)
    finally:
        drv.quit()

print('WebGL renderer:', res['renderer'])
print('Output uji %dx%d, tekstur blur %dx%d, putaran Gaussian %d, kecerahan %.2f' % (W, H, SW, SH, PASSES, DIM))
fails = []
IMG = {c['name']: np.frombuffer(base64.b64decode(res[c['name']]), np.uint8).reshape(H, W, 4)[:, :, :3].astype(int) for c in cases}
PX, PY = np.meshgrid((np.arange(W) + .5) / W, (np.arange(H) + .5) / H)

def masks(rect, m=2):
    """(isi: jauh di dalam kotak game, latar: minimal m piksel di luar kotak)."""
    rx, ry, rw, rh = rect
    qx, qy = (PX - rx) / rw, (PY - ry) / rh
    margin = 1.5 / min(W * rw, H * rh)
    inner = (qx > margin) & (qx < 1 - margin) & (qy > margin) & (qy < 1 - margin)
    xs, ys = PX * W, PY * H
    near = (xs >= rx * W - m) & (xs <= (rx + rw) * W + m) & (ys >= ry * H - m) & (ys <= (ry + rh) * H + m)
    return inner, qx, qy, ~near

# 1. Isi kotak game & mode hitam/warna/blur-gelap.
checked = 0
for c in cases:
    if c['tex'] != 'uv': continue
    im = IMG[c['name']]; inner, qx, qy, bg = masks(c['rect'])
    bad = inner & ((np.abs(im[:, :, 0] / 255 - qx) > .03) | (np.abs(im[:, :, 1] / 255 - qy) > .03))
    checked += inner.sum() + bg.sum()
    if bad.any(): fails.append((c['name'], 'isi', int(bad.sum())))
    if c['mode'] == 0 and (im[bg] != 0).any(): fails.append((c['name'], 'hitam'))
    if c['mode'] == 2:
        exp = np.round(np.array(c['color']) * 255)
        if (np.abs(im[bg] - exp) > 1).any(): fails.append((c['name'], 'warna'))
    if c['mode'] == 1 and bg.any():
        b = im[bg]
        if (b[:, 2] > 1).any() or (b[:, :2] > DIM * 255 + 2).any(): fails.append((c['name'], 'blur-gelap'))
print('Pemeriksaan isi/latar: %d piksel, gagal %d' % (checked, len(fails)))

# 2. Warna rata-rata terjaga.
_, _, _, bgfit = masks(fit)
exp = np.round(np.array(FLAT) * DIM)
worst = int(np.abs(IMG['flat_blur'][bgfit] - exp).max())
print('Sumber seragam %s -> latar diharapkan %s, selisih maks %d' % (FLAT, exp.astype(int).tolist(), worst))
if worst > 2: fails.append(('flat_blur', 'warna-rata', worst))

# 2b. Posisi blur benar (tidak terbalik/bergeser): blur gradasi linear = gradasi itu sendiri.
# Sumber uv: R = bx, G = by pada posisi latar cover. Diperiksa jauh dari tepi frame (blur menjepit di tepi).
rx_, ry_, rw_, rh_ = cover
BX, BY = np.clip((PX - rx_) / rw_, 0, 1), np.clip((PY - ry_) / rh_, 0, 1)
_, _, _, bgs = masks(cases[3]['rect'], 4)
far = bgs & (PX > .2) & (PX < .8) & (PY > .15) & (PY < .85)
im = IMG['small_blur']
err = np.maximum(np.abs(im[:, :, 0] - DIM * 255 * BX), np.abs(im[:, :, 1] - DIM * 255 * BY))[far]
print('Posisi blur (gradasi): %d piksel, selisih maks %.1f' % (err.size, err.max()))
if err.size == 0 or err.max() > 4: fails.append(('small_blur', 'posisi', float(err.max()) if err.size else -1))

# 3. Kehalusan: selisih antar-piksel bertetangga di area latar.
_, _, _, bg3 = masks(fit, 3)
def smooth(name):
    im = IMG[name]
    dx = np.abs(im[:-1, 1:] - im[:-1, :-1]).max(axis=2); dy = np.abs(im[1:, :-1] - im[:-1, :-1]).max(axis=2)
    d = np.maximum(dx, dy)[bg3[:-1, :-1] & bg3[1:, 1:]]
    return d.mean(), int(d.max()), int((d >= 3).sum()), d.size
for nm, lbl in (('game_old', 'LAMA'), ('game_new', 'BARU')):
    a, m, k, n = smooth(nm)
    print('Blur %s: selisih tetangga rata-rata %.3f, maks %d, piksel dengan lompatan terlihat (>=3) %d dari %d' % (lbl, a, m, k, n))
_, old_max, old_steps, _ = smooth('game_old'); _, new_max, new_steps, _ = smooth('game_new')
if not new_max <= 3: fails.append(('game_new', 'tepi-tajam', new_max))
if not (old_steps > 0 and new_steps * 10 <= old_steps): fails.append(('game_new', 'kurang-halus', new_steps, old_steps))

try:
    from PIL import Image, ImageDraw
    def img(name): return Image.fromarray(IMG[name].astype(np.uint8)).resize((W * 2, H * 2), Image.BILINEAR)
    lama, baru = img('game_old'), img('game_new')
    lama.save(os.path.join(HERE, 'blur_lama.png')); baru.save(os.path.join(HERE, 'blur_baru.png'))
    band = Image.new('RGB', (W * 4 + 30, H * 2 + 40), (24, 24, 30)); band.paste(lama, (10, 30)); band.paste(baru, (W * 2 + 20, 30))
    dr = ImageDraw.Draw(band); dr.text((10, 8), 'Blur lama (v1.6.0)', fill=(230, 230, 230)); dr.text((W * 2 + 20, 8), 'Blur halus baru', fill=(230, 230, 230))
    band.save(os.path.join(HERE, 'blur_banding.png'))
except ImportError:
    pass

print('HASIL:', 'LULUS' if not fails else 'GAGAL %s' % fails[:10])
sys.exit(1 if fails else 0)

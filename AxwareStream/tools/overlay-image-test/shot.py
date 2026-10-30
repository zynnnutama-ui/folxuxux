"""Render halaman overlay (Browser Source) di Chromium sungguhan lalu simpan PNG transparan.
Pemakaian: python3 shot.py URL LEBAR TINGGI OUT.png [CHROME_PATH]"""
import sys, glob, os, time, base64, json, re, urllib.request, urllib.parse

url, w, h, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
exe = sys.argv[5] if len(sys.argv) > 5 else (sorted(glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux64/chrome"))) or [None])[-1]

try:
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        b = p.chromium.launch(headless=True, executable_path=exe)
        pg = b.new_page(viewport={"width": w, "height": h})
        pg.goto(url)
        pg.wait_for_function("document.querySelectorAll('img[data-fit]').length > 0 && Array.from(document.querySelectorAll('img[data-fit]')).every(i => i.complete && i.style.visibility === 'visible')", timeout=15000)
        pg.wait_for_timeout(600)  # transisi fade scene selesai
        pg.screenshot(path=out, omit_background=True)
        b.close()
except ImportError:
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.chrome.service import Service

    # Pre-fetch from local server via urllib in case Chromium has enterprise URLBlocklist on http://
    parsed = urllib.parse.urlparse(url)
    base_url = f"{parsed.scheme}://{parsed.netloc}"
    html = urllib.request.urlopen(url).read().decode("utf-8")
    state_json = urllib.request.urlopen(f"{base_url}/api/state").read().decode("utf-8")
    proj_json = urllib.request.urlopen(f"{base_url}/api/project").read().decode("utf-8")
    proj_obj = json.loads(proj_json)
    media_map = {}
    for sc in proj_obj.get("scenes", []):
        for el in sc.get("elements", []):
            m = el.get("media")
            if m and m not in media_map:
                try:
                    raw = urllib.request.urlopen(f"{base_url}/media/{urllib.parse.quote(m)}").read()
                    media_map[f"/media/{urllib.parse.quote(m)}"] = "data:image/png;base64," + base64.b64encode(raw).decode("ascii")
                except Exception:
                    pass

    shim = f"""<script>
    (function() {{
      var _state = {state_json};
      var _proj = {proj_json};
      var _media = {json.dumps(media_map)};
      var _search = {json.dumps(parsed.query and ('?' + parsed.query) or '')};
      var origUSP = window.URLSearchParams;
      window.URLSearchParams = function(s) {{ return new origUSP(s || _search); }};
      window.fetch = function(u) {{
        if (String(u).indexOf('/api/state') >= 0) return Promise.resolve({{ json: function() {{ return Promise.resolve(_state); }} }});
        if (String(u).indexOf('/api/project') >= 0) return Promise.resolve({{ json: function() {{ return Promise.resolve(_proj); }} }});
        return Promise.reject();
      }};
      window.EventSource = function() {{ return {{ addEventListener: function(){{}}, close: function(){{}} }}; }};
      var obs = new MutationObserver(function(muts) {{
        var imgs = document.querySelectorAll('img[data-fit]');
        for (var i = 0; i < imgs.length; i++) {{
          var src = imgs[i].getAttribute('src');
          if (_media[src]) imgs[i].setAttribute('src', _media[src]);
        }}
      }});
      obs.observe(document.documentElement, {{ childList: true, subtree: true }});
    }})();
    </script>"""
    injected_html = html.replace("<head>", "<head>" + shim, 1)
    data_url = "data:text/html;charset=utf-8;base64," + base64.b64encode(injected_html.encode("utf-8")).decode("ascii")

    opts = Options()
    for arg in ['--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--hide-scrollbars']:
        opts.add_argument(arg)
    if os.path.exists('/usr/bin/chromium'):
        opts.binary_location = '/usr/bin/chromium'
    elif exe and os.path.exists(exe):
        opts.binary_location = exe
    svc = Service('/usr/bin/chromedriver') if os.path.exists('/usr/bin/chromedriver') else Service()
    drv = webdriver.Chrome(service=svc, options=opts)
    try:
        drv.execute_cdp_cmd('Emulation.setDeviceMetricsOverride', {'width': w, 'height': h, 'deviceScaleFactor': 1, 'mobile': False})
        drv.execute_cdp_cmd('Emulation.setDefaultBackgroundColorOverride', {'color': {'r': 0, 'g': 0, 'b': 0, 'a': 0}})
        drv.get(data_url)
        deadline = time.time() + 15.0
        while time.time() < deadline:
            ready = drv.execute_script("return document.querySelectorAll('img[data-fit]').length > 0 && Array.from(document.querySelectorAll('img[data-fit]')).every(i => i.complete && i.style.visibility === 'visible');")
            if ready:
                break
            time.sleep(0.05)
        time.sleep(0.6)
        drv.execute_cdp_cmd('Emulation.setDefaultBackgroundColorOverride', {'color': {'r': 0, 'g': 0, 'b': 0, 'a': 0}})
        shot = drv.execute_cdp_cmd('Page.captureScreenshot', {'format': 'png', 'fromSurface': True})
        with open(out, 'wb') as f:
            f.write(base64.b64decode(shot['data']))
    finally:
        drv.quit()
print("OK", out)

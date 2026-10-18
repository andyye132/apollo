"""Render the local design pitch and audit route/layout coverage.

Requires Playwright and a local Chromium installation. All sample data is fictional.
Run with the bundled environment: /Users/andy132/miniconda3/bin/python render-pitch.py
"""
from pathlib import Path
import json
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'exports'
OUT.mkdir(exist_ok=True)
URL = (ROOT / 'index.html').as_uri()

with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    page = browser.new_page(viewport={'width': 1440, 'height': 1080}, device_scale_factor=2)
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto(URL)
    page.wait_for_function('window.apolloPitch && [...document.images].every(i=>i.complete && i.naturalWidth > 0)')
    screens = page.evaluate('window.apolloPitch.screens')
    (ROOT / 'screen-manifest.json').write_text(json.dumps(screens, indent=2))
    expression_map=page.evaluate('window.apolloPitch.expressionMap')
    assert all(screen['id'] in expression_map for screen in screens)
    (ROOT / 'assets/expressions/screen-expression-map.json').write_text(json.dumps(expression_map, indent=2))
    routes = page.locator('[data-go]').evaluate_all('(els)=>[...new Set(els.map(e=>e.dataset.go))]')
    known = {s['id'] for s in screens}
    missing = set(routes) - known
    assert not missing, f'Unknown navigation routes: {missing}'
    page.screenshot(path=str(OUT / '01-overview.png'), full_page=False)
    page.locator('#direction').screenshot(path=str(OUT / '02-brand-direction.png'))
    page.locator('#expression-board').screenshot(path=str(OUT / 'apollo-expression-board.png'))
    page.locator('#expression-application-board').screenshot(path=str(OUT / 'apollo-expressions-in-screens.png'))
    groups = page.evaluate('window.apolloPitch.groups')
    page.add_style_tag(content='.screen-controls{position:static!important}')
    for n, group in enumerate(groups, 3):
        page.locator(f'[data-group="{group["id"]}"]').screenshot(path=str(OUT / f'{n:02d}-{group["id"]}-board.png'))
    page.locator('.pitch-nav [data-open="today"]').click()
    assert page.locator('dialog[open] [data-screen="today"]').count() == 1
    page.locator('dialog [data-go="measure"]').first.click()
    assert page.locator('dialog [data-screen="measure"]').count() == 1
    page.locator('dialog [data-go="temp-intro"]').first.click()
    page.locator('dialog [data-go="temp-ready"]').click()
    page.locator('dialog [data-go="temp-prehold"]').click()
    page.locator('dialog [data-go="temp-contact"]').click()
    assert page.locator('dialog .contact-mode').count() == 1
    page.locator('#close-prototype').click()
    page.locator('[data-filter="heart"]').click()
    assert page.locator('.screen-group:visible').count() == 1
    page.locator('[data-filter="all"]').click()
    page.locator('#dark-toggle').check()
    assert page.locator('.phone.dark').count() == page.locator('.phone').count()
    page.locator('#dark-toggle').uncheck()
    overflow = []
    page.set_viewport_size({'width': 360, 'height': 780})
    for n, screen in enumerate(screens, 1):
        page.goto(f'{URL}?screen={screen["id"]}')
        page.wait_for_function('[...document.images].every(i=>i.complete && i.naturalWidth > 0)')
        assert page.locator('.phone .apollo-face').count() > 0, f'Missing expression on {screen["id"]}'
        info = page.locator('.screen').evaluate('(el)=>({client:el.clientHeight,scroll:el.scrollHeight,width:el.clientWidth,scrollWidth:el.scrollWidth})')
        if info['scroll'] > info['client'] + 2 or info['scrollWidth'] > info['width'] + 2:
            overflow.append({'screen': screen['id'], **info})
        page.locator('.phone').screenshot(path=str(OUT / f'screen-{n:02d}-{screen["id"]}.png'))
    for id in ['today', 'hr-recording', 'trends']:
        page.goto(f'{URL}?screen={id}&theme=dark')
        page.locator('.phone').screenshot(path=str(OUT / f'dark-{id}.png'))
    page.set_viewport_size({'width': 390, 'height': 844})
    page.goto(URL)
    page.wait_for_function('[...document.images].every(i=>i.complete && i.naturalWidth > 0)')
    responsive = page.evaluate('({viewport:innerWidth, width:document.documentElement.scrollWidth})')
    page.screenshot(path=str(OUT / 'mobile-pitch.png'), full_page=False)
    page.locator('.pitch-nav [data-open="today"]').click()
    mobile_dialog = page.locator('dialog').evaluate('(el)=>({client:el.clientWidth,scroll:el.scrollWidth})')
    page.locator('#close-prototype').click()
    page.set_viewport_size({'width': 320, 'height': 844})
    page.locator('.pitch-nav [data-open="today"]').click()
    small_dialog = page.locator('dialog').evaluate('(el)=>({client:el.clientWidth,scroll:el.scrollWidth})')
    page.locator('#close-prototype').click()
    page.set_viewport_size({'width': 1440, 'height': 1080})
    page.locator('#logo-lockup').screenshot(path=str(OUT / 'apollo-logo-lockup.png'))
    page.locator('.app-icon').evaluate("el=>{el.style.width='512px';el.style.height='512px';el.style.borderRadius='120px';const im=el.querySelector('img');im.style.width='448px';im.style.left='32px';im.style.top='32px';}")
    page.locator('.app-icon').screenshot(path=str(OUT / 'apollo-app-icon.png'))
    report = {'screens': len(screens), 'missing_routes': sorted(missing), 'console_errors': errors, 'overflow': overflow, 'mobile': responsive, 'mobile_dialog': mobile_dialog, 'small_dialog': small_dialog}
    (OUT / 'validation.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    browser.close()

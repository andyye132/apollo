"""Create a portable, offline HTML pitch and a zip of the complete design kit."""
from pathlib import Path
import base64
import json
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parent
out = root / 'exports'
manifest = json.loads((root / 'screen-manifest.json').read_text())
report = json.loads((out / 'validation.json').read_text())
assert not report['console_errors'], report
assert not report['overflow'], report
assert not report['missing_routes'], report
assert len(list(out.glob('screen-*.png'))) == len(manifest)

html = (root / 'index.html').read_text()
css = (root / 'styles.css').read_text()
js = (root / 'app.js').read_text()
asset = 'data:image/png;base64,' + base64.b64encode((root / 'assets/apollo-low-poly.png').read_bytes()).decode()
expression_assets = {f'assets/expressions/{path.name}': 'data:image/png;base64,' + base64.b64encode(path.read_bytes()).decode() for path in (root / 'assets/expressions').glob('apollo-*.png')}
assert len(expression_assets) == 6, 'All six expression assets must exist before packaging.'
html = html.replace('<link rel="stylesheet" href="styles.css">', f'<style>{css}</style>')
html = html.replace('src="assets/apollo-low-poly.png"', 'data-apollo-image')
js = js.replace('src="assets/apollo-low-poly.png"', 'src="${window.apolloAsset}"')
setup = f'window.apolloAsset={json.dumps(asset)};window.apolloAssets={json.dumps(expression_assets)};document.querySelectorAll("[data-apollo-image]").forEach(el=>el.src=window.apolloAsset);'
html = html.replace('<script src="app.js"></script>', f'<script>{setup}\n{js}</script>')
html = html.replace('<a href="exports/apollo-ui-pitch.zip" download>Download screen images ↗</a>', '<span>41 SCREENS · WORKS OFFLINE</span>')
standalone = out / 'apollo-ui-pitch.html'
standalone.write_text(html)

with ZipFile(out / 'apollo-ui-pitch.zip', 'w', ZIP_DEFLATED) as archive:
    archive.write(standalone, standalone.name)
    for path in sorted(out.glob('*.png')):
        archive.write(path, 'images/' + path.name)
    archive.write(root / 'screen-manifest.json', 'screen-manifest.json')
    archive.write(out / 'validation.json', 'validation.json')
    archive.write(root / 'assets/apollo-low-poly.png', 'brand/apollo-low-poly.png')
    archive.write(root / 'assets/generation-prompts.json', 'brand/generation-prompts.json')
    for path in sorted((root / 'assets/expressions').iterdir()):
        if path.is_file():
            archive.write(path, 'brand/expressions/' + path.name)

with ZipFile(out / 'apollo-expressions.zip', 'w', ZIP_DEFLATED) as archive:
    for path in sorted((root / 'assets/expressions').iterdir()):
        if path.is_file():
            archive.write(path, path.name)
    archive.write(out / 'apollo-expression-board.png', 'expression-board.png')
    archive.write(out / 'apollo-expressions-in-screens.png', 'expressions-in-screens.png')
print(f'Packaged {len(manifest)} screens, brand assets, boards, and the offline pitch.')
print(f'Archive: {(out / "apollo-ui-pitch.zip").stat().st_size / 1_000_000:.1f} MB')

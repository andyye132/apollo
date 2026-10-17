"""Render docs/DESIGN.md into docs/design-doc.html, one self-contained file.

Usage: python3 docs/build.py

DESIGN.md is the source of truth; edit it, then rebuild. The HTML embeds the Markdown and a
pinned copy of marked.js (docs/assets/marked.min.js, v12.0.2, MIT) so it opens offline.
"""
from datetime import date
from pathlib import Path

DOCS = Path(__file__).resolve().parent


def main():
    source = (DOCS / "DESIGN.md").read_text(encoding="utf-8")
    template = (DOCS / "assets" / "template.html").read_text(encoding="utf-8")
    marked = (DOCS / "assets" / "marked.min.js").read_text(encoding="utf-8")

    # A literal "</script" would end the inline <script> block early; "<\/script" is equivalent in JS
    # and the page's loader turns it back for the Markdown.
    html = (
        template.replace("/*__MARKED__*/", marked.replace("</script", "<\\/script"), 1)
        .replace("<!--__DESIGN_MD__-->", source.replace("</script", "<\\/script"), 1)
        .replace("__BUILT_AT__", date.today().isoformat(), 1)
    )
    out = DOCS / "design-doc.html"
    out.write_text(html, encoding="utf-8")
    print(f"wrote {out.relative_to(DOCS.parent)} ({len(html) // 1024} KB)")


if __name__ == "__main__":
    main()

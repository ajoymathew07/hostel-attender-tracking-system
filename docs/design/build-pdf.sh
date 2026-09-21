#!/usr/bin/env bash
# Build the submission PDF from the Markdown sources.
#
# Mermaid: rendered to PNG when mermaid-cli is available (MMDC=... or `npx mmdc`),
# otherwise left as a verbatim listing, which stays readable in print.
# Emoji and box-drawing glyphs: emoji are substituted for text since no LaTeX engine
# renders colour emoji; box-drawing is preserved via xelatex + a Unicode monospace font.
set -euo pipefail

cd "$(dirname "$0")"
OUT="${1:-design-doc.pdf}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SECTIONS=(01-overview.md 02-architecture.md 03-data-model.md 04-state-machines.md \
          05-ingestion-categorization.md 06-dispatch.md 07-ledger-concurrency.md \
          08-analytics.md 09-api-contracts.md 10-frontend.md 11-security-privacy.md \
          12-test-plan.md 13-timeline.md 14-decisions.md)

# --- emoji -> text, since no LaTeX engine renders colour emoji ---
emoji_strip() {
  # Substitutions MUST be width-preserving (1 codepoint -> 1 char) or the ASCII
  # wireframes in Sec. 10 lose their alignment. Table-only glyphs are exempt.
  sed -e 's/⚡/E/g'   -e 's/🔧/C/g'  -e 's/💧/R/g'  -e 's/🌐/N/g'  -e 's/🔴/!/g' \
      -e 's/🟡/~/g'   -e 's/🟢/./g'  -e 's/⚠️/!/g'  -e 's/⚠/!/g'   -e 's/⭐/*/g' \
      -e 's/📋/#/g'   -e 's/👤/@/g'  -e 's/☰/=/g'   -e 's/🔄/~/g'  -e 's/📥/v/g' \
      -e 's/📊/#/g'   -e 's/⚙/*/g'  -e 's/⏱/T/g'   -e 's/⏳/~/g'  -e 's/●/o/g'  \
      -e 's/▸/>/g'    -e 's/▶/>/g'   -e 's/⤺/</g'   -e 's/✓/Y/g'   -e 's/✗/N/g'  \
      -e 's/🔍/?/g'   -e 's/⏰/T/g' \
      -e 's/┏/+/g'    -e 's/┓/+/g'   -e 's/┗/+/g'   -e 's/┛/+/g'   -e 's/━/-/g'  \
      -e 's/┃/|/g' \
      -e 's/✅/[yes]/g' -e 's/❌/[no]/g' -e 's/§/Sec. /g' \
      -e 's/ₜ/_t/g'   -e 's/₋/-/g'   -e 's/⁶/^6/g'  -e 's/⁵/^5/g' \
      -e 's/‖/||/g'   -e 's/λ̂/L/g'
}

MERMAID_CMD=""
if [ -n "${MMDC:-}" ] && command -v "$MMDC" >/dev/null 2>&1; then
  MERMAID_CMD="$MMDC"
elif command -v mmdc >/dev/null 2>&1; then
  MERMAID_CMD="mmdc"
fi

if [ -n "$MERMAID_CMD" ]; then
  echo "==> Rendering Mermaid diagrams with $MERMAID_CMD"
else
  echo "==> mermaid-cli not found; diagrams will appear as code listings."
  echo "    For rendered diagrams: npm i -g @mermaid-js/mermaid-cli && make pdf"
fi

python3 - "$WORK" "$MERMAID_CMD" "${SECTIONS[@]}" <<'PY'
import hashlib, os, re, subprocess, sys
work, mermaid_cmd, *sections = sys.argv[1:]
parts, n = [], 0
for path in sections:
    text = open(path, encoding='utf-8').read()
    def repl(m):
        global n
        code = m.group(1)
        if not mermaid_cmd:
            return "```\n" + code + "\n```"
        n += 1
        src = os.path.join(work, f"d{n}.mmd"); png = os.path.join(work, f"d{n}.png")
        open(src, 'w', encoding='utf-8').write(code)
        try:
            subprocess.run([mermaid_cmd, "-i", src, "-o", png, "-b", "white", "-s", "2"],
                           check=True, capture_output=True)
            return f"\n![]({png})\n"
        except Exception:
            return "```\n" + code + "\n```"
    text = re.sub(r"```mermaid\n(.*?)\n```", repl, text, flags=re.S)
    parts.append(text)
open(os.path.join(work, "body.md"), 'w', encoding='utf-8').write("\n\n\\newpage\n\n".join(parts))
print(f"==> Assembled {len(sections)} sections" + (f", {n} diagrams rendered" if n else ""))
PY

emoji_strip < "$WORK/body.md" > "$WORK/body.clean.md"

# Pick the first installed font from each candidate list. The monospace font must
# carry box-drawing glyphs or the wireframes in Sec. 10 render as tofu.
pick_font() {
  for f in "$@"; do
    if fc-list "$f" 2>/dev/null | grep -q .; then echo "$f"; return; fi
  done
}
MONO="$(pick_font "DejaVu Sans Mono" "Menlo" "Consolas" "Courier New")"
MAIN="$(pick_font "Times New Roman" "TeX Gyre Termes" "Liberation Serif" "DejaVu Serif")"
SANS="$(pick_font "Helvetica" "TeX Gyre Heros" "Liberation Sans" "DejaVu Sans")"
echo "==> Fonts: main=${MAIN:-default} sans=${SANS:-default} mono=${MONO:-default}"

FONT_ARGS=()
[ -n "$MONO" ] && FONT_ARGS+=(-V monofont="$MONO" -V monofontoptions="Scale=0.70")
[ -n "$MAIN" ] && FONT_ARGS+=(-V mainfont="$MAIN")
[ -n "$SANS" ] && FONT_ARGS+=(-V sansfont="$SANS")

echo "==> Running pandoc (xelatex)"
pandoc metadata.yaml "$WORK/body.clean.md" \
  --pdf-engine=xelatex \
  --syntax-highlighting=tango \
  "${FONT_ARGS[@]}" \
  -o "$OUT"

echo "==> Built $OUT ($(du -h "$OUT" | cut -f1))"

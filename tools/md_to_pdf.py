"""
One-off fixture generator: convert src/main/resources/shop-policies.md into
src/main/resources/shop-policies.pdf, putting ONE "## " section per page.

Why one section per page: the RAG ingestion uses PagePdfDocumentReader, which
yields one Document per PDF page. One policy section per page => one clean,
self-contained chunk per section (same granularity we had with markdown-heading
chunking, now driven by the PDF layout).

Run:  python tools/md_to_pdf.py
"""
from pathlib import Path
from fpdf import FPDF

ROOT = Path(__file__).resolve().parents[1]
MD = ROOT / "src" / "main" / "resources" / "shop-policies.md"
PDF = ROOT / "src" / "main" / "resources" / "shop-policies.pdf"

text = MD.read_text(encoding="utf-8")

# Split into sections on "## " headings; keep the doc "# " title out of the sections.
sections = []
current = None
for line in text.splitlines():
    if line.startswith("## "):
        if current:
            sections.append(current)
        current = [line[3:].strip(), []]          # [heading, body-lines]
    elif line.startswith("# "):
        continue                                   # skip the doc title line
    elif current is not None:
        current[1].append(line)
if current:
    sections.append(current)

pdf = FPDF(format="A4", unit="mm")
pdf.set_auto_page_break(auto=True, margin=15)
pdf.set_margins(20, 20, 20)

for heading, body in sections:
    pdf.add_page()                                 # one section => one page => one chunk
    pdf.set_font("Helvetica", "B", 16)
    pdf.multi_cell(0, 10, heading)
    pdf.ln(2)
    pdf.set_font("Helvetica", "", 12)
    paragraph = " ".join(l.strip() for l in body if l.strip())
    # fpdf2 core fonts are latin-1; our policy text is ASCII so this is safe.
    pdf.multi_cell(0, 7, paragraph)

pdf.output(str(PDF))
print(f"Wrote {PDF} with {len(sections)} pages (sections: {[s[0] for s in sections]})")
